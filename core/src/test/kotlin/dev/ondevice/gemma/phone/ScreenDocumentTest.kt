package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.*

class ScreenDocumentTest {
    private fun largeScreen(nodeCount: Int = 220): ScreenSnapshot {
        val nodes = (0 until nodeCount).map { i ->
            when (i) {
                0 -> ScreenNode("n0", "首页标题", clickable = false, contextHeader = true, depth = 0, region = "top", bounds = "[0,80,1080,180]")
                149 -> ScreenNode("n149", clickable = true, depth = 1, region = "mid", bounds = "[40,900,1040,1020]", resourceId = "app:id/row_bluetooth")
                150 -> ScreenNode("n150", "蓝牙设置", clickTargetId = "n149", depth = 2, region = "mid", bounds = "[80,920,400,990]")
                151 -> ScreenNode("n151", "已关闭", clickTargetId = "n149", depth = 2, region = "mid", checkable = true, checked = false, stateDescription = "已关闭")
                155 -> ScreenNode("n155", "[图片](1)", clickTargetId = "n149", depth = 2, region = "mid")
                nodeCount - 1 -> ScreenNode("n${nodeCount - 1}", "我的", clickable = true, depth = 0, region = "bottom", bounds = "[810,2200,1080,2340]")
                else -> ScreenNode("n$i", "条目 $i", clickable = i % 2 == 0, depth = if (i % 3 == 0) 1 else 2, region = "mid")
            }
        }
        return ScreenSnapshot("snap-large", "com.demo.app", 1234L, "fp-large", nodes)
    }

    @Test fun `small screen inlines completely while large screen shows top and bottom preview with omitted range`() {
        val small = ScreenSnapshot("snap-small", "com.demo.app", 1L, "fp", listOf(
            ScreenNode("n0", "确定", clickable = true, region = "bottom", bounds = "[100,1800,980,1920]"),
        ))
        val smallPrompt = ScreenDocument.promptSection(small)
        assertContains(smallPrompt, "complete=true")
        assertContains(smallPrompt, "[n0] click bottom [100,1800,980,1920] text=\"确定\"")

        val big = largeScreen(220)
        val bigPrompt = ScreenDocument.promptSection(big)
        assertContains(bigPrompt, "complete=false")
        assertContains(bigPrompt, "omitted=")
        assertContains(bigPrompt, "首页标题")
        assertContains(bigPrompt, "我的")
        assertFalse("蓝牙设置" in bigPrompt, "Middle nodes should stay in file and be read/grepped on demand")
    }

    @Test fun `writeToFile persists complete screen and prunes older snapshots`() {
        val dir = Files.createTempDirectory("lifebuddy-screen-doc").toFile()
        try {
            val big = largeScreen(220)
            val saved = ScreenDocument.writeToFile(big, dir)
            assertTrue(saved.isFile)
            val backed = big.copy(screenFilePath = saved.absolutePath)

            val grepResult = ScreenDocument.grep(backed, "蓝牙")
            assertContains(grepResult, "matches=1")
            assertContains(grepResult, "[n149] click")
            assertContains(grepResult, "> L152:")
            assertContains(grepResult, "蓝牙设置")
            assertContains(grepResult, "已关闭")

            // Regex metacharacters in literal UI text safely fall back to literal match.
            val literalGrep = ScreenDocument.grep(backed, "[图片](1)")
            assertContains(literalGrep, "matches=1")
            assertContains(literalGrep, "[n155]")

            // Pagination reads every line cleanly from the saved file.
            val page1 = ScreenDocument.read(backed, "1", "80")
            assertContains(page1, "lines=1-80/221 hasMore=true nextStartLine=81")
            val page2 = ScreenDocument.read(backed, "145", "20")
            assertContains(page2, "L151:")
            assertContains(page2, "蓝牙设置")

            // Prune keeps at most MAX_RETAINED_FILES files.
            repeat(15) { i ->
                val f = ScreenDocument.writeToFile(big.copy(id = "snap-$i"), dir)
                f.setLastModified(1_000_000L + i * 1_000L)
            }
            val remaining = dir.listFiles { f -> f.name.endsWith(".ui.txt") }?.size ?: 0
            assertTrue(remaining <= ScreenDocument.MAX_RETAINED_FILES, "remaining=$remaining")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `read tool schema and policy validate line range arguments`() {
        val args = buildJsonObject {
            put("snapshotId", "snap-large")
            put("reason", "分段阅读页面")
            put("startLine", "81")
            put("lineCount", "60")
        }
        val decoded = PhoneTools.decode("phone_read_screen", args)
        assertEquals(PhoneActionType.READ_SCREEN, decoded.type)
        assertEquals("81", decoded.startLine)
        assertEquals("60", decoded.lineCount)

        val screen = largeScreen(20)
        assertNull(PhonePolicy.validate(decoded, screen, setOf("com.demo.app")))
        assertNull(PhonePolicy.validate(decoded.copy(startLine = "", lineCount = ""), screen, setOf("com.demo.app")))
        assertNotNull(PhonePolicy.validate(decoded.copy(startLine = "0"), screen, setOf("com.demo.app")))
        assertNotNull(PhonePolicy.validate(decoded.copy(lineCount = "500"), screen, setOf("com.demo.app")))
    }

    @Test fun `runner reuses frozen snapshot across screen read and grep and nudges after repeated grep misses`() = runBlocking<Unit> {
        val screen = largeScreen(220)
        var observes = 0
        var executed = 0
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>): ScreenSnapshot {
                observes++
                return screen.copy(id = "snap-$observes")
            }
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                executed++
                return true
            }
            override suspend fun awaitChange() = Unit
        }
        val states = mutableListOf<PhoneRun>()
        var turn = 0
        var sawFeedback = false
        PhoneRunner({ input ->
            when (turn++) {
                0 -> PhoneAction(input.screen.id, PhoneActionType.FIND_NODES, "查词1", query = "不存在的词A")
                1 -> PhoneAction(input.screen.id, PhoneActionType.FIND_NODES, "查词2", query = "不存在的词B")
                2 -> PhoneAction(input.screen.id, PhoneActionType.FIND_NODES, "查词3", query = "不存在的词C")
                3 -> {
                    sawFeedback = input.feedback.contains("phone_read_screen")
                    assertEquals("snap-1", input.screen.id)
                    PhoneAction(input.screen.id, PhoneActionType.READ_SCREEN, "分段读取", startLine = "148", lineCount = "10")
                }
                else -> {
                    assertContains(input.steps.last().observation, "蓝牙设置")
                    PhoneAction(input.screen.id, PhoneActionType.FINISH, "在完整页面文件中找到蓝牙设置", evidence = "蓝牙设置")
                }
            }
        }, driver, { _, _ -> true }, states::add, confirmEveryAction = false).run("找蓝牙设置", mapOf("com.demo.app" to "Demo"))

        assertTrue(sawFeedback, "Runner should nudge planner to use phone_read_screen after 3 zero-match greps")
        assertEquals(2, observes, "Should observe once initially and once to verify FINISH, reusing frozen snapshot during reads")
        assertEquals(0, executed)
        assertEquals(RunStatus.COMPLETED, states.last().status)
    }

    @Test fun `legacy serialized snapshots and actions remain backward compatible`() {
        val oldSnapshot = Json.decodeFromString<ScreenSnapshot>(
            """{"id":"s1","packageName":"pkg","capturedAt":0,"fingerprint":"fp","nodes":[{"id":"n0","text":"hi"}]}"""
        )
        assertEquals("", oldSnapshot.screenFilePath)
        assertFalse(oldSnapshot.truncated)
        assertEquals(0, oldSnapshot.nodes.single().depth)
        assertEquals("", oldSnapshot.nodes.single().bounds)

        val oldAction = Json.decodeFromString<PhoneAction>(
            """{"snapshotId":"s1","type":"READ_SCREEN","reason":"read"}"""
        )
        assertEquals("", oldAction.startLine)
        assertEquals("", oldAction.lineCount)
    }
}
