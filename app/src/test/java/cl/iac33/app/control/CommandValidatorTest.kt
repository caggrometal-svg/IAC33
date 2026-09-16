package cl.iac33.app.control

import cl.iac33.app.core.RemoteCommand
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandValidatorTest {
    @Test fun rejects_expired_command() {
        val result = CommandValidator { 1000L }.validate(RemoteCommand("1", "SYNC", "{}", "key", 999L))
        assertTrue(result is cl.iac33.app.core.OperationResult.Failure)
    }
    @Test fun accepts_valid_command() {
        val result = CommandValidator { 1000L }.validate(RemoteCommand("1", "SYNC", "{}", "key", 2000L))
        assertTrue(result is cl.iac33.app.core.OperationResult.Success)
    }
}
