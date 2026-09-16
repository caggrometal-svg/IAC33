package cl.iac33.app.ai

import cl.iac33.app.core.AiMessage
import cl.iac33.app.core.AiRequest
import cl.iac33.app.core.AiResult
import cl.iac33.app.core.OperationError
import cl.iac33.app.core.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRouterTest {
    private val request = AiRequest(
        conversationId = "test",
        messages = listOf(AiMessage("user", "hola"))
    )

    @Test
    fun fails_when_no_provider_is_configured() = runBlocking {
        val result = AiRouter(emptyList()).generate(request)
        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationError.PROVIDER, (result as OperationResult.Failure).error)
    }

    @Test
    fun skips_unavailable_provider_and_uses_next() = runBlocking {
        val first = FakeProvider("first", available = false)
        val second = FakeProvider("second", available = true)
        val result = AiRouter(listOf(first, second)).generate(request)
        assertTrue(result is OperationResult.Success)
        assertEquals("second", (result as OperationResult.Success).value.provider)
    }

    @Test
    fun fails_over_after_provider_error() = runBlocking {
        val first = FakeProvider("first", available = true, fail = true)
        val second = FakeProvider("second", available = true)
        val result = AiRouter(listOf(first, second)).generate(request)
        assertTrue(result is OperationResult.Success)
        assertEquals("second", (result as OperationResult.Success).value.provider)
    }

    private class FakeProvider(
        override val id: String,
        private val available: Boolean,
        private val fail: Boolean = false
    ) : AiProvider {
        override val model: String = "test-model"
        override fun isAvailable(): Boolean = available

        override suspend fun generate(request: AiRequest): OperationResult<AiResult> {
            if (fail) return OperationResult.Failure(OperationError.PROVIDER, "test failure")
            return OperationResult.Success(
                AiResult(id, model, "ok", 0L)
            )
        }
    }
}
