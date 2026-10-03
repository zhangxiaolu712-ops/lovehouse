package fyi.b612.lovehouse.feature.schedule

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.YearMonth

internal data class ScheduleItem(
    val time: String,
    val title: String,
    val kind: ScheduleKind,
    val detail: String,
    val detailIcon: ScIcon? = null,
    val active: Boolean = false,
)

internal data class TodoItem(val id: Long, val text: String, val completed: Boolean, val completedTime: String?)

internal data class CourseChip(val title: String, val wash: Color, val ink: Color)

internal data class CourseDay(val date: String, val items: List<CourseChip>)

internal data class Habit(val id: Int, val title: String, val target: String, val daysInMonth: Int, val checkedDays: Set<Int>)

internal enum class ScheduleTab(val title: String, val badge: String, val tip: String, val icon: ScIcon) {
    Calendar("日历", "日历视图", "日历", ScIcon.Calendar),
    Create("新建日程", "新建添加", "新建日程", ScIcon.PlusCircle),
    Timeline("行程排期", "时间轴", "行程排期", ScIcon.Clock),
    Todo("待办事项", "待办清单", "待办事项", ScIcon.CheckSquare),
    Course("无界课表", "极简课表", "无界课表", ScIcon.BookOpen),
    Checkin("日常打卡", "打卡矩阵", "日常打卡", ScIcon.Grid),
}

internal val CnMonths = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")

internal fun weekdayCn(date: LocalDate): String = "日一二三四五六"[date.dayOfWeek.value % 7].toString()

/** Monday-first 6×7 grid; null cells are padding so the layout never jumps between months. */
internal fun monthGrid(month: YearMonth): List<Int?> {
    val offset = month.atDay(1).dayOfWeek.value - 1
    return List(42) { index -> (index - offset + 1).takeIf { it in 1..month.lengthOfMonth() } }
}

/** The Sunday-first week containing [date], as on the HTML day strip. */
internal fun weekOf(date: LocalDate): List<LocalDate> {
    val start = date.minusDays((date.dayOfWeek.value % 7).toLong())
    return List(7) { start.plusDays(it.toLong()) }
}

/**
 * Preview data copied from the HTML. In-memory only and never saved; it lives here
 * so the real data source can replace it in one place.
 */
internal object ScheduleSamples {
    fun schedule(month: YearMonth): Map<LocalDate, List<ScheduleItem>> = mapOf(
        month.atDay(7) to listOf(
            ScheduleItem("08:00", "晨间慢跑", ScheduleKind.Life, "滨江公园 5公里", ScIcon.Pin),
            ScheduleItem("10:00 - 11:30", "产品设计复盘", ScheduleKind.Work, "线上会议室 - ZenSchedule UI界面优化", ScIcon.Pin, active = true),
            ScheduleItem("14:00 - 16:00", "客户技术支持", ScheduleKind.Work, "客户现场沟通 & 架构演示", ScIcon.Pin),
            ScheduleItem("19:00 - 20:30", "柔术训练课", ScheduleKind.Course, "极真馆 - 张教练", ScIcon.Cap),
        ),
        month.atDay(8) to listOf(
            ScheduleItem("09:00 - 10:30", "周度团队站会", ScheduleKind.Work, "3号大会议室", ScIcon.Pin, active = true),
            ScheduleItem("12:00 - 13:00", "午餐健康调理", ScheduleKind.Life, "搭配高蛋白营养餐", ScIcon.Pin),
            ScheduleItem("15:00 - 17:00", "代码架构审核", ScheduleKind.Work, "极简前端模块构建", ScIcon.Pin),
        ),
        month.atDay(9) to listOf(
            ScheduleItem("10:00 - 12:00", "前端架构研讨", ScheduleKind.Work, "线上会议", ScIcon.Pin, active = true),
            ScheduleItem("15:00 - 17:00", "读书分享会", ScheduleKind.Course, "社区书店", ScIcon.Cap),
        ),
    )

    val todos = listOf(
        TodoItem(1, "整理第一季度总结 PPT", false, null),
        TodoItem(2, "去超市购买一周燕麦与牛奶", true, "09:15"),
        TodoItem(3, "跟进设计团队的图标导出进度", false, null),
        TodoItem(4, "重构组件库 CSS 样式层", true, "11:40"),
    )

    val courses = listOf(
        CourseDay("周一 (1月5日)", listOf(CourseChip("高级 JS 模式 (09:00)", Sc.AccentTint, Sc.AccentInk), CourseChip("UI 界面设计原理 (14:00)", Sc.PlumWash, Sc.PlumInk))),
        CourseDay("周三 (1月7日)", listOf(CourseChip("巴西柔术进阶 (19:00)", Sc.SageWash, Sc.SageInk))),
        CourseDay("周五 (1月9日)", listOf(CourseChip("数据结构与算法 (10:00)", Sc.AmberWash, Sc.AmberInk), CourseChip("团队 Weekly (16:00)", Sc.RoseWash, Sc.RoseInk))),
    )

    val habits = listOf(
        Habit(1, "紧致小V脸", "X10 6杯", 30, setOf(23, 24, 25, 26, 27, 28)),
        Habit(2, "早起第一杯", "X28", 30, setOf(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27, 28, 29)),
        Habit(3, "护肤保养", "X20", 30, setOf(5, 10, 11, 12, 13, 14, 16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27, 28)),
    )

    const val InitialSelectedDay = 7
}
