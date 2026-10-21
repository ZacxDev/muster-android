package dev.zacx.muster.core.auth

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Everything the app keeps about its pairing. One record, encrypted as a
 * whole: the device credential is in it, so nothing in it is ever written in
 * the clear.
 */
@Serializable
data class DeviceRecord(
    /** The muster origin the user entered ("https://host[:port]"), or null before first run. */
    val origin: String? = null,
    /** The device credential (mdv_…), or null while unpaired. */
    val token: String? = null,
    @SerialName("device_id") val deviceId: Long? = null,
    /** The step-up key muster recorded, or null: read-only. */
    @SerialName("key_kind") val keyKind: String? = null,
    @SerialName("key_level") val keyLevel: String? = null,
    /** The Keystore alias of this pairing's step-up key, or null: no key. */
    @SerialName("key_alias") val keyAlias: String? = null,
    /** Why the phone is read-only or uses a PIN key, shown in its status. */
    @SerialName("key_note") val keyNote: String? = null,
    /**
     * The operator's push hosts from /api/device/config, kept for the
     * push-host check, which runs in the push service when the distributor
     * hands over an endpoint (no UI, no config call there).
     */
    @SerialName("push_hosts") val pushHosts: List<String> = emptyList(),
    @SerialName("push_status") val pushStatus: PushStatus = PushStatus.NONE,
    @SerialName("push_note") val pushNote: String? = null,
) {
    val paired: Boolean get() = token != null

    /** The record with the pairing gone and the origin kept. */
    fun unpaired(): DeviceRecord = DeviceRecord(origin = origin)
}

@Serializable
enum class PushStatus {
    NONE,
    /** muster serves no push (no VAPID keys configured). */
    SERVER_HAS_NO_PUSH,
    /** No UnifiedPush distributor (the ntfy app) is installed. */
    NO_DISTRIBUTOR,
    /** The distributor's server is not the operator's push host. */
    HOST_MISMATCH,
    REGISTERING,
    REGISTERED,
    /** muster refused the endpoint, or its test push failed. */
    REFUSED,
    UNREGISTERED,
}

/** The app's view of its record. */
interface DeviceStore {
    val records: Flow<DeviceRecord>

    suspend fun read(): DeviceRecord = records.first()

    suspend fun update(f: (DeviceRecord) -> DeviceRecord): DeviceRecord
}

/** Authenticated encryption under a key the app never sees (the Keystore's). */
interface Aead {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    /** Throws GeneralSecurityException on a wrong key or tampered bytes. */
    fun decrypt(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM. The cipher picks the IV (the Keystore requires randomized
 * encryption), and the IV travels in front of the ciphertext:
 * `version(1) | iv(12) | ciphertext+tag`.
 */
class AesGcmAead(private val key: () -> SecretKey) : Aead {
    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        val iv = c.iv
        check(iv.size == IV_LEN) { "unexpected IV length ${iv.size}" }
        return byteArrayOf(VERSION) + iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 1 + IV_LEN + TAG_LEN || sealed[0] != VERSION) {
            throw GeneralSecurityException("not a sealed record")
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LEN * 8, sealed, 1, IV_LEN))
        c.updateAAD(aad)
        return c.doFinal(sealed, 1 + IV_LEN, sealed.size - 1 - IV_LEN)
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_LEN = 12
        private const val TAG_LEN = 16

        /** The record key in the Android Keystore. No user authentication: the push service reads it in the background. */
        const val KEYSTORE_ALIAS = "muster-record-v1"

        fun androidKeystore(): AesGcmAead = AesGcmAead {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }
    }
}

/**
 * The DataStore serializer: the whole record sealed with [aead]. A record that
 * cannot be opened (the Keystore key is gone, the file was tampered with) reads
 * as "never paired, no server": the credential cannot be recovered without its
 * key, and the user pairs again.
 */
class SealedRecordSerializer(private val aead: Aead) : Serializer<DeviceRecord> {
    override val defaultValue: DeviceRecord = DeviceRecord()

    override suspend fun readFrom(input: InputStream): DeviceRecord {
        val sealed = input.readBytes()
        if (sealed.isEmpty()) return defaultValue
        val plain = try {
            aead.decrypt(sealed, AAD)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("the device record could not be opened", e)
        }
        return try {
            json.decodeFromString(DeviceRecord.serializer(), plain.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("the device record could not be read", e)
        }
    }

    override suspend fun writeTo(t: DeviceRecord, output: OutputStream) {
        output.write(aead.encrypt(json.encodeToString(DeviceRecord.serializer(), t).encodeToByteArray(), AAD))
    }

    companion object {
        /** Binds the ciphertext to its purpose: a sealed blob from elsewhere does not open here. */
        val AAD = "dev.zacx.muster/device-record/v1".encodeToByteArray()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** [DeviceStore] on a DataStore file, sealed with [aead]. One instance per file. */
class DataStoreDeviceStore(file: File, aead: Aead, scope: CoroutineScope) : DeviceStore {
    private val store: DataStore<DeviceRecord> = DataStoreFactory.create(
        serializer = SealedRecordSerializer(aead),
        corruptionHandler = ReplaceFileCorruptionHandler { DeviceRecord() },
        scope = scope,
        produceFile = { file },
    )

    override val records: Flow<DeviceRecord> = store.data

    override suspend fun update(f: (DeviceRecord) -> DeviceRecord): DeviceRecord = store.updateData { f(it) }
}
