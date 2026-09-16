package cl.iac33.app.ai

import cl.iac33.app.BuildConfig
import cl.iac33.app.IAC33Application
import cl.iac33.app.core.AiEngine
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class AiEngineImpl(
    private val engine: AiEngine = defaultEngine()
) : AiEngine {
    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        if (request.messages.isEmpty() || request.messages.all { it.content.isBlank() }) {
            return OperationResult.Failure(OperationError.VALIDATION, "Prompt vacío")
        }
        return engine.generate(request)
    }

    companion object {
        private fun defaultEngine(): AiEngine {
            val context = IAC33Application.contextOrNull()
            return if (context != null) {
                AiBridge(context, BuildConfig.IAC33_BACKEND_URL)
            } else {
                AiRouter(
                    listOf(
                        RemoteBackendProvider(BuildConfig.IAC33_BACKEND_URL),
                        LocalFallbackProvider()
                    )
                )
            }
        }
    }
}
