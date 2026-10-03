package dev.ondevice.gemma.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class SystemPhoneActionsTest {
    private val apps=mapOf("agent" to "LifeBuddy","clock" to "时钟","settings" to "设置")
    private val targets=listOf(SystemTarget("alarm","闹钟","clock"),SystemTarget("timer","计时","clock"),SystemTarget("display","显示","settings"),SystemTarget("app_details","应用详情","settings"))
    private val screen=ScreenSnapshot("s","agent",0,"initial",emptyList(),systemTargets=targets,localTime="2026-10-03T20:00+08:00[Asia/Shanghai]")
    private fun alarm()=PhoneAction("s",PhoneActionType.SET_ALARM,"设置明早闹钟",packageName="clock",hour="7",minute="30",days="1,2,3,4,5",text="起床")
    private fun valid(a: PhoneAction,s: ScreenSnapshot=screen,steps: List<PhoneStep> = emptyList())=PhonePolicy.validate(a,s,apps.keys,steps=steps)

    @Test fun `OS tools are exposed only for currently discovered and allowed capabilities`() {
        val absent=PhoneTools.available(PlannerInput("设置闹钟",screen.copy(systemTargets=emptyList()),apps,emptyList()),true)
        assertTrue(setOf(PhoneActionType.OPEN_SETTINGS,PhoneActionType.SET_ALARM,PhoneActionType.SET_TIMER,PhoneActionType.SET_PROGRESS,PhoneActionType.SET_CHECKED).none { it in absent })
        val present=PhoneTools.available(PlannerInput("设置闹钟",screen,apps,emptyList()),true)
        assertTrue(setOf(PhoneActionType.OPEN_SETTINGS,PhoneActionType.SET_ALARM,PhoneActionType.SET_TIMER).all { it in present })
        assertNotNull(valid(alarm(),screen.copy(systemTargets=emptyList())))
        assertNotNull(PhonePolicy.validate(alarm(),screen,setOf("agent")))
    }
    @Test fun `alarm validates exact time weekdays target and field lengths`() {
        assertNull(valid(alarm()))
        for(a in listOf(alarm().copy(hour="24"),alarm().copy(minute="-1"),alarm().copy(days="1,1"),alarm().copy(days="0,7"),alarm().copy(days="周一"),alarm().copy(packageName="settings"),alarm().copy(text="a".repeat(81)))) assertNotNull(valid(a))
        assertEquals(emptyList(),SystemPhoneActions.days(""));assertEquals(listOf(7,1),SystemPhoneActions.days("7,1"))
    }
    @Test fun `timer only accepts an explicit bounded integer duration`() {
        fun timer(value: String)=PhoneAction("s",PhoneActionType.SET_TIMER,"倒计时",packageName="clock",seconds=value)
        assertNull(valid(timer("300")))
        for(v in listOf("0","86401","-1","NaN","2.5","五分钟")) assertNotNull(valid(timer(v)))
    }
    @Test fun `unknown setting destinations and unauthorized app details are rejected`() {
        val a=PhoneAction("s",PhoneActionType.OPEN_SETTINGS,"修改显示",destination="display")
        assertNull(valid(a));assertNotNull(valid(a.copy(destination="arbitrary.intent")))
        assertNotNull(valid(a.copy(destination="app_details",packageName="private.app")))
        assertNull(valid(a.copy(destination="app_details",packageName="clock")))
    }
    @Test fun `same alarm cannot be replayed after an interrupted dispatch even with changed label or day order`() {
        val pending=PhoneStep(1,alarm(),dispatchAttempted=true)
        assertNotNull(valid(alarm().copy(text="新名字",days="5,4,3,2,1"),steps=listOf(pending)))
        assertNull(valid(alarm().copy(hour="8"),steps=listOf(pending)))
        assertNotNull(SystemPhoneActions.completionError(screen,listOf(pending)))
    }
    @Test fun `alarm completion requires its target clock current time and label rather than editor echo`() {
        val step=PhoneStep(1,alarm(),true,dispatchAttempted=true)
        val page=screen.copy(packageName="clock",nodes=listOf(ScreenNode("result","起床 07:30 周一至周五")))
        assertNull(SystemPhoneActions.completionError(page,listOf(step)))
        assertNotNull(SystemPhoneActions.completionError(page.copy(packageName="settings"),listOf(step)))
        assertNotNull(SystemPhoneActions.completionError(page.copy(nodes=listOf(ScreenNode("edit","起床 07:30",editable=true))),listOf(step)))
        assertNotNull(SystemPhoneActions.completionError(page.copy(nodes=listOf(ScreenNode("wrong","起床 08:30"))),listOf(step)))
    }
    @Test fun `checkbox state and range updates must be read back with stable identity`() {
        val before=screen.copy(packageName="settings",nodes=listOf(ScreenNode("switch","自动亮度",clickable=true,checkable=true,checked=true),
            ScreenNode("slider","亮度",rangeMin=0f,rangeMax=255f,rangeValue=0f,canSetProgress=true)))
        val toggle=PhoneAction("s",PhoneActionType.SET_CHECKED,"关闭自动亮度","switch",checked=false)
        val progress=PhoneAction("s",PhoneActionType.SET_PROGRESS,"调整为40%","slider",value="40")
        assertNull(valid(toggle,before));assertNull(valid(progress,before))
        assertFalse(SystemPhoneActions.controlMatches(toggle,before,before))
        val after=before.copy(nodes=before.nodes.map { if(it.checkable) it.copy(id="new-switch",checked=false) else it.copy(id="new-slider",rangeValue=102f) })
        assertTrue(SystemPhoneActions.controlMatches(toggle,before,after));assertTrue(SystemPhoneActions.controlMatches(progress,before,after))
        assertNotNull(valid(progress.copy(value="NaN"),before));assertNotNull(valid(progress.copy(value="101"),before))
        assertNotNull(valid(progress,before.copy(nodes=before.nodes.map { it.copy(canSetProgress=false) })))
    }
    @Test fun `verified native results survive app switches without certifying an unseen second alarm`() {
        val first=PhoneStep(1,alarm(),true,dispatchAttempted=true)
        val page=screen.copy(packageName="clock",nodes=listOf(ScreenNode("result","起床 07:30 周一至周五 已开启")))
        val verified=SystemPhoneActions.verifiedSteps(page,listOf(first))
        assertTrue(verified.single().systemVerified)
        assertNull(SystemPhoneActions.completionError(screen.copy(packageName="settings"),verified))
        val second=PhoneStep(2,alarm().copy(hour="8"),true,dispatchAttempted=true)
        val partial=SystemPhoneActions.verifiedSteps(page,verified+second)
        assertFalse(partial.last().systemVerified)
        assertNotNull(SystemPhoneActions.completionError(page,partial))
    }
    @Test fun `checked boolean schema stays strict and old snapshots deserialize`() {
        val args=buildJsonObject { put("snapshotId","s");put("reason","关闭");put("nodeId","n");put("checked",false) }
        assertFalse(PhoneTools.decode("phone_set_checked",args).checked)
        assertFails { PhoneTools.decode("phone_set_checked",JsonObject(args+("checked" to JsonPrimitive("false")))) }
        val old=Json.decodeFromString<ScreenSnapshot>("""{"id":"s","packageName":"agent","capturedAt":0,"fingerprint":"old","nodes":[]}""")
        assertTrue(old.systemTargets.isEmpty())
    }
    @Test fun `normalized floating point sliders cannot pass when their value did not change`() {
        val before=screen.copy(nodes=listOf(ScreenNode("level","亮度",rangeMin=0f,rangeMax=1f,rangeValue=0f,canSetProgress=true)))
        val action=PhoneAction("s",PhoneActionType.SET_PROGRESS,"调到40%","level",value="40")
        assertFalse(SystemPhoneActions.controlMatches(action,before,before))
        assertTrue(SystemPhoneActions.controlMatches(action,before,before.copy(nodes=before.nodes.map { it.copy(rangeValue=.4f) })))
    }
    @Test fun `dispatch intent commits before clock call and an interrupted observation never retries`() = runBlocking {
        var run=PhoneRun(); var calls=0; var dispatched=false
        val driver=object: PhoneDriver {
            override suspend fun observe(allowed: Set<String>): ScreenSnapshot { if(dispatched) error("read interrupted");return screen }
            override suspend fun execute(action: PhoneAction,screen: ScreenSnapshot): Boolean { assertTrue(run.steps.last().dispatchAttempted);calls++;dispatched=true;return true }
            override suspend fun awaitChange()=Unit
        }
        assertFails { PhoneRunner({alarm()},driver,{_,_->error("approval")},{run=it},confirmEveryAction=false).run("设置7:30起床闹钟",apps) }
        assertEquals(1,calls);assertTrue(run.steps.last().dispatched && run.steps.last().dispatchAttempted)
    }
    @Test fun `accepted range action without readback pauses instead of claiming success`()= runBlocking {
        var run=PhoneRun()
        val page=screen.copy(packageName="settings",nodes=listOf(ScreenNode("slider","亮度",rangeMin=0f,rangeMax=100f,rangeValue=10f,canSetProgress=true)))
        val driver=object: PhoneDriver { override suspend fun observe(allowed:Set<String>)=page
            override suspend fun execute(action:PhoneAction,screen:ScreenSnapshot)=true;override suspend fun awaitChange()=Unit }
        PhoneRunner({PhoneAction("s",PhoneActionType.SET_PROGRESS,"调整","slider",value="60")},driver,{_,_->error("approval")},{run=it},confirmEveryAction=false).run("亮度改为60%",apps)
        assertEquals(RunStatus.PAUSED,run.status);assertContains(run.message,"未读回")
    }
    @Test fun `thousands of unrelated apps do not expand the system capability or skill budget`() {
        val contexts=listOf(10,100,1000).map { count ->
            val directory=apps+(1..count).associate { "example.$it" to "应用$it" }
            val k=PhoneKnowledge().context(PlannerInput("设置亮度",screen,directory,emptyList()))
            assertTrue(k.apps.size<=8 && k.skillIds.size<=2 && k.text.length<=6000)
            PhonePrompt.compactUser(PlannerInput("设置亮度",screen,k.apps,emptyList()))
        }
        assertEquals(1,contexts.distinct().size)
    }
    @Test fun `navigation can return to a previously visited page but unproductive cycles remain bounded`() = runBlocking {
        suspend fun scenario(finish: Boolean): PhoneRun {
            var current="clock";var count=0;var run=PhoneRun()
            val driver=object: PhoneDriver {
                override suspend fun observe(allowed:Set<String>)=screen.copy(packageName=current,fingerprint=current,nodes=listOf(ScreenNode("result","当前页面")))
                override suspend fun execute(action:PhoneAction,screen:ScreenSnapshot):Boolean { current=if(current=="clock") "settings" else "clock";count++;return true }
                override suspend fun awaitChange()=Unit
            }
            PhoneRunner({ i -> if(finish && count==3) PhoneAction("s",PhoneActionType.FINISH,"已往返核对",evidence="当前页面")
                else if(current=="clock") PhoneAction("s",PhoneActionType.OPEN_SETTINGS,"进入显示",destination="display")
                else PhoneAction("s",PhoneActionType.OPEN_APP,"回看时钟",packageName="clock") },driver,{_,_->error("approval")},{run=it},confirmEveryAction=false).run("核对后继续",apps)
            return run
        }
        assertEquals(RunStatus.COMPLETED,scenario(true).status)
        val loop=scenario(false);assertEquals(RunStatus.PAUSED,loop.status);assertEquals(4,loop.steps.count { it.dispatched })
    }
}
