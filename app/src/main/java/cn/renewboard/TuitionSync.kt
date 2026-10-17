package cn.renewboard

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val TUITION_MAX_BYTES = 2_000_000
internal class TuitionSyncException(message: String): IOException(message)

@Serializable internal data class TuitionConnection(val url: String, val token: String, val encryptionKey: String)

internal fun validateTuitionConnection(connection: TuitionConnection) {
    val url = connection.url.toHttpUrlOrNull()
    require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
        url.query == null && url.fragment == null) { "请填写不含账号、查询参数或片段的 HTTPS 同步地址" }
    require(connection.token.isNotBlank() && connection.token.length <= 4096 &&
        connection.token.all { it > ' ' && it <= '~' }) { "请填写有效的读取令牌" }
    decodeTuitionKey(connection.encryptionKey)
}

internal fun decodeTuitionKey(key: String): ByteArray = try {
    Base64.getDecoder().decode(key).also { require(it.size == 32) }
} catch(_: Exception) { throw TuitionSyncException("加密密钥应为发布端生成的 32 字节 Base64 密钥") }

/** The relay never receives the AES key or the clear student summary. */
internal object TuitionCrypto {
    @Serializable private data class Envelope(val version: Int, val nonce: String, val ciphertext: String)
    private val json = Json { ignoreUnknownKeys = false }
    fun decrypt(body: ByteArray, key: String): TuitionSnapshot {
        val secret = decodeTuitionKey(key)
        try {
            require(body.size <= TUITION_MAX_BYTES)
            val envelope = json.decodeFromString<Envelope>(body.toString(Charsets.UTF_8))
            require(envelope.version == 1)
            val nonce = Base64.getDecoder().decode(envelope.nonce)
            val encrypted = Base64.getDecoder().decode(envelope.ciphertext)
            require(nonce.size == 12 && encrypted.size >= 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("RenewBoard tuition v1".toByteArray(Charsets.UTF_8))
            }
            val plaintext = cipher.doFinal(encrypted)
            val text = plaintext.toString(Charsets.UTF_8)
            require(json.parseToJsonElement(text).jsonObject.keys.containsAll(listOf("version", "publishedAt", "students")))
            val snapshot = json.decodeFromString<TuitionSnapshot>(text)
            Assets.validateTuition(snapshot)
            return snapshot
        } catch(_: Exception) {
            throw TuitionSyncException("学费文件无法验证，请核对密钥，或在 LessonManager 重新发布")
        } finally { secret.fill(0) }
    }
}

internal fun interface TuitionQuery { suspend fun download(connection: TuitionConnection): TuitionSnapshot }

internal class HttpTuitionQuery(client: OkHttpClient = OkHttpClient()): TuitionQuery {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun download(connection: TuitionConnection): TuitionSnapshot {
        validateTuitionConnection(connection)
        val request = Request.Builder().url(connection.url)
            .header("Authorization", "Bearer ${connection.token}").header("Accept", "application/json")
            .header("Cache-Control", "no-cache").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if(continuation.isActive) continuation.resumeWithException(TuitionSyncException("无法读取学费余额，请检查网络后重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if(!it.isSuccessful) throw TuitionSyncException(when(it.code) {
                                401, 403 -> "读取令牌无效或无权访问，请检查连接设置"
                                404 -> "尚无学费文件，请先在 LessonManager 发布"
                                else -> "学费同步服务暂时不可用，请稍后重试"
                            })
                            val body = it.body ?: throw TuitionSyncException("服务器未返回学费文件")
                            require(body.contentLength() <= TUITION_MAX_BYTES)
                            val bytes = body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while(true) {
                                    val count = input.read(buffer)
                                    if(count < 0) break
                                    require(output.size() + count <= TUITION_MAX_BYTES)
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            TuitionCrypto.decrypt(bytes, connection.encryptionKey)
                        }
                    }
                    if(continuation.isActive) result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(if(it is TuitionSyncException) it else
                            TuitionSyncException("学费文件格式异常或超过 2 MB，请在 LessonManager 重新发布")) })
                }
            })
        }
    }
}
