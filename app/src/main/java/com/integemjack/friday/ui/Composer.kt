package com.integemjack.friday.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 底部输入框：**助手页和会话页共用这一个**，样子、尺寸、按钮位置完全一样。
 *
 * 一整颗胶囊：左边可选的 [leading]（会话页是附件按钮）、中间多行输入、右边一个圆形动作按钮，
 * 都收在同一颗胶囊里。动作按钮三种状态：
 * - [onStop] 不为空 → 停止 / 打断（红底方块，[stopDescription] 是无障碍名）
 * - [busy] → 转圈（附件还在传）
 * - 其余 → 发送（能发时主色实心，不能发时灰着）
 *
 * 胶囊上面可以挂几条提示（排队、重试、附件、直发说明），由调用方经 [above] 给，
 * 跟胶囊同宽、同一套左右留白。
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
    leading: (@Composable () -> Unit)? = null,
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
    // 浅色下胶囊是纯白、比页面背景亮一档；深色下纯「最低」那档比背景还暗，会像个坑，换亮一档
    val pill = when {
        !enabled -> scheme.surfaceContainer
        dark -> scheme.surfaceContainerHigh
        else -> scheme.surfaceContainerLowest
    }
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        above?.invoke(this)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(pill)
                .border(1.dp, borderColor, shape)
                .padding(start = if (leading != null) 5.dp else 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            // 多行时按钮贴底，跟着最后一行走（不会跑到中间去）
            verticalAlignment = Alignment.Bottom,
        ) {
            if (leading != null) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(4.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                maxLines = 5,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                ),
                cursorBrush = SolidColor(scheme.primary),
                // 上下 8dp + 一行 22sp = 38dp，单行时文字和两边的按钮正好一条中线
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
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
            Spacer(Modifier.width(6.dp))
            when {
                onStop != null -> ComposerAction(
                    icon = Icons.Filled.Stop,
                    description = stopDescription,
                    container = scheme.errorContainer,
                    content = scheme.error,
                    onClick = onStop,
                )
                busy -> Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                else -> {
                    val ready = enabled && canSend
                    ComposerAction(
                        icon = Icons.AutoMirrored.Filled.Send,
                        description = "发送",
                        container = if (ready) scheme.primary else scheme.surfaceContainerHighest,
                        content = if (ready) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        enabled = ready,
                        onClick = onSend,
                    )
                }
            }
        }
    }
}

/** 胶囊右边那颗圆按钮（自己画，不用 IconButton：那个会把自己撑到 48dp，胶囊就被顶高了） */
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
            .size(38.dp)
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
