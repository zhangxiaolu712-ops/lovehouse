package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.AgentRuntime
import fyi.b612.lovehouse.feature.chat.RemoteTaskMocks
import fyi.b612.lovehouse.feature.chat.RemoteTaskStatus
import fyi.b612.lovehouse.feature.chat.WorkflowEventStatus
import fyi.b612.lovehouse.feature.chat.applyMockApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTaskContractTest {
    @Test
    fun `four mock scenarios cover the phase A acceptance states`() {
        val tasks = RemoteTaskMocks.scenarios

        assertEquals(4, tasks.size)
        assertEquals(
            setOf(
                RemoteTaskStatus.Running,
                RemoteTaskStatus.WaitingApproval,
                RemoteTaskStatus.RequiresLocalUser,
                RemoteTaskStatus.Completed,
            ),
            tasks.map { it.status }.toSet(),
        )
        assertTrue(tasks.any { it.runtime == AgentRuntime.Vps })
        assertTrue(tasks.any { it.runtime == AgentRuntime.Local })
        assertTrue(tasks.map { it.taskId }.distinct().size == tasks.size)
    }

    @Test
    fun `workflow events expose the shared runtime contract`() {
        RemoteTaskMocks.scenarios.flatMap { it.workflow }.forEach { event ->
            assertTrue(event.id.isNotBlank())
            assertTrue(event.stage.isNotBlank())
            assertTrue(event.action.isNotBlank())
            assertTrue(event.scope.isNotBlank())
            assertTrue(event.summary.isNotBlank())
            assertTrue(event.timestamp.isNotBlank())
        }
    }

    @Test
    fun `low risk approval only changes local mock state`() {
        val waiting = RemoteTaskMocks.scenarios.first { it.status == RemoteTaskStatus.WaitingApproval }
        val approval = waiting.workflow.first { it.status == WorkflowEventStatus.WaitingApproval }
        assertNotNull(approval.approval)

        val approved = waiting.applyMockApproval(approval.id, approved = true)

        assertEquals(RemoteTaskStatus.Running, approved.status)
        assertEquals(
            WorkflowEventStatus.Completed,
            approved.workflow.first { it.id == approval.id }.status,
        )
    }

    @Test
    fun `requires local user never exposes an approval request`() {
        val localUser = RemoteTaskMocks.scenarios.first { it.status == RemoteTaskStatus.RequiresLocalUser }
        val localEvents = localUser.workflow.filter { it.status == WorkflowEventStatus.RequiresLocalUser }

        assertTrue(localEvents.isNotEmpty())
        assertTrue(localEvents.all { it.approval == null })
    }
}
