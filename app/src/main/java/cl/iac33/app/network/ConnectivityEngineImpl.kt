package cl.iac33.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cl.iac33.app.core.ConnectivityEngine
import cl.iac33.app.core.ConnectivityState
import cl.iac33.app.core.OperationResult

class ConnectivityEngineImpl(context: Context) : ConnectivityEngine {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun state(): ConnectivityState = classify(connectivityManager)

    override suspend fun probe(): OperationResult<ConnectivityState> =
        OperationResult.Success(state())

    companion object {
        internal fun classify(manager: ConnectivityManager): ConnectivityState {
            val network = manager.activeNetwork ?: return ConnectivityState.OFFLINE
            val capabilities = manager.getNetworkCapabilities(network)
                ?: return ConnectivityState.UNKNOWN

            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                return ConnectivityState.CAPTIVE
            }
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return ConnectivityState.DEGRADED
            }
            return ConnectivityState.ONLINE
        }
    }
}
