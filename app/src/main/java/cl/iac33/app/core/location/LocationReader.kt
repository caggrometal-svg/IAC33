package cl.iac33.app.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.core.content.ContextCompat

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String
)

class LocationReader(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    suspend fun readCurrentOrLastKnown(): LocationSnapshot? {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (manager.isProviderEnabled(provider)) {
                        val current = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
                            suspendCancellableCoroutine<android.location.Location?> { continuation ->
                                manager.getCurrentLocation(provider, null, appContext.mainExecutor) { location ->
                                    if (continuation.isActive) continuation.resume(location)
                                }
                            }
                        }
                        if (current != null) {
                            return LocationSnapshot(current.latitude, current.longitude, current.accuracy, current.provider ?: provider)
                        }
                    }
                } catch (_: SecurityException) {
                    return null
                }
            }
        }

        val candidates = buildList {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (manager.isProviderEnabled(provider)) {
                        manager.getLastKnownLocation(provider)?.let(::add)
                    }
                } catch (_: SecurityException) {
                    return null
                }
            }
        }
        val best = candidates.maxByOrNull { it.time } ?: return null
        return LocationSnapshot(
            best.latitude,
            best.longitude,
            best.accuracy,
            best.provider ?: "unknown"
        )
    }
    companion object {
        private const val CURRENT_LOCATION_TIMEOUT_MS = 8_000L
    }
}
