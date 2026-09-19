
package cl.iac33.app.ai

import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationResult

interface AiProvider {
    val id: String
    val model: String
    fun isAvailable(): Boolean
    suspend fun generate(request: AiRequest): OperationResult<AiResult>
    suspend fun generateStreaming(request: AiRequest, onDelta: suspend (String) -> Unit): OperationResult<AiResult> {
        val result = generate(request)
        if (result is OperationResult.Success) result.value.text?.takeIf { it.isNotBlank() }?.let { onDelta(it) }
        return result
    }
}
