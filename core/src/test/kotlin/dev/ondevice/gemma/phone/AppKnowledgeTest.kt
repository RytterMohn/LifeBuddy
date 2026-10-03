package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class AppKnowledgeTest {
    private val wechat = "com.tencent.mm"
    private val netdisk = "com.baidu.netdisk"
    private val meituan = "com.sankuai.meituan"
    private val apps = mapOf(wechat to "微信", netdisk to "百度网盘", meituan to "美团")
    private val knowledge = PhoneKnowledge()
    private fun input(goal: String, current: String = "agent", allowed: Map<String, String> = apps,
        steps: List<PhoneStep> = emptyList(), turns: List<PhoneTurn> = emptyList()) =
        PlannerInput(goal, ScreenSnapshot("s", current, 0, "p", emptyList()), allowed, steps, previousTurns=turns)

    @Test fun `bundled skills are bounded and all resources readable`() {
        assertEquals(5, knowledge.library.descriptors.size)
        knowledge.library.descriptors.forEach { assertTrue(knowledge.library.body(it.id).length in 1..3200) }
        assertEquals(30, PhoneTools.all.size) // Three fixed extension gateways, independent of plugin count.
    }

    @Test fun `metadata search is lazy and body cache belongs to revisioned library`() {
        var reads = 0
        val descriptor = AppSkillDescriptor("example.search", "搜索", "查询文件", setOf(netdisk))
        val first = AppSkillLibrary(listOf(descriptor)) { reads++; "修订一" }
        assertEquals(1, first.search("搜索", setOf(netdisk)).size)
        assertEquals(0, reads)
        repeat(3) { assertEquals("修订一", first.body(descriptor.id)) }
        assertEquals(1, reads)
        val updated = AppSkillLibrary(listOf(descriptor.copy(revision=2))) { "修订二" }
        assertEquals("修订二", updated.body(descriptor.id))
    }

    @Test fun `Chinese and English aliases find authorized apps`() {
        for (alias in listOf("微信", "WeChat", "wechat")) assertEquals(wechat, knowledge.rankedApps(alias, apps).first().first)
        for (alias in listOf("百度云", "网盘", "Baidu Netdisk")) assertEquals(netdisk, knowledge.rankedApps(alias, apps).first().first)
        assertEquals(meituan, knowledge.rankedApps("点外卖", apps).first().first)
        assertTrue(knowledge.rankedApps("微信", apps - wechat).isEmpty())
    }

    @Test fun `entire thousand app directory remains reachable through small pages`() {
        val directory = (0..999).associate { "test.app$it" to "工具$it" }
        val seen = mutableSetOf<String>()
        var cursor = ""
        do {
            val page = Json.parseToJsonElement(knowledge.searchApps("", cursor, directory)).jsonObject
            val matches = page.getValue("matches").jsonArray
            assertTrue(matches.size <= 8)
            matches.forEach { assertTrue(seen.add(it.jsonObject.getValue("packageName").jsonPrimitive.content)) }
            cursor = page.getValue("nextCursor").jsonPrimitive.content
        } while (cursor.isNotEmpty())
        assertEquals(directory.keys, seen)
        assertContains(knowledge.searchApps("工具999", "", directory), "test.app999")
        assertEquals(0, Json.parseToJsonElement(knowledge.searchApps("", Int.MAX_VALUE.toString(), directory)).jsonObject.getValue("matches").jsonArray.size)
    }

    @Test fun `ten hundred and thousand apps do not inflate planning context`() {
        val contexts = listOf(10, 100, 1000).map { count ->
            val directory = apps + (1..count).associate { "test.unrelated$it" to "无关工具$it" }
            knowledge.context(input("去微信给测试联系人准备草稿", allowed=directory)).also {
                assertTrue(it.apps.size <= 8 && it.skillIds.size <= 2 && it.text.length <= 6000)
                assertEquals(listOf("messaging.navigate"), it.skillIds)
            }
        }
        assertEquals(1, contexts.map { it.text }.distinct().size)
        assertEquals(1, contexts.map { it.apps }.distinct().size)
    }

    @Test fun `thousand skills expose only three descriptions and two bounded bodies`() {
        for (count in listOf(10, 100, 1000)) {
            var reads = 0
            val library = AppSkillLibrary((1..count).map { AppSkillDescriptor("skill.$it", "搜索技巧$it", "搜索文件", setOf(netdisk)) }) { reads++; "技".repeat(2800) }
            val k = PhoneKnowledge(library)
            val result = k.read(PhoneAction("s", PhoneActionType.SEARCH_SKILLS, "查找", query="搜索"), apps)
            assertEquals(3, Json.parseToJsonElement(result).jsonObject.getValue("skills").jsonArray.size)
            assertEquals(0, reads)
            val context = k.context(input("搜索文件", netdisk))
            assertEquals(2, context.skillIds.size)
            assertTrue(context.text.length <= 6000)
            assertEquals(2, reads)
            assertEquals(30, PhoneTools.all.size)
        }
    }

    @Test fun `disallowed apps and skills are never returned or read`() {
        var reads = 0
        val library = AppSkillLibrary(listOf(AppSkillDescriptor("private.app", "私密应用", "私密应用功能", setOf("private.pkg")))) { reads++; "不可读取" }
        val k = PhoneKnowledge(library)
        assertTrue(library.search("", apps.keys).isEmpty())
        assertFalse(k.read(PhoneAction("s", PhoneActionType.LOAD_SKILL, "载入", skillId="private.app"), apps).startsWith("已载入"))
        assertTrue(k.context(input("私密应用功能")).skillIds.isEmpty())
        assertEquals(0, reads)
        assertNotNull(PhonePolicy.validate(PhoneAction("s", PhoneActionType.LOAD_SKILL, "读取", skillId="../secret"), input("", wechat).screen, apps.keys))
    }

    @Test fun `explicit load appears immediately even before target app is open`() {
        val action = PhoneAction("s", PhoneActionType.LOAD_SKILL, "补充流程", skillId="netdisk.share")
        val step = PhoneStep(1, action, true, knowledge.read(action, apps))
        val directory = apps + (1..100).associate { "test.$it" to "工具$it" }
        val context = knowledge.context(input("继续", wechat, directory, listOf(step)))
        assertContains(context.skillIds, "netdisk.share")
        assertContains(context.apps.keys, netdisk)
        assertFalse(step.observation.contains("正文："))
    }

    @Test fun `switching apps unloads prior guide and preserves task results`() {
        val goal = "百度网盘分享报价单，把链接放入微信测试联系人的草稿"
        val source = knowledge.context(input(goal, netdisk))
        assertTrue(source.skillIds.all { it.startsWith("netdisk.") })
        val result = PhoneStep(1, PhoneAction("s", PhoneActionType.READ_SCREEN, "读取链接"), true, "https://example.test/share/SYNTHETIC")
        val targetInput = input(goal, wechat, steps=listOf(result))
        val target = knowledge.context(targetInput)
        assertEquals(listOf("messaging.navigate"), target.skillIds)
        assertContains(PhonePrompt.user(targetInput.copy(allowedApps=target.apps)), "https://example.test/share/SYNTHETIC")
    }

    @Test fun `followup finds prior message app after a courtesy reply`() {
        val first = PhoneRun("same", "准备草稿", RunStatus.COMPLETED, messageRequest=MessageRequest("app", wechat, "测试联系人", "正文")).archiveTurn()
        val thanks = PhoneRun("same", "谢谢", RunStatus.COMPLETED).archiveTurn()
        val directory = apps + (1..100).associate { "test.$it" to "工具$it" }
        assertContains(knowledge.context(input("再发一次", allowed=directory, turns=listOf(first, thanks))).apps.keys, wechat)
    }

    @Test fun `cross app results survive old step compaction and serialization without node ids`() = runBlocking {
        var current = netdisk
        val link = "https://example.test/share/ACTUAL_RESULT"
        var final = PhoneRun()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = ScreenSnapshot("s", current, 0, current, if(current==netdisk)
                listOf(ScreenNode("stale-link-node", link), ScreenNode("private-edit", "EDIT_NOT_RETAINED", editable=true)) else listOf(ScreenNode("title", "微信")))
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean { current=action.packageName; return true }
            override suspend fun awaitChange() = Unit
        }
        PhoneRunner({ i -> if(i.screen.packageName==netdisk) PhoneAction("s", PhoneActionType.OPEN_APP, "转到微信", packageName=wechat)
            else PhoneAction("s", PhoneActionType.FINISH, "已打开", evidence="微信") }, driver, { _, _ -> error("approval") }, { final=it }, confirmEveryAction=false).run("打开微信", apps)
        assertEquals(RunStatus.COMPLETED, final.status)
        val saved = Json.decodeFromString<PhoneRun>(Json.encodeToString(final))
        val steps = saved.steps + List(10) { PhoneStep(it+3, PhoneAction("old", PhoneActionType.READ_SCREEN, "观察"), true) }
        for (prompt in listOf(PhonePrompt.user(input("继续", wechat, steps=steps)), PhonePrompt.compactUser(input("继续", wechat, steps=steps)))) {
            assertContains(prompt, link)
            assertFalse("stale-link-node" in prompt || "EDIT_NOT_RETAINED" in prompt)
        }
        val huge = List(100) { i -> PhoneStep(i+1, PhoneAction("s", PhoneActionType.OPEN_APP, "切换"), true,
            sourcePage=PhonePageContext("pkg$i", "字".repeat(5000))) }
        assertTrue(PhoneTaskContext.context(huge).length < 6000)
        assertTrue(PhoneTaskContext.context(huge.map { it.copy(dispatched=false) }).isEmpty())
    }

    @Test fun `paginated knowledge tools do not execute device or trigger repeat guard`() = runBlocking {
        val directory = (0..19).associate { "test.app$it" to "应用$it" }
        val page = input("", directory.keys.first(), directory).screen
        var calls = 0
        var final = PhoneRun()
        val driver = object : PhoneDriver {
            override suspend fun observe(allowed: Set<String>) = page
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean = error("read-only")
            override suspend fun awaitChange() = Unit
        }
        val planner = PhonePlanner { i -> when (calls++) {
            0 -> PhoneAction(i.screen.id, PhoneActionType.SEARCH_APPS, "第一页", cursor="0")
            1 -> PhoneAction(i.screen.id, PhoneActionType.SEARCH_APPS, "第二页", cursor="8")
            else -> PhoneAction(i.screen.id, PhoneActionType.RESPOND, "已检索", text="已读取两页目录")
        } }
        PhoneRunner(planner, driver, { _, _ -> error("approval") }, { final=it }, confirmEveryAction=false).run("查看应用目录", directory)
        assertEquals(RunStatus.COMPLETED, final.status)
        assertTrue(final.steps.take(2).all { it.dispatched })
    }

    @Test fun `generic messaging supports unfamiliar apps with the same local checks`() {
        for (pkg in listOf(wechat, "unfamiliar.messenger")) {
            val request = MessageRequest("app", pkg, "测试联系人", "今天下午见", false)
            request.validate()
            val screen = ScreenSnapshot("s", pkg, 0, "p", listOf(ScreenNode("who", request.recipient, contextHeader=true),
                ScreenNode("input", request.body, editable=true), ScreenNode("send", "发送", clickable=true)))
            val action = PhoneAction("s", PhoneActionType.SEND_MESSAGE, "发送", "send", recipientNodeId="who", inputNodeId="input")
            assertNull(PhonePolicy.validate(action, screen, setOf(pkg), request))
            assertFalse(PhonePolicy.needsApproval(action, false))
            assertNotNull(PhonePolicy.validate(action, screen, setOf(pkg), request.copy(draftOnly=true)))
            assertNotNull(PhonePolicy.validate(action, screen, setOf(pkg), request.copy(recipient="别人")))
            assertNotNull(PhonePolicy.validate(action, screen, setOf(pkg), request, listOf(PhoneStep(1, action, dispatchAttempted=true))))
        }
    }
}
