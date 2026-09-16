package cl.iac33.app.control

import android.content.Context

class IdempotencyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iac33_idempotency", Context.MODE_PRIVATE)

    @Synchronized
    fun seen(key: String): Boolean = key.isNotBlank() && prefs.contains("key_$key")

    @Synchronized
    fun record(key: String): Boolean {
        if (key.isBlank() || seen(key)) return false
        prefs.edit().putLong("key_$key", System.currentTimeMillis()).apply()
        return true
    }

    @Synchronized
    fun clear(key: String) { prefs.edit().remove("key_$key").apply() }
}
