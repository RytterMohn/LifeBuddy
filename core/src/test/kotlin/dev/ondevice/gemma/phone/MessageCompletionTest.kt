package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MessageCompletionTest {
    private val request = MessageRequest("qq", "mailbox", "测试联系人", "固定正文", false)
    private val send = PhoneAction("before", PhoneActionType.SEND_MESSAGE, "发送一次", "send", recipientNodeId = "who", inputNodeId = "input")
    private val before = ScreenSnapshot("before", "mailbox", 0, "before", listOf(
        ScreenNode("who", request.recipient, contextHeader = true), ScreenNode("input", request.body, editable = true), ScreenNode("send", "发送", clickable = true)))
    private val after = before.copy(id = "after", fingerprint = "after", nodes = listOf(before.nodes.first(), ScreenNode("input", editable = true), ScreenNode("bubble", request.body)))

    @Test fun `delayed message appears without a second planner call or duplicate send`() = runBlocking {
        val states = mutableListOf<PhoneRun>()
        var clicks = 0; var waits = 0; var plans = 0; var approvals = 0
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = if (clicks == 0) before else if (waits < 2) after.copy(nodes = after.nodes.dropLast(1)) else after
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                assertTrue(states.last().steps.last().dispatchAttempted)
                assertFalse(states.last().steps.last().dispatched)
                clicks++; return true
            }
            override suspend fun awaitChange() { waits++ }
        }
        PhoneRunner({ plans++; check(plans == 1); send }, driver, { _, _ -> approvals++; true }, states::add).run("发送固定正文", mapOf("mailbox" to "信箱"), request)
        assertEquals(1, plans); assertEquals(1, clicks); assertEquals(1, approvals)
        assertEquals(RunStatus.COMPLETED, states.last().status)
        assertEquals(MessageOutcome.VERIFIED, states.last().messageOutcome)
        assertEquals(listOf(PhoneActionType.SEND_MESSAGE, PhoneActionType.VERIFY_MESSAGE, PhoneActionType.FINISH), states.last().steps.map { it.action.type })
    }

    @Test fun `unreadable result pauses as unconfirmed without planner mutations`() = runBlocking {
        val states = mutableListOf<PhoneRun>()
        var clicks = 0; var waits = 0; var plans = 0
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = if (clicks == 0) before else after.copy(nodes = after.nodes.dropLast(1))
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { clicks++; return true }
            override suspend fun awaitChange() { waits++ }
        }
        PhoneRunner({ plans++; send }, driver, { _, _ -> true }, states::add).run("发送固定正文", mapOf("mailbox" to "信箱"), request)
        val run = states.last()
        assertEquals(1, clicks); assertEquals(1, plans); assertTrue(waits <= 3)
        assertEquals(RunStatus.PAUSED, run.status); assertEquals(MessageOutcome.UNCONFIRMED, run.messageOutcome)
        assertContains(run.message, "结果待确认"); assertTrue(run.hasUnconfirmedSend()); assertFalse(run.canContinueWithReply())
    }

    @Test fun `accepted send is saved before a later screen read fails`() = runBlocking {
        val states = mutableListOf<PhoneRun>(); var clicked = false
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>): ScreenSnapshot { check(!clicked) { "screen gone" }; return before }
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { clicked = true; return true }
            override suspend fun awaitChange() = Unit
        }
        assertFailsWith<IllegalStateException> {
            PhoneRunner({ send }, driver, { _, _ -> true }, states::add).run("发送固定正文", mapOf("mailbox" to "信箱"), request)
        }
        assertTrue(states.last().steps.last().dispatched)
        assertTrue(states.last().steps.last().dispatchAttempted)
        assertTrue(states.last().hasUnconfirmedSend())
    }

    @Test fun `interrupted send intent blocks every further device mutation including drafting`() {
        val attempt = PhoneStep(1, send, dispatchAttempted = true)
        for (type in PhonePolicy.deviceActions + PhoneActionType.PREPARE_MESSAGE) {
            assertNotNull(PhonePolicy.validate(send.copy(type = type), before, setOf("mailbox"), request, listOf(attempt)), type.name)
        }
        assertNull(PhonePolicy.validate(send.copy(type = PhoneActionType.VERIFY_MESSAGE), before, setOf("mailbox"), request, listOf(attempt)))
        assertFalse(MessagePolicy.completed(after, request, listOf(attempt)))
    }

    @Test fun `old sent tasks never resume the prior body through a clarification reply`() {
        val asked = PhoneStep(2, send.copy(type = PhoneActionType.ASK_USER))
        val old = PhoneRun(status = RunStatus.PAUSED, steps = listOf(PhoneStep(1, send, dispatched = true), asked), messageRequest = request)
        assertTrue(old.hasUnconfirmedSend()); assertFalse(old.canContinueWithReply())
        assertTrue(old.copy(steps = listOf(asked)).canContinueWithReply())
        assertFalse(old.copy(status = RunStatus.COMPLETED).hasUnconfirmedSend())
    }

    @Test fun `exact accessibility description counts once but substrings and old bubbles cannot verify`() {
        val attempt = PhoneStep(1, send, dispatched = true, matchingMessagesBefore = 1)
        val old = after.copy(nodes = after.nodes.dropLast(1) + ScreenNode("old", description = request.body))
        assertFalse(MessagePolicy.completed(old, request, listOf(attempt)))
        val new = old.copy(nodes = old.nodes + ScreenNode("new", request.body, description = request.body))
        assertEquals(2, MessagePolicy.bodyCount(new, request)); assertTrue(MessagePolicy.completed(new, request, listOf(attempt)))
        assertFalse(MessagePolicy.completed(old.copy(nodes = old.nodes + ScreenNode("wrong", description = "消息：${request.body}")), request, listOf(attempt)))
    }
}
