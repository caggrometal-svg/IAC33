package cl.iac33.app.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaPipelineTest {
    private val bytes = "IAC33-OTA".toByteArray()

    private fun manifest(signature: String = "bad") = OtaManifest(
        schemaVersion = 1,
        releaseId = "release-2",
        appVersion = "0.1.2",
        createdAt = "2026-09-16T00:00:00Z",
        minimumSupportedVersion = "0.1.0",
        artifactRef = "https://example.invalid/iac33.apk",
        artifactSha256 = OtaVerifier.sha256(bytes),
        artifactSize = bytes.size.toLong(),
        algorithm = "SHA256withECDSA",
        signatureBase64 = signature,
        keyId = "iac33-ota-v1",
        rollbackRef = "release-1"
    )

    @Test
    fun invalid_signature_never_reaches_stage() {
        val pipeline = OtaPipeline("bad")
        val result = pipeline.verifyAndStage(manifest(), bytes)
        assertTrue(result.isFailure)
        assertEquals(OtaStage.ROLLBACK, pipeline.currentState().stage)
    }

    @Test
    fun invalid_digest_rolls_back() {
        val pipeline = OtaPipeline("bad")
        val result = pipeline.verifyAndStage(manifest().copy(artifactSha256 = "0".repeat(64)), bytes)
        assertTrue(result.isFailure)
        assertEquals(OtaStage.ROLLBACK, pipeline.currentState().stage)
    }

    @Test
    fun invalid_manifest_rolls_back_before_artifact_checks() {
        val pipeline = OtaPipeline("bad")
        val result = pipeline.verifyAndStage(manifest().copy(algorithm = "SHA256"), bytes)
        assertTrue(result.isFailure)
        assertEquals(OtaStage.ROLLBACK, pipeline.currentState().stage)
    }

    @Test
    fun apply_confirmation_order_is_enforced() {
        val pipeline = OtaPipeline("bad")
        assertTrue(pipeline.markSelfTestPassed().isFailure)
        assertTrue(pipeline.markApplied().isFailure)
        assertTrue(pipeline.markHealthConfirmed().isFailure)
        assertTrue(pipeline.confirm().isFailure)
        assertEquals(OtaStage.CHECK, pipeline.currentState().stage)
    }
}
