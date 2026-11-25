package com.integemjack.friday.speech

import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地流式语音识别（2026-11-25 用户：「改成本地流式」「识别增加标点符号」）：输入框的语音键在手机上自己录音、
 * 边说边出字、带标点，不经过系统语音识别（小米自带的不报音量、不给中间结果，说完一整句才从服务器回来）。
 *
 * 引擎是 App 里本来就带的 sherpa-onnx（语音唤醒用的同一个库）的流式识别；模型是 sherpa-onnx 的 x-asr 中英流式
 * （zipformer transducer，int8，自带标点），第一次用时从 sherpa-onnx 的 GitHub 发布页下载、解开放到
 * filesDir/asr/<模型名>（[LocalAsrModel]），装不下就不装（不塞进安装包：安装包每次更新都要整个下载）。
 */
object LocalAsrModel {
    /**
     * 选的哪个：160 毫秒一块（字一个个出：一句话变二十几次；中文和 480 毫秒的一样准，英文差一点，算力多一倍，手机上够）。
     * 2026-11-25 在 Mac 上拿五句中文录音比过：x-asr 160ms 中文全对、带逗号问号，第一个字开口后约 1 秒；
     * 480ms 一样准但两三个字一跳；小 CTC（20 MB）不认英文、没标点
     */
    const val NAME = "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$NAME.tar.bz2"

    /** 大概多大（下载前没拿到长度时算进度用） */
    const val APPROX_BYTES = 133_898_007L
    const val SAMPLE_RATE = 16_000
}

/** 解开后要用的几个文件（绝对路径） */
data class AsrModelFiles(val encoder: String, val decoder: String, val joiner: String, val tokens: String)

/**
 * 模型的下载、解压、找文件。[root] 是 filesDir/asr：一个模型一个文件夹，里面有 [READY] 才算装好
 * （下到一半、解到一半的不认，下次重来）。
 */
class LocalAsrModels(private val root: File, private val name: String = LocalAsrModel.NAME, private val url: String = LocalAsrModel.URL) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val read: Long, val total: Long) : State {
            val fraction: Float get() = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
        data object Unpacking : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val dir get() = File(root, name)
    private val _state = MutableStateFlow(if (files() != null) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 装好了的话那几个文件 */
    fun files(): AsrModelFiles? {
        if (!File(dir, READY).isFile) return null
        return modelFiles(dir.listFiles()?.map { it.name }.orEmpty())?.let { (encoder, decoder, joiner, tokens) ->
            AsrModelFiles(File(dir, encoder).path, File(dir, decoder).path, File(dir, joiner).path, File(dir, tokens).path)
        }
    }

    /** 装好的占多大 */
    fun installedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 下载用的连接（测试里换成本地的）：先探一下多大、认不认分段，再按段打开 */
    interface Fetcher {
        /** 整个文件多大（不知道是 -1）、能不能按 Range 分段下 */
        fun probe(url: String): Probe

        /** 打开 [from, to] 这一段（都含；[to] 是 -1 时到结尾） */
        fun open(url: String, from: Long, to: Long): InputStream
    }

    data class Probe(val total: Long, val ranges: Boolean)

    /**
     * 下载并解开（阻塞，在后台线程调；同时只下一份：已经在下就直接回）。
     *
     * GitHub 的下载按连接限速（2026-11-25 这边一条连接约 60 KB/s，六条加起来约 360 KB/s）：认分段时分成 [connections] 段同时下。
     * 每段断了自己重试几次、接着下；还是不行这次就算没下好，下到的留着（.partN），下次按段接着下——一百多 MB 在慢的网上不用从头来。
     * 解不开（下坏了）就全删掉，下次从头来
     */
    fun download(fetcher: Fetcher = HttpFetcher, connections: Int = CONNECTIONS) {
        synchronized(this) {
            val now = _state.value
            if (now is State.Downloading || now is State.Unpacking || now is State.Ready) return
            _state.value = State.Downloading(0, LocalAsrModel.APPROX_BYTES)
        }
        var finished = false
        var unpacking = false
        try {
            root.mkdirs()
            val probe = fetcher.probe(url)
            val plan = segments(probe, connections)
            // 上次的分段跟这次对不上（服务器上的文件换了、不认分段了）：不能接着下
            val meta = File(root, "$name.parts")
            val signature = "${probe.total}:${plan.size}:${probe.ranges}"
            if (!probe.ranges || !meta.isFile || meta.readText() != signature) {
                clearParts()
                meta.writeText(signature)
            }
            val parts = plan.indices.map { File(root, "$name.part$it") }
            val done = AtomicLong(parts.sumOf { if (it.isFile) it.length() else 0L })
            val total = if (probe.total > 0) probe.total else LocalAsrModel.APPROX_BYTES
            _state.value = State.Downloading(done.get(), total)
            val failure = AtomicReference<Throwable?>(null)
            val workers = plan.mapIndexed { i, range ->
                thread(name = "friday-asr-download-$i") {
                    try {
                        fetchSegment(fetcher, range, parts[i], done, failure)
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }
            }
            while (workers.any { it.isAlive }) {
                workers.first { it.isAlive }.join(300)
                _state.value = State.Downloading(done.get(), total)
            }
            failure.get()?.let { throw it as? IOException ?: IOException(it.message, it) }

            _state.value = State.Unpacking
            unpacking = true
            val target = dir
            target.deleteRecursively()
            target.mkdirs()
            java.io.SequenceInputStream(java.util.Collections.enumeration(parts.map { it.inputStream() })).use { unpackModel(it, target) }
            if (modelFiles(target.listFiles()?.map { it.name }.orEmpty()) == null) {
                throw IOException("下载的模型里少了文件")
            }
            File(target, READY).writeText(name)
            finished = true
            _state.value = State.Ready
        } catch (e: IOException) {
            failed(e)
        } catch (e: RuntimeException) {
            failed(e)
        } finally {
            // 下到一半断了的留着下次接着下；下完了（解没解开都）不留：解不开说明下坏了，下次从头来
            if (finished || unpacking) clearParts()
        }
    }

    private fun failed(e: Exception) {
        dir.deleteRecursively()
        _state.value = State.Failed("本地语音识别模型没下好：${e.message ?: e.javaClass.simpleName}")
    }

    /** 一段：已经有多少就从哪接着要；断了重试几次（没进展才算一次），别的段已经失败了就不白下 */
    private fun fetchSegment(fetcher: Fetcher, range: LongRange, part: File, done: AtomicLong, failure: AtomicReference<Throwable?>) {
        val length = if (range.last >= 0) range.last - range.first + 1 else -1L
        var misses = 0
        while (failure.get() == null) {
            val have = if (part.isFile) part.length() else 0L
            if (length >= 0 && have >= length) {
                if (have > length) throw IOException("分段长度不对")
                return
            }
            try {
                fetcher.open(url, range.first + have, range.last).use { input ->
                    java.io.FileOutputStream(part, true).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (failure.get() == null) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done.addAndGet(n.toLong())
                        }
                    }
                }
                // 不认分段：读到结尾就是下完了
                if (length < 0) return
            } catch (e: IOException) {
                if (failure.get() != null) return
                if ((if (part.isFile) part.length() else 0L) > have) misses = 0 else misses++
                if (misses >= RETRIES) throw e
                Thread.sleep(1_000L * misses)
            }
        }
    }

    private fun clearParts() {
        root.listFiles()?.filter { it.name.startsWith("$name.part") }?.forEach { it.delete() }
    }

    /** 删掉（设置里点的；下次用语音键再下） */
    fun delete() {
        dir.deleteRecursively()
        clearParts()
        File(root, "$name.parts").delete()
        _state.value = State.Missing
    }

    companion object {
        /** 解完了的标记 */
        const val READY = ".ready"

        /** 同时下几段 */
        const val CONNECTIONS = 4

        /** 一段连着几次没进展就算这次没下好 */
        const val RETRIES = 3

        /** 分段：认 Range、够大才分（小于 8 MB 一段）；不认的一段从头到尾 */
        internal fun segments(probe: Probe, connections: Int): List<LongRange> {
            if (!probe.ranges || probe.total <= 0) return listOf(0L..-1L)
            val count = minOf(connections.coerceAtLeast(1).toLong(), (probe.total / (8L * 1024 * 1024)).coerceAtLeast(1L)).toInt()
            val size = (probe.total + count - 1) / count
            return (0 until count).map { i -> (i * size)..minOf(probe.total, (i + 1) * size) - 1 }
        }
    }
}

/** 按 HTTP 下（GitHub 的下载地址会跳到 release-assets，HttpURLConnection 跟着跳、Range 照带） */
object HttpFetcher : LocalAsrModels.Fetcher {
    private fun connect(url: String, range: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (range != null) conn.setRequestProperty("Range", "bytes=$range")
        return conn
    }

    override fun probe(url: String): LocalAsrModels.Probe {
        val conn = connect(url, "0-0")
        try {
            return when (conn.responseCode) {
                206 -> LocalAsrModels.Probe(conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L, true)
                200 -> LocalAsrModels.Probe(conn.contentLengthLong, false)
                else -> throw IOException("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    override fun open(url: String, from: Long, to: Long): InputStream {
        val whole = from == 0L && to < 0
        val conn = connect(url, if (whole) null else "$from-${if (to >= 0) to.toString() else ""}")
        val code = conn.responseCode
        if (code == 206 || (code == 200 && whole)) return conn.inputStream
        conn.disconnect()
        throw IOException(if (code == 200) "服务器不认分段下载" else "HTTP $code")
    }
}

/**
 * sherpa-onnx 发布的模型包（tar.bz2）里取要用的：*.onnx 和 tokens.txt，去掉包里的目录层级，放平到 [into]；
 * 测试录音（test_wavs/）、说明这些不要
 */
internal fun unpackModel(tarBz2: InputStream, into: File) {
    TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(tarBz2))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.substringAfterLast('/')
            val wanted = name == "tokens.txt" || (name.endsWith(".onnx") && "/test" !in "/${entry.name}")
            if (!wanted || name.startsWith(".")) continue
            File(into, name).outputStream().use { tar.copyTo(it) }
        }
    }
}

/**
 * 解开的文件里认出 encoder / decoder / joiner / tokens（同一样有 int8 和不带的，用 int8 的：小一半、手机上快）；
 * 少了哪个就是 null
 */
internal fun modelFiles(names: List<String>): List<String>? {
    fun pick(part: String): String? {
        val all = names.filter { it.endsWith(".onnx") && part in it }
        return all.firstOrNull { it.endsWith(".int8.onnx") } ?: all.firstOrNull()
    }
    val encoder = pick("encoder") ?: return null
    val decoder = pick("decoder") ?: return null
    val joiner = pick("joiner") ?: return null
    if ("tokens.txt" !in names) return null
    return listOf(encoder, decoder, joiner, "tokens.txt")
}

/** 一次听写用的流：喂音频、取正在说的字、看一句说完没有 */
interface AsrSession {
    fun accept(samples: FloatArray, count: Int)

    /** 这一句到现在认出的字（带标点；还在说时末尾的标点可能还没有） */
    fun text(): String

    /** 一句说完了（停顿够长）：取完字以后 [reset] 开下一句 */
    fun endpoint(): Boolean
    fun reset()

    /** 不说了：把剩下的音频认完，回这一句最后的字 */
    fun finish(): String
    fun release()
}

/** 加载好的识别器（一个 App 共用一份；建流很快，加载模型要一两秒） */
fun interface AsrEngine {
    fun session(): AsrSession
}

/**
 * sherpa-onnx 的流式识别器，加载一次留着：[LocalAsrEngines.IDLE_RELEASE_MS] 没人用就放掉（模型在内存里一百多 MB）。
 * 都在调用方的后台线程上用；同一时刻只有一路听写
 */
class LocalAsrEngines(private val models: LocalAsrModels) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: OnlineRecognizer? = null
    private var loadedFrom: AsrModelFiles? = null
    private val idle = Runnable { synchronized(this) { releaseLocked() } }

    /** 模型装好了就加载（阻塞，后台线程调）；没装好、加载失败回 null */
    fun acquire(): AsrEngine? = synchronized(this) {
        main.removeCallbacks(idle)
        val files = models.files() ?: return null
        if (recognizer == null || loadedFrom != files) {
            releaseLocked()
            recognizer = runCatching { OnlineRecognizer(null, config(files)) }.getOrNull() ?: return null
            loadedFrom = files
        }
        val loaded = recognizer ?: return null
        AsrEngine { SherpaSession(loaded) }
    }

    /** 这次听写完了：过一阵没人再用就放掉 */
    fun done() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_RELEASE_MS)
    }

    private fun releaseLocked() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedFrom = null
    }

    companion object {
        const val IDLE_RELEASE_MS = 180_000L

        private fun config(files: AsrModelFiles) = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = LocalAsrModel.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = files.encoder, decoder = files.decoder, joiner = files.joiner),
                tokens = files.tokens,
                numThreads = 2,
                provider = "cpu",
            ),
            // 一句说完：说过话之后停 0.8 秒；一直没说话 2.4 秒也重开一句（别让一句里攒太多静音）；一句最长 20 秒
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
    }
}

private class SherpaSession(private val recognizer: OnlineRecognizer) : AsrSession {
    private var stream: OnlineStream = recognizer.createStream("")

    override fun accept(samples: FloatArray, count: Int) {
        stream.acceptWaveform(if (count == samples.size) samples else samples.copyOf(count), LocalAsrModel.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    override fun text(): String = recognizer.getResult(stream).text

    override fun endpoint(): Boolean = recognizer.isEndpoint(stream)

    override fun reset() = recognizer.reset(stream)

    override fun finish(): String {
        // 末尾补一点静音再收尾：最后一个字认得出来
        stream.acceptWaveform(FloatArray(LocalAsrModel.SAMPLE_RATE * 3 / 10), LocalAsrModel.SAMPLE_RATE)
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return text()
    }

    override fun release() = stream.release()
}
