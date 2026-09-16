package cl.iac33.app.control

import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import cl.iac33.app.core.RemoteCommand

class CommandValidator(private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    fun validate(command: RemoteCommand): OperationResult<RemoteCommand> {
        if (command.id.isBlank() || command.type.isBlank() || command.idempotencyKey.isBlank()) {
            return OperationResult.Failure(OperationError.VALIDATION, "Command identity is incomplete")
        }
        if (command.expiresAtMs <= clockMs()) {
            return OperationResult.Failure(OperationError.VERSION, "Command expired")
        }
        if (command.payload.isBlank()) {
            return OperationResult.Failure(OperationError.VALIDATION, "Command payload is empty")
        }
        return OperationResult.Success(command)
    }
}
