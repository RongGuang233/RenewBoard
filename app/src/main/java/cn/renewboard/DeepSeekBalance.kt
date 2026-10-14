package cn.renewboard

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface PlatformBalanceQuery {
    suspend fun query(apiKey: String, currency: String): DeepSeekBalanceSnapshot
}

internal data class DeepSeekBalanceSnapshot(
    val currency: String,
    val totalBalance: String,
    val grantedBalance: String,
    val toppedUpBalance: String,
    val queriedAt: Instant
)

internal class PlatformBalanceException(message: String): IOException(message)

/** The only production destination is DeepSeek's official account balance endpoint. */
internal class DeepSeekBalanceQuery(
    client: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now
): PlatformBalanceQuery {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun query(apiKey: String, currency: String): DeepSeekBalanceSnapshot {
        val key = apiKey.trim()
        if(key.isEmpty() || key.any { it <= ' ' || it > '~' })
            throw PlatformBalanceException("请填写有效的 API 密钥")
        val request = Request.Builder().url("https://api.deepseek.com/user/balance")
            .header("Authorization", "Bearer $key").header("Accept", "application/json").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if(continuation.isActive)
                        continuation.resumeWithException(PlatformBalanceException("无法连接平台，请检查网络后重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if(!it.isSuccessful) throw PlatformBalanceException(when(it.code) {
                                401, 403 -> "API 密钥无效或无权查询，请更换密钥"
                                429 -> "查询过于频繁，请稍后重试"
                                in 500..599 -> "平台暂时不可用，请稍后重试"
                                else -> "余额查询失败，请稍后重试"
                            })
                            parseDeepSeekBalance(it.body?.string().orEmpty(), currency, now())
                        }
                    }
                    if(continuation.isActive) result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(
                            if(it is PlatformBalanceException) it else PlatformBalanceException("平台余额响应异常，请稍后重试")) })
                }
            })
        }
    }
}

internal fun parseDeepSeekBalance(body: String, currency: String, queriedAt: Instant): DeepSeekBalanceSnapshot {
    try {
        val entries = Json.parseToJsonElement(body).jsonObject.getValue("balance_infos").jsonArray
        val matches = entries.map { it.jsonObject }.filter {
            (it["currency"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content == currency
        }
        if(matches.isEmpty()) throw PlatformBalanceException("平台未返回账户的 $currency 余额，请检查账户币种")
        if(matches.size != 1) throw PlatformBalanceException("平台余额响应异常，请稍后重试")
        val info = matches.single()
        fun amount(name: String): String {
            val field = info.getValue(name) as? JsonPrimitive
            if(field == null || !field.isString) throw IllegalArgumentException()
            val value = BigDecimal(field.content)
            if(value.signum() < 0) throw IllegalArgumentException()
            return value.toPlainString()
        }
        return DeepSeekBalanceSnapshot(currency, amount("total_balance"), amount("granted_balance"),
            amount("topped_up_balance"), queriedAt)
    } catch(e: PlatformBalanceException) {
        throw e
    } catch(e: Exception) {
        throw PlatformBalanceException("平台余额响应异常，请稍后重试")
    }
}
