package cn.renewboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connection secrets are excluded from Ledger and both JSON and WebDAV backups. */
internal class TuitionConnectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tuition-connection", Context.MODE_PRIVATE)
    val configured get() = prefs.contains("connection")

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): TuitionConnection? {
        val encoded = prefs.getString("connection", null) ?: return null
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(ALIAS.toByteArray())
            }
            return Json.decodeFromString<TuitionConnection>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        } catch(_: Exception) { throw TuitionSyncException("无法读取学费连接，请重新填写连接信息") }
    }

    fun save(connection: TuitionConnection) {
        validateTuitionConnection(connection)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key()); updateAAD(ALIAS.toByteArray())
            }
            val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(Json.encodeToString(connection).toByteArray()))
            check(prefs.edit().putString("connection", encoded).commit())
        } catch(_: Exception) { throw TuitionSyncException("学费连接保存失败，请重试") }
    }

    fun disconnect() {
        if(!prefs.edit().remove("connection").commit()) throw TuitionSyncException("断开连接失败，请重试")
    }

    private companion object { const val ALIAS = "renewboard.tuition.connection"; val lock = Any() }
}
