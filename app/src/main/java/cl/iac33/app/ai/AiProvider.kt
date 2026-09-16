package cl.iac33.app.ai

import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationResult

interface AiProvider {
    val id: String
    val model: String
    fun isAvailable(): Boolean
    suspend fun generate(request: AiRequest): OperationResult<AiResult>
}
