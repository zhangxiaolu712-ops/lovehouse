package fyi.b612.lovehouse.feature.schedule

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleModelsTest {
    @Test
    fun `month grid is always six monday-first weeks`() {
        val october = monthGrid(YearMonth.of(2026, 10))
        assertEquals(42, october.size)
        // 2026-10-01 is a Thursday: three empty cells before it.
        assertEquals(listOf(null, null, null, 1), october.take(4))
        assertEquals(31, october.filterNotNull().last())
        assertNull(october.last())
    }

    @Test
    fun `day strip is the sunday-first week of the selected date`() {
        val week = weekOf(LocalDate.of(2026, 10, 7))
        assertEquals(LocalDate.of(2026, 10, 4), week.first())
        assertEquals(LocalDate.of(2026, 10, 10), week.last())
        assertEquals(listOf("日", "一", "二", "三", "四", "五", "六"), week.map(::weekdayCn))
    }

    @Test
    fun `sample schedule sits on the 7th to 9th of the given month`() {
        val month = YearMonth.of(2026, 10)
        val samples = ScheduleSamples.schedule(month)
        assertEquals(setOf(7, 8, 9), samples.keys.map { it.dayOfMonth }.toSet())
        assertEquals(1, samples.getValue(month.atDay(7)).count { it.active })
    }
}
