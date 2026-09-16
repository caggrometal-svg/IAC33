package cl.iac33.app

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
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
        IAC33Runtime.initialize(applicationContext)
    }

    companion object {
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
                val startup = OneTimeWorkRequestBuilder<DeviceControlWorker>().build()
                workManager.enqueueUniqueWork("iac33-device-control-startup", ExistingWorkPolicy.REPLACE, startup)

                val request = PeriodicWorkRequestBuilder<DeviceControlWorker>(15, TimeUnit.MINUTES)
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
