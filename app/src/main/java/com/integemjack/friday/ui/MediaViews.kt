package com.integemjack.friday.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.integemjack.friday.proto.MediaResult
import com.integemjack.friday.proto.MediaSource
import com.integemjack.friday.proto.mimeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 媒体在界面上的四种样子 */
sealed interface MediaUi {
    data object Loading : MediaUi
    data class ImageReady(val bitmap: Bitmap, val file: File) : MediaUi
    data class FileReady(val file: File) : MediaUi
    data class Failed(val message: String) : MediaUi
    data class TooBig(val bytes: Long) : MediaUi
}

/** 解码后的位图缓存（按文件路径 + 大小，最多占 1/8 可用堆） */
private object Bitmaps {
    private val cache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(file: File, maxDim: Int = 2048): Bitmap? {
        val key = "${file.absolutePath}:${file.length()}"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}

/** 拉一个媒体（缓存优先），图片顺带解码 */
@Composable
fun rememberMedia(source: MediaSource?, decodeImage: Boolean): MediaUi {
    val model = LocalAppModel.current
    val state = remember(source?.key, decodeImage) { mutableStateOf<MediaUi>(MediaUi.Loading) }
    LaunchedEffect(source?.key, decodeImage) {
        val current = source
        if (current == null) {
            state.value = MediaUi.Failed("没有媒体源")
            return@LaunchedEffect
        }
        state.value = when (val result = model.media.load(current)) {
            is MediaResult.Ready -> {
                if (decodeImage && current.kind == "image") {
                    val bitmap = withContext(Dispatchers.IO) { Bitmaps.get(result.file) }
                    if (bitmap != null) MediaUi.ImageReady(bitmap, result.file)
                    else MediaUi.FileReady(result.file)
                } else MediaUi.FileReady(result.file)
            }
            is MediaResult.Failed -> MediaUi.Failed(result.message)
            is MediaResult.TooBig -> MediaUi.TooBig(result.bytes)
        }
    }
    return state.value
}

/** 聊天里的图片：拉回来显示，点开看大图 */
@Composable
fun RemoteImage(
    source: MediaSource,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val state = rememberMedia(source, decodeImage = true)
    var fullScreen by remember(source.key) { mutableStateOf<File?>(null) }
    Column(modifier) {
        when (state) {
            is MediaUi.ImageReady -> {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = caption ?: source.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { fullScreen = state.file },
                )
            }
            is MediaUi.FileReady -> MediaChip(source, state.file, Modifier.fillMaxWidth())
            MediaUi.Loading -> MediaPlaceholder("${source.displayName} 加载中…")
            is MediaUi.Failed -> MediaPlaceholder("${source.displayName}：${state.message}", error = true)
            is MediaUi.TooBig -> MediaPlaceholder(
                "${source.displayName} 太大（${state.bytes / 1024 / 1024} MB），不在手机上看",
                error = true,
            )
        }
        if (!caption.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    fullScreen?.let { file -> ImageLightbox(file, source.displayName) { fullScreen = null } }
}

/** 非图片的媒体（音频 / 视频 / 文件）：一行卡片，点了下载到本机再用系统播放器打开 */
@Composable
fun MediaChip(source: MediaSource, file: File? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val model = LocalAppModel.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember(source.key) { mutableStateOf(false) }
    val icon = when (source.kind) {
        "audio" -> Icons.Filled.Audiotrack
        "video" -> Icons.Filled.Videocam
        else -> Icons.Filled.AttachFile
    }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !busy) {
                val ready = file ?: model.media.cachedFile(source)
                if (ready != null) {
                    openExternally(context, ready, mimeOf(ready.name))
                } else {
                    busy = true
                    scope.launch {
                        val result = model.media.load(source)
                        busy = false
                        if (result is MediaResult.Ready) openExternally(context, result.file, mimeOf(result.file.name))
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(source.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                if (busy) "下载中…" else "点开用系统播放器看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun MediaPlaceholder(text: String, error: Boolean = false) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (error) {
            Icon(
                Icons.Filled.AttachFile, contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        } else {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 全屏看图：双指缩放 + 拖动，单击关闭 */
@Composable
fun ImageLightbox(file: File, title: String, onDismiss: () -> Unit) {
    val bitmap = remember(file.absolutePath, file.length()) { Bitmaps.get(file, 4096) }
    if (bitmap == null) {
        onDismiss()
        return
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                .transformable(transformState),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x * density
                        translationY = offset.y * density
                    },
            )
            Text(
                title,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            )
        }
    }
}

/** 用系统播放器 / 查看器打开缓存下来的媒体 */
fun openExternally(context: Context, file: File, mime: String) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.updatefile", file)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 工具卡片里的媒体：看图/看视频/听录音的参数路径 + 工具自己带的 media 附件 */
@Composable
fun ToolMedia(tool: com.integemjack.friday.proto.ToolEvent, workingDirectory: String?) {
    val argSource = remember(tool.name, tool.arguments) {
        com.integemjack.friday.proto.toolMediaSource(tool.name, tool.arguments)?.let { (kind, src) ->
            if (src.startsWith("http://") || src.startsWith("https://")) {
                MediaSource.Url(kind, src)
            } else {
                MediaSource.ToolPath(kind, src, workingDirectory)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        argSource?.let { source ->
            if (source.kind == "image") RemoteImage(source, Modifier.fillMaxWidth())
            else MediaChip(source, modifier = Modifier.fillMaxWidth())
        }
        tool.media.forEach { attachment ->
            val source = MediaSource.Attachment(attachment)
            if (attachment.isImage) RemoteImage(source, Modifier.fillMaxWidth(), attachment.caption)
            else MediaChip(source, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 消息里的 media 块 */
@Composable
fun MediaBlockView(attachment: com.integemjack.friday.proto.MediaAttachment, modifier: Modifier = Modifier) {
    val source = remember(attachment.relativePath, attachment.id) { MediaSource.Attachment(attachment) }
    if (attachment.isImage) {
        RemoteImage(source, modifier, attachment.caption)
    } else {
        MediaChip(source, modifier = modifier)
    }
}

/** 给 MediaLoader 用的：本地缓存目录 */
fun mediaCacheDir(context: Context): File = File(context.cacheDir, "media")

