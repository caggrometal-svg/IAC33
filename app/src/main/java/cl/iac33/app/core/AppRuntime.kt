package cl.iac33.app.core

/** Single process runtime state holder. State changes are explicit and observable. */
class AppRuntime : AppRuntimeContract {
    private var currentState: RuntimeState = RuntimeState.STARTING
    private val events = mutableListOf<DiagnosticEvent>()

    @Synchronized
    override fun state(): RuntimeState = currentState

    @Synchronized
    override fun diagnostics(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun transition(next: RuntimeState, diagnostic: DiagnosticEvent? = null) {
        currentState = next
        diagnostic?.let(events::add)
    }

    @Synchronized
    fun record(event: DiagnosticEvent) {
        events.add(event)
    }
}
