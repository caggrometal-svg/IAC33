package cl.iac33.app.ai

import cl.iac33.app.core.AiMessage
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.OperationResult
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertTrue

class AiEngineImplTest {
    @Test
    fun default_engine_works_without_external_provider() = runTest {
        val request = AiRequest("test", listOf(AiMessage("user", "hola")))
        val result = AiEngineImpl().generate(request)
        assertTrue(result is OperationResult.Success)
    }
}
