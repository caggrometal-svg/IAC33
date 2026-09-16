package cl.iac33.app.connectivity

import cl.iac33.app.core.ConnectivityState
import cl.iac33.app.core.OperationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultConnectivityEngineTest {
    @Test
    fun `probe failure transitions to offline`() = runTest {
        val engine = DefaultConnectivityEngine(probeUrl = "http://127.0.0.1:1")

        val result = engine.probe()

        assertEquals(ConnectivityState.OFFLINE, engine.state())
        assert(result is OperationResult.Failure)
    }

    @Test
    fun `initial state is unknown`() {
        val engine = DefaultConnectivityEngine(probeUrl = "http://127.0.0.1:1")
        assertEquals(ConnectivityState.UNKNOWN, engine.state())
    }
}
