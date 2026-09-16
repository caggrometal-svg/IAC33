package cl.iac33.app.ai

import cl.iac33.app.core.AiEngine
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class AiRouter(
    private val providers: List<AiProvider>
) : AiEngine {

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        if (providers.isEmpty()) {
            return OperationResult.Failure(
                OperationError.PROVIDER,
                "No AI providers configured"
            )
        }

        var lastFailure: OperationResult.Failure? = null
        for (provider in providers) {
            if (!provider.isAvailable()) continue
            when (val result = provider.generate(request)) {
                is OperationResult.Success -> return result
                is OperationResult.Failure -> lastFailure = result
            }
        }

        return lastFailure ?: OperationResult.Failure(
            OperationError.PROVIDER,
            "No AI provider available"
        )
    }
}
