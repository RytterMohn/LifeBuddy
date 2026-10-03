package dev.ondevice.gemma.phone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import dev.ondevice.gemma.learning.LearningBook
import dev.ondevice.gemma.learning.AutoSkillTrace
import dev.ondevice.gemma.extensions.ExtensionPort
import dev.ondevice.gemma.extensions.ExtensionCatalog
import kotlinx.serialization.json.*

interface PhoneDriver {
    suspend fun observe(allowed: Set<String>): ScreenSnapshot
    suspend fun execute(action: PhoneAction, screen: ScreenSnapshot): Boolean
    suspend fun awaitChange()
}

/** A single run has one writer. Persisting an intent happens before any external action. */
class PhoneRunner(
    private val planner: PhonePlanner,
    private val driver: PhoneDriver,
    private val approve: suspend (PhoneAction, ScreenSnapshot) -> Boolean,
    private val publish: (PhoneRun) -> Unit,
    private val maxSteps: Int = 40,
    private val confirmEveryAction: Boolean = true,
    private val learning: PhoneLearning? = null,
    private val settleBeforePlanning: Boolean = false,
    private val knowledge: PhoneKnowledge = PhoneKnowledge(),
    private val captureSkills: Boolean = false,
    private val extensions: ExtensionPort? = null,
) {
    private fun since(started: Long) = (System.nanoTime() - started) / 1_000_000
    suspend fun run(goal: String, apps: Map<String, String>, messageRequest: MessageRequest? = null, resume: PhoneRun? = null,
                    continueFrom: PhoneRun? = null) {
        messageRequest?.validate()
        require(messageRequest == null || messageRequest.packageName in apps)
        require(resume == null || resume.canContinueWithReply())
        require(continueFrom == null || (resume == null && messageRequest == null && continueFrom.canAcceptTurn()))
        require(resume == null || (goal.isNotBlank() && goal.length <= 2000 && resume.followUps.size < 8))
        val started = System.nanoTime()
        var feedback = ""
        val executed = mutableSetOf<String>()
        val navigationCounts=mutableMapOf<String,Int>()
        val navigationActions=setOf(PhoneActionType.OPEN_APP,PhoneActionType.OPEN_SETTINGS)
        var run = resume?.copy(status = RunStatus.RUNNING, message = "正在根据你的补充继续", allowedPackages = apps.keys,
            followUps = resume.followUps + PhoneFollowUp(goal, resume.steps.size))
            ?: continueFrom?.let { PhoneConversation.nextTurn(it, goal, apps.keys) }
            ?: PhoneRun(UUID.randomUUID().toString(), goal, RunStatus.RUNNING, "正在观察", allowedPackages = apps.keys, messageRequest = messageRequest,
                executionId = UUID.randomUUID().toString())
        val priorElapsed = resume?.elapsedMs ?: 0L
        val priorSteps = run.steps.size
        fun update(status: RunStatus, message: String) {
            run = run.copy(status = status, message = message, elapsedMs = priorElapsed + (System.nanoTime() - started) / 1_000_000)
            publish(run)
        }
        suspend fun verifySend(initial: ScreenSnapshot) {
            val request = requireNotNull(run.messageRequest)
            var page = initial
            var readMs = 0L
            var waitMs = 0L
            var verified = false
            update(RunStatus.RUNNING, "已尝试发送，正在核对聊天页新增消息")
            try {
                for (attempt in 0..2) {
                    if (MessagePolicy.completed(page, request, run.steps)) {
                        val start = System.nanoTime()
                        page = driver.observe(apps.keys)
                        readMs += since(start)
                        if (MessagePolicy.completed(page, request, run.steps)) { verified = true; break }
                    }
                    if (page.packageName != request.packageName || attempt == 2) break
                    val waitStarted = System.nanoTime()
                    driver.awaitChange()
                    waitMs += since(waitStarted)
                    val readStarted = System.nanoTime()
                    page = driver.observe(apps.keys)
                    readMs += since(readStarted)
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                // A read failure after sending is not evidence that delivery failed.
            }
            val check = PhoneAction(page.id, PhoneActionType.VERIFY_MESSAGE, "本机核对收件人、输入框与新增消息")
            run = run.copy(messageOutcome = if (verified) MessageOutcome.VERIFIED else MessageOutcome.UNCONFIRMED,
                steps = run.steps + PhoneStep((run.steps.maxOfOrNull { it.number } ?: 0) + 1, check, true,
                    if (verified) "已核对聊天页新增消息" else "已尝试发送，但当前页面不足以确认结果；不会自动重发",
                    timing = PhoneStepTiming(observeMs = readMs, settleMs = waitMs)))
            if (verified) {
                val finish = PhoneAction(page.id, PhoneActionType.FINISH, "已在当前聊天页核对新增消息；不代表对方已收到", evidence = request.body)
                run = run.copy(steps = run.steps + PhoneStep(run.steps.last().number + 1, finish, observation = "已核对页面证据",
                    skillCompletion=if(captureSkills) AutoSkillTrace.completion(run, page, request.body) else null))
                update(RunStatus.COMPLETED, "${finish.reason}\n页面证据：${request.body}")
            } else update(RunStatus.PAUSED, "已尝试发送，结果待确认。请在聊天页核对；这不代表发送失败，不会自动重发。")
        }
        publish(run)
        if (settleBeforePlanning) driver.awaitChange()
        repeat((maxSteps - priorSteps).coerceAtLeast(0)) { turn ->
            val index = priorSteps + turn
            currentCoroutineContext().ensureActive()
            val observeStarted = System.nanoTime()
            val before = driver.observe(apps.keys)
            run=run.copy(steps=SystemPhoneActions.verifiedSteps(before,run.steps))
            var observeMs = since(observeStarted)
            if (before.packageName !in apps) {
                update(RunStatus.PAUSED, "当前应用不在允许范围，已暂停")
                return
            }
            run = run.copy(plannerRequests = run.plannerRequests + 1)
            update(RunStatus.RUNNING, "正在规划第 ${index + 1} 步")
            val planningStarted = System.nanoTime()
            val action = try { planner.next(PlannerInput(run.goal, before, apps, run.steps, feedback, run.messageRequest, run.followUps, run.previousTurns)) }
            catch (error: PhonePlanningException) {
                currentCoroutineContext().ensureActive()
                if (run.replans >= 2) { update(RunStatus.PAUSED, "模型格式连续无效，已停止规划"); return }
                run = run.copy(replans = run.replans + 1)
                feedback = error.message ?: "请返回完整的单步工具调用"
                update(RunStatus.RUNNING, "动作格式无效，正在重新观察与规划")
                return@repeat
            }
            feedback = ""
            val timing = PhoneStepTiming(planningMs=since(planningStarted), observeMs=observeMs, request=planner.lastMetrics)
            val skillEvidence=if(captureSkills) AutoSkillTrace.capture(action, before, run, apps[before.packageName].orEmpty()) else null
            currentCoroutineContext().ensureActive()
            val invalid = PhonePolicy.validate(action, before, apps.keys, run.messageRequest, run.steps)
            if (invalid != null) {
                run = run.copy(steps = run.steps + PhoneStep(index + 1, action,
                    dispatched = false, observation = "未执行：$invalid", timing=timing, skillEvidence=skillEvidence,
                    skillFailure=action.type in setOf(PhoneActionType.TAP, PhoneActionType.TYPE, PhoneActionType.LONG_PRESS, PhoneActionType.SUBMIT_SEARCH)+SystemPhoneActions.controls && before.nodes.none { it.id==action.nodeId }))
                val sendNode = before.nodes.find { it.id == action.nodeId }
                if (action.type == PhoneActionType.TAP && sendNode != null && MessagePolicy.isSendControl(sendNode, before) &&
                    run.messageRequest?.draftOnly != true && !MessagePolicy.sendAttempted(run.steps) && run.replans < 2) {
                    // No click occurred. Repair a tool-selection mistake within the existing retry budget.
                    run = run.copy(replans = run.replans + 1)
                    feedback = if (run.messageRequest == null)
                        "发送按钮尚未点击。必须先调用 phone_prepare_message 从用户原话整理应用、收件人与正文；然后调用 phone_send_message，不能用 phone_tap 发送。无需向用户再次索要发送许可。"
                    else "发送按钮尚未点击。请使用已开放的 phone_send_message 并提供收件人、输入框和发送按钮节点，不能用 phone_tap 发送；无需再次询问许可。"
                    update(RunStatus.RUNNING, "正在调整发送工具，尚未发送")
                    return@repeat
                }
                update(RunStatus.PAUSED, invalid)
                return
            }
            val signature = listOf(before.fingerprint, action.type.name, action.nodeId, action.text, action.packageName, action.direction, action.recipient, action.draftOnly,
                action.destination,action.hour,action.minute,action.days,action.seconds,action.value,action.checked).toString()
            val repeated=if(action.type in navigationActions) (navigationCounts[signature] ?: 0)>=2 else signature in executed
            if (action.type !in PhoneTools.readOnly + setOf(PhoneActionType.WAIT, PhoneActionType.FINISH, PhoneActionType.ASK_USER) && repeated) {
                update(RunStatus.PAUSED, "相同页面上的相同操作已执行过，已阻止重复提交；请检查当前结果")
                return
            }
            run = run.copy(steps = run.steps + PhoneStep(index + 1, action,
                matchingMessagesBefore = run.messageRequest?.let { MessagePolicy.bodyCount(before, it) } ?: 0,
                navigation = if (learning == null) null else LearningBook.navigation(action, before,
                    run.goal + run.followUps.joinToString { it.text }, apps[before.packageName].orEmpty()), timing=timing, skillEvidence=skillEvidence))
            publish(run)
            if (action.type in PhoneTools.extensionActions) {
                val port = extensions
                if (port == null || !port.available) { update(RunStatus.PAUSED, "扩展不可用 / Extensions unavailable"); return }
                val externalStarted = System.nanoTime()
                if (action.type != PhoneActionType.CALL_EXTENSION) {
                    val result = try {
                        if (action.type == PhoneActionType.SEARCH_EXTENSIONS) port.search(action.query, action.cursor)
                        else port.read(action.extensionId, action.resource, action.cursor)
                    } catch (error: IllegalArgumentException) { "Extension reference error / 扩展引用错误" }
                    catch (error: IllegalStateException) { "Extension unavailable; search again / 扩展不可用，请重新搜索" }
                    run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatched = true, observation = result,
                        timing = timing.copy(executeMs = since(externalStarted))))
                    publish(run)
                    if (run.steps.takeLast(3).size == 3 && run.steps.takeLast(3).all {
                        it.action.type == action.type && it.action.extensionId == action.extensionId && it.action.resource == action.resource && it.action.cursor == action.cursor && it.observation == result
                    }) { update(RunStatus.PAUSED, "扩展读取没有进展 / Extension lookup made no progress"); return }
                    return@repeat
                }
                val args = Json.parseToJsonElement(action.argumentsJson).jsonObject
                val duplicate = run.steps.dropLast(1).any { step -> step.action.type == PhoneActionType.CALL_EXTENSION && step.dispatchAttempted &&
                    step.action.extensionId == action.extensionId && runCatching { ExtensionCatalog.canonical(Json.parseToJsonElement(step.action.argumentsJson)) }.getOrNull() == ExtensionCatalog.canonical(args) }
                if (duplicate) { update(RunStatus.PAUSED, "本轮外部调用已尝试，已阻止重复执行 / External call already attempted; duplicate blocked"); return }
                val validation = try { port.validateCall(action.extensionId, args); null }
                catch (error: IllegalArgumentException) { "Tool parameters invalid; read the schema and correct arguments / 工具参数不符，请重新读取定义" }
                catch (error: IllegalStateException) { "Read an enabled tool before calling it / 请先读取已启用的工具" }
                if (validation != null) {
                    feedback = validation
                    run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(observation = validation))
                    if (run.replans >= 2) { update(RunStatus.PAUSED, validation); return }
                    run = run.copy(replans = run.replans + 1)
                    publish(run)
                    return@repeat
                }
                currentCoroutineContext().ensureActive()
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatchAttempted = true, observation = "External call started; result pending / 外部调用已开始，结果待确认"))
                publish(run)
                val result = try { port.call(action.extensionId, args) }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    update(RunStatus.PAUSED, "外部调用中断，结果待确认；不会自动重试，请先检查服务中的结果 / External call interrupted; outcome unknown. Check the service before retrying.")
                    return
                }
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatched = true, extensionSucceeded = !result.isError,
                    observation = result.text.take(12_000), timing = timing.copy(executeMs = since(externalStarted))))
                publish(run)
                feedback = if (result.isError) "The external tool reported an error; do not claim success / 外部工具返回错误，不能声称完成"
                    else "External result received. For a tool-only task you may now phone_respond based on the returned data. For phone mutations still verify the visible page / 仅外部工具任务可依据返回回答；手机操作仍须核对页面"
                return@repeat
            }
            if (action.type == PhoneActionType.NOTE_PREFERENCE) {
                val result = learning?.proposePreference(listOf(run.goal) + run.followUps.map { it.text }, action.text, action.packageName, run.id)
                    ?: "学习已关闭，未记录任何候选"
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatched = true, observation = result))
                executed += signature
                publish(run)
                return@repeat
            }
            if (action.type == PhoneActionType.PREPARE_MESSAGE) {
                run = run.copy(messageRequest = action.messageRequest(), steps = run.steps.dropLast(1) + run.steps.last().copy(
                    dispatched = true, observation = "已整理消息对象与原文；尚未操作应用或发送。发送任务将核对页面后直接发送，草稿任务不发送。"))
                executed += signature
                publish(run)
                return@repeat
            }
            if (action.type == PhoneActionType.RESPOND) {
                update(RunStatus.COMPLETED, action.text)
                return
            }
            if (action.type in PhoneTools.readOnly) {
                val result = PhoneTools.read(action, before, apps, run.messageRequest, run.steps, knowledge)
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatched = true, observation = result))
                publish(run)
                if (run.steps.takeLast(3).size == 3 && run.steps.takeLast(3).all { it.action.type == action.type && it.action.query == action.query && it.action.cursor == action.cursor && it.action.skillId == action.skillId && it.observation == result }) {
                    update(RunStatus.PAUSED, "连续读取同一结果，没有继续推进；已暂停")
                    return
                }
                return@repeat
            }
            if (action.type == PhoneActionType.ASK_USER) {
                update(RunStatus.PAUSED, action.reason)
                return
            }
            if (action.type == PhoneActionType.FINISH) {
                val verifyStarted = System.nanoTime()
                val current = driver.observe(apps.keys)
                run = run.copy(steps=run.steps.dropLast(1) + run.steps.last().copy(timing=timing.copy(observeMs=observeMs + since(verifyStarted))))
                if (current.packageName != before.packageName || current.fingerprint != before.fingerprint ||
                    !current.containsText(action.evidence) || PhonePolicy.validate(action.copy(snapshotId = current.id), current, apps.keys, run.messageRequest, run.steps) != null) {
                    update(RunStatus.PAUSED, "规划期间页面已变化，无法确认完成")
                    return
                }
                if(captureSkills) run=run.copy(steps=run.steps.dropLast(1)+run.steps.last().copy(skillCompletion=AutoSkillTrace.completion(run, current, action.evidence)))
                val result = when {
                    run.messageRequest?.draftOnly == true -> "消息草稿已准备，尚未发送"
                    run.messageRequest != null -> "已在当前聊天页核对新增消息；不代表对方已收到"
                    else -> action.reason
                }
                update(RunStatus.COMPLETED, "$result\n页面证据：${action.evidence}")
                return
            }
            if (PhonePolicy.needsApproval(action, confirmEveryAction)) {
                update(RunStatus.WAITING_APPROVAL, action.reason)
                if (!approve(action, before)) {
                    update(RunStatus.PAUSED, "用户拒绝该步骤，已暂停")
                    return
                }
            }
            currentCoroutineContext().ensureActive()
            val freshStarted = System.nanoTime()
            val fresh = driver.observe(apps.keys)
            observeMs += since(freshStarted)
            run = run.copy(steps=run.steps.dropLast(1) + run.steps.last().copy(timing=timing.copy(observeMs=observeMs)))
            if (fresh.packageName != before.packageName || fresh.fingerprint != before.fingerprint) {
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(observation = "页面已变化；旧动作未执行"))
                if (fresh.packageName !in apps || run.replans >= 2) {
                    update(RunStatus.PAUSED, "页面已变化且无法稳定规划；旧动作未执行，请检查页面")
                    return
                }
                run = run.copy(replans = run.replans + 1)
                feedback = "规划期间页面变化，旧动作未执行。请依据新快照重新规划。" + if (confirmEveryAction) "新动作仍须重新确认。" else ""
                update(RunStatus.RUNNING, "页面已变化，正在重新观察与规划")
                return@repeat
            }
            val currentAction = action.copy(snapshotId = fresh.id)
            PhonePolicy.validate(currentAction, fresh, apps.keys, run.messageRequest, run.steps)?.let {
                run=run.copy(steps=run.steps.dropLast(1)+run.steps.last().copy(skillFailure=fresh.nodes.none { n -> n.id==currentAction.nodeId }))
                update(RunStatus.PAUSED, it)
                return
            }
            if ((action.type == PhoneActionType.OPEN_APP && action.packageName != fresh.packageName) || action.type==PhoneActionType.OPEN_SETTINGS || action.type in SystemPhoneActions.creates) {
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(sourcePage=PhoneTaskContext.capture(fresh)))
            }
            update(RunStatus.RUNNING, "执行：${action.reason}")
            if (action.type == PhoneActionType.SEND_MESSAGE) {
                run = run.copy(messageOutcome = MessageOutcome.UNCONFIRMED,
                    steps = run.steps.dropLast(1) + run.steps.last().copy(dispatchAttempted = true,
                        observation = "已开始尝试发送；结果待确认，不会自动重发"))
                publish(run)
            }
            val executeStarted = System.nanoTime()
            if(action.type in SystemPhoneActions.creates) {
                run=run.copy(steps=run.steps.dropLast(1)+run.steps.last().copy(dispatchAttempted=true,observation="已开始请求系统创建；中断后先核对，不自动重试"))
                publish(run)
            }
            val accepted = driver.execute(currentAction, fresh)
            val executeMs = since(executeStarted)
            if (action.type == PhoneActionType.SEND_MESSAGE || action.type in SystemPhoneActions.creates) {
                // Checkpoint the accepted click before waiting/reading, both of which can fail.
                run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatched = accepted,
                    observation = if(action.type in SystemPhoneActions.creates) "系统创建已请求；仍需核对实际页面，不自动重复创建"
                        else if (accepted) "发送按钮已点击，正在核对结果" else "发送动作已尝试，系统未确认接受；不会自动重试",
                    timing = timing.copy(observeMs = observeMs, executeMs = executeMs)))
                publish(run)
            }
            currentCoroutineContext().ensureActive()
            val settleStarted = System.nanoTime()
            if (accepted) {
                executed += signature
                if(action.type in navigationActions) navigationCounts[signature]=(navigationCounts[signature] ?: 0)+1
                else if(action.type in PhonePolicy.deviceActions) navigationCounts.clear()
                driver.awaitChange()
            }
            val settleMs = if(accepted) since(settleStarted) else 0L
            val afterStarted = System.nanoTime()
            val after = driver.observe(apps.keys)
            observeMs += since(afterStarted)
            val observation = if (accepted) PhonePolicy.observation(action, fresh, after) else "系统未接受此动作"
            run = run.copy(steps = run.steps.dropLast(1) + run.steps.last().copy(dispatched = accepted, observation = observation,
                timing=timing.copy(observeMs=observeMs,executeMs=executeMs,settleMs=settleMs),
                skillEvidence=AutoSkillTrace.after(skillEvidence, action, fresh, after)))
            run=run.copy(steps=SystemPhoneActions.verifiedSteps(after,run.steps))
            publish(run)
            if(accepted && action.type in SystemPhoneActions.controls && !SystemPhoneActions.controlMatches(action,fresh,after)) {
                update(RunStatus.PAUSED,"已尝试修改控件，但未读回目标状态；请检查当前设置，不能据此声称已完成")
                return
            }
            if (action.type == PhoneActionType.SEND_MESSAGE) {
                verifySend(after)
                return
            }
            if (!accepted || after.packageName !in apps.keys) {
                update(RunStatus.PAUSED, if (!accepted) observation else "界面切换到未授权应用，已暂停")
                return
            }
            if (run.steps.takeLast(3).size == 3 && run.steps.takeLast(3).all {
                it.observation.startsWith("界面未发生")
            }) {
                update(RunStatus.PAUSED, "连续三步没有可观察变化，停止重复操作")
                return
            }
        }
        update(RunStatus.PAUSED, "已达到 $maxSteps 步上限；任务未确认完成")
    }
}
