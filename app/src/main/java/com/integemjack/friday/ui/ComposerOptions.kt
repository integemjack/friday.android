package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.EngineInfo

/*
 * 输入框工具行里的三个选择器：模型、任务模式、思考模式（同桌面端 / Qt 输入框底行的模型菜单，
 * 那边三节在一个菜单里；手机上地方小，拆成三枚胶囊，各点各的）。
 * 会话页、助手页用的是同一套胶囊和菜单，只是改的东西不同（见 ChatComposer / AssistantComposer）。
 */

// MARK: 任务模式 / 思考档位的文案（== 桌面端 builtinCommands.ts、Qt 端）

/** 一种任务模式：raw 是协议里的值，title / detail 是菜单里的，short 是胶囊上的 */
data class TeamModeInfo(val raw: String, val title: String, val short: String, val detail: String)

val TEAM_MODES = listOf(
    TeamModeInfo("solo", "不派子智能体（默认）", "不派", "主智能体自己做"),
    TeamModeInfo("delegatesHeavy", "需要时派子智能体", "按需派", "主智能体自己做，活大、多步、能并行时派子智能体"),
    TeamModeInfo("ultra", "工作流", "工作流", "先定制完整流程，子智能体并联 / 串联去做，做完验收、不合格打回重做"),
)

/** 2.11 以前的 delegatesOnly / delegatesWhenNeeded 按 delegatesHeavy 算；没有 = solo */
fun normalizeTeamMode(raw: String?): String = when (raw) {
    null, "" -> "solo"
    "delegatesOnly", "delegatesWhenNeeded" -> "delegatesHeavy"
    else -> raw
}

/** 服务端没给 thinkingLevels（旧版）时列全量 */
val ALL_THINKING_LEVELS = listOf("off", "low", "medium", "high", "ultra")

private val LEVEL_TITLES = mapOf("off" to "关闭", "low" to "低", "medium" to "中", "high" to "高", "ultra" to "超级模式")

/** 这一档写什么：只有开 / 关两档时写「开启 / 关闭」 */
fun thinkingTitle(level: String, levels: List<String>): String =
    if (levels.size == 2) (if (level == "off") "关闭" else "开启") else LEVEL_TITLES[level] ?: level

/** 现在实际用的档位：不在能调的里面时，开着的算最高一档、关着的算关（== Qt effective） */
fun effectiveThinking(current: String?, levels: List<String>): String? {
    if (current.isNullOrEmpty()) return null
    if (current in levels) return current
    return if (current != "off" && levels.isNotEmpty()) levels.last() else "off"
}

// MARK: 胶囊 + 菜单

/** 菜单里的一项 */
data class ComposerChoice(
    val key: String,
    val title: String,
    val detail: String? = null,
    val selected: Boolean = false,
)

/**
 * 工具行里的一枚选择胶囊：图标 + 当前值 + ▾，点开往上弹一个菜单（[header] 是菜单顶上的小标题）。
 * 选中的那项打勾；点已经选着的那项只是收起菜单。
 */
@Composable
fun ComposerPicker(
    icon: ImageVector,
    label: String,
    description: String,
    choices: List<ComposerChoice>,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = null,
    footnote: String? = null,
    enabled: Boolean = true,
    emptyText: String = "没有能选的",
    muted: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    var open by remember { mutableStateOf(false) }
    val tint = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
        open -> scheme.primary
        muted -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
        else -> scheme.onSurfaceVariant
    }
    Box(modifier) {
        Row(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        open -> scheme.primary.copy(alpha = 0.12f)
                        dark -> scheme.surfaceContainerHighest
                        else -> scheme.surfaceContainer
                    }
                )
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = description) { open = true }
                .padding(start = 8.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            if (header != null) {
                Text(
                    header,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                )
            }
            if (choices.isEmpty()) {
                DropdownMenuItem(text = { Text(emptyText) }, enabled = false, onClick = {})
            }
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(max = 260.dp)) {
                            Text(
                                choice.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (choice.selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (choice.selected) scheme.primary else scheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            choice.detail?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = if (choice.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "当前", tint = scheme.primary, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        if (!choice.selected) onChoose(choice.key)
                    },
                )
            }
            if (footnote != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.6f))
                Text(
                    footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                )
            }
        }
    }
}

// MARK: 三个现成的选择器

/** 「跟新对话一样」那一项的 key（助手页的模型菜单用：清掉自己选的） */
const val FOLLOW_DEFAULT_ENGINE = ""

/**
 * 模型：列这台机器上能用的引擎（显示模型名，详情是服务商）。
 * [followDefault] 不为空时最上面多一项「跟新对话一样（…）」，选它回调 [FOLLOW_DEFAULT_ENGINE]。
 */
@Composable
fun ModelPicker(
    engines: List<EngineInfo>,
    selectedID: String?,
    label: String,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    header: String = "模型",
    footnote: String? = null,
    followDefault: String? = null,
) {
    val choices = buildList {
        if (followDefault != null) {
            add(
                ComposerChoice(
                    key = FOLLOW_DEFAULT_ENGINE,
                    title = "跟新对话一样",
                    detail = followDefault,
                    selected = selectedID.isNullOrEmpty(),
                )
            )
        }
        engines.forEach { engine ->
            add(
                ComposerChoice(
                    key = engine.id,
                    title = engine.label,
                    detail = engine.title.takeIf { it.isNotBlank() && it != engine.label },
                    selected = engine.id == selectedID,
                )
            )
        }
    }
    ComposerPicker(
        icon = Icons.Filled.AutoAwesome,
        label = label,
        description = "选择模型",
        choices = choices,
        onChoose = onChoose,
        header = header,
        footnote = footnote,
        enabled = enabled,
        emptyText = "这台机器上还没有能用的模型",
        modifier = modifier,
    )
}

/** 任务模式：不派 / 需要时派 / 工作流 */
@Composable
fun TeamModePicker(
    current: String,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    header: String = "任务模式",
    footnote: String? = null,
) {
    val mode = normalizeTeamMode(current)
    ComposerPicker(
        icon = Icons.Filled.AccountTree,
        label = TEAM_MODES.firstOrNull { it.raw == mode }?.short ?: mode,
        description = "选择任务模式",
        choices = TEAM_MODES.map { ComposerChoice(it.raw, it.title, it.detail, it.raw == mode) },
        onChoose = onChoose,
        header = header,
        footnote = footnote,
        enabled = enabled,
        modifier = modifier,
    )
}

/** 思考模式：列这个模型能调的档位；[levels] 是空的 = 这个模型不能调 */
@Composable
fun ThinkingPicker(
    levels: List<String>,
    current: String?,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    header: String = "思考模式",
    footnote: String? = null,
) {
    val effective = effectiveThinking(current, levels)
    val label = when {
        levels.isEmpty() -> "不能调"
        effective == null -> "默认"
        else -> thinkingTitle(effective, levels)
    }
    ComposerPicker(
        icon = Icons.Filled.Psychology,
        label = label,
        description = "选择思考模式",
        choices = levels.map { ComposerChoice(it, thinkingTitle(it, levels), selected = it == effective) },
        onChoose = onChoose,
        header = header,
        footnote = footnote,
        enabled = enabled,
        emptyText = "这个模型不能调",
        muted = effective == "off" || levels.isEmpty(),
        modifier = modifier,
    )
}
