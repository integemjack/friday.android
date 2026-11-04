package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 一条 ActivityEvent 合进活动列表（REMOTE.md §6「会话的实时动静」）。返回新列表；不是给哪段会话的返回 null。
 * - `step`：任务状态的目标和步骤在**顶层**（`goal`、`steps` 数组），整份换掉
 * - `tool`：在做的那个工具；`finished` 清掉它
 * - `approval`：`approvals` 是现在全部的（空 = 都处理完了）
 * - 快照里没有的会话（连上以后才动起来的）先建一条再合
 */
fun mergeActivity(current: List<SessionActivity>, params: JsonObject): List<SessionActivity>? {
    val id = params.str("conversationID") ?: return null
    val kind = params.str("kind")
    val existing = current.firstOrNull { it.id == id } ?: SessionActivity(
        id = id,
        title = "",
        status = "idle",
        taskGoal = null,
        taskSteps = emptyList(),
        approvals = emptyList(),
        currentToolTitle = null,
        currentToolSummary = null,
        updatedAt = "",
    )
    val approvals = (params["approvals"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(PendingApproval::fromJson) }
    val tool = params["tool"] as? JsonObject
    val status = when {
        kind == "started" -> "running"
        kind == "finished" -> "idle"
        kind == "quota" -> "quota"
        approvals != null && approvals.isNotEmpty() -> "approval"
        approvals != null && existing.status == "approval" -> "running"
        else -> existing.status
    }
    val updated = existing.copy(
        title = params.str("title")?.takeIf { it.isNotBlank() } ?: existing.title,
        status = status,
        taskGoal = if (kind == "step") params.str("goal") else existing.taskGoal,
        taskSteps = if (kind == "step") TaskSnapshot.stepsFrom(params["steps"]) else existing.taskSteps,
        approvals = approvals ?: existing.approvals,
        currentToolTitle = when {
            kind == "finished" -> null
            tool != null -> tool.str("title")
            else -> existing.currentToolTitle
        },
        currentToolSummary = when {
            kind == "finished" -> null
            tool != null -> tool.str("summary")
            else -> existing.currentToolSummary
        },
        updatedAt = params.str("at") ?: existing.updatedAt,
    )
    return (current.filter { it.id != id } + updated).sortedByDescending { it.updatedAt }
}
