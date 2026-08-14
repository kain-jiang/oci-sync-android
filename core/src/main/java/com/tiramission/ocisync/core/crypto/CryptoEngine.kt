package com.tiramission.ocisync.core.crypto

import org.bouncycastle.crypto.generators.SCrypt
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 加解密失败(密文过短、口令错误、数据损坏)时抛出。 */
class CryptoException(message: String) : Exception(message)

/**
 * scrypt + AES-256-GCM 加解密,字节格式与 Go CLI(tiramission/oci-sync)完全兼容。
 *
 * 布局:[salt(32B)][nonce(12B)][ciphertext + GCM tag(16B)],见 docs/02-core-format.md §2:
 * - scrypt(N=32768, r=8, p=1) 派生 32B 密钥(与 Go x/crypto/scrypt 参数一致)
 * - 每次加密生成新随机 salt + nonce,相同明文密文不同
 * - 最小密文长度 32+12+16=60 字节
 */
object CryptoEngine {

    private const val SALT_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val KEY_SIZE = 32
    private const val SCRYPT_N = 32768
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1
    private const val GCM_TAG_BITS = 128
    private const val MIN_CIPHERTEXT = SALT_SIZE + NONCE_SIZE + 16 // 60

    private val secureRandom = SecureRandom()

    /** 加密:salt + nonce + (ciphertext || tag)。 */
    fun encrypt(data: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(data)
        return salt + nonce + ct
    }

    /** 解密。口令错误/数据损坏抛 [CryptoException],不产出部分数据。 */
    fun decrypt(data: ByteArray, passphrase: String): ByteArray {
        if (data.size < MIN_CIPHERTEXT) {
            throw CryptoException("ciphertext too short")
        }
        val salt = data.copyOfRange(0, SALT_SIZE)
        val nonce = data.copyOfRange(SALT_SIZE, SALT_SIZE + NONCE_SIZE)
        val ct = data.copyOfRange(SALT_SIZE + NONCE_SIZE, data.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            return cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("decrypt failed (wrong passphrase?)")
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray =
        SCrypt.generate(passphrase.toByteArray(Charsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_SIZE)
}
