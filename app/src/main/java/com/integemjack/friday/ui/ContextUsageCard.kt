package com.integemjack.friday.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.ContextUsage

/*
 * 「＋」面板里的「上下文」（会话页）：一行写着这段会话的上下文用了多少（进度环 + 「48.2K / 128K（38%）」），
 * 点一下在原地展开明细——一条分段的条（系统提示、工具定义、用户消息、回复和思考、工具调用和结果、空余）+ 每部分多少、占多少，
 * 再点收起。数和文案照桌面端输入框下面那个上下文进度环（Qt ContextUsageView.qml）：70% 起环变琥珀色、90% 起变红。
 *
 * 各部分的颜色没照搬桌面端那组（灰和青、蓝和紫在色弱下分不开），用的是验过色弱区分度的一组，深浅两套各自取一档；
 * 颜色只是辅助，每部分在下面都有名字和数。
 */

/** 各部分的名字（== Qt ContextUsageView.partTitles，顺序同 [com.integemjack.friday.proto.ContextParts.values]） */
val CONTEXT_PART_TITLES = listOf("系统提示", "工具定义", "用户消息", "回复和思考", "工具调用和结果")

private val LightPartColors = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4))
private val DarkPartColors = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181))

@Composable
fun ContextUsageCard(usage: ContextUsage, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val partColors = if (dark) DarkPartColors else LightPartColors
    val surface = scheme.surfaceContainerLowest
    var expanded by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "contextArrow")
    // 70% 起琥珀色、90% 起红（环的颜色；旁边的字照常用文字色，比例写在字里）
    val ringTint = when {
        !usage.hasLimit || usage.fraction < 0.7f -> scheme.primary
        usage.fraction < 0.9f -> scheme.tertiary
        else -> scheme.error
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起明细" else "展开明细",
                ) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextRing(usage.fraction, ringTint)
            Spacer(Modifier.width(12.dp))
            Text("上下文", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Text(
                headline(usage),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrow),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            ContextDetail(usage, partColors, surface)
        }
    }
}

/** 「48.2K / 128K（38%）」；不知道上限时「48.2K tokens」；有消息但没记下时「还没记下」 */
private fun headline(usage: ContextUsage): String = when {
    usage.unrecorded -> "还没记下"
    usage.hasLimit ->
        "${ContextUsage.compact(usage.used)} / ${ContextUsage.compact(usage.limit!!)}（${ContextUsage.percent(usage.fraction)}）"
    else -> "${ContextUsage.compact(usage.used)} tokens"
}

/** 行头的进度环：跟别的行的图标牌一样大、一样的淡底 */
@Composable
private fun ContextRing(fraction: Float, tint: Color) {
    val sweep by animateFloatAsState(
        // 用了一点点也看得出来
        if (fraction > 0f) fraction.coerceAtLeast(0.03f) else 0f,
        tween(300),
        label = "contextRing",
    )
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(32.dp / 3))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(tint.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (sweep > 0f) {
                drawArc(tint, -90f, sweep * 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun ContextDetail(usage: ContextUsage, partColors: List<Color>, surface: Color) {
    val scheme = MaterialTheme.colorScheme
    val parts = usage.parts?.values
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 分段的条：各部分按顺序排，后面是空余（底色）；没明细时整段用了的一种颜色
        val segments = parts?.zip(partColors) ?: listOf(usage.used to scheme.onSurfaceVariant)
        SegmentedBar(
            segments = segments,
            scale = if (usage.hasLimit) maxOf(usage.limit!!, usage.used) else usage.used,
            track = scheme.onSurfaceVariant.copy(alpha = 0.16f),
            gap = surface,
            description = if (parts != null) {
                parts.indices.joinToString("，") { i ->
                    "${CONTEXT_PART_TITLES[i]} ${ContextUsage.compact(parts[i])}（${usage.share(parts[i])}）"
                } + if (usage.hasLimit) "，空余 ${ContextUsage.compact(usage.free)}（${usage.share(usage.free)}）" else ""
            } else {
                usage.summary
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (parts == null) {
                Text(
                    when {
                        usage.used > 0 -> "已用 ${ContextUsage.grouped(usage.used)} tokens；这段对话之前的回复没记明细，下一次回复后就有"
                        usage.hasMessages -> "还没记下上下文用量，下一次回复后就有"
                        else -> "还没发过消息，上下文是空的"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                parts.forEachIndexed { i, tokens ->
                    PartRow(
                        dot = partColors[i],
                        title = CONTEXT_PART_TITLES[i],
                        value = ContextUsage.compact(tokens),
                        share = usage.share(tokens),
                    )
                }
            }
            if (usage.hasLimit && (parts != null || usage.used > 0)) {
                PartRow(
                    dot = null,
                    title = "空余",
                    value = ContextUsage.compact(usage.free),
                    share = usage.share(usage.free),
                )
            }
        }

        val notes = listOfNotNull(
            usage.compactAt?.takeIf { it > 0 }?.let { "超过 ${ContextUsage.compact(it)} 时自动压缩较早的历史" },
            when {
                !usage.hasLimit -> "不知道 ${usage.model} 的上下文上限"
                usage.guessed -> "${usage.model} 没报上限，按那台机器 设置 › 智能体 的最大上下文长度"
                else -> "上限是 ${usage.model} 或服务商报的"
            },
            if (parts != null) "合计是最近一次请求的实际用量，各部分按字数估算" else null,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            notes.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 细的分段条（8dp、两头全圆）：宽度按比例，相邻两段之间、用了的和空余之间留 2dp 卡片底色的缝；
 * 为 0 的部分不画。[scale] 是整条代表多少（有上限按上限，没有按用了的）
 */
@Composable
private fun SegmentedBar(
    segments: List<Pair<Long, Color>>,
    scale: Long,
    track: Color,
    gap: Color,
    description: String,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description },
    ) {
        drawRect(track)
        val shown = segments.filter { it.first > 0 }
        if (shown.isEmpty() || scale <= 0) return@Canvas
        val used = shown.sumOf { it.first }
        val hasFree = used < scale
        val gapPx = 2.dp.toPx()
        val gaps = if (hasFree) shown.size else shown.size - 1
        val usable = (size.width - gaps * gapPx).coerceAtLeast(0f)
        var x = 0f
        shown.forEachIndexed { i, (tokens, color) ->
            val w = usable * tokens / scale
            drawRect(color, Offset(x, 0f), Size(w, size.height))
            x += w
            if (i < shown.size - 1 || hasFree) {
                drawRect(gap, Offset(x, 0f), Size(gapPx, size.height))
                x += gapPx
            }
        }
    }
}

/** 明细里的一行：圆点（空余是空心圈）、名字 …… 多少、占比 */
@Composable
private fun PartRow(dot: Color?, title: String, value: String, share: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .then(
                    if (dot != null) {
                        Modifier.background(dot)
                    } else {
                        Modifier.border(1.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape)
                    }
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), color = scheme.onSurface)
        Text(
            share,
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

/** 一列数对齐：等宽数字 */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
