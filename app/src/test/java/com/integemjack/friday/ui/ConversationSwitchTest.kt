package com.integemjack.friday.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.integemjack.friday.AppModel
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.ChatState
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import com.integemjack.friday.proto.FridayClient
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.RpcClient
import com.integemjack.friday.proto.ServerInfo
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 切换会话（2026-11-27 用户：「切换会话的时候还是会显示原来的会话，过一会才显示切换过去的会话」）：
 * 点了马上换过去（存下来的 / 只有标题的空页），服务端回来再换成新的；
 * 连着点几段时，前面回得慢的不能把画面盖回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ConversationSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val background = CoroutineScope(Dispatchers.Default)

    /** 假服务端：请求先攒着，测试里想什么时候回、回什么都行 */
    private class ManualRpc {
        val sent = CopyOnWriteArrayList<JsonObject>()
        val rpc = RpcClient(sendFn = { sent.add(Json.parseToJsonElement(it).jsonObject) }, closeFn = {})

        private fun JsonObject.method() = (this["method"] as? JsonPrimitive)?.content
        private fun JsonObject.convo() = ((this["params"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content

        fun requests(method: String, convo: String) = sent.filter { it.containsKey("id") && it.method() == method && it.convo() == convo }
        fun notifications(method: String, convo: String) = sent.count { !it.containsKey("id") && it.method() == method && it.convo() == convo }

        /** 等到发出了第 [nth] 个这样的请求（从 1 数），给它的 id */
        fun awaitRequest(method: String, convo: String, nth: Int = 1): JsonElement {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                requests(method, convo).getOrNull(nth - 1)?.let { return it["id"]!! }
                Thread.sleep(5)
            }
            throw AssertionError("没发 $method $convo")
        }

        fun reply(id: JsonElement, result: JsonObject) = rpc.handleMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()
        )

        fun fail(id: JsonElement, message: String) = rpc.handleMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32000)
                    put("message", message)
                })
            }.toString()
        )
    }

    private val connected = ConnState.Connected(
        ServerInfo(
            version = "2.90", platform = "linux", hostname = "box", user = "u",
            dataDir = "/home/u/.friday", host = "server", defaultWorkingDirectory = null, features = emptyList(),
        )
    )

    private fun summary(id: String, title: String) = ConversationSummary(
        id = id, title = title, updatedAt = "2026-01-01T00:00:00Z",
        pinned = false, isEmpty = false, hasDraft = false,
    )

    private fun chat(id: String, title: String, text: String): ChatState {
        val message = ChatMessage(
            id = "$id-m1",
            role = "assistant",
            blocks = listOf(MessageBlock("$id-b1", buildJsonObject { put("text", buildJsonObject { put("_0", text) }) })),
            createdAt = "2026-01-01T00:00:00Z",
        )
        return ChatState(id = id, title = title, nodes = mapOf(message.id to message), order = listOf(message.id))
    }

    /** conversation.subscribe 的回执：一条助手消息 */
    private fun subscribed(id: String, title: String, text: String) = buildJsonObject {
        put("conversation", buildJsonObject {
            put("id", id)
            put("title", title)
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", "$id-live")
                    put("role", "assistant")
                    put("createdAt", "2026-01-01T00:00:00Z")
                    put("blocks", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "$id-lb")
                            put("kind", buildJsonObject { put("text", buildJsonObject { put("_0", text) }) })
                        })
                    })
                })
            })
        })
    }

    /** 开着 C1（「上一段的内容」），侧栏里有 C1 / C2 / C3，连着假服务端 */
    private fun setUp(cached: Map<String, ChatState> = emptyMap()): Pair<AppModel, ManualRpc> {
        val model = AppModel(app)
        model.client.state.value = FridayState(
            conn = connected,
            summaries = listOf(summary("C1", "改代码"), summary("C2", "写文档"), summary("C3", "查资料")),
            openConversationId = "C1",
            chat = chat("C1", "改代码", "上一段的内容"),
        )
        model.client.cache = object : FridayClient.Cache {
            override fun load(botId: Long): FridayState? = null
            override fun loadChat(botId: Long, conversationId: String) = cached[conversationId]
            override fun removeChat(botId: Long, conversationId: String) {}
        }
        val fake = ManualRpc()
        model.client.attachRpcForTest(fake.rpc)
        return model to fake
    }

    private fun open(model: AppModel, id: String): Deferred<Result<Unit>> =
        background.async { runCatching { model.client.subscribeConversation(id) } }

    private fun renderChat(model: AppModel) = compose.setContent {
        FridayTheme {
            CompositionLocalProvider(LocalAppModel provides model) {
                val drafts = remember { ChatDrafts() }
                HomeScaffold(
                    tab = HomeTabs.CHAT,
                    onTab = {},
                    dock = { ChatComposer(drafts) },
                    sessions = { SessionsScreen(onOpen = {}) },
                ) { ChatScreen() }
            }
        }
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("等不到：$what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `点了马上换过去，不再摆着上一段；服务端回来换成内容`() {
        val (model, fake) = setUp()
        renderChat(model)
        compose.onNodeWithText("上一段的内容").assertExists()

        val job = open(model, "C2")
        val request = fake.awaitRequest("conversation.subscribe", "C2")

        // 服务端还没回：已经是 C2 了，标题从侧栏列表里拿，上一段的内容不见了
        val state = model.client.state.value
        assertEquals("C2", state.openConversationId)
        assertEquals("写文档", state.chat?.title)
        assertTrue(state.chat?.loading == true)
        assertEquals(1, fake.notifications("conversation.unsubscribe", "C1"))
        compose.waitForIdle()
        // 消息区转圈那句 + 输入框的提示
        assertTrue(compose.onAllNodesWithText("正在打开这段会话…", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("上一段的内容").assertDoesNotExist()

        fake.reply(request, subscribed("C2", "写文档", "这一段的内容"))
        runBlocking { job.await() }.getOrThrow()
        assertTrue(model.client.state.value.chat?.loading == false)
        compose.waitForIdle()
        compose.onNodeWithText("这一段的内容").assertExists()
        assertTrue(compose.onAllNodesWithText("正在打开这段会话…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `连着点了几段，前面的回得慢也不会把画面盖回去`() {
        val (model, fake) = setUp()
        val toC2 = open(model, "C2")
        val c2 = fake.awaitRequest("conversation.subscribe", "C2")
        val toC3 = open(model, "C3")
        val c3 = fake.awaitRequest("conversation.subscribe", "C3")

        // C3 先回，C2 后回
        fake.reply(c3, subscribed("C3", "查资料", "第三段"))
        runBlocking { toC3.await() }.getOrThrow()
        fake.reply(c2, subscribed("C2", "写文档", "第二段"))
        runBlocking { toC2.await() }.getOrThrow()

        val state = model.client.state.value
        assertEquals("C3", state.openConversationId)
        assertEquals("C3", state.chat?.id)
        assertEquals("第三段", state.chat?.messages?.single()?.text)
        // 晚回来的那段退订掉（切走时退一次，回来发现过期了再退一次）
        assertEquals(2, fake.notifications("conversation.unsubscribe", "C2"))
    }

    @Test
    fun `存过的先摆存下来的，服务端回来换成新的`() {
        val (model, fake) = setUp(cached = mapOf("C2" to chat("C2", "写文档", "存下来的")))
        renderChat(model)

        val job = open(model, "C2")
        val request = fake.awaitRequest("conversation.subscribe", "C2")
        waitUntil("摆上存下来的") { model.client.state.value.chat?.nodes?.isNotEmpty() == true }
        assertTrue(model.client.state.value.chat?.loading == true)
        compose.waitForIdle()
        compose.onNodeWithText("存下来的").assertExists()
        compose.onNodeWithText("上一段的内容").assertDoesNotExist()
        // 还没订上：先不能发
        compose.onNodeWithText("正在打开这段会话…").assertIsNotEnabled()

        fake.reply(request, subscribed("C2", "写文档", "服务端的"))
        runBlocking { job.await() }.getOrThrow()
        val chat = model.client.state.value.chat
        assertEquals("服务端的", chat?.messages?.single()?.text)
        assertTrue(chat?.loading == false)
    }

    @Test
    fun `打不开又没存过：回到上一段、重新订上它，并报错`() {
        val (model, fake) = setUp()
        val job = open(model, "C2")
        fake.fail(fake.awaitRequest("conversation.subscribe", "C2"), "没有这段会话")

        val error = runBlocking { job.await() }.exceptionOrNull()
        assertNotNull(error)
        val state = model.client.state.value
        assertEquals("C1", state.openConversationId)
        assertEquals("上一段的内容", state.chat?.messages?.single()?.text)
        // 上一段在切走时退订了：重新订回来
        fake.awaitRequest("conversation.subscribe", "C1")
    }

    @Test
    fun `打不开但存过的：留着存下来的看，报错`() {
        val (model, fake) = setUp(cached = mapOf("C2" to chat("C2", "写文档", "存下来的")))
        val job = open(model, "C2")
        val request = fake.awaitRequest("conversation.subscribe", "C2")
        waitUntil("摆上存下来的") { model.client.state.value.chat?.nodes?.isNotEmpty() == true }
        fake.fail(request, "服务端出错")

        assertNotNull(runBlocking { job.await() }.exceptionOrNull())
        val chat = model.client.state.value.chat
        assertEquals("C2", chat?.id)
        assertEquals("存下来的", chat?.messages?.single()?.text)
        assertTrue(chat?.loading == false)
    }

    @Test
    fun `切到别的段以后，前一段打不开不报错`() {
        val (model, fake) = setUp()
        val toC2 = open(model, "C2")
        val c2 = fake.awaitRequest("conversation.subscribe", "C2")
        open(model, "C3")
        fake.awaitRequest("conversation.subscribe", "C3")

        fake.fail(c2, "超时了")
        runBlocking { toC2.await() }.getOrThrow()
        assertEquals("C3", model.client.state.value.openConversationId)
    }
}
