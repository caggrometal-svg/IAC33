package cl.iac33.app.ota

const val OTA_ALGORITHM = "SHA256withECDSA"
const val OTA_KEY_ID = "iac33-bridge-ecdsa-v1"

data class OtaManifest(
    val schemaVersion: Int,
    val releaseId: String,
    val appVersion: String,
    val createdAt: String,
    val minimumSupportedVersion: String,
    val artifactRef: String,
    val artifactSha256: String,
    val artifactSize: Long,
    val algorithm: String,
    val signatureBase64: String,
    val keyId: String,
    val rollbackRef: String
) {
    fun isContractValid(): Boolean =
        schemaVersion == 1 &&
            releaseId.isNotBlank() &&
            appVersion.isNotBlank() &&
            createdAt.isNotBlank() &&
            minimumSupportedVersion.isNotBlank() &&
            artifactRef.isNotBlank() &&
            artifactSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            artifactSize > 0L &&
            algorithm == OTA_ALGORITHM &&
            signatureBase64.isNotBlank() &&
            keyId == OTA_KEY_ID &&
            rollbackRef.isNotBlank()
}

enum class OtaStage {
    CHECK, DOWNLOAD, SIZE_CHECK, DIGEST_CHECK, SIGNATURE_CHECK, STAGE, SELF_TEST, APPLY, HEALTH_CHECK, CONFIRM, ROLLBACK
}

data class OtaState(val releaseId: String?, val stage: OtaStage, val previousReleaseId: String? = null)
