package com.tiramission.ocisync.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoEngineTest {

    private val passphrase = "correct horse battery staple"

    @Test
    fun `encrypt decrypt round-trip`() {
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()
        val ciphertext = CryptoEngine.encrypt(plaintext, passphrase)
        assertArrayEquals(plaintext, CryptoEngine.decrypt(ciphertext, passphrase))
    }

    @Test
    fun `ciphertext length is plaintext plus 60`() {
        val plaintext = ByteArray(100) { it.toByte() }
        val ciphertext = CryptoEngine.encrypt(plaintext, passphrase)
        assertEquals(100 + 60, ciphertext.size)
    }

    @Test
    fun `empty payload round-trip`() {
        val ciphertext = CryptoEngine.encrypt(ByteArray(0), passphrase)
        assertEquals(60, ciphertext.size)
        assertArrayEquals(ByteArray(0), CryptoEngine.decrypt(ciphertext, passphrase))
    }

    @Test
    fun `same plaintext produces different ciphertext each time`() {
        val plaintext = "fixed content".toByteArray()
        val c1 = CryptoEngine.encrypt(plaintext, passphrase)
        val c2 = CryptoEngine.encrypt(plaintext, passphrase)
        assertNotEquals(c1.contentToString(), c2.contentToString())
        // 但都能正确解回
        assertArrayEquals(plaintext, CryptoEngine.decrypt(c1, passphrase))
        assertArrayEquals(plaintext, CryptoEngine.decrypt(c2, passphrase))
    }

    @Test
    fun `wrong passphrase throws CryptoException`() {
        val ciphertext = CryptoEngine.encrypt("secret".toByteArray(), passphrase)
        val ex = assertThrows(CryptoException::class.java) {
            CryptoEngine.decrypt(ciphertext, "wrong-passphrase")
        }
        assertTrue(ex.message!!.contains("decrypt failed"))
    }

    @Test
    fun `ciphertext shorter than 60 bytes throws CryptoException`() {
        val ex = assertThrows(CryptoException::class.java) {
            CryptoEngine.decrypt(ByteArray(59), passphrase)
        }
        assertTrue(ex.message!!.contains("ciphertext too short"))
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val ciphertext = CryptoEngine.encrypt("integrity".toByteArray(), passphrase)
        ciphertext[44] = (ciphertext[44].toInt() xor 0x01).toByte() // 翻转密文首字节
        assertThrows(CryptoException::class.java) { CryptoEngine.decrypt(ciphertext, passphrase) }
    }

    @Test
    fun `different passphrase yields different ciphertext for same plaintext`() {
        val plaintext = "same".toByteArray()
        val c1 = CryptoEngine.encrypt(plaintext, "pass-a")
        val c2 = CryptoEngine.encrypt(plaintext, "pass-b")
        assertFalse(c1.contentEquals(c2))
    }
}
