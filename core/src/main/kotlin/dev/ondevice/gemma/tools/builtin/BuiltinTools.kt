package dev.ondevice.gemma.tools.builtin

import dev.ondevice.gemma.tools.Tool
import dev.ondevice.gemma.tools.ToolExecutor
import dev.ondevice.gemma.tools.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 内置工具集：时间、计算器、系统信息。
 * 后续扩展（Web 搜索、备忘录等）按同样模式新增 Tool 即可。
 */
object BuiltinTools {

    fun all(): List<Tool> = listOf(
        timeTool(),
        calculatorTool(),
        systemInfoTool(),
    )

    private fun timeTool() = Tool(
        schema = ToolSchema(
            name = "get_time",
            description = "获取当前本地时间（年-月-日 时:分:秒）",
            parameters = buildJsonObject { /* 无参数 */ },
        ),
        executor = ToolExecutor { _ ->
            """{"now": "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}"}"""
        },
    )

    private fun calculatorTool() = Tool(
        schema = ToolSchema(
            name = "calculator",
            description = "计算数学表达式，支持 + - * / ( )，如 (3+5)*2",
            parameters = buildJsonObject {
                put("expression", buildJsonObject {
                    put("type", "string")
                    put("description", "要计算的数学表达式")
                })
            },
        ),
        executor = ToolExecutor { args ->
            val expr = args["expression"]?.jsonPrimitive?.content ?: return@ToolExecutor """{"error": "missing expression"}"""
            val result = Calculator.eval(expr)
                ?: return@ToolExecutor """{"error": "invalid expression: $expr"}"""
            """{"expression": "$expr", "result": $result}"""
        },
    )

    private fun systemInfoTool() = Tool(
        schema = ToolSchema(
            name = "system_info",
            description = "获取当前设备/运行环境信息（OS、架构、JVM）",
            parameters = buildJsonObject { /* 无参数 */ },
        ),
        executor = ToolExecutor {
            buildJsonObject {
                put("os", System.getProperty("os.name") ?: "unknown")
                put("arch", System.getProperty("os.arch") ?: "unknown")
                put("java", System.getProperty("java.version") ?: "unknown")
            }.toString()
        },
    )
}

/** 极简表达式求值器：仅支持数字与 + - * / ( )，无任意代码执行风险 */
object Calculator {
    fun eval(expression: String): Double? {
        val cleaned = expression
            .replace('，', ',')
            .replace('x', '*')
            .replace('×', '*')
            .replace('÷', '/')
            .replace("--", "+")
        if (!Regex("^[0-9+\\-*/().\\s]+$").matches(cleaned)) return null
        return try {
            Parser(cleaned).parse()
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skip()
            if (pos < s.length) throw IllegalArgumentException("trailing chars")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skip()
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                skip()
                when (peek()) {
                    '*' -> { pos++; v *= factor() }
                    '/' -> { pos++; v /= factor() }
                    else -> return v
                }
            }
        }

        private fun factor(): Double {
            skip()
            if (peek() == '(') {
                pos++
                val v = expr()
                skip()
                expect(')')
                return v
            }
            return number()
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("expected number")
            return s.substring(start, pos).toDouble()
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("expected '$c'")
            pos++
        }

        private fun skip() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
