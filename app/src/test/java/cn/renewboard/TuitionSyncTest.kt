package cn.renewboard

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TuitionSyncTest {
    private val keyBytes = ByteArray(32) { it.toByte() }
    private val key = Base64.getEncoder().encodeToString(keyBytes)
    private val connection get() = TuitionConnection("https://example.invalid/tuition", "test-read-token", key)
    private val summary = """{"version":1,"publishedAt":"2026-10-16T01:02:03Z","students":[{"id":"student-a","name":"测试学生","status":"active","balanceCents":120000,"verified":true}]}"""
    private fun encrypt(plaintext: String = summary): ByteArray {
        val nonce = ByteArray(12) { (it + 20).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            updateAAD("RenewBoard tuition v1".toByteArray())
        }
        val data = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray()))
        return """{"version":1,"nonce":"${Base64.getEncoder().encodeToString(nonce)}","ciphertext":"$data"}""".toByteArray()
    }
    // Produced by LessonManager's Python cryptography AESGCM, shared in tools/tuition-relay/test-vector.json.
    @Test fun pythonPublisherVectorDecryptsOnAndroidJvm() {
        val envelope = """{"version":1,"nonce":"AAECAwQFBgcICQoL","ciphertext":"PCCgfreWq3TjY626ncsIGOG67keYHjs9TEXfpy9ZMoQsIZ7RnvZGqEKeT92ytxgW3mlQvWrm+fgTtVltbYeQgIRP5ECIqgQIeHaQTJz7e4xd5L1PAgYgHNDMvOBantjgrtpjxEL0Q/Ixidsf7pK14JhLQNiXpFHvPt1t7+OFGmfNNyLqlrWqP2mPK8R+vyK2RdbZBAIho3q8c8oBn6CAiPry+jfTi74Lwh7xgf3p4pmn7LlW/s8W0hHytKICsZrBMWColGyws+sNdDVKGqltPQbaQpKDi5jdU+LupjU9/Nh5AaTOk1NmQXBrmmrUfR0/y/b6EPNE+octRlRDu6xyrTanl+L0vCFbE/1IYdZBSW+n6bdQeEIV+T48ogzXlD7hrJgl"}"""
        val result = TuitionCrypto.decrypt(envelope.toByteArray(), key)
        assertEquals(170000L, result.students[0].balanceCents)
        assertEquals("测试学生", result.students[0].name)
        assertEquals(-30000L, result.students[1].balanceCents)
        assertFalse(result.students[1].verified)
    }
    @Test fun authenticatedSummaryPreservesCentsAndUnicode() {
        val result = TuitionCrypto.decrypt(encrypt(), key)
        assertEquals("测试学生", result.students.single().name)
        assertEquals(120000L, result.students.single().balanceCents)
    }
    @Test fun tamperingWrongKeyAndUnsupportedVersionAreRejected() {
        val encrypted = encrypt().toString(Charsets.UTF_8)
        listOf(encrypted.replace("\"version\":1", "\"version\":2"),
            encrypted.replace("\"nonce\":\"", "\"nonce\":\"A"), "{}").forEach {
            assertTrue(runCatching { TuitionCrypto.decrypt(it.toByteArray(), key) }.exceptionOrNull() is TuitionSyncException)
        }
        val wrong = Base64.getEncoder().encodeToString(ByteArray(32) { 99 })
        assertTrue(runCatching { TuitionCrypto.decrypt(encrypt(), wrong) }.isFailure)
        val bytes = encrypt(); bytes[bytes.size - 5] = if(bytes[bytes.size - 5] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
        assertTrue(runCatching { TuitionCrypto.decrypt(bytes, key) }.isFailure)
    }
    @Test fun invalidPlaintextDoesNotLeakStudentOrCredentials() {
        listOf(summary.replace("120000", "1.5"), summary.replace("active", "unknown"),
            summary.replace("2026-10-16", "2200-01-01"), summary.replace("\"students\":", "\"unexpected\":")).forEach {
            val error = runCatching { TuitionCrypto.decrypt(encrypt(it), key) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("测试学生")); assertFalse(error.message!!.contains(key)); assertNull(error.cause)
        }
    }
    @Test fun connectionRequiresHttpsAndSeparateValidKey() {
        validateTuitionConnection(connection)
        listOf("http://example.invalid/tuition", "https://user:password@example.invalid/tuition", "https://example.invalid/tuition?key=secret", "https://example.invalid/tuition#secret").forEach {
            assertTrue(runCatching { validateTuitionConnection(connection.copy(url = it)) }.isFailure)
        }
        assertTrue(runCatching { validateTuitionConnection(connection.copy(token="bad\nheader")) }.isFailure)
        assertTrue(runCatching { validateTuitionConnection(connection.copy(encryptionKey="wrong")) }.isFailure)
    }
    private fun client(server: MockWebServer) = OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("https://example.invalid/tuition", chain.request().url.toString())
        assertEquals("Bearer test-read-token", chain.request().header("Authorization"))
        assertFalse(chain.request().headers.toString().contains(key))
        chain.proceed(chain.request().newBuilder().url(server.url("/tuition")).build())
    }.build()
    @Test fun downloadSendsOnlyReadTokenAndDecryptsLocally() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(encrypt().toString(Charsets.UTF_8)))
            assertEquals(120000L, HttpTuitionQuery(client(server)).download(connection).students.single().balanceCents)
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals(0L, request.bodySize)
        }
    }
    @Test fun serverErrorsAndRedirectsDoNotOverwriteOrExposeResponse() = runBlocking {
        MockWebServer().use { server ->
            val query = HttpTuitionQuery(client(server))
            listOf(401,403,404,500,302).forEach { status ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location",server.url("/other")).setBody("private server text $key"))
                val error = runCatching { query.download(connection) }.exceptionOrNull()!!
                assertTrue(error is TuitionSyncException); assertFalse(error.message!!.contains("private")); assertFalse(error.message!!.contains(key))
            }
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun oversizedResponseWithAndWithoutLengthIsBounded() = runBlocking {
        MockWebServer().use { server ->
            val query = HttpTuitionQuery(client(server))
            server.enqueue(MockResponse().setBody("x".repeat(TUITION_MAX_BYTES + 1)))
            assertTrue(runCatching { query.download(connection) }.isFailure)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(TUITION_MAX_BYTES + 1),8192))
            assertTrue(runCatching { query.download(connection) }.isFailure)
        }
    }
}
