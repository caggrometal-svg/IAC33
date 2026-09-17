package cl.iac33.app.control

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val appContext = context.applicationContext
    private val keyStoreName = "AndroidKeyStore"
    private val alias = "iac33-device-p256"
    private val prefs = appContext.getSharedPreferences("iac33_device", Context.MODE_PRIVATE)

    init { ensureKeyPair() }

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: error("DEVICE_ID_MISSING")

    val enrolled: Boolean
        get() = prefs.getBoolean("enrolled", false)

    fun publicKeyPem(): String {
        val certificate = keyStore().getCertificate(alias) ?: error("DEVICE_KEY_MISSING")
        val encoded = Base64.getEncoder().encodeToString(certificate.publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n${encoded.chunked(64).joinToString("\n")}\n-----END PUBLIC KEY-----"
    }

    fun sign(payload: ByteArray): String {
        val privateKey = keyStore().getKey(alias, null) ?: error("DEVICE_PRIVATE_KEY_MISSING")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(payload)
        val encoded = normalizeEcdsaSignature(signature.sign())
        return Base64.getEncoder().encodeToString(encoded)
    }

    fun markEnrolled(value: Boolean) {
        prefs.edit().putBoolean("enrolled", value).apply()
    }

    private fun ensureKeyPair() {
        val store = keyStore()
        if (store.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, keyStoreName)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
        prefs.edit().putString("device_id", "iac33-" + UUID.randomUUID().toString()).putBoolean("enrolled", false).apply()
    }

    private fun normalizeEcdsaSignature(signature: ByteArray): ByteArray {
        if (signature.size != 64 || signature[0].toInt() == 0x30) return signature
        val r = derInteger(signature.copyOfRange(0, 32))
        val s = derInteger(signature.copyOfRange(32, 64))
        val body = ByteArrayOutputStream().apply { write(0x02); write(r.size); write(r); write(0x02); write(s.size); write(s) }.toByteArray()
        return ByteArrayOutputStream().apply { write(0x30); write(body.size); write(body) }.toByteArray()
    }

    private fun derInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.lastIndex && value[start].toInt() == 0) start++
        val trimmed = value.copyOfRange(start, value.size)
        return if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
}
