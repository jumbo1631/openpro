package com.openpro.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
enum class ServerType(val label: String) { Nextcloud("Nextcloud"), WebDav("WebDAV") }

/** A server the user runs (or trusts) that OpenPro uploads to. The password lives in [Vault]. */
@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val type: ServerType,
    val name: String,
    /** Nextcloud: the server's address (https://cloud.example.com). WebDAV: the full WebDAV URL. */
    val baseUrl: String,
    val username: String,
    /** Nextcloud user ID for WebDAV paths, when it differs from the login name (e.g. login by email). */
    val davUser: String? = null,
    /** Folder on the server that uploads go into. */
    val folder: String = "/OpenPro",
    /** How uploads are arranged in folders inside [folder] (see [com.openpro.media.PathLayout]). */
    val layout: String = "${com.openpro.media.PathLayout.YEAR}-${com.openpro.media.PathLayout.MONTH}",
    /** SHA-256 of a self-signed certificate the user chose to trust (hex, lowercase). */
    val pinnedCertSha256: String? = null,
) {
    val isCleartext get() = baseUrl.startsWith("http://", ignoreCase = true)
}

/** Server profiles (SharedPreferences, JSON) and which one uploads go to by default. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    val vault = Vault(context)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<ServerProfile>> = _servers
    private val _defaultId = MutableStateFlow(prefs.getString("default", null))
    val defaultId: StateFlow<String?> = _defaultId

    fun get(id: String?): ServerProfile? = _servers.value.firstOrNull { it.id == id }

    /** The server new uploads go to: the chosen default, else the only/first one. */
    fun defaultServer(): ServerProfile? = get(_defaultId.value) ?: _servers.value.firstOrNull()

    fun save(profile: ServerProfile, password: String?) {
        if (password != null) vault.put(profile.id, password)
        _servers.value = _servers.value.filter { it.id != profile.id } + profile
        persist()
        if (_servers.value.size == 1 || _defaultId.value == null) setDefault(profile.id)
    }

    fun remove(id: String) {
        _servers.value = _servers.value.filter { it.id != id }
        vault.remove(id)
        persist()
        if (_defaultId.value == id) setDefault(_servers.value.firstOrNull()?.id)
    }

    fun setDefault(id: String?) {
        prefs.edit().putString("default", id).apply()
        _defaultId.value = id
    }

    fun password(id: String): String? = vault.get(id)

    fun client(profile: ServerProfile): ServerClient =
        ServerClient(profile, password(profile.id) ?: throw IllegalStateException("No saved password for ${profile.name}"))

    private fun persist() {
        prefs.edit().putString("profiles", json.encodeToString(ListSerializer(ServerProfile.serializer()), _servers.value)).apply()
    }

    private fun load(): List<ServerProfile> = prefs.getString("profiles", null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(ServerProfile.serializer()), it) }.getOrNull() }
        .orEmpty()
}

/**
 * Server passwords, encrypted with an AES-GCM key that lives in the Android Keystore (it never
 * leaves the secure hardware and can't be exported). Never logged, never backed up (allowBackup=false).
 */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(id: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(secret.toByteArray())
        prefs.edit().putString(id, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(id: String): String? {
        val sealed = prefs.getString(id, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12))
        }.getOrNull()
    }

    fun remove(id: String) = prefs.edit().remove(id).apply()

    companion object {
        private const val ALIAS = "openpro-server-vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
