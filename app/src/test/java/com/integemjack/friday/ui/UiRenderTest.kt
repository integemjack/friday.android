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
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun `空闲时给发送按钮`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "hi"))))),
            )
        )
        renderChat(model)
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        assertNoDescription("停止")
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
    fun `会话页生成中打了字也能发（排队），空着才是停止`() {
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
        compose.onNodeWithText("接着说会排队（空着点右边是停止）").performTextInput("再补一句")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        assertNoDescription("停止")
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
        compose.onNodeWithText("想插一句？（空着点右边是打断）").performTextInput("#a1b2 先停一下")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
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

    // MARK: 输入框工具行：附件 / 模型 / 任务模式 / 思考模式

    private val engines = listOf(
        com.integemjack.friday.proto.EngineInfo("E1", "智谱", "glm-4.6"),
        com.integemjack.friday.proto.EngineInfo("E2", "Anthropic", "claude-sonnet-4.5"),
        com.integemjack.friday.proto.EngineInfo("E3", "关掉的服务", "old-model", disabled = true),
    )

    @Test
    fun `会话页输入框列出这段对话的模型、任务模式、思考档位，点开能选`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                engines = engines,
                defaultEngineID = "E2",
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
                    runtime = runtime().copy(thinkingLevel = "medium", thinkingLevels = listOf("off", "low", "medium", "high")),
                ),
            )
        )
        renderChat(model)
        compose.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        compose.onNodeWithText("glm-4.6").assertIsDisplayed()
        compose.onNodeWithText("按需派").assertIsDisplayed()
        compose.onNodeWithText("中").assertIsDisplayed()

        // 模型菜单：能用的都列，关掉的服务不列
        compose.onNodeWithText("glm-4.6").performClick()
        compose.onNodeWithText("claude-sonnet-4.5").assertIsDisplayed()
        compose.onNodeWithText("Anthropic").assertIsDisplayed()
        assertNoText("old-model")
    }

    @Test
    fun `任务模式菜单列三种，思考只有开关两档时写开启关闭，不能调时说清楚`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                engines = engines,
                defaultEngineID = "E2",
                openConversationId = "C1",
                chat = chatState(
                    listOf(message("1", "user", listOf(textBlock("b1", "hi")))),
                    runtime = runtime().copy(thinkingLevel = "high", thinkingLevels = listOf("off", "high")),
                ),
            )
        )
        renderChat(model)
        // 没选过模型 / 任务模式：跟新对话的默认（E2）、solo
        compose.onNodeWithText("claude-sonnet-4.5").assertIsDisplayed()
        compose.onNodeWithText("开启").assertIsDisplayed()
        compose.onNodeWithText("不派").performClick()
        compose.onNodeWithText("不派子智能体（默认）").assertIsDisplayed()
        compose.onNodeWithText("需要时派子智能体").assertIsDisplayed()
        compose.onNodeWithText("工作流").assertIsDisplayed()
    }

    @Test
    fun `这个模型不能调思考时，思考胶囊写不能调`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(message("1", "user", listOf(textBlock("b1", "hi")))),
                    runtime = runtime().copy(thinkingLevels = emptyList()),
                ),
            )
        )
        renderChat(model)
        compose.onNodeWithText("不能调").assertIsDisplayed()
    }

    @Test
    fun `助手页输入框也能加附件，也有模型、任务模式、思考模式`() {
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
                conn = connected, assistant = assistant, engines = engines,
                defaultEngineID = "E1", defaultTeamMode = "ultra",
            )
        )
        render(model) {
            val draft = remember { ComposerDraft() }
            AssistantComposer(draft)
        }
        compose.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        // 没选过助手的模型：胶囊上显示新对话的默认
        compose.onNodeWithText("glm-4.6").assertIsDisplayed()
        compose.onNodeWithText("工作流").assertIsDisplayed()
        // brainThinking 是布尔：两档，写「开启」
        compose.onNodeWithText("开启").assertIsDisplayed()

        compose.onNodeWithText("glm-4.6").performClick()
        compose.onNodeWithText("跟新对话一样").assertIsDisplayed()
        compose.onNodeWithText("现在是 glm-4.6").assertIsDisplayed()
    }
}
