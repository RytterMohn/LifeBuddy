package dev.ondevice.gemma.phone

import kotlinx.coroutines.*
import kotlin.test.*

class PhoneRunnerTest {
    private val original = ScreenSnapshot("s", "notes", 0, "same", listOf(ScreenNode("n0", "保存", clickable = true)))
    private val tap = PhoneAction("s", PhoneActionType.TAP, "保存笔记", nodeId = "n0")

    private class Driver(var screen: ScreenSnapshot) : PhoneDriver {
        var executed = 0
        override suspend fun observe(allowed: Set<String>) = screen
        override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { executed++; return true }
        override suspend fun awaitChange() = Unit
    }

    @Test fun `denied action never reaches executor`() = runBlocking {
        val driver = Driver(original)
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ tap }, driver, { _, _ -> false }, states::add).run("save", mapOf("notes" to "Notes"))
        assertEquals(0, driver.executed)
        assertEquals(RunStatus.PAUSED, states.last().status)
    }

    @Test fun `screen changed during approval prevents execution`() = runBlocking {
        val driver = Driver(original)
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ tap }, driver, { _, _ -> driver.screen = original.copy(fingerprint = "changed${states.size}"); true }, states::add)
            .run("save", mapOf("notes" to "Notes"))
        assertEquals(0, driver.executed)
        assertContains(states.last().message, "页面已变化")
    }

    @Test fun `cancel while planning never executes`() = runBlocking {
        val driver = Driver(original)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            PhoneRunner({ entered.complete(Unit); awaitCancellation() }, driver, { _, _ -> true }, {})
                .run("save", mapOf("notes" to "Notes"))
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, driver.executed)
    }

    @Test fun `cancel during approval never executes`() = runBlocking {
        val driver = Driver(original)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            PhoneRunner({ tap }, driver, { _, _ -> entered.complete(Unit); awaitCancellation() }, {})
                .run("save", mapOf("notes" to "Notes"))
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, driver.executed)
    }

    @Test fun `execution intent is persisted before action`() = runBlocking {
        val states = mutableListOf<PhoneRun>()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = original
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                assertEquals(tap, states.last().steps.single().action)
                assertFalse(states.last().steps.single().dispatched)
                return true
            }
            override suspend fun awaitChange() = Unit
        }
        PhoneRunner({ tap }, driver, { _, _ -> true }, states::add, maxSteps = 1).run("save", mapOf("notes" to "Notes"))
        assertTrue(states.last().steps.single().dispatched)
        assertEquals(RunStatus.PAUSED, states.last().status)
        assertContains(states.last().message, "上限")
    }

    @Test fun `persistence failure aborts before any action`() = runBlocking {
        val driver = Driver(original)
        assertFailsWith<IllegalStateException> {
            PhoneRunner({ tap }, driver, { _, _ -> true }, { if (it.steps.isNotEmpty()) error("disk full") })
                .run("save", mapOf("notes" to "Notes"))
        }
        assertEquals(0, driver.executed)
    }

    @Test fun `model cannot finish without screen evidence`() = runBlocking {
        val states = mutableListOf<PhoneRun>()
        val driver = Driver(original)
        PhoneRunner({ PhoneAction("s", PhoneActionType.FINISH, "完成", evidence = "不存在") }, driver,
            { _, _ -> true }, states::add).run("save", mapOf("notes" to "Notes"))
        assertEquals(RunStatus.PAUSED, states.last().status)
        assertEquals(0, driver.executed)
        val rejected = states.last().steps.single()
        assertFalse(rejected.dispatched)
        assertEquals("不存在", rejected.action.evidence)
        assertContains(rejected.observation, "未执行")
    }

    @Test fun `format error replans before any device action and keeps feedback`() = runBlocking {
        val driver = Driver(original)
        val states = mutableListOf<PhoneRun>()
        var calls = 0
        PhoneRunner({ input ->
            if (calls++ == 0) throw PhonePlanningException("工具格式不完整")
            assertContains(input.feedback, "格式")
            PhoneAction(input.screen.id, PhoneActionType.FINISH, "页面显示保存", evidence = "保存")
        }, driver, { _, _ -> true }, states::add).run("查找保存按钮", mapOf("notes" to "Notes"))
        assertEquals(RunStatus.COMPLETED, states.last().status)
        assertEquals(1, states.last().replans)
        assertEquals(2, states.last().plannerRequests)
        assertEquals(0, driver.executed)
    }

    @Test fun `changed screen causes fresh planning and fresh approval never stale execution`() = runBlocking {
        val driver = Driver(original)
        val states = mutableListOf<PhoneRun>()
        var approvals = 0
        var plans = 0
        PhoneRunner({ input ->
            plans++
            tap.copy(snapshotId = input.screen.id, nodeId = input.screen.nodes.single().id)
        }, driver, { _, _ ->
            if (approvals++ == 0) driver.screen = original.copy(id = "new", fingerprint = "new", nodes = listOf(ScreenNode("new-node", "保存", clickable = true)))
            true
        }, states::add, maxSteps = 2).run("save", mapOf("notes" to "Notes"))
        assertEquals(2, plans); assertEquals(2, approvals); assertEquals(1, driver.executed)
        assertFalse(states.last().steps.first().dispatched)
        assertEquals("new-node", states.last().steps.last().action.nodeId)
        assertEquals(1, states.last().replans)
    }

    @Test fun `same submission on unchanged screen executes only once`() = runBlocking {
        val driver = Driver(original)
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ tap }, driver, { _, _ -> true }, states::add).run("save", mapOf("notes" to "Notes"))
        assertEquals(1, driver.executed)
        assertContains(states.last().message, "阻止重复提交")
    }

    @Test fun `planning retries have hard limit and network failures are not replayed`() = runBlocking {
        val driver = Driver(original)
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ throw PhonePlanningException("bad format") }, driver, { _, _ -> true }, states::add)
            .run("save", mapOf("notes" to "Notes"))
        assertEquals(3, states.last().plannerRequests)
        assertEquals(0, driver.executed)
        var requests = 0
        assertFailsWith<java.io.IOException> {
            PhoneRunner({ requests++; throw java.io.IOException("network") }, driver, { _, _ -> true }, {})
                .run("save", mapOf("notes" to "Notes"))
        }
        assertEquals(1, requests)
    }

    @Test fun `early action outcomes remain available after six steps`() {
        val steps = (1..15).map { i -> PhoneStep(i, tap.copy(reason = if (i == 1) "已处理编号 ORIGIN-42" else "步骤 $i"), true, "页面已变化") }
        val prompt = PhonePrompt.user(PlannerInput("继续", original, mapOf("notes" to "Notes"), steps))
        assertContains(prompt, "ORIGIN-42")
        assertContains(prompt, "步骤 15")
        assertContains(prompt, "dispatched")
    }
}
