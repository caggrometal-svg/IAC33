package cl.iac33.app.control

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceControlE2ETest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/devices/enroll" -> MockResponse().setResponseCode(201).setBody("{\"ok\":true}")
                request.path == "/v1/device/commands/claim-next" -> MockResponse().setResponseCode(200).setBody("{\"command\":{\"id\":\"e2e-command\",\"type\":\"PING\",\"payload\":{},\"idempotency_key\":\"e2e-key\",\"expires_at\":\"2099-01-01T00:00:00Z\",\"status\":\"CLAIMED\"}}")
                request.path == "/v1/device/commands/e2e-command/execute" -> MockResponse().setResponseCode(200).setBody("{\"command\":{\"id\":\"e2e-command\",\"status\":\"EXECUTING\"}}")
                request.path == "/v1/device/commands/e2e-command/succeed" -> MockResponse().setResponseCode(200).setBody("{\"command\":{\"id\":\"e2e-command\",\"status\":\"SUCCEEDED\"}}")
                else -> MockResponse().setResponseCode(404).setBody("{\"error\":\"NOT_FOUND\"}")
            }
        }
        server.start()
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun androidKeystore_drives_enrollment_claim_execute_ack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = DeviceIdentity(context)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("Android Keystore key must exist", keyStore.containsAlias("iac33-device-p256"))
        assertNotNull("Keystore private key must exist", keyStore.getKey("iac33-device-p256", null))

        val publicPem = identity.publicKeyPem()
        val publicDer = Base64.getDecoder().decode(publicPem.lines().filter { !it.startsWith("---") }.joinToString(""))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDer))
        val engine = RemoteControlEngine(context, server.url("/").toString().removeSuffix("/"), identity)

        val enrollment = kotlinx.coroutines.runBlocking { engine.enroll("test-pairing-token") }
        assertTrue("Enrollment must succeed", enrollment is cl.iac33.app.core.OperationResult.Success)
        val result = kotlinx.coroutines.runBlocking { engine.pollOnce() }
        assertTrue("Command must finish successfully", result is cl.iac33.app.core.OperationResult.Success)
        assertEquals("ACK:PING", (result as cl.iac33.app.core.OperationResult.Success).value)

        val enrollRequest = server.takeRequest(2, TimeUnit.SECONDS)
        val claimRequest = server.takeRequest(2, TimeUnit.SECONDS)
        val executeRequest = server.takeRequest(2, TimeUnit.SECONDS)
        val ackRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(enrollRequest); assertNotNull(claimRequest); assertNotNull(executeRequest); assertNotNull(ackRequest)
        assertEquals("/v1/devices/enroll", enrollRequest!!.path)
        assertEquals("/v1/device/commands/claim-next", claimRequest!!.path)
        assertEquals("/v1/device/commands/e2e-command/execute", executeRequest!!.path)
        assertEquals("/v1/device/commands/e2e-command/succeed", ackRequest!!.path)
        verifySignature(claimRequest, publicKey)
        verifySignature(executeRequest, publicKey)
        verifySignature(ackRequest, publicKey)
    }

    private fun verifySignature(request: RecordedRequest, publicKey: java.security.PublicKey) {
        val deviceId = request.getHeader("X-Device-Id")
        val timestamp = request.getHeader("X-Device-Timestamp")
        val nonce = request.getHeader("X-Device-Nonce")
        val signatureB64 = request.getHeader("X-Device-Signature")
        assertNotNull(deviceId); assertNotNull(timestamp); assertNotNull(nonce); assertNotNull(signatureB64)
        val body = request.body.readUtf8()
        val canonical = listOf("POST", request.path!!, timestamp!!, nonce!!, body).joinToString("\n")
        val signatureBytes = Base64.getDecoder().decode(signatureB64)
        assertTrue("ECDSA signature must be DER encoded", signatureBytes.size >= 8 && signatureBytes[0].toInt() == 0x30)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical.toByteArray(Charsets.UTF_8))
        assertTrue("Android Keystore signature must verify", verifier.verify(signatureBytes))
    }
}
