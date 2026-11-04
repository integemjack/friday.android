package com.integemjack.friday.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.DiffKind
import com.integemjack.friday.proto.DiffLine
import com.integemjack.friday.proto.FileChange
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.lineDiff
import com.integemjack.friday.ui.theme.LocalFridayExtra

/**
 * 一串块按 [groupBlocks] 摆出来：连续的操作收成一行，其余一块一行。
 * 直接往外层的 Column 里放（不另包一层），外层的行距照样管用
 */
@Composable
fun BlockList(blocks: List<MessageBlock>, workingDirectory: String?) {
    val items = remember(blocks) { groupBlocks(blocks) }
    items.forEach { item ->
        key(item.key) {
            when (item) {
                is BlockItem.Single -> BlockView(item.block, workingDirectory)
                is BlockItem.Ops -> OpsGroupView(item, workingDirectory)
            }
        }
    }
}

/**
 * 收起来的一串操作：一行「▸ 执行命令 ×3、修改文件 ×2   +40 −3」（不写「N 个操作」），
 * 有失败的右边标出来；点开一行一个操作（中间夹着的思考也在里面）。在跑的不收进来（[groupBlocks]）
 */
@Composable
private fun OpsGroupView(group: BlockItem.Ops, workingDirectory: String?) {
    var expanded by remember { mutableStateOf(false) }
    val holdPosition = rememberHoldPosition()
    val summary = remember(group.blocks) { opsSummary(group.tools) }
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    holdPosition()
                    expanded = !expanded
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(LineIndent)) {
                if (summary.running != null && !expanded) {
                    CircularProgressIndicator(Modifier.padding(1.dp).size(12.dp), strokeWidth = 1.5.dp)
                } else {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = muted,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { if (expanded) rotationZ = 90f },
                    )
                }
            }
            Text(
                summary.running?.let { "正在" + toolTitle(it) } ?: summary.kinds,
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (summary.linesAdded + summary.linesRemoved > 0) {
                Spacer(Modifier.width(8.dp))
                DiffCounts(summary.linesAdded, summary.linesRemoved)
            }
            if (summary.failed > 0) {
                Spacer(Modifier.width(8.dp))
                Text("${summary.failed} 个失败", style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 4.dp)
                    .leftRule(scheme.outlineVariant),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.blocks.forEach { block -> key(block.id) { BlockView(block, workingDirectory) } }
            }
        }
    }
}

/** 「+12 −3」：加的绿、删的红 */
@Composable
fun DiffCounts(added: Int, removed: Int) {
    val extra = LocalFridayExtra.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = extra.success)) { append("+$added") }
            append(" ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append("−$removed") }
        },
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

/** 一个文件的 diff 最多画这么多行：再多手机上翻不动，后面写一句还有几行 */
private const val MAX_DIFF_LINES = 300

/** diff 底色是代码块那种深色（两种主题都一样），所以加 / 删的颜色写死 */
private val AddedText = Color(0xFF8FE3AE)
private val AddedBackground = Color(0x3329A35A)
private val RemovedText = Color(0xFFFF9C94)
private val RemovedBackground = Color(0x33E5534B)
private val SameText = Color(0xFF9EA3B0)

/**
 * 改文件的 diff：深色底、等宽字，删掉的行红底「-」、新加的行绿底「+」、没变的灰字。
 * 好几处修改的每处前面写「第 n 处」；整个写一遍的（write_file）全是新加的
 */
@Composable
fun FileDiffView(change: FileChange) {
    val extra = LocalFridayExtra.current
    val sections = remember(change) { change.hunks.map { lineDiff(it.old, it.new) } }
    val total = sections.sumOf { it.size }
    var budget = MAX_DIFF_LINES
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(extra.codeBackground)
            .padding(vertical = 8.dp),
    ) {
        if (change.path.isNotBlank()) {
            Text(
                change.path,
                style = style,
                color = SameText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 6.dp),
            )
        }
        sections.forEachIndexed { index, lines ->
            if (budget <= 0) return@forEachIndexed
            if (sections.size > 1) {
                Text(
                    "第 ${index + 1} 处",
                    style = MaterialTheme.typography.labelSmall,
                    color = SameText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            lines.take(budget).forEach { line -> DiffRow(line, style) }
            budget -= lines.size
        }
        if (total > MAX_DIFF_LINES) {
            Text(
                "… 还有 ${total - MAX_DIFF_LINES} 行没列出",
                style = MaterialTheme.typography.labelSmall,
                color = SameText,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine, style: androidx.compose.ui.text.TextStyle) {
    val (mark, color, background) = when (line.kind) {
        DiffKind.Added -> Triple("+", AddedText, AddedBackground)
        DiffKind.Removed -> Triple("-", RemovedText, RemovedBackground)
        DiffKind.Same -> Triple(" ", SameText, Color.Transparent)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 10.dp),
    ) {
        Text(mark, style = style, color = color, modifier = Modifier.width(14.dp))
        Text(line.text.ifEmpty { " " }, style = style, color = color)
    }
}
