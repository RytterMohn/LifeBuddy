package dev.ondevice.gemma.phone

import kotlin.test.*

class PhonePolicyTest {
    private val screen = ScreenSnapshot("s1", "notes", 1, "a", listOf(
        ScreenNode("n1", "保存", clickable = true),
        ScreenNode("n2", "已保存：旅行计划", editable = true),
    ))
    private val allowed = setOf("notes", "browser")
    private fun action(type: PhoneActionType) = PhoneAction("s1", type, "执行用户任务", nodeId = "n1")

    @Test fun `stale snapshot cannot act`() {
        assertNotNull(PhonePolicy.validate(action(PhoneActionType.TAP).copy(snapshotId = "old"), screen, allowed))
    }
    @Test fun `cannot open unauthorized app`() {
        assertNotNull(PhonePolicy.validate(action(PhoneActionType.OPEN_APP).copy(packageName = "bank"), screen, allowed))
    }
    @Test fun `cannot act from unauthorized foreground`() {
        assertNotNull(PhonePolicy.validate(action(PhoneActionType.BACK), screen.copy(packageName = "bank"), allowed))
    }
    @Test fun `type requires editable node`() {
        assertNotNull(PhonePolicy.validate(action(PhoneActionType.TYPE), screen, allowed))
        assertNull(PhonePolicy.validate(action(PhoneActionType.TYPE).copy(nodeId = "n2"), screen, allowed))
    }
    @Test fun `completion requires visible evidence`() {
        assertNotNull(PhonePolicy.validate(action(PhoneActionType.FINISH).copy(evidence = "任务成功"), screen, allowed))
        assertNotNull(PhonePolicy.validate(action(PhoneActionType.FINISH), screen, allowed))
        assertNull(PhonePolicy.validate(action(PhoneActionType.FINISH).copy(evidence = "已保存：旅行计划"), screen, allowed))
    }
    @Test fun `offline step by step practice requires approval while autonomous operations do not`() {
        PhoneActionType.entries.filter { it in PhonePolicy.deviceActions }
            .forEach {
                assertTrue(PhonePolicy.needsApproval(action(it), confirmEveryAction = true))
                assertFalse(PhonePolicy.needsApproval(action(it), confirmEveryAction = false))
            }
    }
    @Test fun `dispatch or visual change is not completion`() {
        assertContains(PhonePolicy.observation(action(PhoneActionType.TAP), screen, screen), "不代表任务完成")
        assertContains(PhonePolicy.observation(action(PhoneActionType.TAP), screen, screen.copy(fingerprint = "b")), "继续检查")
    }
}
