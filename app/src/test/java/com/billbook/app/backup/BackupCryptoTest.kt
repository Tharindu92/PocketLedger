package com.billbook.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class BackupCryptoTest {
    // Few iterations keep the tests fast; the format stores the count.
    private fun encrypt(data: ByteArray, pw: String = "secret1") =
        ByteArrayOutputStream().also { BackupCrypto.encrypt(ByteArrayInputStream(data), it, pw.toCharArray(), iterations = 1000) }.toByteArray()

    private fun decrypt(data: ByteArray, pw: String = "secret1") =
        ByteArrayOutputStream().also { BackupCrypto.decrypt(ByteArrayInputStream(data), it, pw.toCharArray()) }.toByteArray()

    @Test
    fun roundTripsEmptySmallAndMultiChunkData() {
        for (size in listOf(0, 10, 64 * 1024, 64 * 1024 + 1, 300_000)) {
            val data = Random(size).nextBytes(size)
            val sealed = encrypt(data)
            assertTrue(BackupCrypto.isEncrypted(sealed))
            assertArrayEquals("size $size", data, decrypt(sealed))
        }
    }

    @Test
    fun wrongPasswordIsReported() {
        val sealed = encrypt("hello".toByteArray())
        assertThrows(WrongPasswordException::class.java) { decrypt(sealed, "wrong!!") }
    }

    @Test
    fun tamperedDataIsRejected() {
        val sealed = encrypt(Random(1).nextBytes(1000))
        sealed[sealed.size - 20] = (sealed[sealed.size - 20].toInt() xor 1).toByte()
        assertThrows(WrongPasswordException::class.java) { decrypt(sealed) }
    }

    @Test
    fun cutOffFileIsRejected() {
        val sealed = encrypt(Random(2).nextBytes(200_000))
        // Drop the last chunk entirely: the earlier chunks are valid, but the final marker is missing.
        val lastChunk = 1 + 4 + (200_000 - 3 * 64 * 1024) + 16
        assertThrows(IOException::class.java) { decrypt(sealed.copyOf(sealed.size - lastChunk)) }
    }

    @Test
    fun plainZipIsNotEncrypted() {
        assertFalse(BackupCrypto.isEncrypted("PK\u0003\u0004....".toByteArray()))
    }

    @Test
    fun onlyKnownEntriesAreExtracted() {
        assertEquals("billbook.db", BackupArchive.safePath("billbook.db"))
        assertEquals("bills/bill_1.jpg", BackupArchive.safePath("bills/bill_1.jpg"))
        assertNull(BackupArchive.safePath("bills/../../databases/x"))
        assertNull(BackupArchive.safePath("bills/.."))
        assertNull(BackupArchive.safePath("../billbook.db"))
        assertNull(BackupArchive.safePath("/data/data/x"))
        assertNull(BackupArchive.safePath("shared_prefs/settings.xml"))
    }
}
