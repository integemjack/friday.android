package com.integemjack.friday.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.integemjack.friday.AppModel

/** 全局 AppModel（MainActivity 里 provide） */
val LocalAppModel = staticCompositionLocalOf<AppModel> {
    error("AppModel 未初始化")
}
