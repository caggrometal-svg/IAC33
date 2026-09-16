package cl.iac33.app.connectivity

import cl.iac33.app.core.ConnectivityEngine
import cl.iac33.app.core.ConnectivityState
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultConnectivityEngine(
    private val probeUrl: String = "https://clients3.google.com/generate_204",
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000,
) : ConnectivityEngine {
    @Volatile private var currentState: ConnectivityState = ConnectivityState.UNKNOWN

    override fun state(): ConnectivityState = currentState

    override suspend fun probe(): OperationResult<ConnectivityState> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val connection = (URL(probeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
            }
            try {
                val code = connection.responseCode
                when {
                    code == 204 -> ConnectivityState.ONLINE
                    code in 200..399 -> ConnectivityState.DEGRADED
                    code in 400..499 -> ConnectivityState.CAPTIVE
                    else -> ConnectivityState.DEGRADED
                }
            } finally {
                connection.disconnect()
            }
        }

        result.fold(
            onSuccess = { state ->
                currentState = state
                OperationResult.Success(state)
            },
            onFailure = { error ->
                currentState = ConnectivityState.OFFLINE
                OperationResult.Failure(
                    OperationError.NETWORK,
                    "Connectivity probe failed: ${error::class.simpleName}"
                )
            }
        )
    }
}
