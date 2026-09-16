package cl.iac33.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuntimeTest {
    @Test
    fun runtimeTransitionsAndRecordsDiagnostics() {
        val runtime = AppRuntime()
        assertEquals(RuntimeState.STARTING, runtime.state())

        runtime.transition(
            RuntimeState.READY,
            DiagnosticEvent(code = "CORE_READY", message = "Core initialized")
        )

        assertEquals(RuntimeState.READY, runtime.state())
        assertEquals(1, runtime.diagnostics().size)
        assertTrue(runtime.diagnostics().single().code == "CORE_READY")
    }
}
