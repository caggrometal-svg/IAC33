
package cl.iac33.app.ai

import cl.iac33.app.core.AiEngine
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import kotlinx.coroutines.CancellationException

class AiRouter(
    private val providers: List<AiProvider>
) : AiEngine {

    override suspend fun generateStreaming(
        request: AiRequest,
        onDelta: suspend (String) -> Unit
    ): OperationResult<AiResult> {
        if (providers.isEmpty()) return OperationResult.Failure(OperationError.PROVIDER, "No AI providers configured")
        var lastFailure: OperationResult.Failure? = null
        for (provider in providers) {
            if (!runCatching { provider.isAvailable() }.getOrDefault(false)) continue
            try {
                when (val result = provider.generateStreaming(request, onDelta)) {
                    is OperationResult.Success -> {
                        val clean = AiTextSanitizer.sanitize(result.value.text)
                        if (clean.isBlank()) {
                            lastFailure = OperationResult.Failure(OperationError.PROVIDER, "Empty sanitized AI response")
                            continue
                        }
                        return OperationResult.Success(result.value.copy(text = clean))
                    }
                    is OperationResult.Failure -> lastFailure = result
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastFailure = OperationResult.Failure(OperationError.INTERNAL, "Provider streaming failed")
            }
        }
        return lastFailure ?: OperationResult.Failure(OperationError.PROVIDER, "No AI provider available")
    }

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        if (providers.isEmpty()) {
            return OperationResult.Failure(OperationError.PROVIDER, "No AI providers configured")
        }
        var lastFailure: OperationResult.Failure? = null
        for (provider in providers) {
            if (!runCatching { provider.isAvailable() }.getOrDefault(false)) continue
            try {
                when (val result = provider.generate(request)) {
                    is OperationResult.Success -> {
                        val clean = AiTextSanitizer.sanitize(result.value.text)
                        if (clean.isBlank()) {
                            lastFailure = OperationResult.Failure(OperationError.PROVIDER, "Empty sanitized AI response")
                            continue
                        }
                        return OperationResult.Success(value = result.value.copy(text = clean))
                    }
                    is OperationResult.Failure -> lastFailure = result
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastFailure = OperationResult.Failure(OperationError.INTERNAL, "Provider execution failed")
            }
        }
        return lastFailure ?: OperationResult.Failure(OperationError.PROVIDER, "No AI provider available")
    }
}
