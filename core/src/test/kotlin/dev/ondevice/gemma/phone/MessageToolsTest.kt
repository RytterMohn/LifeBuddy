package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class MessageToolsTest {
    private val request = MessageRequest("qq", "qq", "测试联系人", "今天下午见", false)
    private val screen = ScreenSnapshot("s", "qq", 0, "before", listOf(
        ScreenNode("who", "测试联系人", contextHeader = true),
        ScreenNode("input", "今天下午见", editable = true),
        ScreenNode("send", "发送", clickable = true),
    ))
    private val send = PhoneAction("s", PhoneActionType.SEND_MESSAGE, "发送指定消息", "send", recipientNodeId = "who", inputNodeId = "input")
    private fun check(action: PhoneAction = send, page: ScreenSnapshot = screen, task: MessageRequest = request, steps: List<PhoneStep> = emptyList()) =
        PhonePolicy.validate(action, page, setOf("qq"), task, steps)

    @Test fun `typed tool catalog covers every action with exact arguments`() {
        assertEquals(PhoneActionType.entries.toSet(), PhoneTools.all.map { it.action }.toSet())
        assertEquals(PhoneTools.all.size, PhoneTools.all.map { it.name }.distinct().size)
        val args = buildJsonObject { put("snapshotId", "s"); put("reason", "查找联系人"); put("query", "联系人") }
        assertEquals(PhoneActionType.FIND_NODES, PhoneTools.decode("phone_find_nodes", args).type)
        assertFails { PhoneTools.decode("phone_find_nodes", JsonObject(args + ("type" to JsonPrimitive("TAP")))) }
        assertFails { PhoneTools.decode("shell", args) }
    }
    @Test fun `send is bound to recipient body package and explicit mode`() {
        assertNull(check())
        assertNotNull(check(task = request.copy(recipient = "其他人")))
        assertNotNull(check(task = request.copy(body = "另一段内容")))
        assertNotNull(check(task = request.copy(packageName = "other")))
        assertNotNull(check(task = request.copy(draftOnly = true)))
        assertNotNull(PhonePolicy.validate(send, screen, setOf("qq")))
    }
    @Test fun `message text mentioning recipient is not recipient proof`() {
        assertNotNull(check(page = screen.copy(nodes = screen.nodes.map { if (it.id == "who") it.copy(contextHeader = false) else it })))
        assertNotNull(check(page = screen.copy(nodes = screen.nodes.map { if (it.id == "who") it.copy(editable = true) else it })))
    }
    @Test fun `generic click cannot bypass send validation through a parent`() {
        assertNotNull(check(action = send.copy(type = PhoneActionType.TAP)))
        val page = screen.copy(nodes = screen.nodes.filterNot { it.id == "send" } + listOf(
            ScreenNode("parent", clickable = true), ScreenNode("label", "发送", clickTargetId = "parent")))
        assertNotNull(check(send.copy(type = PhoneActionType.TAP, nodeId = "parent"), page))
        assertNull(check(send.copy(nodeId = "parent"), page))
    }
    @Test fun `old matching message is not evidence of a new send`() {
        val sent = PhoneStep(1, send, true, matchingMessagesBefore = 1)
        val page = screen.copy(nodes = listOf(screen.nodes.first(), ScreenNode("input", editable = true), ScreenNode("old", request.body)))
        assertFalse(MessagePolicy.completed(page, request, listOf(sent)))
        assertTrue(MessagePolicy.completed(page.copy(nodes = page.nodes + ScreenNode("new", request.body)), request, listOf(sent)))
        assertFalse(MessagePolicy.completed(page.copy(nodes = page.nodes + ScreenNode("new", request.body) + ScreenNode("status", "发送失败")), request, listOf(sent)))
        assertNotNull(check(steps = listOf(sent)))
    }
    @Test fun `draft completion is distinct from send completion`() {
        assertTrue(MessagePolicy.completed(screen, request.copy(draftOnly = true), emptyList()))
        assertFalse(MessagePolicy.completed(screen, request, emptyList()))
    }
    @Test fun `search submission requires a declared search field`() {
        assertNotNull(check(send.copy(type = PhoneActionType.SUBMIT_SEARCH, nodeId = "input")))
        assertNull(check(send.copy(type = PhoneActionType.SUBMIT_SEARCH, nodeId = "input"), screen.copy(nodes = screen.nodes.map { if (it.id == "input") it.copy(canSubmitSearch = true) else it })))
    }
    @Test fun `SMS single recipient validation rejects URI injection or multiple numbers`() {
        for (number in listOf("10086;12345", "10086?body=hello", "someone", "+86 123")) {
            assertFails { MessageRequest("sms", "sms", number, "hello").validate() }
        }
        MessageRequest("sms", "sms", "+8613800000000", "hello").validate()
    }
    @Test fun `read only tool returns real nodes without driver execution or approval`() = runBlocking<Unit> {
        val states = mutableListOf<PhoneRun>()
        var plans = 0
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = screen
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean = error("unexpected execution")
            override suspend fun awaitChange() = Unit
        }
        PhoneRunner({ input ->
            if (plans++ == 0) PhoneAction("s", PhoneActionType.FIND_NODES, "查找", query = "联系人")
            else { assertContains(input.steps.last().observation, "who"); PhoneAction("s", PhoneActionType.FINISH, "已查到", evidence = "测试联系人") }
        }, driver, { _, _ -> error("unexpected approval") }, states::add).run("查找联系人文字", mapOf("qq" to "QQ"))
        assertEquals(RunStatus.COMPLETED, states.last().status)
    }
    @Test fun `confirmed send is attempted once and verified after execution`() = runBlocking<Unit> {
        var current = screen
        var calls = 0
        var approvals = 0
        val states = mutableListOf<PhoneRun>()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = current
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                calls++
                assertEquals(request, states.last().messageRequest)
                assertFalse(states.last().steps.last().dispatched)
                current = screen.copy(id = "after", fingerprint = "after", nodes = listOf(screen.nodes.first(), ScreenNode("input", editable = true), ScreenNode("bubble", request.body)))
                return true
            }
            override suspend fun awaitChange() = Unit
        }
        PhoneRunner({ input -> if (input.steps.isEmpty()) send else PhoneAction(input.screen.id, PhoneActionType.FINISH, "已核对", evidence = request.body) }, driver,
            { _, _ -> approvals++; true }, states::add).run(request.goal(), mapOf("qq" to "QQ"), request)
        assertEquals(1, calls); assertEquals(1, approvals)
        assertEquals(RunStatus.COMPLETED, states.last().status)
        assertContains(states.last().message, "不代表对方已收到")
    }
}
