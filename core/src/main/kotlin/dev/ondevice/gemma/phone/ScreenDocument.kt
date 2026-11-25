package dev.ondevice.gemma.phone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Serializes a complete [ScreenSnapshot] into a line-oriented tree document that can be saved to disk
 * and inspected with coding-agent style `read` (line ranges) and `grep` (keyword/regex with context).
 */
object ScreenDocument {
    const val DEFAULT_INLINE_MAX_LINES = 80
    const val DEFAULT_PREVIEW_TOP_LINES = 35
    const val DEFAULT_PREVIEW_BOTTOM_LINES = 10
    const val DEFAULT_READ_LINE_COUNT = 80
    const val MAX_READ_LINE_COUNT = 120
    const val DEFAULT_GREP_CONTEXT_LINES = 2
    const val MAX_GREP_GROUPS = 25
    const val MAX_TOOL_OUTPUT_CHARS = 9_500
    const val MAX_RETAINED_FILES = 10

    private val newlineRegex = Regex("\\r\\n|\\r|\\n")
    private val unsafeFileChars = Regex("[^A-Za-z0-9._-]")

    fun render(screen: ScreenSnapshot): String = buildString {
        append("# pkg=").append(screen.packageName)
            .append(" snapshot=").append(screen.id)
            .append(" nodes=").append(screen.nodes.size)
            .append(" truncated=").append(screen.truncated)
        for (node in screen.nodes) {
            append('\n')
            append(formatNode(node))
        }
    }

    fun formatNode(node: ScreenNode): String = buildString {
        val indent = "  ".repeat(node.depth.coerceIn(0, 10))
        append(indent).append('[').append(node.id).append(']')
        if (node.clickable) append(" click")
        else if (node.clickTargetId.isNotBlank()) append(" ->").append(node.clickTargetId)
        if (node.editable) append(" edit")
        if (node.canSubmitSearch) append(" search")
        if (node.scrollable) append(" scroll")
        if (node.longClickable) append(" long")
        if (node.checkable) append(if (node.checked) " check=on" else " check=off")
        if (node.selected) append(" sel")
        if (node.contextHeader) append(" hdr")
        if (node.rangeValue != null && node.rangeMin != null && node.rangeMax != null) {
            append(" range=").append(formatRange(node.rangeValue, node.rangeIsInteger))
                .append('/').append(formatRange(node.rangeMin, node.rangeIsInteger))
                .append("..").append(formatRange(node.rangeMax, node.rangeIsInteger))
            if (node.canSetProgress) append(" setProgress")
        }
        if (node.region.isNotBlank()) append(' ').append(node.region)
        if (node.bounds.isNotBlank()) append(' ').append(node.bounds)
        if (node.resourceId.isNotBlank()) append(" rid=").append(sanitizeSingleLine(node.resourceId))
        if (node.text.isNotBlank()) append(" text=\"").append(sanitizeSingleLine(node.text)).append('"')
        if (node.description.isNotBlank()) append(" desc=\"").append(sanitizeSingleLine(node.description)).append('"')
        if (node.hint.isNotBlank()) append(" hint=\"").append(sanitizeSingleLine(node.hint)).append('"')
        if (node.stateDescription.isNotBlank()) append(" state=\"").append(sanitizeSingleLine(node.stateDescription)).append('"')
    }

    fun writeToFile(screen: ScreenSnapshot, directory: File): File {
        if (!directory.exists()) directory.mkdirs()
        val safeId = screen.id.replace(unsafeFileChars, "_").ifBlank { "screen" }.take(64)
        val file = File(directory, "screen_$safeId.ui.txt")
        file.writeText(render(screen), Charsets.UTF_8)
        pruneOldFiles(directory, keep = MAX_RETAINED_FILES)
        return file
    }

    fun loadLines(screen: ScreenSnapshot): List<String> {
        val text = if (screen.screenFilePath.isNotBlank()) {
            runCatching {
                val file = File(screen.screenFilePath)
                if (file.isFile && file.canRead()) file.readText(Charsets.UTF_8) else null
            }.getOrNull()
        } else null
        return (text ?: render(screen)).split('\n')
    }

    fun promptSection(
        screen: ScreenSnapshot,
        inlineMaxLines: Int = DEFAULT_INLINE_MAX_LINES,
        previewTopLines: Int = DEFAULT_PREVIEW_TOP_LINES,
        previewBottomLines: Int = DEFAULT_PREVIEW_BOTTOM_LINES,
    ): String = buildString {
        val lines = loadLines(screen)
        val total = lines.size
        val fileLabel = displayFileName(screen)
        append("snapshotId=").append(screen.id)
            .append(" | packageName=").append(screen.packageName)
            .append(" | nodes=").append(screen.nodes.size)
            .append(" | lines=").append(total)
            .append(" | file=").append(fileLabel)
        if (screen.truncated) append(" | truncated=true")
        if (screen.localTime.isNotBlank()) append("\nlocalTime=").append(screen.localTime)
        if (screen.systemTargets.isNotEmpty()) append("\nsystemTargets=").append(Json.encodeToString(screen.systemTargets))
        if (screen.notice.isNotBlank()) append("\nnotice=").append(screen.notice)
        append('\n')
        if (total <= inlineMaxLines) {
            appendLine("[screen_file complete=true lines=1-$total/$total]")
            lines.forEachIndexed { idx, line -> append('L').append(idx + 1).append(": ").appendLine(line) }
        } else {
            val topEnd = previewTopLines.coerceIn(1, total)
            val bottomStart = (total - previewBottomLines + 1).coerceAtLeast(topEnd + 1)
            val omitted = (bottomStart - topEnd - 1).coerceAtLeast(0)
            appendLine("[screen_file complete=false lines=$total showing=L1..L$topEnd,L$bottomStart..L$total omitted=$omitted]")
            for (i in 0 until topEnd) {
                append('L').append(i + 1).append(": ").appendLine(lines[i])
            }
            if (omitted > 0) {
                appendLine("... (L${topEnd + 1}..L${bottomStart - 1} 共 $omitted 行未展开；使用 phone_find_nodes(query) 按关键词/正则 grep 并带上下文，或用 phone_read_screen(startLine=\"${topEnd + 1}\", lineCount=\"80\") 分段通读完整页面文件) ...")
            }
            for (i in (bottomStart - 1) until total) {
                append('L').append(i + 1).append(": ").appendLine(lines[i])
            }
        }
    }.trimEnd()

    fun read(screen: ScreenSnapshot, startLineArg: String = "", lineCountArg: String = ""): String {
        val lines = loadLines(screen)
        val total = lines.size
        val fileLabel = displayFileName(screen)
        val start = startLineArg.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = lineCountArg.trim().toIntOrNull()?.coerceIn(1, MAX_READ_LINE_COUNT) ?: DEFAULT_READ_LINE_COUNT
        if (start > total) {
            return "[screen_read] file=$fileLabel startLine=$start 超出总行数 totalLines=$total（请使用 1..$total 范围内的行号）"
        }
        val end = (start + count - 1).coerceAtMost(total)
        val body = buildString {
            var lastShown = start - 1
            for (lineNo in start..end) {
                val rendered = "L$lineNo: ${lines[lineNo - 1]}\n"
                if (length + rendered.length > MAX_TOOL_OUTPUT_CHARS && lastShown >= start) break
                append(rendered)
                lastShown = lineNo
            }
        }.trimEnd()
        val actualEnd = body.lineSequence().lastOrNull()?.substringAfter('L')?.substringBefore(':')?.toIntOrNull() ?: end
        val hasMore = actualEnd < total
        val header = buildString {
            append("[screen_read] file=").append(fileLabel)
                .append(" lines=").append(start).append('-').append(actualEnd).append('/').append(total)
                .append(" hasMore=").append(hasMore)
            if (hasMore) append(" nextStartLine=").append(actualEnd + 1)
            if (screen.truncated) append(" truncated=true")
        }
        return "$header\n$body"
    }

    fun grep(
        screen: ScreenSnapshot,
        query: String,
        contextLines: Int = DEFAULT_GREP_CONTEXT_LINES,
        maxGroups: Int = MAX_GREP_GROUPS,
    ): String {
        val trimmed = query.trim()
        val lines = loadLines(screen)
        val total = lines.size
        val fileLabel = displayFileName(screen)
        val firstNodeIndex = if (lines.firstOrNull()?.startsWith("# ") == true) 1 else 0
        val regexResult = runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }
        var isRegex = regexResult.isSuccess
        var pattern = regexResult.getOrElse { Regex(Regex.escape(trimmed), RegexOption.IGNORE_CASE) }
        var matchedIndices = (firstNodeIndex until lines.size).filter { pattern.containsMatchIn(lines[it]) }
        if (matchedIndices.isEmpty() && isRegex) {
            val literalPattern = Regex(Regex.escape(trimmed), RegexOption.IGNORE_CASE)
            val literalHits = (firstNodeIndex until lines.size).filter { literalPattern.containsMatchIn(lines[it]) }
            if (literalHits.isNotEmpty()) {
                pattern = literalPattern
                matchedIndices = literalHits
                isRegex = false
            }
        }
        if (matchedIndices.isEmpty()) {
            return "[screen_grep] file=$fileLabel query=\"$trimmed\" matches=0 totalLines=$total " +
                "（当前页面文件未匹配到该词；不要反复换词盲猜，可直接用 phone_read_screen 分段通读 L1..L$total，或滚动页面后再查）"
        }

        val ctx = contextLines.coerceIn(0, 5)
        val matchedSet = matchedIndices.toSet()
        // Merge overlapping [start..end] context windows just like `grep -n -C`.
        val windows = mutableListOf<IntRange>()
        for (idx in matchedIndices) {
            val lo = (idx - ctx).coerceAtLeast(firstNodeIndex)
            val hi = (idx + ctx).coerceAtMost(lines.lastIndex)
            val prev = windows.lastOrNull()
            if (prev != null && lo <= prev.last + 1) {
                windows[windows.lastIndex] = prev.first..maxOf(prev.last, hi)
            } else {
                windows += lo..hi
            }
        }

        val shownWindows = windows.take(maxGroups)
        var shownMatches = 0
        var charTruncated = false
        val body = buildString {
            for ((groupIndex, range) in shownWindows.withIndex()) {
                val block = buildString {
                    if (groupIndex > 0) appendLine("--")
                    for (i in range) {
                        val prefix = if (i in matchedSet) "> " else "  "
                        append(prefix).append('L').append(i + 1).append(": ").appendLine(lines[i])
                    }
                }
                if (length + block.length > MAX_TOOL_OUTPUT_CHARS && groupIndex > 0) {
                    charTruncated = true
                    break
                }
                append(block)
                shownMatches += range.count { it in matchedSet }
            }
        }.trimEnd()

        val omittedMatches = (matchedIndices.size - shownMatches).coerceAtLeast(0)
        val header = buildString {
            append("[screen_grep] file=").append(fileLabel)
                .append(" query=\"").append(trimmed).append('"')
                .append(" regex=").append(isRegex)
                .append(" matches=").append(matchedIndices.size)
                .append(" shown=").append(shownMatches)
                .append(" context=").append(ctx)
                .append(" totalLines=").append(total)
            if (omittedMatches > 0 || charTruncated) {
                append(" (还有 ").append(omittedMatches).append(" 处未展开，可缩小关键词或用 phone_read_screen 读取对应行号)")
            }
        }
        return "$header\n$body"
    }

    private fun displayFileName(screen: ScreenSnapshot): String =
        if (screen.screenFilePath.isNotBlank()) File(screen.screenFilePath).name else "screen_${screen.id}.ui.txt"

    private fun sanitizeSingleLine(value: String): String =
        newlineRegex.replace(value, "\\\\n")

    private fun formatRange(value: Float, isInteger: Boolean): String =
        if (isInteger) value.toInt().toString() else value.toString().removeSuffix(".0")

    private fun pruneOldFiles(directory: File, keep: Int) {
        runCatching {
            val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".ui.txt") } ?: return
            if (files.size <= keep) return
            files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
        }
    }
}
