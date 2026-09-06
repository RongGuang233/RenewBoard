package cn.renewboard

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class DavException(message: String): IOException(message)
class WebDav(base: String, user: String, password: String, private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).followRedirects(false).build()) {
    private val root = base.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("RenewBoard").addPathSegment("").build()
    private val auth = Credentials.basic(user, password)
    private val automatic = Regex("auto-[0-9]{8}T[0-9]{9}Z-[a-f0-9-]{36}\\.json")
    private val manual = Regex("manual-[a-f0-9-]{36}\\.json")
    private val pending = Regex("pending-([0-9]{8}T[0-9]{9}Z)-[a-f0-9-]{36}\\.json")
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
    private fun request(method: String, name: String? = null, body: String? = null, destination: String? = null): Response {
        val url = if(name == null) root else root.newBuilder().addPathSegment(name).build()
        val builder = Request.Builder().url(url).header("Authorization", auth)
        if(method == "PROPFIND") builder.header("Depth", "1")
        if(destination != null) builder.header("Destination", root.newBuilder().addPathSegment(destination).build().toString()).header("Overwrite", "F")
        return client.newCall(builder.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()).execute()
    }
    private fun check(r: Response, allowed: Set<Int> = setOf(200,201,204)) { if(r.code !in allowed) throw DavException("WebDAV 返回 HTTP ${r.code}，请检查地址、应用密码与网络") }
    fun list(): List<String> {
        val names = listFiles()
        val cutoff = Instant.now().minusSeconds(86400)
        names.filter { name ->
            val match = pending.matchEntire(name)
            match != null && runCatching { Instant.from(timestamp.parse(match.groupValues[1])) < cutoff }.getOrDefault(false)
        }.sorted().take(20).forEach { name ->
            // An interrupted upload is disposable; failed cleanup is retried by the next normal listing.
            try { remove(name) } catch (_: IOException) { }
        }
        return names.filter { automatic.matches(it) || manual.matches(it) }
    }
    private fun listFiles(): List<String> {
        request("PROPFIND").use { r ->
            if(r.code == 404) return emptyList()
            check(r,setOf(207))
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            // No DTD is valid in a WebDAV multistatus response.
            val xml = r.body?.string() ?: throw DavException("服务器返回空列表")
            if(xml.contains("<!DOCTYPE",ignoreCase=true) || xml.contains("<!ENTITY",ignoreCase=true)) throw DavException("备份列表格式错误")
            val nodes = factory.newDocumentBuilder().parse(xml.byteInputStream()).getElementsByTagNameNS("*", "href")
            return (0 until nodes.length).mapNotNull { i ->
                val href = nodes.item(i).textContent
                val url = root.resolve(href) ?: return@mapNotNull null
                if(url.host != root.host || url.encodedPath.substringBeforeLast('/') != root.encodedPath.trimEnd('/')) null
                else url.pathSegments.last().takeIf { automatic.matches(it) || manual.matches(it) || pending.matches(it) }
            }.distinct().sortedDescending()
        }
    }
    private fun remove(name: String) { request("DELETE",name).use { check(it,setOf(200,204,404)) } }
    fun download(name: String): Backup {
        require(!name.contains('/') && name.endsWith(".json"))
        request("GET",name).use { r ->
            check(r,setOf(200))
            val stream = r.body?.byteStream() ?: throw DavException("备份为空")
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while(true) { val n = stream.read(chunk); if(n == -1) break; buffer.write(chunk,0,n); if(buffer.size() > 16*1024*1024) throw DavException("备份超过16MB") }
            return Book.decode(buffer.toString("UTF-8"))
        }
    }
    fun upload(l: Ledger, auto: Boolean): String {
        val encoded = Book.encode(l)
        request("MKCOL").use { check(it,setOf(201,405)) }
        val stamp = timestamp.format(Instant.now())
        val name = if(auto) "auto-$stamp-${newId()}.json" else "manual-${newId()}.json"
        val temporary = "pending-$stamp-${newId()}.json"
        try {
            request("PUT",temporary,encoded).use { check(it) }
            if(download(temporary).data != l) throw DavException("上传后核验不一致，保留本地数据")
            // Only a verified resource becomes a successful backup; MOVE is the publication boundary.
            request("MOVE",temporary,destination = name).use { check(it,setOf(201)) }
        } catch (failure: Exception) {
            try { remove(temporary) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        val successful = list()
        if(auto) successful.filter { automatic.matches(it) }.drop(10).forEach(::remove)
        return name
    }
}
