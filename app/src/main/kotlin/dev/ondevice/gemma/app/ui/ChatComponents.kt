package dev.ondevice.gemma.app.ui

import dev.ondevice.gemma.app.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ondevice.gemma.app.R

internal val Ink = Color(0xFF202123)
internal val Muted = Color(0xFF77797D)
internal val Soft = Color(0xFFF4F4F4)
internal val Line = Color(0xFFE9E9E9)
internal val Green = Color(0xFF17836B)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Ink, onPrimary = Color.White, primaryContainer = Soft, onPrimaryContainer = Ink,
        secondary = Green, background = Color.White, surface = Color.White,
        onSurface = Ink, onBackground = Ink, onSurfaceVariant = Muted,
        surfaceVariant = Soft, outline = Color(0xFFD5D5D5), outlineVariant = Line,
        surfaceContainer = Soft, surfaceContainerHigh = Soft,
    ), content = content)
}

/** Small native line icons, with accessible labels supplied by their controls. */
@Composable
internal fun Glyph(name: String, modifier: Modifier = Modifier, color: Color = Ink) {
    Canvas(modifier.size(22.dp)) {
        val u = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), 1.7f*u, StrokeCap.Round)
        fun path(vararg points: Pair<Float, Float>) {
            val p = Path()
            points.forEachIndexed { i, (x,y) -> if (i == 0) p.moveTo(x*u,y*u) else p.lineTo(x*u,y*u) }
            drawPath(p,color,style=Stroke(1.7f*u,cap=StrokeCap.Round))
        }
        when (name) {
            "search" -> { drawCircle(color,6.5f*u,Offset(10f*u,10f*u),style=Stroke(1.7f*u)); line(15f,15f,21f,21f) }
            "more" -> { listOf(5f,12f,19f).forEach { drawCircle(color,1.5f*u,Offset(it*u,12f*u)) } }
            "trash" -> { line(4f,6f,20f,6f); path(8f to 6f,8f to 3f,16f to 3f,16f to 6f); path(6f to 6f,7f to 21f,17f to 21f,18f to 6f); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
            "menu" -> { line(4f,8f,20f,8f); line(4f,16f,14f,16f) }
            "new" -> { path(13f to 5f,5f to 5f,5f to 20f,20f to 20f,20f to 12f); path(10f to 14f,11f to 10f,19f to 2f,22f to 5f,14f to 13f,10f to 14f) }
            "arrow" -> { line(12f,19f,12f,5f); path(6f to 11f,12f to 5f,18f to 11f) }
            "chevron" -> path(8f to 10f,12f to 14f,16f to 10f)
            "right" -> path(10f to 6f,16f to 12f,10f to 18f)
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "plus" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            "check" -> path(5f to 12f,10f to 17f,19f to 7f)
            "pause" -> { line(8f,5f,8f,19f); line(16f,5f,16f,19f) }
            "stop" -> drawRoundRect(color,Offset(6*u,6*u),Size(12*u,12*u),androidx.compose.ui.geometry.CornerRadius(2*u))
            "phone" -> { drawRoundRect(color,Offset(6*u,2*u),Size(12*u,20*u),androidx.compose.ui.geometry.CornerRadius(3*u),style=Stroke(1.7f*u)); line(10f,18f,14f,18f) }
            "settings" -> { line(3f,7f,21f,7f); line(3f,17f,21f,17f); drawCircle(Color.White,3*u,Offset(9*u,7*u)); drawCircle(color,3*u,Offset(9*u,7*u),style=Stroke(1.7f*u)); drawCircle(Color.White,3*u,Offset(16*u,17*u)); drawCircle(color,3*u,Offset(16*u,17*u),style=Stroke(1.7f*u)) }
            "chat" -> path(5f to 4f,20f to 4f,20f to 17f,10f to 17f,4f to 21f,4f to 4f,5f to 4f)
            else -> { path(12f to 2f,15f to 9f,22f to 12f,15f to 15f,12f to 22f,9f to 15f,2f to 12f,9f to 9f,12f to 2f) }
        }
    }
}

@Composable
internal fun GlyphButton(name: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Glyph(name, color = if (enabled) Ink else Muted.copy(alpha=.4f))
    }
}

@Composable
internal fun AgentMark(size: Int = 36) {
    Image(painter = painterResource(R.drawable.ic_agent), contentDescription = "LifeBuddy",
        modifier = Modifier.size(size.dp))
}

@Composable
internal fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        Text(text,Modifier.widthIn(max=300.dp).clip(RoundedCornerShape(24.dp)).background(Soft).padding(horizontal=18.dp,vertical=13.dp),
            fontSize=16.sp,lineHeight=24.sp)
    }
}

@Composable
internal fun Composer(
    value: String, onChange: (String) -> Unit, placeholder: String,
    mode: String, onMode: () -> Unit, onSend: () -> Unit,
    enabled: Boolean, busy: Boolean = false, onStop: () -> Unit = {}, readOnly: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=8.dp,bottom=8.dp)
        .clip(RoundedCornerShape(28.dp)).background(Soft).padding(8.dp)) {
        BasicTextField(value, onChange, modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp).heightIn(min=26.dp,max=130.dp),
            enabled=!busy, readOnly=readOnly, textStyle=LocalTextStyle.current.copy(color=Ink,fontSize=16.sp,lineHeight=24.sp),
            cursorBrush=SolidColor(Ink), decorationBox={ inner ->
                Box { if(value.isEmpty()) Text(placeholder,color=Muted,fontSize=16.sp); inner() }
            })
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape).clickable(enabled=!busy,onClick=onMode).padding(horizontal=12.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Glyph("settings",Modifier.size(18.dp))
                Text(mode,fontSize=12.sp,fontWeight=FontWeight.Medium)
                Glyph("chevron",Modifier.size(14.dp),Muted)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick=if(busy) onStop else onSend, enabled=enabled || busy,
                modifier=Modifier.size(40.dp).clip(CircleShape).background(if(enabled || busy) Ink else Color(0xFFDEDEDE))
                    .semantics { contentDescription = if(busy) tr("停止任务") else tr("发送任务") }) {
                Glyph(if(busy) "stop" else "arrow",color=Color.White)
            }
        }
    }
}

@Composable
internal fun SheetHeading(title: String, subtitle: String) {
    Text(title,fontSize=23.sp,fontWeight=FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(subtitle,fontSize=14.sp,lineHeight=21.sp,color=Muted)
    Spacer(Modifier.height(16.dp))
}

@Composable
internal fun OptionRow(icon: String, title: String, detail: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if(selected) Soft else Color.White)
        .clickable(onClick=onClick).padding(16.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Glyph(icon)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Medium)
            Text(detail,fontSize=12.sp,lineHeight=18.sp,color=Muted)
        }
        Glyph(if(selected) "check" else "right",Modifier.size(18.dp),if(selected) Green else Muted)
    }
}
