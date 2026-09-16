package cl.iac33.app.ota

/**
 * Deterministic OTA pipeline for a downloaded immutable artifact.
 * Transport and Android package installation are deliberately outside this class.
 * No artifact reaches STAGE/APPLY unless contract, size, digest and signature pass.
 */
class OtaPipeline(private val publicKeyBase64: String) {
    private var state = OtaState(null, OtaStage.CHECK)

    fun currentState(): OtaState = state

    fun verifyAndStage(manifest: OtaManifest, artifact: ByteArray): Result<OtaState> {
        state = OtaState(manifest.releaseId, OtaStage.CHECK, state.releaseId)
        if (!manifest.isContractValid()) return fail("Invalid OTA manifest contract")

        state = state.copy(stage = OtaStage.DOWNLOAD)
        state = state.copy(stage = OtaStage.SIZE_CHECK)
        if (!OtaVerifier.verifySize(artifact, manifest.artifactSize)) return fail("OTA artifact size mismatch")

        state = state.copy(stage = OtaStage.DIGEST_CHECK)
        if (!OtaVerifier.verifyDigest(artifact, manifest.artifactSha256)) return fail("OTA artifact digest mismatch")

        state = state.copy(stage = OtaStage.SIGNATURE_CHECK)
        if (!OtaVerifier.verifySignature(artifact, manifest.signatureBase64, publicKeyBase64)) {
            return fail("OTA artifact signature invalid")
        }

        state = state.copy(stage = OtaStage.STAGE)
        return Result.success(state)
    }

    fun markSelfTestPassed(): Result<OtaState> {
        if (state.stage != OtaStage.STAGE) return Result.failure(IllegalStateException("OTA self-test out of order"))
        state = state.copy(stage = OtaStage.SELF_TEST)
        return Result.success(state)
    }

    fun markApplied(): Result<OtaState> {
        if (state.stage != OtaStage.SELF_TEST) return Result.failure(IllegalStateException("OTA apply out of order"))
        state = state.copy(stage = OtaStage.APPLY)
        return Result.success(state)
    }

    fun markHealthConfirmed(): Result<OtaState> {
        if (state.stage != OtaStage.APPLY) return Result.failure(IllegalStateException("OTA health check out of order"))
        state = state.copy(stage = OtaStage.HEALTH_CHECK)
        return Result.success(state)
    }

    fun confirm(): Result<OtaState> {
        if (state.stage != OtaStage.HEALTH_CHECK) return Result.failure(IllegalStateException("OTA confirmation out of order"))
        state = state.copy(stage = OtaStage.CONFIRM)
        return Result.success(state)
    }

    fun rollback(): OtaState {
        state = state.copy(stage = OtaStage.ROLLBACK)
        return state
    }

    private fun fail(message: String): Result<OtaState> {
        state = state.copy(stage = OtaStage.ROLLBACK)
        return Result.failure(IllegalStateException(message))
    }
}
