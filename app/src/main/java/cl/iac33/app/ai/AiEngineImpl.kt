
package cl.iac33.app.ai

import cl.iac33.app.BuildConfig
import cl.iac33.app.IAC33Application
import cl.iac33.app.core.AiEngine
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class AiEngineImpl(
    private val engine: AiEngine = defaultEngine(false)
) : AiEngine {

    constructor(localFirst: Boolean) : this(defaultEngine(localFirst))

    override suspend fun generateStreaming(request: AiRequest, onDelta: suspend (String) -> Unit): OperationResult<AiResult> {
        if (request.messages.isEmpty() || request.messages.size > MAX_MESSAGES ||
            request.messages.all { it.content.isBlank() } ||
            request.messages.any { it.content.length > MAX_MESSAGE_CHARS }
        ) return OperationResult.Failure(OperationError.VALIDATION, "Solicitud IA fuera de límites")
        return engine.generateStreaming(request, onDelta)
    }

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        if (request.messages.isEmpty() || request.messages.size > MAX_MESSAGES ||
            request.messages.all { it.content.isBlank() } ||
            request.messages.any { it.content.length > MAX_MESSAGE_CHARS }
        ) return OperationResult.Failure(OperationError.VALIDATION, "Solicitud IA fuera de límites")
        return engine.generate(request)
    }

    companion object {
        private const val MAX_MESSAGES = 32
        private const val MAX_MESSAGE_CHARS = 8_000

        private fun defaultEngine(localFirst: Boolean): AiEngine {
            val context = IAC33Application.contextOrNull()
            return if (context != null) {
                AiBridge(context, BuildConfig.IAC33_BACKEND_URL, localFirst)
            } else {
                val local = LocalFallbackProvider()
                val remote = RemoteBackendProvider(BuildConfig.IAC33_BACKEND_URL)
                AiRouter(if (localFirst) listOf(local, remote) else listOf(remote, local))
            }
        }
    }
}
