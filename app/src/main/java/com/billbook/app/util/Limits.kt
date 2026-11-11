package com.billbook.app.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Size limits for data coming from outside the app, so a huge or broken file can't fill memory or storage. */
object Limits {
    /** Bank statements (CSV or PDF). Real ones are well under 5 MB. */
    const val STATEMENT_BYTES = 20L * 1024 * 1024
    /** Bill photos picked from the gallery or the scanner. */
    const val IMAGE_BYTES = 30L * 1024 * 1024
    /** One exchange-rate download (about 10 KB in practice). */
    const val RATES_BYTES = 1L * 1024 * 1024

    fun mb(bytes: Long) = bytes / (1024 * 1024)
}

class TooLargeException(val limit: Long) : IOException("File is larger than ${Limits.mb(limit)} MB")

/** Like [InputStream.copyTo], but stops with [TooLargeException] once more than [limit] bytes arrive. */
fun InputStream.copyToLimited(out: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return total
        total += n
        if (total > limit) throw TooLargeException(limit)
        out.write(buffer, 0, n)
    }
}

/** Reads the whole stream as UTF-8 text, failing with [TooLargeException] beyond [limit] bytes. */
fun InputStream.readTextLimited(limit: Long): String {
    val out = java.io.ByteArrayOutputStream()
    copyToLimited(out, limit)
    return out.toString(Charsets.UTF_8.name())
}
