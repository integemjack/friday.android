package com.integemjack.friday.data

import com.integemjack.friday.proto.AssistantState
import com.integemjack.friday.proto.ChatMessage
import com.integemjack.friday.proto.ChatState
import com.integemjack.friday.proto.ConversationSummary
import com.integemjack.friday.proto.EngineInfo
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.GenerationStats
import com.integemjack.friday.proto.JournalEntry
import com.integemjack.friday.proto.MessageBlock
import com.integemjack.friday.proto.RecentDirectory
import com.integemjack.friday.proto.ServerInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 本地快照：存进去的读回来要一模一样（走的是各模型现成的 fromJson） */
class StateCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = ServerInfo(
        version = "2.90", platform = "macOS", hostname = "JacksMacBook-Pro", user = "u",
        dataDir = "/d", host = "desktop", defaultWorkingDirectory = "/w", features = listOf("serviceUsage", "activity"),
    )

    private fun assistant(): AssistantState = AssistantState.fromJson(
        buildJsonObject {
            put("enabled", true)
            put("reacting", true)
            put("brainEngineTitle", "Claude")
            put("config", buildJsonObject {
                put("name", "小F")
                put("brainThinking", false)
                put("toolRounds", 12)
                put("somethingNew", "留着") // 不认识的设置也要原样带回去
            })
        }
    ).let { it.copy(config = it.config.copy(sessionEngineID = "E2", brainThinking = true)) }

    private fun state() = FridayState(
        summaries = listOf(
            ConversationSummary(
                id = "C1", title = "改代码", updatedAt = "2026-11-04T06:00:00Z", pinned = true, isEmpty = false,
                hasDraft = false, workingDirectory = "/w/p", workingDirectoryName = "p", engineID = "E1",
                thinking = "high", teamMode = "ultra",
            )
        ),
        engines = listOf(EngineInfo("E1", "智谱", "glm-4.6", baseURL = "https://x", apiKeySecret = "K"), EngineInfo("E2", "A", "b", disabled = true)),
        defaultEngineID = "E1",
        defaultTeamMode = "delegatesHeavy",
        directories = listOf(RecentDirectory("/w/p", true), RecentDirectory("/gone", false)),
        assistant = assistant(),
        assistantEntries = listOf(
            JournalEntry("j1", "2026-11-04T06:00:00Z", "user", "voice", "你好", listOf("p"), false, false, null),
            JournalEntry("j2", "2026-11-04T06:00:01Z", "assistant", null, "在呢", emptyList(), true, false, buildJsonObject { put("silent", false) }),
            JournalEntry("j3", "2026-11-04T06:00:02Z", "assistant", null, "说到一半", emptyList(), false, true, null),
        ),
    )

    @Test
    fun `整台机器的快照存进去读回来一样`() {
        val cache = StateCache(tmp.root)
        cache.save(7, 42, StateCache.Snapshot.of(state(), server))
        val back = cache.load(7, 42) ?: error("没读回来")
        val s = back.toState()

        assertEquals(state().summaries, s.summaries)
        assertEquals(state().engines, s.engines)
        assertEquals("E1", s.defaultEngineID)
        assertEquals("delegatesHeavy", s.defaultTeamMode)
        assertEquals(state().directories, s.directories)
        assertEquals(server, s.lastServer)
        assertNotNull(s.cachedAt)
        // 正在出字的那条不存；读回来的助手不算「在说」
        assertEquals(listOf("j1", "j2"), s.assistantEntries.map { it.id })
        assertEquals(state().assistantEntries.take(2), s.assistantEntries)
        val a = s.assistant!!
        assertFalse(a.speaking)
        assertEquals("小F", a.config.name)
        assertEquals("E2", a.config.sessionEngineID)
        assertTrue(a.config.brainThinking)
        assertEquals(12, a.config.toolRounds)
        assertEquals("留着", a.config.raw?.get("somethingNew")?.toString()?.trim('"'))
    }

    @Test
    fun `别的账号、别的机器读不到`() {
        val cache = StateCache(tmp.root)
        cache.save(7, 42, StateCache.Snapshot.of(state(), server))
        assertNull(cache.load(8, 42))
        assertNull(cache.load(7, 43))
    }

    private fun chat(id: String) = ChatState(
        id = id,
        title = "测试",
        nodes = listOf(
            ChatMessage("m1", "user", listOf(MessageBlock("b1", buildJsonObject { put("text", buildJsonObject { put("_0", "问") }) })), "2026-01-01T00:00:00Z"),
            ChatMessage(
                "m2", "assistant", listOf(MessageBlock("b2", buildJsonObject { put("text", buildJsonObject { put("_0", "答一") }) })),
                "2026-01-01T00:00:01Z", parentID = "m1", stats = GenerationStats(12.5, 300, "glm"), quotaWait = true,
            ),
            ChatMessage(
                "m3", "assistant", listOf(MessageBlock("b3", buildJsonObject { put("text", buildJsonObject { put("_0", "答二") }) })),
                "2026-01-01T00:00:02Z", parentID = "m1", interjected = true, error = "断了",
            ),
        ).associateBy { it.id },
        order = listOf("m1", "m2", "m3"),
        selectedChildren = mapOf("m1" to "m3"),
        workingDirectory = "/w/p",
    )

    @Test
    fun `会话内容存进去读回来一样，分支选的哪条也在`() {
        val cache = StateCache(tmp.root)
        cache.saveChat(7, 42, chat("C1"))
        val back = cache.loadChat(7, 42, "C1") ?: error("没读回来")
        assertEquals(chat("C1").nodes, back.nodes)
        assertEquals(listOf("m1", "m2", "m3"), back.order)
        assertEquals(mapOf("m1" to "m3"), back.selectedChildren)
        assertEquals("/w/p", back.workingDirectory)
        assertEquals(listOf("m1", "m3"), back.messages.map { it.id })
    }

    @Test
    fun `会话只留最近看过的那些`() {
        val cache = StateCache(tmp.root)
        repeat(StateCache.MAX_CHATS + 3) { i ->
            cache.saveChat(7, 42, chat("C$i"))
            File(tmp.root, "7-42/chats/C$i.json").setLastModified(1_000_000L + i * 1000)
        }
        cache.saveChat(7, 42, chat("C0")) // 又看了一次最早那段：它留下
        val left = File(tmp.root, "7-42/chats").listFiles()!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(StateCache.MAX_CHATS, left.size)
        assertTrue("C0" in left)
        assertFalse("C1" in left)
    }

    @Test
    fun `坏了的、版本不对的当没有；奇怪的会话 id 不拼进路径`() {
        val cache = StateCache(tmp.root)
        val file = File(tmp.root, "7-42/state.json").apply { parentFile!!.mkdirs(); writeText("{坏了") }
        assertNull(cache.load(7, 42))
        assertFalse(file.exists())
        file.writeText("""{"version":999}""")
        assertNull(cache.load(7, 42))
        cache.saveChat(7, 42, chat("../../evil"))
        assertNull(cache.loadChat(7, 42, "../../evil"))
        assertFalse(File(tmp.root, "evil.json").exists())
    }

    @Test
    fun `退出登录全删`() {
        val cache = StateCache(tmp.root)
        cache.save(7, 42, StateCache.Snapshot.of(state(), server))
        cache.saveChat(7, 42, chat("C1"))
        cache.clearAll()
        assertNull(cache.load(7, 42))
        assertNull(cache.loadChat(7, 42, "C1"))
    }
}
