package com.haishinkit.app.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted atomic record, excluded from Android backup. Never serialize credentials to logs. */
internal class ChatGptStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-credentials-v1.bin"))

    suspend fun readOrCreate(): JsonObject =
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) {
                val initial =
                    buildJsonObject {
                        put("host_id", "urn:uuid:${UUID.randomUUID()}")
                        put("profiles", JsonArray(emptyList()))
                    }
                write(initial)
                return@withContext initial
            }
            val bytes =
                file.openRead().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 512_000) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            require(bytes.size <= 512_000) { "Credential record is too large" }
            require(bytes.size > 28 && bytes[0].toInt() == 12) { "Invalid credential record" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            Json.parseToJsonElement(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)).jsonObject
        }

    suspend fun write(value: JsonObject): Unit =
        withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val plaintext = value.toString().toByteArray(Charsets.UTF_8)
            require(plaintext.size <= 511_971) { "Credential record is too large" }
            val encrypted = cipher.doFinal(plaintext)
            val output = file.startWrite()
            try {
                output.write(cipher.iv.size)
                output.write(cipher.iv)
                output.write(encrypted)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "haishinkit-chatgpt-credentials-v1"
    }
}
