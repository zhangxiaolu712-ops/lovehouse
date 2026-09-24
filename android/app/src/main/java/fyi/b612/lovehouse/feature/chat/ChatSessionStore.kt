package fyi.b612.lovehouse.feature.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val deliveryStatus: LocalChatDeliveryStatus = LocalChatDeliveryStatus.Sent,
    val createdAtEpochMillis: Long? = null,
    val receivedAtEpochMillis: Long? = null,
    val runtime: String? = null,
    val adapterId: String? = null,
    val deliveryError: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val processEvents: List<ChatProcessEvent> = emptyList(),
)

internal fun mergeProcessEvent(
    events: List<ChatProcessEvent>,
    event: ChatProcessEvent,
): List<ChatProcessEvent> {
    val index = events.indexOfFirst { it.id == event.id }
    return if (index < 0) events + event else events.toMutableList().also { it[index] = event }
}

class ChatSessionStore(
    private val codexClient: CodexChatClient = HttpCodexChatClient(),
    private val messageRepository: LocalChatMessageRepository = NoOpLocalChatMessageRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val conversationPersonas: ConversationPersonaStore = InMemoryConversationPersonaStore(),
    private val personaRuntimeSource: PersonaRuntimeSource = NoOpPersonaRuntimeSource,
    private val claudeWebHistoryImporter: ClaudeWebHistoryImporter? = null,
    initialTasks: List<RemoteAgentTask> = emptyList(),
    initialThreads: List<ChatThreadSummary> = LoveHouseChatCatalog.threads,
) {
    val threads = mutableStateListOf<ChatThreadSummary>().apply {
        val saved = conversationPersonas.savedThreads()
        addAll((initialThreads + saved.filter { candidate -> initialThreads.none { it.threadId == candidate.threadId } }).map(::hydratePersona))
    }
    private val legacyPersonas = listOf(
        ChatPersona("g", "G老师", "G", "gpt_private"),
        ChatPersona("claude", "Claude", "C", "claude_private"),
        ChatPersona("codex", "Codex", "⌘", "engineering_context"),
        ChatPersona("gemini", "Gemini", "星", "gemini_private"),
    )
    val personas = mutableStateListOf<ChatPersona>()
    var personaProfileError by mutableStateOf<String?>(null)
        private set
    private val messagesByThread = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessageUi>>()
    private val membersByThread = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMember>>()
    private val backgrounds = mutableStateMapOf<String, String>()
    private val tasksById = mutableStateMapOf<String, RemoteAgentTask>().apply {
        initialTasks.forEach { put(it.taskId, it) }
    }
    private val personaProfileRefreshMutex = Mutex()
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningExecutionsByThread = mutableStateMapOf<String, Int>()
    private val recoveringExecutionIds = ConcurrentHashMap.newKeySet<String>()

    init {
        messagesByThread["persona-gpt"] = mutableStateListOf()
        messagesByThread["living-room"] = mutableStateListOf()
        // The production Codex window starts empty: assistant text must only
        // come from the real runtime stream, never from a local placeholder.
        val persistedCodexMessages = messageRepository.messages(stableCodexThreadId()).map(::persistedMessageUi)
        messagesByThread["agent-codex"] = mutableStateListOf<ChatMessageUi>().apply { addAll(persistedCodexMessages) }
        persistedCodexMessages.lastOrNull()?.let { latest ->
            updateThread("agent-codex") {
                it.copy(preview = latest.body, updatedAt = latest.time)
            }
        }
        claudeWebHistoryImporter?.importIfPresent()
        val persistedClaudeMessages = messageRepository.messages(ClaudeRuntime.threadId).map(::persistedMessageUi)
        messagesByThread[ClaudeRuntime.threadId] = mutableStateListOf<ChatMessageUi>().apply { addAll(persistedClaudeMessages) }
        persistedClaudeMessages.lastOrNull()?.let { latest ->
            updateThread(ClaudeRuntime.threadId) {
                it.copy(preview = latest.body, updatedAt = latest.time)
            }
        }
        membersByThread["living-room"] = mutableStateListOf()
    }

    fun thread(threadId: String): ChatThreadSummary? = threads.firstOrNull { it.threadId == threadId }
    fun persona(threadId: String): ChatPersona? = thread(threadId)?.personaId?.let { personaId ->
        personas.firstOrNull { it.personaId == personaId }
    }
    fun reanchorPending(threadId: String): Boolean = conversationPersonas.reanchorPending(threadId)
    fun requestReanchor(threadId: String) { conversationPersonas.setReanchorPending(threadId, true) }
    suspend fun personaProfile(threadId: String): PersonaProfile? =
        thread(threadId)?.personaId?.let { personaRuntimeSource.profile(it) }
    suspend fun refreshPersonaProfiles() = personaProfileRefreshMutex.withLock {
        try {
            val profiles = personaRuntimeSource.profiles().toMutableList()
            val knownProfileIds = profiles.mapTo(linkedSetOf(), PersonaProfile::personaId)
            threads.asSequence()
                .mapNotNull { thread ->
                    val legacyId = legacyPersonaIdForThread(thread.threadId) ?: return@mapNotNull null
                    val persistedId = conversationPersonas.personaId(thread.threadId) ?: thread.personaId
                    legacyId.takeIf { it == persistedId && it !in knownProfileIds }
                }
                .distinct()
                .forEach { personaId ->
                    val legacy = legacyPersonas.firstOrNull { it.personaId == personaId } ?: return@forEach
                    val saved = personaRuntimeSource.materialize(
                        PersonaProfile(
                            personaId = legacy.personaId,
                            displayName = legacy.name,
                            avatar = legacy.avatar,
                            instructions = "",
                            background = "",
                            version = 0,
                            providerNativeAnchorPreference = false,
                        ),
                    )
                    profiles += saved
                    knownProfileIds += saved.personaId
                }
            val previous = personas.associateBy(ChatPersona::personaId)
            val authoritative = profiles.distinctBy(PersonaProfile::personaId).map { profile ->
                val fallback = previous[profile.personaId]
                    ?: legacyPersonas.firstOrNull { it.personaId == profile.personaId }
                ChatPersona(
                    profile.personaId,
                    profile.displayName,
                    profile.avatar ?: fallback?.avatar ?: profile.displayName.take(1),
                    fallback?.memoryLabel.orEmpty(),
                )
            }
            personas.clear()
            personas.addAll(authoritative)
            personaProfileError = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            personaProfileError = error.personaRuntimeMessage()
            throw error
        }
    }
    suspend fun savePersonaProfile(profile: PersonaProfile): PersonaProfile {
        val saved = personaRuntimeSource.save(profile)
        val index = personas.indexOfFirst { it.personaId == saved.personaId }
        if (index >= 0) {
            val current = personas[index]
            personas[index] = current.copy(name = saved.displayName, avatar = saved.avatar ?: current.avatar)
        } else {
            personas += ChatPersona(
                saved.personaId,
                saved.displayName,
                saved.avatar ?: saved.displayName.take(1),
                "",
            )
        }
        return saved
    }
    fun messages(threadId: String) = messagesByThread.getOrPut(threadId) { mutableStateListOf() }
    fun isSending(threadId: String): Boolean = (runningExecutionsByThread[threadId] ?: 0) > 0
    fun members(threadId: String) = membersByThread.getOrPut(threadId) { mutableStateListOf() }
    fun background(threadId: String): String = backgrounds[threadId] ?: "green"
    fun backgroundOverride(threadId: String): String? = backgrounds[threadId]
    fun task(taskId: String): RemoteAgentTask? = tasksById[taskId]
    fun setBackground(threadId: String, key: String) { backgrounds[threadId] = key }
    fun clearBackground(threadId: String) { backgrounds.remove(threadId) }

    fun sendMessage(threadId: String, body: String, attachments: List<ChatAttachment> = emptyList()) {
        if (body.isBlank() && attachments.isEmpty()) return
        val createdAt = now()
        val localMessage = LocalChatMessage(
            localMessageId = "sent-${UUID.randomUUID()}",
            threadId = threadId,
            role = LocalChatRole.User,
            sender = "owner",
            content = body.trim(),
            attachments = attachments,
            createdAtEpochMillis = createdAt,
            status = LocalChatDeliveryStatus.Sent,
        )
        messageRepository.upsert(localMessage)
        messages(threadId) += persistedMessageUi(localMessage)
        val preview = body.trim().ifEmpty { attachments.joinToString(" · ", transform = ChatAttachment::displaySummary) }
        updateThread(threadId) { it.copy(preview = preview, updatedAt = "刚刚") }
    }

    suspend fun sendCodexMessage(
        threadId: String,
        body: String,
        requestedToolIds: Set<String> = emptySet(),
        attachments: List<ChatAttachment> = emptyList(),
        onText: (String) -> Unit,
    ): Result<CodexChatResult> = sendRuntimeMessage(
        localThreadId = threadId,
        runtime = CodexRuntime,
        assistantName = "Codex",
        assistantAvatar = "⌘",
        body = body,
        requestedToolIds = requestedToolIds,
        attachments = attachments,
        onText = onText,
    )

    suspend fun sendClaudeMessage(
        body: String,
        attachments: List<ChatAttachment> = emptyList(),
        requestedToolIds: Set<String> = emptySet(),
        onText: (String) -> Unit,
    ): Result<CodexChatResult> = sendRuntimeMessage(
        localThreadId = ClaudeRuntime.threadId,
        runtime = ClaudeRuntime,
        assistantName = "Claude",
        assistantAvatar = "C",
        body = body,
        requestedToolIds = requestedToolIds,
        attachments = attachments,
        onText = onText,
    )

    fun launchCodexMessage(
        threadId: String,
        body: String,
        requestedToolIds: Set<String> = emptySet(),
        attachments: List<ChatAttachment> = emptyList(),
        onComplete: (Result<CodexChatResult>) -> Unit = {},
    ): String = launchRuntimeMessage(
        localThreadId = threadId,
        runtime = CodexRuntime,
        assistantName = "Codex",
        assistantAvatar = "⌘",
        body = body,
        requestedToolIds = requestedToolIds,
        attachments = attachments,
        onComplete = onComplete,
    )

    fun launchClaudeMessage(
        body: String,
        attachments: List<ChatAttachment> = emptyList(),
        requestedToolIds: Set<String> = emptySet(),
        onComplete: (Result<CodexChatResult>) -> Unit = {},
    ): String = launchRuntimeMessage(
        localThreadId = ClaudeRuntime.threadId,
        runtime = ClaudeRuntime,
        assistantName = "Claude",
        assistantAvatar = "C",
        body = body,
        requestedToolIds = requestedToolIds,
        attachments = attachments,
        onComplete = onComplete,
    )

    private fun launchRuntimeMessage(
        localThreadId: String,
        runtime: ChatRuntimeConfig,
        assistantName: String,
        assistantAvatar: String,
        body: String,
        requestedToolIds: Set<String>,
        attachments: List<ChatAttachment>,
        onComplete: (Result<CodexChatResult>) -> Unit,
    ): String {
        val executionId = UUID.randomUUID().toString()
        runningExecutionsByThread[localThreadId] = (runningExecutionsByThread[localThreadId] ?: 0) + 1
        executionScope.launch {
            val result = try {
                sendRuntimeMessage(
                    localThreadId, runtime, assistantName, assistantAvatar, body,
                    requestedToolIds, attachments, onText = {}, executionId = executionId,
                )
            } finally {
                val remaining = (runningExecutionsByThread[localThreadId] ?: 1) - 1
                if (remaining > 0) runningExecutionsByThread[localThreadId] = remaining
                else runningExecutionsByThread.remove(localThreadId)
            }
            onComplete(result)
        }
        return executionId
    }

    private suspend fun sendRuntimeMessage(
        localThreadId: String,
        runtime: ChatRuntimeConfig,
        assistantName: String,
        assistantAvatar: String,
        body: String,
        requestedToolIds: Set<String>,
        attachments: List<ChatAttachment>,
        onText: (String) -> Unit,
        executionId: String = UUID.randomUUID().toString(),
    ): Result<CodexChatResult> {
        if (body.isBlank() && attachments.isEmpty()) return Result.failure(CodexChatException("消息不能为空"))
        val displayBody = body.trim()
        val previewBody = displayBody.ifEmpty { attachments.joinToString(" · ", transform = ChatAttachment::displaySummary) }
        val canonicalThreadId = runtime.threadId
        val userId = "user:$executionId"
        val createdAt = now()
        var user = LocalChatMessage(
            localMessageId = userId,
            threadId = canonicalThreadId,
            role = LocalChatRole.User,
            sender = "owner",
            content = displayBody,
            attachments = attachments,
            createdAtEpochMillis = createdAt,
            status = LocalChatDeliveryStatus.Sending,
        )
        val assistantId = "assistant:$executionId"
        var userVisible = false
        var remoteStarted = false
        var assistantText = ""
        var execution: LocalChatExecution? = null
        val processEvents = mutableListOf<ChatProcessEvent>()
        fun updateProcess(event: ChatProcessEvent) {
            val updated = mergeProcessEvent(processEvents, event)
            processEvents.clear()
            processEvents.addAll(updated)
            val current = messages(localThreadId).firstOrNull { it.messageId == assistantId }
            if (current != null) {
                replaceMessage(localThreadId, assistantId, current.copy(processEvents = processEvents.toList()))
            } else {
                replaceMessage(
                    localThreadId,
                    assistantId,
                    message(
                        assistantId,
                        assistantName,
                        assistantAvatar,
                        assistantText,
                        "刚刚",
                        deliveryStatus = LocalChatDeliveryStatus.Sending,
                        createdAtEpochMillis = createdAt,
                    ).copy(processEvents = processEvents.toList()),
                )
            }
        }
        return try {
            withContext(Dispatchers.IO) { messageRepository.upsert(user) }
            messages(localThreadId) += persistedMessageUi(user)
            userVisible = true
            updateThread(localThreadId) { it.copy(preview = previewBody, updatedAt = "刚刚") }
            val selectedPersonaId = thread(localThreadId)?.personaId ?: runtime.personaId
            val pending = conversationPersonas.reanchorPending(localThreadId)
            val snapshot = personaRuntimeSource.resolve(selectedPersonaId, requestedToolIds, pending)
            val needsReanchor = pending || (snapshot != null &&
                conversationPersonas.materializedPersonaVersion(localThreadId) != snapshot.personaVersion)
            val turnSnapshot = snapshot?.let {
                PersonaRuntimeSnapshot(it.personaId, it.personaVersion, it.instructions, it.background,
                    it.connectionIds, it.executionTicket, needsReanchor)
            }
            execution = LocalChatExecution(
                executionId = executionId,
                localThreadId = localThreadId,
                provider = runtime.personaId,
                canonicalThreadId = canonicalThreadId,
                userMessageId = userId,
                assistantMessageId = assistantId,
                status = LocalChatExecutionStatus.Running,
                personaVersion = turnSnapshot?.personaVersion,
                reanchorIntent = turnSnapshot?.reanchorIntent == true,
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = createdAt,
            )
            withContext(Dispatchers.IO) { messageRepository.upsertExecution(execution) }
            val result = withContext(Dispatchers.IO) {
                codexClient.streamRecoverableRuntimeMessage(
                    executionId = executionId,
                    config = runtime,
                    message = body,
                    requestedToolIds = requestedToolIds,
                    attachments = attachments,
                    personaRuntime = turnSnapshot,
                    onStarted = {
                        remoteStarted = true
                        user = user.copy(status = LocalChatDeliveryStatus.Sent)
                        messageRepository.upsert(user)
                        replaceMessage(localThreadId, userId, persistedMessageUi(user))
                    },
                    onText = { fullText ->
                        assistantText = fullText
                        replaceMessage(
                            localThreadId,
                            assistantId,
                            message(
                                assistantId,
                                assistantName,
                                assistantAvatar,
                                fullText,
                                "刚刚",
                                deliveryStatus = LocalChatDeliveryStatus.Sending,
                                createdAtEpochMillis = createdAt,
                            ).copy(processEvents = processEvents.toList()),
                        )
                        onText(fullText)
                    },
                    onProcess = ::updateProcess,
                )
            }
            val receivedAt = now()
            user = user.copy(status = LocalChatDeliveryStatus.Sent)
            val assistant = LocalChatMessage(
                localMessageId = assistantId,
                threadId = canonicalThreadId,
                role = LocalChatRole.Assistant,
                sender = runtime.personaId,
                content = result.text,
                createdAtEpochMillis = createdAt,
                receivedAtEpochMillis = receivedAt,
                status = LocalChatDeliveryStatus.Sent,
                runtime = result.evidence.runtime.takeIf(String::isNotBlank),
                adapterId = result.evidence.adapterId?.takeIf(String::isNotBlank),
            )
            withContext(Dispatchers.IO) { messageRepository.upsert(listOf(user, assistant)) }
            withContext(Dispatchers.IO) {
                messageRepository.upsertExecution(execution.copy(
                    status = LocalChatExecutionStatus.Completed,
                    updatedAtEpochMillis = receivedAt,
                ))
            }
            replaceMessage(localThreadId, userId, persistedMessageUi(user))
            replaceMessage(localThreadId, assistantId, persistedMessageUi(assistant).copy(processEvents = processEvents.toList()))
            if (turnSnapshot != null) {
                conversationPersonas.setMaterializedPersonaVersion(localThreadId, turnSnapshot.personaVersion)
                if (turnSnapshot.reanchorIntent) conversationPersonas.setReanchorPending(localThreadId, false)
            }
            Result.success(result)
        } catch (error: Throwable) {
            if (codexClient.supportsExecutionRecovery && execution != null) {
                val recovery = runCatching { withContext(Dispatchers.IO) { codexClient.chatExecution(executionId) } }
                val recovered = recovery.getOrNull()
                when (recovered?.status) {
                    ChatExecutionRemoteStatus.Completed -> return completeRecoveredExecution(execution, recovered)
                    ChatExecutionRemoteStatus.Failed -> return failRecoveredExecution(execution, recovered.errorMessage ?: error.message)
                    ChatExecutionRemoteStatus.Running -> {
                        launchExecutionRecovery(execution)
                        return Result.failure(CodexChatException("回复仍在后台生成，可稍后重新进入会话查看"))
                    }
                    null -> if ((recovery.exceptionOrNull() as? CodexChatException)?.httpStatus != 404) {
                        if (remoteStarted && user.status != LocalChatDeliveryStatus.Sent) {
                            user = user.copy(status = LocalChatDeliveryStatus.Sent)
                            runCatching { withContext(Dispatchers.IO) { messageRepository.upsert(user) } }
                        }
                        launchExecutionRecovery(execution)
                        return Result.failure(error)
                    }
                }
            }
            if (assistantText.isNotBlank()) {
                val failedAssistant = LocalChatMessage(
                    localMessageId = assistantId,
                    threadId = canonicalThreadId,
                    role = LocalChatRole.Assistant,
                    sender = runtime.personaId,
                    content = assistantText,
                    createdAtEpochMillis = createdAt,
                    receivedAtEpochMillis = now(),
                    status = LocalChatDeliveryStatus.Failed,
                )
                runCatching { withContext(Dispatchers.IO) { messageRepository.upsert(failedAssistant) } }
                replaceMessage(
                    localThreadId,
                    assistantId,
                    persistedMessageUi(failedAssistant).copy(
                        deliveryError = error.message ?: "工具调用失败",
                        processEvents = processEvents.toList(),
                    ),
                )
            } else if (processEvents.isEmpty()) {
                messages(localThreadId).removeAll { message -> message.messageId == assistantId }
            } else {
                val current = messages(localThreadId).firstOrNull { it.messageId == assistantId }
                    ?: message(assistantId, assistantName, assistantAvatar, "", "刚刚")
                replaceMessage(
                    localThreadId,
                    assistantId,
                    current.copy(
                        deliveryStatus = LocalChatDeliveryStatus.Failed,
                        deliveryError = error.message ?: "工具调用失败",
                        processEvents = processEvents.toList(),
                    ),
                )
            }
            if (userVisible) {
                user = user.copy(status = LocalChatDeliveryStatus.Failed)
                runCatching { withContext(Dispatchers.IO) { messageRepository.upsert(user) } }
                replaceMessage(localThreadId, userId, persistedMessageUi(user))
            }
            execution?.let { current ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        messageRepository.upsertExecution(current.copy(
                            status = LocalChatExecutionStatus.Failed,
                            lastError = error.message,
                            updatedAtEpochMillis = now(),
                        ))
                    }
                }
            }
            Result.failure(error)
        }
    }

    fun recoverPendingExecutions() {
        executionScope.launch {
            val pending = runCatching { messageRepository.pendingExecutions() }.getOrDefault(emptyList())
            pending.forEach(::launchExecutionRecovery)
        }
    }

    private fun launchExecutionRecovery(execution: LocalChatExecution) {
        if (!recoveringExecutionIds.add(execution.executionId)) return
        runningExecutionsByThread[execution.localThreadId] =
            (runningExecutionsByThread[execution.localThreadId] ?: 0) + 1
        ensureRunningPlaceholder(execution)
        executionScope.launch {
            try {
                recoverExecutionUntilTerminal(execution)
            } finally {
                recoveringExecutionIds.remove(execution.executionId)
                val remaining = (runningExecutionsByThread[execution.localThreadId] ?: 1) - 1
                if (remaining > 0) runningExecutionsByThread[execution.localThreadId] = remaining
                else runningExecutionsByThread.remove(execution.localThreadId)
            }
        }
    }

    internal suspend fun recoverPendingExecutionsOnce() {
        messageRepository.pendingExecutions().forEach { execution ->
            val result = runCatching { codexClient.chatExecution(execution.executionId) }
            val snapshot = result.getOrNull()
            when (snapshot?.status) {
                ChatExecutionRemoteStatus.Completed -> completeRecoveredExecution(execution, snapshot)
                ChatExecutionRemoteStatus.Failed -> failRecoveredExecution(execution, snapshot.errorMessage)
                ChatExecutionRemoteStatus.Running -> Unit
                null -> if ((result.exceptionOrNull() as? CodexChatException)?.httpStatus == 404) {
                    failRecoveredExecution(execution, "后台执行记录已不可用")
                } else Unit
            }
        }
    }

    private suspend fun recoverExecutionUntilTerminal(execution: LocalChatExecution) {
        if (!codexClient.supportsExecutionRecovery) return
        while (true) {
            val result = runCatching { codexClient.chatExecution(execution.executionId) }
            val snapshot = result.getOrNull()
            when (snapshot?.status) {
                ChatExecutionRemoteStatus.Completed -> {
                    completeRecoveredExecution(execution, snapshot)
                    return
                }
                ChatExecutionRemoteStatus.Failed -> {
                    failRecoveredExecution(execution, snapshot.errorMessage)
                    return
                }
                ChatExecutionRemoteStatus.Running -> delay(2_000)
                null -> {
                    val error = result.exceptionOrNull()
                    if ((error as? CodexChatException)?.httpStatus == 404) {
                        failRecoveredExecution(execution, "后台执行记录已不可用")
                        return
                    }
                    delay(5_000)
                }
            }
        }
    }

    private fun ensureRunningPlaceholder(execution: LocalChatExecution) {
        val existing = messages(execution.localThreadId).firstOrNull { it.messageId == execution.assistantMessageId }
        if (existing == null) {
            val assistantName = if (execution.provider == ClaudeRuntime.personaId) "Claude" else "Codex"
            val assistantAvatar = if (execution.provider == ClaudeRuntime.personaId) "C" else "⌘"
            messages(execution.localThreadId) += message(
                execution.assistantMessageId,
                assistantName,
                assistantAvatar,
                "仍在生成…",
                "刚刚",
                deliveryStatus = LocalChatDeliveryStatus.Sending,
                createdAtEpochMillis = execution.createdAtEpochMillis,
            )
        }
    }

    private suspend fun completeRecoveredExecution(
        execution: LocalChatExecution,
        snapshot: ChatExecutionRemoteSnapshot,
    ): Result<CodexChatResult> {
        val text = snapshot.text?.takeIf(String::isNotBlank)
            ?: return failRecoveredExecution(execution, "后台执行完成但没有返回文字")
        val user = messageRepository.messages(execution.canonicalThreadId)
            .firstOrNull { it.localMessageId == execution.userMessageId }
            ?.copy(status = LocalChatDeliveryStatus.Sent)
        val receivedAt = now()
        val assistant = LocalChatMessage(
            localMessageId = execution.assistantMessageId,
            threadId = execution.canonicalThreadId,
            role = LocalChatRole.Assistant,
            sender = execution.provider,
            content = text,
            createdAtEpochMillis = execution.createdAtEpochMillis,
            receivedAtEpochMillis = receivedAt,
            status = LocalChatDeliveryStatus.Sent,
            runtime = snapshot.runtime,
            adapterId = snapshot.adapterId,
        )
        withContext(Dispatchers.IO) {
            messageRepository.upsert(listOfNotNull(user, assistant))
            messageRepository.upsertExecution(execution.copy(
                status = LocalChatExecutionStatus.Completed,
                lastError = null,
                updatedAtEpochMillis = receivedAt,
            ))
        }
        user?.let { replaceMessage(execution.localThreadId, it.localMessageId, persistedMessageUi(it)) }
        replaceMessage(execution.localThreadId, assistant.localMessageId, persistedMessageUi(assistant))
        updateThread(execution.localThreadId) { it.copy(preview = text, updatedAt = "刚刚") }
        execution.personaVersion?.let { version ->
            conversationPersonas.setMaterializedPersonaVersion(execution.localThreadId, version)
            if (execution.reanchorIntent) conversationPersonas.setReanchorPending(execution.localThreadId, false)
        }
        return Result.success(CodexChatResult(
            text = text,
            evidence = CodexRuntimeEvidence(
                runtime = snapshot.runtime.orEmpty(),
                adapterId = snapshot.adapterId,
                threadId = execution.canonicalThreadId,
            ),
        ))
    }

    private suspend fun failRecoveredExecution(
        execution: LocalChatExecution,
        message: String?,
    ): Result<CodexChatResult> {
        val failure = message ?: "后台执行失败"
        val user = messageRepository.messages(execution.canonicalThreadId)
            .firstOrNull { it.localMessageId == execution.userMessageId }
            ?.copy(status = LocalChatDeliveryStatus.Failed)
        withContext(Dispatchers.IO) {
            user?.let(messageRepository::upsert)
            messageRepository.upsertExecution(execution.copy(
                status = LocalChatExecutionStatus.Failed,
                lastError = failure,
                updatedAtEpochMillis = now(),
            ))
        }
        user?.let { replaceMessage(execution.localThreadId, it.localMessageId, persistedMessageUi(it)) }
        messages(execution.localThreadId).firstOrNull { it.messageId == execution.assistantMessageId }?.let { current ->
            replaceMessage(execution.localThreadId, execution.assistantMessageId, current.copy(
                deliveryStatus = LocalChatDeliveryStatus.Failed,
                deliveryError = failure,
            ))
        }
        return Result.failure(CodexChatException(failure))
    }

    fun createThread(persona: ChatPersona, temporary: Boolean): ChatThreadSummary {
        val id = "thread-${UUID.randomUUID()}"
        val thread = ChatThreadSummary(
            threadId = id,
            kind = if (temporary) ChatThreadKind.TemporaryTask else ChatThreadKind.Direct,
            title = if (temporary) "${persona.name} · 临时窗口" else persona.name,
            preview = "本地窗口 · Runtime 尚未连接",
            updatedAt = "刚刚",
            presence = null,
            speakerLabel = if (temporary) "72h 临时 Thread" else "长期单聊",
            expiresAtLabel = if (temporary) "72小时" else null,
            taskId = null,
            avatarGlyph = persona.avatar,
            personaId = persona.personaId,
        )
        conversationPersonas.setPersonaId(id, persona.personaId)
        conversationPersonas.saveThread(thread)
        threads.add(0, thread)
        messagesByThread[id] = mutableStateListOf()
        return thread
    }

    fun setPersona(threadId: String, persona: ChatPersona) {
        conversationPersonas.setPersonaId(threadId, persona.personaId)
        conversationPersonas.setReanchorPending(threadId, true)
        updateThread(threadId) { current ->
            current.copy(personaId = persona.personaId).also(conversationPersonas::saveThread)
        }
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

    private fun hydratePersona(thread: ChatThreadSummary): ChatThreadSummary {
        val persisted = conversationPersonas.personaId(thread.threadId)
        val resolved = persisted ?: thread.personaId ?: legacyPersonaIdForThread(thread.threadId)
        if (persisted == null && resolved != null) {
            conversationPersonas.setPersonaId(thread.threadId, resolved)
        }
        return thread.copy(personaId = resolved)
    }

    private fun threadForTask(taskId: String): String? = threads.firstOrNull { it.taskId == taskId }?.threadId

    private fun replaceMessage(threadId: String, messageId: String, replacement: ChatMessageUi) {
        val target = messages(threadId)
        val index = target.indexOfFirst { it.messageId == messageId }
        if (index >= 0) target[index] = replacement else target += replacement
    }

    private fun persistedMessageUi(message: LocalChatMessage): ChatMessageUi {
        val mine = message.role == LocalChatRole.User
        val assistantName = if (message.sender == ClaudeRuntime.personaId) "Claude" else "Codex"
        val assistantAvatar = if (message.sender == ClaudeRuntime.personaId) "C" else "⌘"
        return ChatMessageUi(
            messageId = message.localMessageId,
            author = if (mine) "我" else assistantName,
            avatar = if (mine) "我" else assistantAvatar,
            body = message.content,
            time = if (message.localMessageId.startsWith("migration-")) {
                "Web 迁移"
            } else {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.receivedAtEpochMillis ?: message.createdAtEpochMillis))
            },
            mine = mine,
            deliveryStatus = message.status,
            createdAtEpochMillis = message.createdAtEpochMillis,
            receivedAtEpochMillis = message.receivedAtEpochMillis,
            runtime = message.runtime,
            adapterId = message.adapterId,
            attachments = message.attachments,
        )
    }

    private fun message(
        id: String, author: String, avatar: String, body: String, time: String, mine: Boolean = false,
        thoughtDuration: String? = null, thoughtSummary: String? = null, workflowEventId: String? = null,
        deliveryStatus: LocalChatDeliveryStatus = LocalChatDeliveryStatus.Sent,
        createdAtEpochMillis: Long? = null,
    ) = ChatMessageUi(
        id, author, avatar, body, time, mine,
        thoughtDuration = thoughtDuration,
        thoughtSummary = thoughtSummary,
        workflowEventId = workflowEventId,
        deliveryStatus = deliveryStatus,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}
