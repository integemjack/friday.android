package com.integemjack.friday.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 听写时底行那条音波的描述（读屏、测试认它） */
const val VOICE_WAVE_DESCRIPTION = "正在听写的音波"

/**
 * 听写时输入框底行原来写模型的地方：跟着麦克风音量往左滚的音波——每 [WAVE_TICK_MS] 毫秒取一次音量，
 * 新的一根从右边进来，一排竖条跟着往左挪。起得快、落得慢（看着不一闪一闪）；安静时是一排矮点。
 * 音量来自识别服务的 onRmsChanged（[VoiceInput.level]）：不报音量的识别服务上就一直是矮点。
 */
@Composable
fun VoiceWave(level: () -> Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val samples = remember { FloatArray(WAVE_SAMPLES) }
    // 写进去了几根：读它的是画的那一步，变了只重画、不重组
    var count by remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(level)
    LaunchedEffect(Unit) {
        var last = 0L
        var shown = 0f
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                if (now - last >= WAVE_TICK_MS) {
                    last = now
                    shown = waveStep(shown, current())
                    samples[count % WAVE_SAMPLES] = shown
                    count++
                }
            }
        }
    }
    Canvas(modifier.semantics { contentDescription = VOICE_WAVE_DESCRIPTION }) {
        val bar = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val bars = ((size.width + gap) / (bar + gap)).toInt().coerceIn(0, WAVE_SAMPLES)
        val written = count
        val middle = size.height / 2
        // 最右边是最新的一根，往左越来越早；还没写到的位置是矮点
        for (i in 0 until bars) {
            val index = written - 1 - i
            val value = if (index < 0) 0f else samples[index % WAVE_SAMPLES]
            val height = (size.height * (WAVE_FLOOR + (1f - WAVE_FLOOR) * value)).coerceAtLeast(bar)
            val x = size.width - (i + 1) * bar - i * gap
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * value),
                topLeft = Offset(x, middle - height / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** 音波下一根的高度（0–1）：比上一根高就直接跟上，低了慢慢落 */
internal fun waveStep(previous: Float, level: Float): Float {
    val target = level.coerceIn(0f, 1f)
    return if (target >= previous) target else previous * 0.65f + target * 0.35f
}

/** 取一次音量的间隔（一根竖条往左挪一格的时间） */
const val WAVE_TICK_MS = 60L

/** 最多记这么多根（够铺满横屏的底行） */
private const val WAVE_SAMPLES = 160

/** 安静时竖条占高度的比例 */
private const val WAVE_FLOOR = 0.12f
