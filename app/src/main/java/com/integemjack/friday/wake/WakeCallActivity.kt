package com.integemjack.friday.wake

import android.app.NotificationManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.integemjack.friday.FridayApp
import com.integemjack.friday.call.AssistantCall
import com.integemjack.friday.ui.CallScreen
import com.integemjack.friday.ui.LocalAppModel
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.coroutines.launch

/**
 * 说「星期五」叫起来的通话页：熄屏时点亮屏幕、锁着时显示在锁屏上面（不解锁），只有通话那一页
 * （App 别的内容不露在锁屏上）。通话一结束（挂断、对方挂了、没打通）就关掉，不停在「通话结束」。
 *
 * 自己一个任务（taskAffinity 单独），不把主界面带到锁屏上；不进最近任务。
 * MIUI 上要「锁屏显示」「后台弹出界面」两项权限才起得来（小米 10 上实测，见设置页的说明）
 */
class WakeCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 通话页是深色的：铺到状态栏、导航条后面（不然状态栏那条是主题的浅色底），图标用浅色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.setBackgroundDrawable(ColorDrawable(CALL_BACKGROUND))
        val model = (application as FridayApp).model
        setContent {
            FridayTheme {
                CompositionLocalProvider(LocalAppModel provides model) {
                    CallScreen(model.call)
                }
            }
        }
        // 通话一结束就关（挂断、对方挂了、没打通都是；没打通的原因叫醒那边会念出来）
        lifecycleScope.launch {
            model.call.state.collect { if (!it.inCall || it.phase == AssistantCall.Phase.Ended) finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // 起来了就不用那条「全屏通知」兜底了
        getSystemService(NotificationManager::class.java)?.cancel(WakeCallUi.NOTIFICATION_ID)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    companion object {
        /** 通话页的底色（同 CallScreen） */
        private const val CALL_BACKGROUND = 0xFF0B0C12.toInt()

        /** 通话页正摆在前面（兜底的全屏通知看这个决定发不发） */
        @Volatile
        var visible = false
            private set
    }
}
