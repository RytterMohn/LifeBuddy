package dev.ondevice.gemma.learning

import dev.ondevice.gemma.phone.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AutoSkillBookTest {
    private val pkg="test.novel.notes"
    private val apps=mapOf(pkg to "陌生笔记")
    private fun successful(query: String="机密项目甲", version: String="1", layout: String="old", reference: String=""): PhoneRun = runBlocking {
        var input=""; var submitted=false; var run=PhoneRun()
        val driver=object: PhoneDriver {
            override suspend fun observe(allowed: Set<String>)=ScreenSnapshot("s",pkg,0,"$input-$submitted",
                if(submitted) listOf(ScreenNode("$layout-result", "已找到 $input")) else listOf(ScreenNode("$layout-input",input,editable=true,canSubmitSearch=true,hint="搜索笔记",resourceId="$pkg:id/${layout}_query")),appVersion=version)
            override suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean {
                when(action.type) { PhoneActionType.TYPE -> input=action.text; PhoneActionType.SUBMIT_SEARCH -> submitted=true; else -> return false }; return true
            }
            override suspend fun awaitChange()=Unit
        }
        val planner=object: PhonePlanner {
            override val lastMetrics=PhoneRequestMetrics(skillIds=listOf(reference).filter { it.isNotBlank() })
            override suspend fun next(input: PlannerInput)=when {
                submitted -> PhoneAction("s",PhoneActionType.FINISH,"结果已匹配",evidence="已找到 $query")
                this@AutoSkillBookTest.let { input.screen.nodes.first().text==query } -> PhoneAction("s",PhoneActionType.SUBMIT_SEARCH,"提交","$layout-input")
                else -> PhoneAction("s",PhoneActionType.TYPE,"填写","$layout-input",text=query)
            }
        }
        PhoneRunner(planner,driver,{_,_->error("approval")},{run=it},confirmEveryAction=false,captureSkills=true).run("搜索笔记 $query",apps)
        assertEquals(RunStatus.COMPLETED,run.status)
        run
    }
    private fun learn(state: AutoSkillState=AutoSkillState(), run: PhoneRun=successful()) = AutoSkillBook.processNext(AutoSkillBook.record(state,run))

    @Test fun `unknown app first success creates queued parameterized trial without builtin guide`() {
        val run=successful()
        assertTrue(AppSkillLibrary.bundled.search("搜索",apps.keys).isEmpty())
        val queued=AutoSkillBook.record(AutoSkillState(),run)
        assertEquals(1,queued.jobs.size); assertTrue(queued.skills.isEmpty())
        assertEquals(queued,AutoSkillBook.record(queued,run))
        val state=AutoSkillBook.processNext(queued)
        val skill=state.skills.single()
        assertEquals("trial",skill.status); assertEquals(1,skill.current.successes)
        assertContains(AutoSkillBook.render(skill),"{query}")
        val serialized=Json.encodeToString(state)
        assertFalse("机密项目甲" in serialized || "old-input" in serialized || "old-result" in serialized)
        assertEquals(state,AutoSkillBook.record(state,run))
    }

    @Test fun `restart queue and library support different query with a shared two skill budget`() {
        val pending=AutoSkillBook.record(AutoSkillState(),successful())
        val restarted=Json.decodeFromString<AutoSkillState>(Json.encodeToString(pending))
        val state=AutoSkillBook.processNext(restarted)
        val disk=Json.decodeFromString<AutoSkillState>(Json.encodeToString(state))
        val knowledge=PhoneKnowledge(AutoSkillBook.library(disk) { "1" })
        val context=knowledge.context(PlannerInput("在陌生笔记搜索预算乙",ScreenSnapshot("fresh",pkg,0,"p",emptyList()),apps,emptyList()))
        assertEquals(listOf(disk.skills.single().reference),context.skillIds)
        assertContains(context.text,"{query}"); assertFalse("机密项目甲" in context.text)
        val next=learn(disk,successful("预算乙",reference=disk.skills.single().reference))
        assertEquals(1,next.skills.size); assertEquals("verified",next.skills.single().status)
        assertEquals(2,next.skills.single().current.successes)
        assertEquals(1,next.skills.single().current.number)
    }

    @Test fun `same value successes do not claim verification across parameters`() {
        val first=learn()
        val same=learn(first,successful())
        assertEquals("trial",same.skills.single().status)
        assertEquals(2,same.skills.single().current.successes)
        assertEquals(1,same.skills.single().current.variants.size)
    }

    @Test fun `version and layout changes produce revisions and old version is excluded`() {
        val first=learn()
        assertTrue(AutoSkillBook.library(first) { "2" }.descriptors.none { it.learned })
        val second=learn(first,successful("预算乙",version="2",layout="new"))
        assertEquals(1,second.skills.size)
        assertEquals(listOf("1","2"),second.skills.single().revisions.map { it.version })
        assertEquals(2,second.skills.single().current.number)
        assertEquals("trial",second.skills.single().status)
        val third=learn(second,successful("预算丙",version="2",layout="changed"))
        assertEquals(3,third.skills.single().current.number)
    }

    @Test fun `two attributed local failures suspend guide and successful exploration revises it`() {
        var state=learn(); val skill=state.skills.single()
        fun failure(id: String)=successful().let { r -> r.copy(executionId=id,status=RunStatus.PAUSED,steps=listOf(r.steps.first().copy(skillFailure=true,
            timing=PhoneStepTiming(request=PhoneRequestMetrics(skillIds=listOf(skill.reference)))))) }
        state=AutoSkillBook.record(state,failure("one")); state=AutoSkillBook.record(state,failure("one"))
        assertEquals(1,state.skills.single().current.failures)
        state=AutoSkillBook.record(state,failure("two"))
        assertEquals("review",state.skills.single().status)
        assertTrue(AutoSkillBook.library(state) { "1" }.descriptors.none { it.learned })
        val fixed=learn(state,successful("新参数",layout="new"))
        assertEquals(2,fixed.skills.single().current.number)
        assertEquals(2,fixed.skills.single().revisions.first().failures)
        assertEquals("trial",fixed.skills.single().status)
    }

    @Test fun `network errors cancellation and questions do not penalize a guide`() {
        val state=learn(); val run=successful(reference=state.skills.single().reference)
        for(status in listOf(RunStatus.FAILED,RunStatus.CANCELLED,RunStatus.PAUSED)) assertEquals(state,AutoSkillBook.record(state,run.copy(status=status)))
    }

    @Test fun `deleted and disabled skills stay excluded even with queued revisions`() {
        val state=learn(); val id=state.skills.single().id
        val queued=AutoSkillBook.record(state,successful("预算乙",layout="new"))
        val deleted=AutoSkillBook.change(queued,id,"delete")
        assertTrue(deleted.skills.isEmpty() && deleted.jobs.isEmpty())
        assertEquals(deleted,AutoSkillBook.record(deleted,successful("预算丙",layout="changed")))
        val disabled=AutoSkillBook.change(queued,id,"disable")
        val drained=AutoSkillBook.processNext(disabled)
        assertEquals("disabled",drained.skills.single().status)
        assertEquals(1,drained.skills.single().current.number)
        assertTrue(AutoSkillBook.library(drained) { "1" }.descriptors.none { it.learned })
        assertEquals(drained,AutoSkillBook.record(drained,successful("预算丙")))
    }

    @Test fun `editing preserves old revision and does not reactivate disabled skill`() {
        val state=learn(); val id=state.skills.single().id
        val edited=AutoSkillBook.change(AutoSkillBook.change(state,id,"disable"),id,"edit","搜索入口可能在顶部工具栏。")
        assertEquals("disabled",edited.skills.single().status)
        assertEquals(2,edited.skills.single().revisions.size)
        assertEquals("",edited.skills.single().revisions.first().notes)
        assertContains(AutoSkillBook.render(edited.skills.single()),"顶部工具栏")
        assertEquals("trial",AutoSkillBook.change(edited,id,"activate").skills.single().status)
    }

    @Test fun `old histories unverified finish and editable echo never generate skills`() = runBlocking {
        val run=successful()
        assertTrue(AutoSkillBook.record(AutoSkillState(),run.copy(steps=run.steps.map { it.copy(skillCompletion=null) })).jobs.isEmpty())
        val page=ScreenSnapshot("s",pkg,0,"p",listOf(ScreenNode("input","机密项目甲",editable=true)),appVersion="1")
        assertNull(AutoSkillTrace.completion(run,page,"机密项目甲"))
        val old=Json.decodeFromString<PhoneRun>("""{"id":"old","status":"COMPLETED"}""")
        assertTrue(AutoSkillBook.record(AutoSkillState(),old).jobs.isEmpty())
    }

    @Test fun `message skills keep recipient and body as parameters and retain send checks`() = runBlocking {
        val request=MessageRequest("app",pkg,"测试私密联系人甲","仅本次正文不可留存",false)
        var body=""; var sent=false; var run=PhoneRun()
        val driver=object: PhoneDriver {
            override suspend fun observe(allowed: Set<String>)=ScreenSnapshot("s",pkg,0,"$body-$sent",listOf(
                ScreenNode("person",request.recipient,contextHeader=true),ScreenNode("input",body,editable=true,hint="消息正文"),ScreenNode("send","发送",clickable=true)
            )+if(sent) listOf(ScreenNode("bubble",request.body)) else emptyList(),appVersion="1")
            override suspend fun execute(action: PhoneAction,screen: ScreenSnapshot): Boolean {
                if(action.type==PhoneActionType.TYPE) body=action.text else { sent=true; body="" }; return true
            }
            override suspend fun awaitChange()=Unit
        }
        PhoneRunner({ i -> if(body.isEmpty()) PhoneAction(i.screen.id,PhoneActionType.TYPE,"输入","input",text=request.body)
            else PhoneAction(i.screen.id,PhoneActionType.SEND_MESSAGE,"发送","send",recipientNodeId="person",inputNodeId="input") },driver,{_,_->error("approval")},{run=it},confirmEveryAction=false,captureSkills=true)
            .run(request.goal(),apps,request)
        assertEquals(MessageOutcome.VERIFIED,run.messageOutcome)
        val state=learn(run=run); val serialized=Json.encodeToString(state)
        assertEquals(1,state.skills.size)
        assertFalse(request.body in serialized || request.recipient in serialized)
        assertContains(AutoSkillBook.render(state.skills.single()),"本次用户要求发送")
        assertContains(AutoSkillBook.render(state.skills.single()),"{body}")
    }

    @Test fun `cross app success does not certify the unverified source app segment`() {
        val run=successful()
        val other=run.steps.first().copy(skillEvidence=run.steps.first().skillEvidence!!.copy(packageName="other.app"))
        val boundary=PhoneStep(2,PhoneAction("s",PhoneActionType.OPEN_APP,"切换",packageName=pkg),true)
        val state=learn(run=run.copy(steps=listOf(other,boundary)+run.steps))
        assertEquals(listOf(pkg),state.skills.map { it.packageName })
    }

    @Test fun `proven backtracking detour is omitted but submitted work is retained`() {
        val run=successful()
        val hint=run.steps.first().skillEvidence!!
        val detour=PhoneStep(1,PhoneAction("s",PhoneActionType.TAP,"探索","temporary"),true,
            skillEvidence=hint.copy(action=PhoneActionType.TAP,label="错误入口",parameter="",beforePage="a",afterPage="b"))
        val back=PhoneStep(2,PhoneAction("s",PhoneActionType.BACK,"返回"),true,
            skillEvidence=hint.copy(action=PhoneActionType.BACK,label="",parameter="",beforePage="b",afterPage="a"))
        val state=learn(run=run.copy(steps=listOf(detour,back)+run.steps))
        assertEquals(2,state.skills.single().current.steps.size)
        assertFalse("错误入口" in AutoSkillBook.render(state.skills.single()))
    }

    @Test fun `user corrections survive an automatic layout revision`() {
        val first=learn(); val id=first.skills.single().id
        val edited=AutoSkillBook.change(first,id,"edit","优先查找顶部搜索入口。")
        val updated=learn(edited,successful("参数乙",layout="new"))
        assertEquals("优先查找顶部搜索入口。",updated.skills.single().current.notes)
        assertEquals(3,updated.skills.single().current.number)
    }

    @Test fun `IME search with visible button fallback learns the same route as a single submission`() {
        val run=successful()
        val submit=run.steps.first { it.action.type==PhoneActionType.SUBMIT_SEARCH }
        val fallback=submit.copy(action=submit.action.copy(type=PhoneActionType.TAP),
            skillEvidence=submit.skillEvidence!!.copy(action=PhoneActionType.TAP,label="搜索",resourceId="$pkg:id/search_button"))
        val initial=learn(run=run.copy(steps=run.steps.dropLast(1)+fallback+run.steps.last()))
        assertEquals(2,initial.skills.single().current.steps.size)
        val reused=learn(initial,successful("参数乙"))
        assertEquals(1,reused.skills.single().current.number)
        assertEquals("verified",reused.skills.single().status)
    }

    @Test fun `builtin and generated knowledge share a combined maximum of two bodies`() {
        val state=learn(); val skill=state.skills.single()
        val builtin=AppSkillLibrary(listOf(AppSkillDescriptor("builtin.one","搜索笔记","搜索笔记",setOf(pkg)),AppSkillDescriptor("builtin.two","搜索笔记流程","搜索笔记",setOf(pkg)))) { "参考当前页面完成搜索。" }
        val k=PhoneKnowledge(AutoSkillBook.library(state,builtin) { "1" })
        val selected=k.context(PlannerInput("在陌生笔记搜索笔记",ScreenSnapshot("s",pkg,0,"p",emptyList()),apps,emptyList()))
        assertEquals(2,selected.skillIds.size)
        assertContains(selected.skillIds,skill.reference)
        assertTrue(selected.text.length<=6000)
    }
}
