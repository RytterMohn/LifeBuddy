package dev.ondevice.gemma.app.ui

import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.ext.gfm.tables.TableBlock
import kotlin.test.*

class MarkdownMessageTest {
    @Test fun `formatting markers become text styles instead of literal asterisks`() {
        val paragraph=ChatMarkdown.parse("你好，**重要**，以及 `128*37`。").firstChild
        val text=ChatMarkdown.inline(paragraph)
        assertEquals("你好，重要，以及 128*37。",text.text)
        assertTrue(text.spanStyles.any {it.item.fontWeight==FontWeight.Bold && text.text.substring(it.start,it.end)=="重要"})
    }
    @Test fun `streaming unfinished fence preserves code content`() {
        val doc=ChatMarkdown.parse("## 示例\n\n```kotlin\nval count = 3\n")
        assertTrue(doc.firstChild is Heading)
        val code=doc.lastChild as FencedCodeBlock
        assertEquals("val count = 3\n",code.literal)
        assertEquals("kotlin",code.info)
    }
    @Test fun `table and nested lists retain their block structure`() {
        val doc=ChatMarkdown.parse("| 工具 | 结果 |\n| --- | --- |\n| 计算器 | **4736** |\n\n- 第一项\n  - 子项\n")
        val table=doc.firstChild as TableBlock
        val row=table.children().last().firstChild
        assertEquals("4736",ChatMarkdown.inline(row.lastChild).text)
        assertEquals(2,doc.children().size)
    }
    @Test fun `unsafe link schemes cannot create clickable links`() {
        val unsafe=ChatMarkdown.inline(ChatMarkdown.parse("[点我](javascript:alert) [本地](file:///private/data)").firstChild)
        assertTrue(unsafe.getLinkAnnotations(0,unsafe.length).isEmpty())
        val safe=ChatMarkdown.inline(ChatMarkdown.parse("[文档](https://example.com/docs)").firstChild)
        assertEquals("文档",safe.text)
        assertEquals(1,safe.getLinkAnnotations(0,safe.length).size)
    }
}
