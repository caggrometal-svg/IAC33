package cl.iac33.app.seismic

import cl.iac33.app.core.SeismicEvent
import cl.iac33.app.core.SeismicQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SeismicEngineTest {
    private val events = listOf(
        SeismicEvent("a", 3.0, 10.0, -38.7, -73.2, "test", 1000),
        SeismicEvent("b", 5.0, 20.0, -39.0, -73.0, "test", 2000)
    )

    @Test fun filters_and_orders_events() = runBlocking {
        val result = InMemorySeismicEngine(events).events(SeismicQuery(minMagnitude = 4.0))
        val values = (result as cl.iac33.app.core.OperationResult.Success).value
        assertEquals(listOf("b"), values.map { it.id })
    }

    @Test fun analyzer_returns_b_value() {
        assertNotNull(SeismicAnalyzer.bValue(events, 3.0))
    }
}
