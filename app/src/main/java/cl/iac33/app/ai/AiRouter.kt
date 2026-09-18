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

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        if (providers.isEmpty()) {
            return OperationResult.Failure(OperationError.PROVIDER, "No AI providers configured")
        }

        var lastFailure: OperationResult.Failure? = null
        for (provider in providers) {
            val available = try {
                provider.isAvailable()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastFailure = OperationResult.Failure(
                    OperationError.INTERNAL,
                    "Provider availability check failed"
                )
                false
            }
            if (!available) continue

            try {
                when (val result = provider.generate(request)) {
                    is OperationResult.Success -> {
                        val value = result.value
                        // Provider/diagnostic metadata is transport data, never
                        // part of the assistant's visible answer.
                        return OperationResult.Success(
                            value.copy(text = cleanAssistantText(value.text))
                        )
                    }
                    is OperationResult.Failure -> lastFailure = result
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastFailure = OperationResult.Failure(
                    OperationError.INTERNAL,
                    "Provider execution failed"
                )
            }
        }

        return lastFailure ?: OperationResult.Failure(
            OperationError.PROVIDER,
            "No AI provider available"
        )
    }

    private fun cleanAssistantText(raw: String?): String {
        val lines = raw.orEmpty()
            .trim()
            .lines()
            .filterNot { line ->
                val normalized = line.trim().lowercase()
                normalized.startsWith("system:") ||
                    normalized.startsWith("user:") ||
                    normalized.startsWith("assistant:") ||
                    normalized.startsWith("provider ·") ||
                    normalized.startsWith("provider:") ||
                    normalized.startsWith("http 503") ||
                    normalized.startsWith("http 429") ||
                    normalized.startsWith("ai_providers_unavailable") ||
                    normalized.startsWith("modo local activo") ||
                    normalized.startsWith("respaldo local activado")
            }
        return lines.joinToString("\n").trim()
    }
}
