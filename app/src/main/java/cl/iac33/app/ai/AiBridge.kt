
package cl.iac33.app.ai

import android.content.Context
import cl.iac33.app.core.AiEngine
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationResult
import cl.iac33.app.core.connectivity.ConnectivityMonitor
import cl.iac33.app.core.connectivity.ConnectivityStatus

/** Stable connectivity boundary between IAC33 and the AI provider router. */
class AiBridge(
    context: Context,
    backendUrl: String,
    private val localFirst: Boolean = false
) : AiEngine {
    private val connectivity = ConnectivityMonitor(context)
    private val local = LocalFallbackProvider()
    private val remote = RemoteBackendProvider(backendUrl)
    private val router = AiRouter(if (localFirst) listOf(local, remote) else listOf(remote, local))

    override suspend fun generateStreaming(request: AiRequest, onDelta: suspend (String) -> Unit): OperationResult<AiResult> {
        return when (connectivity.status()) {
            ConnectivityStatus.OFFLINE -> local.generateStreaming(request, onDelta)
            ConnectivityStatus.ONLINE,
            ConnectivityStatus.LIMITED -> router.generateStreaming(request, onDelta)
        }
    }

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        return when (connectivity.status()) {
            ConnectivityStatus.OFFLINE -> local.generate(request)
            ConnectivityStatus.ONLINE,
            ConnectivityStatus.LIMITED -> router.generate(request)
        }
    }
}
