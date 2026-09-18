package cl.iac33.app.ai

import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult

class LocalFallbackProvider : AiProvider {
    override val id = "local-fallback"
    override val model = "iac33-local-assistant"

    override fun isAvailable() = true

    override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
        val text = request.messages.lastOrNull { it.role == "user" }?.content?.trim()
            ?: return OperationResult.Failure(OperationError.VALIDATION, "No user message")

        val normalized = text.lowercase()
        val answer = when {
            normalized.matches(Regex("hola|holi|buenas|hey|hello.*")) ->
                "Hola. Soy el asistente local de IAC33. Puedo ayudarte con IA, sismicidad, GPS, mapas, multimedia, conectividad y configuración."

            normalized.contains("sismo") || normalized.contains("terremoto") ->
                "El módulo Sismicidad consulta eventos recientes, los ubica en el mapa y calcula una estimación estadística por tasa histórica. Esa estimación no determina una fecha ni predice un terremoto concreto."

            normalized.contains("gps") || normalized.contains("ubicación") || normalized.contains("ubicacion") ->
                "En GPS puedes conceder el permiso de ubicación, ver coordenadas y centrar el mapa sobre la posición actual o conocida."

            normalized.contains("multimedia") || normalized.contains("video") || normalized.contains("vídeo") || normalized.contains("foto") || normalized.contains("imagen") ->
                "En Editor Multimedia puedes abrir una imagen o vídeo. Las imágenes permiten rotación y blanco/negro; los vídeos permiten seleccionar inicio y fin y generar un recorte local."

            normalized.contains("config") ->
                "Configuración guarda preferencias de IA, actualización de sismicidad, magnitud mínima, zoom del mapa y vibración."

            normalized.contains("estado") || normalized.contains("funciona") ->
                "IAC33 está diseñado con funcionamiento local de respaldo, conexión al backend cuando hay red, memoria local y módulos independientes para GPS, sismicidad, multimedia y control."

            else ->
                "Estoy en respaldo local. Puedo resolver tareas básicas de IAC33 sin conexión y mantener disponible el acceso a GPS, sismicidad, multimedia, conectividad, estado y configuración. Para preguntas generales que requieren conocimiento externo o información actualizada, IAC33 intentará el nodo remoto automáticamente cuando exista red."
        }

        return OperationResult.Success(
            AiResult(
                provider = id,
                model = model,
                text = answer,
                latencyMs = 0L
            )
        )
    }
}
