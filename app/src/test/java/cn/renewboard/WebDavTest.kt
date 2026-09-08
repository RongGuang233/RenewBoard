package cn.renewboard

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class WebDavTest {
    private lateinit var server: MockWebServer
    private lateinit var dav: WebDav
    private val files = Collections.synchronizedMap(linkedMapOf<String, String>())
    private val lastModified = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val propertyStatus = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private var downloadedOverride: String? = null
    private var putStatus = 201
    private var moveStatus = 201
    private var deleteStatus = 204
    private val ledger = Ledger(
        plans = listOf(Plan(id = "package", name = "影音套餐", amount = "12.50", currency = "USD", billingAnchor = "2026-01-31")),
        benefits = listOf(
            Benefit(id = "video", planId = "package", name = "视频会员", anchor = "2026-02-28", renewals = 1, giftDays = 3),
            Benefit(id = "music", planId = "package", name = "音乐会员", anchor = "2026-03-15", renewals = 1)
        ),
        payments = listOf(Payment(id = "receipt", planId = "package", planName = "影音套餐", amount = "12.50", currency = "USD", date = "2026-01-31", note = "套餐首付", benefitIds = listOf("video", "music"))),
        settings = Settings(reminderDays = listOf(7, 3, 0), rates = mapOf("USD" to "7.1234"))
    )

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                val method = requireNotNull(request.method)
                calls.add(method to path)
                if (request.getHeader("Authorization") != Credentials.basic("test-user", "test-password")) return MockResponse().setResponseCode(401)
                val name = requireNotNull(request.requestUrl).pathSegments.last()
                return when (method) {
                    "MKCOL" -> MockResponse().setResponseCode(405)
                    "PUT" -> {
                        if (putStatus in 200..299) files[name] = request.body.readUtf8()
                        MockResponse().setResponseCode(putStatus)
                    }
                    "GET" -> (downloadedOverride ?: files[name])?.let { MockResponse().setResponseCode(200).setBody(it) }
                        ?: MockResponse().setResponseCode(404)
                    "PROPFIND" -> {
                        if (request.getHeader("Depth") != "1") return MockResponse().setResponseCode(400)
                        if (request.getHeader("Content-Type")?.startsWith("application/xml") != true || !request.body.readUtf8().contains("getlastmodified")) return MockResponse().setResponseCode(400)
                        val names = synchronized(files) { files.keys.toList() }
                        MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(multistatus(names))
                    }
                    "MOVE" -> {
                        val destination = request.getHeader("Destination")?.toHttpUrl()
                            ?: return MockResponse().setResponseCode(400)
                        if (request.getHeader("Overwrite") != "F" || destination.encodedPath.substringBeforeLast('/') != "/dav/RenewBoard") return MockResponse().setResponseCode(400)
                        val target = destination.pathSegments.last()
                        if (files.containsKey(target)) return MockResponse().setResponseCode(412)
                        if (moveStatus == 201) files[target] = files.remove(name) ?: return MockResponse().setResponseCode(404)
                        MockResponse().setResponseCode(moveStatus)
                    }
                    "DELETE" -> { if (deleteStatus == 204) files.remove(name); MockResponse().setResponseCode(deleteStatus) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
        dav = WebDav(server.url("/dav/").toString(), "test-user", "test-password")
    }

    @After fun stop() { server.shutdown() }
    private fun automatic(i: Int) = "auto-202001${i.toString().padStart(2, '0')}T000000000Z-00000000-0000-0000-0000-000000000000.json"
    private fun manual(i: Int) = "manual-00000000-0000-0000-0000-${i.toString().padStart(12, '0')}.json"
    private fun pending(at: Instant, i: Int) = "pending-${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(at)}-00000000-0000-0000-0000-${i.toString().padStart(12, '0')}.json"
    private fun multistatus(names: List<String>) = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/RenewBoard/</d:href></d:response>${names.joinToString("") { name ->
        val modified = lastModified[name]?.let { "<d:propstat><d:prop><d:getlastmodified>$it</d:getlastmodified></d:prop><d:status>${propertyStatus[name] ?: "HTTP/1.1 200 OK"}</d:status></d:propstat>" }.orEmpty()
        "<d:response><d:href>/dav/RenewBoard/$name</d:href>$modified</d:response>"
    }}</d:multistatus>"""

    @Test fun manualUploadUsesRealPutAndVerifyingGetWithoutPruning() {
        (1..12).forEach { files[automatic(it)] = Book.encode(ledger) }
        val name = dav.upload(ledger, auto = false)
        assertTrue(name.startsWith("manual-"))
        assertEquals(ledger, Book.decode(requireNotNull(files[name])).data)
        assertEquals(listOf("MKCOL", "PUT", "GET", "MOVE", "PROPFIND"), calls.map { it.first })
        assertTrue(calls[1].second.startsWith("/dav/RenewBoard/pending-"))
        assertEquals(calls[1].second, calls[2].second)
        assertEquals(calls[1].second, calls[3].second)
        assertEquals(13, files.size)
    }

    @Test fun automaticUploadRetainsNewestTenAndNeverDeletesManualBackups() {
        (1..12).forEach { files[automatic(it)] = Book.encode(ledger) }
        // Server modification time can change when an old backup is copied or restored remotely.
        lastModified[automatic(1)] = "Thu, 1 Jan 2099 00:00:00 GMT"
        (1..3).forEach { files[manual(it)] = Book.encode(ledger) }
        val name = dav.upload(ledger, auto = true)
        assertTrue(files.containsKey(name))
        assertEquals(10, files.keys.count { it.startsWith("auto-") })
        assertEquals(3, files.keys.count { it.startsWith("manual-") })
        (1..3).forEach { assertFalse(files.containsKey(automatic(it))) }
        (4..12).forEach { assertTrue(files.containsKey(automatic(it))) }
        assertEquals(listOf("MKCOL", "PUT", "GET", "MOVE", "PROPFIND", "DELETE", "DELETE", "DELETE"), calls.map { it.first })
        assertEquals(setOf(automatic(1), automatic(2), automatic(3)), calls.filter { it.first == "DELETE" }.map { it.second.substringAfterLast('/') }.toSet())
    }

    @Test fun propfindReturnsRecognizedFilesAndDownloadDecodesStoredBackup() {
        val name = manual(1)
        files[name] = Book.encode(ledger)
        files["unrelated.json"] = "{}"
        assertEquals(listOf(name), dav.list().map { it.name })
        assertEquals(ledger, dav.download(name).data)
        assertEquals(listOf("PROPFIND", "GET"), calls.map { it.first })
    }

    @Test fun listingOrdersManualAndAutomaticBackupsByServerTimeWithoutDownloads() {
        listOf(manual(9), automatic(12), manual(1)).forEach { files[it] = "not downloaded" }
        lastModified[manual(9)] = "Mon, 7 Sep 2026 12:00:00 GMT"
        lastModified[manual(1)] = "Tue, 8 Sep 2026 08:30:00 GMT"
        lastModified[automatic(12)] = "Tue, 8 Sep 2026 08:00:00 GMT"

        val backups = dav.list()

        assertEquals(listOf(manual(1), automatic(12), manual(9)), backups.map { it.name })
        assertEquals(listOf(false, true, false), backups.map { it.automatic })
        assertEquals(Instant.parse("2026-09-08T08:30:00Z"), backups.first().time)
        assertEquals(Instant.parse("2026-09-08T08:00:00Z"), backups[1].time)
        assertEquals(listOf("PROPFIND"), calls.map { it.first })
    }

    @Test fun listingPlacesMissingInvalidAndUnsuccessfulDatesLastInStableNameOrder() {
        listOf(manual(3), manual(2), manual(1), manual(4)).forEach { files[it] = "not downloaded" }
        lastModified[manual(2)] = "not a date"
        lastModified[manual(3)] = "Tue, 8 Sep 2026 08:00:00 GMT"
        propertyStatus[manual(3)] = "HTTP/1.1 404 Not Found"
        lastModified[manual(4)] = "Tue, 8 Sep 2026 08:00:00 GMT"

        val backups = dav.list()

        assertEquals(listOf(manual(4), manual(1), manual(2), manual(3)), backups.map { it.name })
        assertTrue(backups.drop(1).all { it.time == null })
        assertEquals(listOf("PROPFIND"), calls.map { it.first })
    }

    @Test fun legacyAutomaticNamesSupplyDatesWhenServerMetadataIsMissingOrInvalid() {
        listOf(automatic(1), manual(1), automatic(12), automatic(32)).forEach { files[it] = "not downloaded" }
        lastModified[automatic(12)] = "invalid"

        val backups = dav.list()

        assertEquals(listOf(automatic(12), automatic(1), automatic(32), manual(1)), backups.map { it.name })
        assertEquals(Instant.parse("2020-01-12T00:00:00Z"), backups[0].time)
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), backups[1].time)
        assertNull(backups[2].time)
        assertNull(backups[3].time)
        assertEquals(listOf("PROPFIND"), calls.map { it.first })
    }

    @Test fun corruptDownloadFailsInsteadOfReturningEmptyLedger() {
        files[manual(1)] = "broken json"
        assertThrows(Exception::class.java) { dav.download(manual(1)) }
    }

    @Test fun corruptVerificationStopsBeforeListingOrDeletingBackups() {
        (1..12).forEach { files[automatic(it)] = Book.encode(ledger) }
        downloadedOverride = "broken json"
        assertThrows(Exception::class.java) { dav.upload(ledger, auto = true) }
        assertEquals(listOf("MKCOL", "PUT", "GET", "DELETE"), calls.map { it.first })
        assertEquals(12, files.size)
        (1..12).forEach { assertTrue(files.containsKey(automatic(it))) }
    }

    @Test fun validButDifferentVerificationFailsBeforePruning() {
        downloadedOverride = Book.encode(Ledger(settings = Settings(listOf(7))))
        assertThrows(DavException::class.java) { dav.upload(ledger, auto = true) }
        assertEquals(listOf("MKCOL", "PUT", "GET", "DELETE"), calls.map { it.first })
        assertTrue(files.isEmpty())
    }

    @Test fun unsuccessfulPutDoesNotAttemptVerificationOrPruning() {
        putStatus = 507
        assertThrows(DavException::class.java) { dav.upload(ledger, auto = true) }
        assertEquals(listOf("MKCOL", "PUT", "DELETE"), calls.map { it.first })
        assertTrue(files.isEmpty())
    }

    @Test fun missingBackupIsAnError() {
        assertThrows(DavException::class.java) { dav.download(manual(1)) }
    }

    @Test fun failedMoveDoesNotPublishOrPruneOldSuccessfulBackups() {
        (1..12).forEach { files[automatic(it)] = Book.encode(ledger) }
        moveStatus = 405
        assertThrows(DavException::class.java) { dav.upload(ledger, auto = true) }
        assertEquals(listOf("MKCOL", "PUT", "GET", "MOVE", "DELETE"), calls.map { it.first })
        assertEquals((1..12).map(::automatic).toSet(), files.keys.toSet())
    }

    @Test fun failedCleanupLeavesPendingOutsideSuccessfulBackupCount() {
        (1..10).forEach { files[automatic(it)] = Book.encode(ledger) }
        downloadedOverride = "broken json"
        deleteStatus = 503
        assertThrows(Exception::class.java) { dav.upload(ledger, auto = true) }
        assertEquals(1, files.keys.count { it.startsWith("pending-") })
        assertEquals(10, dav.list().size)
        downloadedOverride = null
        deleteStatus = 204
        dav.upload(ledger, auto = true)
        assertEquals(10, dav.list().count { it.automatic })
        assertFalse(files.containsKey(automatic(1)))
        (2..10).forEach { assertTrue(files.containsKey(automatic(it))) }
    }

    @Test fun normalListingCleansOldPendingFilesInBoundedBatchesOnly() {
        val old = Instant.now().minusSeconds(2 * 86400)
        (1..23).forEach { files[pending(old, it)] = "incomplete" }
        val recent = pending(Instant.now(), 24)
        files[recent] = "active upload"
        files[manual(1)] = Book.encode(ledger)
        files["pending-user-document.json"] = "unrelated"
        assertEquals(listOf(manual(1)), dav.list().map { it.name })
        assertEquals(20, calls.count { it.first == "DELETE" })
        assertEquals(4, files.keys.count { it.startsWith("pending-") && it != "pending-user-document.json" })
        assertEquals(listOf(manual(1)), dav.list().map { it.name })
        assertEquals(23, calls.count { it.first == "DELETE" })
        assertTrue(files.containsKey(recent))
        assertTrue(files.containsKey("pending-user-document.json"))
        assertTrue(files.containsKey(manual(1)))
    }
}
