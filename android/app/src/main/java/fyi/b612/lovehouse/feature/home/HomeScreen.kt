package fyi.b612.lovehouse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_EFFECT_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_WALLPAPER_KEY
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val DesktopInk = Color(0xFF4B504D)
private val DesktopMuted = Color(0xFF7C8580)
private val Glass = Color(0xFFF8F5EF).copy(alpha = 0.48f)
private val GlassBorder = Color.White.copy(alpha = 0.70f)
private val GlassShadow = Color(0x21614641)
private const val DesktopEditScale = 0.80f

private enum class DesktopGridMode(val storageValue: String, val columns: Int, val rows: Int) {
    FourByFour("4x4", 4, 4),
    FourByFive("4x5", 4, 5),
    FiveByFour("5x4", 5, 4),
    FiveByFive("5x5", 5, 5),
    FiveBySix("5x6", 5, 6),
    Custom("custom", 6, 6),
}

private data class GridPlacement(val page: Int, val row: Int, val column: Int, val rowSpan: Int, val columnSpan: Int)
private sealed interface ItemPlacement {
    data class Desktop(val grid: GridPlacement) : ItemPlacement
    data class Dock(val slot: Int) : ItemPlacement
}
private data class GridGeometry(
    val rows: Int,
    val columns: Int,
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp,
) {
    val cellWidth get() = width / columns
    val cellHeight get() = height / rows
    fun modifier(placement: GridPlacement): Modifier = Modifier
        .offset(x = cellWidth * placement.column, y = cellHeight * placement.row)
        .size(cellWidth * placement.columnSpan, cellHeight * placement.rowSpan)
}

private enum class DesktopEditSheet { Appearance, Effect, Widgets, Dock, Desktop }

@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLab: () -> Unit,
    localStorage: LocalStorage,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    var activeSheet by remember { mutableStateOf<DesktopEditSheet?>(null) }
    val editor = remember {
        DesktopEditor(onPersist = { placements, hidden, gridMode, dockItems, looseDockItems, customColumns, customRows ->
            scope.launch {
                localStorage.writeString(DESKTOP_GRID_SLOTS_KEY, encodeGridPlacements(placements))
                localStorage.writeString(DESKTOP_HIDDEN_KEY, hidden.sorted().joinToString(","))
                localStorage.writeString(DESKTOP_GRID_KEY, gridMode.storageValue)
                localStorage.writeString(DESKTOP_DOCK_KEY, dockItems.joinToString(","))
                localStorage.writeString(DESKTOP_LOOSE_DOCK_KEY, looseDockItems.joinToString(","))
                localStorage.writeString(DESKTOP_CUSTOM_GRID_KEY, "$customColumns,$customRows")
            }
        })
    }
    LaunchedEffect(localStorage) {
        editor.restore(
            placements = decodeGridPlacements(localStorage.readString(DESKTOP_GRID_SLOTS_KEY)),
            hidden = localStorage.readString(DESKTOP_HIDDEN_KEY)
                ?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty(),
            gridMode = DesktopGridMode.entries.firstOrNull {
                it.storageValue == localStorage.readString(DESKTOP_GRID_KEY)
            } ?: DesktopGridMode.FourByFour,
            dockItems = localStorage.readString(DESKTOP_DOCK_KEY)?.split(',')?.filter(String::isNotBlank) ?: DEFAULT_DOCK_IDS,
            looseDockItems = localStorage.readString(DESKTOP_LOOSE_DOCK_KEY)?.split(',')?.filter(String::isNotBlank).orEmpty(),
            customGrid = localStorage.readString(DESKTOP_CUSTOM_GRID_KEY),
        )
    }
    LaunchedEffect(pagerState.currentPage) { editor.currentPage = pagerState.currentPage }
    LaunchedEffect(editor.requestedDragPage) {
        val target = editor.requestedDragPage ?: return@LaunchedEffect
        pagerState.animateScrollToPage(target)
        editor.onDragPageShown(target)
    }

    Box(
        modifier.fillMaxSize().pointerInput(editor) {
            kotlinx.coroutines.coroutineScope {
                var edgeHoverJob: kotlinx.coroutines.Job? = null
                var scheduledDirection = 0
                detectDragGesturesAfterLongPress(
                    onDragStart = { rootPosition -> editor.startDragAt(rootPosition) },
                    onDragEnd = {
                        edgeHoverJob?.cancel()
                        editor.draggingId?.let(editor::finishDrag)
                    },
                    onDragCancel = {
                        edgeHoverJob?.cancel()
                        if (editor.draggingId != null) editor.cancelDrag()
                    },
                    onDrag = { change, amount ->
                        val id = editor.draggingId ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        editor.drag(id, amount)
                        val direction = editor.edgeHoverDirection
                        if (direction != scheduledDirection) {
                            edgeHoverJob?.cancel()
                            scheduledDirection = direction
                            if (direction != 0) {
                                edgeHoverJob = launch {
                                    delay(450)
                                    editor.requestCrossPage(direction)
                                    scheduledDirection = 0
                                }
                            }
                        }
                    },
                )
            }
        },
    ) {
        if (editor.isEditing) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopEditControlsRegion(
                    editor = editor,
                    onDone = { activeSheet = null; editor.finishEditing() },
                )
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onGloballyPositioned { editor.updatePlaceableBounds(it.boundsInRoot()) },
                ) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), pageSpacing = 12.dp, userScrollEnabled = editor.draggingId == null, key = { it }) { page ->
                        DesktopPage(page, editor, onOpenLab, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    DesktopPageIndicator(pagerState.currentPage, Modifier.align(Alignment.BottomCenter))
                }
                Box(
                    Modifier.fillMaxWidth().height(98.dp)
                        .onGloballyPositioned { bounds ->
                            editor.dockBounds = bounds.boundsInRoot()
                            editor.dockRegionBounds = bounds.boundsInRoot()
                        },
                ) {
                    DesktopDock(onOpenChat = onOpenChat, onOpenSettings = onOpenSettings, editor = editor)
                }
                BottomEditToolsRegion(
                    activeSheet = activeSheet,
                    onSheet = { activeSheet = it },
                    editor = editor,
                )
            }
        } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { editor.placeableBounds = it.boundsInRoot() },
                pageSpacing = 12.dp,
                key = { it },
            ) { page ->
                DesktopPage(page, editor, onOpenLab, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).padding(top = if (editor.isEditing) 54.dp else 0.dp))
            }

            DesktopPageIndicator(pagerState.currentPage)

            DesktopDock(onOpenChat = onOpenChat, onOpenSettings = onOpenSettings, editor = editor)
        }

        editor.looseDockItems.filter { editor.looseDockPage[it] == editor.currentPage }.forEach { id -> LooseDockEntry(id, editor, onOpenChat, onOpenSettings) }

        DesktopRootDragOverlay(editor)

        if (editor.isEditing) {
            RootDeleteTarget(editor, Modifier.align(Alignment.TopStart).statusBarsPadding())
        }

        if (editor.isEditing && activeSheet != null) {
            DesktopEditSheetPanel(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                sheet = activeSheet!!,
                gridMode = editor.gridMode,
                onGridMode = editor::selectGridMode,
                customColumns = editor.customColumns,
                customRows = editor.customRows,
                onCustomGrid = editor::selectCustomGrid,
                onWallpaper = { value -> scope.launch { localStorage.writeString(APPEARANCE_WALLPAPER_KEY, value) } },
                onEffect = { value -> scope.launch { localStorage.writeString(APPEARANCE_EFFECT_KEY, value) } },
                onClose = { activeSheet = null },
            )
        }
    }
}

@Composable
private fun DesktopRootDragOverlay(editor: DesktopEditor) {
    val id = editor.draggingId ?: return
    val bounds = editor.draggedRootBounds ?: return
    val visual = editor.dragVisuals[id] ?: return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset {
                IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
            }
            .size(
                with(density) { bounds.width.toDp() },
                with(density) { bounds.height.toDp() },
            )
            .zIndex(200f)
            .graphicsLayer {
                scaleX = DesktopEditScale
                scaleY = DesktopEditScale
                transformOrigin = TransformOrigin.Center
            },
    ) { visual() }
}

@Composable
private fun DesktopPageIndicator(currentPage: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            Box(Modifier.padding(horizontal = 3.dp).width(if (currentPage == index) 18.dp else 5.dp).height(5.dp).clip(CircleShape)
                .background(if (currentPage == index) DesktopInk.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.55f)))
        }
    }
}

@Composable
private fun DesktopPage(page: Int, editor: DesktopEditor, onOpenLab: () -> Unit, modifier: Modifier) {
    DynamicGridPage(page, editor, modifier) { geometry ->
        FirstDesktopItems(page, editor, geometry)
        SecondDesktopItems(page, editor, geometry)
        ThirdDesktopItems(page, editor, geometry)
        FourthDesktopItems(page, editor, geometry, onOpenLab)
        FifthDesktopItems(page, editor, geometry)
        DockDesktopItems(page, editor, geometry)
    }
}

@Composable
private fun FirstDesktopItems(renderPage: Int, editor: DesktopEditor, geometry: GridGeometry) {
        val cellWidth = geometry.cellWidth
        val cellHeight = geometry.cellHeight
        GridGlassPanel("p1-days", renderPage, editor, GridPlacement(0, 0, 0, 1, 4), geometry, 18.dp) {
            Text("61", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("DAYS TOGETHER", color = DesktopMuted, fontFamily = FontFamily.Serif, fontSize = 8.sp, letterSpacing = 1.2.sp)
        }
        GridGlassPanel("p1-clock", renderPage, editor, GridPlacement(0, 1, 0, 2, 2), geometry, 20.dp) {
                Text("12:51", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Text("9月1日 · 星期二", color = DesktopMuted, fontFamily = FontFamily.Serif, fontSize = 9.sp)
                Text("· right here", color = DesktopMuted, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 10.sp)
        }
        GridDesktopApp("♡", "收藏", renderPage, editor, GridPlacement(0, 1, 2, 1, 1), geometry)
        GridDesktopApp("⌑", "日历", renderPage, editor, GridPlacement(0, 1, 3, 1, 1), geometry)
        GridDesktopApp("☁", "天气", renderPage, editor, GridPlacement(0, 2, 2, 1, 1), geometry)
        GridDesktopApp("✎", "画像", renderPage, editor, GridPlacement(0, 2, 3, 1, 1), geometry)
        GridGlassPanel("p1-space", renderPage, editor, GridPlacement(0, 3, 0, 1, 2), geometry, 18.dp) {
                Text("Our Space", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 15.sp)
                Text("🌷  ·  🏠  ·  ☁", fontSize = 18.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("memory.sync", color = DesktopMuted, fontSize = 7.sp)
                    Text("always on", color = DesktopMuted, fontSize = 7.sp)
                }
        }
        GridGlassPanel("p1-diary", renderPage, editor, GridPlacement(0, 3, 2, 1, 2), geometry, 18.dp) {
                Text("My Diary", color = DesktopInk, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp)
                Text("所有窗口都通向同一个小屋。", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 10.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Day 61", color = DesktopMuted, fontSize = 7.sp)
                    Text("2026.08.16", color = DesktopMuted, fontSize = 7.sp)
                }
        }
}

@Composable
private fun DynamicGridPage(page: Int, editor: DesktopEditor, modifier: Modifier, content: @Composable BoxScope.(GridGeometry) -> Unit) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val geometry = GridGeometry(editor.gridRows, editor.gridColumns, maxWidth, maxHeight)
        key(page, editor.gridRows, editor.gridColumns) {
            editor.dragPreview?.takeIf { it.page == page }?.let { target ->
                Box(geometry.modifier(target).padding(7.dp)
                    .border(2.dp, if (editor.dragPreviewValid) Color(0x884F8B68) else Color(0xB8B44E5A), RoundedCornerShape(16.dp))
                    .background(if (editor.dragPreviewValid) Color(0x334F8B68) else Color(0x33B44E5A), RoundedCornerShape(16.dp)))
            }
            content(geometry)
        }
    }
}

@Composable
private fun GridGlassPanel(id: String, renderPage: Int, editor: DesktopEditor, default: GridPlacement, geometry: GridGeometry, radius: androidx.compose.ui.unit.Dp, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val contentScale = minOf(1f, 4f / editor.gridColumns, 4f / editor.gridRows)
    GridPlacedItem(id, renderPage, editor, default, geometry) {
        Surface(Modifier.fillMaxSize(), RoundedCornerShape(radius * contentScale), Glass, border = BorderStroke(1.dp, GlassBorder)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * contentScale, density.fontScale)) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
            }
        }
    }
}

@Composable
private fun GridDesktopApp(glyph: String, label: String, renderPage: Int, editor: DesktopEditor, default: GridPlacement, geometry: GridGeometry, itemId: String = "page:${default.page}:app:$glyph:$label", onClick: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val contentScale = minOf(1f, 4f / editor.gridColumns, 4f / editor.gridRows)
    GridPlacedItem(itemId, renderPage, editor, default, geometry) {
        CompositionLocalProvider(LocalDensity provides Density(density.density * contentScale, density.fontScale)) {
            Column(
                Modifier.fillMaxSize().then(
                    if (onClick != null && !editor.isEditing) Modifier.clickable(onClick = onClick) else Modifier,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(Modifier.size((minOf(geometry.cellWidth.value, geometry.cellHeight.value) * .52f / contentScale).dp), RoundedCornerShape(15.dp), Color(0xDDF8F5EF), border = BorderStroke(1.dp, GlassBorder)) { Box(contentAlignment = Alignment.Center) { Text(glyph, color = DesktopInk, fontSize = 18.sp) } }
                Text(label, Modifier.padding(top = 4.dp), color = DesktopInk, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DockDesktopItems(renderPage: Int, editor: DesktopEditor, geometry: GridGeometry) {
    DockSpecs.forEachIndexed { index, spec ->
        GridDesktopApp(spec.glyph, spec.label, renderPage, editor, GridPlacement(0, index / 4, index % 4, 1, 1), geometry, spec.id)
    }
}

@Composable
private fun GridPlacedItem(id: String, renderPage: Int, editor: DesktopEditor, default: GridPlacement, geometry: GridGeometry, content: @Composable () -> Unit) {
    val placement = editor.gridPlacement(id, default)
    if (editor.isDockPlaced(id)) return
    if (placement.page != renderPage) return
    MovableDesktopItem(
        id, editor,
        geometry.modifier(placement).padding(5.dp),
        content,
    )
}

@Composable
private fun SecondDesktopItems(renderPage: Int, editor: DesktopEditor, geometry: GridGeometry) {
        GridGlassPanel("p2-ecosystem", renderPage, editor, GridPlacement(1, 0, 0, 2, 2), geometry, 20.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("瓶中生态", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 15.sp)
                    Text("第46天 · 夏", color = DesktopMuted, fontSize = 8.sp)
                }
                Text("◯   ◌   ◯", modifier = Modifier.fillMaxWidth(), color = DesktopInk.copy(alpha = 0.48f), fontSize = 30.sp, textAlign = TextAlign.Center)
                Text("● 绿藻      ● 苍穹", color = DesktopMuted, fontSize = 8.sp)
                Text("翠鸟扎进水里，又空空地窜回来。池塘里没有它要找的银亮影子。", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 9.sp, lineHeight = 14.sp)
        }
        GridDesktopApp("⌂", "家", renderPage, editor, GridPlacement(1, 0, 2, 1, 1), geometry)
        GridDesktopApp("☂", "共读", renderPage, editor, GridPlacement(1, 0, 3, 1, 1), geometry)
        GridDesktopApp("●", "待办", renderPage, editor, GridPlacement(1, 1, 2, 1, 1), geometry)
        GridDesktopApp("☾", "小账本", renderPage, editor, GridPlacement(1, 1, 3, 1, 1), geometry)
        GridGlassPanel("p2-music", renderPage, editor, GridPlacement(1, 2, 0, 1, 4), geometry, 20.dp) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().width(72.dp).background(Color.White.copy(alpha = 0.44f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Text("🎧", fontSize = 28.sp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("NOW PLAYING · 14 QUEUED", color = DesktopMuted, fontSize = 7.sp, letterSpacing = 0.8.sp)
                    Text("NO HOOK FREESTYLE Pt.4", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 13.sp)
                    Text("Rapeter", color = DesktopMuted, fontSize = 9.sp)
                    Text("♡       ‹      ▶      ›", color = DesktopInk, fontSize = 14.sp)
                }
            }
        }
}

@Composable
private fun ThirdDesktopItems(renderPage: Int, editor: DesktopEditor, geometry: GridGeometry) {
        listOf("▤" to "世界书", "🎨" to "画廊", "▣" to "相册", "◎" to "小圈").forEachIndexed { column, item ->
            GridDesktopApp(item.first, item.second, renderPage, editor, GridPlacement(2, 0, column, 1, 1), geometry)
        }
        GridGlassPanel("p3-health", renderPage, editor, GridPlacement(2, 1, 0, 2, 2), geometry, 20.dp) {
                Text("♡ ─── ♡", color = DesktopMuted, fontSize = 10.sp)
                Text("73", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 34.sp)
                Text("bpm", color = DesktopMuted, fontSize = 9.sp)
                Text("36.5°C · 正常", color = DesktopMuted, fontSize = 10.sp)
                Text("⌁──⌁──⌁", color = DesktopInk.copy(alpha = 0.5f), fontSize = 12.sp)
        }
        GridGlassPanel("ghost:朋友圈", renderPage, editor, GridPlacement(2, 1, 2, 1, 2), geometry, 18.dp) { Text("朋友圈", color = DesktopInk, fontSize = 13.sp); Text("MOMENTS · 最近动态", color = DesktopMuted, fontSize = 7.sp) }
        GridGlassPanel("ghost:观星室", renderPage, editor, GridPlacement(2, 2, 2, 1, 2), geometry, 18.dp) { Text("观星室", color = DesktopInk, fontSize = 13.sp); Text("OBSERVATORY · 找一颗星", color = DesktopMuted, fontSize = 7.sp) }
}

@Composable
private fun FourthDesktopItems(renderPage: Int, editor: DesktopEditor, geometry: GridGeometry, onOpenLab: () -> Unit) {
        listOf("A" to "背单词", "♫" to "音乐", "▥" to "小屋快报", "⌂" to "小屋档案").forEachIndexed { column, item -> GridDesktopApp(item.first, item.second, renderPage, editor, GridPlacement(3, 0, column, 1, 1), geometry) }
        listOf("✦" to "观星室", "◉" to "BOBO", "▤" to "万花筒").forEachIndexed { column, item -> GridDesktopApp(item.first, item.second, renderPage, editor, GridPlacement(3, 1, column, 1, 1), geometry) }
        GridDesktopApp("⚗", "Lab", renderPage, editor, GridPlacement(3, 1, 3, 1, 1), geometry, onClick = onOpenLab)
        GridGlassPanel("p4-calendar", renderPage, editor, GridPlacement(3, 2, 0, 2, 4), geometry, 20.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("2026 · 八月", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 13.sp)
                Text("›", color = DesktopInk, fontSize = 16.sp)
            }
            CalendarGrid()
        }
}

@Composable
private fun FifthDesktopItems(renderPage: Int, editor: DesktopEditor, geometry: GridGeometry) {
        GridGlassPanel("p5-house", renderPage, editor, GridPlacement(4, 0, 0, 2, 2), geometry, 20.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("陆宅", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 15.sp)
                    Text("会客中", color = DesktopMuted, fontSize = 8.sp)
                }
                Text("百", modifier = Modifier.fillMaxWidth(), color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 34.sp, textAlign = TextAlign.Center)
                Text("Guest 来过：你好！欢迎来访。快请坐！\n☕ 小婷的朋友就是我的朋友。", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 9.sp, lineHeight = 14.sp)
        }
        GridGlassPanel("p5-tide", renderPage, editor, GridPlacement(4, 0, 2, 2, 2), geometry, 20.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("潮汐", color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 15.sp)
                    Text("指数 5.6", color = DesktopMuted, fontSize = 8.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("〰︎  〰︎  〰︎", modifier = Modifier.fillMaxWidth(), color = Color(0xFFA68A96), fontSize = 23.sp, textAlign = TextAlign.Center)
                Text("今天中潮", modifier = Modifier.fillMaxWidth(), color = DesktopMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
        }
        listOf("▯" to "小手机", "✦" to "总控台", "◔" to "通知", "☑" to "Todo").forEachIndexed { column, item -> GridDesktopApp(item.first, item.second, renderPage, editor, GridPlacement(4, 2, column, 1, 1), geometry) }
        listOf("♡" to "健康", "⌁" to "设备", "▣" to "快递", "⌂" to "生活服务").forEachIndexed { column, item -> GridDesktopApp(item.first, item.second, renderPage, editor, GridPlacement(4, 3, column, 1, 1), geometry) }
}

@Composable
private fun AppGridRow(editor: DesktopEditor, items: List<Pair<String, String>>) {
    val rowHeight = if (editor.gridMode.rows == 5) 76.dp else 88.dp
    Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(if (editor.gridMode.columns == 5) 6.dp else 10.dp)) {
        items.forEach { (glyph, label) -> DesktopApp(glyph, label, editor, Modifier.weight(1f)) }
        repeat((editor.gridMode.columns - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun DesktopApp(glyph: String, label: String, editor: DesktopEditor, modifier: Modifier = Modifier) {
    val iconSize = if (editor.gridMode.columns == 5) 42.dp else 48.dp
    MovableDesktopItem("app:$glyph:$label", editor, modifier) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(
            modifier = Modifier.size(iconSize).shadow(5.dp, RoundedCornerShape(15.dp), ambientColor = GlassShadow, spotColor = GlassShadow),
            shape = RoundedCornerShape(15.dp),
            color = Color(0xDDF8F5EF),
            border = BorderStroke(1.dp, GlassBorder),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(glyph, color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 18.sp)
            }
        }
        Text(label, modifier = Modifier.padding(top = 5.dp), color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 9.sp, maxLines = 1)
    }
    }
}

@Composable
private fun GlassPanel(
    itemId: String,
    editor: DesktopEditor,
    modifier: Modifier,
    radius: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    MovableDesktopItem(itemId, editor, modifier) {
    Surface(
        modifier = Modifier.fillMaxSize().shadow(10.dp, RoundedCornerShape(radius), ambientColor = GlassShadow, spotColor = GlassShadow),
        shape = RoundedCornerShape(radius),
        color = Glass,
        border = BorderStroke(1.dp, GlassBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
    }
}

@Composable
private fun GhostWidget(title: String, subtitle: String, editor: DesktopEditor, modifier: Modifier) {
    GlassPanel("ghost:$title", editor, modifier.fillMaxWidth(), 18.dp) {
        Text(title, color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 13.sp)
        Text(subtitle, color = DesktopMuted, fontSize = 7.sp)
    }
}

@Composable
private fun CalendarGrid() {
    val values = listOf("日", "一", "二", "三", "四", "五", "六") + (1..31).map(Int::toString)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        values.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                week.forEach { value ->
                    Text(
                        value,
                        modifier = Modifier.width(28.dp),
                        color = if (value in setOf("16", "19", "25")) Color(0xFF98677B) else DesktopMuted,
                        fontSize = 7.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private data class DockSpec(val id: String, val glyph: String, val label: String)
private val DockSpecs = listOf(DockSpec("dock:chat", "◌", "聊天"), DockSpec("dock:friends", "✿", "朋友圈"), DockSpec("dock:room", "◌◌", "群聊"), DockSpec("dock:settings", "⚙", "设置"))
private val DEFAULT_DOCK_IDS = DockSpecs.map { it.id }
private fun dockSpec(id: String): DockSpec = DockSpecs.firstOrNull { it.id == id } ?: id.split(':').let { parts ->
    DockSpec(id, parts.getOrNull(parts.lastIndex - 1) ?: "◇", parts.lastOrNull() ?: id)
}

@Composable
private fun DesktopDock(onOpenChat: () -> Unit, onOpenSettings: () -> Unit, editor: DesktopEditor) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(82.dp)
            .shadow(14.dp, RoundedCornerShape(25.dp), ambientColor = GlassShadow, spotColor = GlassShadow),
        shape = RoundedCornerShape(25.dp),
        color = Color(0xB8F8F5EF),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            editor.dockItems.forEach { id ->
                val spec = dockSpec(id)
                DockEntry(spec, editor, when (id) { "dock:chat" -> onOpenChat; "dock:settings" -> onOpenSettings; else -> null })
            }
            if (editor.isEditing && editor.dockItems.size < 4) Box(Modifier.width(64.dp).fillMaxHeight().background(Color.White.copy(alpha = .18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("＋", color = DesktopMuted) }
        }
    }
    if (editor.dockFullFeedback) Text("常用区域已满", Modifier.fillMaxWidth(), color = Color(0xFF9A6870), fontSize = 8.sp, textAlign = TextAlign.Center)
}

@Composable
private fun DockEntry(spec: DockSpec, editor: DesktopEditor, onClick: (() -> Unit)? = null) {
    val scale by animateFloatAsState(if (editor.isEditing) DesktopEditScale else 1f, label = "dock edit scale")
    val itemOffset = editor.offsets[spec.id] ?: Offset.Zero
    val dockVisual: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(Modifier.size(39.dp), RoundedCornerShape(13.dp), Color.White.copy(alpha = 0.50f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.70f))) {
                Box(contentAlignment = Alignment.Center) { Text(spec.glyph, color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
            Text(spec.label, Modifier.padding(top = 4.dp), color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 8.sp)
        }
    }
    SideEffect { editor.dragVisuals[spec.id] = dockVisual }
    Column(
        modifier = Modifier.width(64.dp).fillMaxHeight().offset { IntOffset(itemOffset.x.roundToInt(), itemOffset.y.roundToInt()) }
            .zIndex(if (editor.draggingId == spec.id) 30f else 0f)
            .graphicsLayer { alpha = if (editor.draggingId == spec.id) 0f else 1f }
            .onGloballyPositioned { editor.registerItemBounds(spec.id, it.boundsInRoot()) }
            .then(if (!editor.isEditing && onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin.Center
            }.onGloballyPositioned { editor.visualBounds[spec.id] = it.boundsInRoot() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            dockVisual()
        }
    }
}

@Composable
private fun LooseDockEntry(id: String, editor: DesktopEditor, onOpenChat: () -> Unit, onOpenSettings: () -> Unit) {
    val spec = dockSpec(id)
    val offset = editor.offsets[id] ?: Offset(80f, 360f)
    val scale by animateFloatAsState(if (editor.isEditing) DesktopEditScale else 1f, label = "loose dock edit scale")
    val onClick = when (id) {
        "dock:chat" -> onOpenChat
        "dock:settings" -> onOpenSettings
        else -> null
    }
    Box(Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }.size(74.dp).zIndex(15f)
        .onGloballyPositioned { editor.registerItemBounds(id, it.boundsInRoot()) }
        .pointerInput(id) { detectDragGesturesAfterLongPress(onDragStart = { editor.startDrag(id) }, onDragEnd = { editor.finishDrag(id) }, onDragCancel = editor::cancelDrag, onDrag = { change, amount -> change.consume(); editor.drag(id, amount) }) }
        .then(if (!editor.isEditing && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin.Center }
                .onGloballyPositioned { editor.visualBounds[id] = it.boundsInRoot() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(Modifier.size(44.dp), RoundedCornerShape(14.dp), Color(0xDDF8F5EF), border = BorderStroke(1.dp, GlassBorder)) { Box(contentAlignment = Alignment.Center) { Text(spec.glyph, color = DesktopInk) } }
            Text(spec.label, color = DesktopInk, fontSize = 8.sp)
        }
    }
}

private const val DESKTOP_GRID_SLOTS_KEY = "desktop_grid_slots_v3"
private const val DESKTOP_HIDDEN_KEY = "desktop_layout_hidden_v2"
private const val DESKTOP_GRID_KEY = "desktop_grid_mode_v1"
private const val DESKTOP_DOCK_KEY = "desktop_dock_items_v1"
private const val DESKTOP_LOOSE_DOCK_KEY = "desktop_loose_dock_items_v1"
private const val DESKTOP_CUSTOM_GRID_KEY = "desktop_custom_grid_v1"

private class DesktopEditor(
    private val onPersist: (Map<String, GridPlacement>, Set<String>, DesktopGridMode, List<String>, List<String>, Int, Int) -> Unit,
) {
    val offsets = mutableStateMapOf<String, Offset>()
    private val gridSlots = mutableStateMapOf<String, Offset>()
    private val gridDefaults = mutableStateMapOf<String, GridPlacement>()
    private val savedPlacements = mutableStateMapOf<String, GridPlacement>()
    private val placementStates = mutableStateMapOf<String, ItemPlacement>()
    val hidden = mutableStateMapOf<String, Boolean>()
    val itemBounds = mutableStateMapOf<String, Rect>()
    val visualBounds = mutableStateMapOf<String, Rect>()
    val dragVisuals = mutableMapOf<String, @Composable () -> Unit>()
    var deleteBounds: Rect? = null
    var dockBounds: Rect? = null
    var topRegionBounds by mutableStateOf<Rect?>(null)
    var placeableBounds by mutableStateOf<Rect?>(null)
    var editToolsBounds by mutableStateOf<Rect?>(null)
    var dockRegionBounds by mutableStateOf<Rect?>(null)
    val dockItems = mutableStateListOf<String>()
    val looseDockItems = mutableStateListOf<String>()
    val looseDockPage = mutableStateMapOf<String, Int>()
    var currentPage by mutableStateOf(0)
    var dockFullFeedback by mutableStateOf(false)
    var isEditing by mutableStateOf(false)
        private set
    var draggingId by mutableStateOf<String?>(null)
        private set
    var overDelete by mutableStateOf(false)
        private set
    var gridMode by mutableStateOf(DesktopGridMode.FourByFour)
        private set
    var customColumns by mutableStateOf(6)
        private set
    var customRows by mutableStateOf(6)
        private set
    val gridColumns: Int get() = if (gridMode == DesktopGridMode.Custom) customColumns else gridMode.columns
    val gridRows: Int get() = if (gridMode == DesktopGridMode.Custom) customRows else gridMode.rows
    var gridConflictFeedback by mutableStateOf(false)
    var dragPreview by mutableStateOf<GridPlacement?>(null)
        private set
    var dragPreviewValid by mutableStateOf(false)
        private set
    var edgeHoverDirection by mutableStateOf(0)
        private set
    var requestedDragPage by mutableStateOf<Int?>(null)
        private set
    var draggedRootBounds by mutableStateOf<Rect?>(null)
        private set
    private var dragStartOffset = Offset.Zero
    private var dragStartBounds: Rect? = null
    private var dragStartPlacement: GridPlacement? = null
    private var dragStartState: ItemPlacement? = null
    private var draggedScreenBounds: Rect? = null
    private var hasDragged = false

    fun restore(placements: Map<String, GridPlacement>, hidden: Set<String>, gridMode: DesktopGridMode, dockItems: List<String>, looseDockItems: List<String>, customGrid: String?) {
        this.offsets.clear()
        this.gridSlots.clear()
        this.savedPlacements.clear(); this.savedPlacements.putAll(placements)
        placementStates.clear()
        gridDefaults.forEach { (id, placement) -> placementStates[id] = ItemPlacement.Desktop(placement) }
        placements.forEach { (id, placement) -> placementStates[id] = ItemPlacement.Desktop(placement) }
        placements.forEach { (id, placement) -> this.gridSlots[id] = Offset(placement.column.toFloat(), placement.row.toFloat()) }
        this.hidden.clear()
        hidden.forEach { this.hidden[it] = true }
        this.gridMode = gridMode
        customGrid?.split(',')?.let { values ->
            customColumns = values.getOrNull(0)?.toIntOrNull()?.coerceIn(3, 8) ?: 6
            customRows = values.getOrNull(1)?.toIntOrNull()?.coerceIn(3, 8) ?: 6
        }
        this.dockItems.clear(); this.dockItems.addAll(dockItems.take(4))
        this.dockItems.forEachIndexed { slot, id -> placementStates[id] = ItemPlacement.Dock(slot) }
        this.looseDockItems.clear(); this.looseDockItems.addAll(looseDockItems)
        looseDockItems.forEach { looseDockPage[it] = 0 }
    }

    fun updatePlaceableBounds(bounds: Rect) {
        placeableBounds = bounds
        itemBounds.keys.filterNot(gridDefaults::containsKey).forEach(::applyGridSlot)
    }

    fun registerItemBounds(id: String, bounds: Rect) {
        itemBounds[id] = bounds
        if (draggingId == id) {
            if (!hasDragged) {
                draggedScreenBounds = bounds
                draggedRootBounds = bounds
            }
            return
        }
        if (id in dockItems) return
        if (id in gridDefaults) return
        val placeable = placeableBounds ?: return
        if (!bounds.overlaps(placeable)) return
        if (gridSlots[id] != null) applyGridSlot(id)
    }

    fun startDrag(id: String) {
        isEditing = true
        draggingId = id
        dragStartOffset = offsets[id] ?: Offset.Zero
        dragStartBounds = itemBounds[id]
        draggedScreenBounds = itemBounds[id]
        draggedRootBounds = itemBounds[id]
        dragStartPlacement = gridDefaults[id]
        dragStartState = placementStates[id] ?: dragStartPlacement?.let(ItemPlacement::Desktop)
        hasDragged = false
    }

    fun startDragAt(rootPosition: Offset) {
        val id = itemBounds.entries
            .asSequence()
            .filter { (candidateId, bounds) ->
                ((placementStates[candidateId] as? ItemPlacement.Desktop)?.grid?.page == currentPage || candidateId in dockItems) &&
                    (hidden[candidateId] != true || candidateId in dockItems) &&
                    rootPosition in bounds
            }
            .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
            ?.key
            ?: return
        startDrag(id)
    }

    fun gridPlacement(id: String, default: GridPlacement): GridPlacement {
        val durable = (placementStates[id] as? ItemPlacement.Desktop)?.grid ?: savedPlacements[id] ?: default
        gridDefaults[id] = durable
        if (placementStates[id] == null && id !in dockItems) placementStates[id] = ItemPlacement.Desktop(durable)
        val slot = gridSlots[id]
        return if (slot == null) durable else durable.copy(column = slot.x.roundToInt(), row = slot.y.roundToInt())
    }

    fun isDockPlaced(id: String): Boolean = placementStates[id] is ItemPlacement.Dock || id in dockItems

    fun drag(id: String, amount: Offset) {
        if (amount.getDistanceSquared() > 1f) hasDragged = true
        if (id !in gridDefaults) offsets[id] = (offsets[id] ?: Offset.Zero) + amount
        draggedScreenBounds = (draggedScreenBounds ?: itemBounds[id])?.translate(amount)
        draggedRootBounds = draggedScreenBounds
        overDelete = overlapsDelete(id)
        val base = gridDefaults[id]
        val current = draggedScreenBounds
        if (base != null && current != null) {
            val slot = slotFor(current)
            dragPreview = base.copy(page = currentPage, column = slot.x.roundToInt(), row = slot.y.roundToInt())
            dragPreviewValid = isGridPlacementAvailable(id, dragPreview!!) && !overDelete
        }
        updateEdgeHover()
    }

    fun requestCrossPage(direction: Int) {
        draggingId ?: return
        if (direction != edgeHoverDirection) return
        val targetPage = (currentPage + direction).coerceIn(0, 4)
        if (targetPage == currentPage) { edgeHoverDirection = 0; return }
        edgeHoverDirection = 0
        dragPreview = null
        dragPreviewValid = false
        requestedDragPage = targetPage
    }

    fun onDragPageShown(page: Int) {
        currentPage = page
        requestedDragPage = null
        val id = draggingId ?: return
        val base = gridDefaults[id] ?: return
        val bounds = draggedRootBounds ?: return
        val slot = slotFor(bounds)
        dragPreview = base.copy(page = page, column = slot.x.roundToInt(), row = slot.y.roundToInt())
        dragPreviewValid = isGridPlacementAvailable(id, dragPreview!!)
    }

    private fun updateEdgeHover() {
        if (overDelete) { edgeHoverDirection = 0; return }
        val bounds = draggedScreenBounds ?: return
        val region = placeableBounds ?: return
        val threshold = region.width * 0.10f
        edgeHoverDirection = when {
            bounds.center.x <= region.left + threshold && currentPage > 0 -> -1
            bounds.center.x >= region.right - threshold && currentPage < 4 -> 1
            else -> 0
        }
    }

    fun finishDrag(id: String) {
        if (!hasDragged) {
            cancelDrag()
            return
        }
        if (overlapsDelete(id)) {
            hidden[id] = true
            dockItems.remove(id)
            placementStates.remove(id)
            reindexDockPlacements()
            looseDockItems.remove(id)
            offsets.remove(id)
        } else if (overlapsDock(id)) {
            if (dockItems.size < 4 || id in dockItems) {
                looseDockItems.remove(id)
                val insertion = dockInsertionIndex(id)
                dockItems.remove(id)
                dockItems.add(insertion.coerceIn(0, dockItems.size), id)
                hidden.remove(id)
                reindexDockPlacements()
                offsets.remove(id)
                dockFullFeedback = false
            } else {
                offsets[id] = dragStartOffset
                dockFullFeedback = true
            }
        } else {
            if (id in gridDefaults && isWithinPlaceable(id)) {
                val candidatePlacement = dragPreview
                val candidate = candidatePlacement?.let { Offset(it.column.toFloat(), it.row.toFloat()) } ?: itemBounds[id]?.let(::slotFor) ?: Offset.Zero
                if (dragPreviewValid || (dragPreview == null && isGridCellAvailable(id, candidate))) {
                    val accepted = (candidatePlacement ?: gridDefaults.getValue(id).copy(
                        page = currentPage,
                        column = candidate.x.roundToInt(),
                        row = candidate.y.roundToInt(),
                    ))
                    gridDefaults[id] = accepted
                    savedPlacements[id] = accepted
                    placementStates[id] = ItemPlacement.Desktop(accepted)
                    dockItems.remove(id)
                    reindexDockPlacements()
                    hidden.remove(id)
                    gridSlots[id] = candidate
                    offsets.remove(id)
                    gridConflictFeedback = false
                } else {
                    offsets[id] = dragStartOffset
                    gridConflictFeedback = true
                    val restorePage = dragStartPlacement?.page
                    dragStartPlacement?.let { original ->
                        gridDefaults[id] = original
                        savedPlacements[id] = original
                        gridSlots[id] = Offset(original.column.toFloat(), original.row.toFloat())
                    }
                    clearDragSession()
                    requestedDragPage = restorePage
                    persist()
                    return
                }
                clearDragSession(); persist()
                return
            }
            val dragged = itemBounds[id]
            val target = dragged?.let { draggedBounds ->
                itemBounds.entries
                    .asSequence()
                    .filter { (candidateId, candidateBounds) ->
                        candidateId != id && hidden[candidateId] != true &&
                            sameSlotSize(draggedBounds, candidateBounds) &&
                            draggedBounds.center in candidateBounds
                    }
                    .minByOrNull { (_, candidateBounds) ->
                        (candidateBounds.center - draggedBounds.center).getDistanceSquared()
                    }
            }
            if (target != null && dragStartBounds != null) {
                val targetId = target.key
                val targetBounds = target.value
                val draggedBase = baseBounds(id, dragged)
                val targetBase = baseBounds(targetId, targetBounds)
                offsets[id] = targetBounds.topLeft - draggedBase.topLeft
                offsets[targetId] = dragStartBounds!!.topLeft - targetBase.topLeft
            } else if (isWithinPlaceable(id) && !overlapsAnotherItem(id)) {
                itemBounds[id]?.let { gridSlots[id] = slotFor(it) }
                applyGridSlot(id)
                if (id.startsWith("dock:")) {
                    if (id !in looseDockItems) looseDockItems += id
                    dockItems.remove(id)
                    hidden.remove(id)
                }
            } else {
                offsets[id] = dragStartOffset
            }
        }
        clearDragSession()
        persist()
    }

    fun finishDockDrag(id: String) {
        finishDrag(id)
    }

    fun cancelDrag() {
        val restorePage = dragStartPlacement?.page
        draggingId?.let { offsets[it] = dragStartOffset }
        draggingId?.let { id -> dragStartPlacement?.let { gridDefaults[id] = it; savedPlacements[id] = it; gridSlots[id] = Offset(it.column.toFloat(), it.row.toFloat()) } }
        draggingId?.let { id -> dragStartState?.let { placementStates[id] = it } }
        clearDragSession()
        requestedDragPage = restorePage
    }

    fun remove(id: String) {
        hidden[id] = true
        offsets.remove(id)
        persist()
    }

    fun finishEditing() {
        isEditing = false
        clearDragSession()
        persist()
    }

    fun selectGridMode(mode: DesktopGridMode) {
        val placements = gridDefaults.map { (id, fallback) ->
            val slot = gridSlots[id]
            fallback.copy(column = slot?.x?.roundToInt() ?: fallback.column, row = slot?.y?.roundToInt() ?: fallback.row)
        }
        val fits = placements.all { it.column + it.columnSpan <= mode.columns && it.row + it.rowSpan <= mode.rows } &&
            placements.withIndex().all { (index, item) -> placements.drop(index + 1).none { other -> item.page == other.page && cellsOverlap(item, other) } }
        if (fits) { gridMode = mode; gridConflictFeedback = false; persist() } else gridConflictFeedback = true
    }
    fun selectCustomGrid(columns: Int, rows: Int) {
        customColumns = columns.coerceIn(3, 8)
        customRows = rows.coerceIn(3, 8)
        selectGridMode(DesktopGridMode.Custom)
    }

    private fun slotFor(bounds: Rect): Offset {
        val placeable = placeableBounds ?: return Offset.Zero
        val cellWidth = placeable.width / gridColumns
        val cellHeight = placeable.height / gridRows
        val span = gridDefaults.entries.firstOrNull { itemBounds[it.key] == bounds }?.value
        val column = ((bounds.left - placeable.left) / cellWidth).roundToInt().coerceIn(0, gridColumns - (span?.columnSpan ?: 1))
        val row = ((bounds.top - placeable.top) / cellHeight).roundToInt().coerceIn(0, gridRows - (span?.rowSpan ?: 1))
        return Offset(column.toFloat(), row.toFloat())
    }

    private fun isGridCellAvailable(id: String, slot: Offset): Boolean {
        val base = gridDefaults[id] ?: return true
        val candidate = base.copy(column = slot.x.roundToInt(), row = slot.y.roundToInt())
        return isGridPlacementAvailable(id, candidate)
    }
    private fun isGridPlacementAvailable(id: String, candidate: GridPlacement): Boolean {
        return placementStates.none { (otherId, placement) ->
            if (otherId == id || hidden[otherId] == true) return@none false
            val other = (placement as? ItemPlacement.Desktop)?.grid ?: return@none false
            other.page == candidate.page && cellsOverlap(candidate, other)
        }
    }
    private fun cellsOverlap(a: GridPlacement, b: GridPlacement): Boolean =
        a.column < b.column + b.columnSpan && a.column + a.columnSpan > b.column &&
            a.row < b.row + b.rowSpan && a.row + a.rowSpan > b.row

    private fun applyGridSlot(id: String) {
        if (id in dockItems || draggingId == id) return
        val slot = gridSlots[id] ?: return
        val current = itemBounds[id] ?: return
        val placeable = placeableBounds ?: return
        val cellWidth = placeable.width / gridColumns
        val cellHeight = placeable.height / gridRows
        val desired = Offset(placeable.left + slot.x * cellWidth, placeable.top + slot.y * cellHeight)
        val delta = desired - current.topLeft
        if (delta.getDistanceSquared() > 1f) offsets[id] = (offsets[id] ?: Offset.Zero) + delta
    }

    private fun overlapsDelete(id: String): Boolean {
        if (!hasDragged) return false
        val item = if (draggingId == id) draggedScreenBounds else itemBounds[id] ?: return false
        val target = deleteBounds ?: return false
        return item?.overlaps(target) == true
    }

    private fun clearDragSession() {
        draggingId = null
        overDelete = false
        dragPreview = null
        dragPreviewValid = false
        dragStartBounds = null
        dragStartPlacement = null
        dragStartState = null
        hasDragged = false
        draggedScreenBounds = null
        draggedRootBounds = null
        edgeHoverDirection = 0
        requestedDragPage = null
    }

    private fun overlapsDock(id: String): Boolean = (if (draggingId == id) draggedRootBounds else itemBounds[id])?.overlaps(dockBounds ?: return false) == true
    private fun isWithinPlaceable(id: String): Boolean {
        val item = (if (draggingId == id) draggedRootBounds else itemBounds[id]) ?: return false
        val placeable = placeableBounds ?: return false
        return item.left >= placeable.left && item.right <= placeable.right && item.top >= placeable.top && item.bottom <= placeable.bottom
    }
    private fun overlapsAnotherItem(id: String): Boolean {
        val item = itemBounds[id] ?: return false
        return itemBounds.any { (otherId, other) ->
            otherId != id && hidden[otherId] != true && otherId !in dockItems &&
                minOf(item.right, other.right) - maxOf(item.left, other.left) > 8f &&
                minOf(item.bottom, other.bottom) - maxOf(item.top, other.top) > 8f
        }
    }
    private fun dockInsertionIndex(id: String): Int {
        val centerX = (if (draggingId == id) draggedRootBounds else itemBounds[id])?.center?.x ?: return dockItems.size
        return dockItems.mapNotNull { itemBounds[it]?.center?.x }.count { it < centerX }
    }

    private fun reindexDockPlacements() {
        dockItems.forEachIndexed { slot, itemId -> placementStates[itemId] = ItemPlacement.Dock(slot) }
    }

    private fun baseBounds(id: String, current: Rect): Rect {
        val offset = offsets[id] ?: Offset.Zero
        return Rect(
            left = current.left - offset.x,
            top = current.top - offset.y,
            right = current.right - offset.x,
            bottom = current.bottom - offset.y,
        )
    }

    private fun sameSlotSize(first: Rect, second: Rect): Boolean =
        kotlin.math.abs(first.width - second.width) < 24f &&
            kotlin.math.abs(first.height - second.height) < 24f

    private fun persist() {
        val placements = gridDefaults.filterKeys { placementStates[it] is ItemPlacement.Desktop }.mapValues { (id, base) ->
            val slot = gridSlots[id]
            base.copy(column = slot?.x?.roundToInt() ?: base.column, row = slot?.y?.roundToInt() ?: base.row)
        }
        savedPlacements.clear(); savedPlacements.putAll(placements)
        onPersist(placements, hidden.filterValues { it }.keys, gridMode, dockItems.toList(), looseDockItems.toList(), customColumns, customRows)
    }
}

@Composable
private fun MovableDesktopItem(
    id: String,
    editor: DesktopEditor,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    SideEffect { editor.dragVisuals[id] = content }
    if (editor.hidden[id] == true) {
        Spacer(modifier)
        return
    }

    val itemOffset = editor.offsets[id] ?: Offset.Zero
    val editScale by animateFloatAsState(if (editor.isEditing) DesktopEditScale else 1f, label = "desktop edit scale")
    Box(
        modifier = modifier
            .offset { IntOffset(itemOffset.x.roundToInt(), itemOffset.y.roundToInt()) }
            .zIndex(if (editor.draggingId == id) 20f else 0f)
            .graphicsLayer { alpha = if (editor.draggingId == id) 0f else 1f }
            .onGloballyPositioned { editor.registerItemBounds(id, it.boundsInRoot()) },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = editScale
                scaleY = editScale
                transformOrigin = TransformOrigin.Center
            }.onGloballyPositioned { editor.visualBounds[id] = it.boundsInRoot() },
        ) {
            content()
            if (editor.isEditing) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(22.dp)
                        .clickable { editor.remove(id) },
                    shape = CircleShape,
                    color = Color(0xE9FFF9F4),
                    border = BorderStroke(1.dp, Color(0x66A06F77)),
                    shadowElevation = 3.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("−", color = Color(0xFF8B5F68), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopEditControlsRegion(editor: DesktopEditor, onDone: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(64.dp)
            .onGloballyPositioned { editor.topRegionBounds = it.boundsInRoot() },
    ) {
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).clickable(onClick = onDone),
            shape = RoundedCornerShape(18.dp), color = Color(0xDDF8F5EF), border = BorderStroke(1.dp, GlassBorder),
        ) {
            Text("完成", Modifier.padding(horizontal = 17.dp, vertical = 8.dp), color = DesktopInk, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RootDeleteTarget(editor: DesktopEditor, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(start = 16.dp, top = 10.dp).size(44.dp).zIndex(210f)
            .onGloballyPositioned { editor.deleteBounds = it.boundsInRoot() },
        shape = CircleShape,
        color = if (editor.overDelete) Color(0xE8A96F79) else Color(0xDDF8F5EF),
        border = BorderStroke(1.dp, if (editor.overDelete) Color.White.copy(alpha = 0.85f) else GlassBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("删除", color = if (editor.overDelete) Color.White else DesktopInk, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomEditToolsRegion(
    activeSheet: DesktopEditSheet?,
    onSheet: (DesktopEditSheet?) -> Unit,
    editor: DesktopEditor,
) {
    Box(
        Modifier.fillMaxWidth().height(66.dp)
            .onGloballyPositioned { editor.editToolsBounds = it.boundsInRoot() },
    ) {
        if (activeSheet == null) Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            EditTool("✦", "外观") { onSheet(DesktopEditSheet.Appearance) }
            EditTool("◉", "效果") { onSheet(DesktopEditSheet.Effect) }
            EditTool("▣", "小部件") { onSheet(DesktopEditSheet.Widgets) }
            EditTool("◫", "常用") { onSheet(DesktopEditSheet.Dock) }
            EditTool("⌂", "桌面") { onSheet(DesktopEditSheet.Desktop) }
        }
    }
}

@Composable
private fun DesktopRegionDebugOverlay(editor: DesktopEditor) {
    Box(Modifier.fillMaxSize().zIndex(100f)) {
        DebugRegion(editor.topRegionBounds, "TOP EDIT", Color(0xFFE85D75), 3.dp)
        DebugRegion(editor.placeableBounds, "PLACEABLE", Color(0xFF00A878), 3.dp)
        DebugRegion(editor.editToolsBounds, "EDIT TOOLS", Color(0xFF7B61FF), 3.dp)
        DebugRegion(editor.dockRegionBounds, "DOCK", Color(0xFFFF9500), 3.dp)
        editor.itemBounds.forEach { (id, bounds) ->
            DebugRegion(bounds, id.substringAfterLast(':'), Color(0xFFFFD60A), 1.dp, compact = true)
        }
    }
}

@Composable
private fun DesktopItemBoundsDebugOverlay(editor: DesktopEditor) {
    Box(Modifier.fillMaxSize().zIndex(100f)) {
        editor.itemBounds.forEach { (id, logical) ->
            DebugRegion(logical, "L:${id.substringAfterLast(':')}", Color(0xFFFF3B30), 2.dp, compact = true)
            DebugRegion(editor.visualBounds[id], "V", Color(0xFF00B8D9), 1.dp, compact = true)
        }
    }
}

@Composable
private fun DebugRegion(rect: Rect?, label: String, color: Color, stroke: androidx.compose.ui.unit.Dp, compact: Boolean = false) {
    if (rect == null || rect.width <= 0f || rect.height <= 0f) return
    val density = LocalDensity.current
    Box(
        Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .border(stroke, color)
            .background(color.copy(alpha = if (compact) 0.05f else 0.09f)),
    ) {
        Text(
            label,
            Modifier.align(Alignment.TopStart).background(color.copy(alpha = 0.88f)).padding(horizontal = 3.dp, vertical = 1.dp),
            color = Color.White,
            fontSize = if (compact) 6.sp else 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EditModeOverlay(
    overDelete: Boolean,
    gridMode: DesktopGridMode,
    onGridMode: (DesktopGridMode) -> Unit,
    activeSheet: DesktopEditSheet?,
    onSheet: (DesktopEditSheet?) -> Unit,
    onWallpaper: (String) -> Unit,
    onEffect: (String) -> Unit,
    onDone: () -> Unit,
    onDeleteBounds: (Rect) -> Unit,
) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (activeSheet == null) Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 16.dp).size(44.dp)
                .onGloballyPositioned { onDeleteBounds(it.boundsInRoot()) },
            shape = CircleShape,
            color = if (overDelete) Color(0xE8A96F79) else Color(0xDDF8F5EF),
            border = BorderStroke(1.dp, if (overDelete) Color.White.copy(alpha = 0.85f) else GlassBorder),
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("删除", color = if (overDelete) Color.White else DesktopInk, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp).clickable(onClick = onDone),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xDDF8F5EF),
            border = BorderStroke(1.dp, GlassBorder),
            shadowElevation = 5.dp,
        ) {
            Text("完成", modifier = Modifier.padding(horizontal = 17.dp, vertical = 8.dp), color = DesktopInk, fontSize = 12.sp)
        }
        if (activeSheet == null) Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 88.dp).fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xDDF8F5EF),
            border = BorderStroke(1.dp, GlassBorder),
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                EditTool("✦", "外观") { onSheet(DesktopEditSheet.Appearance) }
                EditTool("◉", "效果") { onSheet(DesktopEditSheet.Effect) }
                EditTool("▣", "小部件") { onSheet(DesktopEditSheet.Widgets) }
                EditTool("◫", "常用") { onSheet(DesktopEditSheet.Dock) }
                EditTool("⌂", "桌面") { onSheet(DesktopEditSheet.Desktop) }
            }
        }

        if (activeSheet != null) {
            DesktopEditSheetPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                sheet = activeSheet,
                gridMode = gridMode,
                onGridMode = onGridMode,
                onWallpaper = onWallpaper,
                onEffect = onEffect,
                onClose = { onSheet(null) },
            )
        }
    }
}

@Composable
private fun EditTool(glyph: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(58.dp).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, color = DesktopInk, fontSize = 15.sp)
        Text(label, color = DesktopMuted, fontSize = 8.sp)
    }
}

@Composable
private fun DesktopEditSheetPanel(
    modifier: Modifier = Modifier,
    sheet: DesktopEditSheet,
    gridMode: DesktopGridMode,
    onGridMode: (DesktopGridMode) -> Unit,
    customColumns: Int = 6,
    customRows: Int = 6,
    onCustomGrid: (Int, Int) -> Unit = { _, _ -> },
    onWallpaper: (String) -> Unit,
    onEffect: (String) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().heightIn(max = 560.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
        color = Color(0xEEF8F5EF),
        border = BorderStroke(1.dp, GlassBorder),
        shadowElevation = 14.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(sheet.title(), color = DesktopInk, fontFamily = FontFamily.Serif, fontSize = 17.sp)
                Text("×", modifier = Modifier.clickable(onClick = onClose).padding(6.dp), color = DesktopInk, fontSize = 20.sp)
            }
            when (sheet) {
                DesktopEditSheet.Appearance -> AppearanceSheet(onWallpaper)
                DesktopEditSheet.Effect -> ChoiceSection("壁纸效果", listOf("清晰" to "clear", "模糊" to "blur", "压暗" to "dim", "柔雾" to "soft"), onEffect)
                DesktopEditSheet.Widgets -> WidgetsSheet()
                DesktopEditSheet.Dock -> DockSheet()
                DesktopEditSheet.Desktop -> DesktopSheet(gridMode, onGridMode, customColumns, customRows, onCustomGrid)
            }
        }
    }
}

private fun DesktopEditSheet.title(): String = when (this) {
    DesktopEditSheet.Appearance -> "外观与个性化"
    DesktopEditSheet.Effect -> "壁纸效果"
    DesktopEditSheet.Widgets -> "小部件"
    DesktopEditSheet.Dock -> "常用区域"
    DesktopEditSheet.Desktop -> "桌面设置"
}

@Composable
private fun AppearanceSheet(onWallpaper: (String) -> Unit) {
    ChoiceSection("字体 · 全局", listOf("宋体" to "serif", "无衬线" to "sans", "小薇" to "xiaowei", "手写" to "mashan", "系统" to "system", "Gama Hand*" to "gama")) {}
    ChoiceSection("壁纸 · 全局", listOf("绿荫" to "house", "暖纸" to "warm", "粉雾" to "rose", "夜空" to "night", "＋ 相册" to "upload"), onWallpaper)
    ChoiceSection("图标 · 全局", listOf("原始" to "original", "细线" to "line", "柔玻璃" to "soft", "墨线" to "ink")) {}
    Text("雾面效果    ━━━━━━━   10px", color = DesktopMuted, fontSize = 10.sp)
    Text("这是全局主题层：母版、聊天、世界书、朋友圈及后续迁入页面优先继承这里。", color = DesktopMuted, fontSize = 9.sp, lineHeight = 14.sp)
}

@Composable
private fun ChoiceSection(title: String, items: List<Pair<String, String>>, onChoice: (String) -> Unit) {
    Text(title, color = DesktopMuted, fontSize = 9.sp, letterSpacing = 0.7.sp)
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { (label, value) ->
                Surface(
                    modifier = Modifier.weight(1f).height(42.dp).clickable { onChoice(value) },
                    shape = RoundedCornerShape(15.dp), color = Color.White.copy(alpha = 0.42f), border = BorderStroke(1.dp, GlassBorder),
                ) { Box(contentAlignment = Alignment.Center) { Text(label, color = DesktopInk, fontSize = 10.sp) } }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun WidgetsSheet() {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.42f), border = BorderStroke(1.dp, GlassBorder)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("⚗  组件工坊\n    图片 · 文字 · 功能组件 · 自定义尺寸", color = DesktopInk, fontSize = 10.sp, lineHeight = 15.sp)
            Text("›", color = DesktopInk)
        }
    }
    ChoiceSection("组件材质", listOf("毛玻璃" to "frost", "雾面" to "matte", "透明" to "clear")) {}
    listOf("纪念日", "时间", "Our Space", "My Diary", "瓶中生态", "音乐", "健康", "朋友圈", "观星室", "日历", "陆宅", "潮汐").forEach {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(it, color = DesktopInk, fontSize = 10.sp); Text("●", color = Color(0xFF718069), fontSize = 12.sp)
        }
    }
}

@Composable
private fun DockSheet() {
    Text("最多放 4 个。勾选会把图标移动到常用区域；取消会把它放回当前桌面。", color = DesktopMuted, fontSize = 9.sp)
    listOf("聊天", "朋友圈", "群聊", "设置", "收藏", "日历", "天气", "画像").forEachIndexed { index, label ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = DesktopInk, fontSize = 11.sp); Text(if (index < 4) "●" else "○", color = Color(0xFF718069), fontSize = 13.sp)
        }
    }
}

@Composable
private fun DesktopSheet(gridMode: DesktopGridMode, onGridMode: (DesktopGridMode) -> Unit, customColumns: Int, customRows: Int, onCustomGrid: (Int, Int) -> Unit) {
    Text("布局密度", color = DesktopMuted, fontSize = 9.sp)
    DesktopGridMode.entries.chunked(3).forEach { modes ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            modes.forEach { mode ->
                Surface(
                    modifier = Modifier.weight(1f).height(42.dp).clickable { onGridMode(mode) },
                    shape = RoundedCornerShape(15.dp), color = if (mode == gridMode) Color(0xB067755C) else Color.White.copy(alpha = 0.42f), border = BorderStroke(1.dp, GlassBorder),
                ) { Box(contentAlignment = Alignment.Center) { Text(if (mode == DesktopGridMode.Custom) "自定义" else mode.storageValue.replace('x', '×'), color = if (mode == gridMode) Color.White else DesktopInk, fontSize = 10.sp) } }
            }
            repeat(3 - modes.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (gridMode == DesktopGridMode.Custom) {
        Text("列 $customColumns    行 $customRows", color = DesktopInk, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("列−" to { onCustomGrid(customColumns - 1, customRows) }, "列＋" to { onCustomGrid(customColumns + 1, customRows) }, "行−" to { onCustomGrid(customColumns, customRows - 1) }, "行＋" to { onCustomGrid(customColumns, customRows + 1) }).forEach { (label, action) ->
                Surface(Modifier.weight(1f).height(36.dp).clickable(onClick = action), RoundedCornerShape(13.dp), Color.White.copy(alpha = .42f), border = BorderStroke(1.dp, GlassBorder)) { Box(contentAlignment = Alignment.Center) { Text(label, color = DesktopInk, fontSize = 9.sp) } }
            }
        }
    }
    Text("画布范围固定，只改变里面的行列密度；切换后仍留在同一个小手机桌面。", color = DesktopMuted, fontSize = 9.sp)
    ChoiceSection("图标尺寸", listOf("小图标" to "small", "标准" to "medium", "大图标" to "large")) {}
    ChoiceSection("整理", listOf("恢复隐藏" to "restore", "重置布局" to "reset")) {}
}

@Composable
private fun DesktopGridGuide(mode: DesktopGridMode, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(mode.rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(mode.columns) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().background(
                            Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp),
                        ).then(Modifier),
                    )
                }
            }
        }
    }
}

private fun encodeGridPlacements(placements: Map<String, GridPlacement>): String = placements.entries.joinToString("|") { (id, p) ->
    "$id:${p.page}:${p.row}:${p.column}:${p.rowSpan}:${p.columnSpan}"
}

private fun decodeGridPlacements(raw: String?): Map<String, GridPlacement> = raw.orEmpty()
    .split('|')
    .mapNotNull { entry ->
        val parts = entry.split(':')
        if (parts.size < 6) return@mapNotNull null
        val values = parts.takeLast(5).map { it.toIntOrNull() ?: return@mapNotNull null }
        parts.dropLast(5).joinToString(":") to GridPlacement(values[0], values[1], values[2], values[3], values[4])
    }
    .toMap()
