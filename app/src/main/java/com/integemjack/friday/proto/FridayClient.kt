package com.integemjack.friday.proto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
    /** 这台的子 Agent（TeamState.runs）：会话页的「后台」标签和面板用 */
    val team: List<TeamRun> = emptyList(),
    /** 这台的内置终端（run_in_terminal 开的；TerminalsState）：后台任务里列这段会话的 */
    val terminals: List<TerminalInfo> = emptyList(),
    /** 这台的定时任务（/loop；LoopsState，连上时 loops.list 取一次、之后收 loops 事件） */
    val loops: List<LoopTask> = emptyList(),
    /** attention 事件计数（有待确认的调用时 +1，后台时据此发系统通知） */
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
    /**
     * 刚对助手说了话（assistant.say 回来了）、它还没开始反应：助手页先摆上「思考中…」，
     * 不等 assistant.state 的 reacting。它开始反应 / 反应完了、回复或说明到了、等了一阵还没动静，就收
     */
    val assistantAwaiting: Boolean = false,
    /** 这台机器上的引擎（输入框的模型选择器列这些；关掉的服务的不列） */
    val engines: List<EngineInfo> = emptyList(),
    /** 新对话默认用的引擎（engineID 空的会话跟着它） */
    val defaultEngineID: String? = null,
    /** 那台机器的新对话默认任务模式（settings.get 的 teamMode；没取到是 null，按 solo 显示） */
    val defaultTeamMode: String? = null,
    /** 各家在线服务的余额 / 用量（键 = usageKey(接口地址, 密钥名)；模型菜单里每个模型下面那一句） */
    val usage: Map<String, UsageInfo> = emptyMap(),
    /** 这台最近选过的工作目录；null = 旧服务端没有 directories（菜单里不列最近，选了也不记） */
    val directories: List<RecentDirectory>? = null,
    /** 上次连上的那台（没连上时界面上写「离线 · 它的名字」） */
    val lastServer: ServerInfo? = null,
    /**
     * 现在摆着的不是实时的：从本地快照读出来的 / 连接断了之后留着的，是这个时刻的内容（毫秒）。
     * null = 实时的（连着、hello 之后）
     */
    val cachedAt: Long? = null,
) {
    val connected: Boolean get() = conn is ConnState.Connected

    /** 模型菜单里能选的（关掉的服务的模型不列） */
    val usableEngines: List<EngineInfo> get() = engines.filterNot { it.disabled }

    /** 连着的那台有没有这个功能（server.features） */
    fun hasFeature(name: String): Boolean = (conn as? ConnState.Connected)?.server?.features?.contains(name) == true
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

    /** 本地快照（断网 / 重开 App 时先显示上次的）；AppModel 接上，测试里可以不给 */
    interface Cache {
        fun load(botId: Long): FridayState?
        fun loadChat(botId: Long, conversationId: String): ChatState?
        fun removeChat(botId: Long, conversationId: String)
    }

    var cache: Cache? = null

    /** state 里的数据是哪台机器的（换机器时先换成那台的快照，别把上一台的会话摆出来） */
    @Volatile
    var dataBotId: Long = 0
        private set

    /** 想连着（连过、没被用户断开 / 没被永久拒绝）：网络回来时据此马上重连 */
    val wantsConnection: Boolean get() = wantConnected
    private var reconnectAttempt = 0
    /** 助手页开着（订了 assistant.subscribe）：重连后要重新订回来 */
    private var assistantWanted = false

    // MARK: 连接

    /** 拨号到指定机器人（走 SChat 信令 + WebRTC 工作通道） */
    suspend fun connect(target: RemoteTarget): ServerInfo {
        teardownConnection()
        assistantWanted = false
        // 同一台：留着现在的数据（快照 / 断线前的），连上后被实时的盖掉；换了一台：先摆那台的快照
        if (dataBotId != target.botId) showCached(target.botId)
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
            val directories = (snapshot?.get("directories") as? JsonObject)?.let { recentDirectories(it) }
            state.value = state.value.copy(
                conn = ConnState.Connected(server),
                lastServer = server,
                cachedAt = null,
                summaries = summaries,
                runner = runner,
                team = TeamRun.listFrom(snapshot?.get("team") as? JsonObject),
                terminals = TerminalInfo.listFrom(snapshot?.get("terminals") as? JsonObject),
                engines = engines?.engines ?: emptyList(),
                defaultEngineID = engines?.defaultEngineID,
                directories = directories,
                usage = emptyMap(),
            )
            // 新对话默认的任务模式只在 settings 里（hello 不带）：不挡着连接，后台取一下
            scope.launch { refreshSettings() }
            // 连上就订活动（审批提醒、任务状态）：要回复里的快照（以前 notify 拿不到快照，
            // 之后的事件又只更新已有的条目，活动列表一直是空的）
            scope.launch { refreshActivities() }
            // 定时任务不在 hello 里：取一次（之后这个客户端就收 loops 事件）
            if (server.features.contains("loops")) scope.launch { runCatching { refreshLoops() } }
            if (assistantWanted) runCatching { subscribeAssistant() }
            // 开着一段会话（断线前开的 / 离线时看的快照）：重新订上，换成实时的
            state.value.openConversationId?.let { id -> scope.launch { runCatching { subscribeConversation(id) } } }
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
            // 数据留着（标成「这会儿的」），界面上照样看得到，连回来再换成实时的
            val since = state.value.cachedAt ?: System.currentTimeMillis()
            if (state.value.conn is ConnState.Connected || state.value.conn is ConnState.Connecting) {
                state.value = state.value.copy(conn = ConnState.Failed(reason ?: "连接断开，自动重连中…"), cachedAt = since)
            } else if (reason != null) {
                state.value = state.value.copy(conn = ConnState.Failed(reason), cachedAt = since)
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
                .onSuccess { reconnectAttempt = 0 }
                .onFailure { scheduleReconnect() }
        }
    }

    /**
     * 马上重连（网络回来了 / App 回到前台）：不等退避。只在想连着、又没连着也没在连的时候动
     */
    fun reconnectNow() {
        if (!wantConnected) return
        val conn = state.value.conn
        if (conn is ConnState.Connected || conn is ConnState.Connecting) return
        val target = lastTarget ?: return
        reconnectJob?.cancel()
        reconnectAttempt = 0
        reconnectJob = scope.launch {
            runCatching { doConnect(target) }.onFailure { scheduleReconnect() }
        }
    }

    /** 用户点「断开工作通道」：不再自动重连；界面上的数据留着（离线看得到上次的） */
    fun disconnect() {
        teardownConnection()
        assistantWanted = false
        val since = state.value.cachedAt ?: System.currentTimeMillis()
        state.value = state.value.copy(
            conn = ConnState.Disconnected,
            runner = null,
            activities = emptyList(),
            team = emptyList(),
            terminals = emptyList(),
            loops = emptyList(),
            openConversationId = null,
            chat = null,
            cachedAt = if (dataBotId > 0) since else null,
        )
    }

    /** 退出登录：数据全清（快照由 AppModel 删） */
    fun forget() {
        teardownConnection()
        assistantWanted = false
        dataBotId = 0
        state.value = FridayState()
    }

    /** 摆出 [botId] 那台的本地快照（没有就空着）；只在没连着的时候用 */
    fun showCached(botId: Long) {
        if (dataBotId == botId && botId > 0) return
        dataBotId = botId
        state.value = cache?.load(botId) ?: FridayState()
    }

    private fun teardownConnection() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        eventJob?.cancel()
        eventJob = null
        cleanupTransport()
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
            // 子 Agent 登记 / 注销、在做什么、第几步变了（整份 TeamState）
            "team" -> state.value = state.value.copy(team = TeamRun.listFrom(params))
            // 内置终端开了、关了、跑完了（整份 TerminalsState）
            "terminals" -> state.value = state.value.copy(terminals = TerminalInfo.listFrom(params))
            // 定时任务变了（整份 LoopsState；调过 loops.* 才收）
            "loops" -> state.value = state.value.copy(loops = LoopTask.listFrom(params))
            "directories" -> state.value = state.value.copy(directories = recentDirectories(params))
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
                val next = AssistantState.fromJson(s)
                val current = state.value
                state.value = current.copy(
                    assistant = next,
                    assistantAwaiting = awaitingAfterState(current.assistantAwaiting, current.assistant, next),
                )
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
                assistantEntries = emptyList(), assistantHasOlder = false, assistantAwaiting = false,
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
        val current = state.value.activities
        state.value = state.value.copy(activities = mergeActivity(current, params) ?: return)
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
            // 版本号全局递增：不比手里的新 = 过时的（整条拉回来之前就在路上的那些），直接丢。
            // 不丢的话每个过时的补丁都对不上、各拉一次整条，拉的这一会儿又压一批过时的，越拉越多（同 Qt 端 m_resyncing）
            if (local != null && version <= local) return
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
            // 整条拉回来的已经比它新：这条是之前就在路上的旧快照，别拿它盖掉
            val local = chat.versions[message.id]
            if (version != null && local != null && version < local) return
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
        // 没连着：有存下来的就先看存下来的（连上以后 doConnect 会重新订上，换成实时的）
        val rpc = rpc ?: run {
            val cached = cache?.loadChat(dataBotId, id)
                ?: throw Exception("没连上远程机器，这段会话也没有存下来的内容")
            state.value = state.value.copy(openConversationId = id, chat = cached)
            return
        }
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

    /** 停掉一个子 Agent（agent.stop；后台的不跟着停止键停，要单独停） */
    suspend fun stopAgent(agentID: String) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("agent.stop", buildJsonObject { put("agentID", agentID) })
    }

    // MARK: 后台任务：终端、定时任务

    /** 结束一个内置终端（shell 和它起的进程），从列表去掉（term.close） */
    suspend fun closeTerminal(id: String) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("term.close", buildJsonObject { put("id", id) })
    }

    /**
     * 一个终端最近的输出（term.attach 给最近 512 KB 原始字节，拿到就 term.detach，不接着收实时输出）：
     * 去掉控制序列，只留最后几十行
     */
    suspend fun terminalOutput(id: String): String {
        val rpc = rpc ?: throw Exception("未连接")
        val result = rpc.request("term.attach", buildJsonObject { put("id", id) }).jsonObject
        runCatching { rpc.notify("term.detach", buildJsonObject { put("id", id) }) }
        val data = result.str("data").orEmpty()
        val bytes = if (data.isEmpty()) ByteArray(0) else java.util.Base64.getDecoder().decode(data)
        return terminalText(bytes)
    }

    /** 取一次定时任务（loops.list；调过以后收 loops 事件） */
    suspend fun refreshLoops() {
        val rpc = rpc ?: return
        val result = rpc.request("loops.list").jsonObject
        state.value = state.value.copy(loops = LoopTask.listFrom(result))
    }

    /** 暂停 / 恢复一个定时任务（恢复时不补暂停期间错过的） */
    suspend fun setLoopPaused(id: String, paused: Boolean) {
        val rpc = rpc ?: throw Exception("未连接")
        val result = rpc.request("loops.setPaused", buildJsonObject {
            put("id", id)
            put("paused", paused)
        }).jsonObject
        state.value = state.value.copy(loops = LoopTask.listFrom(result))
    }

    /** 马上跑一次（在跑的不重复跑） */
    suspend fun runLoopNow(id: String) {
        val rpc = rpc ?: throw Exception("未连接")
        val result = rpc.request("loops.runNow", buildJsonObject { put("id", id) }).jsonObject
        state.value = state.value.copy(loops = LoopTask.listFrom(result))
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
        val result = rpc.request("assistant.say", buildJsonObject {
            put("text", text)
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        })
        val said = ((result as? JsonObject)?.get("entry") as? JsonObject)?.let { JournalEntry.fromJson(it) }
        if (expectsAssistantReply(text, said, state.value.assistant)) awaitAssistantReply()
    }

    private var awaitJob: Job? = null

    /** 说完话先摆上「思考中…」；它一直没开始反应（主机忙别的、没接上大脑……）就别一直挂着 */
    private fun awaitAssistantReply() {
        state.value = state.value.copy(assistantAwaiting = true)
        awaitJob?.cancel()
        awaitJob = scope.launch {
            delay(ASSISTANT_AWAIT_MS)
            state.value = state.value.copy(assistantAwaiting = false)
        }
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
        val current = state.value
        val list = current.assistantEntries.toMutableList()
        val index = list.indexOfFirst { it.id == entry.id }
        if (index >= 0) list[index] = entry else list.add(entry)
        state.value = current.copy(
            assistantEntries = list.sortedBy { it.at },
            assistantAwaiting = current.assistantAwaiting && !(index < 0 && answersAwaiting(entry)),
        )
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

    // MARK: 余额 / 用量（REMOTE.md §6「在线服务」services.usage，2.6 起 serviceUsage）

    /**
     * 查一遍能用的模型那几家的余额 / 套餐用量（同一家的多个模型只查一次，本机地址不查）。
     * 打开模型菜单时调；30 秒内查过的不重查（[force] 不管）
     */
    suspend fun refreshUsage(force: Boolean = false) {
        val rpc = rpc ?: return
        if (!state.value.hasFeature("serviceUsage")) return
        val targets = state.value.usableEngines
            .filter { it.baseURL.isNotBlank() && !isLocalBaseURL(it.baseURL) }
            .distinctBy { it.usageKey }
        val now = System.currentTimeMillis()
        coroutineScope {
            targets.forEach { engine ->
                val key = engine.usageKey
                val existing = state.value.usage[key]
                if (existing?.loading == true) return@forEach
                if (!force && existing != null && now - existing.checkedAt < 30_000) return@forEach
                setUsage(key) { (it ?: UsageInfo()).copy(loading = true) }
                launch {
                    val info = runCatching {
                        rpc.request("services.usage", buildJsonObject {
                            put("baseURL", engine.baseURL)
                            put("secretName", engine.apiKeySecret)
                            // 客户端没有服务目录：认得的服务照旧（按地址），不认得的当 one-api 这类中转试一次（同桌面端）
                            put("custom", true)
                        }).jsonObject
                    }.fold(
                        onSuccess = { result ->
                            UsageInfo(
                                line = usageMenuLine(result),
                                error = result.str("error"),
                                supported = result.bool("supported") == true,
                                checkedAt = System.currentTimeMillis(),
                            )
                        },
                        onFailure = { UsageInfo(error = it.message, supported = false, checkedAt = System.currentTimeMillis()) },
                    )
                    setUsage(key) { info }
                }
            }
        }
    }

    private fun setUsage(key: String, transform: (UsageInfo?) -> UsageInfo) {
        synchronized(this) {
            val map = state.value.usage
            state.value = state.value.copy(usage = map + (key to transform(map[key])))
        }
    }

    // MARK: 工作目录（REMOTE.md §5 DirectoriesState、§6 runner.setWorkingDirectory / fs.list）

    private fun recentDirectories(o: JsonObject): List<RecentDirectory> =
        (o["recent"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.let { d ->
                d.str("path")?.takeIf { it.isNotBlank() }?.let { RecentDirectory(it, d.bool("exists") ?: true) }
            }
        }.orEmpty()

    /** 打开目录菜单时重新看一遍最近的那几个还在不在（旧服务端没有 directories 就算了） */
    suspend fun refreshDirectories() {
        val rpc = rpc ?: return
        if (state.value.directories == null) return
        runCatching { rpc.request("directories.get").jsonObject }
            .onSuccess { state.value = state.value.copy(directories = recentDirectories(it)) }
    }

    /**
     * 换这段对话的工作目录（runner.setWorkingDirectory {conversationID, path?}；path 不写 = 回默认）。
     * 选了目录同时记进这台的「最近选择」（directories.push），服务端会推 directories 事件
     */
    suspend fun setWorkingDirectory(conversationID: String, path: String?) {
        val rpc = rpc ?: throw Exception("未连接")
        rpc.request("runner.setWorkingDirectory", buildJsonObject {
            put("conversationID", conversationID)
            if (!path.isNullOrBlank()) put("path", path)
        })
        updateChat(conversationID) { it.copy(workingDirectory = path) }
        updateSummary(conversationID) {
            it.copy(workingDirectory = path, workingDirectoryName = path?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\'))
        }
        if (!path.isNullOrBlank() && state.value.directories != null) {
            runCatching { rpc.request("directories.push", buildJsonObject { put("path", path) }) }
        }
    }

    /** 从「最近选择」里去掉一个（directories.remove） */
    suspend fun forgetDirectory(path: String) {
        val rpc = rpc ?: return
        runCatching { rpc.request("directories.remove", buildJsonObject { put("path", path) }) }
        state.value = state.value.copy(directories = state.value.directories?.filterNot { it.path == path })
    }

    /** 浏览这台机器上的一层子目录（fs.list；不给 path 是主目录） */
    suspend fun listDirectory(path: String?): FsListing {
        val rpc = rpc ?: throw Exception("未连接")
        val o = rpc.request("fs.list", buildJsonObject { if (!path.isNullOrBlank()) put("path", path) }).jsonObject
        fun entries(key: String): List<FsEntry> = (o[key] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.let { e ->
                val p = e.str("path") ?: return@let null
                FsEntry(e.str("name")?.ifBlank { null } ?: p, p)
            }
        }.orEmpty()
        return FsListing(
            path = o.str("path").orEmpty(),
            parent = o.str("parent")?.takeIf { it.isNotBlank() },
            entries = entries("entries"),
            roots = entries("roots"),
            truncated = o.bool("truncated") ?: false,
        )
    }

    /** 现在连着哪台（打给它的助手要知道机器人 id） */
    val target: RemoteTarget? get() = lastTarget

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
        cache?.removeChat(dataBotId, id)
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

    /**
     * 批准 / 拒绝（或回答 ask_user：[answers] 每题一条——选项名，多选「、」连起来，空 = 没选；
     * [reply] 是没点选项直接回的一句，ASK.md）。失败（没连上、那台已经不在等了）在返回值里
     */
    suspend fun resolveApproval(
        eventID: String,
        approved: Boolean,
        answers: List<String>? = null,
        reply: String? = null,
    ): Result<Unit> {
        val rpc = rpc ?: return Result.failure(Exception("没连上远程 Friday"))
        val params = buildJsonObject {
            put("eventID", eventID)
            put("approved", approved)
            if (answers != null || reply != null) put("answer", buildJsonObject {
                put("answers", kotlinx.serialization.json.JsonArray(answers.orEmpty().map { kotlinx.serialization.json.JsonPrimitive(it) }))
                if (reply != null) put("reply", reply)
            })
        }
        val result = runCatching { rpc.request("runner.resolveApproval", params); Unit }
        refreshActivities()
        return result
    }
}
