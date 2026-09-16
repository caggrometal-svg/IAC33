package cl.iac33.app.core

import java.util.UUID

enum class OperationError { NETWORK, AUTH, VALIDATION, TIMEOUT, RATE_LIMIT, PROVIDER, STORAGE, INTEGRITY, VERSION, PERMISSION, CONFLICT, INTERNAL, UNSUPPORTED }

data class OperationContext(
    val requestId: String = UUID.randomUUID().toString(),
    val traceId: String = UUID.randomUUID().toString(),
    val idempotencyKey: String? = null
)

data class DiagnosticEvent(
    val code: String,
    val message: String,
    val context: OperationContext = OperationContext(),
    val recoverable: Boolean = true
)

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T, val context: OperationContext = OperationContext()) : OperationResult<T>
    data class Failure(val error: OperationError, val message: String, val context: OperationContext = OperationContext()) : OperationResult<Nothing>
}

interface AppRuntimeContract {
    fun state(): RuntimeState
    fun diagnostics(): List<DiagnosticEvent>
}

enum class RuntimeState { STARTING, READY, DEGRADED, OFFLINE, RECOVERING, FAILED }
