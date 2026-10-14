package cn.renewboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class DeepSeekBalanceTest {
    private val now = Instant.parse("2026-10-14T01:02:03Z")
    private fun response(total: String = "110.00", granted: String = "10.00", topped: String = "100.00", currency: String = "CNY") =
        """{"is_available":false,"balance_infos":[{"currency":"$currency","total_balance":"$total","granted_balance":"$granted","topped_up_balance":"$topped"}]}"""

    @Test fun zeroAndUnavailableRemainValidAndTotalIncludesGift() {
        assertEquals("0.00", parseDeepSeekBalance(response("0.00", "0", "0"), "CNY", now).totalBalance)
        val snapshot = parseDeepSeekBalance(response(), "CNY", now)
        assertEquals("110.00", snapshot.totalBalance)
        assertEquals("10.00", snapshot.grantedBalance)
        assertEquals("100.00", snapshot.toppedUpBalance)
        assertEquals(now, snapshot.queriedAt)
    }

    @Test fun matchesCurrencyExactlyRatherThanConvertingOrChoosingFirst() {
        val usd = response("20", "5", "15", "USD")
        assertEquals("20", parseDeepSeekBalance(usd, "USD", now).totalBalance)
        val error = runCatching { parseDeepSeekBalance(usd, "CNY", now) }.exceptionOrNull()
        assertTrue(error is PlatformBalanceException)
        assertTrue(error!!.message!!.contains("CNY"))
        val mixed = """{"balance_infos":[{"currency":"USD","total_balance":"20","granted_balance":"0","topped_up_balance":"20"},{"currency":"CNY","total_balance":"8","granted_balance":"3","topped_up_balance":"5"}]}"""
        assertEquals("8", parseDeepSeekBalance(mixed, "CNY", now).totalBalance)
    }

    @Test fun malformedNegativeAndNumericAmountsAreRejectedWithoutResponseLeak() {
        listOf("secret-key", "{}", response("-1"), response("NaN"), response().replace("\"110.00\"", "110.00"))
            .forEach { body ->
                val error = runCatching { parseDeepSeekBalance(body, "CNY", now) }.exceptionOrNull()
                assertEquals("平台余额响应异常，请稍后重试", error?.message)
                assertNull(error?.cause)
            }
    }

    private fun client(server: MockWebServer, observe: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            observe(chain.request())
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()

    @Test fun officialEndpointAndBearerHeaderAreUsed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response()))
            val query = DeepSeekBalanceQuery(client(server) {
                assertEquals("https://api.deepseek.com/user/balance", it.url.toString())
                assertEquals("GET", it.method)
                assertEquals("Bearer test-not-a-real-key", it.header("Authorization"))
            }) { now }
            assertEquals("110.00", query.query(" test-not-a-real-key ", "CNY").totalBalance)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/user/balance", request.path)
            assertEquals("Bearer test-not-a-real-key", request.getHeader("Authorization"))
        }
    }

    @Test fun failureAndRedirectDoNotLeakBodyOrFollowRedirect() = runBlocking {
        MockWebServer().use { server ->
            val query = DeepSeekBalanceQuery(client(server)) { now }
            listOf(401, 403, 429, 500, 302).forEach { code ->
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/leak"))
                    .setBody("raw response test-not-a-real-key"))
                val error = runCatching { query.query("test-not-a-real-key", "CNY") }.exceptionOrNull()
                assertTrue(error is PlatformBalanceException)
                assertFalse(error!!.message!!.contains("test-not-a-real-key"))
                assertFalse(error.message!!.contains("raw response"))
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun disconnectDoesNotRetryAndCancellationCancelsCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val query = DeepSeekBalanceQuery(client(server)) { now }
            val error = runCatching { query.query("test-key", "CNY") }.exceptionOrNull()
            assertEquals("无法连接平台，请检查网络后重试", error?.message)
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = client(server)
            val query = DeepSeekBalanceQuery(http) { now }
            val task = async { query.query("test-key", "CNY") }
            // Yield to start the asynchronous request, then cancel without waiting for a network timeout.
            kotlinx.coroutines.yield()
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            task.cancel()
            try { task.await(); fail("Expected cancellation") } catch(_: CancellationException) { }
            assertTrue(http.dispatcher.runningCalls().all { it.isCanceled() })
        }
    }
}
