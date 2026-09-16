package cl.iac33.app.network

import android.content.Context
import org.json.JSONObject
import java.util.UUID

class OfflineOperationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iac33_offline_ops", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(type: String, payload: String, idempotencyKey: String = UUID.randomUUID().toString()): String {
        require(type.isNotBlank() && payload.isNotBlank())
        val id = UUID.randomUUID().toString()
        val record = JSONObject().put("id", id).put("type", type).put("payload", payload)
            .put("idempotencyKey", idempotencyKey).put("createdAtMs", System.currentTimeMillis())
        val ids = prefs.getString("ids", "").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableList()
        ids += id
        prefs.edit().putString("ids", ids.joinToString("\n")).putString("op_$id", record.toString()).apply()
        return id
    }

    @Synchronized
    fun pending(): List<String> = prefs.getString("ids", "").orEmpty().split('\n')
        .filter { it.isNotBlank() && prefs.contains("op_$it") }

    @Synchronized
    fun payload(id: String): String? = prefs.getString("op_$id", null)

    @Synchronized
    fun acknowledge(id: String) {
        prefs.edit().remove("op_$id").apply()
    }
}
