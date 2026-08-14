package com.tiramission.ocisync.data

import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.config.RegistryAuth
import com.tiramission.ocisync.core.oci.Credential
import com.tiramission.ocisync.core.oci.RegistryAuthProvider

/**
 * [RegistryAuthProvider] 的 DataStore + Keystore 实现:
 * 凭据经 [ConfigLoader] 读取(内存明文,落盘 Keystore 密文)。
 */
class ConfigAuthProvider(
    private val configLoader: ConfigLoader,
) : RegistryAuthProvider {

    override suspend fun credential(registryHost: String): Credential? =
        configLoader.getRegistryAuth(registryHost)
            ?.takeIf { it.username.isNotEmpty() || it.password.isNotEmpty() }
            ?.let { Credential(it.username, it.password) }

    override suspend fun saveCredential(registryHost: String, credential: Credential) {
        configLoader.addAuth(registryHost, RegistryAuth(credential.username, credential.password))
    }
}
