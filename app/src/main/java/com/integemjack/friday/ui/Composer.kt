package com.integemjack.friday.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 底部输入框：**助手页和会话页共用这一个**，样子、尺寸、按钮位置完全一样。
 *
 * 一整块圆角卡片，上下两行：
 * - 上面是多行输入
 * - 下面一行：左边「＋」（[onMore]：附件、模型、任务模式、思考模式、工作目录、视频通话都在它点出来的面板里），
 *   旁边一行小字 [summary] 写着现在用的模型 · 任务模式 · 思考（点它也是打开面板）；右边是动作按钮
 *
 * 动作按钮按状态换：
 * - 在跑（[onStop] 不为空）→ 红色停止 / 打断；框里有话时旁边再多一颗 [runningSend]
 *   （会话页是「补充给智能体」：做完当前这一步就交给它，同桌面端 / Qt 的双键）
 * - 在听写（[voice] 在听）→ 主色的话筒，按一下停
 * - [busy] → 转圈（附件还在传）
 * - 有话可发 → 发送
 * - 框里空着 → 话筒（[voice]：默认就是语音键）
 *
 * 卡片上面可以挂几条提示（排队、重试、附件、直发说明），由调用方经 [above] 给，
 * 跟卡片同宽、同一套左右留白。
 *
 * 容器（背景、让位导航条和键盘）由 [HomeScaffold] 的 dock 负责，这里只画输入框本身。
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    canSend: Boolean = value.isNotBlank(),
    busy: Boolean = false,
    onStop: (() -> Unit)? = null,
    stopDescription: String = "停止",
    runningSend: RunningSend = RunningSend.Send,
    voice: VoiceInput? = null,
    onMore: (() -> Unit)? = null,
    summary: String? = null,
    above: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        when {
            !enabled -> scheme.outlineVariant.copy(alpha = 0.4f)
            focused -> scheme.primary.copy(alpha = 0.55f)
            else -> scheme.outlineVariant.copy(alpha = 0.75f)
        },
        tween(160),
        label = "composerBorder",
    )
    // 浅色下卡片是纯白、比页面背景亮一档；深色下「最低」那档比背景还暗，会像个坑，换亮一档
    val card = when {
        !enabled -> scheme.surfaceContainer
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surfaceContainerLowest
    }
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        above?.invoke(this)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(card)
                .border(1.dp, borderColor, shape),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                maxLines = 6,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                ),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                if (voice?.listening == true) "正在听，说吧…（说完点右边的话筒停）" else placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onMore != null) {
                    MoreButton(enabled = enabled, onClick = onMore)
                    Spacer(Modifier.width(6.dp))
                }
                // 现在用的模型 · 任务模式 · 思考：一行小字，点它也是打开面板
                Text(
                    summary.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (onMore != null && !summary.isNullOrEmpty()) {
                                Modifier.clickable(enabled = enabled, onClickLabel = "打开设置", onClick = onMore)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Actions(
                    enabled = enabled,
                    canSend = canSend,
                    busy = busy,
                    onSend = onSend,
                    onStop = onStop,
                    stopDescription = stopDescription,
                    runningSend = runningSend,
                    voice = voice,
                )
            }
        }
    }
}

/** 在跑时、框里有话时多出来的那颗：会话页是补充（做完这一步就交给它），助手页是插话（打断重来） */
enum class RunningSend(val description: String) {
    Send("发送"),
    Supplement("补充给智能体"),
}

@Composable
private fun Actions(
    enabled: Boolean,
    canSend: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: (() -> Unit)?,
    stopDescription: String,
    runningSend: RunningSend,
    voice: VoiceInput?,
) {
    val scheme = MaterialTheme.colorScheme
    val listening = voice?.listening == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            onStop != null -> {
                if (listening) MicButton(voice!!, enabled)
                ComposerAction(
                    icon = Icons.Filled.Stop,
                    description = stopDescription,
                    container = scheme.errorContainer,
                    content = scheme.error,
                    onClick = onStop,
                )
                if (enabled && canSend && !busy) {
                    ComposerAction(
                        icon = if (runningSend == RunningSend.Supplement) Icons.Filled.ArrowUpward else Icons.AutoMirrored.Filled.Send,
                        description = runningSend.description,
                        container = scheme.primary,
                        content = scheme.onPrimary,
                        onClick = onSend,
                    )
                } else if (!listening && voice != null && enabled && !canSend) {
                    // 在跑、框里空着：停止 + 话筒（说一句补充）
                    MicButton(voice, enabled)
                }
            }
            listening -> MicButton(voice!!, enabled)
            busy -> Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            enabled && canSend -> ComposerAction(
                icon = Icons.AutoMirrored.Filled.Send,
                description = "发送",
                container = scheme.primary,
                content = scheme.onPrimary,
                onClick = onSend,
            )
            voice != null -> MicButton(voice, enabled)
            else -> ComposerAction(
                icon = Icons.AutoMirrored.Filled.Send,
                description = "发送",
                container = scheme.surfaceContainerHighest,
                content = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                enabled = false,
                onClick = onSend,
            )
        }
    }
}

/** 语音键：平时是淡主色底的话筒，听着的时候主色实心、外面一圈跟着音量跳 */
@Composable
private fun MicButton(voice: VoiceInput, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val listening = voice.listening
    val ring by animateFloatAsState(if (listening) 1f + voice.level * 0.35f else 1f, tween(90), label = "micRing")
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier
                    .size(36.dp)
                    .scale(ring)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.22f))
            )
        }
        ComposerAction(
            icon = Icons.Filled.Mic,
            description = if (listening) "停止听写" else "语音输入",
            container = if (listening) scheme.primary else scheme.primary.copy(alpha = 0.12f),
            content = when {
                listening -> scheme.onPrimary
                enabled -> scheme.primary
                else -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            enabled = enabled,
            onClick = { voice.toggle() },
        )
    }
}

/** 左下角的「＋」：点开底部面板 */
@Composable
private fun MoreButton(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) scheme.surfaceContainerHigh else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "更多",
            tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(19.dp),
        )
    }
}

/** 卡片右下角那颗圆按钮（自己画，不用 IconButton：那个会把自己撑到 48dp，工具行就被顶高了） */
@Composable
private fun ComposerAction(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val bg by animateColorAsState(container, tween(160), label = "composerAction")
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = content, modifier = Modifier.size(18.dp))
    }
}

/**
 * 输入框上面的一条提示（重试、直发说明这种）：淡色底 + 图标 + 一句话，右边可挂一个按钮。
 * 和 [Composer] 同宽同圆角体系，看着是输入框的一部分。
 */
@Composable
fun ComposerNotice(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp)
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 7.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

// MARK: 草稿 + 附件（两页共用）

/**
 * 输入框里打了一半的话 + 选了还没发的附件。会话页按会话各存一份（[ChatDrafts]），助手页一份（AppRoot 拿着）：
 * 切到别的页、别的会话再回来，打了一半的话和附件都还在。
 */
@Stable
class ComposerDraft(
    initial: String = "",
    /** 字变了就存一下（[com.integemjack.friday.data.DraftStore]：App 关掉再开还在）；不给就只在内存里 */
    private val persist: ((String) -> Unit)? = null,
) {
    private var text by mutableStateOf(initial)

    var input: String
        get() = text
        set(value) {
            if (value == text) return
            text = value
            persist?.invoke(value)
        }
    val attachments = mutableStateListOf<PendingAttachment>()
    /** 上传前拷到本地的副本（发完、移除时删掉） */
    val localCopies = mutableMapOf<String, java.io.File>()

    /** 要一起发出去的位置（助手页「＋」→「位置」；输入框上面那条可以 × 掉） */
    var location by mutableStateOf<SharedLocation?>(null)

    /** 正在定位 */
    var locating by mutableStateOf(false)

    /** 还有附件在传：发送键转圈，先别发 */
    val uploading: Boolean get() = attachments.any { !it.done && it.error == null }

    /** 传好了的附件在服务端上的路径（runner.submit / assistant.say 的 attachments） */
    val readyPaths: List<String> get() = attachments.filter { it.done }.mapNotNull { it.serverPath }

    /** 没字、没附件、也没要发的位置 */
    val isEmpty: Boolean get() = input.isBlank() && readyPaths.isEmpty() && location == null

    fun update(id: String, transform: (PendingAttachment) -> PendingAttachment) {
        val index = attachments.indexOfFirst { it.id == id }
        if (index >= 0) attachments[index] = transform(attachments[index])
    }

    fun remove(id: String) {
        attachments.removeAll { it.id == id }
        localCopies.remove(id)?.delete()
    }

    fun clear() {
        input = ""
        location = null
        attachments.clear()
        localCopies.values.forEach { it.delete() }
        localCopies.clear()
    }

    companion object {
        /** 转屏 / 进程重建时只留打了一半的字（附件的本地副本和上传进度留不住） */
        val Saver: Saver<ComposerDraft, String> = Saver(
            save = { it.input },
            restore = { text -> ComposerDraft(text) },
        )
    }
}

/** 附件按钮点出来的两样：选相册（图片 / 视频）、选任意文件 */
class AttachmentPickers(val pickMedia: () -> Unit, val pickFile: () -> Unit)

/**
 * 选附件 → 拷进上传缓存 → file.upload，进度写回**选的那一刻**的草稿（中途换了会话，进度还落回原来那段）。
 * 会话页和助手页都用它，传法一样（都是先传到那台机器上，再把服务端路径带着发）。
 */
@Composable
fun rememberAttachmentPickers(draft: ComposerDraft): AttachmentPickers {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(draft)

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val target = current
        val fresh = uris.map { uri ->
            PendingAttachment(
                id = UUID.randomUUID().toString(),
                name = displayName(context, uri),
                size = displaySize(context, uri),
            ) to uri
        }
        target.attachments.addAll(fresh.map { it.first })
        fresh.forEach { (item, uri) ->
            scope.launch {
                val local = copyToUploadCache(context, uri, item.name)
                if (local == null) {
                    target.update(item.id) { it.copy(error = "读不了这个文件") }
                    return@launch
                }
                target.localCopies[item.id] = local
                runCatching {
                    model.client.uploadFile(local) { fraction ->
                        target.update(item.id) { it.copy(progress = fraction) }
                    }
                }.onSuccess { path ->
                    target.update(item.id) { it.copy(progress = 1f, serverPath = path) }
                }.onFailure { error ->
                    target.update(item.id) { it.copy(error = error.message ?: "上传失败") }
                }
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> add(uris) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { add(listOf(it)) } }
    return remember(mediaPicker, filePicker) {
        AttachmentPickers(
            pickMedia = {
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            pickFile = { filePicker.launch(arrayOf("*/*")) },
        )
    }
}
