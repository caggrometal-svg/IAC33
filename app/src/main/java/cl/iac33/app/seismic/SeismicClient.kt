package cl.iac33.app.seismic

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SeismicEvent(
    val id: String,
    val source: String,
    val occurredAtLocal: String,
    val place: String,
    val depthKm: Double,
    val magnitude: Double,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class SeismicEstimate(
    val magnitudeThreshold: Double,
    val annualRate: Double,
    val expected7d: Double,
    val probability7d: Double,
    val expected30d: Double,
    val probability30d: Double
)

data class SeismicForecast(
    val method: String,
    val interpretation: String,
    val historyYears: Int,
    val completenessMagnitude: Double,
    val sampleCount: Int,
    val bValue: Double?,
    val estimates: List<SeismicEstimate>
)

data class SeismicSnapshot(
    val cached: Boolean,
    val fetchedAt: Long,
    val events: List<SeismicEvent>,
    val mapEvents: List<SeismicEvent>,
    val forecast: SeismicForecast?
)

class SeismicClient(private val baseUrl: String) {
    suspend fun latest(): Result<SeismicSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(baseUrl.trimEnd('/') + "/v1/seismic/latest").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) error("HTTP " + status)
                val root = JSONObject(body)
                if (!root.optBoolean("ok", false)) error(root.optString("error", "SEISMIC_UNAVAILABLE"))

                val events = parseEvents(root.optJSONArray("events"))
                val mapEvents = parseEvents(root.optJSONArray("mapEvents"))

                val forecastJson = root.optJSONObject("forecast")
                val forecast = forecastJson?.let { parseForecast(it) }

                SeismicSnapshot(
                    cached = root.optBoolean("cached", false),
                    fetchedAt = root.optLong("fetchedAt", 0L),
                    events = events,
                    mapEvents = mapEvents,
                    forecast = forecast
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseEvents(array: org.json.JSONArray?): List<SeismicEvent> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    SeismicEvent(
                        id = item.getString("id"),
                        source = item.optString("source", "CSN"),
                        occurredAtLocal = item.optString("occurredAtLocal", ""),
                        place = item.optString("place", "Ubicación desconocida"),
                        depthKm = item.optDouble("depthKm", 0.0),
                        magnitude = item.optDouble("magnitude", 0.0),
                        latitude = item.optDouble("latitude", Double.NaN).takeUnless { it.isNaN() },
                        longitude = item.optDouble("longitude", Double.NaN).takeUnless { it.isNaN() }
                    )
                )
            }
        }
    }

    private fun parseForecast(json: JSONObject): SeismicForecast {
        val estimates = buildList {
            val array = json.optJSONArray("estimates") ?: return@buildList
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    SeismicEstimate(
                        magnitudeThreshold = item.optDouble("magnitudeThreshold"),
                        annualRate = item.optDouble("annualRate"),
                        expected7d = item.optDouble("expected7d"),
                        probability7d = item.optDouble("probability7d"),
                        expected30d = item.optDouble("expected30d"),
                        probability30d = item.optDouble("probability30d")
                    )
                )
            }
        }
        return SeismicForecast(
            method = json.optString("method", "Gutenberg-Richter + Poisson"),
            interpretation = json.optString("interpretation", ""),
            historyYears = json.optInt("historyYears", 10),
            completenessMagnitude = json.optDouble("completenessMagnitude", 4.0),
            sampleCount = json.optInt("sampleCount", 0),
            bValue = json.optDouble("bValue", Double.NaN).takeUnless { it.isNaN() },
            estimates = estimates
        )
    }
}
