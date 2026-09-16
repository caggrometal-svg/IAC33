package cl.iac33.app.ai

import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class LocalFallbackProvider : AiProvider {
    override val id = "local-fallback"
    override val model = "local-rule-engine"
    override fun isAvailable() = true
    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        val text = request.messages.lastOrNull { it.role == "user" }?.content?.trim()
            ?: return OperationResult.Failure(OperationError.VALIDATION, "No user message")
        return OperationResult.Success(AiResult(id, model, "Modo local activo: $text", 0L))
    }
}
