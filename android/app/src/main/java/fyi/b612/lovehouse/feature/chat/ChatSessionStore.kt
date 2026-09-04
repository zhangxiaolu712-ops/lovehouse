package fyi.b612.lovehouse.feature.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatPersona(
    val personaId: String,
    val name: String,
    val avatar: String,
    val memoryLabel: String,
)

data class ChatMember(
    val memberId: String,
    val name: String,
    val avatar: String,
    val status: String,
)

enum class ChatMessageKind { Text, Task, Workflow, ForwardBundle }

data class ForwardedMessage(
    val author: String,
    val body: String,
    val time: String,
)

data class ChatMessageUi(
    val messageId: String,
    val author: String,
    val avatar: String,
    val body: String,
    val time: String,
    val mine: Boolean,
    val kind: ChatMessageKind = ChatMessageKind.Text,
    val thoughtDuration: String? = null,
    val thoughtSummary: String? = null,
    val taskId: String? = null,
    val workflowEventId: String? = null,
    val forwarded: List<ForwardedMessage> = emptyList(),
)

class ChatSessionStore(
    private val codexClient: CodexChatClient = HttpCodexChatClient(),
) {
    val threads = mutableStateListOf<ChatThreadSummary>().apply { addAll(MockChatRepository.mockThreads.filter { it.kind != ChatThreadKind.Archive }) }
    val personas = mutableStateListOf(
        ChatPersona("g", "G老师", "G", "gpt_private"),
        ChatPersona("claude", "Claude", "C", "claude_private"),
        ChatPersona("codex", "Codex", "⌘", "engineering_context"),
        ChatPersona("gemini", "Gemini", "星", "gemini_private"),
    )
    private val messagesByThread = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessageUi>>()
    private val membersByThread = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMember>>()
    private val backgrounds = mutableStateMapOf<String, String>()
    private val tasksById = mutableStateMapOf<String, RemoteAgentTask>().apply { RemoteTaskMocks.scenarios.forEach { put(it.taskId, it) } }

    init {
        messagesByThread["persona-gpt"] = mutableStateListOf(
            message("g1", "G老师", "G", "回来啦。", "20:17", thoughtDuration = "2s", thoughtSummary = "已检查当前窗口的上下文与可展示工具状态。"),
            message("g2", "我", "我", "今天先把 LoveHouse 的聊天界面定下来。", "20:17", mine = true),
            message("g3", "G老师", "G", "窗口只按人格切，模型藏到右上角详情里。", "20:18"),
            message("g4", "我", "我", "好。前台切人格，后台换模型。", "20:18", mine = true),
            message("g5", "G老师", "G", "长消息会在合理的最大宽度内自然换行。头像、姓名和时间都留在气泡外面。", "20:19"),
        )
        messagesByThread["living-room"] = mutableStateListOf(
            message("l1", "GPT", "G", "小客厅已经同步到最新 Chat Shell。", "21:03"),
            message("l2", "Claude", "C", "我负责检查文案和交互边界。", "21:04"),
            ChatMessageUi("l3", "Codex", "⌘", "Chat 页面迁移 · 正在施工", "21:05", false, ChatMessageKind.Task, taskId = "mock-running-001"),
            message("l4", "我", "我", "完成后把结果直接放在正文时间线。", "21:06", mine = true),
        )
        val activeTask = tasksById.getValue("mock-running-001")
        messagesByThread["task-remote-ui"] = mutableStateListOf<ChatMessageUi>().apply {
            activeTask.workflow.forEachIndexed { index, event ->
                add(message("task-log-$index", "Codex", "⌘", "${event.action}\n${event.summary}", event.timestamp, workflowEventId = event.id))
            }
            add(ChatMessageUi("task-card", "Codex", "⌘", activeTask.title, "刚刚", false, ChatMessageKind.Task, taskId = activeTask.taskId))
        }
        messagesByThread["task-claude-copy"] = mutableStateListOf(
            message("c1", "Claude", "C", "文案整理已完成，等待最终回执。", "昨天"),
        )
        // The production Codex window starts empty: assistant text must only
        // come from the real runtime stream, never from a local placeholder.
        messagesByThread["agent-codex"] = mutableStateListOf()
        membersByThread["living-room"] = mutableStateListOf(
            ChatMember("g", "GPT", "G", "在线"),
            ChatMember("claude", "Claude", "C", "在线"),
            ChatMember("codex", "Codex", "⌘", "施工中"),
        )
    }

    fun thread(threadId: String): ChatThreadSummary? = threads.firstOrNull { it.threadId == threadId }
    fun messages(threadId: String) = messagesByThread.getOrPut(threadId) { mutableStateListOf() }
    fun members(threadId: String) = membersByThread.getOrPut(threadId) { mutableStateListOf() }
    fun background(threadId: String): String = backgrounds[threadId] ?: "green"
    fun task(taskId: String): RemoteAgentTask? = tasksById[taskId]
    fun setBackground(threadId: String, key: String) { backgrounds[threadId] = key }

    fun sendMessage(threadId: String, body: String) {
        if (body.isBlank()) return
        messages(threadId) += message("sent-${UUID.randomUUID()}", "我", "我", body, "刚刚", mine = true)
        updateThread(threadId) { it.copy(preview = body, updatedAt = "刚刚") }
    }

    suspend fun sendCodexMessage(threadId: String, body: String, onText: (String) -> Unit): Result<CodexChatResult> {
        if (body.isBlank()) return Result.failure(CodexChatException("消息不能为空"))
        messages(threadId) += message("sent-${UUID.randomUUID()}", "我", "我", body, "刚刚", mine = true)
        updateThread(threadId) { it.copy(preview = body, updatedAt = "刚刚") }
        val assistantId = "codex-${UUID.randomUUID()}"
        return runCatching {
            withContext(Dispatchers.IO) {
                codexClient.streamMessage(stableCodexThreadId(), body) { fullText ->
                    messages(threadId).removeAll { it.messageId == assistantId }
                    messages(threadId) += message(assistantId, "Codex", "⌘", fullText, "刚刚")
                    onText(fullText)
                }
            }
        }.onFailure {
            messages(threadId).removeAll { message -> message.messageId == assistantId && message.body.isBlank() }
        }
    }

    fun importPersona(name: String): ChatPersona {
        val persona = ChatPersona("import-${UUID.randomUUID()}", name.ifBlank { "新 Persona" }, name.take(1).ifBlank { "新" }, "独立 Memory")
        personas += persona
        return persona
    }

    fun createThread(persona: ChatPersona, temporary: Boolean): ChatThreadSummary {
        val id = "thread-${UUID.randomUUID()}"
        val thread = ChatThreadSummary(
            threadId = id,
            kind = if (temporary) ChatThreadKind.TemporaryTask else ChatThreadKind.Direct,
            title = if (temporary) "${persona.name} · 临时窗口" else persona.name,
            preview = "窗口已创建，可以开始聊天。",
            updatedAt = "刚刚",
            presence = ChatPresence.Online,
            speakerLabel = if (temporary) "72h 临时 Thread" else "长期单聊",
            expiresAtLabel = if (temporary) "72小时" else null,
            taskId = if (temporary) "mock-running-001" else null,
            avatarGlyph = persona.avatar,
        )
        threads.add(0, thread)
        messagesByThread[id] = mutableStateListOf(message("welcome-$id", persona.name, persona.avatar, "窗口已经准备好了。", "刚刚"))
        return thread
    }

    fun addMember(threadId: String, persona: ChatPersona) {
        if (members(threadId).none { it.memberId == persona.personaId }) {
            members(threadId) += ChatMember(persona.personaId, persona.name, persona.avatar, "刚加入")
        }
    }

    fun updateAvatar(threadId: String, avatar: String) = updateThread(threadId) { it.copy(avatarGlyph = avatar) }
    fun retain(threadId: String) = updateThread(threadId) { it.copy(expiresAtLabel = "已保留", preview = "已保留，不参与自动清理。") }
    fun convertToLongTerm(threadId: String) = updateThread(threadId) { it.copy(kind = ChatThreadKind.Direct, expiresAtLabel = null, preview = "已转为长期窗口。") }
    fun archive(threadId: String) = updateThread(threadId) { it.copy(kind = ChatThreadKind.Archive, preview = "已归档") }

    fun advanceTask(taskId: String) {
        val task = tasksById[taskId] ?: return
        val current = task.workflow.indexOfFirst { it.status == WorkflowEventStatus.Current }
        if (current < 0) return
        val next = (current + 1).takeIf { it < task.workflow.size }
        val nextNeedsApproval = next?.let { task.workflow[it].approval != null } == true
        val workflow = task.workflow.mapIndexed { index, event ->
            when (index) {
                current -> event.copy(status = WorkflowEventStatus.Completed, summary = "${event.summary} 已完成。")
                next -> event.copy(status = if (nextNeedsApproval) WorkflowEventStatus.WaitingApproval else WorkflowEventStatus.Current, timestamp = "刚刚")
                else -> event
            }
        }
        tasksById[taskId] = task.copy(
            workflow = workflow,
            status = when { next == null -> RemoteTaskStatus.Completed; nextNeedsApproval -> RemoteTaskStatus.WaitingApproval; else -> RemoteTaskStatus.Running },
            latestMilestone = next?.let { workflow[it].action } ?: "任务完成",
            updatedAt = "刚刚",
        )
        threadForTask(taskId)?.let { threadId ->
            messages(threadId) += message("advance-${UUID.randomUUID()}", "Codex", "⌘", "${task.workflow[current].action} 已完成。${next?.let { "\n继续执行：${workflow[it].action}" }.orEmpty()}", "刚刚", workflowEventId = next?.let { workflow[it].id } ?: workflow[current].id)
        }
    }

    fun decideApproval(taskId: String, eventId: String, approved: Boolean) {
        tasksById[taskId]?.let { task ->
            val event = task.workflow.firstOrNull { it.id == eventId }
            tasksById[taskId] = task.applyMockApproval(eventId, approved)
            threadForTask(taskId)?.let { threadId ->
                messages(threadId) += message(
                    "approval-${UUID.randomUUID()}", "我", "我",
                    "${if (approved) "已批准" else "已拒绝"}：${event?.approval?.request ?: event?.action}\n影响：${event?.approval?.impact ?: event?.scope}\n风险：${event?.approval?.risk?.label ?: "低"}",
                    "刚刚", mine = true, workflowEventId = eventId,
                )
            }
        }
    }

    fun forwardWorkflow(taskId: String, targetThreadId: String) {
        val task = tasksById[taskId] ?: return
        messages(targetThreadId) += ChatMessageUi(
            messageId = "workflow-${UUID.randomUUID()}", author = "我", avatar = "我",
            body = task.title, time = "刚刚", mine = true, kind = ChatMessageKind.Workflow, taskId = taskId,
        )
    }

    fun forward(sourceThreadId: String, messageIds: Set<String>, targetThreadId: String, merged: Boolean) {
        val selected = messages(sourceThreadId).filter { it.messageId in messageIds }
        if (selected.isEmpty()) return
        if (merged || selected.size > 1) {
            messages(targetThreadId) += ChatMessageUi(
                messageId = "forward-${UUID.randomUUID()}", author = "我", avatar = "我",
                body = "聊天记录 · ${selected.size} 条消息", time = "刚刚", mine = true,
                kind = ChatMessageKind.ForwardBundle,
                forwarded = selected.map { ForwardedMessage(it.author, it.body, it.time) },
            )
        } else {
            val source = selected.single()
            messages(targetThreadId) += message("forward-${UUID.randomUUID()}", "我", "我", "转发自 ${source.author}：${source.body}", "刚刚", mine = true)
        }
    }

    private fun updateThread(threadId: String, transform: (ChatThreadSummary) -> ChatThreadSummary) {
        val index = threads.indexOfFirst { it.threadId == threadId }
        if (index >= 0) threads[index] = transform(threads[index])
    }

    private fun threadForTask(taskId: String): String? = threads.firstOrNull { it.taskId == taskId }?.threadId

    private fun message(
        id: String, author: String, avatar: String, body: String, time: String, mine: Boolean = false,
        thoughtDuration: String? = null, thoughtSummary: String? = null, workflowEventId: String? = null,
    ) = ChatMessageUi(id, author, avatar, body, time, mine, thoughtDuration = thoughtDuration, thoughtSummary = thoughtSummary, workflowEventId = workflowEventId)
}
