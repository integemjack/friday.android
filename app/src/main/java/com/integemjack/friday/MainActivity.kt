package com.integemjack.friday

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.integemjack.friday.ui.ActivityScreen
import com.integemjack.friday.ui.ChatScreen
import com.integemjack.friday.ui.HomeScaffold
import com.integemjack.friday.ui.LocalAppModel
import com.integemjack.friday.ui.SettingsScreen
import com.integemjack.friday.ui.SessionsScreen
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 通知点进来要跳的页（onNewIntent 也写这里，界面收着就跳） */
    val tabRequest = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as FridayApp).model
        tabRequest.value = intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }
        setContent {
            FridayTheme {
                CompositionLocalProvider(LocalAppModel provides model) {
                    AppRoot(tabRequest)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra(EXTRA_TAB, -1)
        if (tab >= 0) tabRequest.value = tab
    }

    companion object {
        const val EXTRA_TAB = "friday_tab"
        const val TAB_SESSIONS = 0
        const val TAB_ACTIVITY = 1
        const val TAB_SETTINGS = 2
    }
}

@Composable
fun AppRoot(tabRequest: MutableStateFlow<Int?>) {
    var tab by rememberSaveable { mutableIntStateOf(MainActivity.TAB_SESSIONS) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    val requested by tabRequest.collectAsState()
    // 通知点进来：跳到那一页（从聊天里出来，回到主框架）
    androidx.compose.runtime.LaunchedEffect(requested) {
        requested?.let {
            tab = it
            showChat = false
            tabRequest.value = null
        }
    }
    if (showChat) {
        ChatScreen(onBack = { showChat = false })
    } else {
        HomeScaffold(
            tab = tab,
            onTab = { tab = it },
            sessions = { SessionsScreen(onOpen = { showChat = true }) },
            activity = { ActivityScreen(onOpen = { showChat = true }) },
            settings = { SettingsScreen() },
        )
    }
}
