package cl.iac33.app.ota

import android.content.Context

/** Persists the OTA awaiting post-install health/ACK across the installer process restart. */
class PendingOtaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iac33_pending_ota", Context.MODE_PRIVATE)

    data class Pending(
        val commandId: String,
        val idempotencyKey: String,
        val releaseId: String,
        val appVersion: String
    )

    @Synchronized
    fun save(commandId: String, idempotencyKey: String, releaseId: String, appVersion: String): Boolean =
        prefs.edit()
            .putString("commandId", commandId)
            .putString("idempotencyKey", idempotencyKey)
            .putString("releaseId", releaseId)
            .putString("appVersion", appVersion)
            .commit()

    @Synchronized
    fun get(): Pending? {
        val commandId = prefs.getString("commandId", null) ?: return null
        val idempotencyKey = prefs.getString("idempotencyKey", null) ?: return null
        val releaseId = prefs.getString("releaseId", null) ?: return null
        val appVersion = prefs.getString("appVersion", null) ?: return null
        return Pending(commandId, idempotencyKey, releaseId, appVersion)
    }

    @Synchronized
    fun clear(): Boolean = prefs.edit().clear().commit()
}
