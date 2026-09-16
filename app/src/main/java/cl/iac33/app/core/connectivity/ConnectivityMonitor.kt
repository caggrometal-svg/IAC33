package cl.iac33.app.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class ConnectivityMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var listener: ((ConnectivityStatus) -> Unit)? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(currentStatus())
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish(statusFor(capabilities))
        override fun onLost(network: Network) = publish(currentStatus())
    }

    fun start(onStatusChanged: (ConnectivityStatus) -> Unit) {
        listener = onStatusChanged
        if (!registered) {
            connectivityManager.registerDefaultNetworkCallback(callback)
            registered = true
        }
        publish(currentStatus())
    }

    fun stop() {
        if (registered) {
            connectivityManager.unregisterNetworkCallback(callback)
            registered = false
        }
        listener = null
    }

    fun status(): ConnectivityStatus = currentStatus()

    private fun publish(status: ConnectivityStatus) {
        listener?.invoke(status)
    }

    private fun currentStatus(): ConnectivityStatus {
        val capabilities = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            ?: return ConnectivityStatus.OFFLINE
        return statusFor(capabilities)
    }

    private fun statusFor(capabilities: NetworkCapabilities): ConnectivityStatus = when {
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> ConnectivityStatus.ONLINE
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> ConnectivityStatus.LIMITED
        else -> ConnectivityStatus.OFFLINE
    }
}
