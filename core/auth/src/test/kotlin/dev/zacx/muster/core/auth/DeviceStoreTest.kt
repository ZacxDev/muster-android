package dev.zacx.muster.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The credential store: the real DataStore and the real AES-GCM code, with a
 * software key standing in for the Keystore's (the JVM has no AndroidKeyStore).
 */
class DeviceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val token = "mdv_" + "T".repeat(43)

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun record() = DeviceRecord(origin = "https://m.example.test", token = token, deviceId = 5, keyKind = "biometric", keyLevel = "strongbox")

    /** Runs [block] with a store on [file]; the store is closed after. */
    private fun <T> withStore(file: File, k: SecretKey, block: suspend (DeviceStore) -> T): T = runBlocking {
        val job = SupervisorJob()
        try {
            block(DataStoreDeviceStore(file, AesGcmAead { k }, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            // DataStore allows one live instance per file: end this one before the next.
            job.cancelAndJoin()
        }
    }

    @Test
    fun aRecordRoundTripsAndIsNeverOnDiskInTheClear() {
        val f = File(tmp.root, "device.sealed")
        val k = key()
        withStore(f, k) { it.update { record() } }

        val disk = f.readBytes()
        assertFalse("the credential is on disk in the clear", String(disk, Charsets.ISO_8859_1).contains("TTTTTTTT"))
        assertFalse(String(disk, Charsets.ISO_8859_1).contains("m.example.test"))
        assertEquals(1, disk[0].toInt()) // the format version

        assertEquals(record(), withStore(f, k) { it.read() })
    }

    /** The Keystore key is gone (or another phone's file): the phone reads as never paired. */
    @Test
    fun aRecordUnderAnotherKeyReadsAsUnpaired() {
        val f = File(tmp.root, "device.sealed")
        withStore(f, key()) { it.update { record() } }
        assertEquals(DeviceRecord(), withStore(f, key()) { it.read() })
    }

    @Test
    fun aTamperedRecordReadsAsUnpaired() {
        val f = File(tmp.root, "device.sealed")
        val k = key()
        withStore(f, k) { it.update { record() } }
        val b = f.readBytes()
        b[b.size - 20] = (b[b.size - 20].toInt() xor 1).toByte()
        f.writeBytes(b)
        assertEquals(DeviceRecord(), withStore(f, k) { it.read() })
    }

    @Test
    fun theCipherBindsItsPurposeAndUsesAFreshIv() {
        val k = key()
        val aead = AesGcmAead { k }
        val plain = "hello".encodeToByteArray()
        val a = aead.encrypt(plain, SealedRecordSerializer.AAD)
        val b = aead.encrypt(plain, SealedRecordSerializer.AAD)
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(plain, aead.decrypt(a, SealedRecordSerializer.AAD))
        assertThrows(GeneralSecurityException::class.java) { aead.decrypt(a, "other".encodeToByteArray()) }
        assertThrows(GeneralSecurityException::class.java) { aead.decrypt(byteArrayOf(2) + a.copyOfRange(1, a.size), SealedRecordSerializer.AAD) }
    }

    @Test
    fun unpairingKeepsOnlyTheOrigin() {
        val r = record().copy(pushStatus = PushStatus.REGISTERED, vapidKey = "v", pushHosts = listOf("h"))
        assertEquals(DeviceRecord(origin = "https://m.example.test"), r.unpaired())
    }
}
