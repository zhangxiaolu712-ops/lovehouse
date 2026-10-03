package fyi.b612.lovehouse.feature.schedule

import androidx.compose.ui.graphics.Color

/**
 * Palette of the Owner's schedule HTML (its warm slate / terracotta remap of Tailwind),
 * with the Tool Center accent rule applied: selected and primary states use the terracotta
 * accent instead of near-black, soft accent fills replace dark blocks.
 */
internal object Sc {
    val Ink900 = Color(0xFF2F2A26)
    val Ink800 = Color(0xFF3A332D)
    val Ink700 = Color(0xFF3F3833)
    val Ink600 = Color(0xFF4D453F)
    val Ink500 = Color(0xFF5F554E)
    val Ink400 = Color(0xFF756A62)
    val Line200 = Color(0xFFE2DAD0)
    val Fill100 = Color(0xFFEFE9E1)

    val Accent = Color(0xFFA55F52)
    val AccentDeep = Color(0xFF8F4F43)
    val AccentInk = Color(0xFF7A4137)
    val AccentWash = Color(0xFFF3E6E2)
    val AccentTint = Color(0xFFECD5CF)
    val AccentLine = Color(0x33A55F52)
    val AccentHalo = Color(0x80A55F52)

    val OkWash = Color(0xFFE4EFE9)
    val OkInk = Color(0xFF3F6F59)
    val OkDot = Color(0xFF6F9A85)
    val AmberWash = Color(0xFFF3E7CF)
    val AmberInk = Color(0xFF7A5A1F)
    val AmberDot = Color(0xFFD9B46C)
    val PlumWash = Color(0xFFE6E3F1)
    val PlumInk = Color(0xFF5D5890)
    val RoseWash = Color(0xFFF1DDE1)
    val RoseInk = Color(0xFF8F3F50)
    val RoseDot = Color(0xFFC0707F)
    val SageWash = Color(0xFFD9E8E0)
    val SageInk = Color(0xFF34614C)

    val Glass = Color(0x6BFFFCF7)
    val GlassEdge = Color(0x8CFFFFFF)
    val GlassPop = Color(0xDBFCF9F4)
    val PopEdge = Color(0x99FFFFFF)
    val PopShadow = Color(0x24503C32)
    val Field = Color(0x66FFFFFF)
    val SoftWhite = Color(0x4DFFFFFF)
    val SoftEdge = Color(0x80FFFFFF)
    val TabTrack = Color(0xB3E2DAD0)
    val TabOn = Color(0xCCFFFFFF)
    val CellOff = Color(0xCCE2DAD0)
}

/** Category colors for schedule tags: soft wash + readable ink, never a dark block. */
internal enum class ScheduleKind(val label: String, val icon: ScIcon, val wash: Color, val ink: Color) {
    Work("工作", ScIcon.Work, Sc.AccentWash, Sc.AccentInk),
    Life("生活", ScIcon.Life, Sc.OkWash, Sc.OkInk),
    Course("课程", ScIcon.Course, Sc.PlumWash, Sc.PlumInk),
    Rest("休息", ScIcon.Rest, Sc.Fill100, Sc.Ink500),
}

/** The HTML's own `<symbol>` line icons (stroke 1.8), with rect/circle converted to paths. */
internal enum class ScIcon(val path: String) {
    Calendar("M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM16 2v4M8 2v4M3 10h18"),
    PlusCircle("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 8v8M8 12h8"),
    Clock("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 6v6l4 2"),
    CheckSquare("M9 11l3 3L22 4M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"),
    BookOpen("M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2zM22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"),
    Grid("M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z"),
    List("M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"),
    MoreVertical("M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM12 4a1 1 0 1 0 0 2a1 1 0 1 0 0-2zM12 18a1 1 0 1 0 0 2a1 1 0 1 0 0-2z"),
    Work("M4 7h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"),
    Life("M3 11l9-8 9 8v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"),
    Course("M4 4h12a3 3 0 0 1 3 3v13H7a3 3 0 0 1-3-3zM4 17a3 3 0 0 1 3-3h12"),
    Rest("M5 9h11v5a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4zM16 10h1.5a2.5 2.5 0 0 1 0 5H16M7 3v2M10 3v2M13 3v2"),
    ChevDown("M6 9l6 6 6-6"),
    Pin("M21 10c0 7-9 13-9 13S3 17 3 10a9 9 0 0 1 18 0zM12 7a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"),
    Cap("M2 9l10-5 10 5-10 5zM6 11.5V16c0 1.5 2.7 3 6 3s6-1.5 6-3v-4.5"),
}
