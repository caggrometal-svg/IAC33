package cl.iac33.app.seismic

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SeismicEvent(
    val id: String,
    val source: String,
    val occurredAtLocal: String,
    val place: String,
    val depthKm: Double,
    val magnitude: Double
)

data class SeismicSnapshot(
    val cached: Boolean,
    val fetchedAt: Long,
    val events: List<SeismicEvent>
)

class SeismicClient(private val baseUrl: String) {
    suspend fun latest(): Result<SeismicSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(baseUrl.trimEnd('/') + "/v1/seismic/latest").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                if (status !in 200..299) error("HTTP $status")
                val root = JSONObject(body)
                if (!root.optBoolean("ok", false)) error(root.optString("error", "SEISMIC_UNAVAILABLE"))
                val array = root.optJSONArray("events") ?: error("SEISMIC_EMPTY")
                val events = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(
                            SeismicEvent(
                                id = item.getString("id"),
                                source = item.optString("source", "CSN"),
                                occurredAtLocal = item.getString("occurredAtLocal"),
                                place = item.getString("place"),
                                depthKm = item.getDouble("depthKm"),
                                magnitude = item.getDouble("magnitude")
                            )
                        )
                    }
                }
                SeismicSnapshot(
                    cached = root.optBoolean("cached", false),
                    fetchedAt = root.optLong("fetchedAt", 0L),
                    events = events
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
