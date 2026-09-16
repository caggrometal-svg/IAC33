package cl.iac33.app.memory

import android.content.Context
import cl.iac33.app.core.MemoryEngine
import cl.iac33.app.core.MemoryItem
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import java.util.Base64

class MemoryEngineImpl(context: Context) : MemoryEngine {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun put(item: MemoryItem): OperationResult<Unit> = synchronized(preferences) {
        if (item.id.isBlank() || item.scope.isBlank() || item.text.isBlank()) {
            return@synchronized OperationResult.Failure(
                OperationError.VALIDATION,
                "Memory item fields cannot be blank"
            )
        }
        val ids = preferences.getString(KEY_IDS, "")
            .orEmpty()
            .split('\n')
            .filter(String::isNotBlank)
            .toMutableSet()
        ids.add(item.id)
        preferences.edit()
            .putString(KEY_IDS, ids.joinToString("\n"))
            .putString(KEY_PREFIX + item.id, MemoryCodec.encode(item))
            .apply()
        OperationResult.Success(Unit)
    }

    override suspend fun search(query: String): OperationResult<List<MemoryItem>> = synchronized(preferences) {
        val ids = preferences.getString(KEY_IDS, "")
            .orEmpty()
            .split('\n')
            .filter(String::isNotBlank)
        val normalized = query.trim().lowercase()
        val result = ids.mapNotNull { id ->
            preferences.getString(KEY_PREFIX + id, null)?.let(MemoryCodec::decode)
        }.filter { item ->
            normalized.isEmpty() ||
                item.text.lowercase().contains(normalized) ||
                item.scope.lowercase().contains(normalized)
        }
        OperationResult.Success(result)
    }

    companion object {
        private const val PREFERENCES_NAME = "iac33_memory"
        private const val KEY_IDS = "ids"
        private const val KEY_PREFIX = "item_"
    }
}

internal object MemoryCodec {
    fun encode(item: MemoryItem): String = listOf(item.id, item.scope, item.text)
        .joinToString(".") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

    fun decode(value: String): MemoryItem? = runCatching {
        val parts = value.split('.')
        require(parts.size == 3)
        MemoryItem(
            String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8),
            String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8),
            String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
        )
    }.getOrNull()
}
