package com.nousresearch.hermesmobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with the app's Keystore key, for what chats keep on the phone (composers, the outbox). */
object LocalCipher {
    private const val ALIAS = "hermes_mobile_chat_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun encrypt(clear: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(clear.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + body.size)
        cipher.iv.copyInto(payload)
        body.copyInto(payload, cipher.iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    @Synchronized
    fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }
}

/** The one outbox of this process (the ViewModel and the cache cleanup share it; two would fight over the file). */
object Outboxes {
    @Volatile private var instance: Outbox? = null
    @Volatile private var storage: PrefsOutboxStorage? = null

    fun get(context: Context): Outbox = instance ?: synchronized(this) {
        instance ?: PrefsOutboxStorage(context.applicationContext).let { disk ->
            Outbox(disk).also { storage = disk; instance = it }
        }
    }

    /**
     * Files the outbox needs, or null when that is not known for sure (it could not be opened, or some rows could not
     * be read): then nothing may be cleaned up.
     */
    fun filesToKeep(context: Context): Set<String>? = runCatching {
        val box = get(context)
        box.referencedAttachmentUris().takeIf { storage?.unreadable == 0 }
    }.getOrNull()
}

/**
 * The outbox on disk: its own encrypted preferences file, one key per entry, every write a synchronous commit(), so an
 * entry is on disk before prompt.submit is called and survives the process being killed right after.
 */
class PrefsOutboxStorage(context: Context) : OutboxStorage {
    private val prefs = context.getSharedPreferences("hermes_mobile_outbox", Context.MODE_PRIVATE)
    /** Rows the last [readAll] could not read (the Keystore refused): their files must not be cleaned up. */
    @Volatile var unreadable = 0
        private set

    override fun readAll(): Map<String, String> {
        var failed = 0
        val rows = prefs.all.mapNotNull { (id, value) ->
            (value as? String)?.let { runCatching { id to LocalCipher.decrypt(it) }.onFailure { failed++ }.getOrNull() }
        }.toMap()
        unreadable = failed
        return rows
    }

    /** One editor, one commit(): every change in it lands together or none does (SharedPreferences writes the file whole). */
    override fun writeAll(changes: Map<String, String?>) {
        val edit = prefs.edit()
        changes.forEach { (id, value) -> if (value == null) edit.remove(id) else edit.putString(id, LocalCipher.encrypt(value)) }
        check(edit.commit()) { "Could not save the outbox" }
    }
}
