package cl.iac33.app.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaVerifierTest {
    private fun validManifest(bytes: ByteArray = "IAC33".toByteArray()) = OtaManifest(
        schemaVersion = 1,
        releaseId = "release-1",
        appVersion = "0.1.1",
        createdAt = "2026-09-16T00:00:00Z",
        minimumSupportedVersion = "0.1.0",
        artifactRef = "https://example.invalid/iac33.apk",
        artifactSha256 = OtaVerifier.sha256(bytes),
        artifactSize = bytes.size.toLong(),
        algorithm = "SHA256withECDSA",
        signatureBase64 = "signature",
        keyId = OTA_KEY_ID,
        rollbackRef = "release-0"
    )

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

    @Test
    fun manifest_contract_accepts_complete_manifest() {
        assertTrue(validManifest().isContractValid())
    }

    @Test
    fun manifest_contract_rejects_missing_required_fields() {
        val invalid = validManifest().copy(
            releaseId = "",
            artifactSha256 = "bad",
            artifactSize = 0L,
            keyId = ""
        )
        assertFalse(invalid.isContractValid())
    }

    @Test
    fun verify_artifact_rejects_invalid_manifest_before_crypto() {
        val bytes = "IAC33".toByteArray()
        val invalid = validManifest(bytes).copy(algorithm = "SHA256")
        assertFalse(OtaVerifier.verifyArtifact(bytes, invalid, "bad"))
    }
}
