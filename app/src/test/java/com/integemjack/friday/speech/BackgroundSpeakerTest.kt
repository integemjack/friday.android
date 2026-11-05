package com.integemjack.friday.speech

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale

/**
 * 后台语音播报（Robolectric 的假引擎）：起引擎、念、放焦点；不会说中文 / 没引擎；
 * 静音、跟助手通话、拿不到焦点时不念；去重；闲了放掉引擎
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundSpeakerTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val audio: AudioManager get() = app.getSystemService(AudioManager::class.java)
    private var calling = false
    private lateinit var speaker: BackgroundSpeaker

    @Before
    fun setUp() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        speaker = BackgroundSpeaker(app) { calling }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun lastEngine(): TextToSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()

    private fun shadow(tts: TextToSpeech): ShadowTextToSpeech = Shadow.extract(tts)

    /** 念一句，引擎随后回调初始化结果 */
    private fun speakAndInit(text: String, status: Int = TextToSpeech.SUCCESS): ShadowTextToSpeech {
        speaker.speak(text)
        idle()
        val tts = shadow(lastEngine())
        tts.onInitListener.onInit(status)
        idle()
        return tts
    }

    @Test
    fun `引擎起来以后念那一句：中文、顶掉上一句、别的声音压低，念完放焦点`() {
        val tts = speakAndInit("Friday 等你确认：改代码，运行命令")
        assertEquals(listOf("Friday 等你确认：改代码，运行命令"), tts.spokenTextList)
        assertEquals(TextToSpeech.QUEUE_FLUSH, tts.queueMode)
        assertEquals(Locale.SIMPLIFIED_CHINESE, tts.currentLanguage)
        assertEquals(BackgroundSpeaker.Engine.Ready, speaker.engine.value)

        val focus = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focus.focusGain)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, focus.audioAttributes.usage)
        // 假引擎念完会回调 onDone：焦点还回去
        assertSame(focus, shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `跟通知一起来：等提示音响完（1_5 秒）再起引擎念`() {
        speaker.speak("Friday 等你确认", delayMs = BackgroundSpeaker.AFTER_CHIME_MS)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(BackgroundSpeaker.AFTER_CHIME_MS - 100))
        assertNull("提示音还在响，不该起引擎", ShadowTextToSpeech.getLastTextToSpeechInstance())

        looper.idleFor(Duration.ofMillis(100))
        val tts = shadow(lastEngine())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("Friday 等你确认"), tts.spokenTextList)
    }

    @Test
    fun `引擎没起来时来了两句：只念后一句`() {
        speaker.speak("第一句")
        speaker.speak("第二句")
        idle()
        val tts = shadow(lastEngine())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("第二句"), tts.spokenTextList)
    }

    @Test
    fun `不会说中文：不念，设置页看得到，引擎放掉`() {
        ShadowTextToSpeech.reset() // 清掉 setUp 加的中文
        val tts = speakAndInit("Friday 等你确认")
        assertTrue(tts.spokenTextList.isEmpty())
        assertEquals(BackgroundSpeaker.Engine.NoChinese, speaker.engine.value)
        assertTrue(tts.isShutdown)
    }

    @Test
    fun `没有能用的引擎（初始化失败）：不念`() {
        val tts = speakAndInit("Friday 等你确认", status = TextToSpeech.ERROR)
        assertTrue(tts.spokenTextList.isEmpty())
        assertEquals(BackgroundSpeaker.Engine.Unavailable, speaker.engine.value)
        assertTrue(tts.isShutdown)
    }

    @Test
    fun `静音或震动时不念，试听照念`() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val tts = speakAndInit("Friday 等你确认")
        assertTrue(tts.spokenTextList.isEmpty())

        speaker.speak(BackgroundSpeaker.SAMPLE, force = true)
        idle()
        assertEquals(listOf(BackgroundSpeaker.SAMPLE), tts.spokenTextList)
    }

    @Test
    fun `跟助手通话时不念`() {
        calling = true
        val tts = speakAndInit("Friday 等你确认")
        assertTrue(tts.spokenTextList.isEmpty())
    }

    @Test
    fun `拿不到音频焦点（在打电话、后台没有前台服务）：不念`() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val tts = speakAndInit("Friday 等你确认")
        assertTrue(tts.spokenTextList.isEmpty())
    }

    @Test
    fun `同一句一分钟内只念一次，换一句照念`() {
        val tts = speakAndInit("A")
        speaker.speak("A")
        idle()
        speaker.speak("B")
        idle()
        assertEquals(listOf("A", "B"), tts.spokenTextList)
    }

    @Test
    fun `闲一分钟放掉引擎；过了一分钟同一句再来，重新起引擎照念`() {
        val tts = speakAndInit("A")
        val first = lastEngine()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue(tts.isShutdown)

        speaker.speak("A")
        idle()
        val again = lastEngine()
        assertNotSame(first, again)
        shadow(again).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("A"), shadow(again).spokenTextList)
    }
}
