package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.Paragraph as MarkdownParagraph
import org.commonmark.parser.Parser
import java.net.URI

/** Parse Markdown as data; render native Compose text, never HTML or executable web content. */
internal object ChatMarkdown {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
    fun parse(text: String): Node = parser.parse(text)

    fun inline(node: Node): AnnotatedString = buildAnnotatedString {
        fun visit(value: Node) {
            when (value) {
                is MarkdownText -> append(value.literal)
                is SoftLineBreak -> append("\n")
                is HardLineBreak -> append("\n")
                is Code -> withStyle(SpanStyle(fontFamily=FontFamily.Monospace, background=Color(0xFFF0F0F0))) { append(value.literal) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight=FontWeight.Bold)) { value.children().forEach(::visit) }
                is Emphasis -> withStyle(SpanStyle(fontStyle=FontStyle.Italic)) { value.children().forEach(::visit) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration=TextDecoration.LineThrough)) { value.children().forEach(::visit) }
                is Link -> {
                    val safe = runCatching { URI(value.destination).scheme?.lowercase() in setOf("https", "http", "mailto") }.getOrDefault(false)
                    if (safe) withLink(LinkAnnotation.Url(value.destination, TextLinkStyles(SpanStyle(color=Green, textDecoration=TextDecoration.Underline)))) {
                        value.children().forEach(::visit)
                    } else value.children().forEach(::visit)
                }
                is Image -> { append(tr("[图片：")); value.children().forEach(::visit); append("]") }
                is HtmlInline -> append(value.literal)
                else -> value.children().forEach(::visit)
            }
        }
        node.children().forEach(::visit)
    }
}

internal fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()

@Composable
fun MarkdownMessage(markdown: String, modifier: Modifier = Modifier) {
    // Parse off the UI thread; incomplete fences/lists are valid incremental CommonMark input.
    val document by produceState<Node?>(null, markdown) {
        value = withContext(Dispatchers.Default) { ChatMarkdown.parse(markdown) }
    }
    SelectionContainer(modifier.fillMaxWidth()) {
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            document?.children()?.forEach { MarkdownBlock(it) }
        }
    }
}

@Composable
private fun MarkdownBlock(node: Node) {
    when (node) {
        is Heading -> {
            val size = when(node.level) { 1 -> 26; 2 -> 22; 3 -> 19; else -> 17 }
            Text(ChatMarkdown.inline(node),fontSize=size.sp,lineHeight=(size+9).sp,fontWeight=FontWeight.Bold,color=Ink)
        }
        is MarkdownParagraph -> Text(ChatMarkdown.inline(node),fontSize=16.sp,lineHeight=26.sp,color=Ink)
        is FencedCodeBlock -> MarkdownCode(node.literal,node.info.substringBefore(' ').take(30))
        is IndentedCodeBlock -> MarkdownCode(node.literal,"")
        is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Line))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) { node.children().forEach { MarkdownBlock(it) } }
        }
        is BulletList, is OrderedList -> Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val start = (node as? OrderedList)?.markerStartNumber ?: 1
            node.children().forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(node is OrderedList) "${start+index}." else "•",Modifier.widthIn(min=20.dp),fontSize=16.sp,lineHeight=26.sp,color=Ink)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) { item.children().forEach { MarkdownBlock(it) } }
                }
            }
        }
        is ThematicBreak -> HorizontalDivider(color=Line)
        is TableBlock -> MarkdownTable(node)
        is HtmlBlock -> Text(node.literal,fontSize=14.sp,color=Muted)
        else -> node.children().forEach { MarkdownBlock(it) }
    }
}

@Composable
private fun MarkdownCode(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Soft)) {
        Row(Modifier.fillMaxWidth().padding(start=14.dp,end=4.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(language.ifBlank { tr("代码") },Modifier.padding(vertical=14.dp),fontSize=12.sp,color=Muted)
            TextButton(onClick={clipboard.setText(AnnotatedString(code));copied=true}) { Text(if(copied) tr("已复制") else tr("复制"),fontSize=12.sp,color=Muted) }
        }
        HorizontalDivider(color=Line)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp)) {
            Text(code.trimEnd('\n'),fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=22.sp,color=Ink,softWrap=false)
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val rows = table.children().flatMap { it.children() }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp,Line,RoundedCornerShape(6.dp))) {
        rows.forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.children().forEach { cell ->
                    val header = (cell as? TableCell)?.isHeader == true
                    Box(Modifier.width(160.dp).fillMaxHeight().background(if(header) Soft else Color.White).border(0.5.dp,Line).padding(12.dp)) {
                        Text(ChatMarkdown.inline(cell),fontSize=14.sp,lineHeight=22.sp,color=Ink,
                            fontWeight=if(header) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
