package com.billbook.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("Wrong password, or the file is damaged")

/**
 * Password protection for backup files: AES-256-GCM with a key derived from the password
 * (PBKDF2-HMAC-SHA256). The data is encrypted in 64 KB chunks so big backups never have to fit
 * in memory; every chunk is authenticated, and the last one is marked so a cut-off file is noticed.
 *
 * Layout: "PLBK" 1 | iterations (int) | salt (16) | nonce prefix (8) | chunks…
 * Chunk:  final flag (byte) | length (int) | ciphertext + 16-byte tag
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1)
    private const val CHUNK = 64 * 1024
    private const val TAG_BITS = 128
    const val ITERATIONS = 200_000
    private const val MAX_ITERATIONS = 2_000_000

    /** True when [head] (the first bytes of a file) starts like an encrypted backup. */
    fun isEncrypted(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, iterations: Int = ITERATIONS) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val out = DataOutputStream(output)
        val header = header(iterations, salt, prefix)
        out.write(header)
        val key = deriveKey(password, salt, iterations)

        val buffer = ByteArray(CHUNK)
        var counter = 0
        var n = readFully(input, buffer)
        while (true) {
            // Look ahead one chunk to know whether this one is the last.
            val next = ByteArray(CHUNK)
            val nextN = if (n == CHUNK) readFully(input, next) else 0
            val final = nextN == 0
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, counter, header, final).doFinal(buffer, 0, n)
            out.writeByte(if (final) 1 else 0)
            out.writeInt(sealed.size)
            out.write(sealed)
            if (final) break
            next.copyInto(buffer)
            n = nextN
            counter++
        }
        out.flush()
    }

    /** @throws WrongPasswordException for a wrong password or tampered data */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
        if (!isEncrypted(magic)) throw IOException("Not an encrypted Pocket Ledger backup")
        val iterations = data.readInt()
        if (iterations !in 1..MAX_ITERATIONS) throw IOException("Damaged backup header")
        val salt = ByteArray(16).also { data.readFully(it) }
        val prefix = ByteArray(8).also { data.readFully(it) }
        val header = header(iterations, salt, prefix)
        val key = deriveKey(password, salt, iterations)

        var counter = 0
        while (true) {
            val flag = try { data.readUnsignedByte() } catch (e: EOFException) { throw IOException("The backup file is incomplete") }
            if (flag > 1) throw IOException("Damaged backup")
            val length = data.readInt()
            if (length !in 16..CHUNK + 16) throw IOException("Damaged backup")
            val sealed = ByteArray(length).also { data.readFully(it) }
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, counter, header, flag == 1).doFinal(sealed)
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException()
            }
            output.write(plain)
            if (flag == 1) break
            counter++
        }
        if (data.read() != -1) throw IOException("Unexpected data after the end of the backup")
        output.flush()
    }

    private fun header(iterations: Int, salt: ByteArray, prefix: ByteArray): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).apply { write(MAGIC); writeInt(iterations); write(salt); write(prefix) }
        return bytes.toByteArray()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Nonce = random prefix + chunk number; the header and the final flag are authenticated too. */
    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, counter: Int, header: ByteArray, final: Boolean): Cipher {
        val nonce = prefix + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(byteArrayOf(if (final) 1 else 0))
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
