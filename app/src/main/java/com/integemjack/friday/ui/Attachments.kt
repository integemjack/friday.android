package com.integemjack.friday.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.io.File

/** 一个正在传 / 已传好的附件 */
data class PendingAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val progress: Float = 0f,
    /** 服务端上的路径（传完才有，runner.submit 的 attachments 用它） */
    val serverPath: String? = null,
    val error: String? = null,
) {
    val done: Boolean get() = serverPath != null
}

/** 从 content:// 里问出显示名（问不到就按 uri 尾巴凑一个） */
fun displayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    if (!queried.isNullOrBlank()) return queried
    val tail = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
    return if (tail.contains('.')) tail else "$tail.jpg"
}

/** 文件大小（问不到就 0） */
fun displaySize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
    }
}.getOrNull() ?: 0L

/**
 * 把选中的 Uri 拷进上传缓存（file.upload 要按块读本地文件，content:// 只能经 ContentResolver）。
 * 拷不动（权限、空间不够）返回 null。
 */
fun copyToUploadCache(context: Context, uri: Uri, name: String): File? = runCatching {
    val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
    val target = File(dir, uniqueName(dir, name))
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    if (target.length() == 0L) {
        target.delete()
        null
    } else target
}.getOrNull()

/** 同名不覆盖：a.png → a-1.png */
private fun uniqueName(dir: File, name: String): String {
    if (!File(dir, name).exists()) return name
    val base = name.substringBeforeLast('.', name)
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var index = 1
    while (File(dir, "$base-$index$ext").exists()) index++
    return "$base-$index$ext"
}

/** 上传完的本地副本可以删了（服务端已经有了一份） */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 附件按钮：点开选相册还是选文件 */
@Composable
fun AttachmentButton(enabled: Boolean, onPickMedia: () -> Unit, onPickFile: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.AttachFile, contentDescription = "添加附件")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("图片 / 视频") },
                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                onClick = { expanded = false; onPickMedia() },
            )
            DropdownMenuItem(
                text = { Text("文件") },
                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                onClick = { expanded = false; onPickFile() },
            )
        }
    }
}

/** 待传附件的一排小卡片（带进度、错误、删除） */
@Composable
fun AttachmentChips(items: List<PendingAttachment>, onRemove: (String) -> Unit) {
    if (items.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    when {
                        item.error != null -> Text(
                            item.error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        item.done -> Text(
                            "已传好${formatBytes(item.size).let { if (it.isEmpty()) "" else " · $it" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        else -> {
                            Text(
                                "上传中 ${(item.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = { item.progress },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (!item.done) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "移除",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
