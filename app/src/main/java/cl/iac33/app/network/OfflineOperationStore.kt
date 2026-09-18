package cl.iac33.app.network

import android.content.Context
import org.json.JSONObject
import java.util.UUID

class OfflineOperationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iac33_offline_ops", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(type: String, payload: String, idempotencyKey: String = UUID.randomUUID().toString()): String {
        require(type.isNotBlank() && payload.isNotBlank())
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Offline payload too large" }
        val id = UUID.randomUUID().toString()
        val ids = currentIds().takeLast(MAX_OPERATIONS - 1).toMutableList()
        ids += id
        val record = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("payload", payload)
            .put("idempotencyKey", idempotencyKey)
            .put("createdAtMs", System.currentTimeMillis())
        check(prefs.edit().putString(KEY_IDS, ids.joinToString("\n")).putString("op_$id", record.toString()).commit()) {
            "Offline operation persistence failed"
        }
        return id
    }

    @Synchronized
    fun pending(): List<String> = currentIds().filter { prefs.contains("op_$it") }

    @Synchronized
    fun payload(id: String): String? = prefs.getString("op_$id", null)

    @Synchronized
    fun acknowledge(id: String) {
        val ids = currentIds().filterNot { it == id && !prefs.contains("op_$it") }.toMutableList()
        ids.remove(id)
        prefs.edit().remove("op_$id").putString(KEY_IDS, ids.joinToString("\n")).apply()
    }

    private fun currentIds(): List<String> =
        prefs.getString(KEY_IDS, "").orEmpty().split('\n').filter { it.isNotBlank() }.distinct().takeLast(MAX_OPERATIONS)

    companion object {
        private const val KEY_IDS = "ids"
        private const val MAX_OPERATIONS = 256
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
