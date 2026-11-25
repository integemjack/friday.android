package com.integemjack.friday.speech

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 本地识别模型：下载、从 sherpa-onnx 的 tar.bz2 里取要用的文件、认 int8 的、没下完的不认 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAsrModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun archive(vararg files: Pair<String, Int>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            // sherpa-onnx 的包名超过 100 字节：同真包，用 GNU 长名
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            for ((name, size) in files) {
                val entry = TarArchiveEntry(name)
                entry.size = size.toLong()
                tar.putArchiveEntry(entry)
                // 随机的字节：压不小（不然整个包只有几百字节，分不出段、断不到半路）
                tar.write(ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) })
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val pack = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"

    /**
     * 假的下载：认不认分段、每段第一次在第 [dropAfter] 个字节断掉（模拟网断了）；[deadSegments] 里的段一直连不上。
     * 记下每次打开的是哪一段
     */
    private class FakeFetcher(
        private val data: ByteArray,
        private val ranges: Boolean = true,
        private val dropAfter: Int = -1,
        private val deadSegments: Set<Long> = emptySet(),
    ) : LocalAsrModels.Fetcher {
        val opened = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        private val dropped = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        override fun probe(url: String) = LocalAsrModels.Probe(data.size.toLong(), ranges)
        override fun open(url: String, from: Long, to: Long): java.io.InputStream {
            opened += from to to
            if (deadSegments.any { from >= it && from < it + 1 }) throw java.io.IOException("连不上")
            val end = if (to >= 0) to.toInt() + 1 else data.size
            val drop = dropAfter >= 0 && dropped.add(from)
            return object : java.io.InputStream() {
                var at = from.toInt()
                var sent = 0
                override fun read(): Int {
                    if (at >= end) return -1
                    if (drop && sent >= dropAfter) throw java.io.IOException("连接断了")
                    sent++
                    return data[at++].toInt() and 0xff
                }
            }
        }
    }

    private fun fullModel() = archive(
        "$pack/encoder.int8.onnx" to 4000, "$pack/encoder.onnx" to 9000,
        "$pack/decoder.onnx" to 1000, "$pack/joiner.int8.onnx" to 500,
        "$pack/tokens.txt" to 100, "$pack/test_wavs/0.wav" to 3000, "$pack/README.md" to 50,
    )

    @Test
    fun `下好解开：只留模型文件、放平、用 int8 的，装好了才认`() {
        val root = folder.newFolder("asr")
        val models = LocalAsrModels(root, name = "m", url = "https://example.invalid/m.tar.bz2")
        assertEquals(LocalAsrModels.State.Missing, models.state.value)
        assertNull(models.files())
        val fetcher = FakeFetcher(fullModel())
        models.download(fetcher)
        assertEquals(LocalAsrModels.State.Ready, models.state.value)
        val files = models.files()!!
        assertTrue(files.encoder.endsWith("/m/encoder.int8.onnx"))
        assertTrue(files.decoder.endsWith("/m/decoder.onnx"))
        assertTrue(files.joiner.endsWith("/m/joiner.int8.onnx"))
        assertTrue(files.tokens.endsWith("/m/tokens.txt"))
        val kept = File(root, "m").list()!!.toSet()
        assertFalse("测试录音、说明不要：$kept", kept.any { it.endsWith(".wav") || it.endsWith(".md") })
        assertFalse("分段的临时文件删掉了", root.list()!!.any { it.startsWith("m.part") })
        assertEquals(4000L + 9000 + 1000 + 500 + 100 + File(root, "m/${LocalAsrModels.READY}").length(), models.installedBytes())

        // 装好了再下：不动
        models.download(object : LocalAsrModels.Fetcher {
            override fun probe(url: String) = error("不该再下")
            override fun open(url: String, from: Long, to: Long) = error("不该再下")
        })
        assertEquals(LocalAsrModels.State.Ready, models.state.value)
        // 删掉：回到没装
        models.delete()
        assertEquals(LocalAsrModels.State.Missing, models.state.value)
        assertNull(models.files())
    }

    @Test
    fun `分段：够大才分、按连接数分、不认分段的一段到底`() {
        val mb = 1024L * 1024
        assertEquals(listOf(0L..-1L), LocalAsrModels.segments(LocalAsrModels.Probe(133 * mb, false), 4))
        assertEquals(listOf(0L..(5 * mb - 1)), LocalAsrModels.segments(LocalAsrModels.Probe(5 * mb, true), 4))
        val four = LocalAsrModels.segments(LocalAsrModels.Probe(133 * mb, true), 4)
        assertEquals(4, four.size)
        assertEquals(0L, four.first().first)
        assertEquals(133 * mb - 1, four.last().last)
        four.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun `几段同时下，断了的段自己接着下`() {
        val root = folder.newFolder("asr")
        val models = LocalAsrModels(root, name = "m", url = "u")
        val data = fullModel()
        val fetcher = FakeFetcher(data, dropAfter = 300)
        // 包很小：按连接数硬分 3 段看看（真包小于 8 MB 不分）
        models.download(fetcher, connections = 3)
        assertEquals(LocalAsrModels.State.Ready, models.state.value)
        assertTrue("断了又从断的地方接着要：${fetcher.opened}", fetcher.opened.any { (from, _) -> from == 300L })
    }

    @Test
    fun `一段一直连不上：这次没下好，下到的留着下次接着下；下完解不开全删掉从头来`() {
        val root = folder.newFolder("asr")
        val models = LocalAsrModels(root, name = "m", url = "u")
        val data = fullModel()
        // 不认分段、半路一直断：没下好，留下的不算（下次从头来）
        models.download(FakeFetcher(data, ranges = false, dropAfter = data.size / 2, deadSegments = emptySet()).let { f ->
            object : LocalAsrModels.Fetcher {
                override fun probe(url: String) = f.probe(url)
                override fun open(url: String, from: Long, to: Long): java.io.InputStream =
                    if (from == 0L) f.open(url, from, to) else throw java.io.IOException("不认分段")
            }
        })
        // 第一回半路断了，接着要（from > 0）不行：重试几次后算没下好
        assertTrue(models.state.value is LocalAsrModels.State.Failed)
        assertNull(models.files())
        assertFalse(File(root, "m").exists())

        // 认分段：下到一半断了、重试也连不上 → 留着；下次只要剩下的
        val cut = (data.size / 2).toLong()
        val dead = FakeFetcher(data)
        val half = object : LocalAsrModels.Fetcher {
            override fun probe(url: String) = dead.probe(url)
            override fun open(url: String, from: Long, to: Long): java.io.InputStream {
                if (from >= cut) throw java.io.IOException("连不上")
                return dead.open(url, from, minOf(to, cut - 1))
            }
        }
        models.download(half)
        assertTrue("${models.state.value} ${dead.opened}", models.state.value is LocalAsrModels.State.Failed)
        assertEquals(cut, File(root, "m.part0").length())
        val rest = FakeFetcher(data)
        models.download(rest)
        assertEquals(LocalAsrModels.State.Ready, models.state.value)
        assertEquals("只要剩下的：${rest.opened}", cut, rest.opened.single().first)

        // 下完了却解不开（下坏了）：不留，下次从头来
        models.delete()
        val broken = data.copyOf(data.size / 2)
        models.download(FakeFetcher(broken))
        assertTrue(models.state.value is LocalAsrModels.State.Failed)
        assertFalse(File(root, "m").exists())
        assertFalse(root.list()!!.any { it.startsWith("m.part") })
        // 包里少了 joiner
        val missing = archive("$pack/encoder.int8.onnx" to 10, "$pack/decoder.onnx" to 10, "$pack/tokens.txt" to 10)
        models.download(FakeFetcher(missing))
        assertTrue((models.state.value as LocalAsrModels.State.Failed).message.contains("少了文件"))
        models.download(FakeFetcher(data))
        assertEquals(LocalAsrModels.State.Ready, models.state.value)
    }

    @Test
    fun `认文件：int8 优先，少一样就不行`() {
        assertEquals(
            listOf("encoder-x.int8.onnx", "decoder-x.onnx", "joiner-x.int8.onnx", "tokens.txt"),
            modelFiles(listOf("encoder-x.onnx", "encoder-x.int8.onnx", "decoder-x.onnx", "joiner-x.int8.onnx", "joiner-x.onnx", "tokens.txt")),
        )
        assertNull(modelFiles(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx")))
        assertNull(modelFiles(listOf("encoder.onnx", "joiner.onnx", "tokens.txt")))
    }
}
