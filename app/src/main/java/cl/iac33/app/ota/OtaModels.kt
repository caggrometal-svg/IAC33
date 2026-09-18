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
            appVersion.matches(Regex("\\d+(\\.\\d+){2,3}")) &&
            createdAt.isNotBlank() &&
            minimumSupportedVersion.matches(Regex("\\d+(\\.\\d+){2,3}")) &&
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

fun compareOtaVersions(left: String, right: String): Int {
    fun parse(value: String): List<Int> = value.split('.').map(String::toInt)
    val a = parse(left)
    val b = parse(right)
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}
