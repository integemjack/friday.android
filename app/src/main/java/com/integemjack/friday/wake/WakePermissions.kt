package com.integemjack.friday.wake

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * 叫醒以后锁屏上弹通话页要的系统权限。
 *
 * 小米（MIUI / HyperOS）自己还有两项，侧装的 App 默认都关着（小米 10 上实测：不开的话通话页起不来，
 * 只开「后台弹出界面」时起来了但屏幕不亮、压在锁屏下面）：
 * - 锁屏显示（MIUI 的权限编号 10020）
 * - 后台弹出界面（10021）
 */
object WakePermissions {
    const val MIUI_SHOW_WHEN_LOCKED = 10020
    const val MIUI_BACKGROUND_START = 10021

    fun isXiaomi(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.equals("xiaomi", ignoreCase = true) || it.equals("redmi", ignoreCase = true) || it.equals("poco", ignoreCase = true)
    }

    /** MIUI 的那几项开没开（查不到是 null：新系统换了编号、或者不让反射） */
    fun miuiAllowed(context: Context, op: Int): Boolean? = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val check = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        (check.invoke(ops, op, Process.myUid(), context.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /** 小米的「应用权限」页（有「锁屏显示」「后台弹出界面」）；打不开就退到系统的应用详情 */
    fun openMiuiPermissions(context: Context) {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(editor) }.onFailure { openAppDetails(context) }
    }

    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Android 14+ 全屏通知要用户允许（侧装的一般默认给；不给的话锁屏时只是一条横幅） */
    fun canFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
    }

    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }
}
