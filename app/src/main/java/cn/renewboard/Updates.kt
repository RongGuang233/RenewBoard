package cn.renewboard

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks public release metadata only; installing remains an explicit user action. */
data class AppUpdate(val version: String, val notes: String, val newer: Boolean)
object Updates {
    const val RELEASE_PAGE = "https://github.com/RongGuang233/RenewBoard/releases/latest"
    const val API_URL = "https://api.github.com/repos/RongGuang233/RenewBoard/releases/latest"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private fun versionParts(value: String): List<Int> {
        val clean = value.removePrefix("v")
        require(clean.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "无法识别发布版本" }
        return clean.split('.').map { it.toInt() }
    }
    fun isNewer(remote: String, current: String): Boolean {
        val r = versionParts(remote); val c = versionParts(current)
        return r.zip(c).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }
    fun parse(body: String, current: String): AppUpdate {
        val release = Json.parseToJsonElement(body).jsonObject
        require(release["draft"]?.jsonPrimitive?.booleanOrNull == false && release["prerelease"]?.jsonPrimitive?.booleanOrNull == false) { "尚无可用正式版本" }
        val tag = release.getValue("tag_name").jsonPrimitive.content
        return AppUpdate(tag.removePrefix("v"), release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), isNewer(tag, current))
    }
    fun check(current: String, http: OkHttpClient = client, endpoint: String = API_URL): AppUpdate {
        val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json").header("User-Agent", "RenewBoard/$current").build()
        return http.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> throw IOException("暂未发布正式版本")
                403, 429 -> throw IOException("检查过于频繁，请稍后重试")
            }
            if (!response.isSuccessful) throw IOException("检查失败，请稍后重试")
            parse(response.body?.string() ?: throw IOException("无法读取版本信息"), current)
        }
    }
}
