package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PhoneConversationTest {
    private val request = MessageRequest("qq", "mailbox", "测试联系人", "固定正文", false)
    private val sent = PhoneRun(id = "original-conversation", goal = "发送固定正文", status = RunStatus.COMPLETED,
        message = "已核对新增消息", messageRequest = request, plannerRequests = 7, replans = 1, elapsedMs = 12345,
        steps = listOf(PhoneStep(1, PhoneAction("old-screen-secret", PhoneActionType.SEND_MESSAGE, "发送",
            nodeId = "old-node-secret"), dispatched = true, dispatchAttempted = true)), messageOutcome = MessageOutcome.VERIFIED)

    @Test fun `terminal followups preserve conversation and reset only the execution`() {
        for (status in listOf(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)) {
            val previous = sent.copy(status = status)
            val next = PhoneConversation.nextTurn(previous, "那是之前发的，再发一次", setOf("mailbox"))
            assertEquals(previous.id, next.id)
            assertNotEquals(previous.executionId, next.executionId)
            assertEquals(listOf(previous.archiveTurn()), next.previousTurns)
            assertEquals(RunStatus.RUNNING, next.status)
            assertTrue(next.steps.isEmpty()); assertTrue(next.followUps.isEmpty())
            assertNull(next.messageRequest); assertNull(next.messageOutcome)
            assertEquals(0, next.plannerRequests); assertEquals(0, next.replans); assertEquals(0L, next.elapsedMs)
        }
        for (status in listOf(RunStatus.IDLE, RunStatus.RUNNING, RunStatus.WAITING_APPROVAL)) {
            assertFailsWith<IllegalArgumentException> { PhoneConversation.nextTurn(sent.copy(status = status), "继续", emptySet()) }
        }
    }

    @Test fun `legacy records decode and multiple rounds survive serialization without losing clarification`() {
        val legacy = Json.decodeFromString<PhoneRun>("""{"id":"legacy","goal":"发消息","status":"PAUSED","followUps":[{"text":"QQ","afterStep":1}]}""")
        val next = PhoneConversation.nextTurn(legacy, "继续", setOf("mailbox")).copy(status = RunStatus.COMPLETED)
        val third = PhoneConversation.nextTurn(next, "再解释一下", setOf("mailbox"))
        val loaded = Json.decodeFromString<PhoneRun>(Json.encodeToString(third))
        assertEquals(third, loaded)
        assertEquals("legacy", loaded.id)
        assertEquals(listOf("发消息", "继续"), loaded.previousTurns.map { it.goal })
        assertEquals("QQ", loaded.previousTurns.first().followUps.single().text)
        assertEquals(3, (loaded.previousTurns.map { it.executionId } + loaded.executionId).distinct().size)
    }

    @Test fun `both prompt variants include same conversation references without historical nodes`() {
        val input = PlannerInput("那是之前发的，再发一次", ScreenSnapshot("now", "mailbox", 0, "f", emptyList()),
            mapOf("mailbox" to "信箱"), emptyList(), previousTurns = listOf(sent.archiveTurn()))
        for (prompt in listOf(PhonePrompt.user(input), PhonePrompt.compactUser(input))) {
            assertContains(prompt, input.goal); assertContains(prompt, "测试联系人"); assertContains(prompt, "固定正文")
            assertContains(prompt, "sendAttempted"); assertFalse(prompt.contains("old-screen-secret")); assertFalse(prompt.contains("old-node-secret"))
        }
        val older = (0..20).map { sent.copy(id = "c$it", messageRequest = null, goal = "第${it}轮" + "长".repeat(1900)).archiveTurn() }
        val bounded = PhoneConversation.context(listOf(sent.archiveTurn()) + older)
        assertContains(bounded, "测试联系人"); assertContains(bounded, "第20轮")
        assertFalse(bounded.contains("第0轮")); assertTrue(bounded.length < 14_000)
        assertEquals("", PhoneConversation.context(emptyList()))
    }

    @Test fun `followup answer gets history but never inherits prepared send or dispatches old steps`() = runBlocking {
        var executions = 0
        val runs = mutableListOf<PhoneRun>()
        val screen = ScreenSnapshot("new", "mailbox", 0, "f", emptyList())
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = screen
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { executions++; return true }
            override suspend fun awaitChange() = Unit
        }
        val planner = PhonePlanner { input ->
            assertEquals("谢谢，不用再发了", input.goal)
            assertEquals(listOf(sent.archiveTurn()), input.previousTurns)
            assertTrue(input.steps.isEmpty()); assertNull(input.messageRequest)
            PhoneAction(screen.id, PhoneActionType.RESPOND, "回应用户", text = "好的")
        }
        PhoneRunner(planner, driver, { _, _ -> error("No approval expected") }, runs::add, confirmEveryAction = false)
            .run("谢谢，不用再发了", mapOf("mailbox" to "信箱"), continueFrom = sent)
        assertEquals(0, executions); assertEquals(sent.id, runs.last().id)
        assertEquals(RunStatus.COMPLETED, runs.last().status)
        assertEquals(1, runs.last().previousTurns.size)
    }

    @Test fun `explicit repeat can send once despite earlier send and verifies new bubble`() = runBlocking {
        val runs = mutableListOf<PhoneRun>()
        var sends = 0; var plans = 0
        fun page() = ScreenSnapshot("current-$sends", "mailbox", 0, "f-$sends", listOf(
            ScreenNode("who", request.recipient, contextHeader = true),
            ScreenNode("input", if (sends == 0) request.body else "", editable = true),
            ScreenNode("send", "发送", clickable = true), ScreenNode("existing", request.body)
        ) + if (sends == 1) listOf(ScreenNode("new-message", request.body)) else emptyList())
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = page()
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { sends++; return true }
            override suspend fun awaitChange() = Unit
        }
        val planner = PhonePlanner { input ->
            plans++
            assertEquals(sent.archiveTurn(), input.previousTurns.single())
            if (input.messageRequest == null) PhoneAction(input.screen.id, PhoneActionType.PREPARE_MESSAGE, "沿用用户明确指代的消息",
                text = request.body, packageName = request.packageName, channel = request.channel, recipient = request.recipient, draftOnly = false)
            else PhoneAction(input.screen.id, PhoneActionType.SEND_MESSAGE, "按新指令再发送一次", nodeId = "send", recipientNodeId = "who", inputNodeId = "input")
        }
        PhoneRunner(planner, driver, { _, _ -> error("No approval expected") }, runs::add, confirmEveryAction = false)
            .run("那是之前发的，再发一次", mapOf("mailbox" to "信箱"), continueFrom = sent)
        val result = runs.last()
        assertEquals(1, sends); assertEquals(2, plans); assertEquals(sent.id, result.id)
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(MessageOutcome.VERIFIED, result.messageOutcome)
        assertEquals(1, result.steps.single { it.action.type == PhoneActionType.SEND_MESSAGE }.matchingMessagesBefore)
    }

    @Test fun `repair merges all interactions and clarifications without manufacturing a send`() {
        val first = sent.copy(status = RunStatus.PAUSED, steps = emptyList(), messageOutcome = null,
            followUps = listOf(PhoneFollowUp("QQ", 1)))
        val second = PhoneRun("split1", "那是之前发的，再发一次", RunStatus.PAUSED, "请提供对象")
        val third = second.copy(id = "split2", message = "请提供正文")
        val merged = PhoneConversation.merge(listOf(first, second, third))
        assertEquals(first.id, merged.id); assertEquals(third.goal, merged.goal)
        assertEquals(listOf(first.archiveTurn(), second.archiveTurn()), merged.previousTurns)
        assertEquals(third.archiveTurn(), merged.archiveTurn())
        assertEquals(RunStatus.PAUSED, merged.status); assertTrue(merged.steps.isEmpty())
        assertFailsWith<IllegalArgumentException> { PhoneConversation.merge(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { PhoneConversation.merge(listOf(first, third.copy(status = RunStatus.RUNNING))) }
    }

    @Test fun `next input is saved before the initial settle can fail`() = runBlocking {
        val saved = mutableListOf<PhoneRun>()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>): ScreenSnapshot = error("not called")
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot) = false
            override suspend fun awaitChange(): Unit = error("connection lost")
        }
        assertFailsWith<IllegalStateException> {
            PhoneRunner({ error("not called") }, driver, { _, _ -> false }, saved::add, settleBeforePlanning = true)
                .run("新的后续输入", mapOf("mailbox" to "信箱"), continueFrom = sent)
        }
        assertEquals("新的后续输入", saved.single().goal)
        assertEquals(listOf(sent.archiveTurn()), saved.single().previousTurns)
    }
}
