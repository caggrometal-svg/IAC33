package cl.iac33.app.seismic

import cl.iac33.app.core.SeismicEvent
import kotlin.math.log10

object SeismicAnalyzer {
    fun bValue(events: List<SeismicEvent>, minimumMagnitude: Double): Double? {
        val magnitudes = events.map { it.magnitude }.filter { it >= minimumMagnitude }
        if (magnitudes.isEmpty()) return null
        val mean = magnitudes.average()
        val correction = 0.05
        val denominator = mean - (minimumMagnitude - correction)
        return if (denominator > 0.0) log10(Math.E) / denominator else null
    }

    fun averageMagnitude(events: List<SeismicEvent>): Double? =
        events.takeIf { it.isNotEmpty() }?.map(SeismicEvent::magnitude)?.average()
}
