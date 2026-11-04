package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「＋」面板里的上下文用量（== Qt ChatViewModel::computeContextUsage、ContextUsage::compact） */
class ContextUsageTest {

    private fun assistant(id: String, stats: GenerationStats?) =
        ChatMessage(id = id, role = "assistant", blocks = emptyList(), createdAt = "2026-11-05T00:00:00Z", stats = stats)

    private fun user(id: String) = ChatMessage(id = id, role = "user", blocks = emptyList(), createdAt = "2026-11-05T00:00:00Z")

    @Test
    fun `数字缩写同桌面端`() {
        assertEquals("0", ContextUsage.compact(0))
        assertEquals("999", ContextUsage.compact(999))
        assertEquals("1.2K", ContextUsage.compact(1234))
        assertEquals("2K", ContextUsage.compact(2000))
        assertEquals("48.2K", ContextUsage.compact(48_200))
        assertEquals("131K", ContextUsage.compact(131_072))
        assertEquals("1M", ContextUsage.compact(1_000_000))
        assertEquals("1.5M", ContextUsage.compact(1_500_000))
        assertEquals("48,200", ContextUsage.grouped(48_200))
    }

    @Test
    fun `消息统计里的上下文字段都读出来`() {
        val stats = GenerationStats.fromJson(
            Json.parseToJsonElement(
                """{"tokensPerSecond":30,"tokenCount":120,"engineName":"智谱 · glm-4.6","contextTokenCount":48200,
                   "contextParts":{"system":2100,"tools":6000,"user":10000,"replies":20100,"toolUse":10000},
                   "contextLimit":128000,"contextLimitGuessed":true,"contextCompactAt":100000}"""
            ).jsonObject
        )
        assertEquals(48_200L, stats.contextTokenCount)
        assertEquals(ContextParts(2100, 6000, 10000, 20100, 10000), stats.contextParts)
        assertEquals(48_200L, stats.contextParts!!.total)
        assertEquals(128_000L, stats.contextLimit)
        assertEquals(true, stats.contextLimitGuessed)
        assertEquals(100_000L, stats.contextCompactAt)
    }

    @Test
    fun `取路径上最近一条带上下文的统计，上限按它记的`() {
        val messages = listOf(
            user("1"),
            assistant("2", GenerationStats(1.0, 10, "智谱 · glm-4.6", contextTokenCount = 9_000, contextLimit = 128_000)),
            user("3"),
            assistant(
                "4",
                GenerationStats(
                    1.0, 10, "智谱 · glm-4.6",
                    contextTokenCount = 48_200, contextLimit = 128_000, contextLimitGuessed = false,
                    contextParts = ContextParts(2100, 6000, 10000, 20100, 10000),
                ),
            ),
            // 正在生成、还没报上下文的那条：跳过，用上一条
            assistant("5", GenerationStats(1.0, 3, "智谱 · glm-4.6")),
        )
        val usage = contextUsageOf(messages, "glm-4.6")!!
        assertEquals(48_200L, usage.used)
        assertEquals(128_000L, usage.limit)
        assertFalse(usage.guessed)
        assertEquals("38%", ContextUsage.percent(usage.fraction))
        assertEquals(79_800L, usage.free)
        assertEquals("2%", usage.share(2100))
        assertEquals("<1%", usage.share(500))
        assertEquals("上下文 48.2K / 128K tokens（38%）", usage.summary)
    }

    @Test
    fun `这一轮换了模型：上一个模型的上限不用`() {
        val messages = listOf(
            assistant("1", GenerationStats(1.0, 10, "Anthropic · claude-sonnet-4.5", contextTokenCount = 48_200, contextLimit = 200_000)),
        )
        val usage = contextUsageOf(messages, "glm-4.6")!!
        assertEquals(48_200L, usage.used)
        assertNull(usage.limit)
        assertFalse(usage.hasLimit)
        assertEquals(0f, usage.fraction)
        assertEquals("33%", usage.share(16_000))
    }

    @Test
    fun `本地引擎没有 engineName 也用它记的上限`() {
        val messages = listOf(
            assistant("1", GenerationStats(1.0, 10, null, contextTokenCount = 3_000, contextLimit = 8_192, contextCompactAt = 6_000)),
        )
        val usage = contextUsageOf(messages, "qwen3-8b")!!
        assertEquals(8_192L, usage.limit)
        assertEquals(6_000L, usage.compactAt)
    }

    @Test
    fun `没接入模型不显示；没消息是空的；有消息没统计是还没记下`() {
        assertNull(contextUsageOf(emptyList(), null))
        assertNull(contextUsageOf(listOf(user("1")), ""))

        val empty = contextUsageOf(emptyList(), "glm-4.6")!!
        assertEquals(0L, empty.used)
        assertFalse(empty.unrecorded)

        val old = contextUsageOf(listOf(user("1"), assistant("2", GenerationStats(1.0, 10))), "glm-4.6")!!
        assertTrue(old.unrecorded)
        assertNull(old.parts)
    }
}
