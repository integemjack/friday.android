package com.integemjack.friday.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 各家在线服务的余额 / 套餐用量（REMOTE.md §6「在线服务」services.usage）：模型菜单里每家模型下面那一句。
 * 文案照桌面端 lib/serviceUsage.ts 的 usageMenuLine（== Qt ServiceUsage::menuLine，关键短语逐字照抄）：
 * 「5 小时剩 91% · 每周剩 78%」「余额 ¥12.34」「余额不足，充值后恢复」「额度用完，15:20 恢复」「额度用完了」。
 */

/** 一行给用户看的（warning = 提醒色：额度用完、余额不足、欠费） */
data class UsageLine(val text: String, val warning: Boolean)

/** 手里记着的一家（按接口地址 + 密钥名）：在查 / 查到的那一句 / 查不到的原因 */
data class UsageInfo(
    val loading: Boolean = false,
    val line: UsageLine? = null,
    val error: String? = null,
    /** 这个地址没有能查的接口（不再提示「查不到」） */
    val supported: Boolean = true,
    val checkedAt: Long = 0,
)

/** 服务端认的键：接口地址去掉末尾的 /、小写 + `|` + 密钥名（== ServiceUsage::observedKey） */
fun usageKey(baseURL: String, secretName: String): String =
    baseURL.trim().lowercase().trimEnd('/') + "|" + secretName

/** 本机 / 局域网的地址（本地大模型、Ollama）：不查余额 */
fun isLocalBaseURL(baseURL: String): Boolean {
    val text = baseURL.trim().lowercase()
    if (!text.startsWith("http://") && !text.startsWith("https://")) return true
    val host = runCatching { java.net.URI(text).host?.lowercase() }.getOrNull() ?: return true
    if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0" || host.endsWith(".local")) return true
    if (host.startsWith("10.") || host.startsWith("192.168.")) return true
    val m = Regex("^172\\.(\\d+)\\.").find(host)
    if (m != null && (m.groupValues[1].toIntOrNull() ?: 0) in 16..31) return true
    return false
}

private class Window(
    val title: String,
    val used: Double?,
    val limit: Double?,
    val remaining: Double?,
    val remainingPercent: Double?,
    val resetAt: Instant?,
)

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** ISO 时间 / Unix 秒 / 毫秒 */
private fun parseTime(value: JsonPrimitive?): Instant? {
    val text = value?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { n ->
        val ms = when {
            n > 1e12 -> n
            n > 1e9 -> n * 1000
            else -> return null
        }
        return Instant.ofEpochMilli(ms.toLong())
    }
    return runCatching { Instant.parse(text) }.getOrNull()
}

private fun JsonObject.time(key: String): Instant? = parseTime(this[key] as? JsonPrimitive)

/** 「15:20」「明天 08:00」「11月1日 08:00」（== ServiceUsage::when） */
fun usageWhen(at: Instant, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val date = at.atZone(zone)
    val time = DateTimeFormatter.ofPattern("HH:mm").format(date)
    val today = now.atZone(zone).toLocalDate()
    val day: LocalDate = date.toLocalDate()
    return when (day) {
        today -> time
        today.plusDays(1) -> "明天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> if (day.year == today.year) "${day.monthValue}月${day.dayOfMonth}日 $time" else "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
    }
}

/** 「¥12.34」「$0.00」 */
private fun money(amount: Double, currency: String?): String {
    val symbol = when (currency) {
        "CNY" -> "¥"
        "USD" -> "$"
        else -> ""
    }
    return (if (amount < 0) "-" else "") + symbol + "%.2f".format(kotlin.math.abs(amount))
}

/** 这个窗口已用的比例 0–1（== ServiceUsage::usedFraction）；算不出返回 null */
private fun usedFraction(window: Window, now: Instant): Double? {
    val fraction = when {
        window.remainingPercent != null -> 1 - window.remainingPercent / 100
        window.limit != null && window.limit > 0 && window.remaining != null -> 1 - window.remaining / window.limit
        window.limit != null && window.limit > 0 && window.used != null -> window.used / window.limit
        else -> return null
    }
    if (window.resetAt != null && !window.resetAt.isAfter(now)) return 0.0
    return fraction.coerceIn(0.0, 1.0)
}

/** 窗口标题以英文字母结尾时加个空格（「tokens 剩」/「5 小时剩」） */
private fun spaced(title: String): String =
    if (title.isNotEmpty() && title.last().let { it.code < 0x80 && it.isLetter() }) "$title " else title

/**
 * services.usage 的回执 → 模型菜单里那一句（== usageMenuLine）。没有可说的返回 null。
 */
fun usageMenuLine(result: JsonObject, now: Instant = Instant.now()): UsageLine? {
    val report = result["report"] as? JsonObject
    val observed = result["observed"] as? JsonObject

    // 额度用完 / 余额不足还没过去（== activeQuota，quotaCleared 取简化版：用完后又查到能用就算过去了）
    val quotaAt = observed?.time("quotaAt")
    if (quotaAt != null) {
        val okAt = observed.time("okAt")
        val fetchedAt = report?.time("fetchedAt")
        val cleared = (okAt != null && okAt.isAfter(quotaAt)) ||
            (fetchedAt != null && fetchedAt.toEpochMilli() - quotaAt.toEpochMilli() >= 60_000 && report.flag("available") == true)
        if (!cleared) {
            if (observed.text("quotaKind") == "balance") return UsageLine("余额不足，充值后恢复", true)
            val resumeAt = observed.time("resumeAt")
            if (resumeAt != null) {
                if (resumeAt.isAfter(now)) return UsageLine("额度用完，${usageWhen(resumeAt, now)} 恢复", true)
            } else if (now.toEpochMilli() - quotaAt.toEpochMilli() < 7 * 86_400_000L) {
                return UsageLine("额度用完了", true)
            }
        }
    }
    if (report == null) return null

    // 套餐窗口：用得最多的两个，按原来的顺序
    val windows = (report["windows"] as? JsonArray)?.mapNotNull { element ->
        (element as? JsonObject)?.let {
            Window(
                title = it.text("title").orEmpty(),
                used = it.num("used"),
                limit = it.num("limit"),
                remaining = it.num("remaining"),
                remainingPercent = it.num("remainingPercent"),
                resetAt = it.time("resetAt"),
            )
        }
    }.orEmpty()
    val picked = windows.mapIndexedNotNull { index, w -> usedFraction(w, now)?.let { Triple(w, it, index) } }
        .sortedWith(compareByDescending<Triple<Window, Double, Int>> { it.second }.thenBy { it.third })
        .take(2)
        .sortedBy { it.third }
    if (picked.isNotEmpty()) {
        picked.firstOrNull { it.second >= 1 }?.let { (w, _, _) ->
            val reset = w.resetAt?.takeIf { it.isAfter(now) }?.let { "，${usageWhen(it, now)} 重置" }.orEmpty()
            return UsageLine("${w.title}用完了$reset", true)
        }
        return UsageLine(
            picked.joinToString(" · ") { (w, used, _) -> "${spaced(w.title)}剩 ${Math.round((1 - used) * 100)}%" },
            false,
        )
    }
    (report["warnings"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.let {
        return UsageLine(it, true)
    }
    val parts = mutableListOf<String>()
    val unavailable = report.flag("available") == false
    var warning = unavailable
    val balance = (report["balances"] as? JsonArray)?.firstOrNull() as? JsonObject
    val spent = report.num("spent")
    if (balance != null && balance.num("amount") != null) {
        val currency = balance.text("currency")
        parts += "余额 ${money(balance.num("amount")!!, currency)}"
        val owed = balance.num("owed")
        if (owed != null && owed > 0) {
            parts += "欠费 ${money(owed, currency)}"
            warning = true
        }
    } else if (spent != null) {
        parts += "累计已用 ${money(spent, "USD")}"
    }
    if (unavailable) parts += "不够调用了"
    if (parts.isEmpty()) return null
    return UsageLine(parts.joinToString("，"), warning)
}
