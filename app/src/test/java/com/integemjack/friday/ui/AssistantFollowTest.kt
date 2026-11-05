package com.integemjack.friday.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import com.integemjack.friday.AppModel
import com.integemjack.friday.proto.AssistantState
import com.integemjack.friday.proto.ConnState
import com.integemjack.friday.proto.FridayState
import com.integemjack.friday.proto.JournalEntry
import com.integemjack.friday.proto.ServerInfo
import com.integemjack.friday.ui.theme.FridayTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 助手页打开 App 时停在最底下（FollowBottom.kt）：要动 Activity 的生命周期、模拟被系统收掉再恢复，
 * 所以单独一个类（UiRenderTest 的 createComposeRule 拿不到 Activity）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantFollowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val connected = ConnState.Connected(
        ServerInfo(
            version = "2.90", platform = "linux", hostname = "box", user = "u",
            dataDir = "/home/u/.friday", host = "server", defaultWorkingDirectory = null, features = emptyList(),
        )
    )
    private val assistant = AssistantState.fromJson(buildJsonObject { put("enabled", true); put("config", buildJsonObject { put("name", "小F") }) })

    private fun entry(i: Int) = JournalEntry(
        "e$i", "2026-11-05T%02d:%02d:00Z".format(i / 60, i % 60), if (i % 2 == 0) "assistant" else "user",
        null, "第 $i 句", emptyList(), false, false, null,
    )

    private fun model(count: Int) = AppModel(compose.activity.application).also {
        it.client.state.value = FridayState(conn = connected, assistant = assistant, assistantEntries = (1..count).map(::entry))
    }

    /** 按真的样子摆：主外壳 + 助手页 + 底部它的输入框 */
    private val page: @androidx.compose.runtime.Composable (AppModel) -> Unit = { model ->
        FridayTheme {
            CompositionLocalProvider(LocalAppModel provides model) {
                HomeScaffold(
                    tab = HomeTabs.ASSISTANT,
                    onTab = {},
                    dock = { AssistantComposer(remember { ComposerDraft() }) },
                    sessions = {},
                ) { AssistantScreen() }
            }
        }
    }

    /** 第 [n] 句在屏幕上、露在输入框上面（列表铺到输入框背后，光「排出来了」不算） */
    private fun assertAtBottom(n: Int) {
        compose.waitForIdle()
        assertTrue("第 $n 句没排出来：没停在最底下", compose.onAllNodesWithText("第 $n 句").fetchSemanticsNodes().isNotEmpty())
        val last = compose.onNodeWithText("第 $n 句").getBoundsInRoot()
        val input = compose.onNodeWithText("跟助手说点什么…").getBoundsInRoot()
        assertTrue("第 $n 句 ${last.bottom} 被输入框 ${input.top} 挡着", last.bottom <= input.top)
    }

    private fun scrollUp(to: Int) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(to)
        compose.waitForIdle()
    }

    @Test
    fun `往上翻过以后切到后台、再打开 App：回到最新，离开时来的也看得到`() {
        val model = model(100)
        compose.setContent { page(model) }
        assertAtBottom(100)

        scrollUp(0)
        assertFalse(compose.onAllNodesWithText("第 100 句").fetchSemanticsNodes().isNotEmpty())

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        model.client.state.value = model.client.state.value.copy(assistantEntries = (1..105).map(::entry))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertAtBottom(105)

        // 之后来了新的：接着跟
        model.client.state.value = model.client.state.value.copy(assistantEntries = (1..106).map(::entry))
        assertAtBottom(106)
    }

    @Test
    fun `被系统收掉、再打开时 Activity 重建：不恢复上次翻到的位置，停在最底下`() {
        val model = model(100)
        val tester = StateRestorationTester(compose)
        tester.setContent { page(model) }
        assertAtBottom(100)

        scrollUp(40)
        tester.emulateSavedInstanceStateRestore()
        assertAtBottom(100)
    }

    @Test
    fun `还在前台时往上翻着看，来了新的不把人拽下去`() {
        val model = model(100)
        compose.setContent { page(model) }
        scrollUp(40)
        model.client.state.value = model.client.state.value.copy(assistantEntries = (1..101).map(::entry))
        compose.waitForIdle()
        assertFalse(compose.onAllNodesWithText("第 101 句").fetchSemanticsNodes().isNotEmpty())
    }
}
