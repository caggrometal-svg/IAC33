package cl.iac33.app.ai

import cl.iac33.app.core.OperationContext
import cl.iac33.app.core.OperationResult
import kotlin.test.Test
import kotlin.test.assertTrue

class AiEngineImplTest {
    @Test fun default_engine_works_without_external_provider() {
        val result = AiEngineImpl().generate("hola", OperationContext("test"))
        assertTrue(result is OperationResult.Success)
    }
}
