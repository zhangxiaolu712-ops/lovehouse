package fyi.b612.lovehouse.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView

private val ChatInk = Color(0xFF334047)
private val ChatMuted = Color(0xFF829095)

private enum class ListMode(val title: String) {
    All("全部窗口"), LongTerm("长期窗口"), LivingRoom("小客厅"), Temporary("临时任务"), Archived("已归档"),
}

@Composable
fun ChatListScreen(store: ChatSessionStore, onOpenThread: (ChatThreadSummary) -> Unit, modifier: Modifier = Modifier) {
    var showMenu by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ListMode.All) }
    BackHandler(showCreate || searching || mode != ListMode.All) {
        when { showCreate -> showCreate = false; searching -> { searching = false; query = "" }; else -> mode = ListMode.All }
    }
    val visible = store.threads.filter {
        when (mode) {
            ListMode.All -> it.kind != ChatThreadKind.Archive
            ListMode.LongTerm -> it.kind == ChatThreadKind.Direct || it.kind == ChatThreadKind.Agent
            ListMode.LivingRoom -> it.kind == ChatThreadKind.LivingRoom
            ListMode.Temporary -> it.kind == ChatThreadKind.TemporaryTask
            ListMode.Archived -> it.kind == ChatThreadKind.Archive
        }
    }.filter { query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) }

    Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 50.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (mode != ListMode.All) item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTouch(LoveHouseIcon.Back, "返回全部窗口") { mode = ListMode.All }
                    Text(mode.title, Modifier.padding(start = 5.dp), color = ChatInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            val pinned = visible.filter { it.pinned }
            val regular = visible.filterNot { it.pinned }
            if (pinned.isNotEmpty()) {
                item { Text("置顶", Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), color = ChatMuted, fontSize = 8.sp) }
                items(pinned, key = { it.threadId }) { ThreadRow(it, onOpenThread) }
            }
            items(regular, key = { it.threadId }) { ThreadRow(it, onOpenThread) }
            if (visible.isEmpty()) item { EmptyRow("这里还没有窗口") }
        }

        Box(Modifier.fillMaxWidth().height(48.dp).align(Alignment.TopCenter).padding(start = 14.dp, end = 14.dp), contentAlignment = Alignment.Center) {
            if (searching) {
                Surface(Modifier.fillMaxWidth().padding(end = 84.dp).height(38.dp), RoundedCornerShape(14.dp), LoveHouseGlass.StrongBackground, border = BorderStroke(1.dp, LoveHouseGlass.StrongBorder)) {
                    BasicTextField(query, { query = it }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), singleLine = true, textStyle = TextStyle(ChatInk, fontSize = 11.sp), decorationBox = { inner -> Box { if (query.isBlank()) Text("搜索聊天窗口", color = ChatMuted, fontSize = 11.sp); inner() } })
                }
            } else Text("聊天", color = ChatInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconTouch(if (searching) LoveHouseIcon.Close else LoveHouseIcon.Search, if (searching) "关闭搜索" else "搜索聊天") { searching = !searching; if (!searching) query = "" }
                Box {
                    IconTouch(LoveHouseIcon.Plus, "Chat 菜单") { showMenu = true }
            DropdownMenu(showMenu, { showMenu = false }, Modifier.widthIn(min = 142.dp, max = 170.dp)) {
                MenuRow(LoveHouseIcon.Plus, "新增窗口") { showMenu = false; showCreate = true }
                MenuRow(LoveHouseIcon.Chat, "长期窗口") { showMenu = false; mode = ListMode.LongTerm }
                MenuRow(LoveHouseIcon.Home, "小客厅") { showMenu = false; mode = ListMode.LivingRoom }
                MenuRow(LoveHouseIcon.Clock, "临时任务") { showMenu = false; mode = ListMode.Temporary }
                MenuRow(LoveHouseIcon.File, "已归档") { showMenu = false; mode = ListMode.Archived }
            }
                }
            }
        }
        if (showCreate) CreateWindowPanel(store, { showCreate = false }) { thread -> showCreate = false; onOpenThread(thread) }
    }
}

@Composable private fun ThreadRow(thread: ChatThreadSummary, onOpen: (ChatThreadSummary) -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable { onOpen(thread) }, RoundedCornerShape(15.dp), LoveHouseGlass.Background, border = BorderStroke(1.dp, LoveHouseGlass.Border)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            ThreadAvatar(thread)
            Column(Modifier.weight(1f).padding(start = 9.dp, end = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(thread.title, Modifier.weight(1f), color = ChatInk, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(thread.preview, Modifier.padding(top = 2.dp), color = ChatMuted, fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.width(74.dp).height(38.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    if (thread.pinned) LoveHouseIconView(LoveHouseIcon.Star, "置顶", Modifier.size(11.dp), ChatMuted)
                    Text(thread.updatedAt, Modifier.padding(start = 4.dp), color = ChatMuted, fontSize = 7.5.sp, maxLines = 1)
                }
                when {
                    thread.unreadCount > 0 -> Text(thread.unreadCount.toString(), Modifier.background(Color(0xFF718A90), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 7.sp)
                    thread.expiresAtLabel != null -> Text(thread.expiresAtLabel, color = Color(0xFF927D45), fontSize = 7.sp)
                }
            }
        }
    }
}

@Composable private fun ThreadAvatar(thread: ChatThreadSummary) {
    val colors = when (thread.kind) {
        ChatThreadKind.LivingRoom -> listOf(Color(0xFFFFF7F2), Color(0xFFE7D8CE))
        ChatThreadKind.TemporaryTask -> listOf(Color(0xFFFFF9DF), Color(0xFFEADFA8))
        else -> listOf(Color.White, Color(0xFFDBECEE))
    }
    Box(Modifier.size(38.dp).background(Brush.linearGradient(colors), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(thread.avatarGlyph ?: thread.title.take(1), color = ChatMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable private fun CreateWindowPanel(store: ChatSessionStore, onCancel: () -> Unit, onCreated: (ChatThreadSummary) -> Unit) {
    var selected by remember { mutableStateOf<ChatPersona?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importedName by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0x55293232)).clickable(onClick = onCancel), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().clickable(enabled = false) {}, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), LoveHouseGlass.StrongBackground, border = BorderStroke(1.dp, LoveHouseGlass.StrongBorder)) {
            Column(Modifier.navigationBarsPadding().padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected == null) "选择或导入 Persona" else "创建 Thread", Modifier.weight(1f), color = ChatInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    IconTouch(LoveHouseIcon.Close, "取消", onCancel)
                }
                if (selected == null) {
                    store.personas.forEach { persona -> ActionRow(LoveHouseIcon.Contact, persona.name, persona.memoryLabel) { selected = persona } }
                    if (!importing) ActionRow(LoveHouseIcon.Plus, "导入 Persona", "建立独立人格与专属 Memory") { importing = true }
                    else {
                        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), RoundedCornerShape(14.dp), Color.White.copy(alpha = .38f)) {
                            BasicTextField(importedName, { importedName = it }, Modifier.padding(12.dp), textStyle = TextStyle(ChatInk, fontSize = 11.sp), decorationBox = { inner -> Box { if (importedName.isBlank()) Text("Persona 名称", color = ChatMuted, fontSize = 11.sp); inner() } })
                        }
                        Text("确认导入", Modifier.align(Alignment.End).clickable { selected = store.importPersona(importedName) }.padding(12.dp), color = ChatInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("${selected!!.name} · ${selected!!.memoryLabel}", color = ChatMuted, fontSize = 9.sp)
                    ActionRow(LoveHouseIcon.Chat, "长期窗口", "创建稳定 Thread") { onCreated(store.createThread(selected!!, false)) }
                    ActionRow(LoveHouseIcon.Clock, "临时窗口", "创建默认 72h Thread") { onCreated(store.createThread(selected!!, true)) }
                    Text("返回选择 Persona", Modifier.clickable { selected = null }.padding(vertical = 10.dp), color = ChatMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable private fun ActionRow(icon: LoveHouseIcon, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LoveHouseIconView(icon, null, Modifier.size(18.dp), ChatMuted)
        Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(title, color = ChatInk, fontSize = 11.sp); Text(subtitle, color = ChatMuted, fontSize = 8.sp) }
        LoveHouseIconView(LoveHouseIcon.Forward, null, Modifier.size(14.dp), ChatMuted)
    }
}

@Composable private fun IconTouch(icon: LoveHouseIcon, description: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { LoveHouseIconView(icon, description, Modifier.size(17.dp), ChatInk) }
}

@Composable private fun MenuRow(icon: LoveHouseIcon, title: String, onClick: () -> Unit) {
    DropdownMenuItem(leadingIcon = { LoveHouseIconView(icon, null, Modifier.size(16.dp), ChatMuted) }, text = { Text(title, color = ChatInk, fontSize = 10.sp) }, onClick = onClick)
}

@Composable private fun EmptyRow(text: String) { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { Text(text, color = ChatMuted, fontSize = 10.sp) } }
