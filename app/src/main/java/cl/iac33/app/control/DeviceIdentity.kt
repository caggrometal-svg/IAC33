package cl.iac33.app.control

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
        return Base64.getEncoder().encodeToString(signature.sign())
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

    private fun keyStore(): KeyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
}
