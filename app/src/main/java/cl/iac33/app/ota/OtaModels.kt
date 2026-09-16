package cl.iac33.app.ota

data class OtaManifest(
    val releaseId: String,
    val versionCode: Long,
    val artifactSha256: String,
    val artifactSize: Long,
    val signatureBase64: String,
    val algorithm: String = "SHA256withECDSA"
)

enum class OtaStage {
    CHECK, DOWNLOAD, SIZE_CHECK, DIGEST_CHECK, SIGNATURE_CHECK, STAGE, SELF_TEST, APPLY, HEALTH_CHECK, CONFIRM, ROLLBACK
}

data class OtaState(val releaseId: String?, val stage: OtaStage, val previousReleaseId: String? = null)
