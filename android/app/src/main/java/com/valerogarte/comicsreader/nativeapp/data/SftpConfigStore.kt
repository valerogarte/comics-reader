package com.valerogarte.comicsreader.nativeapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.valerogarte.comicsreader.BuildConfig
import com.valerogarte.comicsreader.nativeapp.model.SftpConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the complete profile list atomically, including server names and passwords. */
internal class SftpConfigStore(context: Context, private val defaultJson: String = BuildConfig.NAS_DEFAULT_JSON) {
    private val preferences = context.getSharedPreferences("native-sftp", Context.MODE_PRIVATE)

    @Synchronized
    fun loadAll(): List<SftpConfig> {
        preferences.getString("configurations", null)?.let { encrypted ->
            return decodeProfiles(JSONArray(decrypt(encrypted)))
        }
        preferences.getString("configuration", null)?.let { legacy ->
            val json = JSONObject(decrypt(legacy))
            val previous = decodeProfile(json)
            val defaults = defaultProfiles()
            val matchingDefault = defaults.firstOrNull { it.id == previous.id }
            val migrated = previous.copy(name = json.optString("name", "").ifBlank { matchingDefault?.name ?: previous.name })
            val profiles = listOf(migrated) + defaults.filter { it.id != previous.id }
            saveAll(profiles)
            return profiles
        }
        // An explicitly saved empty list remains empty after application updates.
        if (preferences.getBoolean("profiles_initialized", false)) return emptyList()
        val profiles = defaultProfiles()
        if (profiles.isEmpty()) return emptyList()
        saveAll(profiles)
        return profiles
    }

    @Synchronized
    fun saveAll(configs: List<SftpConfig>) {
        val array = JSONArray()
        configs.forEach { config -> array.put(JSONObject().apply {
            put("host", config.host); put("port", config.port); put("username", config.username)
            put("password", config.password); put("rootPath", config.rootPath)
            put("profileId", config.profileId); put("name", config.name)
        }) }
        check(preferences.edit().putString("configurations", encrypt(array.toString()))
            .putBoolean("profiles_initialized", true).remove("configuration").commit()) {
            "No se pudo guardar la configuración SFTP"
        }
    }

    private fun decodeProfiles(array: JSONArray): List<SftpConfig> = (0 until array.length()).map { decodeProfile(array.getJSONObject(it)) }

    private fun defaultProfiles(): List<SftpConfig> {
        val seed = defaultJson.trim()
        if (seed.isEmpty()) return emptyList()
        return if (seed.startsWith("[")) decodeProfiles(JSONArray(seed)) else listOf(decodeProfile(JSONObject(seed)))
    }

    private fun decodeProfile(json: JSONObject): SftpConfig = SftpConfig(
        host = json.getString("host"), port = json.optInt("port", 22), username = json.getString("username"),
        password = json.optString("password", ""), rootPath = json.optString("rootPath", "/"),
        profileId = json.optString("profileId", "").ifBlank { UUID.randomUUID().toString() },
        name = json.optString("name", "").ifBlank { json.getString("host") },
    )

    private fun decrypt(value: String): String {
        val record = JSONObject(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(record.getString("iv"), Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(record.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return JSONObject().apply {
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }.toString()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object { const val KEY_ALIAS = "comics-reader-native-sftp" }
}
