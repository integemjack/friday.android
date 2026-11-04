package com.integemjack.friday.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performTextInput
import com.integemjack.friday.proto.AssistantState
import com.integemjack.friday.proto.RunnerState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.integemjack.friday.AppModel
import com.integemjack.friday.proto.AskOption
import com.integemjack.friday.proto.AskQuestion
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.ChatState
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.GenerationStats
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.PendingApproval
import com.integemjack.friday.proto.QueuedInput
import com.integemjack.friday.proto.Runtime
import com.integemjack.friday.proto.ServerInfo
import com.integemjack.friday.proto.SessionActivity
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 界面测试（Robolectric + Compose，JVM 上真渲染真点击，不用设备）：
 * 把假的协议状态塞进 client.state，验证新加的这些功能在界面上真的出现、真的能点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun render(model: AppModel, content: @Composable () -> Unit) {
        compose.setContent {
            FridayTheme {
                CompositionLocalProvider(LocalAppModel provides model) { content() }
            }
        }
    }

    /** 会话页按真的样子摆：主外壳 + 会话页（顶级页面）+ 底部共用的输入框 */
    private fun renderChat(model: AppModel) = render(model) {
        val drafts = remember { ChatDrafts() }
        HomeScaffold(
            tab = HomeTabs.CHAT,
            onTab = {},
            dock = { ChatComposer(drafts) },
            sessions = { SessionsScreen(onOpen = {}) },
        ) { ChatScreen() }
    }

    /** 这个文本不该在界面上 */
    private fun assertNoText(text: String) {
        assertEquals("不该出现「$text」", 0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }

    /** 这个无障碍描述不该在界面上 */
    private fun assertNoDescription(description: String) {
        assertEquals(
            "不该出现「$description」", 0,
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size,
        )
    }

    // MARK: 造状态

    private fun textBlock(id: String, text: String) = MessageBlock(
        id,
        buildJsonObject { put("text", buildJsonObject { put("_0", text) }) },
    )

    private fun thinkingBlock(id: String, text: String) = MessageBlock(
        id,
        buildJsonObject { put("thinking", buildJsonObject { put("_0", text) }) },
    )

    private fun toolBlock(id: String, name: String, title: String, status: String, result: String = "") = MessageBlock(
        id,
        buildJsonObject {
            put("tool", buildJsonObject {
                put("_0", buildJsonObject {
                    put("id", id)
                    put("name", name)
                    put("arguments", "{}")
                    put("status", status)
                    put("result", result)
                    put("title", title)
                })
            })
        },
    )

    private fun mediaBlock(id: String, fileName: String = "a.png") = MessageBlock(
        id,
        buildJsonObject {
            put("media", buildJsonObject {
                put("_0", buildJsonObject {
                    put("id", "m-$id")
                    put("kind", "image")
                    put("relativePath", "2026/01/$fileName")
                    put("fileName", fileName)
                    put("mimeType", "image/png")
                    put("byteCount", 2048)
                })
            })
        },
    )

    private fun message(
        id: String,
        role: String,
        blocks: List<MessageBlock>,
        parent: String? = null,
        at: String = "2026-01-01T00:00:00Z",
        stats: GenerationStats? = null,
    ) = ChatMessage(id = id, role = role, blocks = blocks, createdAt = at, parentID = parent, stats = stats)

    private fun runtime(
        generating: Boolean = false,
        queue: List<QueuedInput> = emptyList(),
        queuePaused: Boolean = false,
        canRetry: Boolean = false,
    ) = Runtime(
        generating = generating,
        stopping = false,
        queue = queue,
        queuePaused = queuePaused,
        pendingApproval = false,
        canRetry = canRetry,
        canSend = true,
    )

    private fun chatState(
        messages: List<ChatMessage>,
        runtime: Runtime? = null,
        title: String = "测试会话",
    ) = ChatState(
        id = "C1",
        title = title,
        nodes = messages.associateBy { it.id },
        order = messages.map { it.id },
        runtime = runtime,
        workingDirectory = "/home/u/proj",
    )

    private val connected = ConnState.Connected(
        ServerInfo(
            version = "2.90", platform = "linux", hostname = "box", user = "u",
            dataDir = "/home/u/.friday", host = "server", defaultWorkingDirectory = null, features = emptyList(),
        )
    )

    private fun modelWith(state: FridayState): AppModel = AppModel(app).also { it.client.state.value = state }

    // MARK: 聊天界面

    @Test
    fun `消息流渲染文本、思考折叠块、工具卡片`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "帮我看这张图")), at = "2026-01-01T00:00:00Z"),
                        message(
                            "2", "assistant",
                            listOf(
                                thinkingBlock("b2", "先看看这是什么"),
                                textBlock("b3", "这是一只猫"),
                                toolBlock("b4", "analyze_image", "看图 · a.png", "succeeded", "是只橘猫"),
                            ),
                            parent = "1",
                            at = "2026-01-01T00:00:01Z",
                        ),
                    )
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("帮我看这张图").assertExists()
        compose.onNodeWithText("这是一只猫").assertExists()
        compose.onNodeWithText("看图 · a.png").assertExists()

        // 思考块默认折着，点开才显示内容
        assertNoText("先看看这是什么")
        compose.onNodeWithText("思考 …").performClick()
        compose.onNodeWithText("先看看这是什么").assertExists()
    }

    /** 一长串一问一答（一条接一条挂在上一条下面）；最后一条可以塞很多段，比一屏还长 */
    private fun longConversation(prefix: String, count: Int = 40, lastBlocks: Int = 1) = (1..count).map { i ->
        val blocks = if (i == count && lastBlocks > 1) {
            (1..lastBlocks).map { j -> textBlock("$prefix-b$i-$j", "$prefix 末条第 $j 段\n第二行\n第三行") }
        } else {
            listOf(textBlock("$prefix-b$i", "$prefix 第 $i 条"))
        }
        message(
            "$prefix$i", if (i % 2 == 1) "user" else "assistant", blocks,
            parent = if (i > 1) "$prefix${i - 1}" else null,
        )
    }

    @Test
    fun `打开长会话停在最底下，最后一条不被输入框挡住`() {
        val model = modelWith(
            FridayState(conn = connected, openConversationId = "C1", chat = chatState(longConversation("A")))
        )
        renderChat(model)

        compose.onNodeWithText("A 第 40 条").assertIsDisplayed()
        assertNoText("A 第 1 条")
        assertNoDescription("回到最新")
        // 消息铺在输入框后面，但最后一条的底得在输入框上面
        val last = compose.onNodeWithText("A 第 40 条").getBoundsInRoot()
        val input = compose.onNodeWithText("发消息…").getBoundsInRoot()
        assertTrue("最后一条 ${last.bottom} 被输入框 ${input.top} 挡住了", last.bottom <= input.top)
    }

    @Test
    fun `最后一条比一屏还长时，打开看到的是它的结尾`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(longConversation("A", count = 10, lastBlocks = 30)),
            )
        )
        renderChat(model)

        // 整个气泡能长按，文字合并在气泡那一个节点上：要看单独每一段，得看没合并的树
        compose.onNodeWithText("A 末条第 30 段\n第二行\n第三行", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("A 末条第 1 段\n第二行\n第三行", useUnmergedTree = true).assertIsNotDisplayed()
        // 结尾在输入框上面，没被挡住
        val end = compose.onNodeWithText("A 末条第 30 段\n第二行\n第三行", useUnmergedTree = true).getBoundsInRoot()
        val input = compose.onNodeWithText("发消息…").getBoundsInRoot()
        assertTrue("末条结尾 ${end.bottom} 被输入框 ${input.top} 挡住了", end.bottom <= input.top)
    }

    @Test
    fun `短会话还是从顶上排下来，按时间先后`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(longConversation("A", count = 2)),
            )
        )
        renderChat(model)

        val first = compose.onNodeWithText("A 第 1 条").getBoundsInRoot()
        val second = compose.onNodeWithText("A 第 2 条").getBoundsInRoot()
        assertTrue("先说的那条应该在上面", first.bottom <= second.top)
        assertTrue("两条消息应该贴着顶栏往下排，不是沉在底下（top=${first.top}）", first.top < 300.dp)
    }

    /** 会话页的消息流（能滚到第几项的那个列表，里面正好有这条） */
    private fun messageList(visibleText: String) =
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(visibleText)))

    @Test
    fun `往上翻过以后换一段会话，新的那段也从最底下开始`() {
        val model = modelWith(
            FridayState(conn = connected, openConversationId = "C1", chat = chatState(longConversation("A")))
        )
        renderChat(model)

        // 往上翻到老消息那儿（倒排：index 30 = 第 10 条）：「回到最新」出来
        messageList("A 第 40 条").performScrollToIndex(30)
        compose.onNodeWithText("A 第 10 条").assertIsDisplayed()
        compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()
        assertNoText("A 第 40 条")

        model.client.state.value = model.client.state.value.copy(
            openConversationId = "C2",
            chat = chatState(longConversation("B")).copy(id = "C2"),
        )
        compose.onNodeWithText("B 第 40 条").assertIsDisplayed()
        assertNoDescription("回到最新")

        // 「回到最新」点了也回到底
        messageList("B 第 40 条").performScrollToIndex(30)
        assertNoText("B 第 40 条")
        compose.onNodeWithContentDescription("回到最新").performClick()
        compose.onNodeWithText("B 第 40 条").assertIsDisplayed()
    }

    @Test
    fun `长按消息能把文本复制到剪贴板`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "assistant", listOf(textBlock("b1", "复制我"))))),
            )
        )
        renderChat(model)

        compose.onNodeWithText("复制我").performTouchInput { longClick() }
        compose.onNodeWithText("复制文本").assertIsDisplayed()
        compose.onNodeWithText("复制文本").performClick()

        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("复制我", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `生成中给停止按钮`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(message("1", "user", listOf(textBlock("b1", "hi")))),
                    runtime = runtime(generating = true),
                ),
            )
        )
        renderChat(model)
        compose.onNodeWithContentDescription("停止").assertIsDisplayed()
        assertNoDescription("发送")
    }

    @Test
    fun `空闲时框里空着默认是语音键，打了字换成发送`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "hi"))))),
            )
        )
        renderChat(model)
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        assertNoDescription("发送")
        assertNoDescription("停止")
        compose.onNodeWithText("发消息…").performTextInput("你好")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        assertNoDescription("语音输入")
    }

    @Test
    fun `排队的消息列出来，暂停时给「接着发」，每条能删`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(message("1", "user", listOf(textBlock("b1", "hi")))),
                    runtime = runtime(
                        generating = true,
                        queuePaused = true,
                        queue = listOf(
                            QueuedInput("q1", "第一条排队的", emptyList(), "2026-01-01T00:00:00Z"),
                            QueuedInput("q2", "第二条排队的", emptyList(), "2026-01-01T00:00:01Z", "助手"),
                        ),
                    ),
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("排队已暂停（2）").assertExists()
        compose.onNodeWithText("第一条排队的").assertExists()
        compose.onNodeWithText("第二条排队的").assertExists()
        compose.onNodeWithText("来自 助手").assertExists()
        compose.onNodeWithText("接着发").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithContentDescription("删掉这条").fetchSemanticsNodes().size)
    }

    @Test
    fun `上一轮失败时给重试按钮`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(message("1", "assistant", listOf(textBlock("b1", "断了")))),
                    runtime = runtime(canRetry = true),
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun `生成用量显示在助手消息下面`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message(
                            "1", "assistant",
                            listOf(textBlock("b1", "答完了")),
                            stats = GenerationStats(42.6, 1234, "智谱 GLM"),
                        )
                    )
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("43 tok/s · 1234 tokens · 智谱 GLM").assertExists()
    }

    @Test
    fun `智能体的回复不包气泡，字直接从列表左边开始`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "问一句"))),
                        message("2", "assistant", listOf(textBlock("b2", "答一句")), parent = "1"),
                    )
                ),
            )
        )
        renderChat(model)

        // 列表左右只留 12dp；包在气泡里时字还要再往里缩一圈
        val answer = compose.onNodeWithText("答一句", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("智能体的回复左边 ${answer.left} 还缩在气泡里", answer.left <= 13.dp)
    }

    @Test
    fun `图片拉不到时说清原因，不崩`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(message("1", "assistant", listOf(textBlock("b1", "看图"), mediaBlock("b2"))))
                ),
            )
        )
        renderChat(model)

        // 没连上远程（rpc 为空）→ 媒体加载失败，界面上给文件名 + 一句原因
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("a.png：未连接").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("a.png：未连接").assertExists()
    }

    @Test
    fun `同一条消息有多个分支时能切来切去`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("root", "user", listOf(textBlock("b0", "问一句")), at = "2026-01-01T00:00:00Z"),
                        message("a1", "assistant", listOf(textBlock("b1", "第一种回答")), "root", at = "2026-01-01T00:00:01Z"),
                        message("a2", "assistant", listOf(textBlock("b2", "第二种回答")), "root", at = "2026-01-01T00:00:02Z"),
                    )
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("第一种回答").assertExists()
        compose.onNodeWithText("1/2").assertExists()

        compose.onNodeWithContentDescription("下一条分支").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("2/2").assertExists()
        compose.onNodeWithText("第二种回答").assertExists()
        assertNoText("第一种回答")
    }

    // MARK: 会话列表

    @Test
    fun `长按会话弹出置顶、重命名、删掉`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                summaries = listOf(
                    ConversationSummary(
                        id = "C1", title = "改代码", updatedAt = "2026-01-01T00:00:00Z",
                        pinned = false, isEmpty = false, hasDraft = false, workingDirectoryName = "friday-android",
                    )
                ),
            )
        )
        render(model) { SessionsScreen(onOpen = {}) }

        compose.onNodeWithText("改代码").assertIsDisplayed()
        compose.onNodeWithText("改代码").performTouchInput { longClick() }

        compose.onNodeWithText("置顶").assertIsDisplayed()
        compose.onNodeWithText("重命名").assertIsDisplayed()
        compose.onNodeWithText("删掉").assertIsDisplayed()

        // 删除确认对话框（没输入框的那个）：Dialog 窗口 + TextField 在 Robolectric 下永远不 idle，
        // 所以重命名对话框的内容不在这里验（它的代码和删除确认同一套 AlertDialog）
        compose.onNodeWithText("重命名").assertExists()
        compose.onNodeWithText("删掉").performClick()
        compose.onNodeWithText("删掉这段会话？").assertExists()
        compose.onNodeWithText("算了").assertExists()
    }

    // MARK: 活动页

    @Test
    fun `待确认的调用给批准和拒绝`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                activities = listOf(
                    SessionActivity(
                        id = "C1", title = "改代码", status = "approval", taskGoal = null, taskSteps = emptyList(),
                        approvals = listOf(PendingApproval("e1", "run_shell", "跑一条命令", "rm -rf build", null)),
                        currentToolTitle = "跑一条命令", currentToolSummary = null,
                        updatedAt = "2026-01-01T00:00:00Z",
                    )
                ),
            )
        )
        render(model) { ActivityScreen() }

        compose.onNodeWithText("待确认（1）").assertIsDisplayed()
        compose.onNodeWithText("跑一条命令").assertIsDisplayed()
        compose.onNodeWithText("rm -rf build").assertIsDisplayed()
        compose.onNodeWithText("批准").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
    }

    @Test
    fun `ask_user 的提问列出选项，推荐的标出来`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                activities = listOf(
                    SessionActivity(
                        id = "C1", title = "改代码", status = "question", taskGoal = null, taskSteps = emptyList(),
                        approvals = listOf(
                            PendingApproval(
                                "e2", "ask_user", "问你一句", "",
                                questions = listOf(
                                    AskQuestion(
                                        question = "用哪个方案？",
                                        options = listOf(AskOption("方案 A", "简单"), AskOption("方案 B", null)),
                                        multiple = false,
                                        recommended = 0,
                                    )
                                ),
                            )
                        ),
                        currentToolTitle = null, currentToolSummary = null,
                        updatedAt = "2026-01-01T00:00:00Z",
                    )
                ),
            )
        )
        render(model) { ActivityScreen() }

        compose.onNodeWithText("用哪个方案？").assertIsDisplayed()
        // 选项文字里不再拼「○」和「（推荐）」：左边是单选图标，推荐单独一个胶囊
        compose.onNodeWithText("方案 A").assertIsDisplayed()
        compose.onNodeWithText("简单").assertIsDisplayed()
        compose.onNodeWithText("方案 B").assertIsDisplayed()
        compose.onNodeWithText("推荐").assertIsDisplayed()
    }

    @Test
    fun `没连上时活动页给提示而不是空白`() {
        render(modelWith(FridayState())) { ActivityScreen() }
        compose.onNodeWithText("连接远程机器后，这里显示所有会话的实时动静和待确认的调用。").assertIsDisplayed()
    }

    // MARK: 顶级页面 + 共用输入框 + 侧栏

    @Test
    fun `会话页是顶级页面：左上角是侧栏入口，不是返回箭头`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "hi"))))),
            )
        )
        renderChat(model)
        compose.onNodeWithText("测试会话").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开侧边栏").assertIsDisplayed()
        assertNoDescription("返回")
    }

    @Test
    fun `会话在跑时能工作补充：停止一直在，打了字多一颗补充给智能体`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "hi"))),
                        message("2", "assistant", listOf(textBlock("b2", "在改了")), parent = "1", at = "2026-01-01T00:00:01Z"),
                        ChatMessage(
                            id = "3", role = "user", blocks = listOf(textBlock("b3", "顺便把注释也补上")),
                            createdAt = "2026-01-01T00:00:02Z", parentID = "2", interjected = true,
                        ),
                    ),
                    runtime = runtime(generating = true),
                ),
            )
        )
        renderChat(model)
        compose.onNodeWithContentDescription("停止").assertIsDisplayed()
        assertNoDescription("补充给智能体")
        // 工作时补充进去的那条消息标出来
        compose.onNodeWithText("工作中补充").assertExists()

        compose.onNodeWithText("补充给智能体：做完当前这一步就交给它").performTextInput("再补一句")
        compose.onNodeWithContentDescription("补充给智能体").assertIsDisplayed()
        compose.onNodeWithContentDescription("停止").assertIsDisplayed()
    }

    @Test
    fun `助手页用同一个输入框：在说话时空着是打断，打了字是发送`() {
        val assistant = AssistantState.fromJson(
            buildJsonObject {
                put("enabled", true)
                put("reacting", true)
                put("config", buildJsonObject { put("name", "小F") })
            }
        )
        val model = modelWith(FridayState(conn = connected, assistant = assistant))
        render(model) {
            val draft = remember { ComposerDraft() }
            AssistantComposer(draft)
        }
        compose.onNodeWithContentDescription("打断").assertIsDisplayed()
        compose.onNodeWithText("想插一句？打了字点发送就打断它重来").performTextInput("#a1b2 先停一下")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onNodeWithContentDescription("打断").assertIsDisplayed()
        compose.onNodeWithText("这条直接发给 #a1b2，不经过助手").assertIsDisplayed()
    }

    @Test
    fun `侧栏：会话标题只有一个，新建在标题旁边，按置顶和日期分组`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                runner = RunnerState(
                    generating = emptyList(), stopping = emptyList(),
                    pendingApproval = listOf("C2"), pendingQuestion = listOf("C2"),
                ),
                summaries = listOf(
                    ConversationSummary(
                        id = "C1", title = "钉住的那段", updatedAt = "2026-01-02T00:00:00Z",
                        pinned = true, isEmpty = false, hasDraft = false,
                    ),
                    ConversationSummary(
                        id = "C2", title = "老早的那段", updatedAt = "2026-01-01T00:00:00Z",
                        pinned = false, isEmpty = false, hasDraft = true, workingDirectoryName = "proj",
                    ),
                ),
            )
        )
        render(model) {
            HomeScaffold(
                tab = HomeTabs.ASSISTANT,
                onTab = {},
                sessions = { SessionsScreen(onOpen = {}, selectedId = "C1") },
            ) {}
        }
        // 旧版在侧栏里又压了一条会话页头：标题出现两遍、「新会话」悬浮按钮盖住列表
        assertEquals(1, compose.onAllNodesWithText("会话").fetchSemanticsNodes().size)
        compose.onNodeWithText("新建").assertExists()
        assertNoText("新会话")
        compose.onNodeWithText("置顶").assertExists()
        compose.onNodeWithText("更早").assertExists()
        compose.onNodeWithText("钉住的那段").assertExists()
        compose.onNodeWithText("草稿").assertExists()
        // 同一段会话又要批准又在提问，只算一段
        compose.onNodeWithText("1 待确认").assertExists()
    }

    // MARK: 输入框「＋」面板：附件 / 模型（带余额）/ 任务模式 / 思考模式 / 工作目录 / 视频通话

    private val engines = listOf(
        com.integemjack.friday.proto.EngineInfo("E1", "智谱", "glm-4.6", baseURL = "https://open.bigmodel.cn/api/paas/v4", apiKeySecret = "ZHIPU_KEY"),
        com.integemjack.friday.proto.EngineInfo("E2", "Anthropic", "claude-sonnet-4.5", baseURL = "https://api.anthropic.com", apiKeySecret = "ANTHROPIC_KEY"),
        com.integemjack.friday.proto.EngineInfo("E3", "关掉的服务", "old-model", disabled = true),
    )

    private val usage = mapOf(
        engines[0].usageKey to com.integemjack.friday.proto.UsageInfo(
            line = com.integemjack.friday.proto.UsageLine("余额 ¥12.34", false), checkedAt = Long.MAX_VALUE,
        ),
        engines[1].usageKey to com.integemjack.friday.proto.UsageInfo(
            line = com.integemjack.friday.proto.UsageLine("额度用完，15:20 恢复", true), checkedAt = Long.MAX_VALUE,
        ),
    )

    private fun chatWithSettings(levels: List<String>? = listOf("off", "low", "medium", "high")) = modelWith(
        FridayState(
            conn = connected,
            engines = engines,
            defaultEngineID = "E2",
            usage = usage,
            directories = listOf(
                com.integemjack.friday.proto.RecentDirectory("/home/u/proj", true),
                com.integemjack.friday.proto.RecentDirectory("/home/u/gone", false),
            ),
            summaries = listOf(
                ConversationSummary(
                    id = "C1", title = "测试会话", updatedAt = "2026-01-01T00:00:00Z",
                    pinned = false, isEmpty = false, hasDraft = false,
                    engineID = "E1", teamMode = "delegatesHeavy",
                )
            ),
            openConversationId = "C1",
            chat = chatState(
                listOf(message("1", "user", listOf(textBlock("b1", "hi")))),
                runtime = runtime().copy(thinkingLevel = "medium", thinkingLevels = levels),
            ),
        )
    )

    @Test
    fun `会话页输入框底下一行写着模型、任务模式、思考，选择都收在加号里`() {
        renderChat(chatWithSettings())
        compose.onNodeWithText("glm-4.6 · 按需派 · 思考 中").assertIsDisplayed()
        // 工具行上不再有单独的附件 / 模型胶囊
        assertNoDescription("添加附件")

        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("图片 / 视频").assertIsDisplayed()
        compose.onNodeWithText("文件").assertIsDisplayed()
        compose.onNodeWithText("工作目录").assertIsDisplayed()
        // 模型那行底下就是当前这家的余额
        compose.onNodeWithText("余额 ¥12.34").assertIsDisplayed()
        // 会话页没有视频通话（那是跟助手的）
        assertNoText("视频通话")
    }

    @Test
    fun `模型列表每个下面写着那家的余额或额度，关掉的服务不列`() {
        renderChat(chatWithSettings())
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("模型").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("claude-sonnet-4.5").assertIsDisplayed()
        compose.onNodeWithText("Anthropic").assertIsDisplayed()
        compose.onNodeWithText("额度用完，15:20 恢复").assertIsDisplayed()
        compose.onNodeWithText("余额 ¥12.34").assertIsDisplayed()
        assertNoText("old-model")
    }

    @Test
    fun `任务模式三种、工作目录列出默认和最近的`() {
        renderChat(chatWithSettings())
        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("任务模式").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("不派子智能体（默认）").assertIsDisplayed()
        compose.onNodeWithText("需要时派子智能体").assertIsDisplayed()
        compose.onNodeWithText("工作流").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("工作目录").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("默认目录").assertIsDisplayed()
        // 现在用的就是 /home/u/proj：页头副标题一处、最近列表里打勾的一处
        assertEquals(2, compose.onAllNodesWithText("/home/u/proj").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("当前").assertIsDisplayed()
        compose.onNodeWithText("/home/u/gone（不在了）").assertIsDisplayed()
        compose.onNodeWithText("浏览这台机器上的目录…").assertIsDisplayed()
    }

    @Test
    fun `这个模型不能调思考时写不能调`() {
        renderChat(chatWithSettings(levels = emptyList()))
        compose.onNodeWithText("glm-4.6 · 按需派 · 思考 不能调").assertIsDisplayed()
    }

    @Test
    fun `助手页加号里有附件和视频通话，模型、任务模式、思考对应助手的设置`() {
        val assistant = AssistantState.fromJson(
            buildJsonObject {
                put("enabled", true)
                put("reacting", false)
                put("config", buildJsonObject {
                    put("name", "小F")
                    put("brainThinking", true)
                })
            }
        )
        val model = modelWith(
            FridayState(
                conn = connected, assistant = assistant, engines = engines, usage = usage,
                defaultEngineID = "E1", defaultTeamMode = "ultra",
            )
        )
        render(model) {
            val draft = remember { ComposerDraft() }
            AssistantComposer(draft)
        }
        // 没选过助手的模型：显示新对话的默认；brainThinking 是布尔 → 开启
        compose.onNodeWithText("glm-4.6 · 工作流 · 思考 开启").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()

        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("图片 / 视频").assertIsDisplayed()
        compose.onNodeWithText("视频通话").assertIsDisplayed()
        compose.onNodeWithText("位置").assertIsDisplayed()
        assertNoText("工作目录")

        compose.onNodeWithText("模型").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("跟新对话一样").assertIsDisplayed()
        compose.onNodeWithText("现在是 glm-4.6").assertIsDisplayed()
    }

    @Test
    fun `助手页定好的位置挂在输入框上面，空着也能发，不想发能去掉`() {
        val assistant = AssistantState.fromJson(
            buildJsonObject {
                put("enabled", true)
                put("reacting", false)
                put("config", buildJsonObject { put("name", "小F") })
            }
        )
        val model = modelWith(FridayState(conn = connected, assistant = assistant))
        val draft = ComposerDraft().apply {
            location = SharedLocation(31.230416, 121.473701, 15f, 1_700_000_000_000L, "上海市黄浦区人民大道200号")
        }
        render(model) { AssistantComposer(draft) }

        compose.onNodeWithText("位置：上海市黄浦区人民大道200号 · 精度约 15 米").assertIsDisplayed()
        // 框里没字，但有位置要发：右边是发送不是话筒
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        assertNoDescription("语音输入")

        compose.onNodeWithContentDescription("不发位置").performClick()
        assertNoText("位置：上海市黄浦区人民大道200号 · 精度约 15 米")
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
    }

    // MARK: 离线 / 本地快照 / 草稿

    private val offlineSummary = ConversationSummary(
        id = "C1", title = "改代码", updatedAt = "2026-01-01T00:00:00Z",
        pinned = false, isEmpty = false, hasDraft = false,
    )

    @Test
    fun `离线时侧栏照样列出存下来的会话，顶上标离线`() {
        val model = modelWith(
            FridayState(conn = ConnState.Disconnected, summaries = listOf(offlineSummary), cachedAt = System.currentTimeMillis())
        )
        render(model) { SessionsScreen(onOpen = {}) }
        compose.onNodeWithText("改代码").assertIsDisplayed()
        compose.onNodeWithText("离线", substring = true).assertIsDisplayed()
        assertNoText("还没有连接远程机器")
    }

    @Test
    fun `离线时助手页照样显示存下来的日志`() {
        val assistant = AssistantState.fromJson(buildJsonObject { put("enabled", true); put("config", buildJsonObject { put("name", "小F") }) })
        val model = modelWith(
            FridayState(
                conn = ConnState.Disconnected,
                assistant = assistant,
                assistantEntries = listOf(
                    com.integemjack.friday.proto.JournalEntry(
                        "j1", "2026-01-01T00:00:00Z", "user", null, "昨晚说的那句", emptyList(), false, false, null,
                    )
                ),
                cachedAt = System.currentTimeMillis(),
            )
        )
        render(model) { AssistantScreen() }
        compose.onNodeWithText("昨晚说的那句").assertExists()
        compose.onNodeWithText("存下来的", substring = true).assertExists()
        assertNoText("连上远程 Friday 才有助手")
    }

    @Test
    fun `离线点开会话看存下来的内容，输入框说清楚现在不能发`() {
        val model = modelWith(FridayState(conn = ConnState.Disconnected, summaries = listOf(offlineSummary)))
        val cached = chatState(listOf(message("1", "assistant", listOf(textBlock("b1", "上次的回答")))))
        model.client.cache = object : com.integemjack.friday.proto.FridayClient.Cache {
            override fun load(botId: Long): FridayState? = null
            override fun loadChat(botId: Long, conversationId: String) = cached.takeIf { conversationId == "C1" }
            override fun removeChat(botId: Long, conversationId: String) {}
        }
        kotlinx.coroutines.runBlocking { model.client.subscribeConversation("C1") }
        assertEquals("C1", model.client.state.value.openConversationId)

        renderChat(model)
        compose.onNodeWithText("上次的回答").assertExists()
        compose.onNodeWithText("离线：连上远程机器以后才能发（打的字会留着）").assertIsDisplayed()

        // 没存过的那段：说清楚为什么打不开
        val error = kotlin.runCatching { kotlinx.coroutines.runBlocking { model.client.subscribeConversation("C9") } }.exceptionOrNull()
        assertEquals("没连上远程机器，这段会话也没有存下来的内容", error?.message)
    }

    @Test
    fun `打了一半的话存到本地，重开以后还在，发出去就删`() {
        val store = com.integemjack.friday.data.DraftStore(app)
        store.clearAll()
        ChatDrafts(store).of("C1").input = "打了一半"
        // 「重开 App」：新的一份草稿表，从本地读
        val again = ChatDrafts(store).of("C1")
        assertEquals("打了一半", again.input)
        again.clear()
        assertEquals("", ChatDrafts(store).of("C1").input)
    }

    // MARK: 登录页 / 选机器人 / 设置里的账号

    @Test
    fun `没登录时一打开就是登录页，没装 SChat 时给二维码，其他方式折着`() {
        // 登录页 0.6 秒后会去建扫码会话（真网络）：测试里时钟不走，只看排版
        compose.mainClock.autoAdvance = false
        render(AppModel(app)) { com.integemjack.friday.AppRoot(kotlinx.coroutines.flow.MutableStateFlow(null)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("登录 Friday").assertExists()
        compose.onNodeWithText("打开手机上的 SChat → 扫一扫，扫这个码").assertExists()
        compose.onNodeWithText("其他方式").assertExists()
        assertNoText("通过 SChat 登录")
        // 主框架（侧栏、助手页）不在
        assertNoDescription("打开侧边栏")
    }

    @Test
    fun `二维码出来以后写着多久过期，扫到了提示在手机上确认`() {
        var qr by mutableStateOf<com.integemjack.friday.schat.SchatService.QrLogin>(
            com.integemjack.friday.schat.SchatService.QrLogin.Waiting("tok123", "https://s", System.currentTimeMillis() + 299_000)
        )
        render(modelWith(FridayState())) { QrPanel(qr, onRefresh = {}) }
        compose.onNodeWithText("后过期", substring = true).assertExists()
        qr = (qr as com.integemjack.friday.schat.SchatService.QrLogin.Waiting).copy(scanned = true)
        compose.waitForIdle()
        compose.onNodeWithText("已扫描，请在手机上点「确认登录」").assertExists()
        qr = com.integemjack.friday.schat.SchatService.QrLogin.Expired
        compose.waitForIdle()
        compose.onNodeWithText("重新生成二维码").assertExists()
        compose.onNodeWithText("二维码过期了，点一下重新生成").assertExists()
    }

    @Test
    fun `二维码里写的是 SChat 扫一扫认的 SCSH login 串`() {
        val waiting = com.integemjack.friday.schat.SchatService.QrLogin.Waiting("AbC_-1", "https://s", 0)
        assertEquals("SCSH:1:login:AbC_-1", waiting.payload)
    }

    @Test
    fun `看得出这台手机装没装 SChat、认不认通过 SChat 登录的链接`() {
        val shadow = org.robolectric.Shadows.shadowOf(app.packageManager)
        assertEquals(SchatApp.Missing, schatApp(app))

        shadow.installPackage(android.content.pm.PackageInfo().apply { packageName = SCHAT_PACKAGE })
        assertEquals(SchatApp.Outdated, schatApp(app))

        val probe = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("schat://login/probe"))
            .setPackage(SCHAT_PACKAGE)
        shadow.addResolveInfoForIntent(probe, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = SCHAT_PACKAGE; name = "MainActivity" }
        })
        assertEquals(SchatApp.Ready, schatApp(app))

        val link = schatLoginIntent("tok", "https://47.100.98.3:2012")
        assertEquals("schat://login/tok?s=https%3A%2F%2F47.100.98.3%3A2012", link.dataString)
        assertEquals(SCHAT_PACKAGE, link.`package`)
    }

    @Test
    fun `设置里账号那块只有切换 bot 和退出登录`() {
        compose.mainClock.autoAdvance = false
        render(modelWith(FridayState(conn = connected))) { SettingsScreen() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("切换 bot").assertExists()
        compose.onNodeWithText("退出登录").assertExists()
        assertNoText("账号（用户名 / 手机号）")
        assertNoText("刷新列表")
        assertNoText("断开工作通道")
    }
}
