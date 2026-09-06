package cn.renewboard

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    @Test fun comparesNumbersRatherThanTextAndDoesNotDowngrade() {
        assertTrue(Updates.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Updates.isNewer("v2.0.0", "1.99.99"))
        assertFalse(Updates.isNewer("v1.1.0", "1.1.0"))
        assertFalse(Updates.isNewer("v1.0.0", "1.1.0"))
    }
    @Test fun releaseCheckReadsVersionNotesAndUsesPublicRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"修复与改进"}"""))
            val update=Updates.check("1.1.0",OkHttpClient(),server.url("/latest").toString())
            assertEquals(AppUpdate("1.2.0","修复与改进",true),update)
            val request=server.takeRequest();assertNull(request.getHeader("Authorization"));assertEquals("RenewBoard/1.1.0",request.getHeader("User-Agent"))
        }
    }
    @Test fun missingRateLimitedAndUnavailableReleasesAreErrors() {
        MockWebServer().use { server ->
            for(code in listOf(404,403,429,500)) {
                server.enqueue(MockResponse().setResponseCode(code))
                assertThrows(java.io.IOException::class.java) { Updates.check("1.1.0",OkHttpClient(),server.url("/latest").toString()) }
            }
        }
    }
    @Test fun malformedOrPreviewVersionIsNotAnUpdate() {
        for(body in listOf("{}", """{"tag_name":"v2.0.0-beta","draft":false,"prerelease":true}""", """{"tag_name":"bad","draft":false,"prerelease":false}""")) {
            assertThrows(Exception::class.java) { Updates.parse(body,"1.1.0") }
        }
    }
}
