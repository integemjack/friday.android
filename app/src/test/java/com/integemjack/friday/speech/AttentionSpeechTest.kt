package com.integemjack.friday.speech

import android.app.NotificationManager
import android.media.AudioManager
import com.integemjack.friday.proto.AskQuestion
import com.integemjack.friday.proto.PendingApproval
import com.integemjack.friday.proto.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 后台语音播报念什么、什么时候不念（纯函数，不用引擎） */
class AttentionSpeechTest {

    private fun approval(title: String, tool: String = "Bash", question: String? = null) = PendingApproval(
        eventID = "E-$title",
        tool = tool,
        title = title,
        summary = "",
        questions = question?.let { listOf(AskQuestion(question = it, options = emptyList(), multiple = false, recommended = -1)) },
    )

    private fun activity(title: String, vararg approvals: PendingApproval) = SessionActivity(
        id = "S-$title", title = title, status = "approval", taskGoal = null, taskSteps = emptyList(),
        approvals = approvals.toList(), currentToolTitle = null, currentToolSummary = null, updatedAt = "",
    )

    @Test
    fun `待确认的几项连着会话标题一起列出来`() {
        val list = pendingApprovals(
            listOf(activity("改代码", approval("运行命令 npm test")), activity("空闲"), activity("发版", approval("推送"), approval("打标签")))
        )
        assertEquals(listOf("改代码", "发版", "发版"), list.map { it.first })
        assertEquals(listOf("运行命令 npm test", "推送", "打标签"), list.map { it.second.title })
    }

    @Test
    fun `一项：说哪段会话、什么调用`() {
        assertEquals("Friday 等你确认：改代码，运行命令 npm test", attentionSpeech(listOf("改代码" to approval("运行命令 npm test"))))
        // 没标题就念工具名；没会话标题就不提会话
        assertEquals("Friday 等你确认：改代码，Bash", attentionSpeech(listOf("改代码" to approval(""))))
        assertEquals("Friday 等你确认：运行命令", attentionSpeech(listOf("" to approval("运行命令"))))
    }

    @Test
    fun `提问：直接念问题，没问题文字就念标题`() {
        assertEquals(
            "Friday 在问你：发版，要推到 main 吗？",
            attentionSpeech(listOf("发版" to approval("提问", tool = "ask_user", question = "要推到 main 吗？"))),
        )
        assertEquals(
            "Friday 在问你：发版，提问",
            attentionSpeech(listOf("发版" to approval("提问", tool = "ask_user", question = " "))),
        )
    }

    @Test
    fun `好几项：念前两项，剩下的说个数`() {
        assertEquals(
            "Friday 有 2 项等你确认：推送、打标签",
            attentionSpeech(listOf("发版" to approval("推送"), "发版" to approval("打标签"))),
        )
        assertEquals(
            "Friday 有 3 项等你确认：推送、打标签，还有 1 项",
            attentionSpeech(listOf("发版" to approval("推送"), "发版" to approval("打标签"), "改代码" to approval("删文件"))),
        )
    }

    @Test
    fun `还没收到是哪项（activity 事件在后头）：说个大概`() {
        assertEquals("Friday 有调用等你确认", attentionSpeech(emptyList()))
    }

    @Test
    fun `念出来之前去掉 Markdown 记号、并成一行、太长截断`() {
        assertEquals("运行 npm test 现在", speakable("运行 `npm   test`\n**现在**", 40))
        assertEquals("一二三四…", speakable("一二三四五六", 4))
        val long = "x".repeat(100)
        assertEquals("Friday 等你确认：${"x".repeat(40)}…", attentionSpeech(listOf("" to approval(long))))
    }

    @Test
    fun `静音或震动、勿扰、打电话、跟助手通话时不念`() {
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        assertNull(quietReasonFor(AudioManager.RINGER_MODE_NORMAL, all, AudioManager.MODE_NORMAL, inAssistantCall = false))
        // 拿不到勿扰状态：当没开
        assertNull(quietReasonFor(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN, AudioManager.MODE_NORMAL, false))
        assertEquals("手机静音或震动", quietReasonFor(AudioManager.RINGER_MODE_VIBRATE, all, AudioManager.MODE_NORMAL, false))
        assertEquals("手机静音或震动", quietReasonFor(AudioManager.RINGER_MODE_SILENT, all, AudioManager.MODE_NORMAL, false))
        assertEquals("开着勿扰", quietReasonFor(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_PRIORITY, AudioManager.MODE_NORMAL, false))
        assertEquals("正在打电话", quietReasonFor(AudioManager.RINGER_MODE_NORMAL, all, AudioManager.MODE_IN_CALL, false))
        assertEquals("正在打电话", quietReasonFor(AudioManager.RINGER_MODE_NORMAL, all, AudioManager.MODE_RINGTONE, false))
        assertEquals("正在跟助手通话", quietReasonFor(AudioManager.RINGER_MODE_NORMAL, all, AudioManager.MODE_IN_COMMUNICATION, true))
    }
}
