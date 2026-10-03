package dev.ondevice.gemma.phone

/** Local checks bind a send to immutable user-supplied details, never model-supplied recipient/body. */
object MessagePolicy {
    fun sendAttempted(steps: List<PhoneStep>) = steps.any {
        it.action.type == PhoneActionType.SEND_MESSAGE && (it.dispatched || it.dispatchAttempted)
    }
    private fun labels(node: ScreenNode, screen: ScreenSnapshot) =
        (listOf(node) + screen.nodes.filter { it.clickTargetId == node.id }).flatMap { listOf(it.text.trim(), it.description.trim(), it.hint.trim()) }
    fun isSendControl(node: ScreenNode, screen: ScreenSnapshot) = labels(node, screen).any {
        Regex("(?i)^(发送|发送消息|发送短信|send|send message|send sms)([（(]\\d+[)）])?$").matches(it)
    }
    fun hasVisibleLabel(node: ScreenNode, screen: ScreenSnapshot) = labels(node, screen).any { it.isNotBlank() }
    fun isRestrictedControl(node: ScreenNode, screen: ScreenSnapshot) = labels(node, screen).any {
        Regex("^(付款|支付|确认支付|立即支付|转账|删除联系人|清空聊天记录|提交订单|确认下单)$").matches(it)
    }
    fun recipientMatches(node: ScreenNode, request: MessageRequest): Boolean {
        if (!node.contextHeader || node.editable) return false
        fun normalize(value: String) = if (request.channel == "sms") value.replace(Regex("[\\s()（）-]"), "") else value.trim()
        return listOf(node.text, node.description).any { normalize(it) == normalize(request.recipient) }
    }
    fun bodyCount(screen: ScreenSnapshot, request: MessageRequest) = screen.nodes.count {
        !it.editable && (it.text == request.body || it.description == request.body)
    }
    fun draftVisible(screen: ScreenSnapshot, request: MessageRequest) = screen.packageName == request.packageName &&
        screen.nodes.any { recipientMatches(it, request) } && screen.nodes.any { it.editable && it.text == request.body }

    fun validateSend(action: PhoneAction, screen: ScreenSnapshot, request: MessageRequest?, steps: List<PhoneStep>): String? {
        if (request == null) return "请先调用 phone_prepare_message 整理收件人和正文"
        if (request.draftOnly) return "本次只准备草稿，不能发送"
        if (screen.packageName != request.packageName) return "当前不是本次消息指定的应用"
        if (sendAttempted(steps)) return "本次消息已尝试发送，不会自动重发"
        val send = screen.nodes.find { it.id == action.nodeId }
        if (send?.clickable != true || send.editable || !isSendControl(send, screen)) return "发送按钮无法核对，请手动接管"
        val recipient = screen.nodes.find { it.id == action.recipientNodeId }
        if (recipient == null || !recipientMatches(recipient, request)) return "页面顶部的收件人与本次指定对象不一致或不可核对"
        val input = screen.nodes.find { it.id == action.inputNodeId }
        if (input?.editable != true || input.text != request.body) return "输入框正文与本次消息原文不一致"
        return null
    }

    fun completed(screen: ScreenSnapshot, request: MessageRequest, steps: List<PhoneStep>): Boolean {
        if (request.draftOnly) return draftVisible(screen, request) && !sendAttempted(steps)
        val send = steps.lastOrNull { it.action.type == PhoneActionType.SEND_MESSAGE && it.dispatched } ?: return false
        return screen.packageName == request.packageName && screen.nodes.any { recipientMatches(it, request) } &&
            screen.nodes.none { it.editable && it.text == request.body } && bodyCount(screen, request) > send.matchingMessagesBefore &&
            screen.nodes.none { Regex("发送失败|发送中|未发送|sending|failed", RegexOption.IGNORE_CASE).containsMatchIn(it.text + it.description) }
    }
}
