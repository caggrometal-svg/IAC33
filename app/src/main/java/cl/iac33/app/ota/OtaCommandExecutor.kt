package cl.iac33.app.ota

import android.content.Context
import cl.iac33.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Executes a remotely requested OTA after strict manifest/artifact verification. */
class OtaCommandExecutor(private val context: Context) {
    fun execute(payload: String): String {
        val root = JSONObject(payload)
        val manifestJson = root.getJSONObject("manifest")
        val manifest = OtaManifest(
            schemaVersion = manifestJson.getInt("schemaVersion"),
            releaseId = manifestJson.getString("releaseId"),
            appVersion = manifestJson.getString("appVersion"),
            createdAt = manifestJson.getString("createdAt"),
            minimumSupportedVersion = manifestJson.getString("minimumSupportedVersion"),
            artifactRef = manifestJson.getString("artifactRef"),
            artifactSha256 = manifestJson.getString("artifactSha256"),
            artifactSize = manifestJson.getLong("artifactSize"),
            algorithm = manifestJson.getString("algorithm"),
            signatureBase64 = manifestJson.getString("signatureBase64"),
            keyId = manifestJson.getString("keyId"),
            rollbackRef = manifestJson.getString("rollbackRef")
        )
        require(manifest.isContractValid()) { "Invalid OTA manifest contract" }
        require(manifest.artifactSize <= MAX_ARTIFACT_BYTES) { "OTA artifact exceeds safety limit" }
        require(BuildConfig.IAC33_OTA_PUBLIC_KEY_B64.isNotBlank()) { "OTA trust key not configured" }

        val artifact = download(manifest.artifactRef, manifest.artifactSize)
        val pipeline = OtaPipeline(BuildConfig.IAC33_OTA_PUBLIC_KEY_B64)
        pipeline.verifyAndStage(manifest, artifact).getOrThrow()
        pipeline.markSelfTestPassed().getOrThrow()

        // dryRun is used by automated E2E tests; production commands omit it.
        if (root.optBoolean("dryRun", false)) {
            return "OTA_VERIFIED:${manifest.releaseId}"
        }

        val installer = OtaInstaller(context)
        val staged = installer.stageVerifiedArtifact(manifest, artifact, BuildConfig.IAC33_OTA_PUBLIC_KEY_B64)
        installer.launchInstaller(staged)
        return "OTA_INSTALL_REQUESTED:${manifest.releaseId}"
    }

    private fun download(ref: String, expectedSize: Long): ByteArray {
        val url = URL(ref)
        require(url.protocol == "https" || url.protocol == "http") { "Unsupported OTA artifact protocol" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
        }
        return try {
            val status = connection.responseCode
            require(status in 200..299) { "OTA download HTTP $status" }
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            require(contentLength < 0L || contentLength == expectedSize) { "OTA Content-Length mismatch" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(expectedSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= expectedSize) { "OTA artifact larger than manifest" }
                    output.write(buffer, 0, read)
                }
                require(total == expectedSize) { "OTA artifact size mismatch after download" }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L
    }
}
