package com.integemjack.friday.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.integemjack.friday.proto.AskOption
import com.integemjack.friday.proto.AskQuestion
import com.integemjack.friday.proto.PendingApproval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「待确认」通知（Robolectric）：发出去的样子、按通知上真的按钮（取它的 PendingIntent）发给那台、
 * 答完换下一项 / 收掉、没发出去写原因、别处答了跟着收、只有新来的才响
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttentionNotifierTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    private var pending: List<Pair<String, PendingApproval>> = emptyList()
    private var island = true
    private val sent = mutableListOf<Triple<String, Boolean, Pair<List<String>?, String?>>>()
    private var answer: (String) -> Result<Unit> = { id ->
        pending = pending.filter { it.second.eventID != id } // 那台答上了：activity 里没了
        Result.success(Unit)
    }

    private lateinit var notifier: AttentionNotifier

    @Before
    fun setUp() {
        notifier = AttentionNotifier(
            app,
            island = { island },
            pending = { pending },
            resolve = { eventID, approved, answers, reply ->
                sent += Triple(eventID, approved, answers to reply)
                answer(eventID)
            },
            canNotify = { true },
            contentIntent = { PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE) },
        )
    }

    private fun approval(id: String, title: String) =
        PendingApproval(eventID = id, tool = "Bash", title = title, summary = "", questions = null)

    private fun question(id: String, text: String, vararg options: String) = PendingApproval(
        eventID = id, tool = "ask_user", title = "提问", summary = text,
        questions = listOf(AskQuestion(text, options.map { AskOption(it, null) }, false, -1)),
    )

    private fun shown(): Notification? = shadowOf(nm).getNotification(AttentionNotifier.NOTIFICATION_ID)

    private fun press(label: String, reply: String? = null) = runBlocking {
        val action = shown()!!.actions.first { it.title.toString() == label }
        val intent = Intent(shadowOf(action.actionIntent).savedIntent)
        if (reply != null) {
            val input = RemoteInput.Builder(AttentionNotifier.KEY_REPLY).build()
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(AttentionNotifier.KEY_REPLY, reply) })
        }
        notifier.handle(intent)
    }

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun alerts(n: Notification) = n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0

    @Test
    fun `提问：常驻、选项和「回答…」都在、按钮要先解锁，点选项发答案后收掉`() {
        pending = listOf("测试" to question("Q1", "手机念出来了吗？", "听到了", "没听到"))
        assertTrue(notifier.onAttention())
        val n = shown()!!
        assertEquals("Friday 在问你", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("测试：手机念出来了吗？", text(n))
        assertTrue("灵动岛开着：常驻到答完", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(alerts(n))
        assertEquals(listOf("听到了", "没听到", "回答…"), n.actions.map { it.title.toString() })
        assertTrue(n.actions.all { it.isAuthenticationRequired })
        assertEquals(AttentionNotifier.KEY_REPLY, n.actions[2].remoteInputs.single().resultKey)

        press("听到了")
        assertEquals(Triple("Q1", true, listOf("听到了") to null as String?), sent.single())
        assertNull("答完了收掉", shown())
    }

    @Test
    fun `回答…写一句：每题空着、话在 reply；什么都没写不发`() {
        pending = listOf("测试" to question("Q1", "怎么办？", "A", "B"))
        notifier.onAttention()
        press("回答…", reply = "   ")
        assertTrue(sent.isEmpty())
        assertTrue("没发：通知还在", shown() != null)

        press("回答…", reply = "先放着，明天再说")
        assertEquals(Triple("Q1", true, listOf("") to "先放着，明天再说"), sent.single())
        assertNull(shown())
    }

    @Test
    fun `好几项：批准第一项后换成下一项，不再响`() {
        pending = listOf("发版" to approval("A1", "推送"), "改代码" to approval("A2", "删文件"))
        notifier.onAttention()
        assertEquals("发版：推送", text(shown()!!))
        assertEquals(listOf("批准", "拒绝"), shown()!!.actions.map { it.title.toString() })

        press("批准")
        assertEquals(Triple("A1", true, null to null as String?), sent.single())
        val next = shown()!!
        assertEquals("改代码：删文件", text(next))
        assertFalse("换项不响", alerts(next))

        press("拒绝")
        assertEquals(false, sent.last().second)
        assertNull(shown())
    }

    @Test
    fun `没发出去（没连上）：写原因、按钮留着；那台已经不在等了就当答完`() {
        pending = listOf("发版" to approval("A1", "推送"))
        answer = { Result.failure(Exception("没连上远程 Friday")) }
        notifier.onAttention()
        press("批准")
        assertEquals("没发出去：没连上远程 Friday", text(shown()!!))
        assertEquals(listOf("批准", "拒绝"), shown()!!.actions.map { it.title.toString() })

        // 别处已经答了：那台回错误，activity 里也没了
        answer = { id -> pending = pending.filter { it.second.eventID != id }; Result.failure(Exception("会话已经不在等这个了")) }
        press("批准")
        assertNull(shown())
    }

    @Test
    fun `别处答了：跟着换或收掉；通知不在时不会自己冒出来`() {
        pending = listOf("a" to approval("A1", "推送"), "b" to approval("A2", "删文件"))
        notifier.onAttention()
        notifier.onPendingChanged(listOf("b" to approval("A2", "删文件")))
        assertEquals("b：删文件", text(shown()!!))
        notifier.onPendingChanged(emptyList())
        assertNull(shown())

        notifier.onPendingChanged(listOf("c" to approval("A3", "新的")))
        assertNull("只有 attention（在后台）才发", shown())
    }

    @Test
    fun `activity 比 attention 晚到：先发个大概，到了换成那一项、不再响`() {
        pending = emptyList()
        notifier.onAttention()
        assertEquals("远程会话里有调用在等你批准或回答", text(shown()!!))
        assertTrue(shown()!!.actions.isNullOrEmpty())

        pending = listOf("测试" to question("Q1", "听到了吗", "听到了", "没听到"))
        notifier.onPendingChanged(pending)
        assertEquals("测试：听到了吗", text(shown()!!))
        assertFalse(alerts(shown()!!))
    }

    @Test
    fun `同一项又来一次 attention 不再响，来了新的一项才响`() {
        pending = listOf("a" to approval("A1", "推送"))
        notifier.onAttention()
        notifier.onAttention()
        assertFalse(alerts(shown()!!))
        pending = pending + ("b" to approval("A2", "删文件"))
        notifier.onAttention()
        assertTrue(alerts(shown()!!))
    }

    @Test
    fun `灵动岛关着：普通通知，点开就消失，按钮照样有`() {
        island = false
        pending = listOf("a" to approval("A1", "推送"))
        notifier.onAttention()
        val n = shown()!!
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(listOf("批准", "拒绝"), n.actions.map { it.title.toString() })
    }

    @Test
    fun `提升成实时活动：Android 16 起写请求和胶囊上的字，以下什么都不写`() {
        val builder = NotificationCompat.Builder(app, "c")
        LiveIsland.request(builder, "在问你", sdk = 36)
        val extras = builder.build().extras
        assertTrue(extras.getBoolean(LiveIsland.requestExtra))
        assertEquals("在问你", extras.getString(LiveIsland.SHORT_CRITICAL_TEXT))

        val old = NotificationCompat.Builder(app, "c")
        LiveIsland.request(old, "在问你", sdk = 35)
        assertFalse(old.build().extras.containsKey(LiveIsland.requestExtra))
    }
}
