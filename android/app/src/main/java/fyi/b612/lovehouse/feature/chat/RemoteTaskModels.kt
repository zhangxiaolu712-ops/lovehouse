package fyi.b612.lovehouse.feature.chat

enum class AgentRuntime(val label: String) {
    Vps("VPS"),
    Local("Local"),
}

enum class RemoteTaskStatus(val label: String) {
    Queued("排队中"),
    Running("进行中"),
    WaitingApproval("等待批准"),
    RequiresLocalUser("需要本人操作"),
    Completed("已完成"),
    Failed("失败"),
}

enum class WorkflowEventStatus {
    Completed,
    Current,
    Pending,
    WaitingApproval,
    RequiresLocalUser,
    Failed,
}

enum class ApprovalRisk(val label: String) {
    Low("低"),
    Medium("中"),
    High("高"),
}

data class ApprovalRequest(
    val request: String,
    val impact: String,
    val risk: ApprovalRisk,
)

data class WorkflowEvent(
    val id: String,
    val stage: String,
    val action: String,
    val scope: String,
    val summary: String,
    val status: WorkflowEventStatus,
    val timestamp: String,
    val approval: ApprovalRequest? = null,
)

data class RemoteAgentTask(
    val taskId: String,
    val runtime: AgentRuntime,
    val title: String,
    val summary: String,
    val status: RemoteTaskStatus,
    val latestMilestone: String,
    val updatedAt: String,
    val workflow: List<WorkflowEvent>,
    val finalResult: String? = null,
    val failureReason: String? = null,
)

fun RemoteAgentTask.applyMockApproval(eventId: String, approved: Boolean): RemoteAgentTask {
    val target = workflow.firstOrNull { it.id == eventId } ?: return this
    if (target.status != WorkflowEventStatus.WaitingApproval) return this

    return copy(
        status = if (approved) RemoteTaskStatus.Running else RemoteTaskStatus.Failed,
        latestMilestone = if (approved) "审批已通过，等待继续" else "审批已拒绝",
        workflow = workflow.map { event ->
            if (event.id != eventId) {
                event
            } else {
                event.copy(
                    status = if (approved) WorkflowEventStatus.Completed else WorkflowEventStatus.Failed,
                    summary = if (approved) "已批准（本地 Mock），尚未调用任何远程接口。" else "已拒绝（本地 Mock）。",
                )
            }
        },
        failureReason = if (approved) failureReason else "用户拒绝了本次本地 Mock 审批。",
    )
}
