package cl.iac33.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiTextSanitizerTest {
    @Test
    fun cuts_prompt_template_at_stop_sequence() {
        val clean = AiTextSanitizer.sanitize(
            "Respuesta correcta\nASSISTANT: leaked template\nUSER: leaked user text"
        )
        assertEquals("Respuesta correcta", clean)
        assertFalse(clean.contains("ASSISTANT:"))
        assertFalse(clean.contains("USER:"))
    }

    @Test
    fun removes_template_and_diagnostic_lines() {
        val clean = AiTextSanitizer.sanitize(
            "Respuesta\nSYSTEM: secret\nprovider: kilo\nHTTP 503\nContinuación"
        )
        assertEquals("Respuesta\nContinuación", clean)
    }
}
