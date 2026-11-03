package com.integemjack.friday

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.integemjack.friday.ui.ActivityScreen
import com.integemjack.friday.ui.AssistantInputBar
import com.integemjack.friday.ui.AssistantScreen
import com.integemjack.friday.ui.ChatScreen
import com.integemjack.friday.ui.HomeScaffold
import com.integemjack.friday.ui.LocalAppModel
import com.integemjack.friday.ui.SettingsScreen
import com.integemjack.friday.ui.SessionsScreen
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 通知点进来要跳的页（onNewIntent 也写这里，界面收着就跳） */
    val tabRequest = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 导航条用全透明（默认是 90% 白的 scrim）：底部 dock 的背景一直铺到屏幕底，
        // 看着是一整块，不会在 dock 和导航条之间断一道颜色
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT_SCRIM, TRANSPARENT_SCRIM),
        )
        // 光设透明色还不够：系统默认会自己垫一层 contrast scrim 保证导航键看得清，
        // 那层就是 dock 和导航条之间断的那道白。关掉它，让 dock 背景真的铺到屏幕底。
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val model = (application as FridayApp).model
        tabRequest.value = intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }
        // 演示数据（只在 debug 包里有实现）：给界面填满假数据截图用，release 里是空判断
        Demo.apply(model, intent?.getStringExtra(EXTRA_DEMO))
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
        const val EXTRA_DEMO = "demo"
        const val TAB_ASSISTANT = 0
        const val TAB_SESSIONS = 1
        const val TAB_ACTIVITY = 2
        const val TAB_SETTINGS = 3
        /** 导航条全透明（不是默认的 90% 白 scrim） */
        private const val TRANSPARENT_SCRIM = 0x00000000
    }
}

@Composable
fun AppRoot(tabRequest: MutableStateFlow<Int?>) {
    val model = LocalAppModel.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(MainActivity.TAB_SESSIONS) }
    var showChat by rememberSaveable { mutableStateOf(Demo.startInChat) }
    var chatError by remember { mutableStateOf<String?>(null) }
    // 助手页 dock 里打了一半的话：提到这一层，切 tab 再回来不丢
    var assistantInput by rememberSaveable { mutableStateOf("") }
    val requested by tabRequest.collectAsState()
    // 通知点进来：跳到那一页（从聊天里出来，回到主框架）
    androidx.compose.runtime.LaunchedEffect(requested) {
        requested?.let {
            tab = it
            showChat = false
            tabRequest.value = null
        }
    }
    // 助手页的回执卡上点「打开」：订那段会话再进去
    fun openConversation(id: String) {
        scope.launch {
            runCatching { model.client.subscribeConversation(id) }
                .onSuccess { showChat = true }
                .onFailure { chatError = it.message }
        }
    }
    if (showChat) {
        ChatScreen(onBack = { showChat = false })
    } else {
        // 助手页：输入框和 tab 行坐同一块 dock（HomeScaffold 里），不是两条叠着
        val dock: (@Composable () -> Unit)? =
            if (tab == MainActivity.TAB_ASSISTANT) {
                { AssistantInputBar(input = assistantInput, onInput = { assistantInput = it }) }
            } else {
                null
            }
        HomeScaffold(
            tab = tab,
            onTab = { tab = it },
            dock = dock,
            assistant = { AssistantScreen(onOpenSession = ::openConversation) },
            sessions = { SessionsScreen(onOpen = { showChat = true }) },
            activity = { ActivityScreen(onOpen = { showChat = true }) },
            settings = { SettingsScreen() },
        )
    }
    chatError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { chatError = null },
            title = { androidx.compose.material3.Text("那段会话打不开") },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { chatError = null }) {
                    androidx.compose.material3.Text("知道了")
                }
            },
        )
    }
}
