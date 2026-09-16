package cl.iac33.app.update

import cl.iac33.app.core.UpdateInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateValidatorTest {
    @Test fun accepts_valid_manifest_identity() {
        val info = UpdateInfo("r1", "0.1.1", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 10)
        assertTrue(UpdateValidator.validate(info) is cl.iac33.app.core.OperationResult.Success)
    }
    @Test fun rejects_invalid_digest() {
        val info = UpdateInfo("r1", "0.1.1", "bad", 10)
        assertTrue(UpdateValidator.validate(info) is cl.iac33.app.core.OperationResult.Failure)
    }
}
