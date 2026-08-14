package com.tiramission.ocisync.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigLoaderTest {

    private class MapStore : KeyValueStore {
        val data = mutableMapOf<String, String>()
        override fun get(key: String): String? = data[key]
        override fun put(key: String, value: String) {
            data[key] = value
        }
    }

    /** 测试用 fake:base64 编码模拟"加密",可反向解密。 */
    private class FakeCodec : SecretCodec {
        override fun encrypt(plaintext: String): String =
            java.util.Base64.getEncoder().encodeToString(plaintext.toByteArray())
        override fun decrypt(ciphertext: String): String =
            String(java.util.Base64.getDecoder().decode(ciphertext))
    }

    private fun newLoader(store: MapStore = MapStore()): ConfigLoader =
        ConfigLoader(store, FakeCodec())

    @Test
    fun `load returns empty config when nothing stored`() {
        val config = newLoader().load()
        assertTrue(config.auths.isEmpty())
        assertTrue(config.shortcuts.isEmpty())
    }

    @Test
    fun `password is encrypted on disk and decrypted on read`() {
        val store = MapStore()
        val loader = newLoader(store)
        loader.addAuth("registry.example.com", RegistryAuth("user", "secret-password"))

        // 落盘必须是密文(不含明文)
        val raw = store.data["app_config"]!!
        assertTrue(!raw.contains("secret-password"))

        // 读取返回明文
        val auth = loader.getRegistryAuth("registry.example.com")!!
        assertEquals("user", auth.username)
        assertEquals("secret-password", auth.password)
        assertNull(loader.getRegistryAuth("other.host"))
    }

    @Test
    fun `add and remove auth`() {
        val loader = newLoader()
        loader.addAuth("h1", RegistryAuth("u", "p"))
        loader.addAuth("h2", RegistryAuth("u2", "p2"))
        assertEquals(2, loader.load().auths.size)
        loader.removeAuth("h1")
        assertEquals(setOf("h2"), loader.load().auths.keys)
    }

    @Test
    fun `shortcut round-trip and listing`() {
        val loader = newLoader()
        loader.addShortcut("backup", "registry.example.com/team/files")
        assertEquals("registry.example.com/team/files", loader.getShortcutRepo("backup").getOrNull())
        assertEquals(listOf("backup" to Shortcut("registry.example.com/team/files")), loader.getAllShortcuts())
        loader.removeShortcut("backup")
        assertTrue(loader.getShortcutRepo("backup").isFailure)
    }

    @Test
    fun `shortcut with digest is rejected`() {
        val loader = newLoader()
        val result = loader.addShortcut("bad", "registry.example.com/repo@sha256:abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("@"))
        assertTrue(loader.load().shortcuts.isEmpty())
    }

    @Test
    fun `shortcut with tag is rejected`() {
        val loader = newLoader()
        val result = loader.addShortcut("bad", "registry.example.com/repo:v1")
        assertTrue(result.isFailure)
        assertTrue(loader.load().shortcuts.isEmpty())
    }

    @Test
    fun `shortcut repo with colon in namespace is allowed`() {
        val loader = newLoader()
        // ':' 在最后一个 '/' 之前 → 合法(命名空间含端口?)
        val result = loader.addShortcut("ok", "localhost:5000/team/repo")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `missing shortcut fails`() {
        assertTrue(newLoader().getShortcutRepo("nope").isFailure)
    }

    @Test
    fun `corrupt stored config falls back to empty`() {
        val store = MapStore()
        store.put("app_config", "{not-json")
        assertTrue(newLoader(store).load().auths.isEmpty())
    }
}
