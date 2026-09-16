package cl.iac33.app.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Physical Android OTA executor.
 *
 * Android does not permit a regular application to silently replace itself.
 * This executor therefore performs the safe physical hand-off to PackageInstaller:
 * the artifact must already have passed OtaPipeline verification, is written to
 * app-private cache, and is exposed only through a FileProvider content URI.
 */
class OtaInstaller(private val context: Context) {
    fun stageVerifiedArtifact(manifest: OtaManifest, artifact: ByteArray): File {
        require(manifest.isContractValid()) { "Invalid OTA manifest contract" }
        require(OtaVerifier.verifySize(artifact, manifest.artifactSize)) { "OTA artifact size mismatch" }
        require(OtaVerifier.verifyDigest(artifact, manifest.artifactSha256)) { "OTA artifact digest mismatch" }

        val directory = File(context.cacheDir, "ota").apply { mkdirs() }
        val safeName = manifest.releaseId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val file = File(directory, safeName)
        file.outputStream().use { it.write(artifact) }
        check(file.length() == manifest.artifactSize) { "Staged OTA size mismatch" }
        check(OtaVerifier.verifyDigest(file.readBytes(), manifest.artifactSha256)) { "Staged OTA digest mismatch" }
        return file
    }

    fun launchInstaller(stagedApk: File) {
        require(stagedApk.isFile) { "Staged APK not found" }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            stagedApk
        )
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
