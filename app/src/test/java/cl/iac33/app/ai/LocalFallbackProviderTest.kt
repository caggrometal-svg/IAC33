package cl.iac33.app.ai

import cl.iac33.app.core.AiMessage
import cl.iac33.app.core.AiRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFallbackProviderTest {
    @Test fun answers_without_external_provider() = runBlocking {
        val result = LocalFallbackProvider().generate(
            AiRequest("local", listOf(AiMessage("user", "hola")))
        )
        assertTrue(result is cl.iac33.app.core.OperationResult.Success)
    }
}
