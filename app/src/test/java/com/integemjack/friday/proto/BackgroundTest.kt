package com.integemjack.friday.proto

import com.integemjack.friday.ui.formatWhen
import com.integemjack.friday.ui.loopStatusLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 后台任务：终端、定时任务的解析，触发方式怎么写，终端输出怎么洗干净 */
class BackgroundTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `TerminalsState 解出命令、在不在跑、退出码`() {
        val list = TerminalInfo.listFrom(
            obj(
                """{"terminals":[{"id":"T1","conversationID":"C1","title":"pwsh","shell":"pwsh","command":"ctest -R http",
                   "running":false,"exitCode":1,"cols":117,"rows":31}],"available":true}"""
            )
        )
        val t = list.single()
        assertEquals("ctest -R http", t.label)
        assertEquals(false, t.running)
        assertEquals(1, t.exitCode)
    }

    @Test
    fun `定时任务的触发方式写成人话（同 Qt）`() {
        fun loop(trigger: String) = LoopTask.fromJson(obj("""{"id":"L","number":1,"trigger":$trigger,"content":"x","target":"conversation","createdBy":"agent","runCount":0}"""))
        assertEquals("每 10 分钟", loop("""{"kind":"interval","minutes":10}""").describeTrigger(shanghai))
        assertEquals("每 2 小时", loop("""{"kind":"interval","minutes":120}""").describeTrigger(shanghai))
        assertEquals("每天一次", loop("""{"kind":"interval","minutes":1440}""").describeTrigger(shanghai))
        assertEquals("工作日 17:45", loop("""{"kind":"weekly","days":31,"minuteOfDay":1065}""").describeTrigger(shanghai))
        assertEquals("每周一、三 10:00", loop("""{"kind":"weekly","days":5,"minuteOfDay":600}""").describeTrigger(shanghai))
        assertEquals("每天 9:00", loop("""{"kind":"weekly","days":127,"minuteOfDay":540}""").describeTrigger(shanghai))
        assertEquals("自己定节奏", loop("""{"kind":"self"}""").describeTrigger(shanghai))
        assertEquals("11月5日 14:00 一次", loop("""{"kind":"once","at":"2026-11-05T06:00:00Z"}""").describeTrigger(shanghai))
    }

    @Test
    fun `LoopsState 解出目标会话、下次、在跑、暂停；标题没有用内容第一行`() {
        val tasks = LoopTask.listFrom(
            obj(
                """{"tasks":[{"id":"L1","number":23,"trigger":{"kind":"interval","minutes":30},"content":"\n盯着 CI\n过了说一声",
                   "target":"conversation","conversationID":"C1","createdBy":"agent","nextRun":"2026-11-05T09:45:00Z",
                   "runStartedAt":"2026-11-05T09:15:00Z","paused":true,"runCount":4,"lastResult":"还在跑"}]}"""
            )
        )
        val t = tasks.single()
        assertEquals("盯着 CI", t.label)
        assertEquals("C1", t.conversationID)
        assertTrue(t.running)
        assertTrue(t.paused)
        assertEquals(4, t.runCount)
    }

    @Test
    fun `状态行：下次今天写几点、明天写明天；在跑、暂停照实写`() {
        val now = Instant.parse("2026-11-05T02:00:00Z").toEpochMilli() // 上海 10:00
        fun loop(next: String?, running: Boolean = false, paused: Boolean = false) = LoopTask(
            "L", 1, "interval", 30, null, null, null, "x", "conversation", "C1", null, "agent",
            next, null, if (running) "2026-11-05T01:59:00Z" else null, paused, 2, null,
        )
        assertEquals("每 30 分钟 · 下次 10:30 · 跑过 2 次", loopStatusLine(loop("2026-11-05T02:30:00Z"), now, shanghai))
        assertEquals("每 30 分钟 · 在跑 · 跑过 2 次", loopStatusLine(loop("2026-11-05T02:30:00Z", running = true), now, shanghai))
        assertEquals("每 30 分钟 · 已暂停 · 跑过 2 次", loopStatusLine(loop("2026-11-05T02:30:00Z", paused = true), now, shanghai))
        assertEquals("明天 09:45", formatWhen("2026-11-06T01:45:00Z", now, shanghai))
        assertEquals("11月9日 09:45", formatWhen("2026-11-09T01:45:00Z", now, shanghai))
    }

    @Test
    fun `终端原始输出去掉颜色和光标控制，进度条只看最后的样子，只留最后几行`() {
        val raw = "\u001B[32mPASS\u001B[0m test_bpe\r\n" +
            "\u001B]0;title\u0007downloading 10%\rdownloading 50%\rdownloading 100%\n" +
            "\u001B[2K\u001B[1Gdone\n\n"
        assertEquals("PASS test_bpe\ndownloading 100%\ndone", terminalText(raw.toByteArray()))
        val many = (1..200).joinToString("\n") { "line $it" }
        val tail = terminalText(many.toByteArray(), maxLines = 3)
        assertEquals("line 198\nline 199\nline 200", tail)
    }
}
