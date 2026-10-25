package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * message.patch 的应用（REMOTE.md §5「增量消息」，v2.3 起）。
 * 与 Qt MessagePatch.cpp / 桌面版 MessagePatcher.ts 逐行对齐：
 * 一层块的补丁 = ids（这层完整顺序）+ set（整块换掉）+ tools（工具块分开比，递归 transcript）。
 * 打不上（基线对不上、引用了不存在的块）返回 null，调用方走 message.get 拿整条。
 */

data class BlocksPatch(
    val ids: List<String>?,
    val set: List<MessageBlock>?,
    val tools: List<ToolPatch>?,
) {
    companion object {
        fun fromJson(o: JsonObject): BlocksPatch = BlocksPatch(
            ids = o.strListOrNull("ids"),
            set = (o["set"] as? JsonArray)?.mapNotNull {
                val b = it as? JsonObject ?: return@mapNotNull null
                val id = b.str("id") ?: return@mapNotNull null
                val kind = b["kind"] as? JsonObject ?: return@mapNotNull null
                MessageBlock(id, kind)
            },
            tools = (o["tools"] as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { t -> ToolPatch.fromJson(t) }
            },
        )
    }
}

data class ToolPatch(val id: String, val tool: ToolEvent?, val transcript: BlocksPatch?) {
    companion object {
        fun fromJson(o: JsonObject): ToolPatch = ToolPatch(
            id = o.str("id") ?: "",
            tool = (o["tool"] as? JsonObject)?.let { ToolEvent.fromJson(it) },
            transcript = (o["transcript"] as? JsonObject)?.let { BlocksPatch.fromJson(it) },
        )
    }
}

/** 一层块的补丁应用。返回 null = 打不上（调用方重新拉整条消息） */
fun applyBlocks(blocks: List<MessageBlock>, patch: BlocksPatch): List<MessageBlock>? {
    val index = HashMap<String, Int>()
    var order = mutableListOf<String>()
    blocks.forEachIndexed { i, b ->
        index[b.id] = i
        order.add(b.id)
    }

    val replaced = LinkedHashMap<String, MessageBlock>()
    patch.set?.forEach { b ->
        if (b.id.isNotEmpty()) replaced[b.id] = b
    }

    for (item in patch.tools ?: emptyList()) {
        if (item.id.isEmpty()) return null
        val base = replaced[item.id] ?: index[item.id]?.let { blocks.getOrNull(it) } ?: return null
        val rawTool = base.kind["tool"] ?: return null
        val baseTool = blockTool(base) ?: return null
        var event = baseTool
        if (item.tool != null) {
            // 服务端发来的工具字段不带 transcript，保留原有运行记录
            val incoming = item.tool
            event = incoming.copy(transcript = baseTool.transcript)
        }
        if (item.transcript != null) {
            val transcript = applyBlocks(event.transcript, item.transcript) ?: return null
            event = event.copy(transcript = transcript)
        }
        replaced[item.id] = MessageBlock(
            id = base.id,
            kind = buildJsonObject { put("tool", wrapLike(rawTool, Json.encodeToJsonElement(ToolEventSerializer, event))) }
        )
    }

    patch.ids?.let { ids -> order = ids.toMutableList() }

    val result = mutableListOf<MessageBlock>()
    for (id in order) {
        replaced[id]?.let {
            result.add(it)
            return@let
        }
        val at = index[id] ?: return null
        result.add(blocks[at])
    }
    return result
}

private fun wrapLike(original: JsonElement, value: JsonElement): JsonElement =
    if (original is JsonObject && original.containsKey("_0")) {
        buildJsonObject { put("_0", value) }
    } else value

private fun JsonObject.strListOrNull(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** ToolEvent 的手写序列化（把 transcript 块写回线上形状） */
private object ToolEventSerializer : kotlinx.serialization.KSerializer<ToolEvent> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("ToolEvent", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ToolEvent) {
        val json = buildJsonObject {
            put("id", value.id)
            put("name", value.name)
            put("arguments", value.arguments)
            put("status", value.status)
            put("result", value.result)
            value.title?.let { put("title", it) }
            value.preview?.let { put("preview", it) }
            if (value.queued) put("queued", true)
            if (value.stopped) put("stopped", true)
            value.agentName?.let { put("agentName", it) }
            if (value.transcript.isNotEmpty()) {
                put("transcript", JsonArray(value.transcript.map { b ->
                    buildJsonObject {
                        put("id", b.id)
                        put("kind", b.kind)
                    }
                }))
            }
        }
        encoder.encodeSerializableValue(kotlinx.serialization.serializer<JsonObject>(), json)
    }
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ToolEvent =
        ToolEvent.fromJson(decoder.decodeSerializableValue(kotlinx.serialization.serializer<JsonObject>()))
}

/** 把一条消息补丁应用到本地消息。返回 null = 打不上 */
fun applyMessagePatch(message: ChatMessage, fields: JsonObject?, blocksPatch: BlocksPatch?): ChatMessage? {
    var updated = message
    if (fields != null) {
        val rebuilt = ChatMessage.fromJson(fields)
        if (rebuilt.id != message.id) return null
        updated = rebuilt.copy(blocks = message.blocks)
    }
    if (blocksPatch != null) {
        val blocks = applyBlocks(updated.blocks, blocksPatch) ?: return null
        updated = updated.copy(blocks = blocks)
    }
    return updated
}
