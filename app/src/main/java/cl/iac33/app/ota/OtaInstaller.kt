package cl.iac33.app.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

class OtaInstaller(private val context: Context) {
    fun stageVerifiedArtifact(manifest: OtaManifest, artifact: ByteArray, publicKeyBase64: String): File {
        require(OtaVerifier.verifyArtifact(artifact, manifest, publicKeyBase64)) { "OTA artifact verification failed" }
        return stageVerifiedFile(manifest, artifact.inputStream(), publicKeyBase64, manifest.artifactSize)
    }

    fun stageVerifiedArtifact(manifest: OtaManifest, artifact: File, publicKeyBase64: String): File {
        require(OtaVerifier.verifyArtifact(artifact, manifest, publicKeyBase64)) { "OTA artifact verification failed" }
        return stageVerifiedFile(manifest, artifact.inputStream().buffered(), publicKeyBase64, artifact.length())
    }

    private fun stageVerifiedFile(
        manifest: OtaManifest,
        source: java.io.InputStream,
        publicKeyBase64: String,
        expectedSize: Long
    ): File {
        val directory = File(context.cacheDir, "ota").apply { mkdirs() }
        val safeName = manifest.releaseId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val file = File(directory, safeName)
        source.use { input ->
            file.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
        }
        check(file.length() == expectedSize) { "Staged OTA size mismatch" }
        check(OtaVerifier.verifyArtifact(file, manifest, publicKeyBase64)) { "Staged OTA verification failed" }
        return file
    }

    fun launchInstaller(stagedApk: File) {
        require(stagedApk.isFile) { "Staged APK not found" }
        if (!context.packageManager.canRequestPackageInstalls()) {
            throw SecurityException("UNKNOWN_SOURCES_INSTALL_PERMISSION_REQUIRED")
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", stagedApk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun cleanup(manifest: OtaManifest) {
        val safeName = manifest.releaseId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        File(context.cacheDir, "ota/$safeName").delete()
    }
}
