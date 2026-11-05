package com.integemjack.friday.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 这台手机上的几个开关（不跟账号走） */
class AppSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    private val _keepAlive = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALIVE, true))

    /** 后台保持连接（前台服务 + 常驻通知 + 不让 CPU 睡死）；默认开 */
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    fun setKeepAlive(on: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, on).apply()
        _keepAlive.value = on
    }

    private val _speakInBackground = MutableStateFlow(prefs.getBoolean(KEY_SPEAK_IN_BACKGROUND, true))

    /** 后台语音播报（在后台时有调用等你确认，除了通知再念一句）；默认开 */
    val speakInBackground: StateFlow<Boolean> = _speakInBackground.asStateFlow()

    fun setSpeakInBackground(on: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_IN_BACKGROUND, on).apply()
        _speakInBackground.value = on
    }

    private val _liveIsland = MutableStateFlow(prefs.getBoolean(KEY_LIVE_ISLAND, true))

    /** 待确认的那条通知提升成实时活动（状态栏胶囊 / 小米超级岛），答完才消失；默认开 */
    val liveIsland: StateFlow<Boolean> = _liveIsland.asStateFlow()

    fun setLiveIsland(on: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_ISLAND, on).apply()
        _liveIsland.value = on
    }

    private companion object {
        const val KEY_KEEP_ALIVE = "keepAlive"
        const val KEY_SPEAK_IN_BACKGROUND = "speakInBackground"
        const val KEY_LIVE_ISLAND = "liveIsland"
    }
}
