package fyi.b612.lovehouse.feature.schedule

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fyi.b612.lovehouse.feature.settings.TcPathIcon
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun ScheduleScreen(modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    var tab by rememberSaveable { mutableStateOf(ScheduleTab.Calendar) }
    var dockOpen by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selDay by remember { mutableIntStateOf(ScheduleSamples.InitialSelectedDay) }
    var calendarBadge by remember { mutableStateOf<String?>(null) }
    var selectedScheduleItemIndex by remember { mutableStateOf<Int?>(null) }
    var detailScheduleItem by remember { mutableStateOf<ScheduleItem?>(null) }
    val store = remember {
        mutableStateMapOf<LocalDate, List<ScheduleItem>>().apply { putAll(ScheduleSamples.schedule(YearMonth.from(today))) }
    }
    val todos = remember { mutableStateListOf<TodoItem>().apply { addAll(ScheduleSamples.todos) } }
    val habits = remember { mutableStateListOf<Habit>().apply { addAll(ScheduleSamples.habits) } }
    val selected = month.atDay(selDay.coerceIn(1, month.lengthOfMonth()))
    val goTo: (YearMonth, Int) -> Unit = { target, day ->
        month = target
        selDay = day.coerceIn(1, target.lengthOfMonth())
        selectedScheduleItemIndex = null
        detailScheduleItem = null
    }

    Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 448.dp).fillMaxSize()) {
            ScheduleHeader(
                title = if (tab == ScheduleTab.Calendar) "${month.monthValue}月" else tab.title,
                badge = if (tab == ScheduleTab.Calendar) calendarBadge ?: tab.badge else tab.badge,
                onMenu = { dockOpen = !dockOpen },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    ScheduleTab.Calendar -> CalendarView(
                        month = month,
                        selected = selected,
                        today = today,
                        store = store,
                        selectedItemIndex = selectedScheduleItemIndex,
                        onSelect = {
                            selDay = it
                            selectedScheduleItemIndex = null
                            detailScheduleItem = null
                        },
                        onItemSelect = { index, item ->
                            selectedScheduleItemIndex = index
                            detailScheduleItem = item
                        },
                        onMonth = { goTo(it, selDay) },
                        onToday = { goTo(YearMonth.from(today), today.dayOfMonth) },
                        onMode = { calendarBadge = it },
                    )
                    ScheduleTab.Create -> CreateView { item ->
                        store[selected] = store[selected].orEmpty() + item
                        tab = ScheduleTab.Calendar
                    }
                    ScheduleTab.Timeline -> TimelineView(
                        selected = selected,
                        store = store,
                        selectedItemIndex = selectedScheduleItemIndex,
                        onDateSelect = { goTo(YearMonth.from(it), it.dayOfMonth) },
                        onItemSelect = { index, item ->
                            selectedScheduleItemIndex = index
                            detailScheduleItem = item
                        },
                    )
                    ScheduleTab.Todo -> TodoView(todos)
                    ScheduleTab.Course -> CourseView()
                    ScheduleTab.Checkin -> CheckinView(habits)
                }
            }
        }
        val lift = with(LocalDensity.current) { 8.dp.roundToPx() }
        AnimatedVisibility(
            visible = dockOpen,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp),
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -lift },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { -lift },
        ) {
            ScheduleDock(tab) {
                tab = it
                calendarBadge = null
                dockOpen = false
            }
        }
        detailScheduleItem?.let { item ->
            ScheduleItemDetailDialog(
                item = item,
                onDismiss = { detailScheduleItem = null },
            )
        }
    }
}

@Composable
private fun ScheduleHeader(title: String, badge: String, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            ScText(title, 18f, color = Sc.Ink900, weight = FontWeight.SemiBold, lineHeight = 1.25f, align = TextAlign.Center)
            ScText(badge, 11f, color = Sc.Ink400, letterSpacing = .025f, align = TextAlign.Center)
        }
        Box(
            Modifier.offset(x = 8.dp).size(40.dp).clip(CircleShape)
                .clickable(onClickLabel = "展开/收起菜单", role = Role.Button, onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) { ScIconView(ScIcon.MoreVertical, 20.dp, Sc.Ink800) }
    }
}

/** Floating vertical dock; the active entry uses the accent instead of a dark block. */
@Composable
private fun ScheduleDock(current: ScheduleTab, onSelect: (ScheduleTab) -> Unit) {
    Column(
        Modifier.width(48.dp).scPop(RoundedCornerShape(16.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScheduleTab.entries.forEach { entry ->
            val on = entry == current
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(36.dp)
                    .then(if (on) Modifier.shadow(2.dp, shape, ambientColor = Sc.PopShadow, spotColor = Sc.PopShadow) else Modifier)
                    .clip(shape)
                    .background(if (on) Sc.Accent else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(entry) }
                    .semantics { contentDescription = entry.tip },
                contentAlignment = Alignment.Center,
            ) { ScIconView(entry.icon, 16.dp, if (on) Color.White else Sc.Ink500) }
        }
    }
}

// ---------------- 日历 ----------------

@Composable
private fun CalendarView(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    store: SnapshotStateMap<LocalDate, List<ScheduleItem>>,
    selectedItemIndex: Int?,
    onSelect: (Int) -> Unit,
    onItemSelect: (Int, ScheduleItem) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onToday: () -> Unit,
    onMode: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerYear by remember { mutableIntStateOf(month.year) }
    var listMode by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().scGlass(RoundedCornerShape(24.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.offset(x = (-4).dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "选择年份和月份") { pickerOpen = !pickerOpen; pickerYear = month.year }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ScText("${CnMonths[month.monthValue - 1]} ${month.year}", 14f, color = Sc.Ink800, weight = FontWeight.SemiBold)
                    ScIconView(ScIcon.ChevDown, 14.dp, Sc.Ink400)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeButton(ScIcon.Calendar, "月视图", !listMode) { listMode = false; onMode("月视图") }
                    ModeButton(ScIcon.List, "列表", listMode) { listMode = true; onMode("列表视图") }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                "一二三四五六日".forEach {
                    ScText(it.toString(), 10f, Modifier.weight(1f), color = Sc.Ink400, weight = FontWeight.SemiBold, align = TextAlign.Center)
                }
            }
            val swipe = with(LocalDensity.current) { 50.dp.toPx() }
            Column(
                Modifier.fillMaxWidth().pointerInput(month) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (abs(dx) > swipe) onMonth(if (dx < 0) month.plusMonths(1) else month.minusMonths(1)) },
                    ) { _, amount -> dx += amount }
                },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                monthGrid(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(Modifier.weight(1f).heightIn(min = 36.dp))
                            } else {
                                val date = month.atDay(day)
                                DayCell(
                                    day = day,
                                    isSelected = date == selected,
                                    isToday = date == today,
                                    hasItems = store[date] != null,
                                    modifier = Modifier.weight(1f),
                                ) { onSelect(day) }
                            }
                        }
                    }
                }
            }
        }
        if (pickerOpen) {
            Box(
                Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null) { pickerOpen = false },
            )
            MonthPicker(
                year = pickerYear,
                current = month,
                modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 48.dp),
                onYear = { pickerYear += it },
                onPick = { pickerOpen = false; onMonth(YearMonth.of(pickerYear, it)) },
                onToday = { pickerOpen = false; onToday() },
            )
        }
    }

    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScText("${selected.monthValue}月${selected.dayOfMonth}日 的行程安排", 12f, color = Sc.Ink500, weight = FontWeight.SemiBold, letterSpacing = .05f)
        val items = store[selected] ?: listOf(
            ScheduleItem("全天", "自由安排时间", ScheduleKind.Rest, "暂无特定行程规划，可在【新建】中添加"),
        )
        Column(
            Modifier.fillMaxWidth().scGlass(RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { index, item ->
                DayScheduleRow(
                    item = item,
                    selected = index == selectedItemIndex,
                    onClick = { onItemSelect(index, item) },
                )
            }
        }
    }
}

@Composable
private fun ModeButton(icon: ScIcon, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = label, onClick = onClick).padding(4.dp),
    ) { ScIconView(icon, 14.dp, if (on) Sc.Accent else Sc.Ink400) }
}

@Composable
private fun DayCell(day: Int, isSelected: Boolean, isToday: Boolean, hasItems: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .heightIn(min = 36.dp)
            .then(if (isSelected) Modifier.shadow(4.dp, shape, ambientColor = Sc.PopShadow, spotColor = Sc.PopShadow) else Modifier)
            .clip(shape)
            .background(if (isSelected) Sc.Accent else Color.Transparent)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, Sc.AccentHalo, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScText(
            "$day", 12f,
            color = if (isSelected) Color.White else Sc.Ink700,
            weight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            lineHeight = 1.3f,
            align = TextAlign.Center,
        )
        if (hasItems) Box(Modifier.padding(top = 2.dp).size(4.dp).background(if (isSelected) Color.White else Sc.Accent, CircleShape))
    }
}

@Composable
private fun MonthPicker(
    year: Int,
    current: YearMonth,
    modifier: Modifier,
    onYear: (Int) -> Unit,
    onPick: (Int) -> Unit,
    onToday: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .scPop(RoundedCornerShape(16.dp), fill = Color(0xFFFCF9F4))
            .clickable(remember { MutableInteractionSource() }, null) {}
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PickerArrow("‹", "上一年") { onYear(-1) }
            ScText("$year", 14f, color = Sc.Ink800, weight = FontWeight.SemiBold)
            PickerArrow("›", "下一年") { onYear(1) }
        }
        (1..12).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    val on = year == current.year && m == current.monthValue
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (on) Sc.Accent else Color.Transparent)
                            .clickable { onPick(m) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ScText("${m}月", 12f, color = if (on) Color.White else Sc.Ink700, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sc.AccentWash).clickable(onClick = onToday).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { ScText("回到今天", 12f, color = Sc.AccentDeep, weight = FontWeight.Medium) }
    }
}

@Composable
private fun PickerArrow(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ScText(glyph, 16f, color = Sc.Ink600, weight = FontWeight.SemiBold) }
}

/** Calendar day list row; selection is runtime UI state and never part of sample data. */
@Composable
private fun DayScheduleRow(item: ScheduleItem, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.background(Sc.AccentWash).border(1.dp, Sc.AccentLine, shape) else Modifier)
            .clickable(onClickLabel = "查看${item.title}详情", onClick = onClick)
            .padding(if (selected) 10.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScText(item.time, 11f, Modifier.width(64.dp), color = if (selected) Sc.AccentDeep else Sc.Ink400, weight = FontWeight.Medium)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ScText(item.title, 12f, Modifier.weight(1f, fill = false), color = Sc.Ink800, weight = FontWeight.SemiBold)
                KindTag(item.kind, selected)
            }
            DetailLine(item, 10f, Sc.Ink600, Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun KindTag(kind: ScheduleKind, active: Boolean) {
    val ink = if (active) Color.White else kind.ink
    Row(
        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(if (active) Sc.Accent else kind.wash)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ScIconView(kind.icon, 12.dp, ink)
        ScText(kind.label, 9f, color = ink, weight = FontWeight.Medium)
    }
}

@Composable
private fun DetailLine(item: ScheduleItem, size: Float, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        item.detailIcon?.let { ScIconView(it, 12.dp, color) }
        ScText(item.detail, size, color = color)
    }
}

// ---------------- 新建 ----------------

@Composable
private fun CreateView(onSave: (ScheduleItem) -> Unit) {
    val kinds = listOf(ScheduleKind.Work, ScheduleKind.Life, ScheduleKind.Course)
    var kind by remember { mutableStateOf(ScheduleKind.Work) }
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("09:30") }
    var end by remember { mutableStateOf("11:00") }
    val extra = remember { mutableStateMapOf<ScheduleKind, String>() }
    Column(
        Modifier.fillMaxWidth().scGlass(RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScText("新建日程类型", 16f, color = Sc.Ink800, weight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { option ->
                val on = option == kind
                val shape = RoundedCornerShape(12.dp)
                val ink = if (on) Sc.AccentDeep else Sc.Ink600
                Row(
                    Modifier.weight(1f).clip(shape).background(if (on) Sc.AccentWash else Color.Transparent)
                        .border(1.dp, if (on) Sc.Accent else Sc.Line200, shape)
                        .clickable(role = Role.Tab) { kind = option }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScIconView(option.icon, 14.dp, ink)
                    ScText(option.label, 12f, color = ink, weight = FontWeight.Medium)
                }
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField("标题名称") { ScInput(title, { title = it }, "如：季度例会 / 健身房打卡") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { FormField("开始时间") { TimeField(start) { start = it } } }
                Box(Modifier.weight(1f)) { FormField("结束时间") { TimeField(end) { end = it } } }
            }
            val (label, hint) = when (kind) {
                ScheduleKind.Work -> "会议/工作地点" to "如: 3号大会议室 / 腾讯会议"
                ScheduleKind.Life -> "生活备注与细节" to "如: 买牛奶、健身准备"
                else -> "课程教室/导师" to "如: 柔术馆 / 张教练"
            }
            FormField(label) { ScInput(extra[kind].orEmpty(), { extra[kind] = it }, hint) }
            PrimaryButton("保存并加入日程", Modifier.fillMaxWidth(), tall = true) {
                if (title.isBlank()) return@PrimaryButton
                onSave(
                    ScheduleItem(
                        time = "$start - $end",
                        title = title.trim(),
                        kind = kind,
                        detail = extra[kind]?.trim()?.ifBlank { null } ?: "自定义添加的事项",
                        detailIcon = if (kind == ScheduleKind.Course) ScIcon.Cap else ScIcon.Pin,
                    ),
                )
                title = ""
            }
        }
    }
}

@Composable
private fun FormField(label: String, content: @Composable () -> Unit) {
    Column {
        ScText(label, 12f, Modifier.padding(bottom = 4.dp), color = Sc.Ink600, weight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun ScInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        interactionSource = source,
        cursorBrush = SolidColor(Sc.Accent),
        textStyle = TextStyle(color = Sc.Ink800, fontSize = 12.sp, fontFamily = FontFamily.Serif),
        modifier = modifier.fillMaxWidth().clip(shape).background(Sc.Field)
            .border(1.dp, if (focused) Sc.Accent else Sc.AccentLine, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) ScText(placeholder, 12f, color = Sc.Ink400.copy(alpha = .75f), maxLines = 1)
                inner()
            }
        },
    )
}

/** `<input type="time">` maps to the native Android time picker. */
@Composable
private fun TimeField(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(Sc.Field).border(1.dp, Sc.AccentLine, shape)
            .clickable {
                val time = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(9, 0))
                TimePickerDialog(context, { _, h, m -> onChange("%02d:%02d".format(h, m)) }, time.hour, time.minute, true).show()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { ScText(value, 12f, color = Sc.Ink800) }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, tall: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .shadow(4.dp, shape, ambientColor = Sc.PopShadow, spotColor = Sc.PopShadow)
            .clip(shape)
            .background(Sc.Accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (tall) 12.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) { ScText(label, 12f, color = Color.White, weight = FontWeight.SemiBold) }
}

// ---------------- 行程排期 ----------------

@Composable
private fun TimelineView(
    selected: LocalDate,
    store: SnapshotStateMap<LocalDate, List<ScheduleItem>>,
    selectedItemIndex: Int?,
    onDateSelect: (LocalDate) -> Unit,
    onItemSelect: (Int, ScheduleItem) -> Unit,
) {
    val items = store[selected] ?: listOf(ScheduleItem("全天", "自由休整", ScheduleKind.Rest, "今日无特定的工作或项目任务"))
    Column(Modifier.fillMaxWidth().scGlass(RoundedCornerShape(24.dp)).padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                ScText("今日个人与工作行程", 14f, color = Sc.Ink800, weight = FontWeight.SemiBold)
                ScText("${selected.monthValue}月${selected.dayOfMonth}日 星期${weekdayCn(selected)}", 12f, color = Sc.Ink400, weight = FontWeight.Medium)
            }
            ScText(
                "${items.size}项安排", 11f,
                Modifier.clip(CircleShape).background(Sc.AccentWash).padding(horizontal = 10.dp, vertical = 4.dp),
                color = Sc.AccentInk, weight = FontWeight.Medium,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp)
                .drawBehind { drawLine(Sc.Fill100, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            weekOf(selected).forEach { date ->
                val on = date == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onDateSelect(date) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScText(weekdayCn(date), 10f, color = Sc.Ink400)
                    Box(
                        Modifier.padding(top = 4.dp).size(24.dp)
                            .then(if (on) Modifier.shadow(2.dp, CircleShape, ambientColor = Sc.PopShadow, spotColor = Sc.PopShadow) else Modifier)
                            .clip(CircleShape).background(if (on) Sc.Accent else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { ScText("${date.dayOfMonth}", 12f, color = if (on) Color.White else Sc.Ink700, weight = FontWeight.SemiBold, lineHeight = 1.2f) }
                }
            }
        }
        ScText("时间轴排期", 12f, Modifier.padding(bottom = 12.dp), color = Sc.Ink700, weight = FontWeight.SemiBold)
        Column(
            Modifier.fillMaxWidth()
                .drawBehind {
                    val x = 9.dp.toPx()
                    drawLine(Sc.Line200, Offset(x, 8.dp.toPx()), Offset(x, size.height - 8.dp.toPx()), 2.dp.toPx())
                }
                .padding(start = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items.forEachIndexed { index, item ->
                TimelineRow(
                    item = item,
                    selected = index == selectedItemIndex,
                    onClick = { onItemSelect(index, item) },
                )
            }
        }
    }
}

/** Timeline node sits centred on the rail; selected styling comes only from UI state. */
@Composable
private fun TimelineRow(item: ScheduleItem, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        if (selected) Box(Modifier.offset(x = (-17).dp, y = 2.dp).size(12.dp).background(Sc.AccentTint, CircleShape))
        Box(
            Modifier.offset(x = (-15).dp, y = 4.dp).size(8.dp)
                .background(if (selected) Color.White else Sc.SoftWhite, CircleShape)
                .border(2.dp, if (selected) Sc.AccentDeep else Sc.Ink400, CircleShape),
        )
        Column {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScText(item.time, 11f, Modifier.alignByBaseline(), color = if (selected) Sc.AccentDeep else Sc.Ink400, weight = FontWeight.SemiBold)
                    ScText(item.title, 12f, Modifier.alignByBaseline(), color = Sc.Ink800, weight = FontWeight.SemiBold)
                }
                KindTag(item.kind, selected)
            }
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (selected) Sc.AccentWash else Sc.SoftWhite)
                    .border(1.dp, if (selected) Sc.AccentLine else Sc.SoftEdge, shape)
                    .clickable(onClickLabel = "查看${item.title}详情", onClick = onClick)
                    .padding(8.dp),
            ) { DetailLine(item, 11f, if (selected) Sc.Ink700 else Sc.Ink600) }
        }
    }
}

@Composable
private fun ScheduleItemDetailDialog(item: ScheduleItem, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .scPop(RoundedCornerShape(20.dp), fill = Color(0xFFFCF9F4))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ScText(item.title, 17f, Modifier.weight(1f), color = Sc.Ink900, weight = FontWeight.SemiBold)
                KindTag(item.kind, false)
            }
            DetailField("时间", item.time)
            DetailField("分类", item.kind.label)
            DetailField("地点 / 备注", item.detail)
            PrimaryButton("关闭", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScText(label, 10f, color = Sc.Ink400, weight = FontWeight.Medium)
        ScText(value, 12f, Modifier.fillMaxWidth(), color = Sc.Ink700, lineHeight = 1.5f)
    }
}

// ---------------- 待办 ----------------

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TodoView(todos: SnapshotStateList<TodoItem>) {
    var draft by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().scGlass(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ScText("待办清单", 14f, color = Sc.Ink800, weight = FontWeight.SemiBold)
            ScText("完成仅记录打卡时间", 11f, color = Sc.Ink400)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScInput(draft, { draft = it }, "添加新的待办事项...", Modifier.weight(1f))
            PrimaryButton("添加") {
                if (draft.isNotBlank()) {
                    todos.add(0, TodoItem(System.currentTimeMillis(), draft.trim(), false, null))
                    draft = ""
                }
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todos.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox) {
                            todos[index] = if (item.completed) item.copy(completed = false, completedTime = null)
                            else item.copy(completed = true, completedTime = LocalTime.now().format(ClockFormat))
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBox(item.completed, 16.dp)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ScText(
                            item.text, 12f, Modifier.weight(1f, fill = false),
                            color = if (item.completed) Sc.Ink400 else Sc.Ink800,
                            weight = FontWeight.Medium,
                            strike = item.completed,
                        )
                        if (item.completed) {
                            ScText(
                                "已完成 ${item.completedTime ?: ""}".trim(), 10f,
                                Modifier.padding(start = 6.dp).clip(CircleShape).background(Sc.OkWash).padding(horizontal = 8.dp, vertical = 2.dp),
                                color = Sc.OkInk, weight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean, size: Dp) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(size).clip(shape).background(if (checked) Sc.Accent else Sc.Field)
            .border(2.dp, if (checked) Sc.Accent else Sc.Ink400, shape),
        contentAlignment = Alignment.Center,
    ) { if (checked) TcPathIcon("M5 12l5 5 9-10", size - 4.dp, Color.White, strokeWidth = 3f) }
}

// ---------------- 课表 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseView() {
    Column(
        Modifier.fillMaxWidth().scGlass(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ScText("无界课表 / 计划", 14f, color = Sc.Ink800, weight = FontWeight.SemiBold)
            ScText("无极极简流", 12f, color = Sc.AccentDeep, weight = FontWeight.Medium)
        }
        ScheduleSamples.courses.forEach { day ->
            Column(
                Modifier.fillMaxWidth().scGlass(RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScText(day.date, 12f, color = Sc.Ink500, weight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    day.items.forEach { chip ->
                        ScText(
                            chip.title, 12f,
                            Modifier.clip(RoundedCornerShape(12.dp)).background(chip.wash).padding(horizontal = 10.dp, vertical = 4.dp),
                            color = chip.ink,
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 打卡 ----------------

private val CheckinMonthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckinView(habits: SnapshotStateList<Habit>) {
    var freq by remember { mutableIntStateOf(1) }
    var showDates by remember { mutableStateOf(true) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val total = habits.sumOf { it.checkedDays.size }
    val days = habits.flatMap { it.checkedDays }.toSet().size
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("$total", "总次数", Modifier.weight(1f))
            StatCard("$days", "总天数", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sc.TabTrack).padding(4.dp)) {
            listOf("周", "月", "年").forEachIndexed { index, label ->
                val on = index == freq
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.weight(1f)
                        .then(if (on) Modifier.shadow(1.dp, shape, ambientColor = Sc.PopShadow, spotColor = Sc.PopShadow) else Modifier)
                        .clip(shape).background(if (on) Sc.TabOn else Color.Transparent)
                        .clickable(role = Role.Tab) { freq = index }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { ScText(label, 12f, color = if (on) Sc.Ink800 else Sc.Ink500, weight = FontWeight.Medium) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MonthArrow("‹", "上个月") { month = month.minusMonths(1) }
                ScText(month.format(CheckinMonthFormat), 12f, color = Sc.Ink600, weight = FontWeight.SemiBold)
                MonthArrow("›", "下个月") { month = month.plusMonths(1) }
            }
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { showDates = !showDates }.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScText("显示日期", 11f, color = Sc.Ink500)
                CheckBox(showDates, 14.dp)
            }
        }
        habits.forEachIndexed { index, habit ->
            val checked = habit.checkedDays.size
            Row(
                Modifier.fillMaxWidth().scGlass(RoundedCornerShape(16.dp)).padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.widthIn(max = 110.dp).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ScText(habit.title, 12f, color = Sc.Ink800, weight = FontWeight.SemiBold, lineHeight = 1.375f)
                    ScText(habit.target, 10f, color = Sc.Ink400)
                    ScText("${checked}天", 10f, color = Sc.Ink500, weight = FontWeight.Medium)
                    ScText("${checked * 100 / habit.daysInMonth}%", 10f, color = Sc.AccentDeep, weight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
                    FlowRow(
                        Modifier.widthIn(max = 200.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        (1..habit.daysInMonth).forEach { day ->
                            val on = day in habit.checkedDays
                            Box(
                                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (on) Sc.Accent else Sc.CellOff)
                                    .clickable {
                                        habits[index] = habit.copy(checkedDays = if (on) habit.checkedDays - day else habit.checkedDays + day)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                ScText(
                                    if (showDates) "$day" else if (on) "✓" else "",
                                    9f,
                                    color = if (on) Color.White else Sc.Ink400,
                                    weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    lineHeight = 1.1f,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.scGlass(RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScText(value, 20f, color = Sc.Ink800, weight = FontWeight.SemiBold, lineHeight = 1.4f)
        ScText(label, 11f, color = Sc.Ink400)
    }
}

@Composable
private fun MonthArrow(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { ScText(glyph, 12f, color = Sc.Ink600, weight = FontWeight.SemiBold) }
}

// ---------------- 通用 ----------------

@Composable
internal fun ScText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Sc.Ink800,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Float = 1.5f,
    letterSpacing: Float = 0f,
    align: TextAlign = TextAlign.Start,
    strike: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            lineHeight = (size * lineHeight).sp,
            letterSpacing = letterSpacing.em,
            fontFamily = FontFamily.Serif,
            textAlign = align,
            textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
        ),
    )
}

@Composable
internal fun ScIconView(icon: ScIcon, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    TcPathIcon(icon.path, size, tint, modifier, strokeWidth = 1.8f)
}

private fun Modifier.scGlass(shape: RoundedCornerShape): Modifier = this
    .clip(shape)
    .background(Sc.Glass)
    .border(1.dp, Sc.GlassEdge, shape)

private fun Modifier.scPop(shape: RoundedCornerShape, fill: Color = Sc.GlassPop): Modifier = this
    .shadow(16.dp, shape, ambientColor = Sc.PopShadow, spotColor = Sc.PopShadow)
    .clip(shape)
    .background(fill)
    .border(1.dp, Sc.PopEdge, shape)
