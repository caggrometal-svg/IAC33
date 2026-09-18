package cl.iac33.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small durable chat cache. Keeps process recreation from pushing the conversation into Activity saved state. */
class ChatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<ChatLine> = runCatching {
        val array = JSONArray(prefs.getString(KEY_LINES, "[]").orEmpty())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val text = item.optString("text")
                if ((role == "user" || role == "assistant") && text.isNotBlank()) {
                    val source = item.optString("source").ifBlank { "unknown" }
                    add(ChatLine(role, text.take(MAX_MESSAGE_CHARS), source))
                }
            }
        }.takeLast(MAX_LINES)
    }.getOrDefault(emptyList())

    fun save(lines: List<ChatLine>) {
        val array = JSONArray()
        lines.takeLast(MAX_LINES).forEach { line ->
            if (line.role != "user" && line.role != "assistant") return@forEach
            array.put(
                JSONObject()
                    .put("role", line.role)
                    .put("text", line.text.take(MAX_MESSAGE_CHARS))
                    .put("source", line.source)
            )
        }
        prefs.edit().putString(KEY_LINES, array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_LINES).apply()
    }

    companion object {
        const val MAX_LINES = 40
        const val MAX_MESSAGE_CHARS = 8_000
        private const val PREFS = "iac33_chat"
        private const val KEY_LINES = "lines"
    }
}
