package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页改助手设置：先在本地改、主机回的整份为准（不等 assistant.state 推过来——设置页上不订） */
class AssistantSettingsTest {

    private val assistant = AssistantState.fromJson(buildJsonObject {
        put("enabled", true)
        put("config", buildJsonObject {
            put("enabled", true)
            put("name", "小F")
            put("pushRule", "important")
            put("idleMinutes", 30)
        })
    })

    @Test
    fun `点了开关马上变，别的设置不动`() {
        val next = assistantWithConfig(assistant, changes = buildJsonObject { put("ambientCalls", true) })!!
        assertTrue(next.config.ambientCalls)
        assertEquals("小F", next.config.name)
        assertEquals(30, next.config.idleMinutes)
        assertTrue(next.enabled)
        val renamed = assistantWithConfig(next, changes = buildJsonObject { put("name", JsonPrimitive("星期五")) })!!
        assertEquals("星期五", renamed.config.name)
        assertTrue(renamed.config.ambientCalls)
    }

    @Test
    fun `主机回的整份为准：旧主机不认的项回的里没有，开关就回去`() {
        val tapped = assistantWithConfig(assistant, changes = buildJsonObject { put("ambientCalls", true) })
        val old = assistantWithConfig(tapped, confirmed = buildJsonObject { put("enabled", true); put("name", "小F") })!!
        assertFalse(old.config.ambientCalls)
        val new = assistantWithConfig(tapped, confirmed = buildJsonObject { put("enabled", true); put("ambientCalls", true) })!!
        assertTrue(new.config.ambientCalls)
        // 主机说助手关着：跟着关
        assertFalse(assistantWithConfig(tapped, confirmed = buildJsonObject { put("enabled", false) })!!.enabled)
    }

    @Test
    fun `没读到过助手、没有原始设置的不动`() {
        assertNull(assistantWithConfig(null, changes = buildJsonObject { put("ambientCalls", true) }))
        val bare = assistant.copy(config = assistant.config.copy(raw = null))
        assertSame(bare, assistantWithConfig(bare, changes = buildJsonObject { put("ambientCalls", true) }))
    }
}
