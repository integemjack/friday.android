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

    private companion object {
        const val KEY_KEEP_ALIVE = "keepAlive"
    }
}
