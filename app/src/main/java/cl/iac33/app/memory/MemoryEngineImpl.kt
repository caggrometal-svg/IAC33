package cl.iac33.app.memory

import android.content.Context
import android.util.Base64
import cl.iac33.app.core.MemoryEngine
import cl.iac33.app.core.MemoryItem
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

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
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
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
        .joinToString(".") { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }

    fun decode(value: String): MemoryItem? = runCatching {
        val parts = value.split('.')
        require(parts.size == 3)
        MemoryItem(
            String(Base64.decode(parts[0], Base64.DEFAULT), Charsets.UTF_8),
            String(Base64.decode(parts[1], Base64.DEFAULT), Charsets.UTF_8),
            String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
        )
    }.getOrNull()
}
