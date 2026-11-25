package com.integemjack.friday.speech

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.integemjack.friday.AppModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/**
 * 助手页的新回复直接念（Robolectric 假引擎）：主机推来 assistant.entry，回复说完了、语音播报开着就念，
 * 说的过程中、同一条再推一次、开关关着都不念
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantReplySpeechTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    private fun model(): AppModel = AppModel(app).also { it.speakAssistantReplies() }

    /** 主机推来一条助手回复（assistant.entry） */
    private fun push(model: AppModel, id: String, text: String, streaming: Boolean = false, source: String = "remote") =
        runBlocking {
            model.client.handleEvent("assistant.entry", buildJsonObject {
                put("entry", buildJsonObject {
                    put("id", id)
                    put("at", "2026-11-25T08:00:00Z")
                    put("kind", "assistant")
                    put("source", source)
                    put("text", text)
                    if (streaming) put("streaming", true)
                })
            })
        }

    /** 收回复的协程在后台线程上，排到主线程那句要等一会儿：跑主线程直到起了引擎 */
    private fun awaitEngine(): ShadowTextToSpeech {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { return Shadow.extract(it) }
            Thread.sleep(10)
        }
        error("等了 5 秒没起引擎")
    }

    /** 等一会儿，确认什么也没排上来 */
    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `回复说完了直接念；在说的时候不念，同一条再推一次不重念`() {
        val model = model()
        push(model, "a1", "好的", streaming = true)
        settle()
        assertNull("还在说，不该起引擎", ShadowTextToSpeech.getLastTextToSpeechInstance())

        push(model, "a1", "好的，**测试**全过了")
        val tts = awaitEngine()
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("好的，测试全过了"), tts.spokenTextList)

        // 同一条又推一次（答了它问的、标了项目）：不再念
        push(model, "a1", "好的，**测试**全过了")
        settle()
        assertEquals(listOf("好的，测试全过了"), tts.spokenTextList)

        // 下一条主动汇报照念，排在后面
        push(model, "a2", "发版那段做完了", source = "notice")
        val deadline = System.currentTimeMillis() + 5_000
        while (tts.spokenTextList.size < 2 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(listOf("好的，测试全过了", "发版那段做完了"), tts.spokenTextList)
        assertEquals(TextToSpeech.QUEUE_ADD, tts.queueMode)
    }

    @Test
    fun `语音播报关着：不念`() {
        val model = model()
        model.settings.setSpeakInBackground(false)
        push(model, "a1", "好的，测试全过了")
        settle()
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun `桌面端语音模式那轮的回复：那边念了，手机不念`() {
        val model = model()
        push(model, "a1", "明天上午十点", source = "voice")
        settle()
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }
}
