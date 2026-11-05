package com.integemjack.friday.notify

import android.app.Notification
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * Android 16 的实时活动（promoted ongoing / Live Update）：通知提升成状态栏上的一枚胶囊，
 * 小米 HyperOS 把它接到「超级岛」上——不要厂商审核。做法照 Coast（`live/LiveUpdate.kt`，小米 17 上实测过）：
 *
 * - `NotificationCompat.setRequestPromotedOngoing` 要 androidx.core 1.16 / compileSdk 36，这里还是 1.15 / 35：
 *   它本身就是往 extras 写一个 boolean，手写同一个 key 等价。key 先反射取系统真值，取不到用 android-36.1 SDK 里的字面量
 * - 胶囊上的几个字是 extras 的 `android.shortCriticalText`（常量是 @hide，Coast 在真机上探出来的）；
 *   少于 7 个字，长了静默退回只显示图标
 * - 准入条件归调用方：`setOngoing(true)`、有标题、标准 style（BigTextStyle 可以）、**不** `setColorized`。
 *   哪条不满足都只是退回普通通知，不报错
 */
internal object LiveIsland {

    private val requestKey: String = runCatching {
        Notification::class.java.getField("EXTRA_REQUEST_PROMOTED_ONGOING").get(null) as String
    }.getOrNull() ?: "android.requestPromotedOngoing"

    const val SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    /** `Notification.FLAG_PROMOTED_ONGOING`（android-36 起 = 0x40000） */
    val flagPromoted: Int = runCatching {
        Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
    }.getOrNull() ?: 0x0004_0000

    val requestExtra: String get() = requestKey

    /** 请求提升，胶囊上写 [chip]。Android 16 以下什么都不做（那个 extra 没人读） */
    fun request(builder: NotificationCompat.Builder, chip: String?, sdk: Int = Build.VERSION.SDK_INT) {
        if (sdk < 36) return
        builder.addExtras(Bundle().apply {
            putBoolean(requestKey, true)
            if (!chip.isNullOrEmpty()) putString(SHORT_CRITICAL_TEXT, chip)
        })
    }
}
