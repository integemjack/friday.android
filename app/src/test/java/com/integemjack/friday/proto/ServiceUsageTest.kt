package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** 模型菜单里那一句余额 / 用量（== 桌面端 usageMenuLine） */
class ServiceUsageTest {

    private val now = Instant.parse("2026-11-04T06:00:00Z")

    private fun line(json: String): UsageLine? = usageMenuLine(Json.parseToJsonElement(json).jsonObject, now)

    @Test
    fun `只有余额的写余额`() {
        assertEquals(
            UsageLine("余额 ¥12.34", false),
            line("""{"supported":true,"report":{"balances":[{"amount":12.34,"currency":"CNY"}]}}"""),
        )
    }

    @Test
    fun `欠费的标提醒`() {
        assertEquals(
            UsageLine("余额 ¥0.00，欠费 ¥3.00", true),
            line("""{"report":{"balances":[{"amount":0,"currency":"CNY","owed":3}]}}"""),
        )
    }

    @Test
    fun `套餐窗口写剩多少，取用得最多的两个按原来的顺序`() {
        val text = line(
            """{"report":{"windows":[
                {"title":"5 小时","remainingPercent":91},
                {"title":"每天","limit":100,"used":5},
                {"title":"每周","limit":100,"remaining":78}
            ]}}"""
        )
        assertEquals(UsageLine("5 小时剩 91% · 每周剩 78%", false), text)
    }

    @Test
    fun `窗口用完了说用完了`() {
        val text = line("""{"report":{"windows":[{"title":"5 小时","remainingPercent":0}]}}""")
        assertEquals(true, text?.warning)
        assertEquals("5 小时用完了", text?.text)
    }

    @Test
    fun `额度用完还没恢复优先说这个`() {
        val text = line(
            """{"report":{"balances":[{"amount":5,"currency":"CNY"}]},
                "observed":{"quotaAt":"2026-11-04T05:50:00Z","resumeAt":"2099-01-01T00:00:00Z"}}"""
        )
        assertEquals(true, text?.warning)
        assertEquals(true, text?.text?.startsWith("额度用完，"))
        assertEquals(true, text?.text?.endsWith(" 恢复"))
    }

    @Test
    fun `余额不足`() {
        assertEquals(
            UsageLine("余额不足，充值后恢复", true),
            line("""{"observed":{"quotaAt":"2026-11-04T05:50:00Z","quotaKind":"balance"}}"""),
        )
    }

    @Test
    fun `用完以后又请求成功了就不再说`() {
        assertNull(line("""{"observed":{"quotaAt":"2026-11-04T05:00:00Z","okAt":"2026-11-04T05:30:00Z"}}"""))
    }

    @Test
    fun `本机地址不查`() {
        assertEquals(true, isLocalBaseURL("http://127.0.0.1:8080/v1"))
        assertEquals(true, isLocalBaseURL("http://192.168.1.5:11434"))
        assertEquals(true, isLocalBaseURL("power://friday-llm"))
        assertEquals(false, isLocalBaseURL("https://api.deepseek.com"))
    }

    @Test
    fun `键和服务端一样：地址小写去掉末尾的斜杠`() {
        assertEquals("https://api.deepseek.com/v1|DEEPSEEK", usageKey("https://API.deepseek.com/v1/", "DEEPSEEK"))
    }

    @Suppress("unused")
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
