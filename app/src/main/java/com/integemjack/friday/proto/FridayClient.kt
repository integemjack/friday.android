package com.integemjack.friday.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.integemjack.friday.schat.SchatService
import java.util.UUID

/** 每块最多 1 MB（REMOTE.md §6：fs.read / file.read / file.upload 同一个上限，同 Qt RemoteProtocol::chunkBytes） */
const val CHUNK_BYTES = 1024 * 1024

/** file.read / fs.read 的一块回执 */
data class FileChunk(val data: ByteArray, val size: Long, val eof: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is FileChunk && size == other.size && eof == other.eof && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode() * 31 + size.hashCode()

    companion object {
        fun fromJson(o: JsonObject): FileChunk {
            val text = o.str("data") ?: ""
            val bytes = if (text.isEmpty()) ByteArray(0) else java.util.Base64.getDecoder().decode(text)
            return FileChunk(bytes, o.long("size") ?: bytes.size.toLong(), o.bool("eof") ?: false)
        }
    }
}

/** 会话内容状态（订阅后事件合并的目标） */
data class ChatState(
    val id: String,
    val title: String = "",
    val nodes: Map<String, ChatMessage> = emptyMap(),
    val order: List<String> = emptyList(),
    val selectedChildren: Map<String, String> = emptyMap(),
    val versions: Map<String, Long> = emptyMap(),
    val runtime: Runtime? = null,
    /** 这段会话的工作目录（工具媒体里的相对路径按它拼） */
    val workingDirectory: String? = null,
) {
    val tree: ConversationTree
        get() = conversationTree(order.mapNotNull { nodes[it] }, selectedChildren)
    val messages: List<ChatMessage>
        get() = tree.path
    val branches: Map<String, BranchInfo>
        get() = tree.branches
}

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val message: String) : ConnState
    data class Connected(val server: ServerInfo) : ConnState
    data class Failed(val message: String) : ConnState
}

/** 连哪一台远程 Friday（SChat 好友里的一个机器人） */
data class RemoteTarget(val botId: Long, val botName: String)

data class FridayState(
    val conn: ConnState = ConnState.Disconnected,
    val summaries: List<ConversationSummary> = emptyList(),
    val runner: RunnerState? = null,
    val activities: List<SessionActivity> = emptyList(),
    /** attention 事件计数（有待确认的调用时 +1，提醒用户看活动页） */
    val attentionTick: Long = 0,
    /** 当前打开的会话 id（null = 没开） */
    val openConversationId: String? = null,
    val chat: ChatState? = null,
    /** 助手（REMOTE.md §6「助手」）：状态、日志、状态板、渠道。没订过都是 null / 空 */
    val assistant: AssistantState? = null,
    val assistantEntries: List<JournalEntry> = emptyList(),
    val assistantBoard: Board? = null,
    val assistantChannels: List<AssistantChannel> = emptyList(),
    val assistantHasOlder: Boolean = false,
    val assistantLoading: Boolean = false,
    val assistantError: String? = null,
    /** 这台机器上的引擎（输入框的模型选择器列这些；关掉的服务的不列） */
    val engines: List<EngineInfo> = emptyList(),
    /** 新对话默认用的引擎（engineID 空的会话跟着它） */
    val defaultEngineID: String? = null,
    /** 那台机器的新对话默认任务模式（settings.get 的 teamMode；没取到是 null，按 solo 显示） */
    val defaultTeamMode: String? = null,
) {
    val connected: Boolean get() = conn is ConnState.Connected

    /** 模型菜单里能选的（关掉的服务的模型不列） */
    val usableEngines: List<EngineInfo> get() = engines.filterNot { it.disabled }
}

/**
 * Friday 远程客户端：SChat 的 WebRTC 工作通道 + JSON-RPC + 状态合并（REMOTE.md）。
 * 断线自动重连（重新拨号 + 重新 hello + 重新订阅打开的会话与活动）。
 */
class FridayClient(
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val schat: SchatService,
) : RemoteFiles {

    val state = MutableStateFlow(FridayState())
    val stateFlow: StateFlow<FridayState> get() = state

    private var transport: WorkTransport? = null
    private var rpc: RpcClient? = null
    private var files: FileTransfer? = null
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastTarget: RemoteTarget? = null
    private var wantConnected = false
    private var reconnectAttempt = 0
    /** 助手页开着（订了 assistant.subscribe）：重连后要重新订回来 */
    private var assistantWanted = false

    // MARK: 连接

    /** 拨号到指定机器人（走 SChat 信令 + WebRTC 工作通道） */
    suspend fun connect(target: RemoteTarget): ServerInfo {
        disconnect()
        wantConnected = true
        lastTarget = target
        reconnectAttempt = 0
        return doConnect(target)
    }

    private suspend fun doConnect(target: RemoteTarget): ServerInfo {
        val schatState = schat.state.value
        if (!schatState.online) {
            val message = when (schatState.phase) {
                SchatService.Phase.NotLoggedIn -> "还没登录 SChat：先在设置里登录，再选一台远程 Friday"
                else -> "SChat 还没连上：${schatState.error ?: schatState.status.ifEmpty { "等它连上再来" }}"
            }
            state.value = state.value.copy(conn = ConnState.Failed(message))
            throw IllegalStateException(message)
        }
        state.value = state.value.copy(conn = ConnState.Connecting("通过 SChat 呼叫 ${target.botName} …"))
        try {
            val transport = WorkTransport(
                schat = schat,
                botId = target.botId,
                scope = scope,
                listener = object : WorkTransport.Listener {
                    override fun onOpen() {
                        // DataChannel 开了：hello 已经排队，这里不用做事
                    }

                    override fun onMessage(text: String) {
                        rpc?.handleMessage(text)
                    }

                    override fun onClosed(reason: String, permanent: Boolean) {
                        // 被拒 / 被顶掉 / 协议错：重拨也没用，别刷屏
                        if (permanent) wantConnected = false
                        onTransportClosed(reason)
                    }
                },
            )
            val rpc = RpcClient(
                sendFn = { text -> transport.send(text) },
                closeFn = { transport.close() },
            )
            this.transport = transport
            this.rpc = rpc
            this.files = FileTransfer(rpc)
            transport.open()
            startEventLoop(rpc)

            // hello（REMOTE.md §4）
            val helloParams = buildJsonObject {
                put("protocol", PROTOCOL_VERSION)
                put("client", buildJsonObject {
                    put("name", "Friday")
                    put("version", appVersion)
                    put("platform", "android")
                    put("kind", "mobile")
                })
                put("features", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("messagePatch"))))
            }
            val result = rpc.request("hello", helloParams)
            val obj = result.jsonObject
            val server = ServerInfo.fromJson(obj["server"]?.jsonObject ?: JsonObject(emptyMap()))
            val snapshot = obj["state"]?.jsonObject
            val summaries = (snapshot?.get("summaries") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { s -> ConversationSummary.fromJson(s) }
            }.orEmpty().sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
            val runner = snapshot?.get("runner")?.let { (it as? JsonObject)?.let { r -> RunnerState.fromJson(r) } }
            val engines = (snapshot?.get("engines") as? JsonObject)?.let { EnginesState.fromJson(it) }
            state.value = state.value.copy(
                conn = ConnState.Connected(server),
                summaries = summaries,
                runner = runner,
                engines = engines?.engines ?: emptyList(),
                defaultEngineID = engines?.defaultEngineID,
            )
            // 新对话默认的任务模式只在 settings 里（hello 不带）：不挡着连接，后台取一下
            scope.launch { refreshSettings() }
            // 连上就订活动（审批提醒）
            rpc.notify("activity.subscribe")
            if (assistantWanted) runCatching { subscribeAssistant() }
            return server
        } catch (e: Exception) {
            // 传输层已经报过更具体的原因（比如 not_friends）就别用泛泛的「连接已关闭」盖掉它
            val known = (state.value.conn as? ConnState.Failed)?.message
            val message = known?.takeIf { it.isNotBlank() && it != "连接已关闭" } ?: e.message ?: "连接失败"
            state.value = state.value.copy(conn = ConnState.Failed(message))
            cleanupTransport()
            throw e
        }
    }

    private fun startEventLoop(rpc: RpcClient) {
        eventJob?.cancel()
        eventJob = scope.launch {
            for ((method, params) in rpc.events) {
                runCatching { handleEvent(method, params) }
            }
        }
    }

    private fun onTransportClosed(reason: String? = null) {
        // 先把真实原因交给 RpcClient：在途的 hello 会带着这句失败，
        // 否则 doConnect 的 catch 只会看到一句「连接已关闭」，把被拒的理由盖掉。
        rpc?.close(reason ?: "连接已断开")
        scope.launch {
            cleanupTransport()
            if (state.value.conn is ConnState.Connected || state.value.conn is ConnState.Connecting) {
                state.value = state.value.copy(conn = ConnState.Failed(reason ?: "连接断开，自动重连中…"))
            } else if (reason != null) {
                state.value = state.value.copy(conn = ConnState.Failed(reason))
            }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectJob?.cancel()
        val backoffMs = (10_000L * (1 + reconnectAttempt.coerceAtMost(10))).coerceAtMost(120_000L)
        reconnectJob = scope.launch {
            delay(backoffMs)
            val target = lastTarget ?: return@launch
            reconnectAttempt++
            runCatching { doConnect(target) }
                .onSuccess {
                    reconnectAttempt = 0
                    // 重连后重新订阅打开的会话
                    state.value.openConversationId?.let { id ->
                        runCatching { subscribeConversation(id) }
                    }
                }
                .onFailure { scheduleReconnect() }
        }
    }

    fun disconnect() {
        wantConnected = false
        assistantWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        eventJob?.cancel()
        eventJob = null
        cleanupTransport()
        state.value = FridayState()
    }

    private fun cleanupTransport() {
        rpc?.close()
        rpc = null
        files = null
        transport?.close()
        transport = null
    }

    // MARK: 事件

    private suspend fun handleEvent(method: String, params: JsonObject) {
        when (method) {
            "summaries" -> {
                val list = (params["summaries"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                    (it as? JsonObject)?.let { s -> ConversationSummary.fromJson(s) }
                }.orEmpty().sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
                state.value = state.value.copy(summaries = list)
            }
            "runner" -> state.value = state.value.copy(runner = RunnerState.fromJson(params))
            "engines" -> {
                val engines = EnginesState.fromJson((params["engines"] as? JsonObject) ?: params)
                state.value = state.value.copy(engines = engines.engines, defaultEngineID = engines.defaultEngineID)
            }
            "attention" -> state.value = state.value.copy(attentionTick = state.value.attentionTick + 1)
            "activity" -> applyActivity(params)
            "activity.assistant" -> {
                // 这台开着自己的助手：会话归它汇报（ASSISTANT.md §4.3）。助手页顶栏那行提示用
                val on = params.bool("enabled") ?: false
                state.value = state.value.copy(
                    assistant = state.value.assistant?.copy(enabled = on) ?: state.value.assistant,
                )
            }
            "assistant.state" -> {
                val s = (params["state"] as? JsonObject) ?: params
                state.value = state.value.copy(assistant = AssistantState.fromJson(s))
            }
            "assistant.entry" -> applyAssistantEntry((params["entry"] as? JsonObject)?.let { JournalEntry.fromJson(it) })
            "assistant.board" -> {
                val board = (params["board"] as? JsonObject)?.let { Board.fromJson(it) }
                if (board != null) state.value = state.value.copy(assistantBoard = board)
            }
            "assistant.channels" -> state.value = state.value.copy(
                assistantChannels = (params["channels"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                    (it as? JsonObject)?.let { c -> AssistantChannel.fromJson(c) }
                } ?: emptyList(),
            )
            "assistant.reset" -> state.value = state.value.copy(
                assistantEntries = emptyList(), assistantHasOlder = false,
            )
            "conversation" -> applyConversation(params)
            "message" -> applyMessage(params, patch = false)
            "message.patch" -> applyMessage(params, patch = true)
            "runtime" -> {
                val id = params.str("conversationID") ?: return
                val rt = (params["runtime"] as? JsonObject)?.let { Runtime.fromJson(it) } ?: return
                updateChat(id) { it.copy(runtime = rt) }
            }
            "bridgeError" -> state.value = state.value.copy(conn = ConnState.Failed(params.str("message") ?: "桥错误"))
            else -> {}
        }
    }

    private fun applyActivity(params: JsonObject) {
        val s = params.str("conversationID")
        val kind = params.str("kind")
        // ActivityEvent：本地按 conversationID 合并已有条目；approvals 全量给出
        val id = s ?: return
        val current = state.value.activities
        val approvals = params["approvals"] as? kotlinx.serialization.json.JsonArray
        if (approvals != null || kind == "approval") {
            val list = approvals?.mapNotNull { (it as? JsonObject)?.let { a -> PendingApproval.fromJson(a) } }
            if (list != null) {
                val updated = current.filter { it.id != id }.toMutableList()
                if (list.isNotEmpty() || kind == "started") {
                    // 保留其他字段，仅当条目存在时更新 approvals
                    val existing = current.firstOrNull { it.id == id }
                    if (existing != null) updated.add(existing.copy(approvals = list))
                }
                state.value = state.value.copy(activities = updated.sortedByDescending { it.updatedAt })
                return
            }
        }
        // 简单事件：标题/工具/状态就刷新（活动详情重新订阅太重，首版用 activity.subscribe 的快照 + 就地更新摘要）
        val existing = current.firstOrNull { it.id == id } ?: return
        val title = params.str("title") ?: existing.title
        val toolTitle = (params["tool"] as? JsonObject)?.str("title") ?: existing.currentToolTitle
        val toolSummary = (params["tool"] as? JsonObject)?.str("summary") ?: existing.currentToolSummary
        val goal = (params["steps"] as? JsonObject)?.str("goal")
        val status = when (kind) {
            "started" -> "running"
            "finished" -> "idle"
            "quota" -> "quota"
            "stalled" -> existing.status
            else -> existing.status
        }
        val steps = (params["steps"] as? JsonObject)?.get("steps") as? kotlinx.serialization.json.JsonArray
        val newSteps = steps?.mapNotNull { st ->
            (st as? JsonObject)?.let { TaskStep(it.str("text") ?: "", it.str("status") ?: "todo") }
        }
        state.value = state.value.copy(
            activities = current.map {
                if (it.id == id) it.copy(
                    title = title,
                    status = status,
                    currentToolTitle = toolTitle,
                    currentToolSummary = toolSummary,
                    taskGoal = goal ?: it.taskGoal,
                    taskSteps = newSteps ?: it.taskSteps,
                ) else it
            }
        )
    }

    private suspend fun refreshActivities() {
        val rpc = rpc ?: return
        runCatching {
            val result = rpc.request("activity.subscribe")
            val obj = result.jsonObject
            val sessions = (obj["sessions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { s -> SessionActivity.fromJson(s) }
            }.orEmpty().sortedByDescending { it.updatedAt }
            state.value = state.value.copy(activities = sessions)
        }
    }

    private fun applyConversation(params: JsonObject) {
        val convo = (params["conversation"] as? JsonObject)?.let { Conversation.fromJson(it) } ?: return
        val partial = params["partial"]?.jsonPrimitive?.booleanOrNull ?: false
        val current = state.value.chat
        if (current == null || current.id != convo.id) return
        if (!partial) {
            state.value = state.value.copy(chat = mergeVersions(
                ChatState(
                    id = convo.id,
                    title = convo.title,
                    nodes = convo.nodes.associateBy { it.id },
                    order = convo.nodes.map { it.id },
                    selectedChildren = convo.selectedChildren,
                    runtime = current.runtime,
                    workingDirectory = convo.workingDirectory ?: current.workingDirectory,
                ), params
            ))
        } else {
            // partial：只带变化的消息，nodeIDs 是完整顺序
            val nodeIds = params.strList("nodeIDs")
            val nodes = current.nodes.toMutableMap()
            convo.nodes.forEach { nodes[it.id] = it }
            state.value = state.value.copy(chat = mergeVersions(
                current.copy(
                    title = convo.title,
                    nodes = nodes,
                    order = nodeIds.ifEmpty { current.order },
                    selectedChildren = convo.selectedChildren,
                    workingDirectory = convo.workingDirectory ?: current.workingDirectory,
                ), params
            ))
        }
    }

    private fun mergeVersions(chat: ChatState, params: JsonObject): ChatState {
        val versions = (params["versions"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { k to it }
        }?.toMap() ?: return chat
        return chat.copy(versions = chat.versions + versions)
    }

    private suspend fun applyMessage(params: JsonObject, patch: Boolean) {
        val convoId = params.str("conversationID") ?: return
        val chat = state.value.chat ?: return
        if (chat.id != convoId) return
        if (patch) {
            val messageID = params.str("messageID") ?: return
            val base = params.long("base")
            val version = params.long("version") ?: return
            val local = chat.versions[messageID]
            val existing = chat.nodes[messageID]
            if (existing == null || (base != null && local != null && local != base)) {
                fetchWholeMessage(convoId, messageID)
                return
            }
            val fields = params["fields"] as? JsonObject
            val blocksPatch = (params["blocks"] as? JsonObject)?.let { BlocksPatch.fromJson(it) }
            val updated = applyMessagePatch(existing, fields, blocksPatch)
            if (updated == null) {
                fetchWholeMessage(convoId, messageID)
                return
            }
            updateChat(convoId) {
                it.copy(
                    nodes = it.nodes + (messageID to updated),
                    versions = it.versions + (messageID to version),
                )
            }
        } else {
            val message = (params["message"] as? JsonObject)?.let { ChatMessage.fromJson(it) } ?: return
            val version = params.long("version")
            updateChat(convoId) {
                val versions = if (version != null) it.versions + (message.id to version) else it.versions
                // 新消息先出现在 conversation 事件里，兜底也挂进 order
                val order = if (it.order.contains(message.id)) it.order else it.order + message.id
                it.copy(nodes = it.nodes + (message.id to message), order = order, versions = versions)
            }
        }
    }

    private suspend fun fetchWholeMessage(convoId: String, messageID: String) {
        val rpc = rpc ?: return
        runCatching {
            val result = rpc.request("message.get", buildJsonObject {
                put("conversationID", convoId)
                put("messageID", messageID)
            })
            val obj = result.jsonObject
            val message = (obj["message"] as? JsonObject)?.let { ChatMessage.fromJson(it) } ?: return
            val version = obj.long("version")
            updateChat(convoId) {
                it.copy(
                    nodes = it.nodes + (message.id to message),
                    versions = if (version != null) it.versions + (message.id to version) else it.versions,
                )
            }
        }
    }

    private fun updateChat(id: String, transform: (ChatState) -> ChatState) {
        val chat = state.value.chat ?: return
        if (chat.id != id) return
        state.value = state.value.copy(chat = transform(chat))
    }

    // MARK: 会话操作

    /** 订阅一段会话（REMOTE.md §6） */
    suspend fun subscribeConversation(id: String) {
        val rpc = rpc ?: throw Exception("未连接")
        val current = state.value.chat
        if (current != null && current.id != id) rpc.notify("conversation.unsubscribe", buildJsonObject { put("id", current.id) })
        val result = rpc.request("conversation.subscribe", buildJsonObject { put("id", id) })
        val obj = result.jsonObject
        val convo = (obj["conversation"] as? JsonObject)?.let { Conversation.fromJson(it) }
            ?: throw Exception("订阅失败：没有会话数据")
        val runtime = (obj["runtime"] as? JsonObject)?.let { Runtime.fromJson(it) }
        var chat = ChatState(
            id = convo.id,
            title = convo.title,
            nodes = convo.nodes.associateBy { it.id },
            order = convo.nodes.map { it.id },
            selectedChildren = convo.selectedChildren,
            runtime = runtime,
            workingDirectory = convo.workingDirectory ?: runtime?.workingDirectory,
        )
        chat = mergeVersions(chat, obj)
        state.value = state.value.copy(openConversationId = id, chat = chat)
    }

    fun closeConversation() {
        val chat = state.value.chat ?: return
        rpc?.notify("conversation.unsubscribe", buildJsonObject { put("id", chat.id) })
        state.value = state.value.copy(openConversationId = null, chat = null)
    }

    /** 新建会话（客户端编 UUID，REMOTE.md §6 conversation.create）并订阅 */
    suspend fun newConversation(): String {
        val rpc = rpc ?: throw Exception("未连接")
        val id = UUID.randomUUID().toString().uppercase()
        rpc.request("conversation.create", buildJsonObject { put("id", id) })
        subscribeConversation(id)
        return id
    }

    suspend fun submit(text: String, attachments: List<String> = emptyList()) {
        val rpc = rpc ?: throw Exception("未连接")
        val chat = state.value.chat ?: throw Exception("没有打开的会话")
        rpc.request("runner.submit", buildJsonObject {
            put("conversationID", chat.id)
            put("text", text)
            put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        })
    }

    fun stop() {
        val chat = state.value.chat ?: return
        scope.launch { rpc?.request("runner.stop", buildJsonObject { put("conversationID", chat.id) }) }
    }

    fun stopAll() {
        scope.launch { rpc?.request("runner.stopAll") }
    }

    /** 重试上一轮（runtime.canRetry 为真时可用） */
    fun retry() {
        val chat = state.value.chat ?: return
        scope.launch { runCatching { rpc?.request("runner.retry", buildJsonObject { put("conversationID", chat.id) }) } }
    }

    /** 上一轮被停后暂停的排队消息：点「发送」接着发 */
    fun resumeQueue() {
        val chat = state.value.chat ?: return
        scope.launch { runCatching { rpc?.request("runner.resumeQueue", buildJsonObject { put("conversationID", chat.id) }) } }
    }

    /** 从队列里删掉一条 */
    fun removeQueued(itemID: String) {
        val chat = state.value.chat ?: return
        scope.launch {
            runCatching {
                rpc?.request("runner.removeQueued", buildJsonObject {
                    put("conversationID", chat.id)
                    put("itemID", itemID)
                })
            }
        }
    }

    /** 切到另一条分支（parentKey 空串 = 根消息那一层） */
    fun selectBranch(child: String, parentKey: String) {
        val chat = state.value.chat ?: return
        scope.launch {
            runCatching {
                rpc?.request("runner.selectBranch", buildJsonObject {
                    put("conversationID", chat.id)
                    put("child", child)
                    put("parentKey", parentKey)
                })
            }
        }
        // 乐观更新：服务端的 conversation 快照随后会再对齐
        updateChat(chat.id) {
            it.copy(selectedChildren = it.selectedChildren + (parentKey to child))
        }
    }

    // MARK: 助手（REMOTE.md §6「助手」）

    /** 进助手页时订阅：拿状态 + 最近 count 条日志 + 状态板 + 渠道；之后收 assistant.* 事件 */
    suspend fun subscribeAssistant(count: Int = 100) {
        val rpc = rpc ?: throw Exception("未连接")
        assistantWanted = true
        state.value = state.value.copy(assistantLoading = true, assistantError = null)
        try {
            val obj = rpc.request("assistant.subscribe", buildJsonObject { put("count", count) }).jsonObject
            state.value = state.value.copy(
                assistant = (obj["state"] as? JsonObject)?.let { AssistantState.fromJson(it) },
                assistantBoard = (obj["board"] as? JsonObject)?.let { Board.fromJson(it) },
                assistantEntries = journalEntries(obj["entries"]),
                assistantChannels = (obj["channels"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                    (it as? JsonObject)?.let { c -> AssistantChannel.fromJson(c) }
                } ?: emptyList(),
                assistantHasOlder = true,
                assistantLoading = false,
            )
        } catch (e: Exception) {
            state.value = state.value.copy(
                assistantLoading = false,
                assistantError = if (e is RpcException && e.code == RpcErrors.MethodNotFound)
                    "这台上的 Friday 还没有助手（2.3 之前的版本没有这套接口）"
                else e.message ?: "订阅助手失败",
            )
        }
    }

    /** 离开助手页：不再收它的事件（数据留着，回来再订） */
    fun leaveAssistant() {
        if (!assistantWanted) return
        assistantWanted = false
        rpc?.notify("assistant.unsubscribe")
    }

    /** 往上翻更早的日志（assistant.history：before = 最早那条的时间） */
    suspend fun loadOlderAssistant() {
        val rpc = rpc ?: return
        val state0 = state.value
        if (state0.assistantLoading) return
        val oldest = state0.assistantEntries.minByOrNull { it.at }?.at ?: return
        state.value = state0.copy(assistantLoading = true)
        runCatching {
            val obj = rpc.request("assistant.history", buildJsonObject {
                put("before", oldest)
                put("limit", 100)
            }).jsonObject
            val older = journalEntries(obj["entries"])
            state.value = state.value.copy(
                assistantEntries = (older + state.value.assistantEntries).distinctBy { it.id }
                    .sortedBy { it.at },
                assistantHasOlder = obj.bool("hasOlder") ?: false,
                assistantLoading = false,
            )
        }.onFailure { state.value = state.value.copy(assistantLoading = false) }
    }

    /**
     * 对助手说话（assistant.say）。`attachments` 是这台上的文件路径：先 file.upload 传过去，
     * 主机拷进助手目录、记在这条的 entry.data.attachments 里（REMOTE.md §6「助手」）
     */
    suspend fun sayToAssistant(text: String, attachments: List<String> = emptyList()) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("assistant.say", buildJsonObject {
            put("text", text)
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        })
    }

    /**
     * 助手页输入框选的模型（AssistantConfig.sessionEngineID，ASSISTANT.md §16.10）：助手下次开会话、
     * 往会话里插话用它；空 = 没选过（跟新对话一样）。不动新对话的默认
     */
    suspend fun setAssistantSessionEngine(engineID: String?) {
        updateAssistantLocally { it.copy(sessionEngineID = engineID) }
        updateAssistantConfig("sessionEngineID", JsonPrimitive(engineID.orEmpty()))
    }

    /** 助手大脑开不开深度思考（AssistantConfig.brainThinking，布尔） */
    suspend fun setAssistantBrainThinking(on: Boolean) {
        updateAssistantLocally { it.copy(brainThinking = on) }
        updateAssistantConfig("brainThinking", JsonPrimitive(on))
    }

    /** 先在本地改，输入框上的选择器立刻反映；服务端推回来的 assistant.state 会盖回来 */
    private fun updateAssistantLocally(transform: (AssistantConfig) -> AssistantConfig) {
        val current = state.value.assistant ?: return
        state.value = state.value.copy(assistant = current.copy(config = transform(current.config)))
    }

    /** 打断正在说的（assistant.stop） */
    fun stopAssistant() {
        val rpc = rpc ?: return
        scope.launch { runCatching { rpc.request("assistant.stop") } }
    }

    /** 开 / 关助手（assistant.settings.update）：先本地改，失败了退回 */
    suspend fun setAssistantEnabled(enabled: Boolean) {
        val rpc = rpc ?: throw Exception("未连接")
        val before = state.value.assistant
        state.value = state.value.copy(assistant = before?.copy(enabled = enabled, config = before.config.copy(enabled = enabled)))
        try {
            rpc.request("assistant.settings.update", buildJsonObject { put("enabled", enabled) })
        } catch (e: Exception) {
            state.value = state.value.copy(assistant = before, assistantError = e.message)
            throw e
        }
    }

    /** 改助手设置里的一项（assistant.settings.update）：名字、推什么、空闲多久提醒…… */
    suspend fun updateAssistantConfig(key: String, value: kotlinx.serialization.json.JsonElement) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("assistant.settings.update", buildJsonObject { put(key, value) })
    }

    /** 回执卡上的「批准 / 拒绝」：转给那台机器（assistant.resolveApproval） */
    suspend fun resolveAssistantApproval(machineID: String, eventID: String, approved: Boolean) {
        val rpc = rpc ?: return
        runCatching {
            rpc.request("assistant.resolveApproval", buildJsonObject {
                put("machineID", machineID)
                put("eventID", eventID)
                put("approved", approved)
            })
        }
        refreshActivities()
    }

    private fun journalEntries(element: kotlinx.serialization.json.JsonElement?): List<JournalEntry> =
        (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? JsonObject)?.let { e -> JournalEntry.fromJson(e) }
        }?.sortedBy { it.at } ?: emptyList()

    /** 日志加了一条 / 改了同一条（流式的回复按 id 合并，80 毫秒一批） */
    private fun applyAssistantEntry(entry: JournalEntry?) {
        if (entry == null || entry.id.isEmpty()) return
        val list = state.value.assistantEntries.toMutableList()
        val index = list.indexOfFirst { it.id == entry.id }
        if (index >= 0) list[index] = entry else list.add(entry)
        state.value = state.value.copy(assistantEntries = list.sortedBy { it.at })
    }

    // MARK: 输入框底行：模型 / 任务模式 / 思考模式（REMOTE.md §6 runner.setEngine / setTeamMode / setThinking）

    /** 取那台的设置（只用到新对话默认的任务模式）；旧服务端没有就算了 */
    suspend fun refreshSettings() {
        val rpc = rpc ?: return
        runCatching { rpc.request("settings.get").jsonObject }.onSuccess { settings ->
            settings.str("teamMode")?.let { state.value = state.value.copy(defaultTeamMode = it) }
        }
    }

    /**
     * 换这段对话的模型（runner.setEngine；工作中换的下一轮生效），同时记成新对话的默认
     * （settings.update defaultEngineID，同桌面端 / Qt v2.9 起）。先乐观改本地，summaries 事件会盖回来
     */
    suspend fun setEngine(conversationID: String, engineID: String) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("runner.setEngine", buildJsonObject {
            put("conversationID", conversationID)
            put("engineID", engineID)
        })
        updateSummary(conversationID) { it.copy(engineID = engineID) }
        runCatching { rpc.request("settings.update", buildJsonObject { put("defaultEngineID", engineID) }) }
            .onSuccess { state.value = state.value.copy(defaultEngineID = engineID) }
    }

    /** 换这段对话的思考档位（runner.setThinking {conversationID, level}） */
    suspend fun setThinking(conversationID: String, level: String) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("runner.setThinking", buildJsonObject {
            put("conversationID", conversationID)
            put("level", level)
        })
        updateSummary(conversationID) { it.copy(thinking = level) }
        updateChat(conversationID) { chat -> chat.copy(runtime = chat.runtime?.copy(thinkingLevel = level)) }
    }

    /**
     * 换这段对话的任务模式（runner.setTeamMode，2.9 起有 teamModePerConversation；服务端同时记成新对话默认）。
     * 没这个功能的旧服务端只能改那台全局的（settings.update teamMode）
     */
    suspend fun setTeamMode(conversationID: String, mode: String) {
        val rpc = rpc ?: throw Exception("未连接")
        val server = (state.value.conn as? ConnState.Connected)?.server
        if (server?.features?.contains("teamModePerConversation") == true) {
            rpc.request("runner.setTeamMode", buildJsonObject {
                put("conversationID", conversationID)
                put("mode", mode)
            })
        } else {
            rpc.request("settings.update", buildJsonObject { put("teamMode", mode) })
        }
        updateSummary(conversationID) { it.copy(teamMode = mode) }
        state.value = state.value.copy(defaultTeamMode = mode)
    }

    /** 新对话默认的任务模式（settings.update teamMode）：助手开的会话也跟着它 */
    suspend fun setDefaultTeamMode(mode: String) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("settings.update", buildJsonObject { put("teamMode", mode) })
        state.value = state.value.copy(defaultTeamMode = mode)
    }

    private fun updateSummary(id: String, transform: (ConversationSummary) -> ConversationSummary) {
        val list = state.value.summaries
        if (list.none { it.id == id }) return
        state.value = state.value.copy(summaries = list.map { if (it.id == id) transform(it) else it })
    }

    // MARK: 会话管理

    suspend fun renameConversation(id: String, title: String) {
        rpc?.request("conversation.rename", buildJsonObject {
            put("id", id)
            put("title", title)
        })
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        rpc?.request("conversation.setPinned", buildJsonObject {
            put("id", id)
            put("pinned", pinned)
        })
    }

    /** 删会话（服务端会停掉它的生成和子 Agent、删它的媒体） */
    suspend fun removeConversation(id: String) {
        rpc?.request("conversation.remove", buildJsonObject { put("id", id) })
        if (state.value.openConversationId == id) {
            state.value = state.value.copy(openConversationId = null, chat = null)
        }
    }

    // MARK: 文件（REMOTE.md §6：file.read / fs.read / file.upload，具体分块在 FileTransfer）

    private fun files(): FileTransfer = files ?: throw Exception("未连接")

    /** 读消息附件的一段（media = 附件的 relativePath） */
    override suspend fun readMediaChunk(relativePath: String, offset: Long, length: Int): FileChunk =
        files().readMediaChunk(relativePath, offset, length)

    /** 读远程机器上任意文件的一段（工具里的本地媒体路径用这个） */
    override suspend fun readFileChunk(path: String, offset: Long, length: Int): FileChunk =
        files().readFileChunk(path, offset, length)

    /** 上传一个本地文件，返回服务端上的路径（runner.submit 的 attachments 用它） */
    suspend fun uploadFile(file: java.io.File, onProgress: (Float) -> Unit = {}): String =
        files().uploadFile(file, onProgress)

    /** 批准 / 拒绝（或回答 ask_user） */
    suspend fun resolveApproval(eventID: String, approved: Boolean, answers: List<String>? = null) {
        val rpc = rpc ?: return
        val params = buildJsonObject {
            put("eventID", eventID)
            put("approved", approved)
            if (answers != null) put("answer", buildJsonObject {
                put("answers", kotlinx.serialization.json.JsonArray(answers.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        }
        runCatching { rpc.request("runner.resolveApproval", params) }
        refreshActivities()
    }

    /** 活动页进入时刷新快照 */
    suspend fun refresh() {
        refreshActivities()
    }
}
