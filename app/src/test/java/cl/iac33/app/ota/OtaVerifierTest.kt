package cl.iac33.app.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaVerifierTest {
    @Test
    fun digest_and_size_are_verified() {
        val bytes = "IAC33".toByteArray()
        val digest = OtaVerifier.sha256(bytes)
        assertTrue(OtaVerifier.verifySize(bytes, 5))
        assertTrue(OtaVerifier.verifyDigest(bytes, digest))
        assertFalse(OtaVerifier.verifyDigest(bytes, "00$digest"))
    }

    @Test
    fun invalid_signature_fails_closed() {
        val bytes = "IAC33".toByteArray()
        assertFalse(OtaVerifier.verifySignature(bytes, "bad", "bad"))
    }
}
