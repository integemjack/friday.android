package com.integemjack.friday.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** 关键词检测引擎（真机是 sherpa-onnx；测试里换假的） */
interface KeywordEngine {
    /** 新开一段（时间从这段第一个采样算起） */
    fun newSegment()

    /**
     * 喂一块 16 kHz 采样，返回认出来的词（没有是 null）。认出一个就停：这块剩下的不认了，
     * 调用方要 [newSegment] 从下一块重新开始（时间从那里算）
     */
    fun accept(samples: FloatArray): SpottedWord?

    fun release()
}

/** 麦克风（真机是 AudioRecord；测试里换假的） */
interface MicSource {
    /** 开始录；false = 开不了（没权限、被占着） */
    fun start(): Boolean

    /** 读满一块（阻塞约 100 毫秒）；返回读到的采样数，<= 0 是出错 */
    fun read(buffer: FloatArray): Int

    fun stop()
}

/** 这台手机上跑不了（不是 arm64、原生库加载不上） */
class EngineUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * sherpa-onnx 的关键词检测：模型在 assets/kws/（编译时从 sherpa-onnx 的发布页下的 zh-en 3M 模型，
 * chunk-8、int8），唤醒词表是 assets/kws/keywords.txt。单线程，骁龙 765G 上约是实时的 0.3 倍
 */
class SherpaEngine(context: Context) : KeywordEngine {
    private val spotter: KeywordSpotter
    private var stream: OnlineStream

    init {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = WakeTuning.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.onnx",
                    decoder = "kws/decoder.onnx",
                    joiner = "kws/joiner.onnx",
                ),
                tokens = "kws/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            keywordsFile = "kws/keywords.txt",
            keywordsScore = WakeTuning.KEYWORDS_SCORE,
            keywordsThreshold = WakeTuning.KEYWORDS_THRESHOLD,
            numTrailingBlanks = 1,
        )
        // 原生库只打了 arm64：别的 ABI 上类一加载就抛 UnsatisfiedLinkError（包在 ExceptionInInitializerError 里）
        spotter = try {
            KeywordSpotter(context.assets, config)
        } catch (e: Throwable) {
            throw EngineUnavailable("这台手机跑不了唤醒模型（${e.javaClass.simpleName}）", e)
        }
        stream = spotter.createStream("")
    }

    override fun newSegment() {
        stream.release()
        stream = spotter.createStream("")
    }

    override fun accept(samples: FloatArray): SpottedWord? {
        stream.acceptWaveform(samples, WakeTuning.SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            // 结果只能取一次（再取 timestamps 就空了）
            val result = spotter.getResult(stream)
            if (result.keyword.isNotBlank()) {
                spotter.reset(stream)
                return SpottedWord(result.keyword.trim(), result.timestamps.firstOrNull())
            }
        }
        return null
    }

    override fun release() {
        runCatching { stream.release() }
        runCatching { spotter.release() }
    }
}

/** AudioRecord：VOICE_RECOGNITION（不做通话那种回声消除 / 自动增益，适合识别），16 kHz 单声道 */
class AudioRecordMic : MicSource {
    private var record: AudioRecord? = null
    private var shorts = ShortArray(WakeTuning.CHUNK)

    @SuppressLint("MissingPermission") // 调用方查过 RECORD_AUDIO
    override fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(
            WakeTuning.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeTuning.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WakeTuning.CHUNK * 4 * 2),
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        if (runCatching { rec.startRecording() }.isFailure || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return false
        }
        record = rec
        return true
    }

    override fun read(buffer: FloatArray): Int {
        val rec = record ?: return -1
        if (shorts.size < buffer.size) shorts = ShortArray(buffer.size)
        var got = 0
        while (got < buffer.size) {
            val n = rec.read(shorts, got, buffer.size - got)
            if (n <= 0) return if (got > 0) got else n
            got += n
        }
        for (i in 0 until got) buffer[i] = shorts[i] / 32768f
        return got
    }

    override fun stop() {
        record?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        record = null
    }
}
