package cl.iac33.app.control

import kotlin.test.Test
import kotlin.test.assertTrue
import cl.iac33.app.core.OperationResult

class CommandLifecycleTest {
    @Test fun lifecycle_reaches_success_only_through_execution() {
        val lifecycle = CommandLifecycle()
        assertTrue(lifecycle.create("c1", "k1") is OperationResult.Success)
        assertTrue(lifecycle.transition("c1", CommandState.CLAIMED) is OperationResult.Success)
        assertTrue(lifecycle.transition("c1", CommandState.EXECUTING) is OperationResult.Success)
        assertTrue(lifecycle.transition("c1", CommandState.SUCCEEDED) is OperationResult.Success)
        assertTrue(lifecycle.transition("c1", CommandState.PENDING) is OperationResult.Failure)
    }
}
