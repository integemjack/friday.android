package com.integemjack.friday.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 改文件的工具（`edit_file` / `write_file`）在参数里带着改了什么：手机上按它画 diff。
 * - `edit_file`：`{path, edits:[{old_text, new_text, replace_all?}]}`，老一点的是顶层 `old_text` / `new_text`；
 *   `new_text` 空着就是删掉那段
 * - `write_file`：`{path, content}`，整个文件写一遍（全算新加的）
 * 参数还在流式写、JSON 不完整时解不开，返回 null，等下一块补丁
 */
data class FileChange(val path: String, val hunks: List<Hunk>, val wholeFile: Boolean) {
    data class Hunk(val old: String, val new: String)

    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
}

enum class DiffKind { Same, Added, Removed }

data class DiffLine(val kind: DiffKind, val text: String)

private val FILE_TOOLS = setOf("edit_file", "write_file")

fun isFileTool(tool: ToolEvent): Boolean = tool.name.lowercase() in FILE_TOOLS

fun fileChange(tool: ToolEvent): FileChange? {
    val name = tool.name.lowercase()
    if (name !in FILE_TOOLS) return null
    val raw = tool.arguments.trim()
    if (raw.isEmpty()) return null
    val args = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val path = args.str("path").orEmpty()
    if (name == "write_file") {
        val content = args.str("content") ?: return null
        return FileChange(path, listOf(FileChange.Hunk("", content)), wholeFile = true)
    }
    val edits = (args["edits"] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let { FileChange.Hunk(it.str("old_text").orEmpty(), it.str("new_text").orEmpty()) }
    }.orEmpty()
    val single = if (args.containsKey("old_text") || args.containsKey("new_text")) {
        listOf(FileChange.Hunk(args.str("old_text").orEmpty(), args.str("new_text").orEmpty()))
    } else emptyList()
    val hunks = (edits + single).filter { it.old.isNotEmpty() || it.new.isNotEmpty() }
    if (hunks.isEmpty()) return null
    return FileChange(path, hunks, wholeFile = false)
}

/** 比太大的不逐行比（两边行数相乘超过这个）：整段旧的算删、整段新的算加 */
private const val MAX_DIFF_CELLS = 400_000L

private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return text.replace("\r\n", "\n").removeSuffix("\n").split('\n')
}

/** 逐行 diff（去掉首尾相同的行后按最长公共子序列比）：没变的、删掉的、新加的按文件里的顺序排 */
fun lineDiff(old: String, new: String): List<DiffLine> {
    val a = splitLines(old)
    val b = splitLines(new)
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
        endA--
        endB--
    }
    val out = ArrayList<DiffLine>(a.size + b.size)
    for (k in 0 until start) out += DiffLine(DiffKind.Same, a[k])
    val n = endA - start
    val m = endB - start
    if (n.toLong() * m > MAX_DIFF_CELLS) {
        for (k in start until endA) out += DiffLine(DiffKind.Removed, a[k])
        for (k in start until endB) out += DiffLine(DiffKind.Added, b[k])
    } else {
        // lcs[i][j]：a[start+i..endA) 和 b[start+j..endB) 的最长公共子序列长度
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[start + i] == b[start + j] -> {
                    out += DiffLine(DiffKind.Same, a[start + i]); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    out += DiffLine(DiffKind.Removed, a[start + i]); i++
                }
                else -> {
                    out += DiffLine(DiffKind.Added, b[start + j]); j++
                }
            }
        }
        while (i < n) out += DiffLine(DiffKind.Removed, a[start + i++])
        while (j < m) out += DiffLine(DiffKind.Added, b[start + j++])
    }
    for (k in endA until a.size) out += DiffLine(DiffKind.Same, a[k])
    return out
}

/** 加了几行、删了几行：服务端给了（linesAdded / linesRemoved）就用它的，没给的按参数自己数 */
fun fileLineCounts(tool: ToolEvent): Pair<Int, Int>? {
    if (!isFileTool(tool)) return null
    if (tool.linesAdded != null || tool.linesRemoved != null) return (tool.linesAdded ?: 0) to (tool.linesRemoved ?: 0)
    val change = fileChange(tool) ?: return null
    var added = 0
    var removed = 0
    for (hunk in change.hunks) {
        for (line in lineDiff(hunk.old, hunk.new)) {
            when (line.kind) {
                DiffKind.Added -> added++
                DiffKind.Removed -> removed++
                DiffKind.Same -> {}
            }
        }
    }
    return added to removed
}
