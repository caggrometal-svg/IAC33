package cl.iac33.app.control

import cl.iac33.app.core.OperationResult

enum class CommandState { PENDING, CLAIMED, EXECUTING, SUCCEEDED, FAILED, EXPIRED, REJECTED }

data class CommandRecord(val id: String, val state: CommandState, val idempotencyKey: String)

class CommandLifecycle {
    private val records = LinkedHashMap<String, CommandRecord>()

    @Synchronized
    fun create(id: String, idempotencyKey: String): OperationResult<CommandRecord> {
        if (id.isBlank() || idempotencyKey.isBlank()) return OperationResult.Failure(cl.iac33.app.core.OperationError("COMMAND_INVALID", "id/idempotencyKey requeridos"))
        val existing = records[id]
        if (existing != null) return OperationResult.Success(existing)
        val record = CommandRecord(id, CommandState.PENDING, idempotencyKey)
        records[id] = record
        return OperationResult.Success(record)
    }

    @Synchronized
    fun transition(id: String, next: CommandState): OperationResult<CommandRecord> {
        val current = records[id] ?: return OperationResult.Failure(cl.iac33.app.core.OperationError("COMMAND_NOT_FOUND", "Comando inexistente"))
        if (!allowed(current.state, next)) return OperationResult.Failure(cl.iac33.app.core.OperationError("COMMAND_TRANSITION", "Transición no permitida"))
        val updated = current.copy(state = next)
        records[id] = updated
        return OperationResult.Success(updated)
    }

    private fun allowed(from: CommandState, to: CommandState): Boolean = when (from) {
        CommandState.PENDING -> to == CommandState.CLAIMED || to == CommandState.EXPIRED || to == CommandState.REJECTED
        CommandState.CLAIMED -> to == CommandState.EXECUTING || to == CommandState.FAILED
        CommandState.EXECUTING -> to == CommandState.SUCCEEDED || to == CommandState.FAILED
        CommandState.SUCCEEDED, CommandState.FAILED, CommandState.EXPIRED, CommandState.REJECTED -> false
    }
}
