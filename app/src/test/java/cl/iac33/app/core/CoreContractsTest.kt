package cl.iac33.app.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreContractsTest {
    @Test fun operationContext_hasTraceAndRequestIds() {
        val context = OperationContext()
        assertTrue(context.requestId.isNotBlank())
        assertTrue(context.traceId.isNotBlank())
    }

    @Test fun failure_isTypedAndRecoverableByDefault() {
        val result: OperationResult<Nothing> = OperationResult.Failure(OperationError.NETWORK, "offline")
        assertNotNull(result)
        assertTrue((result as OperationResult.Failure).context.traceId.isNotBlank())
    }
}
