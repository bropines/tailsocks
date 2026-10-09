package io.github.bropines.tailscaled.admin.secure

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import kotlin.io.encoding.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A secret that is stored but cannot be read back: a restored backup, a reset Keystore. */
class SecretUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals text into an authenticated blob and opens it again. [aad] binds a blob to where it is
 * stored — a profile's token cannot be moved into another profile's slot and still open.
 */
interface SecretBox {
    fun seal(plaintext: String, aad: String): String

    @Throws(SecretUnavailableException::class)
    fun open(blob: String, aad: String): String
}

/** Blob layout shared by every box: `v1:<base64 iv>:<base64 ciphertext+tag>`. */
internal object SealedBlob {
    private const val VERSION = "v1"
    // kotlin.io's codec: java.util.Base64 needs API 26, android.util.Base64 is absent from JVM tests.
    fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        "$VERSION:${Base64.encode(iv)}:${Base64.encode(ciphertext)}"

    fun decode(blob: String): Pair<ByteArray, ByteArray> {
        val parts = blob.split(':')
        if (parts.size != 3 || parts[0] != VERSION) throw SecretUnavailableException("unknown blob format")
        return try {
            Base64.decode(parts[1]) to Base64.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw SecretUnavailableException("corrupt blob", e)
        }
    }
}

/**
 * AES-256-GCM under a key that lives in the Android Keystore and never leaves it. No user
 * authentication on this key: it protects secrets at rest (backups, a copied data directory),
 * so the console can read with them; writes are gated separately ([AdminWriteGate]).
 * Not the deprecated EncryptedSharedPreferences — a Keystore-wrapped blob in a plain file.
 */
class KeystoreSecretBox(private val alias: String = DEFAULT_ALIAS) : SecretBox {

    override fun seal(plaintext: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad.toByteArray())
        return SealedBlob.encode(cipher.iv, cipher.doFinal(plaintext.toByteArray()))
    }

    override fun open(blob: String, aad: String): String {
        val (iv, ct) = SealedBlob.decode(blob)
        val key = key(create = false) ?: throw SecretUnavailableException("the storage key is gone")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad.toByteArray())
            String(cipher.doFinal(ct))
        } catch (e: Exception) {
            throw SecretUnavailableException("cannot open the stored secret (${e.javaClass.simpleName})", e)
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "tailsocks_admin_at_rest"
        internal const val ANDROID_KEYSTORE = "AndroidKeyStore"
        internal const val TRANSFORMATION = "AES/GCM/NoPadding"
        internal const val TAG_BITS = 128
    }
}

/** String storage the vault and the profile store sit on; SharedPreferences in the app. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Stores [value], or removes the key for null. Durable when it returns true. */
    fun putString(key: String, value: String?): Boolean
    fun keys(): Set<String>
}

/** Writes are committed, not applied: the migration deletes plaintext only after they land. */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?): Boolean =
        prefs.edit().let { if (value == null) it.remove(key) else it.putString(key, value) }.commit()
    override fun keys(): Set<String> = prefs.all.keys
}

class MemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = LinkedHashMap(initial)
    override fun getString(key: String): String? = synchronized(map) { map[key] }
    override fun putString(key: String, value: String?): Boolean = synchronized(map) {
        if (value == null) map.remove(key) else map[key] = value
        true
    }
    override fun keys(): Set<String> = synchronized(map) { map.keys.toSet() }
}
