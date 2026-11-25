package com.integemjack.friday.wake

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 叫醒的通话页起得来、摆在锁屏上面、点亮屏幕：Android 8.0（minSdk 26）没有 setShowWhenLocked /
 * setTurnScreenOn，用窗口标志；8.1 起用 Activity 的方法
 */
@RunWith(RobolectricTestRunner::class)
class WakeCallActivityTest {

    private fun launch(): WakeCallActivity = Robolectric.buildActivity(WakeCallActivity::class.java).setup().get()

    @Test
    @Config(sdk = [26])
    fun `Android 8_0：不调 8_1 才有的方法，用窗口标志摆在锁屏上面、点亮屏幕`() {
        val flags = launch().window.attributes.flags
        @Suppress("DEPRECATION")
        val wanted = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(wanted, flags and wanted)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    @Config(sdk = [27])
    fun `Android 8_1 起：用 setShowWhenLocked 和 setTurnScreenOn`() {
        val activity = launch()
        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
}
