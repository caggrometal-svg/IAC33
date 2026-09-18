package cl.iac33.app.ai

enum class AiIntent {
    LOCATION, SEISMIC, MULTIMEDIA, CONNECTIVITY, CONFIG, STATUS, GREETING, GENERAL
}

object IntentClassifier {
    fun classify(text: String): AiIntent {
        val normalized = text.trim().lowercase()
        return when {
            Regex("\\b(gps|ubicaci[oó]n|coordenadas|mapa)\\b").containsMatchIn(normalized) -> AiIntent.LOCATION
            Regex("\\b(sismo|sismos|terremoto|magnitud|epicentro|tsunami|r[eé]plica)\\b").containsMatchIn(normalized) -> AiIntent.SEISMIC
            Regex("\\b(multimedia|video|v[ií]deo|foto|imagen|editar|recortar)\\b").containsMatchIn(normalized) -> AiIntent.MULTIMEDIA
            Regex("\\b(red|internet|conectividad|offline|online|servidor|render|backend)\\b").containsMatchIn(normalized) -> AiIntent.CONNECTIVITY
            Regex("\\b(config|configuraci[oó]n|ajustes|preferencias)\\b").containsMatchIn(normalized) -> AiIntent.CONFIG
            Regex("\\b(estado|status|funciona|funcionando|diagn[oó]stico)\\b").containsMatchIn(normalized) -> AiIntent.STATUS
            Regex("^(hola|holi|buenas|hey|hello)\\b").containsMatchIn(normalized) -> AiIntent.GREETING
            else -> AiIntent.GENERAL
        }
    }
}
