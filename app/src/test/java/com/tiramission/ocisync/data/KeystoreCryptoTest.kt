package com.tiramission.ocisync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KeystoreCrypto 测试。
 *
 * ⚠️ 已知限制:Robolectric 无法模拟 AndroidKeyStore 的 binder 服务
 * ("Could not connect to Keystore service"),AndroidKeyStore2 依赖系统进程。
 * 故本测试 @Ignore,KeystoreCrypto 的加解密逻辑(与 CryptoEngine 同构的
 * AES-GCM)由 core 测试 + 真机验收(docs/04-crypto-security.md §6)覆盖。
 * 真机/模拟器上运行:./gradlew connectedDebugAndroidTest 或移除 @Ignore 后
 * 在真机环境执行本类。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.junit.Ignore("Robolectric 无法模拟 Android Keystore binder 服务,需真机验证(见 docs/09-testing.md §4)")
class KeystoreCryptoTest {

    companion object {
        init {
            try {
                val providerClass = Class.forName("android.security.keystore2.AndroidKeyStoreProvider")
                val provider = providerClass.getDeclaredConstructor().newInstance() as java.security.Provider
                java.security.Security.addProvider(provider)
            } catch (e: Exception) {
                println("WARN: could not register AndroidKeyStoreProvider: $e")
            }
        }
    }

    @Test
    fun `encrypt decrypt round-trip`() {
        val crypto = KeystoreCrypto("test_key_roundtrip")
        val ciphertext = crypto.encrypt("secret-token-123")
        assertNotEquals("secret-token-123", ciphertext)
        assertEquals("secret-token-123", crypto.decrypt(ciphertext))
    }

    @Test
    fun `new instance with same alias can decrypt`() {
        val alias = "test_key_persist"
        val ciphertext = KeystoreCrypto(alias).encrypt("persisted-secret")
        // 模拟重启:新实例同一 alias,密钥来自 Keystore
        assertEquals("persisted-secret", KeystoreCrypto(alias).decrypt(ciphertext))
    }

    @Test
    fun `tampered ciphertext fails decryption`() {
        val crypto = KeystoreCrypto("test_key_tamper")
        val raw = android.util.Base64.decode(crypto.encrypt("integrity"), android.util.Base64.NO_WRAP)
        raw[12] = (raw[12].toInt() xor 0x01).toByte() // 翻转第一个密文字节
        val tampered = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        assertThrows(javax.crypto.AEADBadTagException::class.java) { crypto.decrypt(tampered) }
    }

    @Test
    fun `too short ciphertext is rejected`() {
        val crypto = KeystoreCrypto("test_key_short")
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(android.util.Base64.encodeToString(ByteArray(4), android.util.Base64.NO_WRAP))
        }
    }

    @Test
    fun `different alias cannot decrypt`() {
        val c1 = KeystoreCrypto("test_key_a")
        val c2 = KeystoreCrypto("test_key_b")
        val ciphertext = c1.encrypt("cross-alias")
        assertThrows(Exception::class.java) { c2.decrypt(ciphertext) }
    }
}
