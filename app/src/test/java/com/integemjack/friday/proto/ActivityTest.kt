package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 活动事件（任务状态）、update_task_state 参数、TeamState 的解析与合并 */
class ActivityTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `step 事件的目标和步骤在顶层，快照里没有的会话也建出来`() {
        val merged = mergeActivity(
            emptyList(),
            obj(
                """{"conversationID":"C1","kind":"step","seq":3,"title":"清理 C 盘","at":"2026-11-05T01:00:00Z",
                   "goal":"把 C 盘清出空间",
                   "steps":[{"text":"体检","status":"done"},{"text":"删缓存","status":"doing"},{"text":"汇报","status":"todo"}]}"""
            ),
        )!!
        val task = merged.single().task!!
        assertEquals("把 C 盘清出空间", task.goal)
        assertEquals(listOf("done", "doing", "todo"), task.steps.map { it.status })
        assertEquals(1, task.done)
        assertEquals("删缓存", task.current?.text)
        assertEquals("清理 C 盘", merged.single().title)
    }

    @Test
    fun `别的事件不动任务状态；工具、确认、结束各改各的`() {
        var list = mergeActivity(
            emptyList(),
            obj("""{"conversationID":"C1","kind":"step","goal":"g","steps":[{"text":"a","status":"doing"}]}"""),
        )!!
        list = mergeActivity(list, obj("""{"conversationID":"C1","kind":"started","by":"user"}"""))!!
        list = mergeActivity(list, obj("""{"conversationID":"C1","kind":"tool","tool":{"eventID":"e","tool":"run_shell","title":"执行命令","summary":"ls"}}"""))!!
        assertEquals("running", list.single().status)
        assertEquals("执行命令", list.single().currentToolTitle)
        assertEquals("g", list.single().task?.goal)

        list = mergeActivity(list, obj("""{"conversationID":"C1","kind":"approval","approvals":[{"eventID":"e2","tool":"run_shell","title":"删文件","summary":"rm"}]}"""))!!
        assertEquals("approval", list.single().status)
        assertEquals(1, list.single().approvals.size)
        list = mergeActivity(list, obj("""{"conversationID":"C1","kind":"approval","approvals":[]}"""))!!
        assertEquals("running", list.single().status)
        assertTrue(list.single().approvals.isEmpty())

        list = mergeActivity(list, obj("""{"conversationID":"C1","kind":"finished"}"""))!!
        assertEquals("idle", list.single().status)
        assertNull(list.single().currentToolTitle)
        assertEquals(1, list.single().task?.steps?.size)
    }

    @Test
    fun `update_task_state 的参数解出目标、步骤、约束；没流完的解不开`() {
        val task = TaskSnapshot.fromToolArguments(
            """{"goal":"发版","constraints":["不动 main"],"facts":["x"],
               "steps":[{"status":"done","text":"写 CHANGELOG"},{"status":"doing","text":"打 tag"}]}"""
        )!!
        assertEquals("发版", task.goal)
        assertEquals(listOf("不动 main"), task.constraints)
        assertEquals(1, task.done)
        assertEquals(false, task.finished)
        assertNull(TaskSnapshot.fromToolArguments("""{"goal":"发"""))
    }

    @Test
    fun `TeamState 的 run 解出名字、在做什么、步数和是不是后台的`() {
        val runs = TeamRun.listFrom(
            obj(
                """{"runs":[{"id":"r1","name":"清理执行员","parentKey":"C1","conversationID":"C1","background":true,"depth":1,
                   "running":true,"detached":true,"stopRequested":false,"activity":{"kind":"tool","title":"执行命令"},
                   "steps":5,"started":"2026-11-05T01:00:00Z","lastOutput":"删了 3 个","speed":12.3}]}"""
            )
        )
        val run = runs.single()
        assertEquals("清理执行员", run.name)
        assertEquals("C1", run.conversationID)
        assertTrue(run.background && run.detached && run.running)
        assertEquals("tool", run.activityKind)
        assertEquals("执行命令", run.activityTitle)
        assertEquals(5, run.steps)
    }

    @Test
    fun `runner 带着后台子 Agent 的会话`() {
        val runner = RunnerState.fromJson(obj("""{"generating":[],"stopping":[],"pendingApproval":[],"pendingQuestion":[],"backgroundAgents":["C1"]}"""))
        assertTrue(runner.hasBackground("C1"))
    }
}
