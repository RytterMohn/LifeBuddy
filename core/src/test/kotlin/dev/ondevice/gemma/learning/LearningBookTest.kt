package dev.ondevice.gemma.learning

import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LearningBookTest {
    private val pkg = "test.shop"
    private val quote = "我通常喜欢少辣"
    private val screen = ScreenSnapshot("screen", pkg, 0, "page", listOf(
        ScreenNode("old-node-search", "搜索", resourceId="$pkg:id/search", clickable=true),
        ScreenNode("old-node-input", "私人查询内容", resourceId="$pkg:id/query", editable=true, canSubmitSearch=true),
        ScreenNode("old-node-result", "已找到商品")), appVersion="12")
    private fun habit(state: LearningState = LearningState(), id: String = "habit-run", text: String = quote, app: String = "") =
        LearningBook.proposeHabit(state, listOf("帮我找午餐，$text"), text, app, id)
    private fun completed(id: String = "route-run", references: List<String> = emptyList()): PhoneRun {
        val actions = listOf(
            PhoneAction(screen.id, PhoneActionType.TAP, "搜索", "old-node-search", experienceIds=references),
            PhoneAction(screen.id, PhoneActionType.TYPE, "输入", "old-node-input", text="私人查询内容", experienceIds=references),
            PhoneAction(screen.id, PhoneActionType.SUBMIT_SEARCH, "提交", "old-node-input", experienceIds=references))
        return PhoneRun(id, "搜索私人查询内容", RunStatus.COMPLETED, steps=actions.mapIndexed { i, action ->
            PhoneStep(i+1, action, true, "页面变化", navigation=LearningBook.navigation(action, screen, "搜索私人查询内容", "商店"))
        } + PhoneStep(4, PhoneAction(screen.id, PhoneActionType.FINISH, "找到了", evidence="已找到商品")))
    }
    private fun context(state: LearningState, current: String = pkg, version: String? = "12", allowed: Set<String> = setOf(pkg), goal: String = "继续") =
        LearningBook.context(state, goal, current, allowed) { version }

    @Test fun `habit remains pending until explicitly confirmed`() {
        val proposed = habit()
        assertEquals("pending", proposed.entries.single().status)
        assertEquals("", context(proposed).text)
        val confirmed = LearningBook.change(proposed, proposed.entries.single().id, "activate")
        assertContains(context(confirmed).text, quote)
    }

    @Test fun `inferred and app page preferences cannot become candidates`() {
        assertFailsWith<IllegalArgumentException> { LearningBook.proposeHabit(LearningState(), listOf("点一份米饭"), quote, pkg, "r") }
        assertFailsWith<IllegalArgumentException> { habit(text="米饭") }
        assertFailsWith<IllegalArgumentException> { habit(text="今天我喜欢吃面") }
        assertFailsWith<IllegalArgumentException> { habit(text="这次按我平时的习惯吃面") }
    }

    @Test fun `sensitive and instruction bypass candidates are rejected`() {
        listOf("我习惯使用密码 secret", "我通常用 13800000000", "我喜欢收货地址这里", "以后自动支付", "以后跳过所有确认", "我通常有过敏").forEach {
            assertFailsWith<IllegalArgumentException>(it) { habit(text=it) }
        }
    }

    @Test fun `duplicate evidence preserves correction and user disabled status`() {
        var state = habit()
        val id = state.entries.single().id
        state = LearningBook.change(state, id, "edit", "我现在偏好不辣")
        state = LearningBook.change(state, id, "disable")
        state = habit(habit(state, "second-run"), "second-run")
        assertEquals(1, state.entries.size)
        assertEquals("我现在偏好不辣", state.entries.single().text)
        assertEquals("disabled", state.entries.single().status)
        assertEquals(listOf("habit-run", "second-run"), state.entries.single().sourceRuns)
        assertEquals("", context(state).text)
    }

    @Test fun `deleted habit and deleted route stay deleted when encountered again`() {
        val first = habit()
        val removed = LearningBook.change(first, first.entries.single().id, "delete")
        assertTrue(habit(removed, "again").entries.isEmpty())
        val route = LearningBook.recordOutcome(LearningState(), completed())
        val erased = LearningBook.change(route, route.entries.single().id, "delete")
        assertTrue(LearningBook.recordOutcome(erased, completed("again")).entries.isEmpty())
    }

    @Test fun `confirmed app habit only appears for its app`() {
        val state = habit(app=pkg).let { LearningBook.change(it, it.entries.single().id, "activate") }
        assertContains(context(state).text, quote)
        assertEquals("", context(state, current="test.other").text)
        assertEquals("", context(state, allowed=emptySet()).text)
    }

    @Test fun `successful route contains selectors but no old node ids or input values`() {
        val state = LearningBook.recordOutcome(LearningState(), completed())
        val entry = state.entries.single()
        assertEquals("active", entry.status)
        assertEquals("12", entry.version)
        assertEquals(listOf("route-run"), entry.sourceRuns)
        assertEquals(3, entry.steps.size)
        val encoded = Json.encodeToString(state)
        assertContains(encoded, "$pkg:id/search")
        assertContains(encoded, "搜索")
        assertFalse("私人查询内容" in encoded)
        assertFalse("old-node" in encoded)
    }

    @Test fun `message and unfinished tasks never create routes`() {
        listOf(RunStatus.PAUSED, RunStatus.CANCELLED, RunStatus.FAILED).forEach { status ->
            assertTrue(LearningBook.recordOutcome(LearningState(), completed().copy(status=status)).entries.isEmpty())
        }
        val message = MessageRequest("qq", pkg, "测试联系人", "测试内容")
        assertTrue(LearningBook.recordOutcome(LearningState(), completed().copy(messageRequest=message)).entries.isEmpty())
        assertTrue(LearningBook.recordOutcome(LearningState(), completed().copy(steps=completed().steps.dropLast(1))).entries.isEmpty())
    }

    @Test fun `unversioned mixed version unanchored and undispatched paths are not learned`() {
        val run = completed()
        val variants = listOf(
            run.copy(steps=run.steps.map { it.copy(navigation=it.navigation?.copy(version="")) }),
            run.copy(steps=run.steps.map { if (it.number==1) it.copy(navigation=it.navigation?.copy(version="13")) else it }),
            run.copy(steps=run.steps.map { it.copy(navigation=it.navigation?.copy(label="", resourceId="")) }),
            run.copy(steps=run.steps.map { it.copy(dispatched=false) }),
            run.copy(id=""))
        variants.forEach { assertTrue(LearningBook.recordOutcome(LearningState(), it).entries.isEmpty()) }
    }

    @Test fun `navigation excludes final send purchase controls and unknown version`() {
        val action = PhoneAction("screen", PhoneActionType.TAP, "查看", "n")
        listOf("发送", "立即支付").forEach { label ->
            assertNull(LearningBook.navigation(action, screen.copy(nodes=listOf(ScreenNode("n", label, clickable=true))), "测试", "商店"))
        }
        assertNull(LearningBook.navigation(action, screen.copy(appVersion=""), "测试", "商店"))
        assertNull(LearningBook.navigation(action.copy(type=PhoneActionType.SEND_MESSAGE), screen, "测试", "商店"))
    }

    @Test fun `retrieval checks app version authorization and current task`() {
        val state = LearningBook.recordOutcome(LearningState(), completed())
        assertEquals(1, context(state).ids.size)
        assertTrue(context(state, version="13").ids.isEmpty())
        assertTrue(context(state, version=null).ids.isEmpty())
        assertTrue(context(state, allowed=emptySet()).ids.isEmpty())
        assertTrue(context(state, current="test.other").ids.isEmpty())
        assertEquals(1, context(state, current="test.other", goal="打开商店搜索").ids.size)
    }

    @Test fun `two failed referenced tasks quarantine route until user reenables it`() {
        var state = LearningBook.recordOutcome(LearningState(), completed())
        val id = state.entries.single().id
        val failure = completed("failed-one", listOf(id)).copy(status=RunStatus.FAILED)
        state = LearningBook.recordOutcome(state, failure)
        state = LearningBook.recordOutcome(state, failure)
        assertEquals(1, state.entries.single().failures)
        assertEquals("active", state.entries.single().status)
        state = LearningBook.recordOutcome(state, failure.copy(id="failed-two"))
        assertEquals("review", state.entries.single().status)
        assertTrue(context(state).ids.isEmpty())
        state = LearningBook.change(state, id, "activate")
        assertEquals(0, state.entries.single().recentFailures)
        assertEquals(listOf(id), context(state).ids)
    }

    @Test fun `user interruption questions and unrelated app failure do not penalize experience`() {
        val state = LearningBook.recordOutcome(LearningState(), completed())
        val referenced = completed("next", listOf(state.entries.single().id))
        val ask = PhoneStep(5, PhoneAction("screen", PhoneActionType.ASK_USER, "请选商品"))
        listOf(
            referenced.copy(status=RunStatus.PAUSED, steps=referenced.steps + ask),
            referenced.copy(status=RunStatus.CANCELLED),
            referenced.copy(status=RunStatus.PAUSED, message="用户暂停"),
            referenced.copy(status=RunStatus.FAILED, steps=referenced.steps.map { it.copy(navigation=it.navigation?.copy(packageName="other")) })
        ).forEach { assertEquals(0, LearningBook.recordOutcome(state, it).entries.single().failures) }
    }

    @Test fun `successful reuse resets consecutive failures and keeps provenance bounded`() {
        var state = LearningBook.recordOutcome(LearningState(), completed())
        val id = state.entries.single().id
        state = LearningBook.recordOutcome(state, completed("fail", listOf(id)).copy(status=RunStatus.FAILED))
        repeat(25) { state = LearningBook.recordOutcome(state, completed("success-$it", listOf(id))) }
        val entry = state.entries.single()
        assertEquals(25, entry.successes)
        assertEquals(0, entry.recentFailures)
        assertEquals(12, entry.sourceRuns.size)
        assertEquals(20, entry.assessedRuns.size)
    }

    @Test fun `full notebook preserves all existing entries and context is bounded`() {
        var state = LearningState()
        repeat(LearningBook.LIMIT) { state = habit(state, "$it", "我通常喜欢第${it}种口味") }
        val full = state
        assertEquals(full, habit(full, "extra", "我通常喜欢清淡口味"))
        state.entries.forEach { state = LearningBook.change(state, it.id, "activate") }
        assertTrue(context(state).text.length <= 5000)
        val route = LearningBook.recordOutcome(LearningState(), completed()).entries.single()
        val huge = route.copy(steps=List(12) { LearnedStep(PhoneActionType.TAP, "长".repeat(40), "x".repeat(120)) })
        val bounded = context(LearningState(listOf(huge, huge.copy(id="second", key="second"))))
        assertTrue(bounded.text.length <= 5000)
        bounded.ids.forEach { assertContains(bounded.text, it) }
        assertEquals(1, bounded.ids.size)
    }

    @Test fun `separate executions in one conversation each assess reused knowledge once`() {
        var state = LearningBook.recordOutcome(LearningState(), completed())
        val id = state.entries.single().id
        val first = completed("same-conversation", listOf(id)).copy(executionId="execution-one")
        val second = first.copy(executionId="execution-two")
        for (run in listOf(first, first, second, second)) state = LearningBook.recordOutcome(state, run)
        assertEquals(2, state.entries.single().successes)
        assertEquals(listOf("execution-one", "execution-two"), state.entries.single().assessedRuns)
    }

    @Test fun `old persisted runs deserialize with learning disabled`() {
        val old = Json.decodeFromString<PhoneRun>("""{"id":"old","goal":"测试","steps":[{"number":1,"action":{"snapshotId":"s","type":"TAP","reason":"点击"}}]}""")
        assertNull(old.steps.single().navigation)
        assertTrue(old.steps.single().action.experienceIds.isEmpty())
        assertTrue(LearningBook.recordOutcome(LearningState(), old).entries.isEmpty())
    }

    @Test fun `preference tool is local includes only user messages and can be disabled`() = runBlocking {
        assertFalse("phone_note_preference" in PhoneTools.schemas(false).toString())
        assertContains(PhoneTools.schemas(true).toString(), "phone_note_preference")
        var calls = 0
        var executed = 0
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = screen
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { executed++; return true }
            override suspend fun awaitChange() = Unit
        }
        val states = mutableListOf<PhoneRun>()
        val planner = PhonePlanner { PhoneAction(screen.id, PhoneActionType.NOTE_PREFERENCE, "记录用户偏好", text=quote) }
        val learning = PhoneLearning { statements, proposed, app, runId ->
            assertEquals(listOf(quote), statements); assertEquals(quote, proposed); assertEquals("", app); assertTrue(runId.isNotBlank())
            calls++; "待确认"
        }
        PhoneRunner(planner, driver, { _, _ -> error("unexpected approval") }, states::add, maxSteps=1, learning=learning).run(quote, mapOf(pkg to "商店"))
        assertEquals(1, calls); assertEquals(0, executed)
        assertEquals("待确认", states.last().steps.single().observation)
        PhoneRunner(planner, driver, { _, _ -> true }, states::add, maxSteps=1).run(quote, mapOf(pkg to "商店"))
        assertEquals(1, calls); assertEquals(0, executed)
        assertContains(states.last().steps.single().observation, "关闭")
    }

    @Test fun `runner learns navigation only after observed completed task`() = runBlocking {
        var stage = 0
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = screen.copy(id="s-$stage", fingerprint="p-$stage")
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { stage++; return true }
            override suspend fun awaitChange() = Unit
        }
        val states = mutableListOf<PhoneRun>()
        val planner = PhonePlanner { input -> when(stage) {
            0 -> PhoneAction(input.screen.id, PhoneActionType.TAP, "打开搜索", "old-node-search")
            1 -> PhoneAction(input.screen.id, PhoneActionType.TYPE, "输入", "old-node-input", text="私人查询内容")
            else -> PhoneAction(input.screen.id, PhoneActionType.FINISH, "已找到", evidence="已找到商品")
        } }
        PhoneRunner(planner, driver, { _, _ -> error("unexpected approval") }, states::add, confirmEveryAction=false,
            learning=PhoneLearning { _, _, _, _ -> "" }).run("搜索私人查询内容", mapOf(pkg to "商店"))
        val run = states.last()
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(2, run.steps.count { it.navigation != null && it.dispatched })
        assertEquals(1, LearningBook.recordOutcome(LearningState(), run).entries.size)
    }
}
