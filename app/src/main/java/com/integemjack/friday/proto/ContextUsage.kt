package com.integemjack.friday.proto

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 这段会话的上下文用了多少（输入框「＋」面板里的「上下文」，点开看明细；== Qt ChatViewModel::computeContextUsage）。
 *
 * 用了多少 = 最近一条带统计的助手消息里的 contextTokenCount（最近一次请求的输入 + 那一段生成，服务端报的）；
 * 上限 = 那条统计里记的（模型、服务商报的，或按那台 设置 › 智能体 的最大上下文长度）。
 * 这一轮换了模型时，记下的上限是上一个模型的：不用，写「不知道上限」（Qt 客户端按自己本机的设置猜，这边不知道那台的设置）。
 */
data class ContextUsage(
    val used: Long,
    /** 现在这段会话用的模型 */
    val model: String,
    /** 上下文上限；null = 不知道 */
    val limit: Long? = null,
    /** 模型、服务商都没报上限，按那台设置里的最大上下文长度 */
    val guessed: Boolean = false,
    /** 超过多少自动压缩较早的历史（本地大模型） */
    val compactAt: Long? = null,
    /** 各部分；之前的回复没记明细时为 null */
    val parts: ContextParts? = null,
    /** 这条路径上有没有消息：没有 = 上下文是空的；有消息但 [used] 为 0 = 还没记下（还没回复过、或那台太旧不报） */
    val hasMessages: Boolean = false,
) {
    val hasLimit: Boolean get() = (limit ?: 0) > 0

    /** 有消息、但一条带上下文用量的统计都没有 */
    val unrecorded: Boolean get() = used <= 0 && hasMessages

    /** 占上限的比例 0–1（不知道上限时 0） */
    val fraction: Float
        get() = if (hasLimit) (used.toDouble() / limit!!).coerceIn(0.0, 1.0).toFloat() else 0f

    /** 「上下文 48.2K / 128K tokens（38%）」：读屏和面板里那行的完整说法 */
    val summary: String
        get() = if (hasLimit) {
            "上下文 ${compact(used)} / ${compact(limit!!)} tokens（${percent(fraction)}）"
        } else {
            "上下文 ${compact(used)} tokens（不知道这个模型的上限）"
        }

    /** 空余（没上限时 0） */
    val free: Long get() = if (hasLimit) (limit!! - used).coerceAtLeast(0) else 0

    /** 一部分占上限（没有上限时占用了的）多少：「38%」，不到 1% 写「<1%」 */
    fun share(tokens: Long): String {
        val base = if (hasLimit) limit!! else used
        if (base <= 0) return ""
        val value = tokens * 100.0 / base
        return if (value > 0 && value < 1) "<1%" else "${value.roundToLong()}%"
    }

    companion object {
        /** 1234 → 1.2K，131072 → 131K，1000000 → 1M（== Qt ContextUsage::compact） */
        fun compact(tokens: Long): String {
            fun trimmed(value: Double): String =
                if (value >= 100) value.roundToLong().toString()
                else String.format(Locale.US, "%.1f", value).replace(".0", "")
            return when {
                tokens >= 1_000_000 -> trimmed(tokens / 1_000_000.0) + "M"
                tokens >= 1_000 -> trimmed(tokens / 1_000.0) + "K"
                else -> tokens.toString()
            }
        }

        fun percent(fraction: Float): String = "${(fraction * 100).roundToLong()}%"

        /** 12345 → 12,345 */
        fun grouped(tokens: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)
    }
}

/**
 * 按会话里现在这条路径上的消息和现在用的模型算（== Qt ChatViewModel::computeContextUsage）。
 * [model] 为空（这台一个模型都没接入）时没有可说的，返回 null。
 */
fun contextUsageOf(messages: List<ChatMessage>, model: String?): ContextUsage? {
    if (model.isNullOrBlank()) return null
    // 最近一条带上下文的统计（正在生成的那条也在路径上）
    val stats = messages.asReversed().firstNotNullOfOrNull { m ->
        m.stats?.takeIf { (it.contextTokenCount ?: 0) > 0 }
    }
    val usage = ContextUsage(
        used = stats?.contextTokenCount ?: 0,
        model = model,
        parts = stats?.contextParts,
        hasMessages = messages.isNotEmpty(),
    )
    // 那次请求的模型就是现在这个（在线引擎的 engineName 是「服务商 · 模型名」）：用它记下的上限
    val sameModel = stats?.engineName.let { it == null || it.endsWith(" · $model") }
    val limit = stats?.contextLimit?.takeIf { it > 0 && sameModel }
    if (stats == null || limit == null) return usage
    return usage.copy(
        limit = limit,
        guessed = stats.contextLimitGuessed == true,
        compactAt = stats.contextCompactAt,
    )
}
