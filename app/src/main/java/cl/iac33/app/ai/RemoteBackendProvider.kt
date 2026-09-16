package cl.iac33.app.ai

import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RemoteBackendProvider(
    private val baseUrl: String
) : AiProvider {
    override val id: String = "iac33-free-pool"
    override val model: String = "free-pool"

    override fun isAvailable(): Boolean = baseUrl.isNotBlank()

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext OperationResult.Failure(OperationError.NETWORK, "Backend URL not configured")
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(baseUrl.trimEnd('/') + "/v1/ai/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = request.timeoutMs.toInt().coerceAtMost(45_000)
                readTimeout = request.timeoutMs.toInt().coerceAtMost(45_000)
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val messages = JSONArray()
            request.messages.forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("content", message.content))
            }
            val payload = JSONObject().put("conversationId", request.conversationId).put("messages", messages).put("timeoutMs", request.timeoutMs)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
            if (status !in 200..299) {
                val error = json.optString("error", "AI backend error")
                val operationError = if (status == 429) OperationError.RATE_LIMIT else OperationError.PROVIDER
                return@withContext OperationResult.Failure(operationError, error)
            }

            val text = json.optString("text", "")
            if (text.isBlank()) return@withContext OperationResult.Failure(OperationError.PROVIDER, "Empty AI response")
            OperationResult.Success(
                AiResult(
                    provider = json.optString("provider").ifBlank { id },
                    model = json.optString("model").ifBlank { model },
                    text = text,
                    latencyMs = System.currentTimeMillis() - started
                )
            )
        } catch (error: Exception) {
            val operationError = if (error is java.net.SocketTimeoutException) OperationError.TIMEOUT else OperationError.NETWORK
            OperationResult.Failure(operationError, error.message ?: "AI backend request failed")
        } finally {
            connection?.disconnect()
        }
    }
}
