package com.integemjack.friday.data

import android.content.Context
import com.integemjack.friday.proto.AssistantState
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.ChatState
import com.integemjack.friday.proto.Conversation
import com.integemjack.friday.proto.ConversationSummary
import com.integemjack.friday.proto.EngineInfo
import com.integemjack.friday.proto.EnginesState
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.JournalEntry
import com.integemjack.friday.proto.RecentDirectory
import com.integemjack.friday.proto.ServerInfo
import com.integemjack.friday.proto.bool
import com.integemjack.friday.proto.long
import com.integemjack.friday.proto.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 断网 / 重开 App 时先把上次的内容摆出来：每台机器（SChat 账号 + 机器人）一份快照，
 * 连上以后照常被实时的盖掉。
 *
 * 存的是协议里的同一套 JSON（编码器和各模型的 fromJson 一一对应），读回来走现成的解码，
 * 不另起一套格式。放在 filesDir（cacheDir 会被系统清掉），退出 SChat 登录时整个删掉。
 *
 * 存什么：会话列表、助手的日志（最近 200 条）、助手的状态和设置、模型列表和默认、
 * 新对话默认的任务模式、最近选过的工作目录、上次连上的那台的信息；会话内容按会话一份，
 * 只留最近看过的 [MAX_CHATS] 段。实时的东西（活动、状态板、渠道、余额）不存：过时了会误导。
 */
class StateCache(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "state-cache"))

    private val json = Json { ignoreUnknownKeys = true }

    private fun machineDir(account: Long, botId: Long) = File(root, "$account-$botId")

    // MARK: 整台机器的快照

    /** 读快照；没有 / 坏了返回 null（坏了顺手删掉） */
    fun load(account: Long, botId: Long): Snapshot? {
        val file = File(machineDir(account, botId), STATE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val o = json.parseToJsonElement(file.readText()).let { it as JsonObject }
            if ((o.long("version") ?: 0) != VERSION.toLong()) return null
            decodeSnapshot(o)
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun save(account: Long, botId: Long, snapshot: Snapshot) {
        if (account <= 0 || botId <= 0) return
        writeAtomic(File(machineDir(account, botId), STATE_FILE), encodeSnapshot(snapshot).toString())
    }

    // MARK: 会话内容

    fun loadChat(account: Long, botId: Long, conversationId: String): ChatState? {
        val file = chatFile(account, botId, conversationId) ?: return null
        if (!file.exists()) return null
        return runCatching {
            val o = json.parseToJsonElement(file.readText()) as JsonObject
            if ((o.long("version") ?: 0) != VERSION.toLong()) return null
            val convo = Conversation.fromJson(o["conversation"] as JsonObject)
            ChatState(
                id = convo.id,
                title = convo.title,
                nodes = convo.nodes.associateBy { it.id },
                order = (o["order"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: convo.nodes.map { it.id },
                selectedChildren = convo.selectedChildren,
                workingDirectory = convo.workingDirectory,
            )
        }.getOrElse {
            file.delete()
            null
        }
    }

    fun saveChat(account: Long, botId: Long, chat: ChatState) {
        if (account <= 0 || botId <= 0 || chat.id.isBlank()) return
        val file = chatFile(account, botId, chat.id) ?: return
        val text = encodeChat(chat).toString()
        // 特别大的（几百条带长工具输出）不存：读写都慢，也占地方
        if (text.length > MAX_CHAT_CHARS) return
        writeAtomic(file, text)
        file.setLastModified(System.currentTimeMillis())
        pruneChats(file.parentFile)
    }

    fun removeChat(account: Long, botId: Long, conversationId: String) {
        chatFile(account, botId, conversationId)?.delete()
    }

    /** 退出登录：全部删掉（别人的会话内容不该留在这台手机上） */
    fun clearAll() {
        root.deleteRecursively()
    }

    private fun chatFile(account: Long, botId: Long, conversationId: String): File? {
        // 会话 id 是 UUID；别让奇怪的字符拼进路径
        if (!conversationId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return null
        return File(File(machineDir(account, botId), "chats"), "$conversationId.json")
    }

    private fun pruneChats(dir: File?) {
        val files = dir?.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_CHATS) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_CHATS).forEach { it.delete() }
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 一台机器的快照（只有数据，没有连接状态） */
    data class Snapshot(
        val savedAt: Long,
        val server: ServerInfo?,
        val summaries: List<ConversationSummary>,
        val engines: List<EngineInfo>,
        val defaultEngineID: String?,
        val defaultTeamMode: String?,
        val directories: List<RecentDirectory>?,
        val assistant: AssistantState?,
        val assistantEntries: List<JournalEntry>,
    ) {
        /** 摆进界面的样子：连接状态是「没连上」，cachedAt 标着是哪会儿的内容 */
        fun toState(): FridayState = FridayState(
            summaries = summaries,
            engines = engines,
            defaultEngineID = defaultEngineID,
            defaultTeamMode = defaultTeamMode,
            directories = directories,
            assistant = assistant?.copy(reacting = false, reactingEntryID = null),
            assistantEntries = assistantEntries,
            lastServer = server,
            cachedAt = savedAt,
        )

        companion object {
            /** 从实时状态里取要存的那些 */
            fun of(state: FridayState, server: ServerInfo?): Snapshot = Snapshot(
                savedAt = System.currentTimeMillis(),
                server = server,
                summaries = state.summaries,
                engines = state.engines,
                defaultEngineID = state.defaultEngineID,
                defaultTeamMode = state.defaultTeamMode,
                directories = state.directories,
                assistant = state.assistant,
                assistantEntries = state.assistantEntries.filterNot { it.streaming }.takeLast(MAX_ENTRIES),
            )
        }
    }

    // MARK: 编码（和各模型的 fromJson 对应）

    private fun encodeSnapshot(s: Snapshot): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("savedAt", s.savedAt)
        s.server?.let { put("server", encodeServer(it)) }
        put("summaries", JsonArray(s.summaries.map(::encodeSummary)))
        put("engines", buildJsonObject {
            put("engines", JsonArray(s.engines.map(::encodeEngine)))
            s.defaultEngineID?.let { put("defaultEngineID", it) }
        })
        s.defaultTeamMode?.let { put("defaultTeamMode", it) }
        s.directories?.let { list ->
            put("directories", buildJsonObject {
                put("recent", JsonArray(list.map { d -> buildJsonObject { put("path", d.path); put("exists", d.exists) } }))
            })
        }
        s.assistant?.let { put("assistant", encodeAssistant(it)) }
        put("assistantEntries", JsonArray(s.assistantEntries.map(::encodeEntry)))
    }

    private fun decodeSnapshot(o: JsonObject): Snapshot {
        val engines = (o["engines"] as? JsonObject)?.let { EnginesState.fromJson(it) }
        return Snapshot(
            savedAt = o.long("savedAt") ?: 0,
            server = (o["server"] as? JsonObject)?.let { ServerInfo.fromJson(it) },
            summaries = (o["summaries"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(ConversationSummary::fromJson) }.orEmpty(),
            engines = engines?.engines.orEmpty(),
            defaultEngineID = engines?.defaultEngineID,
            defaultTeamMode = o.str("defaultTeamMode"),
            directories = (o["directories"] as? JsonObject)?.let { d ->
                (d["recent"] as? JsonArray)?.mapNotNull { e ->
                    (e as? JsonObject)?.let { RecentDirectory(it.str("path") ?: return@let null, it.bool("exists") ?: true) }
                }.orEmpty()
            },
            assistant = (o["assistant"] as? JsonObject)?.let { AssistantState.fromJson(it) },
            assistantEntries = (o["assistantEntries"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(JournalEntry::fromJson) }.orEmpty(),
        )
    }

    private fun encodeChat(chat: ChatState): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("conversation", buildJsonObject {
            put("id", chat.id)
            put("title", chat.title)
            put("nodes", JsonArray(chat.order.mapNotNull { chat.nodes[it] }.map(::encodeMessage)))
            put("selectedChildren", JsonObject(chat.selectedChildren.mapValues { JsonPrimitive(it.value) }))
            chat.workingDirectory?.let { put("workingDirectory", it) }
        })
        put("order", JsonArray(chat.order.map { JsonPrimitive(it) }))
    }

    companion object {
        const val VERSION = 1
        const val STATE_FILE = "state.json"
        const val MAX_CHATS = 15
        const val MAX_ENTRIES = 200
        const val MAX_CHAT_CHARS = 6_000_000

        fun encodeSummary(s: ConversationSummary): JsonObject = buildJsonObject {
            put("id", s.id)
            put("title", s.title)
            put("updatedAt", s.updatedAt)
            put("pinned", s.pinned)
            put("isEmpty", s.isEmpty)
            put("hasDraft", s.hasDraft)
            opt("workingDirectory", s.workingDirectory)
            opt("workingDirectoryName", s.workingDirectoryName)
            opt("permissionMode", s.permissionMode)
            opt("engineID", s.engineID)
            opt("thinking", s.thinking)
            opt("teamMode", s.teamMode)
        }

        fun encodeEngine(e: EngineInfo): JsonObject = buildJsonObject {
            put("id", e.id)
            put("title", e.title)
            put("model", e.model)
            if (e.disabled) put("disabled", true)
            if (e.baseURL.isNotEmpty()) put("baseURL", e.baseURL)
            if (e.apiKeySecret.isNotEmpty()) put("apiKeySecret", e.apiKeySecret)
        }

        fun encodeServer(s: ServerInfo): JsonObject = buildJsonObject {
            put("version", s.version)
            put("platform", s.platform)
            put("hostname", s.hostname)
            put("user", s.user)
            put("dataDir", s.dataDir)
            opt("host", s.host)
            opt("defaultWorkingDirectory", s.defaultWorkingDirectory)
            put("features", JsonArray(s.features.map { JsonPrimitive(it) }))
        }

        /** 助手状态：设置从服务端给的原始 JSON 出发，叠上本地改过的几项（乐观更新改的是解析后的字段） */
        fun encodeAssistant(a: AssistantState): JsonObject = buildJsonObject {
            put("enabled", a.enabled)
            put("reacting", false)
            opt("brainEngineTitle", a.brainEngineTitle)
            val config = (a.config.raw ?: JsonObject(emptyMap())).toMutableMap()
            config["enabled"] = JsonPrimitive(a.config.enabled)
            config["name"] = JsonPrimitive(a.config.name)
            config["pushRule"] = JsonPrimitive(a.config.pushRule)
            config["brainThinking"] = JsonPrimitive(a.config.brainThinking)
            config["brainEngineID"] = a.config.brainEngineID?.let { JsonPrimitive(it) } ?: JsonNull
            config["sessionEngineID"] = a.config.sessionEngineID?.let { JsonPrimitive(it) } ?: JsonNull
            put("config", JsonObject(config))
            put("engines", buildJsonArray {
                a.engines.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("title", e.title)
                        put("model", e.model)
                        put("supportsTools", e.supportsTools)
                    })
                }
            })
        }

        fun encodeEntry(e: JournalEntry): JsonObject = buildJsonObject {
            put("id", e.id)
            put("at", e.at)
            put("kind", e.kind)
            opt("source", e.source)
            put("text", e.text)
            if (e.projects.isNotEmpty()) put("projects", JsonArray(e.projects.map { JsonPrimitive(it) }))
            if (e.interrupted) put("interrupted", true)
            e.data?.let { put("data", it) }
        }

        fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
            put("id", m.id)
            put("role", m.role)
            put("blocks", JsonArray(m.blocks.map { b -> buildJsonObject { put("id", b.id); put("kind", b.kind) } }))
            put("createdAt", m.createdAt)
            opt("error", m.error)
            opt("notice", m.notice)
            opt("parentID", m.parentID)
            if (m.stoppedByUser) put("stoppedByUser", true)
            if (m.interjected) put("interjected", true)
            opt("fromAgent", m.fromAgent)
            opt("sentBy", m.sentBy)
            if (m.quotaWait) put("quotaWait", buildJsonObject {})
            m.stats?.let { s ->
                put("stats", buildJsonObject {
                    put("tokensPerSecond", s.tokensPerSecond)
                    put("tokenCount", s.tokenCount)
                    opt("engineName", s.engineName)
                })
            }
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.opt(key: String, value: String?) {
            if (value != null) put(key, value)
        }
    }
}
