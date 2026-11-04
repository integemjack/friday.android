package com.integemjack.friday.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.proto.TaskSnapshot
import com.integemjack.friday.proto.TaskStep
import com.integemjack.friday.ui.theme.LocalFridayExtra

// MARK: 任务状态

/**
 * 输入框上面的任务条：智能体记下的任务（update_task_state），一行「图标 · 在做的那步 · 2/5」+ 一道细进度条；
 * 点开看目标、全部步骤和要守的约束。没记任务、或者做完了也不在跑了，就不挂
 */
@Composable
fun TaskBar(task: TaskSnapshot, generating: Boolean) {
    if (task.finished && !generating) return
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val total = task.steps.size
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 输入框背后透明：先垫页面底色，淡色才不会透出后面滚动的字
            .background(scheme.background)
            .background(scheme.primaryContainer.copy(alpha = 0.30f))
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Checklist, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                task.current?.text?.takeIf { it.isNotBlank() } ?: task.goal ?: "任务",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Spacer(Modifier.width(8.dp))
                Text("${task.done}/$total", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "收起任务" else "展开任务",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else -90f },
            )
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { task.done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp),
                color = scheme.primary,
                trackColor = scheme.primary.copy(alpha = 0.15f),
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            ) { TaskChecklist(task) }
        } else {
            Spacer(Modifier.padding(bottom = 6.dp))
        }
    }
}

/** 目标 + 一步一行（✓ 做完、● 在做、○ 还没做）+ 折着的约束。任务条点开、消息里「记下任务状态」点开都用它 */
@Composable
fun TaskChecklist(task: TaskSnapshot) {
    val scheme = MaterialTheme.colorScheme
    var showConstraints by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        task.goal?.takeIf { it.isNotBlank() }?.let {
            Text("目标", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
        }
        if (task.steps.isNotEmpty()) {
            Text(
                "步骤 ${task.done}/${task.steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            task.steps.forEach { step -> StepRow(step) }
        }
        if (task.constraints.isNotEmpty()) {
            Text(
                (if (showConstraints) "要守的约束 ▾" else "要守的约束（${task.constraints.size}）…"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showConstraints = !showConstraints },
            )
            if (showConstraints) {
                task.constraints.forEach { c ->
                    Text("· $c", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: TaskStep) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalFridayExtra.current
    val (icon, tint) = when (step.status) {
        "done" -> Icons.Filled.CheckCircle to extra.success
        "doing" -> Icons.Filled.RadioButtonChecked to scheme.primary
        else -> Icons.Filled.RadioButtonUnchecked to scheme.outline
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            step.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (step.status == "done") scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}
