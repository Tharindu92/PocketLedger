package com.billbook.app.backup

import com.billbook.app.util.copyToLimited
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The zip inside a backup file:
 *   backup.json    what made it, when, database version
 *   billbook.db    the whole database (entries, profiles, rules, categories, rates)
 *   settings.json  app settings
 *   bills/…        bill photos
 */
object BackupArchive {
    const val INFO = "backup.json"
    const val DATABASE = "billbook.db"
    const val SETTINGS = "settings.json"
    const val BILLS = "bills/"

    /** Uncompressed size allowed when reading a backup (stops "zip bombs"). */
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    private val PHOTO_NAME = Regex("[A-Za-z0-9._-]{1,120}")

    /**
     * Where an entry of a backup may be extracted, relative to the target folder, or null for
     * anything that isn't part of a Pocket Ledger backup (including "../" path tricks).
     */
    fun safePath(name: String): String? = when {
        name == INFO || name == DATABASE || name == SETTINGS -> name
        name.startsWith(BILLS) -> name.removePrefix(BILLS).takeIf { it.matches(PHOTO_NAME) && it != "." && it != ".." }
            ?.let { BILLS + it }
        else -> null
    }

    fun write(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    fun write(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Extracts the known entries of [input] into [target]; everything else is skipped. */
    fun extract(input: InputStream, target: File) {
        target.mkdirs()
        var budget = MAX_TOTAL_BYTES
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = if (entry.isDirectory) null else safePath(entry.name)
                if (path != null) {
                    val file = File(target, path)
                    file.parentFile?.mkdirs()
                    val written = file.outputStream().use { zip.copyToLimited(it, budget) }
                    budget -= written
                }
                zip.closeEntry()
            }
        }
        if (!File(target, INFO).exists() || !File(target, DATABASE).exists()) {
            throw IOException("This is not a Pocket Ledger backup")
        }
    }
}
