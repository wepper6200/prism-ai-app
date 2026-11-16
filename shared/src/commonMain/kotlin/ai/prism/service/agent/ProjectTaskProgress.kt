package ai.prism.service.agent

import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.model.MessageStatus

enum class TaskStageStatus { WAITING, RUNNING, DONE, NEEDS_HELP }
/** Derived from actual execution records; persists naturally with the reply's tool history. */
object ProjectTaskProgress {
    fun stages(steps: List<AgentStep>, status: MessageStatus, streaming: Boolean, undone: Boolean = false): List<TaskStageStatus> {
        val writes = steps.filter { it.tool == AgentTool.WRITE_FILE || it.tool == AgentTool.EDIT_FILE }
        val restored = undone || writes.any { it.status == AgentStepStatus.UNDONE }
        val written = writes.any { it.status == AgentStepStatus.DONE }
        val verified = writes.any { it.status == AgentStepStatus.DONE && it.verified }
        val stopped = !streaming && status != MessageStatus.GENERATING
        val prepared = steps.any { it.status == AgentStepStatus.DONE }
        return listOf(
            if (prepared) TaskStageStatus.DONE else if (stopped) TaskStageStatus.NEEDS_HELP else TaskStageStatus.RUNNING,
            if (restored) TaskStageStatus.NEEDS_HELP else if (written) TaskStageStatus.DONE else if (stopped) TaskStageStatus.NEEDS_HELP else if (prepared) TaskStageStatus.RUNNING else TaskStageStatus.WAITING,
            if (restored) TaskStageStatus.NEEDS_HELP else if (verified) TaskStageStatus.DONE else if (stopped) TaskStageStatus.NEEDS_HELP else TaskStageStatus.WAITING,
        )
    }
}
