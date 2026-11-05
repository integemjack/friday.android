package com.integemjack.friday.wake

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/** 唤醒词 */
const val WAKE_WORD = "星期五"

/**
 * 语音唤醒的几个数（在 PC 上用三种中文合成声音、两种语速调出来的，见提交说明）：
 * - 16 kHz 单声道，每次读 100 毫秒；能量按 10 毫秒一帧算
 * - 静音门限：底噪（最近 3 秒里偏安静的那 10%）往上 [OPEN_ABOVE_FLOOR_DB] 才算有人说话，才喂模型（安静时不跑模型，省电）
 * - 「前面要停一下」：命中的「星」开头之前 100–350 毫秒要比这个词本身轻 [PAUSE_GAP_DB]，
 *   句子中间的「星期五」（今天是星期五、下星期五）就不算
 */
object WakeTuning {
    const val SAMPLE_RATE = 16_000
    const val CHUNK = 1_600
    const val FRAME = 160

    const val OPEN_ABOVE_FLOOR_DB = 9f
    const val MIN_SPEECH_DB = -62f
    const val FLOOR_WINDOW_CHUNKS = 30
    const val PRE_ROLL_CHUNKS = 6
    const val HANGOVER_CHUNKS = 15

    const val PAUSE_FROM_MS = 350
    const val PAUSE_TO_MS = 100
    const val WORD_MS = 600
    const val PAUSE_GAP_DB = 10f

    /** 叫醒一次以后这么久里不再叫（通话刚挂、回声） */
    const val COOLDOWN_MS = 4_000L

    const val KEYWORDS_THRESHOLD = 0.25f
    const val KEYWORDS_SCORE = 1.0f
}

/** 一段采样的能量（dBFS；全零是 -120） */
fun levelDb(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
    if (to <= from) return -120f
    var sum = 0.0
    for (i in from until to) sum += (samples[i] * samples[i]).toDouble()
    val mean = sum / (to - from)
    return if (mean <= 1e-12) -120f else (10 * log10(mean)).toFloat()
}

/** 百分位（同 numpy 的默认做法：线性插值） */
fun percentile(values: FloatArray, p: Float): Float {
    require(values.isNotEmpty())
    val sorted = values.sortedArray()
    val pos = (sorted.size - 1) * p / 100f
    val lo = floor(pos).toInt()
    val hi = ceil(pos).toInt()
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

/**
 * 静音门限：安静时不喂模型。按 100 毫秒一块推进：
 * - [Step.Start]：有人开口了——新开一段，先把前面 [WakeTuning.PRE_ROLL_CHUNKS] 块（开口前那一点）喂进去，再喂这块
 * - [Step.Feed]：还在说（或刚停，等 [WakeTuning.HANGOVER_CHUNKS] 块再收，好让模型把最后一个字认完）
 * - [Step.End]：这块喂完这段就收
 * - [Step.Idle]：安静，不喂
 *
 * 底噪取最近 3 秒里偏安静的那 10%：说话中间的停顿会把它压在低处；风扇、空调这种一直响的会被它吃掉，门就不开
 */
class EnergyGate {
    enum class Step { Idle, Start, Feed, End }

    private val recent = ArrayDeque<Float>()
    private var open = false
    private var quiet = 0

    var floorDb = -120f
        private set

    val isOpen: Boolean get() = open

    fun push(db: Float): Step {
        recent.addLast(db)
        while (recent.size > WakeTuning.FLOOR_WINDOW_CHUNKS) recent.removeFirst()
        floorDb = percentile(recent.toFloatArray(), 10f)
        val speech = db >= max(floorDb + WakeTuning.OPEN_ABOVE_FLOOR_DB, WakeTuning.MIN_SPEECH_DB)
        if (!open) {
            if (!speech) return Step.Idle
            open = true
            quiet = 0
            return Step.Start
        }
        quiet = if (speech) 0 else quiet + 1
        if (quiet >= WakeTuning.HANGOVER_CHUNKS) {
            open = false
            return Step.End
        }
        return Step.Feed
    }

    /** 暂停后（通话、播报）从头来：门关上，底噪重新学 */
    fun reset() {
        open = false
        quiet = 0
        recent.clear()
        floorDb = -120f
    }
}

/** 最近几秒每 10 毫秒一帧的能量，按绝对帧号取（帧号 = 采样号 / 160） */
class LevelHistory(private val capacity: Int = 400) {
    private val ring = FloatArray(capacity)
    var frames = 0L
        private set

    fun push(chunk: FloatArray, length: Int = chunk.size) {
        var i = 0
        while (i + WakeTuning.FRAME <= length) {
            ring[(frames % capacity).toInt()] = levelDb(chunk, i, i + WakeTuning.FRAME)
            frames++
            i += WakeTuning.FRAME
        }
    }

    /** [from, to) 这几帧里还留着的那些（太早的已经被盖掉了） */
    fun range(from: Long, to: Long): FloatArray {
        val start = max(from, max(0L, frames - capacity))
        val end = minOf(to, frames)
        if (end <= start) return FloatArray(0)
        return FloatArray((end - start).toInt()) { ring[((start + it) % capacity).toInt()] }
    }

    fun clear() {
        frames = 0
    }
}

/**
 * 「前面要停一下」：唤醒词从 [wordStartFrame] 开始；它前面 100–350 毫秒（大部分时间）要比这个词本身轻
 * [WakeTuning.PAUSE_GAP_DB] 以上。前面没有记录（刚开始听）就当停过
 */
fun pausedBefore(history: LevelHistory, wordStartFrame: Long): Boolean {
    val before = history.range(
        wordStartFrame - WakeTuning.PAUSE_FROM_MS / 10,
        wordStartFrame - WakeTuning.PAUSE_TO_MS / 10,
    )
    if (before.isEmpty()) return true
    val word = history.range(wordStartFrame, wordStartFrame + WakeTuning.WORD_MS / 10)
    if (word.isEmpty()) return false
    return percentile(before, 80f) <= percentile(word, 80f) - WakeTuning.PAUSE_GAP_DB
}

/** 模型认出来的一个词：[startSec] 是第一个音在这一段里的时间（秒） */
data class SpottedWord(val keyword: String, val startSec: Float?)

/** 认出来的词要不要叫醒：只认「星期五」（别的是用来挡近音的诱饵词），前面要停过一下 */
fun shouldWake(word: SpottedWord, segmentStartSample: Long, history: LevelHistory): Boolean {
    if (word.keyword != WAKE_WORD) return false
    val start = word.startSec ?: return false
    val frame = (segmentStartSample + (start * WakeTuning.SAMPLE_RATE).toLong()) / WakeTuning.FRAME
    return pausedBefore(history, frame)
}
