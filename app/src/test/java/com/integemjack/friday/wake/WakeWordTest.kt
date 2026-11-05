package com.integemjack.friday.wake

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.integemjack.friday.AppModel
import com.integemjack.friday.ConnectionService
import com.integemjack.friday.call.AssistantCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** 语音唤醒的开关、状态，什么时候开麦克风 / 放掉；叫醒以后打语音电话 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeWordTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 一直出安静声音的麦克风（每块睡 5 毫秒）；记着开了几次、关了几次 */
    private class QuietMic(private val opened: AtomicInteger, private val closed: AtomicInteger) : MicSource {
        override fun start(): Boolean {
            opened.incrementAndGet()
            return true
        }

        override fun read(buffer: FloatArray): Int {
            Thread.sleep(5)
            buffer.fill(0f)
            return buffer.size
        }

        override fun stop() {
            closed.incrementAndGet()
        }
    }

    private class NoEngine : KeywordEngine {
        var released = false
        override fun newSegment() {}
        override fun accept(samples: FloatArray): SpottedWord? = null
        override fun release() {
            released = true
        }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("等不到：$what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `状态：没开、没麦克风权限、服务没拿到麦克风、在听、通话中暂停、听写时暂停`() {
        val enabled = MutableStateFlow(false)
        val inCall = MutableStateFlow(false)
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val engine = NoEngine()
        val wake = WakeWord(
            app, scope, enabled, inCall,
            speaking = { false },
            onWake = {},
            engineFactory = { engine },
            micFactory = { QuietMic(opened, closed) },
        )
        wake.start()
        waitFor("没开") { wake.status.value.phase == WakeWord.Phase.Off }
        assertFalse(wake.wanted())

        enabled.value = true
        waitFor("没麦克风权限") { wake.status.value.phase == WakeWord.Phase.NeedsMic }
        assertFalse(wake.wanted())

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(wake.wanted())
        wake.poke()
        waitFor("服务没拿到麦克风") { wake.status.value.phase == WakeWord.Phase.NeedsService }
        assertEquals(0, opened.get())

        wake.setServiceMic(true)
        waitFor("在听") { wake.status.value.phase == WakeWord.Phase.Listening }
        assertEquals(1, opened.get())

        inCall.value = true
        waitFor("通话中暂停") { wake.status.value == WakeWord.Status(WakeWord.Phase.Paused, "跟助手通话中") }
        waitFor("通话时放掉麦克风") { closed.get() == 1 }

        // 挂了：等一会儿（别听进通话的尾巴）再开
        inCall.value = false
        waitFor("挂了以后再听") { wake.status.value.phase == WakeWord.Phase.Listening }
        assertEquals(2, opened.get())

        wake.hold("dictation")
        waitFor("听写时暂停") { wake.status.value == WakeWord.Status(WakeWord.Phase.Paused, "在用语音输入") }
        waitFor("听写时放掉麦克风") { closed.get() == 2 }
        wake.release("dictation")
        waitFor("听写完再听") { wake.status.value.phase == WakeWord.Phase.Listening }

        // 关掉：麦克风放掉，模型也放掉
        enabled.value = false
        waitFor("关掉") { wake.status.value.phase == WakeWord.Phase.Off }
        waitFor("关掉放掉麦克风") { closed.get() == 3 }
        assertTrue(engine.released)
    }

    @Test
    fun `前台服务的类型：开着语音唤醒、有麦克风权限才带麦克风`() {
        val special = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val camera = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        assertEquals(special, ConnectionService.foregroundTypes(34, inCall = false, video = false, wakeWord = false, mic = true, camera = true))
        assertEquals(special or mic, ConnectionService.foregroundTypes(34, inCall = false, video = false, wakeWord = true, mic = true, camera = true))
        assertEquals(special, ConnectionService.foregroundTypes(34, inCall = false, video = false, wakeWord = true, mic = false, camera = true))
        assertEquals(special or mic or camera, ConnectionService.foregroundTypes(34, inCall = true, video = true, wakeWord = false, mic = true, camera = true))
        assertEquals(mic, ConnectionService.foregroundTypes(33, inCall = false, video = false, wakeWord = true, mic = true, camera = false))
        assertEquals(0, ConnectionService.foregroundTypes(29, inCall = true, video = true, wakeWord = true, mic = true, camera = true))
    }

    @Test
    fun `常驻通知末尾写在听「星期五」；后台没拿到麦克风写打开一次 App；通话时不写`() {
        assertEquals(" · 在听「星期五」", ConnectionService.wakeSuffix(WakeWord.Phase.Listening, calling = false))
        assertEquals(" · 语音唤醒暂停：打开一次 Friday 恢复", ConnectionService.wakeSuffix(WakeWord.Phase.NeedsService, calling = false))
        assertEquals("", ConnectionService.wakeSuffix(WakeWord.Phase.Listening, calling = true))
        assertEquals("", ConnectionService.wakeSuffix(WakeWord.Phase.Off, calling = false))
    }

    @Test
    fun `叫醒：打给上次连的那台，只开声音；聊天服务器没连上就念一句为什么`() {
        org.robolectric.shadows.ShadowTextToSpeech.reset()
        val model = AppModel(app)
        model.start()
        model.schat.selectBot(7, "Mac")
        model.wakeUp()
        val state = model.call.state.value
        assertFalse(state.video)
        assertEquals(AssistantCall.Phase.Ended, state.phase)
        assertEquals("聊天服务器没连上，打不了电话", state.message)
        // 没打通：念一句为什么（排到主线程上，起了文字转语音引擎）
        waitFor("起了文字转语音引擎") {
            shadowOf(Looper.getMainLooper()).idle()
            org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance() != null
        }
    }

    @Test
    fun `自己点挂断：直接收起，不停在「通话结束」那一页`() {
        val model = AppModel(app)
        model.call.showDemo(AssistantCall.State(phase = AssistantCall.Phase.Active, video = false, peerName = "助手", connectedAt = 1))
        model.call.hangup()
        assertEquals(AssistantCall.State(), model.call.state.value)
        assertFalse(model.call.state.value.inCall)
    }

    @Test
    fun `叫醒：还没选过机器人就不打，念一句`() {
        org.robolectric.shadows.ShadowTextToSpeech.reset()
        val model = AppModel(app)
        model.wakeUp()
        assertEquals(AssistantCall.Phase.Idle, model.call.state.value.phase)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance() != null)
    }
}
