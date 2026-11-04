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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import com.integemjack.friday.AppModel
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.ChatState
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.GenerationStats
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.QueuedInput
import com.integemjack.friday.proto.Runtime
import com.integemjack.friday.proto.ServerInfo
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

        // 思考块默认折着，点开才显示内容
        assertNoText("先看看这是什么")
        compose.onNodeWithText("思考 …").performClick()
        compose.onNodeWithText("先看看这是什么").assertExists()

        // 操作只一行：结果的第一行跟在名字后面（同一个文本），不另起一行
        compose.onNodeWithText("看图 · a.png  是只橘猫").assertExists()
        assertNoText("是只橘猫")
        // 点开：名字后面不再跟结果，完整结果在下面
        compose.onNodeWithText("看图 · a.png  是只橘猫").performClick()
        compose.onNodeWithText("看图 · a.png").assertExists()
        compose.onNodeWithText("是只橘猫").assertExists()
    }

    @Test
    fun `连续操作收成一行，点开一行一个；改文件点开看 diff`() {
        val edit = MessageBlock(
            "e1",
            buildJsonObject {
                put("tool", buildJsonObject {
                    put("_0", buildJsonObject {
                        put("id", "e1"); put("name", "edit_file"); put("title", "修改文件")
                        put("arguments", """{"path":"/home/u/proj/src/a.c","edits":[{"old_text":"int a;","new_text":"int a = 1;"}]}""")
                        put("status", "succeeded"); put("result", "已修改 /home/u/proj/src/a.c：替换了 1 处")
                        put("linesAdded", 1); put("linesRemoved", 1)
                    })
                })
            },
        )
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "改一下"))),
                        message(
                            "2", "assistant",
                            listOf(toolBlock("x1", "run_shell", "执行命令", "succeeded", "ok"), edit, textBlock("s1", "改好了")),
                            parent = "1", at = "2026-01-01T00:00:01Z",
                        ),
                    )
                ),
            )
        )
        renderChat(model)

        // 收起来只有一行：做了哪几种、改了多少行（不写「N 个操作」）
        compose.onNodeWithText("执行命令、修改文件").assertExists()
        assertNoText("2 个操作")
        compose.onNodeWithText("+1 −1").assertExists()
        assertNoText("执行命令  ok")
        compose.onNodeWithText("改好了").assertExists()

        // 点开：一行一个，改文件的那行跟着文件名
        compose.onNodeWithText("执行命令、修改文件").performClick()
        compose.onNodeWithText("执行命令  ok").assertExists()
        compose.onNodeWithText("修改文件  a.c").assertExists()

        // 再点改文件那行：看到 diff（删的、加的），不是参数 JSON
        assertNoText("int a = 1;")
        compose.onNodeWithText("修改文件  a.c").performClick()
        compose.onNodeWithText("int a;").assertExists()
        compose.onNodeWithText("int a = 1;").assertExists()
        compose.onNodeWithText("已修改 /home/u/proj/src/a.c：替换了 1 处").assertExists()
    }

    @Test
    fun `在跑的操作不折进去，自己摊开在跑什么；参数不贴 JSON`() {
        val running = MessageBlock(
            "x3",
            buildJsonObject {
                put("tool", buildJsonObject {
                    put("_0", buildJsonObject {
                        put("id", "x3"); put("name", "run_shell"); put("title", "执行命令")
                        put("arguments", """{"command":"ctest -R http","timeout_seconds":600}""")
                        put("status", "running"); put("result", "")
                    })
                })
            },
        )
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "跑测试"))),
                        message(
                            "2", "assistant",
                            listOf(
                                toolBlock("x1", "read_file", "读取文件", "succeeded", "1 行"),
                                toolBlock("x2", "run_shell", "执行命令", "succeeded", "退出码 0"),
                                running,
                            ),
                            parent = "1", at = "2026-01-01T00:00:01Z",
                        ),
                    ),
                    runtime = runtime(generating = true),
                ),
            )
        )
        renderChat(model)

        // 跑完的两个折成一行，在跑的那个单独一行、详情摊开
        compose.onNodeWithText("读取文件、执行命令").assertExists()
        compose.onNodeWithText("$ ctest -R http").assertExists()
        assertNoText("""{"command":"ctest -R http","timeout_seconds":600}""")
    }

    @Test
    fun `点开思考时那一行留在原地，内容往下长`() {
        val long = (1..12).joinToString("\n") { "想法第 $it 行" }
        val conversation = longConversation("A", count = 30).toMutableList()
        val last = conversation.last()
        // 倒数第二条（离底不远、在屏幕里）带一段思考
        val target = conversation[conversation.size - 2]
        conversation[conversation.size - 2] = target.copy(blocks = listOf(thinkingBlock("T", long)) + target.blocks)
        val model = modelWith(
            FridayState(conn = connected, openConversationId = "C1", chat = chatState(conversation.dropLast(1) + last))
        )
        renderChat(model)

        val before = compose.onNodeWithText("思考 …").getBoundsInRoot().top
        compose.onNodeWithText("思考 …").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("想法第 12 行", substring = true).assertExists()
        val after = compose.onNodeWithText("思考 ▾").getBoundsInRoot().top
        assertTrue("「思考」那一行被顶走了：$before → $after", kotlin.math.abs((after - before).value) <= 2f)

        // 收起：还在原地
        compose.onNodeWithText("思考 ▾").performClick()
        compose.waitForIdle()
        val collapsed = compose.onNodeWithText("思考 …").getBoundsInRoot().top
        assertTrue("收起后「思考」那一行挪了：$before → $collapsed", kotlin.math.abs((collapsed - before).value) <= 2f)
    }

    @Test
    fun `停在最底下时点开最后一条的思考，也留在原地往下长（不被跟底顶上去）`() {
        val long = (1..20).joinToString("\n") { "想法第 $it 行" }
        val conversation = longConversation("A", count = 30).toMutableList()
        val last = conversation.last()
        conversation[conversation.size - 1] = last.copy(blocks = listOf(thinkingBlock("T", long)) + last.blocks)
        val model = modelWith(FridayState(conn = connected, openConversationId = "C1", chat = chatState(conversation)))
        renderChat(model)

        val before = compose.onNodeWithText("思考 …").getBoundsInRoot().top
        compose.onNodeWithText("思考 …").performClick()
        compose.waitForIdle()
        val after = compose.onNodeWithText("思考 ▾").getBoundsInRoot().top
        assertTrue("最后一条的「思考」被顶走了：$before → $after", kotlin.math.abs((after - before).value) <= 2f)
        // 内容往下长到了输入框后面：「回到最新」出来，点了再到底
        compose.onNodeWithContentDescription("回到最新").performClick()
        compose.waitForIdle()
        val end = compose.onNodeWithText("A 第 30 条").getBoundsInRoot()
        val input = compose.onNodeWithText("发消息…").getBoundsInRoot()
        assertTrue("回到最新后最后一条 ${end.bottom} 被输入框 ${input.top} 挡住了", end.bottom <= input.top)
    }

    @Test
    fun `在最底下时最后一条变长会跟到底；翻上去以后就不跟`() {
        val model = modelWith(
            FridayState(conn = connected, openConversationId = "C1", chat = chatState(longConversation("A", count = 30)))
        )
        renderChat(model)

        fun grow(prefix: String) {
            val chat = model.client.state.value.chat!!
            val last = chat.nodes.getValue("A30")
            val more = (1..15).map { textBlock("$prefix-$it", "$prefix 新的第 $it 段") }
            model.client.state.value = model.client.state.value.copy(
                chat = chat.copy(nodes = chat.nodes + ("A30" to last.copy(blocks = last.blocks + more)))
            )
            compose.waitForIdle()
        }

        // 贴着底：流式长出来的跟上，最新那段在输入框上面
        grow("X")
        val newest = compose.onNodeWithText("X 新的第 15 段", useUnmergedTree = true).getBoundsInRoot()
        val input = compose.onNodeWithText("发消息…").getBoundsInRoot()
        assertTrue("没跟到底：最新一段 ${newest.bottom}，输入框 ${input.top}", newest.bottom <= input.top && newest.bottom > input.top - 120.dp)
        assertNoDescription("回到最新")

        // 翻上去看老的：再长也不抢位置
        messageList("A 第 29 条").performScrollToIndex(9)
        val reading = compose.onNodeWithText("A 第 10 条").getBoundsInRoot().top
        grow("Y")
        assertEquals(reading, compose.onNodeWithText("A 第 10 条").getBoundsInRoot().top)
        compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()
    }

    private fun taskActivity(steps: List<com.integemjack.friday.proto.TaskStep>, goal: String = "发 v1.2") =
        com.integemjack.friday.proto.SessionActivity(
            id = "C1", title = "测试会话", status = "running", taskGoal = goal, taskSteps = steps,
            approvals = emptyList(), currentToolTitle = null, currentToolSummary = null, updatedAt = "2026-01-01T00:00:00Z",
        )

    private fun step(text: String, status: String) = com.integemjack.friday.proto.TaskStep(text, status)

    @Test
    fun `任务条挂在输入框上面：在做的那步和进度，点开看目标和全部步骤`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "发版")))), runtime = runtime(generating = true)),
                activities = listOf(taskActivity(listOf(step("写 CHANGELOG", "done"), step("打 tag", "doing"), step("推送", "todo")))),
            )
        )
        renderChat(model)

        compose.onNodeWithText("打 tag").assertExists()
        compose.onNodeWithText("1/3").assertExists()
        assertNoText("写 CHANGELOG")

        compose.onNodeWithContentDescription("展开任务").performClick()
        compose.onNodeWithText("发 v1.2").assertExists()
        compose.onNodeWithText("写 CHANGELOG").assertExists()
        compose.onNodeWithText("推送").assertExists()
    }

    @Test
    fun `任务做完了也不在跑了，任务条就不挂`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "发版")))), runtime = runtime(generating = false)),
                activities = listOf(taskActivity(listOf(step("写 CHANGELOG", "done"), step("打 tag", "done")))),
            )
        )
        renderChat(model)
        assertNoText("2/2")
        assertNoDescription("展开任务")
    }

    private fun backgroundRun(id: String, name: String, title: String = "执行命令") = com.integemjack.friday.proto.TeamRun(
        id = id, name = name, conversationID = "C1", background = true, detached = true, depth = 1,
        running = true, stopRequested = false, activityKind = "tool", activityTitle = title, steps = 4,
        started = "2026-01-01T00:00:00Z", lastOutput = "删了 3 个缓存目录", speed = null,
    )

    @Test
    fun `后台有子 Agent 在跑时输入框上面挂一行`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "清理一下"))))),
                team = listOf(backgroundRun("r1", "清理执行员")),
            )
        )
        renderChat(model)
        compose.onNodeWithText("后台：1 个子 Agent").assertExists()
        compose.onNodeWithContentDescription("看后台任务").assertExists()
    }

    private fun terminal(id: String, running: Boolean, exitCode: Int? = null, conversation: String = "C1") =
        com.integemjack.friday.proto.TerminalInfo(
            id = id, conversationID = conversation, title = "t", shell = "pwsh",
            command = "ctest -R http --output-on-failure", running = running, exitCode = exitCode,
        )

    private fun loopTask(id: String, conversation: String? = "C1", target: String = "conversation", paused: Boolean = false) =
        com.integemjack.friday.proto.LoopTask(
            id = id, number = 3, triggerKind = "weekly", minutes = null, days = 0x1f, minuteOfDay = 17 * 60 + 45, at = null,
            content = "跑模拟盘并汇报\n第二行", target = target, conversationID = conversation, title = null, createdBy = "agent",
            nextRun = null, lastRun = null, runStartedAt = null, paused = paused, runCount = 12, lastResult = "B 净值 1.03",
        )

    @Test
    fun `终端命令和这段会话的定时任务也挂在后台那一行；别的会话的不算`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(listOf(message("1", "user", listOf(textBlock("b1", "跑测试"))))),
                terminals = listOf(terminal("term1", running = true), terminal("other", running = true, conversation = "C2")),
                loops = listOf(loopTask("loop1"), loopTask("loop2", conversation = "C2"), loopTask("sys", conversation = null, target = "system")),
            )
        )
        renderChat(model)
        compose.onNodeWithText("后台：1 个终端在跑、1 个定时任务").assertExists()
    }

    @Test
    fun `后台任务面板：子 Agent 能停，终端能看输出、能结束，定时任务能马上跑、能暂停`() {
        val calls = mutableListOf<String>()
        val tasks = BackgroundTasks(
            agents = listOf(backgroundRun("r1", "清理执行员"), backgroundRun("r2", "测试员", title = "读取文件")),
            terminals = listOf(terminal("term1", running = true), terminal("term2", running = false, exitCode = 1)),
            loops = listOf(loopTask("loop1")),
        )
        render(modelWith(FridayState(conn = connected))) {
            BackgroundContent(
                tasks,
                BackgroundActions(
                    stopAgent = { calls += "stop:$it" },
                    closeTerminal = { calls += "close:$it" },
                    terminalOutput = { id -> calls += "output:$id"; "PASS 12/12" },
                    setLoopPaused = { id, paused -> calls += "pause:$id:$paused" },
                    runLoopNow = { calls += "run:$it" },
                ),
            )
        }
        // 子 Agent：在做什么、第几步，能单独停、能全部停
        compose.onNodeWithText("清理执行员").assertExists()
        assertEquals(1, compose.onAllNodesWithText("在执行「读取文件」 · 第 4 步", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("停下")[0].performClick()
        compose.onNodeWithText("全部停下（2）").performClick()
        assertEquals(listOf("stop:r1", "stop:r1", "stop:r2"), calls)
        calls.clear()

        // 终端：命令、在跑 / 退出码；看输出、结束、关掉
        assertEquals(2, compose.onAllNodesWithText("ctest -R http --output-on-failure").fetchSemanticsNodes().size)
        compose.onNodeWithText("在跑").assertExists()
        compose.onNodeWithText("已结束 · 退出码 1").assertExists()
        compose.onAllNodesWithText("看输出")[0].performClick()
        compose.onNodeWithText("PASS 12/12").assertExists()
        compose.onNodeWithText("结束").performScrollTo().performClick()
        compose.onNodeWithText("关掉").performScrollTo().performClick()
        assertEquals(listOf("output:term1", "close:term1", "close:term2"), calls)
        calls.clear()

        // 定时任务：内容第一行、什么时候跑、跑过几次、上次结果；马上跑、暂停
        compose.onNodeWithText("跑模拟盘并汇报").assertExists()
        compose.onNodeWithText("工作日 17:45 · 跑过 12 次").assertExists()
        compose.onNodeWithText("上次：B 净值 1.03").assertExists()
        compose.onNodeWithText("马上跑").performScrollTo().performClick()
        compose.onNodeWithText("暂停").performScrollTo().performClick()
        assertEquals(listOf("run:loop1", "pause:loop1:true"), calls)
    }

    @Test
    fun `助手页输入框上面挂助手名下的定时任务`() {
        val assistant = AssistantState.fromJson(buildJsonObject { put("enabled", true); put("config", buildJsonObject { put("name", "小F") }) })
        val model = modelWith(
            FridayState(
                conn = connected,
                assistant = assistant,
                loops = listOf(loopTask("a1", conversation = null, target = "assistant"), loopTask("c1")),
            )
        )
        render(model) { AssistantComposer(remember { ComposerDraft() }) }
        compose.onNodeWithText("后台：1 个定时任务").assertExists()
    }

    @Test
    fun `消息里的记下任务状态：行里写进度，点开是步骤清单不是 JSON`() {
        val record = MessageBlock(
            "u1",
            buildJsonObject {
                put("tool", buildJsonObject {
                    put("_0", buildJsonObject {
                        put("id", "u1"); put("name", "update_task_state"); put("title", "记下任务状态")
                        put("arguments", """{"goal":"发 v1.2","steps":[{"status":"done","text":"写 CHANGELOG"},{"status":"doing","text":"打 tag"}]}""")
                        put("status", "succeeded"); put("result", "已记下。")
                    })
                })
            },
        )
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "发版"))),
                        message("2", "assistant", listOf(record), parent = "1", at = "2026-01-01T00:00:01Z"),
                    )
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("记下任务状态  进度 1/2 · 打 tag").assertExists()
        compose.onNodeWithText("记下任务状态  进度 1/2 · 打 tag").performClick()
        compose.onNodeWithText("发 v1.2").assertExists()
        compose.onNodeWithText("写 CHANGELOG").assertExists()
        assertNoText("已记下。")
    }

    @Test
    fun `刚发出去、智能体那条还空着时显示思考中，不是无内容`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "开始吧"))),
                        message("2", "assistant", emptyList(), parent = "1", at = "2026-01-01T00:00:01Z"),
                    ),
                    runtime = runtime(generating = true),
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("思考中…").assertExists()
        assertNoText("（无内容）")
    }

    @Test
    fun `只有思考的那条不再多一句无内容`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "想想"))),
                        message("2", "assistant", listOf(thinkingBlock("b2", "嗯")), parent = "1", at = "2026-01-01T00:00:01Z"),
                    ),
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("思考 …").assertExists()
        assertNoText("（无内容）")
    }

    @Test
    fun `跑完了真的什么都没有才写无内容`() {
        val model = modelWith(
            FridayState(
                conn = connected,
                openConversationId = "C1",
                chat = chatState(
                    listOf(
                        message("1", "user", listOf(textBlock("b1", "在吗"))),
                        message("2", "assistant", emptyList(), parent = "1", at = "2026-01-01T00:00:01Z"),
                    ),
                    runtime = runtime(generating = false),
                ),
            )
        )
        renderChat(model)

        compose.onNodeWithText("（无内容）").assertExists()
        assertNoText("思考中…")
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

        // 往上翻到老消息那儿（index 9 = 第 10 条）：「回到最新」出来
        messageList("A 第 40 条").performScrollToIndex(9)
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
        messageList("B 第 40 条").performScrollToIndex(9)
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
        var picked: Int? = null
        render(model) {
            HomeScaffold(
                tab = HomeTabs.SETTINGS,
                onTab = { picked = it },
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
        // 活动页和单列的「助手」都去掉了：顶上的 Friday 就是助手页入口
        assertNoText("活动")
        assertNoText("助手")
        compose.onNodeWithText("Friday").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(HomeTabs.ASSISTANT, picked)
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

    private fun journalEntry(id: String, kind: String, text: String, tool: String? = null) =
        com.integemjack.friday.proto.JournalEntry(
            id, "2026-01-01T00:00:${id.filter { it.isDigit() }.padStart(2, '0').takeLast(2)}Z", kind, null, text,
            emptyList(), false, false,
            tool?.let { buildJsonObject { put("name", it); put("arguments", """{"query":"$text"}"""); put("result", "查到 3 条") } },
        )

    @Test
    fun `助手页打开停在最底下；连着的工具折成一行，点开那一行原地往下长`() {
        val assistant = AssistantState.fromJson(buildJsonObject { put("enabled", true); put("config", buildJsonObject { put("name", "小F") }) })
        val entries = (1..30).map { journalEntry("u$it", if (it % 2 == 0) "assistant" else "user", "第 $it 句") } +
            listOf(
                journalEntry("t1", "tool", "天气", tool = "search_memory"),
                journalEntry("t2", "tool", "日程", tool = "search_memory"),
                journalEntry("t3", "tool", "提醒", tool = "say"),
                journalEntry("z1", "assistant", "最后一句"),
            )
        val model = modelWith(
            FridayState(conn = ConnState.Disconnected, assistant = assistant, assistantEntries = entries, cachedAt = System.currentTimeMillis())
        )
        render(model) { AssistantScreen() }

        // 一打开在最底下：最后一句在，最早的不在
        compose.onNodeWithText("最后一句").assertIsDisplayed()
        assertNoText("第 1 句")
        // 三次工具调用折成一行
        compose.onNodeWithText("search_memory ×2、say").assertExists()

        val before = compose.onNodeWithText("search_memory ×2、say").getBoundsInRoot().top
        compose.onNodeWithText("search_memory ×2、say").performClick()
        compose.waitForIdle()
        val after = compose.onNodeWithText("search_memory ×2、say").getBoundsInRoot().top
        assertTrue("工具那一行被顶走了：$before → $after", kotlin.math.abs((after - before).value) <= 2f)
        assertEquals(2, compose.onAllNodesWithText("search_memory").fetchSemanticsNodes().size)

        // 点开其中一条：参数不贴 JSON
        compose.onAllNodesWithText("search_memory")[0].performClick()
        compose.onNodeWithText("query: 天气").assertExists()
    }

    @Test
    fun `助手页日志不够一屏时贴着输入框往上排，按时间先后`() {
        val assistant = AssistantState.fromJson(buildJsonObject { put("enabled", true); put("config", buildJsonObject { put("name", "小F") }) })
        val model = modelWith(
            FridayState(
                conn = connected,
                assistant = assistant,
                assistantEntries = listOf(
                    journalEntry("u1", "user", "第 1 句"),
                    journalEntry("u2", "assistant", "第 2 句"),
                ),
            )
        )
        // 按真的样子摆：主外壳 + 助手页 + 底部它的输入框
        render(model) {
            HomeScaffold(
                tab = HomeTabs.ASSISTANT,
                onTab = {},
                dock = { AssistantComposer(remember { ComposerDraft() }) },
                sessions = { SessionsScreen(onOpen = {}) },
            ) { AssistantScreen() }
        }

        val first = compose.onNodeWithText("第 1 句").getBoundsInRoot()
        val last = compose.onNodeWithText("第 2 句").getBoundsInRoot()
        val input = compose.onNodeWithText("跟助手说点什么…").getBoundsInRoot()
        assertTrue("先说的那条应该在上面", first.bottom <= last.top)
        assertTrue("最后一条 ${last.bottom} 被输入框 ${input.top} 挡住了", last.bottom <= input.top)
        // 最后一条下面只有它那行时间 + 列表底边距，不是空着大半屏
        assertTrue("没贴着输入框：中间空了 ${input.top - last.bottom}", input.top - last.bottom < 80.dp)
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
    fun `新装的 App 没登录时先是选服务器，没有默认服务器，直接摆着添加的表单`() {
        compose.mainClock.autoAdvance = false
        render(AppModel(app)) { com.integemjack.friday.AppRoot(kotlinx.coroutines.flow.MutableStateFlow(null)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("选择服务器").assertExists()
        compose.onNodeWithText("先添加一台 SChat 聊天服务器，再用它登录").assertExists()
        compose.onNodeWithText("服务器地址（如 1.2.3.4:2012）").assertExists()
        compose.onNodeWithText("添加并登录").assertExists()
        // 一台都没有：表单收不起来；也没有偷偷填好的地址
        assertNoText("取消")
        assertEquals(0, compose.onAllNodesWithText("47.100.98.3", substring = true).fetchSemanticsNodes().size)
        // 登录页、主框架都还不在
        assertNoText("登录 Friday")
        assertNoDescription("打开侧边栏")
    }

    @Test
    fun `选了加过的服务器才到登录页，没装 SChat 时给二维码，返回回到选服务器`() {
        // 登录页 0.6 秒后会去建扫码会话（真网络）：测试里时钟不走，只看排版
        compose.mainClock.autoAdvance = false
        val model = AppModel(app)
        model.schat.saveServer("10.0.0.8:2012", "公司", "")
        model.schat.saveServer("http://192.168.1.5:3000", "", "")
        render(model) { com.integemjack.friday.AppRoot(kotlinx.coroutines.flow.MutableStateFlow(null)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("选择服务器").assertExists()
        compose.onNodeWithText("公司").assertExists()
        compose.onNodeWithText("10.0.0.8:2012").assertExists()
        compose.onNodeWithText("192.168.1.5:3000").assertExists()
        compose.onNodeWithText("添加服务器").assertExists()
        assertNoText("登录 Friday")

        compose.onNodeWithText("公司").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("登录 Friday").assertExists()
        compose.onNodeWithText("公司 · 10.0.0.8:2012").assertExists()
        compose.onNodeWithText("打开手机上的 SChat → 扫一扫，扫这个码").assertExists()
        compose.onNodeWithText("其他方式").assertExists()
        assertNoText("通过 SChat 登录")
        // 服务器在前一页选好了：「其他方式」里不再有服务器的输入框
        compose.onNodeWithText("其他方式").performClick()
        // 别走到 0.6 秒（会去建扫码会话）：展开的内容第一帧就在树里了
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("账号（用户名 / 手机号）").assertExists()
        assertNoText("聊天服务器（IP:端口，可带 https://）")
        assertNoText("服务器密码（私有服务器才要填）")
        assertNoDescription("打开侧边栏")

        compose.onNodeWithContentDescription("换服务器").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("选择服务器").assertExists()
        assertNoText("登录 Friday")
    }

    @Test
    fun `服务器列表：地址规整、同地址不重复、改地址原地换、删掉`() {
        // 服务器密码要过 Android Keystore 加密（Robolectric 里没有）：这里都不带密码
        val schat = AppModel(app).schat
        val a = schat.saveServer(" 10.0.0.8:2012/ ", "", "")!!
        assertEquals("https://10.0.0.8:2012", a.address)
        schat.saveServer("http://192.168.1.5:3000", "家里", "")
        // 同一个地址再加：改名字，不多一条
        schat.saveServer("https://10.0.0.8:2012", "公司", "")
        assertEquals(listOf("公司", "家里"), schat.servers.value.map { it.title })
        // 改地址：还在原来的位置
        schat.saveServer("10.0.0.9:2012", "公司", "", replacing = "https://10.0.0.8:2012")
        assertEquals(listOf("https://10.0.0.9:2012", "http://192.168.1.5:3000"), schat.servers.value.map { it.address })
        schat.removeServer("http://192.168.1.5:3000")
        assertEquals(listOf("https://10.0.0.9:2012"), schat.servers.value.map { it.address })
        // 落了盘：重开 App 还在
        assertEquals(listOf("https://10.0.0.9:2012"), com.integemjack.friday.data.SchatStore(app).loadServers().map { it.address })
        assertEquals(null, schat.saveServer("   ", "", ""))
    }

    @Test
    fun `老版本记着的那一台服务器升级后挪进服务器列表`() {
        app.getSharedPreferences("schat", Context.MODE_PRIVATE).edit()
            .putString("server", "https://47.100.98.3:2012")
            .putString("fingerprint", "abc")
            .putString("device_id", "dev-1")
            .commit()
        val servers = com.integemjack.friday.data.SchatStore(app).loadServers()
        assertEquals(1, servers.size)
        assertEquals("https://47.100.98.3:2012", servers[0].address)
        assertEquals("abc", servers[0].fingerprint)
        assertEquals("dev-1", servers[0].deviceId)
        // 上次用的就是它：选服务器那一页标出来
        compose.mainClock.autoAdvance = false
        val model = AppModel(app)
        model.schat.restore() // 没有凭据：只把上次用的服务器读出来
        render(model) { ServerPickerScreen(onPicked = {}) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("上次用的").assertExists()
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

    // MARK: 输入框的焦点和回车

    /** 主外壳 + 共用的输入框（字放在 [text]），页面中间一块「页面内容」用来点框外面 */
    private fun renderComposer(
        text: androidx.compose.runtime.MutableState<String>,
        sent: MutableList<String>,
        voice: VoiceInput? = null,
    ) = render(modelWith(FridayState(conn = connected))) {
        HomeScaffold(
            tab = HomeTabs.ASSISTANT,
            onTab = {},
            dock = {
                Composer(
                    value = text.value,
                    onValueChange = { text.value = it },
                    placeholder = "说点什么",
                    onSend = { sent += text.value; text.value = "" },
                    voice = voice,
                )
            },
            sessions = {},
        ) {
            androidx.compose.material3.Text(
                "页面内容",
                androidx.compose.ui.Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun `输入框按发送、话筒不丢焦点，点框外面才收`() {
        val text = mutableStateOf("")
        val sent = mutableListOf<String>()
        var toggled = 0
        renderComposer(text, sent, voice = VoiceInput { toggled++ })
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.assertIsFocused()

        compose.onNodeWithContentDescription("语音输入").performClick()
        assertEquals(1, toggled)
        field.assertIsFocused()

        field.performTextInput("你好")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(listOf("你好"), sent)
        field.assertIsFocused()

        // 点在框外面：照旧收焦点
        compose.onNodeWithText("页面内容").performClick()
        field.assertIsNotFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `键盘回车是发送，按着 Shift 才换行`() {
        val text = mutableStateOf("")
        val sent = mutableListOf<String>()
        renderComposer(text, sent)
        val field = compose.onNode(hasSetTextAction())
        // 软键盘：多行框、回车键的动作是发送
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Send))
        field.performClick()

        // 空着按发送：没反应
        field.performImeAction()
        assertTrue(sent.isEmpty())

        field.performTextInput("第一句")
        field.performImeAction()
        assertEquals(listOf("第一句"), sent)
        field.assertIsFocused()

        // 实体键盘：Shift+回车换行，回车发送
        field.performTextInput("第一行")
        field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        field.performTextInput("第二行")
        assertEquals("第一行\n第二行", text.value)
        field.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("第一句", "第一行\n第二行"), sent)
        assertEquals("", text.value)
        field.assertIsFocused()
    }

    @Test
    fun `外面写进框里的字（语音）光标放到最后，接着打的接在后面`() {
        val text = mutableStateOf("")
        renderComposer(text, mutableListOf())
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("你好")
        text.value = "你好，今天天气"
        compose.waitForIdle()
        field.performTextInput("怎么样")
        assertEquals("你好，今天天气怎么样", text.value)
    }
}
