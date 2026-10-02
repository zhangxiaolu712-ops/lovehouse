package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 工具中心的样式底座，逐项对应定稿 HTML（mcp-tool-center.html）里的设计令牌与组件。
 *
 * 关键：分界靠「陶土色细线」[Line]，不是白线。内层条目（账号行 / 工具块 / 按钮 / 输入框）
 * 都是更淡的半透明白底 + 1dp 陶土细线，所以叠在玻璃上依然一块一块分得清。
 * 注意：Compose 没有「背后模糊」，HTML 里的 backdrop-filter 在这里没有对应物。
 */
internal object ToolStyle {
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
    val Popup = Color(0xFFFCF9F4)
    val Line = Color(0x21A55F52)
    val Edge = Color(0x8CFFFFFF)
    val Scrim = Color(0x293A332D)
    val ShadowCard = Color(0x0D503C32)
    val ShadowPop = Color(0x24503C32)

    val FillAcc = Color(0x2EFFFFFF)
    val FillChip = Color(0x38FFFFFF)
    val FillInput = Color(0x4DFFFFFF)
    val FillAvatar = Color(0x59FFFFFF)
    val SegTrack = Color(0x1F6F645C)
    val SegOn = Color(0xB3FFFFFF)
    val SwitchOff = Color(0x476F645C)
    val SchemaBg = Color(0xE03A332D)
    val ToastBg = Color(0xE63A332D)

    val CardRadius = 18.dp
    val InnerRadius = 14.dp
    val CtlRadius = 12.dp

    val Title = 17.sp
    val Body = 14.sp
    val Sub = 12.sp
    val Cap = 11.sp
}

// ---------------------------------------------------------------- 文字

@Composable
internal fun ToolText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = ToolStyle.Body,
    color: Color = ToolStyle.Ink,
    weight: FontWeight? = null,
    mono: Boolean = false,
    italic: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    lineHeightRatio: Float = 1.6f,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else null,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Serif,
        letterSpacing = letterSpacing,
        textAlign = textAlign,
        lineHeight = size * lineHeightRatio,
        maxLines = maxLines,
        overflow = overflow,
    )
}

// ---------------------------------------------------------------- 点按

/** 无涟漪的点按；按下反馈由各组件自己用底色表达（定稿里是 :active 淡陶土色）。 */
@Composable
internal fun Modifier.tap(enabled: Boolean = true, role: Role? = null, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )

@Composable
internal fun ToolTap(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(ToolStyle.CtlRadius),
    fill: Color = Color.Transparent,
    pressedFill: Color = ToolStyle.AccentSoft,
    border: Color? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var m = modifier.clip(shape).background(if (pressed && enabled) pressedFill else fill)
    if (border != null) m = m.border(1.dp, border, shape)
    Box(
        modifier = m.clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ---------------------------------------------------------------- 图标（与 HTML 里的 SVG 路径一一对应）

internal enum class ToolIconKind(val path: String) {
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
internal fun ToolIcon(
    kind: ToolIconKind,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    tint: Color = ToolStyle.Ink,
) {
    val path = remember(kind) { PathParser().parsePathString(kind.path).toPath() }
    Canvas(modifier.size(iconSize)) {
        val s = this.size.width / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            drawPath(path, tint, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun ToolIconButton(kind: ToolIconKind, description: String, onClick: () -> Unit) {
    ToolTap(
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
        shape = CircleShape,
        onClick = onClick,
    ) { ToolIcon(kind) }
}

// ---------------------------------------------------------------- 玻璃

@Composable
internal fun ToolGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(ToolStyle.CardRadius),
    fill: Color = ToolStyle.Glass,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .shadow(6.dp, shape, clip = false, ambientColor = ToolStyle.ShadowCard, spotColor = ToolStyle.ShadowCard)
            .clip(shape).background(fill).border(1.dp, ToolStyle.Edge, shape),
        content = content,
    )
}

// ---------------------------------------------------------------- 顶栏 / 区块标题

@Composable
internal fun ToolTopBar(
    title: String,
    eyebrow: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIconButton(ToolIconKind.Back, "返回", onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ToolText(title, size = ToolStyle.Title, weight = FontWeight.Medium, letterSpacing = 1.36.sp, lineHeightRatio = 1.3f)
            ToolText(eyebrow.uppercase(), size = 10.sp, color = ToolStyle.Accent, letterSpacing = 2.6.sp)
        }
        if (trailing != null) trailing() else Spacer(Modifier.width(44.dp))
    }
}

@Composable
internal fun ToolSection(title: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        ToolText(title, size = ToolStyle.Sub, color = ToolStyle.Ink2, weight = FontWeight.Medium, letterSpacing = 1.4.sp)
        if (trailing != null) ToolText(trailing, size = ToolStyle.Cap, color = ToolStyle.Ink2)
    }
}

@Composable
internal fun ToolGroupLabel(text: String) {
    ToolText(
        text,
        Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
        size = ToolStyle.Cap,
        color = ToolStyle.Ink2,
        letterSpacing = 1.5.sp,
    )
}

/** 预览提示：后端合同还没接上时显示，明确告诉用户这些改动不会保存。 */
@Composable
internal fun ToolPreviewNote(text: String) {
    val shape = RoundedCornerShape(ToolStyle.CtlRadius)
    ToolText(
        text,
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(shape).background(ToolStyle.AccentSoft)
            .border(1.dp, ToolStyle.Line, shape).padding(horizontal = 12.dp, vertical = 7.dp),
        size = ToolStyle.Cap,
        color = ToolStyle.Accent,
    )
}

@Composable
internal fun ToolEmpty(text: String) {
    ToolGlass {
        ToolText(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp),
            size = ToolStyle.Sub,
            color = ToolStyle.Ink2,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- 行 / 开关

@Composable
internal fun ToolSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val offset by animateDpAsState(if (checked) 18.dp else 0.dp, label = "switch")
    Box(
        Modifier.widthIn(min = 48.dp).heightIn(min = 44.dp).alpha(if (enabled) 1f else .5f)
            .tap(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (checked) ToolStyle.Accent else ToolStyle.SwitchOff),
        ) {
            Box(Modifier.offset(x = 3.dp + offset, y = 3.dp).size(20.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** 玻璃卡片里的一行：左边标题 + 说明，右边放开关 / 选择器；divider 为 true 时上方画一条陶土细线。 */
@Composable
internal fun ToolRow(
    label: String,
    sub: String? = null,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit,
) {
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(ToolStyle.Line))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ToolText(label, size = ToolStyle.Body)
            if (sub != null) ToolText(sub, Modifier.padding(top = 1.dp), size = ToolStyle.Cap, color = ToolStyle.Ink2)
        }
        trailing()
    }
}

@Composable
internal fun ToolDot(color: Color = ToolStyle.Ok) {
    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------- 按钮

@Composable
internal fun ToolChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ToolIconKind? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (danger) ToolStyle.Danger else ToolStyle.Ink
    ToolTap(
        modifier = modifier.heightIn(min = 34.dp).alpha(if (enabled) 1f else .5f),
        fill = ToolStyle.FillChip,
        border = if (danger) ToolStyle.DangerLine else ToolStyle.Line,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) ToolIcon(icon, iconSize = 16.dp, tint = tint)
            ToolText(label, size = ToolStyle.Sub, color = tint)
        }
    }
}

/** 陶土色文字链接（定稿里的 .link-btn）。 */
@Composable
internal fun ToolLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    size: TextUnit = ToolStyle.Sub,
    minHeight: Dp = 36.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier.heightIn(min = minHeight).alpha(if (enabled) 1f else .5f).tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        ToolText(label, size = size, color = if (danger) ToolStyle.Danger else ToolStyle.Accent)
    }
}

/** 页面底部的整宽主按钮（.save-btn）。 */
@Composable
internal fun ToolSaveButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    ToolTap(
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).alpha(if (enabled) 1f else .5f),
        shape = RoundedCornerShape(ToolStyle.CardRadius),
        fill = ToolStyle.Accent,
        pressedFill = ToolStyle.Accent.copy(alpha = .85f),
        enabled = enabled,
        onClick = onClick,
    ) { ToolText(label, size = ToolStyle.Body, color = Color.White, letterSpacing = 1.1.sp) }
}

// ---------------------------------------------------------------- 服务卡片 / 账号行 / 工具块

@Composable
internal fun ToolAvatar(letter: String) {
    val shape = RoundedCornerShape(ToolStyle.CtlRadius)
    Box(
        Modifier.size(38.dp).clip(shape).background(ToolStyle.FillAvatar).border(1.dp, ToolStyle.Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        ToolText(
            letter.trim().take(1).uppercase().ifEmpty { "·" },
            size = 20.sp,
            color = ToolStyle.Accent,
            weight = FontWeight.Medium,
        )
    }
}

/** 可展开的服务卡片：头部（头像 / 名称 / 副标题 / 箭头）+ 展开后的正文。 */
@Composable
internal fun ToolServerCard(
    letter: String,
    title: String,
    sub: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, animationSpec = tween(250), label = "chev")
    ToolGlass(Modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).tap(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolAvatar(letter)
            Column(Modifier.weight(1f)) {
                ToolText(title, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeightRatio = 1.35f)
                ToolText(sub, size = ToolStyle.Cap, color = ToolStyle.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ToolIcon(ToolIconKind.Chev, Modifier.rotate(rotation), tint = ToolStyle.Ink2)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), content = body)
        }
    }
}

@Composable
internal fun ToolStatusLine(color: Color, text: String) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolDot(color)
        ToolText(text, size = ToolStyle.Cap, color = ToolStyle.Ink2)
    }
}

@Composable
internal fun ToolNoteLine(text: String) {
    ToolText(
        text,
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 8.dp),
        size = ToolStyle.Cap,
        color = ToolStyle.Ink2,
        italic = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolActions(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** 「挂载账号」这一行：左边小标题，右边一个链接按钮。 */
@Composable
internal fun ToolAccLabel(label: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolText(label, size = ToolStyle.Cap, color = ToolStyle.Ink2, letterSpacing = 1.1.sp)
        if (action != null) ToolLink(action, onAction)
    }
}

@Composable
internal fun ToolDashed() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = ToolStyle.Line,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/**
 * 账号行（.acc）：名称 + 说明 + 可选开关，下面一条虚线，再下面是一排文字链接。
 * checked 为 null 时不显示开关。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolAcc(
    name: String,
    meta: String,
    checked: Boolean? = null,
    onChecked: (Boolean) -> Unit = {},
    error: String? = null,
    links: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(ToolStyle.InnerRadius)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(ToolStyle.FillAcc)
            .border(1.dp, ToolStyle.Line, shape)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                ToolText(name, size = ToolStyle.Sub, weight = FontWeight.Medium)
                ToolText(meta, size = ToolStyle.Cap, color = ToolStyle.Ink2)
                if (error != null) ToolText(error, size = ToolStyle.Cap, color = ToolStyle.Danger)
            }
            if (checked != null) ToolSwitch(checked, onChecked)
        }
        ToolDashed()
        FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { links() }
    }
}

/** 账号行里的小链接（.acc-links .link-btn）。 */
@Composable
internal fun ToolAccLink(label: String, onClick: () -> Unit, danger: Boolean = false) {
    ToolLink(label, onClick, Modifier.padding(end = 12.dp), danger = danger, size = ToolStyle.Cap, minHeight = 34.dp)
}

/** 工具块（.tool）：名称（等宽、陶土色）+ 说明 + 右侧控件 + 可选的分段条。 */
@Composable
internal fun ToolBlock(
    name: String,
    desc: String?,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    dimmed: Boolean = false,
    trailing: @Composable () -> Unit,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(ToolStyle.InnerRadius)
    Column(
        modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 8.dp).clip(shape).background(ToolStyle.FillAcc)
            .border(1.dp, ToolStyle.Line, shape)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ToolText(name, size = ToolStyle.Sub, color = ToolStyle.Accent, mono = true)
                if (!desc.isNullOrBlank()) ToolText(desc, size = ToolStyle.Cap, color = ToolStyle.Ink2)
            }
            trailing()
        }
        if (below != null) {
            Column(Modifier.fillMaxWidth().alpha(if (dimmed) .45f else 1f).padding(top = 4.dp, end = 6.dp, bottom = 2.dp), content = below)
        }
    }
}

// ---------------------------------------------------------------- 表单

@Composable
internal fun ToolFormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ToolGlass(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            ToolText(title, Modifier.padding(bottom = 10.dp), size = ToolStyle.Sub, color = ToolStyle.Ink2, weight = FontWeight.Medium, letterSpacing = 1.2.sp)
            content()
        }
    }
}

@Composable
internal fun ToolField(label: String, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 11.dp)) {
        ToolText(label, Modifier.padding(bottom = 4.dp), size = ToolStyle.Cap, color = ToolStyle.Ink2, letterSpacing = .7.sp)
        content()
    }
}

@Composable
internal fun ToolHint(text: String) {
    ToolText(text, Modifier.padding(top = 6.dp), size = ToolStyle.Cap, color = ToolStyle.Ink2)
}

@Composable
internal fun ToolInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    multiline: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ToolStyle.CtlRadius)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = if (multiline) 68.dp else 40.dp)
            .alpha(if (enabled) 1f else .65f)
            .clip(shape).background(ToolStyle.FillInput)
            .border(1.dp, if (focused) ToolStyle.Accent else ToolStyle.Line, shape)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        enabled = enabled,
        singleLine = !multiline,
        textStyle = TextStyle(
            color = ToolStyle.Ink,
            fontSize = ToolStyle.Body,
            fontFamily = FontFamily.Serif,
            lineHeight = ToolStyle.Body * 1.6f,
        ),
        cursorBrush = SolidColor(ToolStyle.Accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    ToolText(placeholder, size = ToolStyle.Body, color = ToolStyle.Ink2.copy(alpha = .55f))
                }
                inner()
            }
        },
    )
}

/** 一排胶囊选项（.pill）。selected 里的下标为选中态；单选 / 多选由调用方决定怎么处理 onToggle。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolPills(
    options: List<String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            val on = index in selected
            ToolTap(
                modifier = Modifier.heightIn(min = 34.dp).alpha(if (enabled) 1f else .5f),
                shape = RoundedCornerShape(17.dp),
                fill = if (on) ToolStyle.Accent else ToolStyle.FillChip,
                pressedFill = if (on) ToolStyle.Accent else ToolStyle.AccentSoft,
                border = if (on) ToolStyle.Accent else ToolStyle.Line,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onToggle(index) },
            ) {
                ToolText(label, Modifier.padding(horizontal = 13.dp), size = ToolStyle.Sub, color = if (on) Color.White else ToolStyle.Ink)
            }
        }
    }
}

/** 分段条（.seg）：2~3 选 1。 */
@Composable
internal fun ToolSeg(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)
            .clip(RoundedCornerShape(ToolStyle.CtlRadius)).background(ToolStyle.SegTrack).padding(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier.weight(1f).heightIn(min = 32.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) ToolStyle.SegOn else Color.Transparent)
                    .tap(enabled = enabled, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                ToolText(label, size = ToolStyle.Sub, color = if (on) ToolStyle.Ink else ToolStyle.Ink2)
            }
        }
    }
}

/** 下拉选择（.sel）。mini 为紧凑版（数据去向里的「备用」）。 */
@Composable
internal fun ToolSelect(
    label: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ToolTap(
            modifier = Modifier.heightIn(min = if (mini) 28.dp else 36.dp).widthIn(max = if (mini) 130.dp else 150.dp),
            fill = ToolStyle.FillChip,
            border = ToolStyle.Line,
            onClick = { open = true },
        ) {
            Row(
                Modifier.padding(start = if (mini) 10.dp else 12.dp, end = if (mini) 8.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolText(
                    label,
                    Modifier.weight(1f, fill = false),
                    size = if (mini) ToolStyle.Cap else ToolStyle.Sub,
                    color = ToolStyle.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolIcon(ToolIconKind.Chev, iconSize = 12.dp, tint = ToolStyle.Accent)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(ToolStyle.Popup)) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { ToolText(text, size = ToolStyle.Sub) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 底部悬浮导航

@Composable
internal fun ToolBottomNav(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val itemShape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(ToolStyle.Glass).border(1.dp, ToolStyle.Edge, shape).padding(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier.weight(1f).heightIn(min = 38.dp).clip(itemShape)
                    .background(if (on) ToolStyle.AccentSoft else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, ToolStyle.Line, itemShape) else Modifier)
                    .tap(role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                ToolText(
                    label,
                    size = ToolStyle.Sub,
                    color = if (on) ToolStyle.Accent else ToolStyle.Ink2,
                    weight = if (on) FontWeight.Medium else null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 弹窗与提示（页面内浮层，浅遮罩）

internal data class ToolChoice(val label: String, val enabled: Boolean = true, val onPick: () -> Unit)

internal data class ToolDialogSpec(
    val title: String,
    val body: String,
    val ok: String? = "确定",
    val cancel: String? = "取消",
    val danger: Boolean = false,
    val input: String? = null,
    val choices: List<ToolChoice> = emptyList(),
    val onOk: (String) -> Unit = {},
)

@Stable
internal class ToolUi {
    var toastText by mutableStateOf<String?>(null)
    var toastSeq by mutableIntStateOf(0)
    var dialog by mutableStateOf<ToolDialogSpec?>(null)

    fun toast(text: String) {
        toastText = text
        toastSeq++
    }

    fun dismiss() {
        dialog = null
    }

    fun confirm(title: String, body: String, ok: String = "确定", danger: Boolean = false, onOk: () -> Unit) {
        dialog = ToolDialogSpec(title, body, ok = ok, danger = danger, onOk = { onOk() })
    }

    fun inform(title: String, body: String) {
        dialog = ToolDialogSpec(title, body, ok = "知道了", cancel = null)
    }

    fun prompt(title: String, body: String, initial: String, ok: String = "保存", onOk: (String) -> Unit) {
        dialog = ToolDialogSpec(title, body, ok = ok, input = initial, onOk = onOk)
    }

    fun choose(title: String, body: String, choices: List<ToolChoice>) {
        dialog = ToolDialogSpec(title, body, ok = null, choices = choices)
    }
}

internal val LocalToolUi = staticCompositionLocalOf<ToolUi> { error("ToolUi 未提供") }

/** 工具中心的根容器：提供弹窗与提示的宿主，内容在最下层，弹窗与提示盖在上面。 */
@Composable
internal fun ToolHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val ui = remember { ToolUi() }
    CompositionLocalProvider(LocalToolUi provides ui) {
        Box(modifier) {
            content()
            ToolDialogLayer(ui)
            ToolToastLayer(ui)
        }
    }
}

@Composable
private fun ToolDialogLayer(ui: ToolUi) {
    val spec = ui.dialog ?: return
    BackHandler { ui.dismiss() }
    var text by remember(spec) { mutableStateOf(spec.input.orEmpty()) }
    val shape = RoundedCornerShape(ToolStyle.CardRadius)
    Box(
        Modifier.fillMaxSize().background(ToolStyle.Scrim).tap { ui.dismiss() }.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 330.dp).fillMaxWidth()
                .shadow(16.dp, shape, clip = false, ambientColor = ToolStyle.ShadowPop, spotColor = ToolStyle.ShadowPop)
                .clip(shape).background(ToolStyle.GlassStrong)
                .border(1.dp, ToolStyle.Edge, shape).tap { }
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        ) {
            ToolText(spec.title, size = 15.sp, weight = FontWeight.Medium)
            if (spec.body.isNotBlank()) {
                ToolText(spec.body, Modifier.padding(top = 6.dp), size = ToolStyle.Sub, color = ToolStyle.Ink2)
            }
            if (spec.input != null) {
                Spacer(Modifier.height(14.dp))
                ToolInput(text, { text = it })
            }
            if (spec.choices.isNotEmpty()) Spacer(Modifier.height(12.dp))
            spec.choices.forEach { choice ->
                ToolTap(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(min = 44.dp).alpha(if (choice.enabled) 1f else .5f),
                    fill = ToolStyle.FillChip,
                    border = ToolStyle.Line,
                    enabled = choice.enabled,
                    onClick = { ui.dismiss(); choice.onPick() },
                ) { ToolText(choice.label, Modifier.padding(horizontal = 12.dp), size = ToolStyle.Sub) }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (spec.cancel != null) {
                    ToolTap(Modifier.heightIn(min = 40.dp), onClick = { ui.dismiss() }) {
                        ToolText(spec.cancel, Modifier.padding(horizontal = 16.dp), size = ToolStyle.Sub, color = ToolStyle.Ink2)
                    }
                }
                if (spec.ok != null) {
                    ToolTap(
                        Modifier.heightIn(min = 40.dp),
                        fill = if (spec.danger) ToolStyle.Danger else ToolStyle.Accent,
                        pressedFill = (if (spec.danger) ToolStyle.Danger else ToolStyle.Accent).copy(alpha = .85f),
                        onClick = {
                            val done = spec.onOk
                            val value = text.trim()
                            ui.dismiss()
                            done(value)
                        },
                    ) { ToolText(spec.ok, Modifier.padding(horizontal = 16.dp), size = ToolStyle.Sub, color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ToolToastLayer(ui: ToolUi) {
    val text = ui.toastText
    val last = remember { arrayOf("") }
    if (text != null) last[0] = text
    LaunchedEffect(ui.toastSeq) {
        if (ui.toastText != null) {
            delay(3000)
            ui.toastText = null
        }
    }
    AnimatedVisibility(
        visible = text != null,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
    ) {
        Box(
            Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(22.dp)).background(ToolStyle.ToastBg)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) { ToolText(last[0], size = ToolStyle.Sub, color = Color.White) }
    }
}
