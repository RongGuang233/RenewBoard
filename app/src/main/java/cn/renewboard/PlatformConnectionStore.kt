package cn.renewboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PlatformConnection(val provider: String = "deepseek", val snapshot: DeepSeekBalanceSnapshot)

/** Kept outside Ledger and its backup; each encrypted record is bound to the immutable plan id. */
internal class PlatformConnectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("platform-connections", Context.MODE_PRIVATE)
    private val json = Json

    @Serializable private data class Record(val apiKey: String, val currency: String, val total: String,
        val granted: String, val toppedUp: String, val queriedAt: String)

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("renewboard.platform.deepseek", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("renewboard.platform.deepseek", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    private companion object { val keyLock = Any() }

    private fun record(planId: String): Record? {
        val encoded = prefs.getString(planId, null) ?: return null
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(planId.toByteArray(Charsets.UTF_8))
            }
            return json.decodeFromString<Record>(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch(e: Exception) {
            throw PlatformBalanceException("无法读取平台连接，请重新连接")
        }
    }

    fun connection(planId: String): PlatformConnection? = try {
        record(planId)?.let {
            PlatformConnection(snapshot = DeepSeekBalanceSnapshot(it.currency, it.total, it.granted, it.toppedUp, Instant.parse(it.queriedAt)))
        }
    } catch(e: Exception) {
        throw PlatformBalanceException("无法读取平台连接，请重新连接")
    }

    fun apiKey(planId: String): String = record(planId)?.apiKey
        ?: throw PlatformBalanceException("请先连接平台")

    fun save(planId: String, apiKey: String, snapshot: DeepSeekBalanceSnapshot) {
        val previous = prefs.getString(planId, null)
        try {
            val record = Record(apiKey.trim(), snapshot.currency, snapshot.totalBalance, snapshot.grantedBalance,
                snapshot.toppedUpBalance, snapshot.queriedAt.toString())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(planId.toByteArray(Charsets.UTF_8))
            }
            val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(json.encodeToString(record).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            if(!prefs.edit().putString(planId, encrypted).commit()) {
                restoreEncryptedRecord(planId, previous)
                throw PlatformBalanceException("平台连接保存失败，请重试")
            }
        } catch(e: PlatformBalanceException) { throw e }
        catch(e: Exception) { throw PlatformBalanceException("平台连接保存失败，请重试") }
    }

    fun updateSnapshot(planId: String, snapshot: DeepSeekBalanceSnapshot) = save(planId, apiKey(planId), snapshot)
    fun disconnect(planId: String) {
        val previous = prefs.getString(planId, null)
        if(!prefs.edit().remove(planId).commit()) {
            restoreEncryptedRecord(planId, previous)
            throw PlatformBalanceException("断开连接失败，请重试")
        }
    }

    // Two independent local stores need compensation; never perform preferences I/O inside Room.
    internal suspend fun saveWithLedgerUpdate(planId: String, apiKey: String, snapshot: DeepSeekBalanceSnapshot,
        update: suspend () -> Unit, wasCommitted: suspend () -> Boolean) {
        val previous = prefs.getString(planId, null)
        var saved = false
        try {
            save(planId, apiKey, snapshot)
            saved = true
            update()
        } catch(e: Exception) {
            if(saved && wasCommitted()) return
            restoreEncryptedRecord(planId, previous)
            throw if(e is PlatformBalanceException) e else PlatformBalanceException("余额保存失败，请重试")
        }
    }

    private fun restoreEncryptedRecord(planId: String, value: String?) {
        val editor = prefs.edit()
        if(value == null) editor.remove(planId) else editor.putString(planId, value)
        if(!editor.commit()) throw PlatformBalanceException("平台连接保存失败，请重新连接")
    }
}
