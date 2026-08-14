package com.tiramission.ocisync.core

import com.tiramission.ocisync.core.archive.ArchiveUnpacker
import com.tiramission.ocisync.core.crypto.CryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 与 Go CLI(tiramission/oci-sync)的字节级互操作测试。
 *
 * 样本由上游 Go 实现生成(scripts/gen-go-samples,产物提交到 test resources):
 * - go-pack.tgz:   目录树打包,根目录名 "sample"
 * - go-encrypt.bin: passphrase="interop-passphrase-2026" 加密
 *   "go-encrypted-secret-payload-42"(30B)→ 密文 90B
 *
 * 见 docs/02-core-format.md §6 兼容性矩阵与 docs/09-testing.md §3。
 */
class InteropTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `go packed archive can be unpacked with identical content`() {
        val packed = javaClass.getResourceAsStream("/interop/go-pack.tgz")!!.readBytes()

        val dest = tmp.newFolder("out")
        ArchiveUnpacker.unpack(packed, dest)

        assertEquals("hello from go\n", dest.resolve("sample/hello.txt").readText())
        assertEquals("nested content 42\n", dest.resolve("sample/sub/nested.txt").readText())
        assertTrue(dest.resolve("sample/sub/empty-dir").isDirectory)
    }

    @Test
    fun `go encrypted data can be decrypted`() {
        val ciphertext = javaClass.getResourceAsStream("/interop/go-encrypt.bin")!!.readBytes()
        assertEquals(90, ciphertext.size)

        val plaintext = CryptoEngine.decrypt(ciphertext, "interop-passphrase-2026")
        assertEquals("go-encrypted-secret-payload-42", plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun `go encrypted data fails with wrong passphrase`() {
        val ciphertext = javaClass.getResourceAsStream("/interop/go-encrypt.bin")!!.readBytes()
        val ex = org.junit.Assert.assertThrows(com.tiramission.ocisync.core.crypto.CryptoException::class.java) {
            CryptoEngine.decrypt(ciphertext, "wrong-passphrase")
        }
        assertTrue(ex.message!!.contains("decrypt failed"))
    }
}
