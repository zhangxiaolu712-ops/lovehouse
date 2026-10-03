package fyi.b612.lovehouse.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Design tokens of the Owner's finalized Tool Center HTML (`:root` variables), 1 CSS px = 1 dp/sp. */
internal object Tc {
    val Ink = Color(0xFF3A332D)
    val Ink2 = Color(0xFF5F554E)
    val Accent = Color(0xFFA55F52)
    val AccentSoft = Color(0x1FA55F52)
    val Ok = Color(0xFF4F7F68)
    val Danger = Color(0xFF9A3B32)
    val DangerLine = Color(0x479A3B32)
    val Idle = Color(0xFFA39A92)
    val Glass = Color(0x66FFFCF7)
    val GlassStrong = Color(0xCCFCF9F4)
    val Line = Color(0x21A55F52)
    val Edge = Color(0x8CFFFFFF)
    val CardShadow = Color(0x0D503C32)
    val PopShadow = Color(0x24503C32)
    val Fill18 = Color(0x2EFFFFFF)
    val Fill22 = Color(0x38FFFFFF)
    val Fill30 = Color(0x4DFFFFFF)
    val Fill35 = Color(0x59FFFFFF)
    val SwitchOff = Color(0x476F645C)
    val SegTrack = Color(0x1F6F645C)
    val SegOn = Color(0xB3FFFFFF)
    val SchemaBg = Color(0xE03A332D)
    val SchemaInk = Color(0xFFF6F1EA)
    val Scrim = Color(0x293A332D)
    val ToastBg = Color(0xE63A332D)

    val RadiusCard = 18.dp
    val RadiusInner = 14.dp
    val RadiusCtl = 12.dp
    val Tap = 44.dp

    val Serif: FontFamily = FontFamily.Serif
    val Latin: FontFamily = FontFamily.Serif
    val Mono: FontFamily = FontFamily.Monospace

    const val FsTitle = 17f
    const val FsBody = 14f
    const val FsSub = 12f
    const val FsCap = 11f
}

/** Real tool permission semantics; the UI only ever shows these three. */
internal enum class ToolPermission(val label: String) {
    Deny("禁止"),
    Ask("每次确认"),
    Allow("直接允许"),
}

@Composable
internal fun TcText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Tc.Ink,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Float = 1.6f,
    letterSpacing: Float = 0f,
    family: FontFamily = Tc.Serif,
    italic: Boolean = false,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            lineHeight = (size * lineHeight).sp,
            letterSpacing = letterSpacing.em,
            fontFamily = family,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textAlign = align,
        ),
    )
}

/** The HTML's own line icon set (`<symbol>` paths, stroke 1.7, round caps/joins). */
internal enum class TcIcon(val path: String) {
    Back("M15 5l-7 7 7 7"),
    Plus("M12 5v14M5 12h14"),
    Chev("M6 9l6 6 6-6"),
    Refresh("M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"),
    Edit("M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z"),
    Note("M5 4h14v16H5zM9 9h6M9 13h4"),
    Trash("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"),
    Code("M9 8l-4 4 4 4M15 8l4 4-4 4"),
}

@Composable
internal fun TcIconView(icon: TcIcon, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    TcPathIcon(icon.path, size, tint, modifier)
}

/** A 24x24 line icon drawn from an SVG path string, round caps/joins. */
@Composable
internal fun TcPathIcon(path: String, size: Dp, tint: Color, modifier: Modifier = Modifier, strokeWidth: Float = 1.7f) {
    val vector = remember(path, strokeWidth) {
        ImageVector.Builder("line-icon", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
    Icon(vector, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

internal fun Modifier.tcGlass(
    shape: Shape = RoundedCornerShape(Tc.RadiusCard),
    fill: Color = Tc.Glass,
    elevation: Dp = 5.dp,
    shadowColor: Color = Tc.CardShadow,
): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
    .clip(shape)
    .background(fill)
    .border(1.dp, Tc.Edge, shape)

internal fun Modifier.tcInner(shape: Shape = RoundedCornerShape(Tc.RadiusInner)): Modifier = this
    .clip(shape)
    .background(Tc.Fill18)
    .border(1.dp, Tc.Line, shape)

/** `.glass` container. */
@Composable
internal fun TcGlass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().tcGlass(), content = content)
}

@Composable
internal fun TcTopBar(
    title: String,
    eyebrow: String,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TcIconButton(TcIcon.Back, "返回", onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TcText(title, Tc.FsTitle, weight = FontWeight.Medium, letterSpacing = .08f, lineHeight = 1.3f, align = TextAlign.Center)
            TcText(eyebrow.uppercase(), 10f, color = Tc.Accent, letterSpacing = .26f, family = Tc.Latin, align = TextAlign.Center)
        }
        if (trailing != null) trailing() else Spacer(Modifier.width(Tc.Tap))
    }
}

@Composable
internal fun TcIconButton(icon: TcIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, visible: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .size(Tc.Tap)
            .alpha(if (visible) 1f else 0f)
            .clip(CircleShape)
            .background(if (pressed && visible) Tc.AccentSoft else Color.Transparent)
            .clickable(source, null, enabled = visible, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TcIconView(icon, 22.dp, Tc.Ink) }
}

/** `.section` — h2 on the left, count on the right, baseline aligned. */
@Composable
internal fun TcSection(title: String, trailing: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TcText(title, Tc.FsSub, Modifier.alignByBaseline(), color = Tc.Ink2, weight = FontWeight.Medium, letterSpacing = .12f)
        TcText(trailing, Tc.FsCap, Modifier.alignByBaseline(), color = Tc.Ink2)
    }
}

@Composable
internal fun TcGroupLabel(text: String) {
    TcText(text, Tc.FsCap, Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp), color = Tc.Ink2, letterSpacing = .14f)
}

/** `.row` with a top hairline when it is not the first row. */
@Composable
internal fun TcRow(first: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { if (!first) drawLine(Tc.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun TcRowText(label: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        TcText(label, Tc.FsBody)
        TcText(sub, Tc.FsCap, Modifier.padding(top = 1.dp), color = Tc.Ink2)
    }
}

/** `.switch` inside its 48x44 `.switch-hit` target. */
@Composable
internal fun TcSwitch(checked: Boolean, label: String, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val knob by animateDpAsState(if (checked) 18.dp else 0.dp, tween(200), label = "switch-knob")
    val track by animateColorAsState(if (checked) Tc.Accent else Tc.SwitchOff, tween(200), label = "switch-track")
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(track)) {
            Box(
                Modifier
                    .padding(3.dp)
                    .offset(x = knob)
                    .size(20.dp)
                    .shadow(2.dp, CircleShape, ambientColor = Color(0x403C281E), spotColor = Color(0x403C281E))
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
internal fun TcDot(color: Color = Tc.Ok) {
    Box(Modifier.padding(end = 6.dp).size(8.dp).background(color, CircleShape))
}

/** `.chip` action button. */
@Composable
internal fun TcChip(icon: TcIcon, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    val color = if (danger) Tc.Danger else Tc.Ink
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(if (pressed) Tc.AccentSoft else Tc.Fill22)
            .border(1.dp, if (danger) Tc.DangerLine else Tc.Line, shape)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TcIconView(icon, 16.dp, color)
        TcText(label, Tc.FsSub, color = color)
    }
}

/** `.link-btn`. */
@Composable
internal fun TcLink(
    label: String,
    modifier: Modifier = Modifier,
    size: Float = Tc.FsSub,
    color: Color = Tc.Accent,
    minHeight: Dp = 36.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = minHeight)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) { TcText(label, size, color = color) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TcPills(labels: List<String>, selected: Set<Int>, onClick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label -> TcPill(label, index in selected) { onClick(index) } }
    }
}

@Composable
internal fun TcPill(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        Modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(if (on) Tc.Accent else Tc.Fill22)
            .border(1.dp, if (on) Tc.Accent else Tc.Line, shape)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) { TcText(label, Tc.FsSub, color = if (on) Color.White else Tc.Ink) }
}

/** `.seg` segmented bar; [enabled] = false renders the `.tool.off` state. */
@Composable
internal fun TcSeg(labels: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .45f)
            .clip(RoundedCornerShape(Tc.RadiusCtl))
            .background(Tc.SegTrack)
            .padding(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val shape = RoundedCornerShape(11.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .then(if (on) Modifier.shadow(1.dp, shape, ambientColor = Color(0x1A3C281E), spotColor = Color(0x1A3C281E)) else Modifier)
                    .clip(shape)
                    .background(if (on) Tc.SegOn else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) { TcText(label, Tc.FsSub, color = if (on) Tc.Ink else Tc.Ink2, align = TextAlign.Center) }
        }
    }
}

/** `.form-card` with its `h3`. */
@Composable
internal fun TcFormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().tcGlass().padding(14.dp)) {
        TcText(title, Tc.FsSub, Modifier.padding(bottom = 10.dp), color = Tc.Ink2, weight = FontWeight.Medium, letterSpacing = .1f)
        content()
    }
}

/** `.field` wrapper with its label; [last] drops the bottom margin. */
@Composable
internal fun TcField(label: String, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 11.dp)) {
        TcText(label, Tc.FsCap, Modifier.padding(bottom = 4.dp), color = Tc.Ink2, letterSpacing = .06f)
        content()
    }
}

/** `.input` / `textarea.input`. */
@Composable
internal fun TcInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    multiline: Boolean = false,
    secret: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = !multiline,
        interactionSource = source,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(Tc.Accent),
        textStyle = TextStyle(color = Tc.Ink, fontSize = Tc.FsBody.sp, lineHeight = (Tc.FsBody * 1.6f).sp, fontFamily = Tc.Serif),
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (focused) {
                    val ring = 3.dp.toPx()
                    drawRoundRect(
                        color = Tc.AccentSoft,
                        topLeft = Offset(-ring / 2, -ring / 2),
                        size = Size(size.width + ring, size.height + ring),
                        cornerRadius = CornerRadius(Tc.RadiusCtl.toPx() + ring / 2),
                        style = Stroke(ring),
                    )
                }
            }
            .heightIn(min = if (multiline) 68.dp else 40.dp)
            .clip(shape)
            .background(Tc.Fill30)
            .border(1.dp, if (focused) Tc.Accent else Tc.Line, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) TcText(placeholder, Tc.FsBody, color = Tc.Ink2.copy(alpha = .6f))
                inner()
            }
        },
    )
}

@Composable
internal fun TcHint(text: String) {
    TcText(text, Tc.FsCap, Modifier.padding(top = 6.dp), color = Tc.Ink2)
}

/** `.inline-status`: a test link on the left, the result on the right. */
@Composable
internal fun TcInlineStatus(action: String, status: String, ok: Boolean, busy: Boolean = false, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TcLink(if (busy) "$action…" else action, enabled = !busy, onClick = onAction)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ok) TcDot()
            TcText(status, Tc.FsSub, color = if (ok) Tc.Ok else Tc.Ink2)
        }
    }
}

/** `.tool` inner card. */
@Composable
internal fun TcToolCard(last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(bottom = if (last) 0.dp else 8.dp).fillMaxWidth().tcInner()
            .padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        content = content,
    )
}

@Composable
internal fun TcToolTop(name: String, desc: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            TcText(name, Tc.FsSub, color = Tc.Accent, family = Tc.Mono)
            if (desc.isNotEmpty()) TcText(desc, Tc.FsCap, color = Tc.Ink2)
        }
        trailing()
    }
}

/** `.schema-toggle` + `.schema` block. */
@Composable
internal fun TcSchema(text: String) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { open = !open },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TcIconView(TcIcon.Code, 18.dp, Tc.Accent)
        TcText(if (open) "收起字段" else "查看字段", Tc.FsSub, color = Tc.Accent)
    }
    if (open) {
        Box(
            Modifier
                .padding(end = 4.dp, bottom = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Tc.SchemaBg)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) { TcText(text, Tc.FsCap, color = Tc.SchemaInk, family = Tc.Mono, maxLines = Int.MAX_VALUE) }
    }
}

/** `.sel` dropdown (native select on the HTML side). */
@Composable
internal fun TcSelect(options: List<String>, selected: Int, label: String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    Box {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .sizeIn(maxWidth = 150.dp)
                .clip(shape)
                .background(Tc.Fill22)
                .border(1.dp, Tc.Line, shape)
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = label, role = Role.DropdownList) { expanded = true }
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TcText(options.getOrElse(selected) { "" }, Tc.FsSub, Modifier.weight(1f, fill = false), color = Tc.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TcIconView(TcIcon.Chev, 12.dp, Tc.Accent)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { TcText(option, Tc.FsSub) }, onClick = { expanded = false; onSelect(index) })
            }
        }
    }
}

/** `.save-btn`. */
@Composable
internal fun TcSaveButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tc.RadiusCard)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .shadow(5.dp, shape, ambientColor = Tc.CardShadow, spotColor = Tc.CardShadow)
            .clip(shape)
            .background(Tc.Accent)
            .alpha(if (enabled) 1f else .6f)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TcText(label, Tc.FsBody, color = Color.White, letterSpacing = .08f) }
}

/** `.empty` placeholder text inside a glass card. */
@Composable
internal fun TcEmpty(text: String) {
    TcText(
        text,
        Tc.FsSub,
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp),
        color = Tc.Ink2,
        lineHeight = 1.8f,
        align = TextAlign.Center,
    )
}

/** `.chev` rotating 180° when its card is open. */
@Composable
internal fun TcChevron(open: Boolean) {
    val angle by animateFloatAsState(if (open) 180f else 0f, tween(250), label = "chev")
    TcIconView(TcIcon.Chev, 22.dp, Tc.Ink2, Modifier.rotate(angle))
}

/** `.acc-links` row with its dashed top border. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TcAccLinks(content: @Composable () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    Tc.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
            }
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
internal fun TcAccLink(label: String, danger: Boolean = false, onClick: () -> Unit) {
    TcLink(label, Modifier.padding(end = 12.dp), size = Tc.FsCap, color = if (danger) Tc.Danger else Tc.Accent, minHeight = 34.dp, onClick = onClick)
}
