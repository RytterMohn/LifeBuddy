package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class AutonomousOperationTest {
    private val request = MessageRequest("qq", "qq", "小王", "晚到十分钟", false)
    private val initial = ScreenSnapshot("s", "qq", 0, "initial", listOf(
        ScreenNode("who", "小王", contextHeader = true), ScreenNode("input", editable = true), ScreenNode("send", "发送", clickable = true)))
    private fun prepare(screen: ScreenSnapshot = initial, draft: Boolean = false) = PhoneAction(screen.id,
        PhoneActionType.PREPARE_MESSAGE, "整理用户的消息", text = request.body, packageName = "qq", channel = "qq", recipient = "小王", draftOnly = draft)

    @Test fun `natural language send executes once without approval and verifies locally`() = runBlocking<Unit> {
        var page = initial
        val states = mutableListOf<PhoneRun>()
        val executed = mutableListOf<PhoneActionType>()
        val approvals = mutableListOf<PhoneActionType>()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = page
            override suspend fun awaitChange() = Unit
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                assertEquals(request, states.last().messageRequest)
                executed += action.type
                page = when (action.type) {
                    PhoneActionType.TYPE -> page.copy(id = "typed", fingerprint = "typed", nodes = page.nodes.map { if (it.editable) it.copy(text = action.text) else it })
                    PhoneActionType.SEND_MESSAGE -> page.copy(id = "sent", fingerprint = "sent", nodes = initial.nodes + ScreenNode("bubble", request.body))
                    else -> error("Unexpected device action")
                }
                return true
            }
        }
        PhoneRunner({ input -> when (input.steps.size) {
            0 -> prepare(input.screen)
            1 -> { assertEquals(request, input.messageRequest); PhoneAction(input.screen.id, PhoneActionType.TYPE, "填写原文", nodeId = "input", text = request.body) }
            2 -> PhoneAction(input.screen.id, PhoneActionType.SEND_MESSAGE, "发送指定消息", nodeId = "send", recipientNodeId = "who", inputNodeId = "input")
            else -> PhoneAction(input.screen.id, PhoneActionType.FINISH, "已核对新消息", evidence = request.body)
        } }, driver, { action, _ -> approvals += action.type; true }, states::add, confirmEveryAction = false)
            .run("用 QQ 告诉小王晚到十分钟", mapOf("qq" to "QQ"))
        assertEquals(listOf(PhoneActionType.TYPE, PhoneActionType.SEND_MESSAGE), executed)
        assertTrue(approvals.isEmpty())
        assertTrue(states.none { it.status == RunStatus.WAITING_APPROVAL })
        assertEquals(RunStatus.COMPLETED, states.last().status)
        assertEquals(MessageOutcome.VERIFIED, states.last().messageOutcome)
    }

    @Test fun `natural language draft does not need form or confirmations and never sends`() = runBlocking<Unit> {
        val page = initial.copy(nodes = initial.nodes.map { if (it.editable) it.copy(text = request.body) else it })
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ input -> if (input.messageRequest == null) prepare(page, true) else PhoneAction(page.id, PhoneActionType.FINISH, "草稿已核对", evidence = request.body) },
            readDriver(page), { _, _ -> error("draft must not request confirmation") }, states::add, confirmEveryAction = false)
            .run("给小王准备消息，不发送", mapOf("qq" to "QQ"))
        assertTrue(states.last().messageRequest!!.draftOnly)
        assertEquals(RunStatus.COMPLETED, states.last().status)
        assertContains(states.last().message, "尚未发送")
    }

    @Test fun `typed preparation rejects string booleans and unauthorized recipients`() {
        val args = buildJsonObject {
            put("snapshotId", "s"); put("reason", "准备"); put("channel", "qq"); put("packageName", "qq")
            put("recipient", "小王"); put("text", "晚到十分钟"); put("draftOnly", false)
        }
        assertFalse(PhoneTools.decode("phone_prepare_message", args).draftOnly)
        assertFails { PhoneTools.decode("phone_prepare_message", JsonObject(args + ("draftOnly" to JsonPrimitive("false")))) }
        assertNotNull(PhonePolicy.validate(prepare().copy(packageName = "other"), initial, setOf("qq")))
        assertNotNull(PhonePolicy.validate(prepare().copy(channel = "sms", recipient = "10086?body=bad"), initial, setOf("qq")))
        assertNotNull(PhonePolicy.validate(prepare().copy(recipient = ""), initial, setOf("qq")))
    }

    @Test fun `send tools require preparation and matching visible draft even without compact prompts`() {
        val input = PlannerInput("给小王发送晚到十分钟", initial, mapOf("qq" to "QQ"), emptyList())
        val missing = PhoneTools.forState(input)
        assertTrue(PhoneActionType.PREPARE_MESSAGE in missing)
        assertFalse(PhoneActionType.SEND_MESSAGE in missing)
        assertFalse(PhoneActionType.COMPOSE_SMS in missing)
        assertFalse(PhoneActionType.SEND_MESSAGE in PhoneTools.forState(input.copy(messageRequest = request)))
        val ready = initial.copy(nodes=initial.nodes.map { if(it.editable) it.copy(text=request.body) else it })
        assertTrue(PhoneActionType.SEND_MESSAGE in PhoneTools.forState(input.copy(screen=ready, messageRequest = request)))
        assertFalse(PhoneActionType.SEND_MESSAGE in PhoneTools.forState(input.copy(messageRequest = request.copy(draftOnly = true))))
        val attempted = PhoneStep(1, PhoneAction("s", PhoneActionType.SEND_MESSAGE, "发送"), dispatchAttempted = true)
        val after = PhoneTools.forState(input.copy(messageRequest = request, steps = listOf(attempted)))
        assertTrue(PhonePolicy.deviceActions.none { it in after })
        assertFalse(PhoneActionType.PREPARE_MESSAGE in after)
    }

    @Test fun `wrong generic send click recovers through preparation without clicking or asking approval`() = runBlocking {
        val states = mutableListOf<PhoneRun>(); var plans = 0; var clicks = 0
        var page = initial.copy(nodes = initial.nodes.map { if (it.editable) it.copy(text = request.body) else it })
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = page
            override suspend fun awaitChange() = Unit
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                assertEquals(PhoneActionType.SEND_MESSAGE, action.type); clicks++
                page = initial.copy(id = "after", fingerprint = "after", nodes = initial.nodes + ScreenNode("new", request.body))
                return true
            }
        }
        PhoneRunner({ input -> when (plans++) {
            0 -> PhoneAction(input.screen.id, PhoneActionType.TAP, "发送消息", nodeId = "send")
            1 -> { assertContains(input.feedback, "phone_prepare_message"); prepare(input.screen) }
            else -> PhoneAction(input.screen.id, PhoneActionType.SEND_MESSAGE, "发送", nodeId = "send", recipientNodeId = "who", inputNodeId = "input")
        } }, driver, { _, _ -> error("must not ask approval") }, states::add, confirmEveryAction = false)
            .run("给小王发送晚到十分钟", mapOf("qq" to "QQ"))
        assertEquals(1, clicks); assertEquals(1, states.last().replans)
        assertFalse(states.last().steps.first().dispatched)
        assertEquals(RunStatus.COMPLETED, states.last().status)
    }

    @Test fun `preparation and direct answer cannot bypass an attempted send`() {
        val sent = PhoneStep(1, PhoneAction("s", PhoneActionType.SEND_MESSAGE, "send"), true)
        assertNotNull(PhonePolicy.validate(prepare(), initial, setOf("qq"), request, listOf(sent)))
        assertNotNull(PhonePolicy.validate(PhoneAction("s", PhoneActionType.RESPOND, "答复", text = "已成功发送"), initial, setOf("qq"), request, listOf(sent)))
    }

    @Test fun `clarification continues same history and preserves previous steps`() = runBlocking<Unit> {
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ PhoneAction("s", PhoneActionType.ASK_USER, "请告诉我具体内容？") }, readDriver(initial), { _, _ -> error("unexpected") }, states::add,
            confirmEveryAction = false).run("帮我准备一条消息", mapOf("qq" to "QQ"))
        val paused = Json.decodeFromString<PhoneRun>(Json.encodeToString(states.last()))
        val page = initial.copy(nodes = initial.nodes.map { if (it.editable) it.copy(text = request.body) else it })
        PhoneRunner({ input ->
            assertEquals("帮我准备一条消息", input.goal)
            assertEquals("给小王准备晚到十分钟，不发送", input.followUps.single().text)
            if (input.messageRequest == null) prepare(page, true) else PhoneAction(page.id, PhoneActionType.FINISH, "草稿", evidence = request.body)
        }, readDriver(page), { _, _ -> error("unexpected") }, states::add, confirmEveryAction = false)
            .run("给小王准备晚到十分钟，不发送", mapOf("qq" to "QQ"), resume = paused)
        assertEquals(paused.id, states.last().id)
        assertEquals(paused.steps.first(), states.last().steps.first())
        assertEquals(listOf(1, 2, 3), states.last().steps.map { it.number })
        assertEquals(RunStatus.COMPLETED, states.last().status)
    }

    @Test fun `operation can answer without dispatching device tools`() = runBlocking<Unit> {
        val states = mutableListOf<PhoneRun>()
        PhoneRunner({ PhoneAction("s", PhoneActionType.RESPOND, "无需手机操作", text = "你好！") }, readDriver(initial), { _, _ -> error("unexpected") }, states::add,
            confirmEveryAction = false).run("你好", mapOf("qq" to "QQ"))
        assertEquals("你好！", states.last().message)
        assertEquals(RunStatus.COMPLETED, states.last().status)
    }

    private fun readDriver(page: ScreenSnapshot) = object : PhoneDriver {
        override suspend fun observe(allowed: Set<String>) = page
        override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean = error("unexpected device execution")
        override suspend fun awaitChange() = Unit
    }
}
