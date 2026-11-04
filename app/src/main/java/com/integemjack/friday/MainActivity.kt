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
import androidx.activity.compose.BackHandler
import com.integemjack.friday.ui.AssistantComposer
import com.integemjack.friday.ui.AssistantScreen
import com.integemjack.friday.ui.ChatComposer
import com.integemjack.friday.ui.ChatDrafts
import com.integemjack.friday.ui.ChatScreen
import com.integemjack.friday.ui.ComposerDraft
import com.integemjack.friday.ui.HomeScaffold
import com.integemjack.friday.ui.HomeTabs
import com.integemjack.friday.ui.LocalAppModel
import com.integemjack.friday.ui.SettingsScreen
import com.integemjack.friday.ui.SessionsScreen
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 通知点进来要跳的页（onNewIntent 也写这里，界面收着就跳） */
    val tabRequest = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 导航条用全透明（默认是 90% 白的 scrim）：页面内容一直铺到屏幕底，
        // 输入框和导航条后面都不垫底色，看得到后面滚动的内容
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT_SCRIM, TRANSPARENT_SCRIM),
        )
        // 光设透明色还不够：系统默认会自己垫一层 contrast scrim 保证导航键看得清，
        // 导航条那儿就又多一道底色。关掉它，导航条后面才真的是页面内容。
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
        const val TAB_SETTINGS = 2
        const val TAB_CHAT = 3
        /** 导航条全透明（不是默认的 90% 白 scrim） */
        private const val TRANSPARENT_SCRIM = 0x00000000
    }
}

@Composable
fun AppRoot(tabRequest: MutableStateFlow<Int?>) {
    val model = LocalAppModel.current
    // 没登录：先选聊天服务器（或加一台），再是登录页；登录了还没选机器人（或者在设置里点了「切换 bot」）：
    // 选机器人那一页；都有了才是主框架。演示数据（debug 包截图用）不走这道门
    val schat by model.schat.state.collectAsState()
    var switchingBot by rememberSaveable { mutableStateOf(false) }
    if (!Demo.active) {
        if (!schat.hasSession) {
            // 选的是哪台：每次打开 App / 退出登录回来都先停在选服务器那一页
            var picked by rememberSaveable { mutableStateOf<String?>(null) }
            val servers by model.schat.servers.collectAsState()
            val server = servers.firstOrNull { it.address == picked }
            if (server == null) {
                com.integemjack.friday.ui.ServerPickerScreen(onPicked = { picked = it.address })
            } else {
                BackHandler { picked = null }
                com.integemjack.friday.ui.LoginScreen(server, onChangeServer = { picked = null })
            }
            return
        }
        if (schat.lastBotId <= 0L) {
            com.integemjack.friday.ui.BotPickerScreen(onPicked = { switchingBot = false })
            return
        }
    }
    MainFrame(tabRequest, onSwitchBot = { switchingBot = true })
    // 设置里点「切换 bot」：盖在主框架上面（主框架留着，返回时还在设置页）
    if (switchingBot && !Demo.active) {
        BackHandler { switchingBot = false }
        com.integemjack.friday.ui.BotPickerScreen(
            onPicked = { switchingBot = false },
            onBack = { switchingBot = false },
        )
    }
}

/** 主框架：登录了、选好机器人之后的那一套（助手 / 会话 / 设置 + 侧栏） */
@Composable
private fun MainFrame(tabRequest: MutableStateFlow<Int?>, onSwitchBot: () -> Unit) {
    val model = LocalAppModel.current
    // 只盯「开着哪段会话」：整份 state 每个流式字都会变，收它会让整个外壳跟着重组
    val openConversationId by remember(model) {
        model.client.stateFlow.map { it.openConversationId }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 主页面就是助手页：开 App 进来先看它在忙什么。会话页跟它平级（HomeTabs.CHAT）
    var tab by rememberSaveable {
        mutableIntStateOf(if (Demo.startInChat) HomeTabs.CHAT else MainActivity.TAB_ASSISTANT)
    }
    var openError by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 两个输入框里打了一半的话（和附件）都提到这一层：切到别的页再回来不丢。会话的按会话分开存
    // 字也存到本地（DraftStore）：App 被关掉再开还在
    val assistantDraft = remember {
        val key = com.integemjack.friday.data.DraftStore.ASSISTANT
        ComposerDraft(model.drafts.load(key)) { model.drafts.save(key, it) }
    }
    val chatDrafts = remember { ChatDrafts(model.drafts) }
    val requested by tabRequest.collectAsState()

    // 切页：离开会话页就退订那段会话（事件量不小，没人看就别收）
    fun go(target: Int) {
        if (tab == HomeTabs.CHAT && target != HomeTabs.CHAT) model.client.closeConversation()
        tab = target
    }
    // 通知点进来：跳到那一页（老通知里带的活动页编号已经没了，回助手页）
    androidx.compose.runtime.LaunchedEffect(requested) {
        requested?.let {
            go(HomeTabs.of(it))
            tabRequest.value = null
        }
    }
    // 助手页的回执卡上点「打开」、侧栏里点会话：订那段会话，切到会话页
    fun openConversation(id: String) {
        // 演示数据里没有真的 RPC（点进去只会报未连接），直接就进去看
        if (Demo.active) {
            tab = HomeTabs.CHAT
            return
        }
        // 已经开着的就是这段：不用再订一遍
        if (tab == HomeTabs.CHAT && model.client.state.value.chat?.id == id) return
        scope.launch {
            runCatching { model.client.subscribeConversation(id) }
                .onSuccess { tab = HomeTabs.CHAT }
                .onFailure { openError = "那段会话打不开" to (it.message ?: "") }
        }
    }
    fun newConversation() {
        scope.launch {
            runCatching { model.client.newConversation() }
                .onSuccess { tab = HomeTabs.CHAT }
                .onFailure { openError = "新会话没开起来" to (it.message ?: "") }
        }
    }
    // 返回键：别的页先回助手页（主页面），在助手页上才退出 App。侧栏开着时 HomeScaffold 先收侧栏
    BackHandler(enabled = tab != HomeTabs.ASSISTANT) { go(HomeTabs.ASSISTANT) }

    // 跟助手视频通话：先要麦克风（必须）和摄像头（没给就只听、对方看不到你），再打给连着的那台的机器人
    val context = androidx.compose.ui.platform.LocalContext.current
    fun startCall() {
        val target = model.client.target ?: return
        val name = model.client.state.value.assistant?.config?.name?.takeIf { it.isNotBlank() } ?: "助手"
        model.call.start(target.botId, name, video = true)
    }
    val callPermissions = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            startCall()
        } else {
            android.widget.Toast.makeText(context, "要跟助手通话得给麦克风权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun requestCall() {
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startCall() else callPermissions.launch(needed.toTypedArray())
    }

    // 底部输入框：助手页和会话页是同一个 Composer，只是发给谁不同；设置页没有输入框
    val dock: (@Composable () -> Unit)? = when (tab) {
        HomeTabs.ASSISTANT -> {
            { AssistantComposer(assistantDraft, onVideoCall = ::requestCall) }
        }
        HomeTabs.CHAT -> {
            { ChatComposer(chatDrafts) }
        }
        else -> null
    }
    HomeScaffold(
        tab = tab,
        onTab = ::go,
        dock = dock,
        sessions = {
            SessionsScreen(
                onOpen = ::openConversation,
                selectedId = if (tab == HomeTabs.CHAT) openConversationId else null,
                onGoSettings = { go(HomeTabs.SETTINGS) },
            )
        },
        onNewConversation = ::newConversation,
    ) {
        when (tab) {
            HomeTabs.ASSISTANT -> AssistantScreen(onOpenSession = ::openConversation)
            HomeTabs.CHAT -> ChatScreen()
            else -> SettingsScreen(onSwitchBot = onSwitchBot)
        }
    }
    // 跟助手的通话盖在整个界面上（没在通话时什么都不画）
    com.integemjack.friday.ui.CallScreen(model.call)
    openError?.let { (title, message) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { openError = null },
            title = { androidx.compose.material3.Text(title) },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { openError = null }) {
                    androidx.compose.material3.Text("知道了")
                }
            },
        )
    }
}
