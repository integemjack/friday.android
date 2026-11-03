package com.integemjack.friday.schat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 读 JSON 的小抄：SChat 各端字段在「数字 / 字符串」「bool / 0-1」之间不统一（老服务端给字符串），
 * 这里两种都认，读不到就是 null，不抛异常。
 */
internal fun JsonObject.str(key: String): String? = runCatching {
    val element = this[key] ?: return null
    element.jsonPrimitive.contentOrNull
}.getOrNull()

internal fun JsonObject.long(key: String): Long? = runCatching {
    val element = this[key] ?: return null
    element.jsonPrimitive.longOrNull ?: element.jsonPrimitive.contentOrNull?.toLongOrNull()
}.getOrNull()

internal fun JsonObject.bool(key: String): Boolean? = runCatching {
    val element = this[key] ?: return null
    element.jsonPrimitive.booleanOrNull
        ?: when (element.jsonPrimitive.contentOrNull?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
}.getOrNull()