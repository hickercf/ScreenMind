package cn.weiguang.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("weiguang-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("weiguang-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(session: UserSession) {
        val data = JSONObject().put("id", session.userId).put("username", session.username)
            .put("access", session.accessToken).put("refresh", session.refreshToken).put("endpoint", session.endpoint)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("value", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    fun load(): UserSession? {
        val raw = preferences.getString("value", null) ?: return null
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            UserSession(data.getString("id"), data.getString("username"), data.getString("access"), data.getString("refresh"), data.getString("endpoint"))
        } catch (_: Exception) { clear(); null }
    }
    fun clear() { preferences.edit().clear().commit() }
}
