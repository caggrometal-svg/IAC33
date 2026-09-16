package cl.iac33.app.ota

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.security.MessageDigest

object OtaVerifier {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun verifyDigest(bytes: ByteArray, expectedHex: String): Boolean =
        expectedHex.equals(sha256(bytes), ignoreCase = true)

    fun verifySize(bytes: ByteArray, expectedSize: Long): Boolean = bytes.size.toLong() == expectedSize

    fun verifySignature(bytes: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = runCatching {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(bytes)
        verifier.verify(Base64.getDecoder().decode(signatureBase64))
    }.getOrDefault(false)

    fun verifyArtifact(bytes: ByteArray, manifest: OtaManifest, publicKeyBase64: String): Boolean =
        verifySize(bytes, manifest.artifactSize) &&
            verifyDigest(bytes, manifest.artifactSha256) &&
            manifest.algorithm == "SHA256withECDSA" &&
            verifySignature(bytes, manifest.signatureBase64, publicKeyBase64)
}
