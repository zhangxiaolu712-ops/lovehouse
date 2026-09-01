package fyi.b612.lovehouse.feature.chat

object RemoteTaskMocks {
    val scenarios: List<RemoteAgentTask> = listOf(
        running(),
        waitingApproval(),
        requiresLocalUser(),
        completed(),
    )

    private fun baseWorkflow(): List<WorkflowEvent> = listOf(
        event("accepted", "准备", "已接单", "任务", "Codex 已接收任务并建立工作清单。", WorkflowEventStatus.Completed, "21:06"),
        event("rules", "准备", "读取工程规则", "LoveHouse", "已确认本轮施工边界。", WorkflowEventStatus.Completed, "21:07"),
        event("worktree", "准备", "确认 worktree", "Android", "已进入最新 main 的干净施工目录。", WorkflowEventStatus.Completed, "21:08"),
        event("inspect", "检查", "检查相关文件", "小客厅", "已找到消息流与任务详情入口。", WorkflowEventStatus.Completed, "21:10"),
    )

    private fun running() = RemoteAgentTask(
        taskId = "mock-running-001",
        runtime = AgentRuntime.Vps,
        title = "恢复 MCP 回执展示",
        summary = "让小客厅能用短摘要展示远程任务进度。",
        status = RemoteTaskStatus.Running,
        latestMilestone = "正在修改 MCP 回执层",
        updatedAt = "刚刚 · 21:18",
        workflow = baseWorkflow() + listOf(
            event("editing", "实现", "修改 MCP 回执层", "Android UI", "正在整理统一的任务事件展示。", WorkflowEventStatus.Current, "21:18"),
            event("targeted-test", "验证", "运行针对性测试", "Android", "等待当前修改完成。", WorkflowEventStatus.Pending, "—"),
            event("full-test", "验证", "运行完整测试", "Android", "等待针对性测试通过。", WorkflowEventStatus.Pending, "—"),
            event("approval", "交付", "等待审批", "变更", "完成后等待用户查看。", WorkflowEventStatus.Pending, "—"),
            event("done", "完成", "完成", "任务", "尚未完成。", WorkflowEventStatus.Pending, "—"),
        ),
    )

    private fun waitingApproval() = RemoteAgentTask(
        taskId = "mock-approval-002",
        runtime = AgentRuntime.Vps,
        title = "更新远程任务展示文案",
        summary = "已准备好低风险 UI 文案调整，等待批准。",
        status = RemoteTaskStatus.WaitingApproval,
        latestMilestone = "需要批准低风险改动",
        updatedAt = "2 分钟前 · 21:16",
        workflow = baseWorkflow() + listOf(
            event(
                id = "approve-copy",
                stage = "审批",
                action = "需要批准",
                scope = "小客厅 UI",
                summary = "准备应用任务状态文案与颜色调整。",
                status = WorkflowEventStatus.WaitingApproval,
                timestamp = "21:16",
                approval = ApprovalRequest(
                    request = "应用任务状态文案与颜色调整",
                    impact = "仅影响本地 Android Mock 页面",
                    risk = ApprovalRisk.Low,
                ),
            ),
            event("done", "完成", "完成", "任务", "等待审批后继续。", WorkflowEventStatus.Pending, "—"),
        ),
    )

    private fun requiresLocalUser() = RemoteAgentTask(
        taskId = "mock-local-user-003",
        runtime = AgentRuntime.Local,
        title = "真机安装验收",
        summary = "构建已完成，需要本人在电脑旁确认设备授权。",
        status = RemoteTaskStatus.RequiresLocalUser,
        latestMilestone = "请到电脑旁确认 USB 调试",
        updatedAt = "5 分钟前 · 21:13",
        workflow = baseWorkflow() + listOf(
            event("build", "验证", "完成 Android 构建", "本机", "Debug 安装包已准备好。", WorkflowEventStatus.Completed, "21:12"),
            event("usb", "本人操作", "请到电脑旁确认 USB 调试", "连接的手机", "该操作必须由本人在设备上确认，不能远程批准。", WorkflowEventStatus.RequiresLocalUser, "21:13"),
            event("install", "验证", "安装到真机", "Android", "等待 USB 调试授权。", WorkflowEventStatus.Pending, "—"),
        ),
    )

    private fun completed() = RemoteAgentTask(
        taskId = "mock-completed-004",
        runtime = AgentRuntime.Vps,
        title = "只读检查 DevSpace",
        summary = "远程链路已完成只读 smoke test。",
        status = RemoteTaskStatus.Completed,
        latestMilestone = "只读验收完成",
        updatedAt = "昨天 · 23:42",
        workflow = baseWorkflow() + listOf(
            event("smoke", "验证", "运行只读 smoke test", "DevSpace", "连接与读取均正常。", WorkflowEventStatus.Completed, "23:40"),
            event("done", "完成", "完成", "任务", "检查结果已整理。", WorkflowEventStatus.Completed, "23:42"),
        ),
        finalResult = "DevSpace 可访问，任务链路正常；本次未修改生产数据。",
    )

    private fun event(
        id: String,
        stage: String,
        action: String,
        scope: String,
        summary: String,
        status: WorkflowEventStatus,
        timestamp: String,
        approval: ApprovalRequest? = null,
    ) = WorkflowEvent(id, stage, action, scope, summary, status, timestamp, approval)
}
