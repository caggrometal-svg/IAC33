package cl.iac33.app.ai

import cl.iac33.app.core.*

class AiEngineImpl(
    private val router: AiRouter = AiRouter(listOf(LocalFallbackProvider()))
) : AiEngine {
    override fun generate(prompt: String, context: OperationContext): OperationResult<AiResponse> {
        if (prompt.isBlank()) return OperationResult.Failure(OperationError("AI_EMPTY_PROMPT", "Prompt vacío"))
        return router.generate(prompt, context)
    }
}
