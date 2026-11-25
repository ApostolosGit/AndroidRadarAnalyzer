package gr.aircat.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SyncLogin(val password: String, val pin: String)

internal class SyncCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("sync2wifi_credentials", Context.MODE_PRIVATE)
    private val alias = "aircat_sync2wifi_aes_v4"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun hasSaved() = prefs.contains("cipher") && prefs.contains("iv")
    fun save(value: SyncLogin): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val data = cipher.doFinal((value.pin + value.password).toByteArray(Charsets.UTF_8))
        prefs.edit().putString("cipher", Base64.encodeToString(data, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()
    } catch (_: Exception) { false }
    fun load(): SyncLogin? = try {
        val data = Base64.decode(prefs.getString("cipher", "") ?: "", Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString("iv", "") ?: "", Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        val value = cipher.doFinal(data).toString(Charsets.UTF_8)
        val pin = value.take(4)
        val pass = value.drop(4)
        if (pin.matches(Regex("[0-9]{4}")) && pass.length in 8..63) SyncLogin(pass,pin) else null
    } catch (_: Exception) { null }
}
