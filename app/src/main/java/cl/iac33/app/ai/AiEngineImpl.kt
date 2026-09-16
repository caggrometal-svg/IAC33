package cl.iac33.app.ai

import cl.iac33.app.core.AiEngine
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class AiEngineImpl(
    private val router: AiRouter = AiRouter(listOf(LocalFallbackProvider()))
) : AiEngine {
    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        if (request.messages.isEmpty() || request.messages.all { it.content.isBlank() }) {
            return OperationResult.Failure(OperationError.VALIDATION, "Prompt vacío")
        }
        return router.generate(request)
    }
}
