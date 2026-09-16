package cl.iac33.app.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class OtaPipelineTest {
    private val bytes = "IAC33-OTA".toByteArray()

    private fun signedFixture(): Pair<OtaManifest, String> {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(bytes)
        }.sign()
        val manifest = OtaManifest(
            schemaVersion = 1,
            releaseId = "release-2",
            appVersion = "0.1.2",
            createdAt = "2026-09-16T00:00:00Z",
            minimumSupportedVersion = "0.1.0",
            artifactRef = "https://example.invalid/iac33.apk",
            artifactSha256 = OtaVerifier.sha256(bytes),
            artifactSize = bytes.size.toLong(),
            algorithm = "SHA256withECDSA",
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
            keyId = "iac33-ota-v1",
            rollbackRef = "release-1"
        )
        return manifest to Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    @Test
    fun signed_artifact_reaches_stage_and_confirmation() {
        val (manifest, publicKey) = signedFixture()
        val pipeline = OtaPipeline(publicKey)
        assertTrue(pipeline.verifyAndStage(manifest, bytes).isSuccess)
        assertEquals(OtaStage.STAGE, pipeline.currentState().stage)
        assertTrue(pipeline.markSelfTestPassed().isSuccess)
        assertTrue(pipeline.markApplied().isSuccess)
        assertTrue(pipeline.markHealthConfirmed().isSuccess)
        assertTrue(pipeline.confirm().isSuccess)
        assertEquals(OtaStage.CONFIRM, pipeline.currentState().stage)
    }

    @Test
    fun invalid_signature_never_reaches_stage() {
        val (manifest, publicKey) = signedFixture()
        val pipeline = OtaPipeline(publicKey)
        val result = pipeline.verifyAndStage(manifest.copy(signatureBase64 = "bad"), bytes)
        assertTrue(result.isFailure)
        assertEquals(OtaStage.ROLLBACK, pipeline.currentState().stage)
    }

    @Test
    fun invalid_digest_rolls_back() {
        val (manifest, publicKey) = signedFixture()
        val pipeline = OtaPipeline(publicKey)
        val result = pipeline.verifyAndStage(manifest.copy(artifactSha256 = "0".repeat(64)), bytes)
        assertTrue(result.isFailure)
        assertEquals(OtaStage.ROLLBACK, pipeline.currentState().stage)
    }

    @Test
    fun invalid_manifest_rolls_back_before_artifact_checks() {
        val (manifest, publicKey) = signedFixture()
        val pipeline = OtaPipeline(publicKey)
        val result = pipeline.verifyAndStage(manifest.copy(algorithm = "SHA256"), bytes)
        assertTrue(result.isFailure)
        assertEquals(OtaStage.ROLLBACK, pipeline.currentState().stage)
    }

    @Test
    fun apply_confirmation_order_is_enforced() {
        val pipeline = OtaPipeline(publicKeyBase64 = "bad")
        assertTrue(pipeline.markSelfTestPassed().isFailure)
        assertTrue(pipeline.markApplied().isFailure)
        assertTrue(pipeline.markHealthConfirmed().isFailure)
        assertTrue(pipeline.confirm().isFailure)
        assertEquals(OtaStage.CHECK, pipeline.currentState().stage)
    }
}
