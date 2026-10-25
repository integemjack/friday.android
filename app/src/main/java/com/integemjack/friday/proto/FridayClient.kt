package com.integemjack.friday.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** 会话内容状态（订阅后事件合并的目标） */
data class ChatState(
    val id: String,
    val title: String = "",
    val nodes: Map<String, ChatMessage> = emptyMap(),
    val order: List<String> = emptyList(),
    val selectedChildren: Map<String, String> = emptyMap(),
    val versions: Map<String, Long> = emptyMap(),
    val runtime: Runtime? = null,
) {
    val messages: List<ChatMessage>
        get() = conversationPath(order.mapNotNull { nodes[it] }, selectedChildren)
}

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val message: String) : ConnState
    data class Connected(val server: ServerInfo) : ConnState
    data class Failed(val message: String) : ConnState
}

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
) {
    val connected: Boolean get() = conn is ConnState.Connected
}

/**
 * Friday 远程客户端：SSH 桥 + JSON-RPC + 状态合并（REMOTE.md）。
 * 断线自动重连（重新 hello + 重新订阅打开的会话与活动）。
 */
class FridayClient(private val scope: CoroutineScope, private val appVersion: String) {

    val state = MutableStateFlow(FridayState())
    val stateFlow: StateFlow<FridayState> get() = state

    private var transport: SshTransport? = null
    private var rpc: RpcClient? = null
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastConfig: SshConfig? = null
    private var wantConnected = false
    private var reconnectAttempt = 0

    // MARK: 连接

    suspend fun connect(cfg: SshConfig): ServerInfo {
        disconnect()
        wantConnected = true
        lastConfig = cfg
        reconnectAttempt = 0
        return doConnect(cfg)
    }

    private suspend fun doConnect(cfg: SshConfig): ServerInfo {
        state.value = state.value.copy(conn = ConnState.Connecting("连接 ${cfg.user}@${cfg.host}:${cfg.port} …"))
        try {
            val transport = SshTransport(
                config = cfg,
                onLine = { line -> rpc?.handleMessage(line) },
                onBridgeError = { msg -> state.value = state.value.copy(conn = ConnState.Failed("远程服务：$msg")) },
                onClosed = { onTransportClosed() },
            )
            val rpc = RpcClient(
                sendFn = { text -> transport.send(text) },
                closeFn = { transport.close() },
            )
            this.transport = transport
            this.rpc = rpc
            transport.connect()
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
            state.value = state.value.copy(
                conn = ConnState.Connected(server),
                summaries = summaries,
                runner = runner,
            )
            // 连上就订活动（审批提醒）
            rpc.notify("activity.subscribe")
            return server
        } catch (e: Exception) {
            state.value = state.value.copy(conn = ConnState.Failed(e.message ?: "连接失败"))
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

    private fun onTransportClosed() {
        if (!wantConnected) return
        scope.launch {
            cleanupTransport()
            if (state.value.conn is ConnState.Connected || state.value.conn is ConnState.Connecting) {
                state.value = state.value.copy(conn = ConnState.Failed("连接断开，自动重连中…"))
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
            val cfg = lastConfig ?: return@launch
            reconnectAttempt++
            runCatching { doConnect(cfg) }
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
            "attention" -> state.value = state.value.copy(attentionTick = state.value.attentionTick + 1)
            "activity" -> applyActivity(params)
            "activity.assistant" -> { /* 助手开关，暂不展示 */ }
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

    suspend fun submit(text: String) {
        val rpc = rpc ?: throw Exception("未连接")
        val chat = state.value.chat ?: throw Exception("没有打开的会话")
        rpc.request("runner.submit", buildJsonObject {
            put("conversationID", chat.id)
            put("text", text)
        })
    }

    fun stop() {
        val chat = state.value.chat ?: return
        scope.launch { rpc?.request("runner.stop", buildJsonObject { put("conversationID", chat.id) }) }
    }

    fun stopAll() {
        scope.launch { rpc?.request("runner.stopAll") }
    }

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
