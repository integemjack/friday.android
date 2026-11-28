package com.integemjack.friday.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 分批加载（2026-11-28 用户：「改成分批加载，先拿最后几十条」）：服务端有 conversationPaging 时订阅只要最后 50 条，
 * 翻到顶用 conversation.history 再要；没有这个功能的旧服务端照旧整段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ConversationPagingTest {

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

    private fun server(features: List<String>) = ConnState.Connected(
        ServerInfo(
            version = "2.98", platform = "linux", hostname = "box", user = "u",
            dataDir = "/home/u/.friday", host = "server", defaultWorkingDirectory = null, features = features,
        )
    )

    /** 一条接一条的消息（m{from}..m{to}，每条的父消息是上一条） */
    private fun nodes(from: Int, to: Int) = buildJsonArray {
        for (i in from..to) add(buildJsonObject {
            put("id", "m$i")
            put("role", if (i % 2 == 0) "user" else "assistant")
            put("createdAt", "2026-01-01T00:00:%02dZ".format(i))
            if (i > 0) put("parentID", "m${i - 1}")
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("id", "b$i")
                    put("kind", buildJsonObject { put("text", buildJsonObject { put("_0", "第 $i 条") }) })
                })
            })
        })
    }

    private fun ids(from: Int, to: Int) = buildJsonArray { for (i in from..to) add(JsonPrimitive("m$i")) }

    private fun setUp(features: List<String>): Pair<AppModel, ManualRpc> {
        val model = AppModel(app)
        model.client.state.value = FridayState(
            conn = server(features),
            summaries = listOf(
                ConversationSummary(id = "C1", title = "长会话", updatedAt = "2026-01-01T00:00:00Z", pinned = false, isEmpty = false, hasDraft = false),
            ),
        )
        model.client.cache = null
        val fake = ManualRpc()
        model.client.attachRpcForTest(fake.rpc)
        return model to fake
    }

    private fun open(model: AppModel): Deferred<Result<Unit>> =
        background.async { runCatching { model.client.subscribeConversation("C1") } }

    private fun params(fake: ManualRpc, method: String): JsonObject =
        fake.sent.last { (it["method"] as? JsonPrimitive)?.content == method }["params"]!!.jsonObject

    private fun texts(model: AppModel) = model.client.state.value.chat?.messages?.map { it.text }.orEmpty()

    @Test
    fun `服务端会分批：先要最后 50 条，往上翻再要，接在前面`() {
        val (model, fake) = setUp(listOf("conversationPaging"))
        val job = open(model)
        val request = fake.awaitRequest("conversation.subscribe", "C1")
        assertEquals(FridayClient.PAGE, params(fake, "conversation.subscribe")["limit"]?.jsonPrimitive?.int)
        fake.reply(request, buildJsonObject {
            put("conversation", buildJsonObject {
                put("id", "C1")
                put("title", "长会话")
                put("nodes", nodes(8, 9))
            })
            put("hasOlder", true)
        })
        runBlocking { job.await() }.getOrThrow()
        assertTrue(model.client.state.value.chat?.hasOlder == true)
        assertEquals(listOf("第 8 条", "第 9 条"), texts(model))

        val older = background.async { runCatching { model.client.loadOlderMessages() } }
        val history = fake.awaitRequest("conversation.history", "C1")
        assertEquals(FridayClient.PAGE, params(fake, "conversation.history")["limit"]?.jsonPrimitive?.int)
        assertTrue(model.client.state.value.chat?.loadingOlder == true)
        fake.reply(history, buildJsonObject {
            put("nodes", nodes(0, 7))
            put("nodeIDs", ids(0, 9))
            put("versions", buildJsonObject { put("m0", 5) })
            put("hasOlder", false)
        })
        runBlocking { older.await() }.getOrThrow()
        val chat = model.client.state.value.chat!!
        assertEquals((0..9).map { "第 $it 条" }, texts(model))
        assertFalse(chat.hasOlder)
        assertFalse(chat.loadingOlder)
        assertEquals(5L, chat.versions["m0"])
        // 没有更早的了：不再要
        runBlocking { model.client.loadOlderMessages() }
        assertEquals(1, fake.requests("conversation.history", "C1").size)
    }

    @Test
    fun `旧服务端没有 conversationPaging：照旧整段，不带 limit`() {
        val (model, fake) = setUp(emptyList())
        val job = open(model)
        val request = fake.awaitRequest("conversation.subscribe", "C1")
        assertNull(params(fake, "conversation.subscribe")["limit"])
        fake.reply(request, buildJsonObject {
            put("conversation", buildJsonObject {
                put("id", "C1")
                put("nodes", nodes(0, 3))
            })
        })
        runBlocking { job.await() }.getOrThrow()
        assertFalse(model.client.state.value.chat?.hasOlder == true)
        runBlocking { model.client.loadOlderMessages() }
        assertTrue(fake.requests("conversation.history", "C1").isEmpty())
    }

    @Test
    fun `重新订同一段（断线重连）：翻上去的条数一起要回来`() {
        val (model, fake) = setUp(listOf("conversationPaging"))
        val messages = (0 until 70).map { i ->
            ChatMessage(
                id = "m$i", role = "user", createdAt = "2026-01-01T00:00:00Z",
                parentID = if (i > 0) "m${i - 1}" else null,
                blocks = listOf(MessageBlock("b$i", buildJsonObject { put("text", buildJsonObject { put("_0", "第 $i 条") }) })),
            )
        }
        model.client.state.value = model.client.state.value.copy(
            openConversationId = "C1",
            chat = ChatState(id = "C1", nodes = messages.associateBy { it.id }, order = messages.map { it.id }),
        )
        val job = open(model)
        fake.reply(fake.awaitRequest("conversation.subscribe", "C1"), buildJsonObject {
            put("conversation", buildJsonObject { put("id", "C1"); put("nodes", nodes(0, 69)) })
            put("hasOlder", false)
        })
        runBlocking { job.await() }.getOrThrow()
        assertEquals(70, params(fake, "conversation.subscribe")["limit"]?.jsonPrimitive?.int)
    }

    @Test
    fun `服务端重定窗口（那边切了更早的分支）：整个换掉，带上 hasOlder`() {
        val (model, fake) = setUp(listOf("conversationPaging"))
        val job = open(model)
        fake.reply(fake.awaitRequest("conversation.subscribe", "C1"), buildJsonObject {
            put("conversation", buildJsonObject { put("id", "C1"); put("nodes", nodes(8, 9)) })
            put("hasOlder", true)
        })
        runBlocking { job.await() }.getOrThrow()
        // 服务端推一个不带 partial 的快照
        fake.rpc.handleMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "conversation")
            put("params", buildJsonObject {
                put("conversation", buildJsonObject { put("id", "C1"); put("nodes", nodes(0, 2)) })
                put("hasOlder", false)
            })
        }.toString())
        val deadline = System.currentTimeMillis() + 5_000
        while (texts(model).size != 3 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(listOf("第 0 条", "第 1 条", "第 2 条"), texts(model))
        assertFalse(model.client.state.value.chat?.hasOlder == true)
    }

    @Test
    fun `会话页：不满一屏一进来就往上要，最上面显示在加载`() {
        val (model, fake) = setUp(listOf("conversationPaging"))
        val job = open(model)
        fake.reply(fake.awaitRequest("conversation.subscribe", "C1"), buildJsonObject {
            put("conversation", buildJsonObject { put("id", "C1"); put("title", "长会话"); put("nodes", nodes(8, 9)) })
            put("hasOlder", true)
        })
        runBlocking { job.await() }.getOrThrow()
        compose.setContent {
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
        compose.waitForIdle()
        val history = fake.awaitRequest("conversation.history", "C1")
        compose.waitForIdle()
        compose.onNodeWithText("正在加载更早的消息…").assertExists()
        fake.reply(history, buildJsonObject {
            put("nodes", nodes(0, 7))
            put("nodeIDs", ids(0, 9))
            put("hasOlder", false)
        })
        compose.waitUntil(5_000) { texts(model).size == 10 }
        compose.waitForIdle()
        compose.onNodeWithText("正在加载更早的消息…").assertDoesNotExist()
        compose.onNodeWithText("加载更早的消息").assertDoesNotExist()
        compose.onNodeWithText("第 9 条").assertExists()
    }
}
