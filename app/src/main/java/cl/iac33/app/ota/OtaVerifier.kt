package cl.iac33.app.ota

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object OtaVerifier {
    fun sha256(bytes: ByteArray): String =
        bytes.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyDigest(bytes: ByteArray, expectedHex: String): Boolean =
        expectedHex.equals(sha256(bytes), ignoreCase = true)

    fun verifyDigest(file: File, expectedHex: String): Boolean =
        runCatching { FileInputStream(file).use { expectedHex.equals(sha256(it), ignoreCase = true) } }.getOrDefault(false)

    fun verifySize(bytes: ByteArray, expectedSize: Long): Boolean =
        bytes.size.toLong() == expectedSize

    fun verifySize(file: File, expectedSize: Long): Boolean =
        file.isFile && file.length() == expectedSize

    fun verifySignature(bytes: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean =
        bytes.inputStream().use { verifySignature(it, signatureBase64, publicKeyBase64) }

    fun verifySignature(input: InputStream, signatureBase64: String, publicKeyBase64: String): Boolean = runCatching {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            verifier.update(buffer, 0, read)
        }
        verifier.verify(Base64.getDecoder().decode(signatureBase64))
    }.getOrDefault(false)

    fun verifyArtifact(bytes: ByteArray, manifest: OtaManifest, publicKeyBase64: String): Boolean =
        manifest.isContractValid() &&
            verifySize(bytes, manifest.artifactSize) &&
            verifyDigest(bytes, manifest.artifactSha256) &&
            verifySignature(bytes, manifest.signatureBase64, publicKeyBase64)

    fun verifyArtifact(file: File, manifest: OtaManifest, publicKeyBase64: String): Boolean =
        manifest.isContractValid() &&
            verifySize(file, manifest.artifactSize) &&
            verifyDigest(file, manifest.artifactSha256) &&
            FileInputStream(file).use { verifySignature(it, manifest.signatureBase64, publicKeyBase64) }

    private const val BUFFER_SIZE = 64 * 1024
}
