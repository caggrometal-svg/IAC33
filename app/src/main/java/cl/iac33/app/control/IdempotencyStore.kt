package cl.iac33.app.control

import android.content.Context

class IdempotencyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iac33_idempotency", Context.MODE_PRIVATE)

    @Synchronized
    fun seen(key: String): Boolean = key.isNotBlank() && prefs.contains("key_$key")

    @Synchronized
    fun record(key: String): Boolean {
        if (key.isBlank() || seen(key)) return false
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putLong("key_$key", now)
        val existing = prefs.all.entries
            .asSequence()
            .filter { it.key.startsWith("key_") && it.value is Long }
            .map { it.key to (it.value as Long) }
            .sortedByDescending { it.second }
            .drop(MAX_KEYS - 1)
            .map { it.first }
            .toList()
        existing.forEach(editor::remove)
        editor.putLong("key_$key", now).apply()
        return true
    }

    @Synchronized
    fun clear(key: String) {
        prefs.edit().remove("key_$key").apply()
    }

    companion object {
        private const val MAX_KEYS = 512
    }
}
