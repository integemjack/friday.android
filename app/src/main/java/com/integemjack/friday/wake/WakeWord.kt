package com.integemjack.friday.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 语音唤醒（设置 › 后台保持连接 › 语音唤醒，默认关）：一直听「星期五」，听到就跟助手打语音电话。
 *
 * - 听要麦克风常开，得在后台保持连接的前台服务里（类型带 microphone）。Android 不让后台起带麦克风的前台服务：
 *   服务是在前台（打开 App）时起的才有；进程被系统收掉、在后台自己重启的话就听不了，等下次打开 App。
 *   [setServiceMic] 由 ConnectionService 告诉这里有没有拿到
 * - 跟助手通话时、输入框在听写时（[hold]）把麦克风放掉；手机在念播报、在打电话时不认（麦克风不放）
 * - 状态 [status] 给设置页和常驻通知看；[heard] 是最近一次认出来的词和叫没叫醒
 */
class WakeWord(
    private val app: Context,
    private val scope: CoroutineScope,
    private val enabled: StateFlow<Boolean>,
    private val inCall: StateFlow<Boolean>,
    /** 手机在念播报 */
    private val speaking: () -> Boolean,
    private val onWake: () -> Unit,
    engineFactory: () -> KeywordEngine = { SherpaEngine(app) },
    micFactory: () -> MicSource = { AudioRecordMic() },
) {
    enum class Phase {
        /** 设置里没开 */
        Off,
        /** 没有麦克风权限 */
        NeedsMic,
        /** 后台保持连接没开着，或者服务没拿到麦克风（后台重启的）：打开一次 App 就好 */
        NeedsService,
        /** 跟助手通话中、在听写：暂停 */
        Paused,
        Starting,
        Listening,
        /** 麦克风开不了，过一会儿自己再试 */
        MicFailed,
        /** 这台手机跑不了唤醒模型 */
        Unsupported,
    }

    data class Status(val phase: Phase, val detail: String? = null)

    private val listener = WakeListener(
        engineFactory = engineFactory,
        micFactory = micFactory,
        busy = { speaking() || phoneBusy() },
        onWake = onWake,
    )

    private val _status = MutableStateFlow(Status(Phase.Off))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 最近一次认出来的词（叫没叫醒、为什么没叫） */
    val heard: StateFlow<WakeListener.Heard?> = listener.heard

    private val serviceMic = MutableStateFlow(false)
    private val holds = ConcurrentHashMap.newKeySet<String>()
    private val holdTick = MutableStateFlow(0)

    fun start() {
        scope.launch {
            // 听写的 hold / release、poke 只动 holdTick：它也要算进去，不然 distinctUntilChanged 把它吃掉
            combine(enabled, inCall, serviceMic, holdTick) { on, calling, mic, tick -> listOf(on, calling, mic, tick) }
                .distinctUntilChanged()
                .collect { (_, calling, _, _) ->
                    calling as Boolean
                    // 刚挂电话：等一会儿再听（别把通话的尾巴听进去）
                    if (wasInCall && !calling) delay(RESUME_AFTER_CALL_MS)
                    wasInCall = calling
                    sync()
                }
        }
        scope.launch { listener.status.collect { refresh() } }
    }

    private var wasInCall = false

    /** ConnectionService 进了前台：带没带麦克风类型 */
    fun setServiceMic(on: Boolean) {
        serviceMic.value = on
    }

    /** 暂时别占麦克风（[reason]：听写之类）；[release] 同一个 reason 放开 */
    fun hold(reason: String) {
        if (holds.add(reason)) holdTick.value++
    }

    fun release(reason: String) {
        if (holds.remove(reason)) holdTick.value++
    }

    /** 重新看一遍（打开 App 时：麦克风权限可能在系统设置里改过） */
    fun poke() {
        holdTick.value++
    }

    /** 想不想开着麦克风听（ConnectionService 据此决定服务类型带不带麦克风） */
    fun wanted(): Boolean = enabled.value && micGranted()

    @Synchronized
    private fun sync() {
        val listen = wanted() && serviceMic.value && !inCall.value && holds.isEmpty()
        if (listen) listener.start() else listener.stop()
        if (!enabled.value) listener.release()
        refresh()
    }

    private fun refresh() {
        _status.value = when {
            !enabled.value -> Status(Phase.Off)
            !micGranted() -> Status(Phase.NeedsMic)
            !serviceMic.value -> Status(Phase.NeedsService)
            inCall.value -> Status(Phase.Paused, "跟助手通话中")
            holds.isNotEmpty() -> Status(Phase.Paused, "在用语音输入")
            else -> when (val s = listener.status.value) {
                WakeListener.Status.Listening -> Status(Phase.Listening)
                is WakeListener.Status.MicFailed -> Status(Phase.MicFailed, s.message)
                is WakeListener.Status.Unsupported -> Status(Phase.Unsupported, s.message)
                else -> Status(Phase.Starting)
            }
        }
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 在打电话（系统通话、微信语音这类通话模式）：不认 */
    private fun phoneBusy(): Boolean {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    companion object {
        const val RESUME_AFTER_CALL_MS = 1_500L
    }
}
