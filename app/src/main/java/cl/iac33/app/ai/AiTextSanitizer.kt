package cl.iac33.app.ai

object AiTextSanitizer {
    val stopSequences = listOf("\nUSER:", "\nASSISTANT:")

    fun sanitize(raw: String?): String {
        var text = raw.orEmpty()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")

        val lower = text.lowercase()
        val stopIndex = stopSequences
            .mapNotNull { stop -> lower.indexOf(stop.lowercase()).takeIf { it >= 0 } }
            .minOrNull()
        if (stopIndex != null) text = text.substring(0, stopIndex)

        return text
            .lines()
            .map { it.trimEnd() }
            .filterNot { line ->
                val normalized = line.trim()
                normalized.matches(Regex("(?i)^(system|user|assistant|developer|tool)\\s*:.*")) ||
                    normalized.matches(Regex("(?i)^(provider\\s*(·|:)|http\\s+[45]\\d\\d|ai_providers_unavailable|modo\\s+local\\s+activo|respaldo\\s+local\\s+activado).*"))
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(12_000)
    }
}
