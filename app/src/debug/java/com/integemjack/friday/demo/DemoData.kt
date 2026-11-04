package com.integemjack.friday.demo

import com.integemjack.friday.AppModel
import com.integemjack.friday.proto.AskOption
import com.integemjack.friday.proto.AskQuestion
import com.integemjack.friday.proto.AssistantChannel
import com.integemjack.friday.proto.AssistantConfig
import com.integemjack.friday.proto.AssistantEngine
import com.integemjack.friday.proto.AssistantState
import com.integemjack.friday.proto.Board
import com.integemjack.friday.proto.BoardMachine
import com.integemjack.friday.proto.BoardSession
import com.integemjack.friday.proto.BranchInfo
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.ChatState
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.ConversationSummary
import com.integemjack.friday.proto.EngineInfo
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.GenerationStats
import com.integemjack.friday.proto.JournalEntry
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.PendingApproval
import com.integemjack.friday.proto.QueuedInput
import com.integemjack.friday.proto.RunnerState
import com.integemjack.friday.proto.Runtime
import com.integemjack.friday.proto.ServerInfo
import com.integemjack.friday.proto.SessionActivity
import com.integemjack.friday.proto.TaskStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 演示数据：把界面填成「真的连着一台远程 Friday、有会话在跑、有调用等确认」的样子，
 * 用来截图和看排版。只在 debug 包里编译（app/src/debug），release 包里没有这份代码。
 *
 * 场景（`adb shell am start … --es demo <scene>`）：
 *   sessions      会话列表
 *   chat          聊天页（文本 / 思考 / 工具卡 / 排队 / 分支）
 *   chat-running  聊天页（生成中：转圈 + 跑着的工具卡）
 *   activity      活动页（在跑的会话 + 待确认的调用 / 提问）
 *   assistant     助手页（时间线：用户 / 助手 / 工具 / 回执 / 说明 + 状态板 + 渠道）
 *   assistant-off 助手页（还没打开那个样子）
 *   settings      设置页
 */
object DemoData {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching = false

    fun fill(model: AppModel, scene: String) {
        val state = build(scene)
        model.client.state.value = state
        // 演示模式下真实流程（自动拨号失败、订阅失败…）可能把状态改回去：兜回来
        if (watching) return
        watching = true
        scope.launch {
            model.client.stateFlow.collect { current ->
                if (current.conn !is ConnState.Connected) model.client.state.value = state
            }
        }
    }

    // MARK: 状态

    private fun build(scene: String): FridayState = FridayState(
        conn = ConnState.Connected(server),
        summaries = summaries,
        runner = RunnerState(
            generating = listOf("A2"),
            stopping = emptyList(),
            pendingApproval = listOf("A3"),
            pendingQuestion = listOf("A3"),
        ),
        activities = activities,
        assistant = if (scene.startsWith("assistant") || scene == "settings") {
            assistantState(enabled = scene != "assistant-off")
        } else {
            null
        },
        assistantEntries = if (scene == "assistant") assistantEntries else emptyList(),
        assistantBoard = if (scene.startsWith("assistant") || scene == "settings") assistantBoard else null,
        assistantChannels = if (scene.startsWith("assistant") || scene == "settings") assistantChannels else emptyList(),
        assistantHasOlder = scene == "assistant",
        openConversationId = if (scene.startsWith("chat")) CHAT_ID else null,
        // 输入框工具行的模型 / 任务模式选择器
        engines = engines,
        defaultEngineID = "claude-sonnet-4.5",
        defaultTeamMode = "delegatesHeavy",
        chat = when (scene) {
            "chat" -> chat(generating = false)
            "chat-running" -> chat(generating = true)
            else -> null
        },
    )

    private val server = ServerInfo(
        version = "0.3.0",
        platform = "macOS 15.1",
        hostname = "JacksMacBook-Pro",
        user = "ultra",
        dataDir = "/Users/ultra/Library/Application Support/Friday",
        host = "desktop",
        defaultWorkingDirectory = "/Users/ultra/Desktop/works",
        features = listOf("messagePatch", "fileTransfer", "media", "activity"),
    )

    private const val CHAT_ID = "A1"

    private val engines = listOf(
        EngineInfo("claude-sonnet-4.5", "Anthropic", "claude-sonnet-4.5"),
        EngineInfo("gpt-5", "OpenAI", "gpt-5"),
        EngineInfo("glm-4.6", "智谱", "glm-4.6"),
        EngineInfo("qwen3-local", "本机 friday-llm", "qwen3-30b-a3b"),
    )

    private val summaries = listOf(
        ConversationSummary(
            id = CHAT_ID,
            title = "改成和 friday.work 一样用 WebRTC 连",
            updatedAt = "2026-11-03T15:31:00Z",
            pinned = true,
            isEmpty = false,
            hasDraft = false,
            workingDirectory = "/Users/ultra/Desktop/works/friday-android",
            workingDirectoryName = "friday-android",
            permissionMode = "ask",
            engineID = "claude-sonnet-4.5",
            teamMode = "ultra",
        ),
        ConversationSummary(
            id = "A2",
            title = "排查构建缓存为什么一直超时",
            updatedAt = "2026-11-03T14:58:00Z",
            pinned = false,
            isEmpty = false,
            hasDraft = false,
            workingDirectory = "/Users/ultra/Desktop/works/friday-android",
            workingDirectoryName = "friday-android",
            permissionMode = "ask",
        ),
        ConversationSummary(
            id = "A3",
            title = "清理旧会话的媒体缓存",
            updatedAt = "2026-11-03T13:20:00Z",
            pinned = false,
            isEmpty = false,
            hasDraft = true,
            workingDirectory = "/Users/ultra/Library/Application Support/Friday",
            workingDirectoryName = "Friday",
        ),
        ConversationSummary(
            id = "A4",
            title = "给发布流程加个 smoke test",
            updatedAt = "2026-11-02T09:12:00Z",
            pinned = false,
            isEmpty = true,
            hasDraft = false,
            workingDirectory = "/Users/ultra/Desktop/works/friday-android",
            workingDirectoryName = "friday-android",
        ),
    )

    private val activities = listOf(
        SessionActivity(
            id = CHAT_ID,
            title = "改成和 friday.work 一样用 WebRTC 连",
            status = "running",
            taskGoal = "把 SSH 桥换成 WebRTC 工作通道，并逐页截图验收",
            taskSteps = listOf(
                TaskStep("读 friday.work 的 WorkSignaling / WorkFramer", "done"),
                TaskStep("落地 WorkPeer + 信令状态机", "done"),
                TaskStep("模拟器跑起来逐页截图", "doing"),
                TaskStep("提交并说明还差哪一步", "todo"),
            ),
            approvals = emptyList(),
            currentToolTitle = "运行 shell",
            currentToolSummary = "./gradlew :app:assembleDebug",
            updatedAt = "2026-11-03T15:31:00Z",
        ),
        SessionActivity(
            id = "A3",
            title = "清理旧会话的媒体缓存",
            status = "question",
            taskGoal = "把两天没用过的媒体缓存清掉，腾点空间",
            taskSteps = listOf(
                TaskStep("统计缓存占用", "done"),
                TaskStep("确认清理范围", "doing"),
            ),
            approvals = listOf(
                PendingApproval(
                    eventID = "ap-ask-1",
                    tool = "ask_user",
                    title = "清哪些？",
                    summary = "媒体缓存共 1.8 GB，其中 1.2 GB 属于已经删掉的会话。",
                    questions = listOf(
                        AskQuestion(
                            question = "要清掉哪一部分？",
                            options = listOf(
                                AskOption("只清已删会话的（1.2 GB）", "不影响还能点开的记录"),
                                AskOption("全部清掉（1.8 GB）", "下次看同一张图要重新从远程拉"),
                                AskOption("先别动", "只把统计结果留着"),
                            ),
                            multiple = false,
                            recommended = 0,
                        ),
                    ),
                ),
                PendingApproval(
                    eventID = "ap-tool-1",
                    tool = "run_shell",
                    title = "要删掉 312 个缓存文件",
                    summary = "rm -rf ~/Library/Application Support/Friday/media/20261030-*",
                    questions = null,
                ),
            ),
            currentToolTitle = null,
            currentToolSummary = null,
            updatedAt = "2026-11-03T15:26:00Z",
        ),
        SessionActivity(
            id = "A2",
            title = "排查构建缓存为什么一直超时",
            status = "running",
            taskGoal = "找出 gradle 缓存写不进去的原因",
            taskSteps = listOf(
                TaskStep("复现并计时", "done"),
                TaskStep("看 %TEMP% 占用", "done"),
                TaskStep("清临时文件后重跑", "doing"),
            ),
            approvals = emptyList(),
            currentToolTitle = "运行 shell",
            currentToolSummary = "Get-ChildItem \$env:TEMP -Recurse | Measure-Object Length -Sum",
            updatedAt = "2026-11-03T15:20:00Z",
        ),
        SessionActivity(
            id = "A4",
            title = "给发布流程加个 smoke test",
            status = "error",
            taskGoal = "发版前自动打开 App 截一张图，确认没白屏",
            taskSteps = listOf(
                TaskStep("写 workflow 步骤", "done"),
                TaskStep("在模拟器上跑通", "todo"),
            ),
            approvals = listOf(
                PendingApproval(
                    eventID = "ap-tool-2",
                    tool = "run_shell",
                    title = "要重新下载系统镜像（1.4 GB）",
                    summary = "sdkmanager \"system-images;android-34;google_apis;x86_64\"",
                    questions = null,
                ),
            ),
            currentToolTitle = null,
            currentToolSummary = null,
            updatedAt = "2026-11-02T09:40:00Z",
        ),
    )

    // MARK: 聊天页

    private fun chat(generating: Boolean): ChatState {
        val nodes = listOf(
            message(
                id = "m1", role = "user", at = "2026-11-03T15:02:00Z",
                blocks = listOf(text("b1", "改成和 friday.work 一样使用 webrtc 进行连接操作")),
            ),
            message(
                id = "m2", role = "assistant", at = "2026-11-03T15:05:00Z", parent = "m1",
                blocks = listOf(
                    thinking(
                        "b2",
                        "先看 friday.work 那边是怎么连的：schat-ws 发 p2p_request{callType:\"work\"}，" +
                            "被接受后建 RTCPeerConnection，offer/answer/ICE 走 p2p_signaling。" +
                            "数据通道标签是 friday-work，大消息按 128 KB 分片。Android 这边照抄这套，SSH 桥整条删掉。",
                    ),
                    tool(
                        id = "t1", name = "read_file", title = "读 WebRTC 工作通道的实现",
                        status = "succeeded",
                        arguments = """{"path":"friday-desktop-work/src/renderer/src/lib/workChannel.ts"}""",
                        result = "1  // WebRTC 工作通道：ask 方建 PC、出 offer，answer 方应答\n2  // 分片头 19 字节：'FW1' + 会话 + 序号 + 总数\n…",
                    ),
                    text(
                        "b4",
                        "换好了：SChat 登录 → p2p_request 呼机器人 → offer/answer 走 p2p_signaling → " +
                            "DataChannel「friday-work」上跑 JSON-RPC。jsch 和整条 SSH 桥都删了，上层 RpcClient 一行没动。",
                    ),
                ),
                stats = GenerationStats(tokensPerSecond = 42.6, tokenCount = 1834, engineName = "claude-sonnet-4.5"),
            ),
            message(
                id = "m3", role = "user", at = "2026-11-03T15:20:00Z", parent = "m2",
                blocks = listOf(text("b5", "模拟器中运行截图我看看")),
            ),
            message(
                id = "m4a", role = "assistant", at = "2026-11-03T15:22:00Z", parent = "m3",
                blocks = listOf(text("b6", "（另一个分支的回答：我没装模拟器，只能给你编译结果。）")),
            ),
            message(
                id = "m4b", role = "assistant", at = "2026-11-03T15:24:00Z", parent = "m3",
                blocks = listOf(
                    tool(
                        id = "t2", name = "run_shell", title = "起模拟器并装上 APK",
                        status = "succeeded",
                        arguments = """{"command":"emulator -avd li_test && adb install -r app-debug.apk"}""",
                        result = "Performing Streamed Install\nSuccess\npackage:com.integemjack.friday",
                    ),
                    text(
                        "b8",
                        "模拟器（Android 14，API 34）上跑起来了，截图在下面。设置页能看到 SChat 登录状态、" +
                            "机器人列表和工作通道状态；会话页还没有内容，因为机器人那头没通过好友请求。",
                    ),
                ),
                stats = GenerationStats(tokensPerSecond = 38.1, tokenCount = 926, engineName = "claude-sonnet-4.5"),
            ),
            message(
                id = "m5", role = "user", at = "2026-11-03T15:30:00Z", parent = "m4b",
                blocks = listOf(text("b9", "每个功能每个页面都要截图给我看")),
            ),
            message(
                id = "m6", role = "assistant", at = "2026-11-03T15:31:00Z", parent = "m5",
                blocks = if (generating) {
                    listOf(
                        tool(
                            id = "t3", name = "run_shell", title = "逐页截图", status = "running",
                            arguments = """{"command":"adb exec-out screencap -p > shots/01.png"}""",
                            result = "",
                            preview = "正在截第 3 张：活动页",
                        ),
                    )
                } else {
                    listOf(
                        text(
                            "b10",
                            "好，逐个页面来：会话列表、会话长按菜单、聊天页、消息长按菜单、活动页、待确认、设置页，全都截。",
                        )
                    )
                },
            ),
        )

        val runtime = if (generating) {
            Runtime(
                generating = true, stopping = false, queue = emptyList(), queuePaused = false,
                pendingApproval = false, canRetry = false, canSend = true,
                workingDirectory = "/Users/ultra/Desktop/works/friday-android",
                thinkingLevel = "high", permissionMode = "ask",
                thinkingLevels = listOf("off", "low", "medium", "high"),
            )
        } else {
            Runtime(
                generating = false, stopping = false,
                queue = listOf(
                    QueuedInput(
                        id = "q1",
                        text = "顺便把通知栏那个检查更新的提示也挪走",
                        createdAt = "2026-11-03T15:31:30Z",
                    ),
                ),
                queuePaused = false, pendingApproval = false, canRetry = true,
                failedMessageID = "m6", canSend = true,
                workingDirectory = "/Users/ultra/Desktop/works/friday-android",
                thinkingLevel = "medium", permissionMode = "ask",
                thinkingLevels = listOf("off", "low", "medium", "high"),
            )
        }

        return ChatState(
            id = CHAT_ID,
            title = "改成和 friday.work 一样用 WebRTC 连",
            nodes = nodes.associateBy { it.id },
            order = nodes.map { it.id },
            // 分支：m3 这一问有两个回答，选的是第二个（界面上显示 2/2）
            selectedChildren = mapOf("m3" to "m4b"),
            runtime = runtime,
            workingDirectory = "/Users/ultra/Desktop/works/friday-android",
        )
    }

    // MARK: 构造小工具

    private fun message(
        id: String,
        role: String,
        at: String,
        blocks: List<MessageBlock>,
        parent: String? = null,
        stats: GenerationStats? = null,
    ) = ChatMessage(
        id = id, role = role, blocks = blocks, createdAt = at,
        parentID = parent, stats = stats,
    )

    private fun text(id: String, value: String) =
        MessageBlock(id, buildJsonObject { put("text", JsonPrimitive(value)) })

    private fun thinking(id: String, value: String) =
        MessageBlock(id, buildJsonObject { put("thinking", JsonPrimitive(value)) })

    private fun tool(
        id: String,
        name: String,
        title: String,
        status: String,
        arguments: String,
        result: String,
        preview: String? = null,
        agentName: String? = null,
    ) = MessageBlock(
        id,
        buildJsonObject {
            put(
                "tool",
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("name", JsonPrimitive(name))
                    put("title", JsonPrimitive(title))
                    put("status", JsonPrimitive(status))
                    put("arguments", JsonPrimitive(arguments))
                    put("result", JsonPrimitive(result))
                    preview?.let { put("preview", JsonPrimitive(it)) }
                    agentName?.let { put("agentName", JsonPrimitive(it)) }
                },
            )
        },
    )

    /** 没用上但留着：分支信息（会话里从 m3 分叉时界面自己算，这里只是提醒数据结构长这样） */
    @Suppress("unused")
    private val sampleBranch = BranchInfo(index = 1, count = 2, siblings = listOf("m4a", "m4b"), parentKey = "m3")

    // MARK: 助手页

    private fun assistantState(enabled: Boolean) = AssistantState(
        enabled = enabled,
        reacting = enabled,
        reactingEntryID = if (enabled) "e7" else null,
        config = AssistantConfig(
            enabled = enabled,
            name = "Friday 个人助理",
            pushRule = "important",
            brainEngineID = "claude-sonnet-4.5",
            sessionEngineID = null,
            brainThinking = true,
            toolRounds = 24,
            idleMinutes = 45,
            digestSeconds = 30,
            confirmDestructive = true,
            approveFromChannels = false,
            brainApproves = true,
            briefWriter = true,
            raw = null,
        ),
        brainEngineTitle = "Claude Sonnet 4.5",
        engines = listOf(
            AssistantEngine("claude-sonnet-4.5", "Claude Sonnet 4.5", "sonnet-4.5", true),
            AssistantEngine("gpt-5", "GPT-5", "gpt-5", true),
        ),
    )

    private val assistantBoard = Board(
        machines = listOf(
            BoardMachine(
                id = "local",
                name = "JacksMacBook-Pro",
                online = true,
                idle = false,
                entries = listOf(
                    BoardSession(
                        machineID = "local", conversationID = "A2", shortID = "a2",
                        title = "排查构建缓存为什么一直超时", workingDirectory = "/Users/ultra/Desktop/works/friday-android",
                        status = "running", statusText = "在跑：跑 gradle 的 assembleDebug",
                        line = "编译里", progress = "3/5", approvals = 0,
                    ),
                    BoardSession(
                        machineID = "local", conversationID = "A3", shortID = "a3",
                        title = "给设置页加缓存清理", workingDirectory = "/Users/ultra/Desktop/works/friday-android",
                        status = "approval", statusText = "等你确认：要删远程目录",
                        line = "删 build-screens", progress = "", approvals = 1,
                    ),
                    BoardSession(
                        machineID = "local", conversationID = "A4", shortID = "a4",
                        title = "整理这周的改动", workingDirectory = "/Users/ultra/Desktop/works/friday.work",
                        status = "idle", statusText = "闲着的", line = "", progress = "", approvals = 0,
                    ),
                ),
            ),
        ),
    )

    private val assistantChannels = listOf(
        AssistantChannel(
            id = "c1", kind = "schat", title = "SChat", enabled = true, notify = true,
            status = "online", statusText = "在线", botName = "Friday 助手",
            server = "47.100.98.3:2012", hasToken = true,
            owners = listOf("ultra"), groups = listOf("工作群"),
        ),
    )

    private fun entry(
        id: String,
        at: String,
        kind: String,
        text: String,
        source: String? = null,
        interrupted: Boolean = false,
        streaming: Boolean = false,
        data: kotlinx.serialization.json.JsonObject? = null,
    ) = JournalEntry(
        id = id, at = at, kind = kind, source = source, text = text,
        projects = emptyList(), interrupted = interrupted, streaming = streaming, data = data,
    )

    private val assistantEntries = listOf(
        // 昨天
        entry(
            "e1", "2026-11-03T07:12:00Z", "notice",
            "助手打开了，开始盯这台机器上的会话",
        ),
        entry(
            "e2", "2026-11-03T07:20:00Z", "user",
            "看下 android 那个构建为什么老超时，别改代码，先说原因",
            source = "schat",
        ),
        entry(
            "e3", "2026-11-03T07:31:00Z", "assistant",
            "看了：不是代码的问题。那台机器上 %TEMP% 堆了 1.3 GB 的旧临时文件，Robolectric 起不来、" +
                "gradle 的配置缓存也老是被判定过期重跑。清掉之后 12 个用例全绿，构建从 4 分多回到 1 分半。",
        ),
        entry(
            "e4", "2026-11-03T07:33:00Z", "receipt",
            "「排查构建缓存为什么一直超时」跑完了",
            data = buildJsonObject {
                put("machineID", "local")
                put("conversationID", "A2")
                put("level", "info")
                put("status", "done")
                put("event", "finished")
                put("brief", "清了临时目录，测试恢复全绿；结论写在会话最后一条")
            },
        ),
        // 今天
        entry(
            "e5", "2026-11-03T20:05:00Z", "user",
            "安卓端的助手页也做出来，和桌面一样能看到你在忙什么、能直接吩咐你",
        ),
        entry(
            "e6", "2026-11-03T20:07:00Z", "tool",
            "读了 AssistantView.qml",
            data = buildJsonObject {
                put("name", "read_file")
                put("arguments", "{\"path\":\"qt/src/app/qml/assistant/AssistantView.qml\",\"limit\":120}")
                put("result", "（120 行）助手页：工具栏标题是助手名字 + 状态板开关；中间时间线按天分隔、从下往上排…")
            },
        ),
        entry(
            "e7", "2026-11-03T20:12:00Z", "assistant",
            "好。安卓端原来只有会话 / 活动 / 设置三页，助手那套接口一个都没接。现在补上：底部多一个「助手」，" +
                "进来订 assistant.subscribe，日志按天分…",
            streaming = true,
        ),
        entry(
            "e8", "2026-11-03T20:20:00Z", "receipt",
            "「给设置页加缓存清理」有个调用等你确认",
            data = buildJsonObject {
                put("machineID", "local")
                put("conversationID", "A3")
                put("level", "warn")
                put("status", "approval")
                put("event", "approval")
                put("brief", "要删 /Users/ultra/Desktop/works/friday-android/build-screens 整个目录（41 个文件）")
                put("approvalEventID", "EV-20261104-9")
            },
        ),
        entry(
            "e9", "2026-11-03T20:26:00Z", "user",
            "那个删目录的批准了，别清太狠",
            source = "voice",
        ),
        entry(
            "e10", "2026-11-03T20:26:40Z", "receipt",
            "已批准：删 build-screens",
            interrupted = false,
            data = buildJsonObject {
                put("machineID", "local")
                put("conversationID", "A3")
                put("level", "info")
                put("status", "running")
                put("event", "approval.resolved")
                put("brief", "只删截图，不动仓库里的 build 缓存")
            },
        ),
    )
}