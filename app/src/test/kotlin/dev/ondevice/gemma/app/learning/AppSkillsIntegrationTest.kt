package dev.ondevice.gemma.app.learning

import dev.ondevice.gemma.app.data.CloudConfig
import dev.ondevice.gemma.app.model.CloudApiClient
import dev.ondevice.gemma.app.model.CloudPhonePlanner
import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/** Real provider + production harness. App package names label synthetic pages, never real Apps. */
class AppSkillsIntegrationTest {
    private val wechat = "com.tencent.mm"
    private val netdisk = "com.baidu.netdisk"
    private val meituan = "com.sankuai.meituan"
    private val unknown = "test.unknown.notes"
    private val agent = "test.agent"
    private val contact = "测试联系人"
    private val filename = "测试报价单.pdf"
    private val link = "https://example.test/share/ALPHA14"
    private val apps = mapOf(agent to "Agent", wechat to "微信", netdisk to "百度网盘", meituan to "美团", unknown to "星河便笺") +
        (1..1000).associate { "test.other$it" to "无关应用$it" }
    private val cases = mutableListOf<JsonObject>()
    private var stage = "configuration"

    @Test fun representativeAppTasks() = runBlocking {
        check(System.getenv("ANDROIDMODEL_LEARNING_API_TEST") == "1")
        val config = CloudConfig(System.getenv("ANDROIDMODEL_TEST_API_URL").orEmpty(),
            System.getenv("ANDROIDMODEL_TEST_MODEL").orEmpty(), System.getenv("ANDROIDMODEL_TEST_API_KEY").orEmpty())
        CloudApiClient.validate(config)
        var success = false
        try { withTimeout(12 * 60_000L) {
            runCase(config, "wechat_send", "用微信给测试联系人发送消息，正文原文：HARNESS_WECHAT_14。", "messaging.navigate") { run, d ->
                assertEquals(MessageRequest("app", wechat, contact, "HARNESS_WECHAT_14", false), run.messageRequest)
                assertEquals(MessageOutcome.VERIFIED, run.messageOutcome)
                assertEquals(1, d.sends)
                assertTrue(d.contactSearched)
            }
            runCase(config, "wechat_draft", "在微信给测试联系人准备草稿 HARNESS_DRAFT_14，不发送。", "messaging.navigate") { run, d ->
                assertEquals(MessageRequest("app", wechat, contact, "HARNESS_DRAFT_14", true), run.messageRequest)
                assertEquals("HARNESS_DRAFT_14", d.message)
                assertEquals(0, d.sends)
            }
            runCase(config, "netdisk_search", "在百度网盘我的文件里搜索测试报价单.pdf，核对实际搜索结果后告诉我是否找到，不分享。", "netdisk.search") { _, d ->
                assertTrue(d.fileSearched)
                assertEquals("file-results", d.pages[netdisk])
                assertFalse(d.linkGenerated)
            }
            runCase(config, "meituan_options", "在美团外卖搜索测试粥铺，打开店里的南瓜粥并选择少辣，到口味结果页核对；不要下单或付款。", "meituan.search") { _, d ->
                assertTrue(d.shopSearched)
                assertEquals("少辣", d.flavor)
                assertEquals("flavor-result", d.pages[meituan])
            }
            runCase(config, "netdisk_to_wechat_draft", "在百度网盘搜索测试报价单.pdf，打开文件并生成分享链接；然后把页面实际生成的链接原文放入微信测试联系人的草稿，不发送。", "netdisk.share") { run, d ->
                assertTrue(d.linkGenerated)
                assertEquals(link, d.message)
                assertEquals(MessageRequest("app", wechat, contact, link, true), run.messageRequest)
                assertEquals(0, d.sends)
                val afterSwitch = run.steps.dropWhile { it.action.type != PhoneActionType.OPEN_APP || it.action.packageName != wechat }.drop(1)
                assertTrue(afterSwitch.any { it.timing?.request?.skillIds == listOf("messaging.navigate") })
                assertTrue(afterSwitch.none { it.timing?.request?.skillIds.orEmpty().any { id -> id.startsWith("netdisk.") } })
            }
            runCase(config, "unknown_app_without_skill", "在星河便笺里搜索会议纪要，核对搜索结果后告诉我。", null) { run, d ->
                assertEquals("notes-results", d.pages[unknown])
                assertTrue(run.steps.all { it.timing?.request?.skillIds.orEmpty().isEmpty() })
            }
            runCase(config, "explicit_discovery_tools", "只做工具检索验证：先用 phone_search_apps 搜索百度云，再用 phone_search_skills 搜索网盘分享，接着用 phone_load_skill 载入检索出的分享技能。完成后直接回复找到的技能名称，不操作应用页面。", "netdisk.share") { run, d ->
                assertTrue(run.steps.filter { it.dispatched }.map { it.action.type }.containsAll(listOf(PhoneActionType.SEARCH_APPS, PhoneActionType.SEARCH_SKILLS, PhoneActionType.LOAD_SKILL)))
                assertEquals(0, d.executions)
            }
            success = true
        } } finally {
            val out = File(System.getenv("ANDROIDMODEL_TEST_REPORT") ?: error("Missing report path"))
            out.parentFile?.mkdirs()
            out.writeText(Json { prettyPrint=true }.encodeToString(buildJsonObject {
                put("success", success); put("lastStage", stage); put("model", config.model)
                put("environment", "real HTTPS, production CloudPhonePlanner/PhoneRunner, synthetic App screens with 1005 allowed applications")
                put("actualAppCompatibilityTest", false); put("realMessagesSent", 0); put("realOrdersSubmitted", 0)
                put("cases", JsonArray(cases))
            }))
        }
    }

    private suspend fun runCase(config: CloudConfig, name: String, goal: String, expectedSkill: String?, verify: (PhoneRun, SceneDriver) -> Unit) {
        stage = name
        val driver = SceneDriver()
        var run = PhoneRun()
        var passed = false
        try {
            withTimeout(150_000) {
                PhoneRunner(CloudPhonePlanner(config), driver, { _, _ -> error("Unexpected approval") }, { run=it }, maxSteps=24, confirmEveryAction=false).run(goal, apps)
            }
            assertEquals(RunStatus.COMPLETED, run.status, "$name: ${run.message}")
            val metrics = run.steps.mapNotNull { it.timing?.request }
            assertTrue(metrics.isNotEmpty())
            assertTrue(metrics.all { it.appCandidates <= 8 && it.skillIds.size <= 2 && it.knowledgeChars <= 6000 })
            if (expectedSkill != null) assertTrue(metrics.any { expectedSkill in it.skillIds }, "Missing $expectedSkill")
            verify(run, driver)
            passed = true
            println("PASS $name (${run.plannerRequests} requests, ${run.elapsedMs} ms)")
        } finally {
            cases += buildJsonObject {
                put("name", name); put("success", passed); put("allowedAppCount", run.allowedPackages.size)
                put("run", Json.encodeToJsonElement(run.copy(allowedPackages=run.allowedPackages.intersect(setOf(agent, wechat, netdisk, meituan, unknown)))))
                put("simulatedSends", driver.sends); put("deviceActions", driver.executions)
            }
        }
    }

    private inner class SceneDriver : PhoneDriver {
        var current = agent
        val pages = mutableMapOf(wechat to "contacts", netdisk to "files", meituan to "shops", unknown to "notes")
        val inputs = mutableMapOf<String, String>()
        var message = ""
        var sends = 0
        var executions = 0
        var contactSearched = false
        var fileSearched = false
        var shopSearched = false
        var linkGenerated = false
        var flavor = ""
        private var reads = 0
        private var revision = 0
        private val bubbles = mutableListOf<String>()
        override suspend fun observe(allowed: Set<String>): ScreenSnapshot {
            reads++
            // Like accessibility paths: IDs remain stable while the page is unchanged, change after an action.
            val prefix = "$revision-"
            fun text(id: String, value: String, click: Boolean=false) = ScreenNode(prefix+id, value, clickable=click)
            fun field(hint: String) = ScreenNode(prefix+"query", inputs[current].orEmpty(), editable=true, canSubmitSearch=true, hint=hint)
            val nodes = when (current) {
                wechat -> when(pages[current]) {
                    "contacts" -> listOf(text("title", "微信"), field("搜索联系人"), text("search", "搜索", true))
                    "contact-results" -> listOf(text("title", "联系人搜索结果"), text("contact", contact, true))
                    else -> listOf(ScreenNode(prefix+"header", contact, contextHeader=true), ScreenNode(prefix+"input", message, editable=true, hint="消息正文"), text("send", "发送", true)) + bubbles.mapIndexed { i, body -> text("bubble$i", body) }
                }
                netdisk -> when(pages[current]) {
                    "files" -> listOf(text("title", "百度网盘 · 我的文件"), field("搜索我的文件"), text("search", "搜索", true))
                    "file-results" -> listOf(text("title", "文件搜索结果：1 项"), text("file", filename, true))
                    "file-detail" -> listOf(text("file", filename), text("share", "分享", true))
                    else -> listOf(text("title", "分享链接已生成"), text("link", link))
                }
                meituan -> when(pages[current]) {
                    "shops" -> listOf(text("title", "美团外卖"), field("搜索商家"), text("search", "搜索", true))
                    "shop-results" -> listOf(text("title", "外卖商家结果"), text("shop", "测试粥铺", true))
                    "menu" -> listOf(text("title", "测试粥铺"), text("dish", "南瓜粥", true))
                    "flavors" -> listOf(text("title", "南瓜粥 · 选择口味"), text("mild", "少辣", true), text("hot", "正常辣", true))
                    else -> listOf(text("title", "口味选择结果"), text("chosen", "南瓜粥 · 已选少辣"))
                }
                unknown -> if (pages[current] == "notes") listOf(text("title", "星河便笺"), field("搜索笔记"), text("search", "搜索", true))
                    else listOf(text("title", "笔记搜索结果：1 项"), text("note", "会议纪要"))
                else -> listOf(text("title", "Agent 控制页"))
            }
            return ScreenSnapshot("screen-$reads", current, System.currentTimeMillis(), "$current-${pages[current]}-${inputs[current]}-$message-$sends-$flavor", nodes, appVersion="synthetic14")
        }
        override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
            executions++
            revision++
            if (action.type == PhoneActionType.OPEN_APP && action.packageName in pages) { current=action.packageName; return true }
            val node = screen.nodes.singleOrNull { it.id == action.nodeId } ?: return false
            val id = node.id.substringAfter('-')
            if (action.type == PhoneActionType.TYPE && node.editable) {
                if (id == "input") message=action.text else inputs[current]=action.text
                return true
            }
            if (action.type == PhoneActionType.SEND_MESSAGE && current == wechat && id == "send") {
                bubbles += message; message=""; sends++; return true
            }
            if (action.type == PhoneActionType.SUBMIT_SEARCH || (action.type == PhoneActionType.TAP && id == "search")) {
                when(current) {
                    wechat -> { if(inputs[current] != contact) return false; contactSearched=true; pages[current]="contact-results" }
                    netdisk -> { if(inputs[current] != filename) return false; fileSearched=true; pages[current]="file-results" }
                    meituan -> { if(inputs[current] != "测试粥铺") return false; shopSearched=true; pages[current]="shop-results" }
                    unknown -> { if(inputs[current] != "会议纪要") return false; pages[current]="notes-results" }
                    else -> return false
                }
                return true
            }
            if (action.type != PhoneActionType.TAP || !node.clickable) return false
            when (id) {
                "contact" -> pages[current]="chat"
                "file" -> pages[current]="file-detail"
                "share" -> { pages[current]="link"; linkGenerated=true }
                "shop" -> pages[current]="menu"
                "dish" -> pages[current]="flavors"
                "mild" -> { pages[current]="flavor-result"; flavor="少辣" }
                else -> return false
            }
            return true
        }
        override suspend fun awaitChange() = Unit
    }
}
