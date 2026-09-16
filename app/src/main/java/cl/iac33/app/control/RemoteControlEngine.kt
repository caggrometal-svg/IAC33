package cl.iac33.app.control

import android.content.Context
import cl.iac33.app.core.ControlEngine
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import cl.iac33.app.core.RemoteCommand
import cl.iac33.app.ota.OtaCommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

class RemoteControlEngine(
    context: Context,
    private val baseUrl: String,
    private val identity: DeviceIdentity = DeviceIdentity(context)
) : ControlEngine {
    private val otaExecutor = OtaCommandExecutor(context.applicationContext)

    suspend fun enroll(pairingToken: String): OperationResult<Unit> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext OperationResult.Failure(OperationError.NETWORK, "Backend URL not configured")
        try {
            val json = JSONObject().put("deviceId", identity.deviceId).put("publicKeyPem", identity.publicKeyPem()).put("pairingToken", pairingToken)
            val result = request("/v1/devices/enroll", json.toString(), signed = false)
            if (result.first !in 200..299) return@withContext OperationResult.Failure(OperationError.AUTH, result.second.optString("error", "Enrollment failed"))
            identity.markEnrolled(true)
            OperationResult.Success(Unit)
        } catch (error: Exception) {
            OperationResult.Failure(OperationError.NETWORK, error.message ?: "Enrollment failed")
        }
    }

    suspend fun claimNext(): OperationResult<RemoteCommand?> = withContext(Dispatchers.IO) {
        if (!identity.enrolled) return@withContext OperationResult.Failure(OperationError.AUTH, "DEVICE_NOT_ENROLLED")
        try {
            val result = request("/v1/device/commands/claim-next", "{}", signed = true)
            if (result.first !in 200..299) return@withContext OperationResult.Failure(OperationError.AUTH, result.second.optString("error", "Claim failed"))
            val command = result.second.optJSONObject("command") ?: return@withContext OperationResult.Success(null)
            OperationResult.Success(
                RemoteCommand(
                    id = command.getString("id"),
                    type = command.getString("type"),
                    payload = command.getJSONObject("payload").toString(),
                    idempotencyKey = command.getString("idempotency_key"),
                    expiresAtMs = Instant.parse(command.getString("expires_at")).toEpochMilli()
                )
            )
        } catch (error: Exception) {
            OperationResult.Failure(OperationError.NETWORK, error.message ?: "Claim failed")
        }
    }

    override suspend fun submit(command: RemoteCommand): OperationResult<String> = executeAndAck(command)

    suspend fun executeAndAck(command: RemoteCommand): OperationResult<String> = withContext(Dispatchers.IO) {
        if (!identity.enrolled) return@withContext OperationResult.Failure(OperationError.AUTH, "DEVICE_NOT_ENROLLED")
        try {
            val execute = request("/v1/device/commands/${command.id}/execute", "{}", signed = true)
            if (execute.first !in 200..299) return@withContext OperationResult.Failure(OperationError.PROVIDER, execute.second.optString("error", "Execute transition failed"))
            val result = executeCommand(command)
            val target = if (result.first) "succeed" else "fail"
            val ackBody = JSONObject().put("detail", JSONObject().put("message", result.second).put("idempotencyKey", command.idempotencyKey)).toString()
            val ack = request("/v1/device/commands/${command.id}/$target", ackBody, signed = true)
            if (ack.first !in 200..299) return@withContext OperationResult.Failure(OperationError.PROVIDER, ack.second.optString("error", "ACK failed"))
            if (result.first) OperationResult.Success(result.second) else OperationResult.Failure(OperationError.UNSUPPORTED, result.second)
        } catch (error: Exception) {
            OperationResult.Failure(OperationError.NETWORK, error.message ?: "Command execution failed")
        }
    }

    suspend fun pollOnce(): OperationResult<String> {
        return when (val claimed = claimNext()) {
            is OperationResult.Success -> {
                val command = claimed.value ?: return OperationResult.Success("NO_COMMAND")
                executeAndAck(command)
            }
            is OperationResult.Failure -> claimed
        }
    }

    private fun executeCommand(command: RemoteCommand): Pair<Boolean, String> = when (command.type.uppercase()) {
        "PING", "NOOP" -> true to "ACK:${command.type.uppercase()}"
        "OTA_INSTALL" -> runCatching { true to otaExecutor.execute(command.payload) }
            .getOrElse { false to "OTA_FAILED:${it.message ?: "unknown"}" }
        else -> false to "UNSUPPORTED_COMMAND:${command.type}"
    }

    private fun request(path: String, body: String, signed: Boolean): Pair<Int, JSONObject> {
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (signed) {
                setRequestProperty("X-Device-Id", identity.deviceId)
                setRequestProperty("X-Device-Timestamp", timestamp.toString())
                setRequestProperty("X-Device-Nonce", nonce)
                val canonical = listOf("POST", path, timestamp.toString(), nonce, body).joinToString("\n")
                setRequestProperty("X-Device-Signature", identity.sign(canonical.toByteArray(Charsets.UTF_8)))
            }
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally { connection.disconnect() }
    }
}
