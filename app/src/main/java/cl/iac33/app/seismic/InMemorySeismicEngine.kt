package cl.iac33.app.seismic

import cl.iac33.app.core.OperationResult
import cl.iac33.app.core.SeismicEngine
import cl.iac33.app.core.SeismicEvent
import cl.iac33.app.core.SeismicQuery

class InMemorySeismicEngine(initial: List<SeismicEvent> = emptyList()) : SeismicEngine {
    private val data = initial.toMutableList()
    override suspend fun events(query: SeismicQuery): OperationResult<List<SeismicEvent>> =
        OperationResult.Success(data.filter { event ->
            (query.fromMs == null || event.timestampMs >= query.fromMs) &&
            (query.toMs == null || event.timestampMs <= query.toMs) &&
            (query.minMagnitude == null || event.magnitude >= query.minMagnitude)
        }.sortedByDescending { it.timestampMs })

    fun replace(events: List<SeismicEvent>) { data.clear(); data.addAll(events) }
}
