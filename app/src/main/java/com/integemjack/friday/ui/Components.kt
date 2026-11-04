package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.integemjack.friday.ui.theme.LocalFridayExtra

/**
 * 界面里反复出现的小零件：卡片、区块标题、状态标签、代码块、空状态。
 * 各页面只挑这些拼，圆角、留白、字号就不会各写一套。
 */

/** 内容卡片：白底浮在淡彩背景上 + 极细描边，不用阴影（列表里一片阴影会脏） */
@Composable
fun FridayCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(padding),
        content = content,
    )
}

/**
 * 页面的大标题区：大标题 + 数字胶囊 + 一行副标题（带状态灯）。
 * 会话 / 活动 / 设置三页都走这一个，免得每页的字号、间距各写一套。
 * 这些页面是从抽屉进来的，左上角给一个菜单按钮把抽屉再拉出来。
 */
@Composable
fun PageHeader(
    title: String,
    count: Int? = null,
    subtitle: String? = null,
    dotColor: Color? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(
            start = if (onOpenDrawer != null) 0.dp else 4.dp,
            top = 2.dp,
            bottom = 4.dp,
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onOpenDrawer != null) {
                DrawerButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (count != null && count > 0) {
                Spacer(Modifier.width(10.dp))
                Chip(
                    "$count",
                    color = MaterialTheme.colorScheme.primary,
                    container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                )
            }
        }
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = if (onOpenDrawer != null) 44.dp else 0.dp),
            ) {
                if (dotColor != null) {
                    Dot(dotColor, size = 6.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 区块标题：图标 + 标题，右边可以挂个小标签 */
@Composable
fun SectionTitle(
    icon: ImageVector? = null,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** 小标签：淡底 + 同色字，用来标「草稿」「待确认」「完成」这种状态 */
@Composable
fun Chip(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    container: Color = color.copy(alpha = 0.14f),
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/** 圆点（状态灯）：占住 size 这么宽，里面一个实心点 + 一圈淡晕，小尺寸下也看得出是个「灯」 */
@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.6f)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/** 分隔点：行内两个信息之间的小圆点（3dp 这种小尺寸用带光晕的 Dot 会发虚） */
@Composable
fun SepDot(color: Color = MaterialTheme.colorScheme.outlineVariant, size: Dp = 3.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

/** 代码 / 工具输出：深色底 + 等宽字，跟正文一眼能分开 */
@Composable
fun MonospaceBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val extra = LocalFridayExtra.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(extra.codeBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (dark) Color(0xFFD6D8E0) else Color(0xFFD9DBE4),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 空状态：大图标 + 一句标题 + 一句怎么办 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                // 从左上到右下的淡淡渐变，比一块平色更像「卡片」而不是色块
                .background(
                    Brush.linearGradient(
                        listOf(tint.copy(alpha = 0.20f), tint.copy(alpha = 0.07f)),
                    ),
                    RoundedCornerShape(22.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 细分割线 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    )
}

/** 圆角方形图标底座（工具卡、列表项前面那个） */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 36.dp,
    contentDescription: String? = null,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** 头像/字母牌：取标题第一个字符，底色按标题散列挑一个 */
@Composable
fun MonogramBadge(text: String, size: Dp = 40.dp, seedColors: List<Color>? = null) {
    val palette = seedColors ?: listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val color = palette[(text.hashCode().let { if (it < 0) -it else it }) % palette.size]
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.trim().take(1).ifBlank { "·" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue