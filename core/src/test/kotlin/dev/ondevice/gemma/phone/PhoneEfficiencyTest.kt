package dev.ondevice.gemma.phone

import dev.ondevice.gemma.learning.NavigationHint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class PhoneEfficiencyTest {
    private val screen = ScreenSnapshot("current-screen", "shop", 0, "private-fingerprint",
        listOf(ScreenNode("current-node", "搜索", clickable=true)))
    private fun input(page: ScreenSnapshot = screen, request: MessageRequest? = null, steps: List<PhoneStep> = emptyList()) =
        PlannerInput("查找商品", page, mapOf("shop" to "商店"), steps, messageRequest=request)

    @Test fun `tools follow actual page capabilities without hiding app discovery or clarification`() {
        val available = PhoneTools.available(input(), true)
        assertTrue(setOf(PhoneActionType.TAP, PhoneActionType.OPEN_APP, PhoneActionType.LIST_APPS,
            PhoneActionType.FIND_NODES, PhoneActionType.ASK_USER, PhoneActionType.FINISH, PhoneActionType.NOTE_PREFERENCE).all { it in available })
        assertTrue(setOf(PhoneActionType.TYPE, PhoneActionType.SUBMIT_SEARCH, PhoneActionType.SCROLL, PhoneActionType.LONG_PRESS).none { it in available })
        val page = screen.copy(nodes=screen.nodes + ScreenNode("field", editable=true, canSubmitSearch=true, scrollable=true, longClickable=true))
        val next = PhoneTools.available(input(page), false)
        assertTrue(setOf(PhoneActionType.TYPE, PhoneActionType.SUBMIT_SEARCH, PhoneActionType.SCROLL, PhoneActionType.LONG_PRESS).all { it in next })
        assertFalse(PhoneActionType.NOTE_PREFERENCE in next)
    }

    @Test fun `message tools reappear after preparation but send cannot appear for drafts or after send`() {
        val request = MessageRequest("sms", "shop", "10086", "测试", true)
        assertFalse(PhoneActionType.SEND_MESSAGE in PhoneTools.available(input(), true))
        val draftTools = PhoneTools.available(input(request=request), true)
        assertTrue(PhoneActionType.COMPOSE_SMS in draftTools && PhoneActionType.VERIFY_MESSAGE in draftTools)
        assertFalse(PhoneActionType.SEND_MESSAGE in draftTools || PhoneActionType.RESPOND in draftTools)
        assertFalse(PhoneActionType.SEND_MESSAGE in PhoneTools.available(input(request=request.copy(draftOnly=false)), true))
        val ready = screen.copy(nodes=listOf(ScreenNode("who", "10086", contextHeader=true), ScreenNode("body", "测试", editable=true), ScreenNode("send", "发送", clickable=true)))
        assertTrue(PhoneActionType.SEND_MESSAGE in PhoneTools.available(input(ready, request.copy(draftOnly=false)), true))
        val sent = PhoneStep(1, PhoneAction("s", PhoneActionType.SEND_MESSAGE, "发送"), dispatched=true)
        val after = PhoneTools.available(input(request=request.copy(draftOnly=false), steps=listOf(sent)), true)
        assertFalse(PhoneActionType.SEND_MESSAGE in after || PhoneActionType.PREPARE_MESSAGE in after)
        assertTrue(PhoneActionType.VERIFY_MESSAGE in after && PhoneActionType.ASK_USER in after)
    }

    @Test fun `compact prompt retains evidence exact inputs and current nodes but drops stale metadata`() {
        val step = PhoneStep(1, PhoneAction("old-snapshot", PhoneActionType.TYPE, "输入", "old-node", text="输入原文 MARKER", experienceIds=listOf("old-experience")),
            dispatched=true, observation="已读回 MARKER", matchingMessagesBefore=2,
            navigation=NavigationHint("shop", "version-only-metadata", label="navigation-only-metadata"),
            timing=PhoneStepTiming(planningMs=987654))
        val request = MessageRequest("qq", "shop", "测试对象", "完整正文", false)
        val value = input(request=request, steps=listOf(step)).copy(followUps=listOf(PhoneFollowUp("最新补充", 1)), feedback="页面已变化")
        val compact = PhonePrompt.compactUser(value)
        listOf("current-screen", "current-node", "输入原文 MARKER", "已读回 MARKER", "完整正文", "测试对象", "最新补充", "页面已变化", "matchingMessagesBefore").forEach { assertContains(compact,it) }
        listOf("old-snapshot", "old-experience", "private-fingerprint", "navigation-only-metadata", "987654").forEach { assertFalse(it in compact,it) }
    }

    @Test fun `compact ledger still retains early outcomes and failed dispatch status`() {
        val steps = (1..12).map { PhoneStep(it, PhoneAction("s", PhoneActionType.TAP, "步骤 $it"), it != 1, if(it==1) "EARLY_NOT_EXECUTED" else "界面变化") }
        val text = PhonePrompt.compactUser(input(steps=steps))
        assertContains(text,"EARLY_NOT_EXECUTED"); assertContains(text,"步骤 12"); assertContains(text,"\"dispatched\":false")
    }

    @Test fun `normal menu request has fewer tools and substantially less prompt material`() {
        val value = input()
        val old = PhonePrompt.system + PhonePrompt.user(value) + PhoneTools.schemas(true)
        val compact = PhonePrompt.compactSystem + PhonePrompt.compactUser(value) + PhoneTools.schemas(true,PhoneTools.available(value,true))
        assertTrue(compact.length < old.length * .8, "old=${old.length}, compact=${compact.length}")
        assertTrue(PhoneTools.available(value,true).size < PhoneTools.all.size)
    }

    @Test fun `timing separates model observation execution and page wait without changing intent ordering`() = runBlocking {
        val states = mutableListOf<PhoneRun>()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>): ScreenSnapshot { delay(5); return screen }
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                assertFalse(states.last().steps.single().dispatched)
                delay(8); return true
            }
            override suspend fun awaitChange() { delay(12) }
        }
        val planner = object : PhonePlanner {
            override val lastMetrics = PhoneRequestMetrics(10,8,2000,12,40)
            override suspend fun next(input: PlannerInput): PhoneAction { delay(15); return PhoneAction(screen.id,PhoneActionType.TAP,"搜索","current-node") }
        }
        PhoneRunner(planner,driver,{_,_->true},states::add,maxSteps=1).run("搜索",mapOf("shop" to "商店"))
        val step = states.last().steps.single()
        assertTrue(step.dispatched)
        val timing = assertNotNull(step.timing)
        assertTrue(timing.planningMs >= 10 && timing.observeMs >= 10 && timing.executeMs >= 5 && timing.settleMs >= 8)
        assertEquals(2000,timing.request?.estimatedInputTokens)
    }

    @Test fun `old task steps remain readable without fabricated timing`() {
        val old = Json.decodeFromString<PhoneStep>("""{"number":1,"action":{"snapshotId":"s","type":"TAP","reason":"旧记录"}}""")
        assertNull(old.timing)
    }
}
