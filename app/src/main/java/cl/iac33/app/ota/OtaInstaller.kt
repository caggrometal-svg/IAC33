package cl.iac33.app.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Physical Android OTA executor.
 * A regular application cannot silently replace itself; Android controls the final install step.
 */
class OtaInstaller(private val context: Context) {
    fun stageVerifiedArtifact(manifest: OtaManifest, artifact: ByteArray, publicKeyBase64: String): File {
        require(OtaVerifier.verifyArtifact(artifact, manifest, publicKeyBase64)) { "OTA artifact verification failed" }

        val directory = File(context.cacheDir, "ota").apply { mkdirs() }
        val safeName = manifest.releaseId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val file = File(directory, safeName)
        file.outputStream().use { it.write(artifact) }
        check(file.length() == manifest.artifactSize) { "Staged OTA size mismatch" }
        check(OtaVerifier.verifyArtifact(file.readBytes(), manifest, publicKeyBase64)) { "Staged OTA verification failed" }
        return file
    }

    fun launchInstaller(stagedApk: File) {
        require(stagedApk.isFile) { "Staged APK not found" }
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
