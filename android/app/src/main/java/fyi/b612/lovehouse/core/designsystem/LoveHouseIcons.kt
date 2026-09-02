package fyi.b612.lovehouse.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class LoveHouseIcon {
    Back, Close, More, Search, Settings, Plus, Expand, Collapse, Home,
    Call, Chat, Message, Mail, Send, Contact, Bell,
    Copy, Transcribe, Play, ReadAloud, Retry, Regenerate, Translate, Reply, Forward, Delete,
    Camera, Photo, File, Location, Mic, VoiceMessage, ModelSwitch, CatPawSend,
    Calendar, Clock, Music, Cart, Wallet, Travel, Car, Computer, Emoji, Volume, Weather,
    Wrench, Star,
}

enum class LoveHouseIconOpticalSize { Regular, Compact }

@Composable
fun LoveHouseIconView(
    icon: LoveHouseIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LoveHouseGlass.Ink,
    opticalSize: LoveHouseIconOpticalSize = LoveHouseIconOpticalSize.Regular,
) {
    Canvas(
        modifier.then(
            if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription },
        ),
    ) {
        drawLoveHouseIcon(icon, tint, opticalSize)
    }
}

private fun DrawScope.drawLoveHouseIcon(icon: LoveHouseIcon, tint: Color, opticalSize: LoveHouseIconOpticalSize) {
    val u = size.minDimension / 24f
    val ox = (size.width - 24f * u) / 2f
    val oy = (size.height - 24f * u) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val strokeWidth = if (opticalSize == LoveHouseIconOpticalSize.Compact) 1.12.dp.toPx() else 1.55.dp.toPx()
    val line = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun segment(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth, StrokeCap.Round)
    fun circle(x: Float, y: Float, r: Float, filled: Boolean = false) = drawCircle(tint, r * u, p(x, y), style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else line)
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float = 2.5f) = drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = line)
    fun strokedPath(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = line)
    fun arc(start: Float, sweep: Float, x: Float, y: Float, w: Float, h: Float) = drawArc(tint, start, sweep, false, p(x, y), Size(w * u, h * u), style = line)

    when (icon) {
        LoveHouseIcon.Back -> { segment(15.5f, 5f, 8.5f, 12f); segment(8.5f, 12f, 15.5f, 19f) }
        LoveHouseIcon.Close -> { segment(6.5f, 6.5f, 17.5f, 17.5f); segment(17.5f, 6.5f, 6.5f, 17.5f) }
        LoveHouseIcon.More -> listOf(6f, 12f, 18f).forEach { circle(it, 12f, 1f, true) }
        LoveHouseIcon.Search -> { circle(10.5f, 10.5f, 5.8f); segment(14.8f, 14.8f, 19.5f, 19.5f) }
        LoveHouseIcon.Settings -> {
            circle(12f, 12f, 3f)
            repeat(8) { i ->
                val a = Math.toRadians(i * 45.0)
                segment((12 + cos(a) * 6).toFloat(), (12 + sin(a) * 6).toFloat(), (12 + cos(a) * 8).toFloat(), (12 + sin(a) * 8).toFloat())
            }
        }
        LoveHouseIcon.Plus -> { segment(5.5f, 12f, 18.5f, 12f); segment(12f, 5.5f, 12f, 18.5f) }
        LoveHouseIcon.Expand -> { segment(6f, 9f, 12f, 15f); segment(12f, 15f, 18f, 9f) }
        LoveHouseIcon.Collapse -> { segment(6f, 15f, 12f, 9f); segment(12f, 9f, 18f, 15f) }
        LoveHouseIcon.Home -> strokedPath { moveTo(p(4f, 11f).x, p(4f, 11f).y); lineTo(p(12f, 4.5f).x, p(12f, 4.5f).y); lineTo(p(20f, 11f).x, p(20f, 11f).y); lineTo(p(18.5f, 11f).x, p(18.5f, 11f).y); lineTo(p(18.5f, 19.5f).x, p(18.5f, 19.5f).y); lineTo(p(5.5f, 19.5f).x, p(5.5f, 19.5f).y); lineTo(p(5.5f, 11f).x, p(5.5f, 11f).y) }
        LoveHouseIcon.Call -> strokedPath {
            moveTo(p(7.2f, 4.5f).x, p(7.2f, 4.5f).y)
            cubicTo(p(5.3f, 5.2f).x, p(5.3f, 5.2f).y, p(5.2f, 7.2f).x, p(5.2f, 7.2f).y, p(6.1f, 9.5f).x, p(6.1f, 9.5f).y)
            cubicTo(p(7.8f, 13.7f).x, p(7.8f, 13.7f).y, p(10.4f, 16.3f).x, p(10.4f, 16.3f).y, p(14.6f, 18f).x, p(14.6f, 18f).y)
            cubicTo(p(16.9f, 18.9f).x, p(16.9f, 18.9f).y, p(18.8f, 18.7f).x, p(18.8f, 18.7f).y, p(19.5f, 16.8f).x, p(19.5f, 16.8f).y)
            lineTo(p(16f, 14.7f).x, p(16f, 14.7f).y); lineTo(p(13.9f, 16.2f).x, p(13.9f, 16.2f).y)
            cubicTo(p(11.2f, 14.9f).x, p(11.2f, 14.9f).y, p(9f, 12.7f).x, p(9f, 12.7f).y, p(7.7f, 10f).x, p(7.7f, 10f).y)
            lineTo(p(9.2f, 7.9f).x, p(9.2f, 7.9f).y); close()
        }
        LoveHouseIcon.Chat -> { roundRect(3.5f, 4.5f, 17f, 13f, 4f); segment(7f, 17.5f, 5.5f, 20f); segment(7f, 17.5f, 10f, 17.5f) }
        LoveHouseIcon.Message -> { roundRect(4f, 5f, 16f, 12f, 3f); segment(7f, 9f, 17f, 9f); segment(7f, 13f, 14f, 13f); segment(16f, 17f, 18f, 19f) }
        LoveHouseIcon.Mail -> { roundRect(3.5f, 5.5f, 17f, 13f, 2f); segment(4.5f, 7f, 12f, 13f); segment(19.5f, 7f, 12f, 13f) }
        LoveHouseIcon.Send -> strokedPath { moveTo(p(3.5f, 5f).x, p(3.5f, 5f).y); lineTo(p(21f, 12f).x, p(21f, 12f).y); lineTo(p(3.5f, 19f).x, p(3.5f, 19f).y); lineTo(p(7f, 12f).x, p(7f, 12f).y); close() }
        LoveHouseIcon.Contact -> { circle(12f, 8.2f, 3.2f); arc(195f, 150f, 5f, 11f, 14f, 9f) }
        LoveHouseIcon.Bell -> { arc(180f, 180f, 6f, 5f, 12f, 14f); segment(6f, 12f, 4.5f, 17f); segment(4.5f, 17f, 19.5f, 17f); segment(19.5f, 17f, 18f, 12f); arc(20f, 140f, 9f, 16f, 6f, 4f) }
        LoveHouseIcon.Copy -> {
            roundRect(if (opticalSize == LoveHouseIconOpticalSize.Compact) 7f else 7.5f, 6.5f, 11f, 12f, 2f)
            segment(5f, 15.5f, 5f, 5f); segment(5f, 5f, 15.5f, 5f)
        }
        LoveHouseIcon.Transcribe -> { segment(5f, 7f, 19f, 7f); segment(5f, 12f, 15f, 12f); segment(5f, 17f, 12f, 17f); segment(17f, 14f, 20f, 17f); segment(20f, 17f, 17f, 20f) }
        LoveHouseIcon.Play -> strokedPath { moveTo(p(8f, 5f).x, p(8f, 5f).y); lineTo(p(19f, 12f).x, p(19f, 12f).y); lineTo(p(8f, 19f).x, p(8f, 19f).y); close() }
        LoveHouseIcon.ReadAloud, LoveHouseIcon.Volume -> {
            strokedPath { moveTo(p(4f, 10f).x, p(4f, 10f).y); lineTo(p(8f, 10f).x, p(8f, 10f).y); lineTo(p(12f, 6.5f).x, p(12f, 6.5f).y); lineTo(p(12f, 17.5f).x, p(12f, 17.5f).y); lineTo(p(8f, 14f).x, p(8f, 14f).y); lineTo(p(4f, 14f).x, p(4f, 14f).y); close() }
            arc(-48f, 96f, 11f, 7.5f, 7f, 9f)
            if (opticalSize == LoveHouseIconOpticalSize.Regular) arc(-45f, 90f, 12f, 4f, 10f, 16f)
        }
        LoveHouseIcon.Retry -> { arc(-55f, 285f, 5f, 5f, 14f, 14f); segment(5.3f, 5.6f, 5.5f, 10f); segment(5.3f, 5.6f, 9.7f, 6f) }
        LoveHouseIcon.Regenerate -> { arc(190f, 205f, 4.5f, 4.5f, 15f, 15f); segment(4.7f, 9f, 5f, 4.8f); segment(5f, 4.8f, 9.1f, 5.3f); arc(10f, 145f, 4.5f, 4.5f, 15f, 15f); segment(19.3f, 15f, 19f, 19.2f); segment(19f, 19.2f, 14.9f, 18.7f) }
        LoveHouseIcon.Translate -> {
            segment(4f, 19f, 8f, 5f); segment(8f, 5f, 12f, 19f); segment(5.5f, 14f, 10.5f, 14f)
            segment(13f, 8f, 21f, 8f); segment(17f, 5f, 17f, 17f); arc(0f, 155f, 13f, 9f, 8f, 9f)
        }
        LoveHouseIcon.Reply -> { segment(10f, 7f, 4f, 12f); segment(4f, 12f, 10f, 17f); arc(190f, 125f, 7f, 8f, 13f, 10f) }
        LoveHouseIcon.Forward -> { segment(14f, 7f, 20f, 12f); segment(20f, 12f, 14f, 17f); arc(225f, 125f, 4f, 8f, 13f, 10f) }
        LoveHouseIcon.Delete -> { roundRect(7f, 7.5f, 10f, 12f, 1.5f); segment(5f, 6f, 19f, 6f); segment(9f, 3.8f, 15f, 3.8f); segment(10f, 10f, 10f, 17f); segment(14f, 10f, 14f, 17f) }
        LoveHouseIcon.Camera -> { roundRect(3.5f, 7f, 17f, 12f, 2.5f); segment(8f, 7f, 9.5f, 4.5f); segment(9.5f, 4.5f, 14.5f, 4.5f); segment(14.5f, 4.5f, 16f, 7f); circle(12f, 13f, 3.3f) }
        LoveHouseIcon.Photo -> { roundRect(3.5f, 4f, 17f, 16f, 2f); circle(16f, 8f, 1.4f); strokedPath { moveTo(p(5f, 18f).x, p(5f, 18f).y); lineTo(p(10f, 12f).x, p(10f, 12f).y); lineTo(p(13f, 15f).x, p(13f, 15f).y); lineTo(p(16f, 12f).x, p(16f, 12f).y); lineTo(p(19f, 17f).x, p(19f, 17f).y) } }
        LoveHouseIcon.File -> { strokedPath { moveTo(p(6f, 3.5f).x, p(6f, 3.5f).y); lineTo(p(14f, 3.5f).x, p(14f, 3.5f).y); lineTo(p(19f, 8.5f).x, p(19f, 8.5f).y); lineTo(p(19f, 20.5f).x, p(19f, 20.5f).y); lineTo(p(6f, 20.5f).x, p(6f, 20.5f).y); close() }; segment(14f, 3.5f, 14f, 8.5f); segment(14f, 8.5f, 19f, 8.5f) }
        LoveHouseIcon.Location -> { strokedPath { moveTo(p(12f, 21f).x, p(12f, 21f).y); cubicTo(p(9f, 16f).x, p(9f, 16f).y, p(6f, 13f).x, p(6f, 13f).y, p(6f, 9.5f).x, p(6f, 9.5f).y); cubicTo(p(6f, 5.8f).x, p(6f, 5.8f).y, p(8.6f, 3.5f).x, p(8.6f, 3.5f).y, p(12f, 3.5f).x, p(12f, 3.5f).y); cubicTo(p(15.4f, 3.5f).x, p(15.4f, 3.5f).y, p(18f, 5.8f).x, p(18f, 5.8f).y, p(18f, 9.5f).x, p(18f, 9.5f).y); cubicTo(p(18f, 13f).x, p(18f, 13f).y, p(15f, 16f).x, p(15f, 16f).y, p(12f, 21f).x, p(12f, 21f).y) }; circle(12f, 9.5f, 2.2f) }
        LoveHouseIcon.Mic, LoveHouseIcon.VoiceMessage -> { roundRect(9f, 3.5f, 6f, 11f, 3f); arc(0f, 180f, 6.5f, 8f, 11f, 9f); segment(12f, 17f, 12f, 21f); segment(8.5f, 21f, 15.5f, 21f) }
        LoveHouseIcon.ModelSwitch -> { circle(8f, 12f, 3.5f); circle(16f, 12f, 3.5f); segment(11.5f, 12f, 12.5f, 12f); segment(5f, 6f, 19f, 6f) }
        LoveHouseIcon.CatPawSend -> {
            circle(6.2f, 8.2f, 1.65f); circle(10.2f, 5.5f, 1.75f); circle(14.5f, 5.7f, 1.75f); circle(18f, 8.8f, 1.65f)
            strokedPath { moveTo(p(12f, 9.3f).x, p(12f, 9.3f).y); cubicTo(p(8.7f, 9.3f).x, p(8.7f, 9.3f).y, p(6.8f, 13.2f).x, p(6.8f, 13.2f).y, p(8.1f, 16.4f).x, p(8.1f, 16.4f).y); cubicTo(p(9.2f, 19.2f).x, p(9.2f, 19.2f).y, p(11.2f, 17.7f).x, p(11.2f, 17.7f).y, p(12.8f, 17.7f).x, p(12.8f, 17.7f).y); cubicTo(p(14.4f, 17.7f).x, p(14.4f, 17.7f).y, p(16.5f, 19f).x, p(16.5f, 19f).y, p(17.3f, 16.2f).x, p(17.3f, 16.2f).y); cubicTo(p(18.2f, 13f).x, p(18.2f, 13f).y, p(15.2f, 9.3f).x, p(15.2f, 9.3f).y, p(12f, 9.3f).x, p(12f, 9.3f).y); close() }
        }
        LoveHouseIcon.Calendar -> { roundRect(4f, 5.5f, 16f, 14.5f, 2.5f); segment(4f, 9f, 20f, 9f); segment(8f, 3.5f, 8f, 7f); segment(16f, 3.5f, 16f, 7f) }
        LoveHouseIcon.Clock -> { circle(12f, 12f, 8f); segment(12f, 7f, 12f, 12f); segment(12f, 12f, 16f, 14f) }
        LoveHouseIcon.Music -> { segment(10f, 5f, 10f, 17f); segment(10f, 5f, 19f, 3.5f); segment(19f, 3.5f, 19f, 15f); circle(7f, 18f, 3f); circle(16f, 16f, 3f) }
        LoveHouseIcon.Cart -> { segment(3.5f, 5f, 6f, 5f); segment(6f, 5f, 8f, 16f); segment(8f, 16f, 18f, 16f); segment(7f, 8f, 20f, 8f); segment(20f, 8f, 18f, 14f); circle(9f, 19f, 1.4f); circle(17f, 19f, 1.4f) }
        LoveHouseIcon.Wallet -> { roundRect(3.5f, 5.5f, 17f, 13.5f, 2.5f); roundRect(13f, 9f, 8f, 6f, 2f); circle(16f, 12f, .8f, true) }
        LoveHouseIcon.Travel -> { roundRect(5f, 6.5f, 14f, 14f, 2.5f); arc(180f, 180f, 8.5f, 2.5f, 7f, 8f); segment(9f, 9f, 9f, 18f); segment(15f, 9f, 15f, 18f) }
        LoveHouseIcon.Car -> { strokedPath { moveTo(p(3.5f, 15f).x, p(3.5f, 15f).y); lineTo(p(5.5f, 9f).x, p(5.5f, 9f).y); lineTo(p(8f, 6f).x, p(8f, 6f).y); lineTo(p(16f, 6f).x, p(16f, 6f).y); lineTo(p(18.5f, 9f).x, p(18.5f, 9f).y); lineTo(p(20.5f, 15f).x, p(20.5f, 15f).y); lineTo(p(20f, 18f).x, p(20f, 18f).y); lineTo(p(4f, 18f).x, p(4f, 18f).y); close() }; segment(5.5f, 10f, 18.5f, 10f); circle(7f, 16f, 1.3f); circle(17f, 16f, 1.3f) }
        LoveHouseIcon.Computer -> { roundRect(3.5f, 4f, 17f, 13f, 2f); segment(9f, 20f, 15f, 20f); segment(12f, 17f, 12f, 20f) }
        LoveHouseIcon.Emoji -> { circle(12f, 12f, 8f); circle(9f, 10f, .7f, true); circle(15f, 10f, .7f, true); arc(15f, 150f, 8f, 11f, 8f, 5f) }
        LoveHouseIcon.Weather -> { circle(15.5f, 8f, 3.5f); segment(15.5f, 2f, 15.5f, 3.5f); segment(20f, 3.5f, 19f, 4.5f); strokedPath { moveTo(p(5f, 18f).x, p(5f, 18f).y); cubicTo(p(2f, 18f).x, p(2f, 18f).y, p(2f, 13f).x, p(2f, 13f).y, p(6f, 13f).x, p(6f, 13f).y); cubicTo(p(7f, 8f).x, p(7f, 8f).y, p(14f, 9f).x, p(14f, 9f).y, p(14.5f, 13f).x, p(14.5f, 13f).y); cubicTo(p(19f, 12f).x, p(19f, 12f).y, p(21f, 18f).x, p(21f, 18f).y, p(17f, 18f).x, p(17f, 18f).y); close() } }
        LoveHouseIcon.Wrench -> { strokedPath { moveTo(p(5f, 4f).x, p(5f, 4f).y); cubicTo(p(8f, 3f).x, p(8f, 3f).y, p(10f, 5f).x, p(10f, 5f).y, p(10f, 8f).x, p(10f, 8f).y); lineTo(p(20f, 18f).x, p(20f, 18f).y); lineTo(p(18f, 20f).x, p(18f, 20f).y); lineTo(p(8f, 10f).x, p(8f, 10f).y); cubicTo(p(5f, 10f).x, p(5f, 10f).y, p(3f, 8f).x, p(3f, 8f).y, p(4f, 5f).x, p(4f, 5f).y); lineTo(p(6f, 7f).x, p(6f, 7f).y); lineTo(p(8f, 5f).x, p(8f, 5f).y); close() } }
        LoveHouseIcon.Star -> strokedPath { moveTo(p(12f, 3f).x, p(12f, 3f).y); lineTo(p(14.7f, 8.6f).x, p(14.7f, 8.6f).y); lineTo(p(21f, 9.5f).x, p(21f, 9.5f).y); lineTo(p(16.5f, 14f).x, p(16.5f, 14f).y); lineTo(p(17.5f, 20.5f).x, p(17.5f, 20.5f).y); lineTo(p(12f, 17.5f).x, p(12f, 17.5f).y); lineTo(p(6.5f, 20.5f).x, p(6.5f, 20.5f).y); lineTo(p(7.5f, 14f).x, p(7.5f, 14f).y); lineTo(p(3f, 9.5f).x, p(3f, 9.5f).y); lineTo(p(9.3f, 8.6f).x, p(9.3f, 8.6f).y); close() }
    }
}

@Composable
fun LoveHouseIconGallery(modifier: Modifier = Modifier) {
    val icons = LoveHouseIcon.entries
    Column(modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("LoveHouse Icon System 0.1", color = LoveHouseGlass.Ink, fontSize = 14.sp)
        Text("24dp regular · 18dp compact optical size", color = LoveHouseGlass.MutedInk, fontSize = 8.sp)
        icons.chunked(6).forEach { rowIcons ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowIcons.forEach { icon ->
                    Column(Modifier.weight(1f).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = .24f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            LoveHouseIconView(icon, null, Modifier.size(if (icon in compactGalleryIcons) 18.dp else 22.dp), opticalSize = if (icon in compactGalleryIcons) LoveHouseIconOpticalSize.Compact else LoveHouseIconOpticalSize.Regular)
                        }
                        Text(icon.name, color = LoveHouseGlass.MutedInk, fontSize = 5.5.sp, maxLines = 1)
                    }
                }
                repeat(6 - rowIcons.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

private val compactGalleryIcons = setOf(
    LoveHouseIcon.Copy, LoveHouseIcon.Retry, LoveHouseIcon.Play, LoveHouseIcon.ReadAloud,
    LoveHouseIcon.Translate, LoveHouseIcon.More, LoveHouseIcon.Transcribe, LoveHouseIcon.Reply,
    LoveHouseIcon.Forward, LoveHouseIcon.Regenerate,
)

@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
private fun LoveHouseIconGalleryPreview() {
    Box(Modifier.background(Color(0xFFDCE4F3))) { LoveHouseIconGallery() }
}
