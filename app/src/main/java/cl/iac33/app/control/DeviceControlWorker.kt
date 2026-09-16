package cl.iac33.app.control

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cl.iac33.app.BuildConfig
import cl.iac33.app.core.OperationResult

class DeviceControlWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val baseUrl = BuildConfig.IAC33_BACKEND_URL.trim()
        if (baseUrl.isBlank()) return Result.failure()

        return when (val result = RemoteControlEngine(applicationContext, baseUrl).pollOnce()) {
            is OperationResult.Success -> Result.success()
            is OperationResult.Failure -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
