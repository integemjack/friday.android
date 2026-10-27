package com.integemjack.friday.proto

import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体的取与缓存（REMOTE.md §6：消息附件走 file.read、工具参数里的本地路径走 fs.read，每块最多 1 MB）。
 * 拉回来的按「源」确定性命名缓存到 cacheDir/media，第二次显示不再走网络；
 * 先写 .part 再改名（同 Qt RemoteClient::download），避免半截文件被当成完整的用。
 */

/** 一个能取回来的媒体源 */
sealed interface MediaSource {
    /** 缓存键（同一个源永远算出同一个） */
    val key: String
    val kind: String // image / video / audio / file
    val displayName: String

    /** 消息里的附件：relativePath 在服务端媒体目录之内 */
    data class Attachment(val attachment: MediaAttachment) : MediaSource {
        override val key get() = "media:${attachment.relativePath}"
        override val kind get() = attachment.kind.ifBlank {
            when {
                attachment.mimeType.startsWith("image/") -> "image"
                attachment.mimeType.startsWith("video/") -> "video"
                attachment.mimeType.startsWith("audio/") -> "audio"
                else -> "file"
            }
        }
        override val displayName get() = attachment.fileName.ifBlank { attachment.relativePath.substringAfterLast('/') }
    }

    /** 工具参数里的路径（analyze_image / analyze_video / transcribe_audio）：相对路径按会话工作目录拼 */
    data class ToolPath(
        override val kind: String,
        val path: String,
        val workingDirectory: String?,
    ) : MediaSource {
        override val key get() = "tool:$kind:${resolvedPath(path, workingDirectory)}"
        override val displayName get() = path.substringAfterLast('/').substringAfterLast('\\')
    }

    /** http(s) 直连的媒体 */
    data class Url(override val kind: String, val url: String) : MediaSource {
        override val key get() = "url:$url"
        override val displayName get() = url.substringAfterLast('/').substringBefore('?')
    }
}

/** 内嵌与缓存共用的拉取上限（同桌面版 stackMediaCache.CAP_BYTES）：超过的不拉，给提示 */
fun capBytesFor(kind: String): Long = when (kind) {
    "image" -> 24L * 1024 * 1024
    "video" -> 64L * 1024 * 1024
    "audio" -> 32L * 1024 * 1024
    else -> 24L * 1024 * 1024
}

/** 绝对路径（盘符 / UNC / POSIX 绝对 / ~）；相对的要拼上会话工作目录才能在服务端读到 */
fun isAbsolutePath(path: String): Boolean =
    Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(path) || path.startsWith("\\\\") ||
        path.startsWith("/") || path.startsWith("~")

/** 相对路径 → 会话工作目录下的绝对路径（同桌面版 joinUnder） */
fun resolvedPath(path: String, base: String?): String {
    if (isAbsolutePath(path) || base.isNullOrBlank()) return path
    return base.trimEnd('/', '\\') + "/" + path.trimStart('/', '\\')
}

/** 缓存文件名：md5(键) + 原扩展名（不含路径分隔符，服务端/本地都安全） */
fun cacheNameOf(key: String, fallbackExt: String = ""): String {
    val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    val ext = fallbackExt.lowercase().filter { it.isLetterOrDigit() }.take(8)
    return if (ext.isEmpty()) hex else "$hex.$ext"
}

/** 取回来的结果 */
sealed interface MediaResult {
    data class Ready(val file: File) : MediaResult
    data class Failed(val message: String) : MediaResult
    data class TooBig(val bytes: Long) : MediaResult
}

/** 扩展名 → MIME（系统播放器认的那几个，认不出的一律当二进制流） */
fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}

class MediaLoader(private val files: RemoteFiles, private val cacheDir: File) {

    /** 同一个源同时在拉的去重（一条长消息里几十张图不能各拉一遍） */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<MediaResult>>()

    fun cachedFile(source: MediaSource): File? {
        val file = File(cacheDir, cacheNameOf(source.key, extOf(source.displayName)))
        return if (file.isFile && file.length() > 0) file else null
    }

    /** 取一个媒体：本地缓存有就直接给，没有就分块拉回来存下 */
    suspend fun load(source: MediaSource): MediaResult {
        cachedFile(source)?.let { return MediaResult.Ready(it) }
        val existing = inFlight[source.key]
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<MediaResult>()
        val previous = inFlight.putIfAbsent(source.key, deferred)
        if (previous != null) return previous.await()
        val result = runCatching { download(source) }.getOrElse {
            MediaResult.Failed(it.message ?: "媒体加载失败")
        }
        inFlight.remove(source.key)
        deferred.complete(result)
        return result
    }

    private suspend fun download(source: MediaSource): MediaResult {
        cacheDir.mkdirs()
        val target = File(cacheDir, cacheNameOf(source.key, extOf(source.displayName)))
        val part = File(target.absolutePath + ".part")
        part.delete()
        val cap = capBytesFor(source.kind)
        try {
            when (source) {
                is MediaSource.Url -> downloadUrl(source, part, cap)
                else -> downloadRemote(source, part, cap)
            }
        } catch (e: TooBigException) {
            part.delete()
            return MediaResult.TooBig(e.bytes)
        } catch (e: Exception) {
            part.delete()
            return MediaResult.Failed(e.message ?: "拉不到这个媒体")
        }
        if (!part.isFile || part.length() == 0L) {
            part.delete()
            return MediaResult.Failed("远端这个文件是空的")
        }
        target.delete()
        if (!part.renameTo(target)) return MediaResult.Failed("写不进本地缓存")
        return MediaResult.Ready(target)
    }

    private suspend fun downloadRemote(source: MediaSource, part: File, cap: Long) {
        var offset = 0L
        var written = 0L
        while (true) {
            val chunk = when (source) {
                is MediaSource.Attachment -> files.readMediaChunk(source.attachment.relativePath, offset)
                is MediaSource.ToolPath -> files.readFileChunk(resolvedPath(source.path, source.workingDirectory), offset)
                is MediaSource.Url -> throw IllegalStateException("unreachable")
            }
            if (chunk.size > cap) throw TooBigException(chunk.size)
            part.appendBytes(chunk.data)
            written += chunk.data.size
            if (written > cap) throw TooBigException(written)
            offset += chunk.data.size
            if (chunk.eof || chunk.data.isEmpty()) break
        }
    }

    private fun downloadUrl(source: MediaSource.Url, part: File, cap: Long) {
        val connection = java.net.URL(source.url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            val length = connection.contentLengthLong
            if (length > cap) throw TooBigException(length)
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > cap) throw TooBigException(total)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    class TooBigException(val bytes: Long) : Exception("文件太大（${bytes / 1024 / 1024} MB），不在手机上看")

    companion object {
        fun extOf(name: String): String {
            val clean = name.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('\\')
            val dot = clean.lastIndexOf('.')
            return if (dot >= 0) clean.substring(dot + 1) else ""
        }
    }
}
