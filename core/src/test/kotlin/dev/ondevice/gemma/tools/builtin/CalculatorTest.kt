package dev.ondevice.gemma.tools.builtin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalculatorTest {

    @Test
    fun `基础四则运算`() {
        assertEquals(7.0, Calculator.eval("3+4"))
        assertEquals(6.0, Calculator.eval("2*3"))
        assertEquals(4.0, Calculator.eval("8/2"))
        assertEquals(2.0, Calculator.eval("5-3"))
    }

    @Test
    fun `括号优先级`() {
        assertEquals(16.0, Calculator.eval("(3+5)*2"))
        assertEquals(13.0, Calculator.eval("3+5*2"))
        assertEquals(2.5, Calculator.eval("(1+4)/2"))
    }

    @Test
    fun `中文符号替换`() {
        assertEquals(16.0, Calculator.eval("(3+5)×2"))
        assertEquals(4.0, Calculator.eval("8÷2"))
        assertEquals(16.0, Calculator.eval("(3+5)x2"))
    }

    @Test
    fun `小数`() {
        assertEquals(0.5, Calculator.eval("1/2"))
        assertEquals(3.14, Calculator.eval("3.14"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(Calculator.eval("abc"))
        assertNull(Calculator.eval("1+"))
        assertNull(Calculator.eval(""))
        assertNull(Calculator.eval("2**3"))
        assertNull(Calculator.eval("1+2; rm -rf /"))
    }
}
