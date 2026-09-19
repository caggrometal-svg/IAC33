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

        val userText = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val intent = IntentClassifier.classify(userText)

        // Respect the configured provider order. Local fallback is only preferred
        // when the user explicitly enabled "IA local primero".
        val orderedProviders = if (intent != AiIntent.GENERAL &&
            providers.firstOrNull()?.id == "local-fallback"
        ) {
            providers
        } else {
            providers
        }

        var lastFailure: OperationResult.Failure? = null
        for (provider in orderedProviders) {
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
                        val clean = AiTextSanitizer.sanitize(value.text)
                        if (clean.isBlank()) {
                            lastFailure = OperationResult.Failure(
                                OperationError.PROVIDER,
                                "Empty sanitized AI response"
                            )
                            continue
                        }
                        return OperationResult.Success(
                            value.copy(text = clean)
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
}
