
package cl.iac33.app.core

data class AiMessage(val role: String, val content: String)
data class AiRequest(val conversationId: String, val messages: List<AiMessage>, val timeoutMs: Long = 20_000)
data class AiResult(val provider: String?, val model: String?, val text: String?, val latencyMs: Long, val error: OperationError? = null)

interface AiEngine {
    suspend fun generate(request: AiRequest): OperationResult<AiResult>
    suspend fun generateStreaming(request: AiRequest, onDelta: suspend (String) -> Unit): OperationResult<AiResult> {
        val result = generate(request)
        if (result is OperationResult.Success) result.value.text?.takeIf { it.isNotBlank() }?.let { onDelta(it) }
        return result
    }
}
interface ConnectivityEngine { fun state(): ConnectivityState; suspend fun probe(): OperationResult<ConnectivityState> }
interface MemoryEngine { suspend fun put(item: MemoryItem): OperationResult<Unit>; suspend fun search(query: String): OperationResult<List<MemoryItem>> }
interface LocationEngine { fun permissionState(): LocationPermission; suspend fun lastKnown(): OperationResult<LocationSnapshot?> }
interface SeismicEngine { suspend fun events(query: SeismicQuery): OperationResult<List<SeismicEvent>> }
interface ControlEngine { suspend fun submit(command: RemoteCommand): OperationResult<String> }
interface UpdateEngine { suspend fun check(): OperationResult<UpdateInfo?> }

data class MemoryItem(val id: String, val scope: String, val text: String)
enum class LocationPermission { UNKNOWN, DENIED, APPROXIMATE, PRECISE }
data class LocationSnapshot(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timestampMs: Long)
enum class ConnectivityState { ONLINE, DEGRADED, OFFLINE, CAPTIVE, UNKNOWN }
data class SeismicQuery(val fromMs: Long? = null, val toMs: Long? = null, val minMagnitude: Double? = null)
data class SeismicEvent(val id: String, val magnitude: Double, val depthKm: Double, val latitude: Double, val longitude: Double, val source: String, val timestampMs: Long)
data class RemoteCommand(val id: String, val type: String, val payload: String, val idempotencyKey: String, val expiresAtMs: Long)
data class UpdateInfo(val releaseId: String, val version: String, val sha256: String, val sizeBytes: Long)
