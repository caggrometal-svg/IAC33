package cl.iac33.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentClassifierTest {
    @Test
    fun classifies_local_capability_intents() {
        assertEquals(AiIntent.LOCATION, IntentClassifier.classify("muéstrame mi ubicación GPS"))
        assertEquals(AiIntent.SEISMIC, IntentClassifier.classify("analiza los sismos"))
        assertEquals(AiIntent.MULTIMEDIA, IntentClassifier.classify("abre el editor de video"))
        assertEquals(AiIntent.CONNECTIVITY, IntentClassifier.classify("cómo está la conectividad"))
        assertEquals(AiIntent.CONFIG, IntentClassifier.classify("abre configuración"))
    }

    @Test
    fun defaults_general_questions_to_remote_capable_route() {
        assertEquals(AiIntent.GENERAL, IntentClassifier.classify("explica la relatividad"))
    }
}
