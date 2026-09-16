package cl.iac33.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import cl.iac33.app.core.LocationEngine
import cl.iac33.app.core.LocationPermission
import cl.iac33.app.core.LocationSnapshot
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class LocationEngineImpl(context: Context) : LocationEngine {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun permissionState(): LocationPermission {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return when {
            fine -> LocationPermission.PRECISE
            coarse -> LocationPermission.APPROXIMATE
            else -> LocationPermission.DENIED
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): OperationResult<LocationSnapshot?> {
        val permission = permissionState()
        if (permission == LocationPermission.DENIED) {
            return OperationResult.Failure(OperationError.PERMISSION, "Location permission denied")
        }
        val providers = manager.getProviders(true)
        val best = providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        return OperationResult.Success(best?.let {
            LocationSnapshot(it.latitude, it.longitude, it.accuracy, it.time)
        })
    }
}
