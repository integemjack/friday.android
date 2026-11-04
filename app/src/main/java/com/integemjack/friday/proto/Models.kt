package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Friday 远程协议数据模型（REMOTE.md §5；字段与 Qt ChatModels / 桌面版 protocol.ts 对齐）。
 * 消息块保留线上原始 JSON 形状（Swift 带关联值枚举的 Codable：`{text:{"_0":"…"}}`、`{tool:{"_0":{…}}}`）。
 */

const val PROTOCOL_VERSION = 1

/** 协议错误码（REMOTE.md §3） */
object RpcErrors {
    const val Parse = -32700
    const val InvalidRequest = -32600
    const val MethodNotFound = -32601
    const val InvalidParams = -32602
    const val Internal = -32603
    const val ProtocolVersion = 1
    const val NotHello = 2
    const val NotFound = 3
    const val NotAllowed = 4
}

class RpcException(val code: Int, message: String) : Exception(message)

// MARK: 会话

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val pinned: Boolean,
    val isEmpty: Boolean,
    val hasDraft: Boolean,
    val workingDirectory: String? = null,
    val workingDirectoryName: String? = null,
    val permissionMode: String? = null,
    val engineID: String? = null,
    /** 这段对话的思考档位（off / low / medium / high / ultra；没选过不写） */
    val thinking: String? = null,
    /** 这段对话的任务模式（solo / delegatesHeavy / ultra；2.9 起，没选过不写 = 跟那台的新对话默认） */
    val teamMode: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject): ConversationSummary = ConversationSummary(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "",
            updatedAt = o.str("updatedAt") ?: "",
            pinned = o.bool("pinned") ?: false,
            isEmpty = o.bool("isEmpty") ?: false,
            hasDraft = o.bool("hasDraft") ?: false,
            workingDirectory = o.str("workingDirectory"),
            workingDirectoryName = o.str("workingDirectoryName"),
            permissionMode = o.str("permissionMode"),
            engineID = o.str("engineID"),
            thinking = o.str("thinking"),
            teamMode = o.str("teamMode"),
        )
    }
}

/** 排队等着发的一条（REMOTE.md §5 QueuedInput） */
data class QueuedInput(
    val id: String,
    val text: String,
    val attachments: List<String> = emptyList(),
    val createdAt: String = "",
    /** 替用户发的那条的来源标记（助手 / 渠道），自己发的没有 */
    val fromLabel: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject): QueuedInput = QueuedInput(
            id = o.str("id") ?: "",
            text = o.str("text") ?: "",
            attachments = o.strList("attachments"),
            createdAt = o.str("createdAt") ?: "",
            fromLabel = (o["from"] as? JsonObject)?.str("label"),
        )
    }
}

data class Runtime(
    val generating: Boolean,
    val stopping: Boolean,
    val queue: List<QueuedInput>,
    val queuePaused: Boolean,
    val pendingApproval: Boolean,
    val failedMessageID: String? = null,
    val canRetry: Boolean,
    val canSend: Boolean,
    val workingDirectory: String? = null,
    val thinkingLevel: String? = null,
    val permissionMode: String? = null,
    /** 这个模型能调的思考档位；null = 服务端没给（旧版），空 = 这个模型不能调 */
    val thinkingLevels: List<String>? = null,
    /** 这段对话实际在用的引擎 id（runtime.engine.id；一个都没接入时没有） */
    val engineID: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject): Runtime = Runtime(
            generating = o.bool("generating") ?: false,
            stopping = o.bool("stopping") ?: false,
            queue = (o["queue"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { q -> QueuedInput.fromJson(q) }
            }.orEmpty(),
            queuePaused = o.bool("queuePaused") ?: false,
            pendingApproval = o.bool("pendingApproval") ?: false,
            failedMessageID = o.str("failedMessageID"),
            canRetry = o.bool("canRetry") ?: false,
            canSend = o.bool("canSend") ?: true,
            workingDirectory = o.str("workingDirectory"),
            thinkingLevel = o.str("thinkingLevel"),
            permissionMode = o.str("permissionMode"),
            thinkingLevels = if (o["thinkingLevels"] is kotlinx.serialization.json.JsonArray) o.strList("thinkingLevels") else null,
            engineID = (o["engine"] as? JsonObject)?.str("id"),
        )
    }
}

data class ServerInfo(
    val version: String,
    val platform: String,
    val hostname: String,
    val user: String,
    val dataDir: String,
    val host: String?, // server / desktop / swift
    val defaultWorkingDirectory: String?,
    val features: List<String>,
) {
    val isDesktop: Boolean get() = host == "desktop"
    val displayName: String get() = if (isDesktop) "Friday 桌面端 $version" else "Friday $version"

    companion object {
        fun fromJson(o: JsonObject): ServerInfo = ServerInfo(
            version = o.str("version") ?: "?",
            platform = o.str("platform") ?: "?",
            hostname = o.str("hostname") ?: "?",
            user = o.str("user") ?: "?",
            dataDir = o.str("dataDir") ?: "?",
            host = o.str("host"),
            defaultWorkingDirectory = o.str("defaultWorkingDirectory"),
            features = (o["features"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.str("name") ?: (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                .orEmpty(),
        )
    }
}

// MARK: 消息与块

/** 线上形状：{id, kind: {case: payload}}，payload 可能包在 `_0` 里 */
data class MessageBlock(val id: String, val kind: JsonObject) {
    val case: String get() = kind.keys.firstOrNull() ?: ""
}

fun blockCaseName(block: MessageBlock): String = block.case

/** 取块里某种 case 的原始值（未解 `_0` 包装） */
private fun rawCaseValue(block: MessageBlock, case: String): JsonElement? = block.kind[case]

/** 解 `_0` 包装：带关联值的枚举 case 包在 `_0` 里（Swift Codable），否则裸值 */
fun unwrapCase(value: JsonElement?): JsonElement? {
    val obj = value as? JsonObject ?: return value
    return obj["_0"] ?: obj
}

fun blockText(block: MessageBlock): String? {
    if (block.case != "text") return null
    return (unwrapCase(rawCaseValue(block, "text")) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

fun blockThinking(block: MessageBlock): String? {
    if (block.case != "thinking") return null
    return (unwrapCase(rawCaseValue(block, "thinking")) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

fun blockTool(block: MessageBlock): ToolEvent? {
    if (block.case != "tool") return null
    val obj = unwrapCase(rawCaseValue(block, "tool")) as? JsonObject ?: return null
    return ToolEvent.fromJson(obj)
}

/** 消息里的附件（REMOTE.md §5 MediaAttachment）：relativePath 在媒体目录之内，用 file.read 取 */
data class MediaAttachment(
    val id: String,
    val kind: String, // image / video / audio / file
    val relativePath: String,
    val fileName: String,
    val mimeType: String,
    val byteCount: Long,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val isImage: Boolean
        get() = kind == "image" || (kind.isBlank() && mimeType.startsWith("image/")) ||
            IMAGE_EXT.contains(ext)
    val isAudio: Boolean get() = kind == "audio" || mimeType.startsWith("audio/")
    val isVideo: Boolean get() = kind == "video" || mimeType.startsWith("video/")
    val ext: String get() = fileName.substringAfterLast('.', "").lowercase()

    companion object {
        val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif")

        fun fromJson(o: JsonObject): MediaAttachment = MediaAttachment(
            id = o.str("id") ?: "",
            kind = o.str("kind") ?: "",
            relativePath = o.str("relativePath") ?: "",
            fileName = o.str("fileName") ?: "",
            mimeType = o.str("mimeType") ?: "",
            byteCount = o.long("byteCount") ?: 0L,
            caption = o.str("caption"),
            width = o.long("width")?.toInt(),
            height = o.long("height")?.toInt(),
        )

        fun listFrom(element: kotlinx.serialization.json.JsonElement?): List<MediaAttachment> =
            (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { o -> fromJson(o) }
            }.orEmpty()
    }
}

fun blockMedia(block: MessageBlock): MediaAttachment? {
    if (block.case != "media") return null
    val obj = unwrapCase(rawCaseValue(block, "media")) as? JsonObject ?: return null
    return MediaAttachment.fromJson(obj)
}

/** 会直接内嵌媒体的工具（同桌面版 mediaTools.ts）：工具名 → 参数里装媒体路径的字段名 */
val TOOL_MEDIA_ARG: Map<String, String> = mapOf(
    "analyze_image" to "image",
    "analyze_video" to "video",
    "transcribe_audio" to "audio",
)

/** 从工具参数 JSON 里取媒体源（参数还没流完整时解不开，返回 null 等下一块补丁） */
fun toolMediaSource(name: String, arguments: String): Pair<String, String>? {
    val field = TOOL_MEDIA_ARG[name.lowercase()] ?: return null
    val raw = arguments.trim()
    if (raw.isEmpty()) return null
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val src = obj.str(field)?.trim().orEmpty()
    if (src.isEmpty()) return null
    val kind = when (field) {
        "image" -> "image"
        "video" -> "video"
        else -> "audio"
    }
    return kind to src
}

data class ToolEvent(
    val id: String,
    val name: String,
    val arguments: String,
    val status: String, // awaitingApproval / running / succeeded / failed / denied
    val result: String,
    val title: String? = null,
    val preview: String? = null,
    val queued: Boolean = false,
    val stopped: Boolean = false,
    val agentName: String? = null,
    val media: List<MediaAttachment> = emptyList(),
    val transcript: List<MessageBlock> = emptyList(),
) {
    companion object {
        fun fromJson(o: JsonObject): ToolEvent {
            val transcript = (o["transcript"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                val b = it as? JsonObject ?: return@mapNotNull null
                val id = b.str("id") ?: return@mapNotNull null
                val kind = b["kind"] as? JsonObject ?: return@mapNotNull null
                MessageBlock(id, kind)
            }.orEmpty()
            return ToolEvent(
                id = o.str("id") ?: "",
                name = o.str("name") ?: "",
                arguments = o.str("arguments") ?: "",
                status = o.str("status") ?: "",
                result = o.str("result") ?: "",
                title = o.str("title"),
                preview = o.str("preview"),
                queued = o.bool("queued") ?: false,
                stopped = o.bool("stopped") ?: false,
                agentName = o.str("agentName"),
                media = MediaAttachment.listFrom(o["media"]),
                transcript = transcript,
            )
        }
    }
}

/** 一次生成的用量（REMOTE.md §5 GenerationStats，只取界面上要显示的几个） */
data class GenerationStats(
    val tokensPerSecond: Double,
    val tokenCount: Long,
    val engineName: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject): GenerationStats = GenerationStats(
            tokensPerSecond = o.dbl("tokensPerSecond") ?: 0.0,
            tokenCount = o.long("tokenCount") ?: 0L,
            engineName = o.str("engineName"),
        )
    }
}

data class ChatMessage(
    val id: String,
    val role: String, // user / assistant
    val blocks: List<MessageBlock>,
    val createdAt: String,
    val error: String? = null,
    val notice: String? = null,
    val parentID: String? = null,
    val stoppedByUser: Boolean = false,
    val interjected: Boolean = false,
    val fromAgent: String? = null,
    val sentBy: String? = null,
    val quotaWait: Boolean = false,
    val stats: GenerationStats? = null,
) {
    val text: String
        get() = blocks.mapNotNull { blockText(it) }.joinToString("\n").trim()

    companion object {
        fun fromJson(o: JsonObject): ChatMessage = ChatMessage(
            id = o.str("id") ?: "",
            role = o.str("role") ?: "assistant",
            blocks = (o["blocks"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                val b = it as? JsonObject ?: return@mapNotNull null
                val id = b.str("id") ?: return@mapNotNull null
                val kind = b["kind"] as? JsonObject ?: return@mapNotNull null
                MessageBlock(id, kind)
            }.orEmpty(),
            createdAt = o.str("createdAt") ?: "",
            error = o.str("error"),
            notice = o.str("notice"),
            parentID = o.str("parentID"),
            stoppedByUser = o.bool("stoppedByUser") ?: false,
            interjected = o.bool("interjected") ?: false,
            fromAgent = o.str("fromAgent"),
            sentBy = o.str("sentBy"),
            quotaWait = o["quotaWait"] != null,
            stats = (o["stats"] as? JsonObject)?.let { GenerationStats.fromJson(it) },
        )
    }
}

data class Conversation(
    val id: String,
    val title: String,
    val nodes: List<ChatMessage>,
    val selectedChildren: Map<String, String>,
    val workingDirectory: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject): Conversation = Conversation(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "",
            nodes = (o["nodes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { n -> ChatMessage.fromJson(n) }
            }.orEmpty(),
            selectedChildren = (o["selectedChildren"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.toMap() ?: emptyMap(),
            workingDirectory = o.str("workingDirectory"),
        )
    }
}

/** 一条消息在它的兄弟分支里的位置（切分支用，同桌面版 BranchInfo） */
data class BranchInfo(
    val index: Int,
    val count: Int,
    val siblings: List<String>,
    /** 父消息 id；根消息是空串 */
    val parentKey: String,
)

data class ConversationTree(
    val path: List<ChatMessage>,
    /** 消息 id → 分支信息（只有同父多于一个孩子的那些才有） */
    val branches: Map<String, BranchInfo>,
)

/** 消息树的当前路径 + 分支信息（与桌面版 ConversationPath.ts 同一规则：parentID + selectedChildren，层内按创建时间） */
fun conversationTree(nodes: List<ChatMessage>, selectedChildren: Map<String, String>): ConversationTree {
    val ids = nodes.map { it.id }.toHashSet()
    val byParent = HashMap<String, MutableList<ChatMessage>>()
    for (node in nodes) {
        val parent = if (node.parentID != null && ids.contains(node.parentID)) node.parentID!! else ""
        byParent.getOrPut(parent) { mutableListOf() }.add(node)
    }
    for (list in byParent.values) list.sortBy { it.createdAt }
    val path = mutableListOf<ChatMessage>()
    val branches = HashMap<String, BranchInfo>()
    var level = byParent[""] ?: return ConversationTree(path, branches)
    var parentKey = ""
    while (level.isNotEmpty()) {
        val selected = selectedChildren[parentKey]
        var index = if (selected != null) level.indexOfFirst { it.id == selected } else 0
        if (index < 0) index = 0
        val current = level[index]
        if (level.size > 1) {
            branches[current.id] = BranchInfo(
                index = index,
                count = level.size,
                siblings = level.map { it.id },
                parentKey = parentKey,
            )
        }
        path.add(current)
        parentKey = current.id
        level = byParent[current.id] ?: break
    }
    return ConversationTree(path, branches)
}

/** 消息树当前路径（只要路径时用这个） */
fun conversationPath(nodes: List<ChatMessage>, selectedChildren: Map<String, String>): List<ChatMessage> =
    conversationTree(nodes, selectedChildren).path

// MARK: 活动与审批

data class AskOption(val label: String, val detail: String?)

data class AskQuestion(
    val question: String,
    val options: List<AskOption>,
    val multiple: Boolean,
    val recommended: Int,
)

data class PendingApproval(
    val eventID: String,
    val tool: String,
    val title: String,
    val summary: String,
    val questions: List<AskQuestion>?, // 非空 = ask_user 提问
) {
    val isQuestion: Boolean get() = !questions.isNullOrEmpty()

    companion object {
        fun fromJson(o: JsonObject): PendingApproval = PendingApproval(
            eventID = o.str("eventID") ?: "",
            tool = o.str("tool") ?: "",
            title = o.str("title") ?: "",
            summary = o.str("summary") ?: "",
            questions = (o["questions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { q ->
                val qo = q as? JsonObject ?: return@mapNotNull null
                val options = (qo["options"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { op ->
                    val oo = op as? JsonObject ?: return@mapNotNull null
                    AskOption(oo.str("label") ?: "", oo.str("detail"))
                }.orEmpty()
                AskQuestion(
                    question = qo.str("question") ?: "",
                    options = options,
                    multiple = qo.bool("multiple") ?: false,
                    recommended = qo.long("recommended")?.toInt() ?: -1,
                )
            }
        )
    }
}

data class TaskStep(val text: String, val status: String)

data class SessionActivity(
    val id: String,
    val title: String,
    val status: String, // idle / running / approval / question / quota / error
    val taskGoal: String?,
    val taskSteps: List<TaskStep>,
    val approvals: List<PendingApproval>,
    val currentToolTitle: String?,
    val currentToolSummary: String?,
    val updatedAt: String,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionActivity = SessionActivity(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "",
            status = o.str("status") ?: "idle",
            taskGoal = (o["task"] as? JsonObject)?.str("goal"),
            taskSteps = (o["task"] as? JsonObject)?.let { t ->
                (t["steps"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                    val s = it as? JsonObject ?: return@mapNotNull null
                    TaskStep(s.str("text") ?: "", s.str("status") ?: "todo")
                }
            } ?: emptyList(),
            approvals = (o["approvals"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { a -> PendingApproval.fromJson(a) }
            } ?: emptyList(),
            currentToolTitle = (o["currentTool"] as? JsonObject)?.str("title"),
            currentToolSummary = (o["currentTool"] as? JsonObject)?.str("summary"),
            updatedAt = o.str("updatedAt") ?: "",
        )
    }
}

data class RunnerState(
    val generating: List<String>,
    val stopping: List<String>,
    val pendingApproval: List<String>,
    val pendingQuestion: List<String>,
) {
    fun isGenerating(id: String) = generating.contains(id)

    companion object {
        fun fromJson(o: JsonObject): RunnerState = RunnerState(
            generating = o.strList("generating"),
            stopping = o.strList("stopping"),
            pendingApproval = o.strList("pendingApproval"),
            pendingQuestion = o.strList("pendingQuestion"),
        )
    }
}

// MARK: 助手（REMOTE.md §6「助手」/ docs\ASSISTANT.md）

/**
 * 助手日志的一条（JournalEntry）：`{id, at, kind, source?, text, projects?, refs?, interrupted?, data?, streaming?}`。
 * `data` 按 kind 不同装不同东西（回执 / 工具 / 助手回复），这里保留原始 JSON，界面上按类型取。
 */
data class JournalEntry(
    val id: String,
    val at: String,
    val kind: String, // user / assistant / receipt / tool / notice
    val source: String?,
    val text: String,
    val projects: List<String>,
    val interrupted: Boolean,
    val streaming: Boolean,
    val data: JsonObject?,
) {
    /** 回执（某段会话的动静）：助手页上那张状态卡 */
    val receipt: ReceiptInfo?
        get() = if (kind != "receipt") null else data?.let { ReceiptInfo.fromJson(it) }

    /** 工具那一条 */
    val tool: ToolInfo?
        get() = if (kind != "tool") null else data?.let { ToolInfo.fromJson(it) }

    /** 助手回复标了 silent 的不显示 */
    val silent: Boolean get() = data?.bool("silent") ?: false
    val error: String? get() = if (kind == "assistant") data?.str("error") else null

    companion object {
        fun fromJson(o: JsonObject): JournalEntry = JournalEntry(
            id = o.str("id") ?: "",
            at = o.str("at") ?: "",
            kind = o.str("kind") ?: "notice",
            source = o.str("source"),
            text = o.str("text") ?: "",
            projects = o.strList("projects"),
            interrupted = o.bool("interrupted") ?: false,
            streaming = o.bool("streaming") ?: false,
            data = o["data"] as? JsonObject,
        )
    }
}

/** 回执的 data：`{machineID, conversationID, level, status, event, brief?, approvalEventID?, woke?}` */
data class ReceiptInfo(
    val machineID: String?,
    val conversationID: String?,
    val level: String,
    val status: String,
    val event: String,
    val brief: String?,
    val approvalEventID: String?,
    val woke: Boolean,
) {
    /** 有它就能在这张卡上直接批 / 拒（assistant.resolveApproval） */
    val needsDecision: Boolean get() = approvalEventID != null

    companion object {
        fun fromJson(o: JsonObject): ReceiptInfo = ReceiptInfo(
            machineID = o.str("machineID"),
            conversationID = o.str("conversationID"),
            level = o.str("level") ?: "info",
            status = o.str("status") ?: "",
            event = o.str("event") ?: "",
            brief = o.str("brief"),
            approvalEventID = o.str("approvalEventID"),
            woke = o.bool("woke") ?: false,
        )
    }
}

/** 工具那条的 data：`{name, arguments, result, reply}` */
data class ToolInfo(val name: String, val arguments: String?, val result: String?, val reply: String?) {
    companion object {
        fun fromJson(o: JsonObject): ToolInfo = ToolInfo(
            name = o.str("name") ?: "",
            arguments = o.str("arguments"),
            result = o.str("result"),
            reply = o.str("reply"),
        )
    }
}

/** 这台机器上的一个引擎（EnginesState.engines 的一项，REMOTE.md §5；只有密钥名，没有值） */
data class EngineInfo(
    val id: String,
    /** 服务商 / 引擎名 */
    val title: String,
    /** 实际的模型名（选择器上显示它） */
    val model: String,
    /** 关掉的服务的模型：配置留着，模型菜单里不列 */
    val disabled: Boolean = false,
    /** 接口地址（查余额 / 用量按它 + 密钥名；本机地址不查） */
    val baseURL: String = "",
    /** 密钥的名字（值不出那台） */
    val apiKeySecret: String = "",
) {
    /** 查余额用的键（同一家的几个模型共用一份） */
    val usageKey: String get() = usageKey(baseURL, apiKeySecret)

    /** 选择器上显示的名字：模型名优先，没有就引擎名 */
    val label: String get() = model.ifBlank { title }.ifBlank { "默认模型" }

    companion object {
        fun fromJson(o: JsonObject): EngineInfo = EngineInfo(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "",
            model = o.str("model") ?: "",
            disabled = o.bool("disabled") ?: false,
            baseURL = o.str("baseURL") ?: "",
            apiKeySecret = o.str("apiKeySecret") ?: "",
        )
    }
}

/** 最近选过的一个工作目录（DirectoriesState.recent；exists 按发出时看） */
data class RecentDirectory(val path: String, val exists: Boolean)

/** 这台机器上一层目录（fs.list 的回执：选工作目录时浏览用） */
data class FsListing(
    val path: String,
    val parent: String?,
    val entries: List<FsEntry>,
    /** 主目录和各个盘（Linux 是 /） */
    val roots: List<FsEntry>,
    val truncated: Boolean,
)

data class FsEntry(val name: String, val path: String)

/** EnginesState：`{engines: [EngineConfig], defaultEngineID?}`（hello 的 state.engines、engines 事件） */
data class EnginesState(val engines: List<EngineInfo>, val defaultEngineID: String?) {
    companion object {
        fun fromJson(o: JsonObject): EnginesState = EnginesState(
            engines = (o["engines"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { e -> EngineInfo.fromJson(e) }
            }.orEmpty().filter { it.id.isNotEmpty() },
            defaultEngineID = o.str("defaultEngineID")?.takeIf { it.isNotEmpty() },
        )
    }
}

/** 助手能用的大脑（AssistantState.engines 的一项） */
data class AssistantEngine(val id: String, val title: String, val model: String, val supportsTools: Boolean) {
    companion object {
        fun fromJson(o: JsonObject): AssistantEngine = AssistantEngine(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "",
            model = o.str("model") ?: "",
            supportsTools = o.bool("supportsTools") ?: false,
        )
    }
}

/** AssistantConfig（ASSISTANT.md §9）：界面上只用到其中几项，其余原样留着好回写 */
data class AssistantConfig(
    val enabled: Boolean,
    val name: String,
    val pushRule: String, // important / all / none
    val brainEngineID: String?,
    val sessionEngineID: String?,
    /** 大脑开不开深度思考（服务端是布尔值） */
    val brainThinking: Boolean,
    val toolRounds: Int,
    val idleMinutes: Int,
    val digestSeconds: Int,
    val confirmDestructive: Boolean,
    val approveFromChannels: Boolean,
    val brainApproves: Boolean,
    val briefWriter: Boolean,
    val raw: JsonObject?,
) {
    companion object {
        fun fromJson(o: JsonObject): AssistantConfig = AssistantConfig(
            enabled = o.bool("enabled") ?: false,
            name = o.str("name") ?: "Friday 个人助理",
            pushRule = o.str("pushRule") ?: "important",
            brainEngineID = o.str("brainEngineID"),
            sessionEngineID = o.str("sessionEngineID"),
            brainThinking = o.bool("brainThinking") ?: false,
            toolRounds = o.long("toolRounds")?.toInt() ?: 0,
            idleMinutes = o.long("idleMinutes")?.toInt() ?: 0,
            digestSeconds = o.long("digestSeconds")?.toInt() ?: 0,
            confirmDestructive = o.bool("confirmDestructive") ?: true,
            approveFromChannels = o.bool("approveFromChannels") ?: false,
            brainApproves = o.bool("brainApproves") ?: false,
            briefWriter = o.bool("briefWriter") ?: false,
            raw = o,
        )
    }
}

/** AssistantState：`{enabled, reacting, reactingEntryID?, config, brainEngineTitle?, engines}` */
data class AssistantState(
    val enabled: Boolean,
    val reacting: Boolean,
    val reactingEntryID: String?,
    val config: AssistantConfig,
    val brainEngineTitle: String?,
    val engines: List<AssistantEngine>,
) {
    /** 正在说话（大脑在出字）：界面上显示进度、给「打断」按钮 */
    val speaking: Boolean get() = reacting

    companion object {
        fun fromJson(o: JsonObject): AssistantState = AssistantState(
            enabled = o.bool("enabled") ?: false,
            reacting = o.bool("reacting") ?: false,
            reactingEntryID = o.str("reactingEntryID"),
            config = (o["config"] as? JsonObject)?.let { AssistantConfig.fromJson(it) }
                ?: AssistantConfig.fromJson(JsonObject(emptyMap())),
            brainEngineTitle = o.str("brainEngineTitle"),
            engines = (o["engines"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { e -> AssistantEngine.fromJson(e) }
            } ?: emptyList(),
        )
    }
}

/** 聊天工具渠道（Channel）：SChat 机器人那头的绑定 */
data class AssistantChannel(
    val id: String,
    val kind: String,
    val title: String,
    val enabled: Boolean,
    val notify: Boolean,
    val status: String, // off / connecting / online / error
    val statusText: String,
    val botName: String,
    val server: String,
    val hasToken: Boolean,
    val owners: List<String>,
    val groups: List<String>,
) {
    companion object {
        fun fromJson(o: JsonObject): AssistantChannel = AssistantChannel(
            id = o.str("id") ?: "",
            kind = o.str("kind") ?: "",
            title = o.str("title") ?: "",
            enabled = o.bool("enabled") ?: false,
            notify = o.bool("notify") ?: false,
            status = o.str("status") ?: "off",
            statusText = o.str("statusText") ?: "",
            botName = o.str("botName") ?: "",
            server = o.str("server") ?: "",
            hasToken = o.bool("hasToken") ?: false,
            owners = (o["owners"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.str("name")
            } ?: emptyList(),
            groups = (o["groups"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.str("name")
            } ?: emptyList(),
        )
    }
}

/** 状态板（Board）：这台机器上各段会话的实时动静，助手页顶部那行「几个在跑」用它 */
data class BoardSession(
    val machineID: String,
    val conversationID: String,
    val shortID: String,
    val title: String,
    val workingDirectory: String,
    val status: String,
    val statusText: String,
    val line: String,
    val progress: String,
    val approvals: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): BoardSession = BoardSession(
            machineID = o.str("machineID") ?: "",
            conversationID = o.str("conversationID") ?: "",
            shortID = o.str("shortID") ?: "",
            title = o.str("title") ?: "",
            workingDirectory = o.str("workingDirectory") ?: o.str("directory") ?: "",
            status = o.str("status") ?: "idle",
            statusText = o.str("statusText") ?: "",
            line = o.str("line") ?: "",
            progress = o.str("progress") ?: "",
            approvals = o.long("approvals")?.toInt() ?: 0,
        )
    }
}

data class Board(val machines: List<BoardMachine>) {
    val sessions: List<BoardSession> get() = machines.flatMap { it.entries }
    val running: Int get() = sessions.count { it.status == "running" }
    val awaiting: Int get() = sessions.sumOf { it.approvals }

    companion object {
        fun fromJson(o: JsonObject): Board = Board(
            machines = (o["machines"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                BoardMachine(
                    id = mo.str("id") ?: "",
                    name = mo.str("name") ?: "",
                    online = mo.bool("online") ?: false,
                    idle = mo.bool("idle") ?: false,
                    entries = (mo["entries"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        (it as? JsonObject)?.let { e -> BoardSession.fromJson(e) }
                    } ?: emptyList(),
                )
            } ?: emptyList(),
        )
    }
}

data class BoardMachine(
    val id: String,
    val name: String,
    val online: Boolean,
    val idle: Boolean,
    val entries: List<BoardSession>,
)

// MARK: JsonObject 便捷取值

fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() || true }

fun JsonObject.bool(key: String): Boolean? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

fun JsonObject.long(key: String): Long? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

fun JsonObject.dbl(key: String): Double? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

fun JsonObject.strList(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    } ?: emptyList()
