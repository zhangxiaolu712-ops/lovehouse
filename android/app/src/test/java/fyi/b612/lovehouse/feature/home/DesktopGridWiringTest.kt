package fyi.b612.lovehouse.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopGridWiringTest {
    private fun editor(): DesktopEditor = DesktopEditor { _, _, _, _, _, _, _, _ -> }

    @Test
    fun `grid migration preserves valid placements and moves only overflow`() {
        val original = linkedMapOf(
            "wide-widget" to GridPlacement(page = 0, row = 0, column = 0, rowSpan = 1, columnSpan = 4),
            "kept-icon" to GridPlacement(page = 0, row = 1, column = 0, rowSpan = 1, columnSpan = 1),
            "overflow-icon" to GridPlacement(page = 0, row = 4, column = 4, rowSpan = 1, columnSpan = 1),
        )

        val result = reflowGridPlacements(original, columns = 4, rows = 4)
        assertNotNull(result)
        val migrated = result!!

        assertEquals(original.getValue("wide-widget"), migrated.getValue("wide-widget"))
        assertEquals(original.getValue("kept-icon"), migrated.getValue("kept-icon"))
        assertTrue(migrated.getValue("overflow-icon").column in 0..3)
        assertTrue(migrated.getValue("overflow-icon").row in 0..3)
    }

    @Test
    fun `custom grid dimensions are real inputs rather than fixed six by six`() {
        val placement = mapOf(
            "edge" to GridPlacement(page = 0, row = 7, column = 7, rowSpan = 1, columnSpan = 1),
        )

        assertEquals(7, reflowGridPlacements(placement, columns = 8, rows = 8)!!.getValue("edge").column)
        val migratedToFive = reflowGridPlacements(placement, columns = 5, rows = 5)!!
        assertTrue(migratedToFive.getValue("edge").column < 5)
        assertTrue(migratedToFive.getValue("edge").row < 5)
    }

    @Test
    fun `grid migration refuses impossible spans without deleting items`() {
        val impossible = mapOf(
            "too-wide" to GridPlacement(page = 0, row = 0, column = 0, rowSpan = 1, columnSpan = 5),
        )

        assertNull(reflowGridPlacements(impossible, columns = 4, rows = 4))
    }

    @Test
    fun `pages remain isolated during reflow`() {
        val placements = mapOf(
            "page-one" to GridPlacement(page = 0, row = 3, column = 3, rowSpan = 1, columnSpan = 1),
            "page-two" to GridPlacement(page = 1, row = 3, column = 3, rowSpan = 1, columnSpan = 1),
        )

        val migrated = reflowGridPlacements(placements, columns = 3, rows = 3)!!

        assertEquals(0, migrated.getValue("page-one").page)
        assertEquals(1, migrated.getValue("page-two").page)
    }

    @Test
    fun `restore hidden and reset layout are real state changes`() {
        val editor = editor()
        val default = GridPlacement(page = 0, row = 1, column = 1, rowSpan = 1, columnSpan = 1)
        editor.restore(
            placements = mapOf("item" to default),
            hidden = setOf("item"),
            gridMode = DesktopGridMode.FiveByFive,
            dockItems = emptyList(),
            looseDockItems = emptyList(),
            customGrid = "7,8",
            iconSize = DesktopIconSize.Large,
        )
        editor.gridPlacement("item", default)

        editor.restoreHidden()
        assertTrue(editor.hidden.isEmpty())

        editor.resetLayout()
        assertEquals(DesktopGridMode.FourByFour, editor.gridMode)
        assertEquals(DesktopIconSize.Standard, editor.iconSize)
        assertEquals(6, editor.customColumns)
        assertEquals(6, editor.customRows)
    }

    @Test
    fun `icon size selection changes persisted desktop state`() {
        val editor = editor()
        editor.selectIconSize(DesktopIconSize.Small)
        assertEquals(DesktopIconSize.Small, editor.iconSize)
        editor.selectIconSize(DesktopIconSize.Large)
        assertEquals(DesktopIconSize.Large, editor.iconSize)
    }
}
