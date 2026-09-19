package cl.iac33.app

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cl.iac33.app.control.DeviceControlWorker
import java.util.concurrent.TimeUnit

class IAC33Application : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        if (!startupWorkersDisabled()) {
            IAC33Runtime.initialize(applicationContext)
        }
    }

    private fun startupWorkersDisabled(): Boolean =
        runCatching {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getBoolean(META_DISABLE_STARTUP_WORKERS, false) == true
        }.getOrDefault(false)

    companion object {
        const val META_DISABLE_STARTUP_WORKERS = "cl.iac33.app.DISABLE_STARTUP_WORKERS"

        @Volatile
        private var context: Context? = null

        fun contextOrNull(): Context? = context
    }
}

object IAC33Runtime {
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                val workManager = WorkManager.getInstance(context)
                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                val startup = OneTimeWorkRequestBuilder<DeviceControlWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                workManager.enqueueUniqueWork("iac33-device-control-startup", ExistingWorkPolicy.REPLACE, startup)

                val request = PeriodicWorkRequestBuilder<DeviceControlWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    "iac33-device-control",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                initialized = true
            }
        }
    }
}
