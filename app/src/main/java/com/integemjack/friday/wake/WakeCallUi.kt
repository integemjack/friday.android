package com.integemjack.friday.wake

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.integemjack.friday.FridayApp
import com.integemjack.friday.R

/**
 * 叫醒以后把通话页摆到眼前：
 * - App 在前台：主界面上本来就盖着通话页，什么都不用做
 * - 不在前台：直接起 [WakeCallActivity]（默认助理、或系统放行后台弹界面时起得来）；
 *   [FALLBACK_DELAY_MS] 后还没摆出来，就发一条带全屏意图（fullScreenIntent）的通知兜底——
 *   熄屏 / 锁屏时系统自己把通话页拉起来，亮屏用着别的 App 时是一条横幅（点开、或直接点「挂断」）
 *
 * 起不来也不要紧：通话照样在打（只有声音），常驻通知上也有「挂断」
 */
class WakeCallUi(
    private val app: Context,
    private val inForeground: () -> Boolean,
    private val stillInCall: () -> Boolean,
    private val canNotify: () -> Boolean,
    private val hangup: () -> PendingIntent,
) {
    private val main = Handler(Looper.getMainLooper())

    fun show() {
        if (inForeground()) return
        runCatching { app.startActivity(callIntent()) }
        main.postDelayed({
            if (!WakeCallActivity.visible && !inForeground() && stillInCall()) postFallback()
        }, FALLBACK_DELAY_MS)
    }

    fun hide() {
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
    }

    private fun callIntent(): Intent = Intent(app, WakeCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @SuppressLint("MissingPermission") // canNotify() 查过
    private fun postFallback() {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(
            app, 0, callIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, FridayApp.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Friday 在听")
            .setContentText("说吧，说完点挂断")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "挂断", hangup())
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val NOTIFICATION_ID = 1005
        const val FALLBACK_DELAY_MS = 700L
    }
}
