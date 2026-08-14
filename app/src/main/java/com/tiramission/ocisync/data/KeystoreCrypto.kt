package com.tiramission.ocisync.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tiramission.ocisync.core.config.SecretCodec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-GCM 实现的 [SecretCodec],见 docs/04-crypto-security.md §3.2。
 *
 * - 主密钥存于 AndroidKeyStore,不可导出(root 也拿不到)
 * - `setUserAuthenticationRequired(false)`:不绑定生物识别,后台任务无需锁屏交互
 * - 布局:base64(iv(12B) || ciphertext || tag(16B))
 * - 所有 `auths` password 落盘前经此加密
 */
class KeystoreCrypto(
    private val alias: String = "oci_sync_master_key",
) : SecretCodec {

    override fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val key = getOrCreateKey()
        val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
        require(raw.size > IV_SIZE) { "ciphertext too short" }
        val iv = raw.copyOfRange(0, IV_SIZE)
        val ct = raw.copyOfRange(IV_SIZE, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
