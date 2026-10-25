package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC 2.0 客户端（REMOTE.md §3/§4）：请求/回执按 id 配对、超时、事件分发。
 * 传输层负责分帧（SSH 按行），本层只处理文本消息。
 */
class RpcClient(
    private val sendFn: (String) -> Unit,
    private val closeFn: () -> Unit,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    /** 事件通道（method → params），缓冲无上限，由 FridayClient 消费 */
    val events = kotlinx.coroutines.channels.Channel<Pair<String, JsonObject>>(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)

    class RpcTimeout(method: String) : Exception("请求 $method 超时")

    /** 收到传输层一行/一帧 */
    fun handleMessage(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        if (msg["jsonrpc"]?.let { (it as? JsonPrimitive)?.content } != "2.0") return
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
        if (method != null) {
            // 事件（无 id）；反向请求（带 id）手机端暂不处理，回 -32601
            val hasId = msg.containsKey("id")
            if (!hasId) {
                events.trySend(method to (msg["params"] as? JsonObject ?: JsonObject(emptyMap())))
            } else {
                val id = msg["id"]
                runCatching {
                    sendFn(Json.encodeToString(kotlinx.serialization.serializer<JsonObject>(), buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id ?: JsonPrimitive(0))
                        put("error", buildJsonObject {
                            put("code", RpcErrors.MethodNotFound)
                            put("message", "手机端不认识的方法 $method")
                        })
                    }))
                }
            }
            return
        }
        val id = (msg["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
        val entry = pending.remove(id) ?: return
        val error = msg["error"] as? JsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: RpcErrors.Internal
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "错误 $code"
            entry.completeExceptionally(RpcException(code, message))
        } else {
            entry.complete(msg["result"] ?: JsonObject(emptyMap()))
        }
    }

    /** 请求超时（REMOTE.md §3）：hello 30 秒、file.* 3 分钟、其余 60 秒 */
    private fun timeoutFor(method: String): Long = when {
        method == "hello" -> 30_000
        method.startsWith("file.") -> 180_000
        else -> 60_000
    }

    suspend fun request(method: String, params: JsonObject? = null, timeoutMs: Long? = null): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        try {
            sendFn(Json.encodeToString(kotlinx.serialization.serializer<JsonObject>(), payload))
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
        return try {
            withTimeout(timeoutMs ?: timeoutFor(method)) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pending.remove(id)
            throw RpcTimeout(method)
        }
    }

    fun notify(method: String, params: JsonObject? = null) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        sendFn(Json.encodeToString(kotlinx.serialization.serializer<JsonObject>(), payload))
    }

    /** 传输断开：所有在途请求立即失败 */
    fun failAll(error: Throwable) {
        for ((_, entry) in pending) entry.completeExceptionally(error)
        pending.clear()
        events.close()
    }

    fun close() {
        failAll(Exception("连接已关闭"))
        runCatching { closeFn() }
    }
}
