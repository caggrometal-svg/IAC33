package cl.iac33.app.ai

import android.util.Log
import cl.iac33.app.BuildConfig
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.min

class RemoteBackendProvider(
    private val baseUrl: String
) : AiProvider {
    override val id: String = "iac33-free-pool"
    override val model: String = "free-pool"

    override fun isAvailable(): Boolean = baseUrl.isNotBlank()

    override suspend fun generateStreaming(
        request: AiRequest,
        onDelta: suspend (String) -> Unit
    ): OperationResult<AiResult> = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext OperationResult.Failure(OperationError.NETWORK, "Backend URL not configured")
        }
        val deadline = System.currentTimeMillis() + request.timeoutMs.coerceIn(2_000L, 45_000L)
        var connection: HttpURLConnection? = null
        val started = System.currentTimeMillis()
        try {
            val parsed = URI(baseUrl.trimEnd('/'))
            val secure = parsed.scheme.equals("https", ignoreCase = true)
            val localDebug = BuildConfig.DEBUG && parsed.scheme.equals("http", ignoreCase = true) &&
                (parsed.host.equals("localhost", true) || parsed.host == "127.0.0.1" || parsed.host == "10.0.2.2")
            if (!secure && !localDebug) {
                return@withContext OperationResult.Failure(OperationError.NETWORK, "Backend URL must use HTTPS")
            }
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val timeout = min(15_000L, remaining).toInt().coerceAtLeast(1_000)
            connection = (URL(baseUrl.trimEnd('/') + "/v1/ai/stream").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeout
                readTimeout = timeout
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "IAC33/2.0 Android")
            }
            val messages = JSONArray()
            request.messages.forEach { message ->
                messages.put(JSONObject().put("role", message.role).put("content", message.content))
            }
            connection.outputStream.use {
                it.write(
                    JSONObject()
                        .put("conversationId", request.conversationId)
                        .put("messages", messages)
                        .put("timeoutMs", timeout)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return@withContext OperationResult.Failure(
                    when (status) {
                        408 -> OperationError.TIMEOUT
                        429 -> OperationError.RATE_LIMIT
                        else -> if (status >= 500) OperationError.PROVIDER else OperationError.NETWORK
                    },
                    "HTTP $status · " + runCatching { JSONObject(raw).optString("error") }.getOrDefault("AI backend error")
                )
            }
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            val text = StringBuilder()
            var provider: String? = null
            var model: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data == "null" || data.isBlank()) continue
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    text.append(delta)
                    onDelta(delta)
                }
                if (event.optBoolean("done", false)) {
                    provider = event.optString("provider").ifBlank { provider }
                    model = event.optString("model").ifBlank { model }
                }
                if (System.currentTimeMillis() >= deadline) break
            }
            val clean = AiTextSanitizer.sanitize(text.toString())
            if (clean.isBlank()) return@withContext OperationResult.Failure(OperationError.PROVIDER, "Empty AI streaming response")
            OperationResult.Success(AiResult(provider, model, clean, System.currentTimeMillis() - started))
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            OperationResult.Failure(OperationError.TIMEOUT, error.message ?: "AI streaming timeout")
        } catch (error: Exception) {
            OperationResult.Failure(OperationError.NETWORK, error.message ?: "AI streaming request failed")
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext OperationResult.Failure(
                OperationError.NETWORK,
                "Backend URL not configured"
            )
        }

        val deadline = System.currentTimeMillis() + request.timeoutMs.coerceIn(2_000L, 45_000L)
        var lastFailure: OperationResult.Failure? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (System.currentTimeMillis() >= deadline) break
            try {
                val result = executeOnce(request, deadline)
                if (result is OperationResult.Success) return@withContext result

                lastFailure = result as OperationResult.Failure
                if (!isRetryable(lastFailure.error) || attempt == MAX_ATTEMPTS) {
                    return@withContext lastFailure
                }

                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 50L) break
                val backoff = min(
                    1_200L,
                    BASE_BACKOFF_MS * (1L shl (attempt - 1))
                )
                delay(min(backoff, remaining - 1L))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Remote attempt $attempt failed: ${error.javaClass.simpleName}: ${error.message}")
                lastFailure = OperationResult.Failure(
                    if (error is java.net.SocketTimeoutException) OperationError.TIMEOUT else OperationError.NETWORK,
                    error.message ?: "AI backend request failed"
                )
                if (attempt == MAX_ATTEMPTS) return@withContext lastFailure
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 50L) break
                delay(min(BASE_BACKOFF_MS * (1L shl (attempt - 1)), remaining - 1L))
            }
        }

        lastFailure ?: OperationResult.Failure(
            OperationError.TIMEOUT,
            "AI backend request timed out"
        )
    }

    private fun executeOnce(
        request: AiRequest,
        deadline: Long
    ): OperationResult<AiResult> {
        var connection: HttpURLConnection? = null
        val started = System.currentTimeMillis()
        try {
            val parsed = URI(baseUrl.trimEnd('/'))
            val secure = parsed.scheme.equals("https", ignoreCase = true)
            val localDebug = BuildConfig.DEBUG && parsed.scheme.equals("http", ignoreCase = true) &&
                (parsed.host.equals("localhost", true) || parsed.host == "127.0.0.1" || parsed.host == "10.0.2.2")
            if (!secure && !localDebug) {
                return OperationResult.Failure(OperationError.NETWORK, "Backend URL must use HTTPS")
            }

            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val timeout = min(5_000L, remaining).toInt().coerceAtLeast(1_000)

            connection = (URL(baseUrl.trimEnd('/') + "/v1/ai/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeout
                readTimeout = timeout
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "IAC33/2.0 Android")
            }

            val messages = JSONArray()
            request.messages.forEach { message ->
                messages.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", message.content)
                )
            }

            val payload = JSONObject()
                .put("conversationId", request.conversationId)
                .put("messages", messages)
                .put("timeoutMs", timeout)

            Log.d(TAG, "AI remote attempt start timeoutMs=$timeout messages=${request.messages.size}")

            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (raw.isNotBlank()) JSONObject(raw) else JSONObject()

            if (status !in 200..299) {
                val error = json.optString("error", "AI backend error")
                val operationError = when {
                    status == 408 -> OperationError.TIMEOUT
                    status == 429 -> OperationError.RATE_LIMIT
                    status >= 500 -> OperationError.PROVIDER
                    else -> OperationError.NETWORK
                }
                return OperationResult.Failure(operationError, "HTTP $status · $error")
            }

            val text = AiTextSanitizer.sanitize(json.optString("text", ""))
            if (text.isBlank()) {
                return OperationResult.Failure(OperationError.PROVIDER, "Empty AI response")
            }

            return OperationResult.Success(
                AiResult(
                    provider = json.optString("provider").ifBlank { id },
                    model = json.optString("model").ifBlank { model },
                    text = text,
                    latencyMs = System.currentTimeMillis() - started
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "AI remote exception ${error.javaClass.simpleName}: ${error.message}")
            return OperationResult.Failure(
                if (error is java.net.SocketTimeoutException) OperationError.TIMEOUT else OperationError.NETWORK,
                error.message ?: "AI backend request failed"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun isRetryable(error: OperationError): Boolean =
        error == OperationError.NETWORK ||
            error == OperationError.TIMEOUT ||
            error == OperationError.RATE_LIMIT ||
            error == OperationError.PROVIDER

    companion object {
        private const val TAG = "IAC33-AI"
        private const val MAX_ATTEMPTS = 3
        private const val BASE_BACKOFF_MS = 200L
    }
}
