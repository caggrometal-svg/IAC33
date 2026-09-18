package cl.iac33.app.ota

import android.content.Context
import cl.iac33.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Executes a remotely requested OTA after strict manifest/artifact verification. */
class OtaCommandExecutor(private val context: Context) {
    fun execute(payload: String, beforeInstall: ((OtaManifest) -> Unit)? = null): String {
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
        require(compareOtaVersions(BuildConfig.VERSION_NAME, manifest.minimumSupportedVersion) >= 0) {
            "OTA core version below manifest minimum"
        }
        require(compareOtaVersions(manifest.appVersion, BuildConfig.VERSION_NAME) > 0) {
            "OTA replay or downgrade rejected"
        }
        require(manifest.artifactSize <= MAX_ARTIFACT_BYTES) { "OTA artifact exceeds safety limit" }
        require(BuildConfig.IAC33_OTA_PUBLIC_KEY_B64.isNotBlank()) { "OTA trust key not configured" }

        val artifact = download(manifest.artifactRef, manifest.artifactSize)
        try {
            val pipeline = OtaPipeline(BuildConfig.IAC33_OTA_PUBLIC_KEY_B64)
            pipeline.verifyAndStage(manifest, artifact).getOrThrow()

            if (root.optBoolean("dryRun", false)) {
                pipeline.markSelfTestPassed().getOrThrow()
                return "OTA_VERIFIED:${manifest.releaseId}"
            }

            val installer = OtaInstaller(context)
            val staged = installer.stageVerifiedArtifact(manifest, artifact, BuildConfig.IAC33_OTA_PUBLIC_KEY_B64)
            beforeInstall?.invoke(manifest)
            try {
                installer.launchInstaller(staged)
            } catch (error: Exception) {
                beforeInstall?.let { _ -> PendingOtaStore(context).clear() }
                pipeline.rollback()
                throw error
            }
            return "OTA_INSTALL_REQUESTED:${manifest.releaseId}"
        } finally {
            artifact.delete()
        }
    }

    private fun download(ref: String, expectedSize: Long): File {
        var current = java.net.URI(ref)
        val directory = File(context.cacheDir, "ota-downloads").apply { mkdirs() }
        val file = File(directory, "download-${UUID.randomUUID()}.apk")
        try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                require(current.scheme.equals("https", ignoreCase = true)) { "OTA artifact must use HTTPS" }
                require(isTrustedArtifactHost(current.host)) { "OTA artifact host is not trusted" }

                val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                }

                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        require(hop < MAX_REDIRECTS) { "Too many OTA redirects" }
                        val location = connection.getHeaderField("Location")
                        require(!location.isNullOrBlank()) { "OTA redirect missing Location" }
                        current = current.resolve(location)
                        return@repeat
                    }

                    require(status in 200..299) { "OTA download HTTP $status" }
                    val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                    require(contentLength < 0L || contentLength == expectedSize) { "OTA Content-Length mismatch" }

                    connection.inputStream.use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= expectedSize) { "OTA artifact larger than manifest" }
                                output.write(buffer, 0, read)
                            }
                            require(total == expectedSize) { "OTA artifact size mismatch after download" }
                        }
                    }
                    require(file.length() == expectedSize) { "OTA artifact size mismatch after staging" }
                    return file
                } finally {
                    connection.disconnect()
                }
            }
            error("OTA redirect limit exceeded")
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun isTrustedArtifactHost(host: String?): Boolean =
        host != null && (
            host.equals("github.com", ignoreCase = true) ||
                host.equals("release-assets.githubusercontent.com", ignoreCase = true) ||
                host.equals("objects.githubusercontent.com", ignoreCase = true)
        )


    companion object {
        private const val MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L
        private const val MAX_REDIRECTS = 3
    }
}
