package com.billbook.app.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.billbook.app.BillBookApp
import com.billbook.app.util.Limits
import com.billbook.app.util.copyToLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipOutputStream

/**
 * Saves everything (entries, profiles, rules, categories, settings and bill photos) to one file,
 * optionally password-protected, and restores such a file, replacing the data on this phone.
 * Needed when moving to a new phone or to a differently signed install of the app.
 */
class BackupManager(private val app: BillBookApp) {

    data class Summary(val created: Long, val entries: Int, val profiles: Int, val photos: Int)

    class NewerVersionException : IOException("This backup was made by a newer version of Pocket Ledger. Update the app first.")

    private val work get() = File(app.cacheDir, "backup")
    private val restoreDir get() = File(work, "restore")
    private val incoming get() = File(work, "incoming")
    private val dbFile get() = app.getDatabasePath(DB_NAME)
    private val billsDir get() = File(app.filesDir, "bills")
    private val prefs: SharedPreferences get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Export ----

    suspend fun export(target: Uri, password: CharArray?): Summary = withContext(Dispatchers.IO) {
        work.deleteRecursively()
        work.mkdirs()
        val zipFile = File(work, "backup.zip")
        try {
            val db = app.database.openHelper.writableDatabase
            // Move everything from the write-ahead log into the main database file before copying it.
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val entries = db.query("SELECT COUNT(*) FROM transactions").use { it.moveToFirst(); it.getInt(0) }
            val profiles = db.query("SELECT COUNT(*) FROM profiles").use { it.moveToFirst(); it.getInt(0) }
            val photos = billsDir.listFiles()?.filter { it.isFile && it.length() > 0 }.orEmpty()
            val summary = Summary(System.currentTimeMillis(), entries, profiles, photos.size)

            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                BackupArchive.write(zip, BackupArchive.INFO, JSONObject()
                    .put("format", FORMAT)
                    .put("app", "Pocket Ledger")
                    .put("created", summary.created)
                    .put("databaseVersion", db.version)
                    .put("entries", entries)
                    .put("profiles", profiles)
                    .put("photos", photos.size)
                    .toString(2))
                BackupArchive.write(zip, BackupArchive.DATABASE, dbFile)
                BackupArchive.write(zip, BackupArchive.SETTINGS, prefsToJson(prefs).toString(2))
                photos.forEach { BackupArchive.write(zip, BackupArchive.BILLS + it.name, it) }
            }

            val out = app.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write the file")
            out.buffered().use { o ->
                zipFile.inputStream().buffered().use { i ->
                    if (password != null) BackupCrypto.encrypt(i, o, password) else i.copyTo(o)
                }
            }
            summary
        } finally {
            password?.fill(' ')
            work.deleteRecursively()
        }
    }

    // ---- Restore, step 1: copy the picked file in ----

    /** Copies the picked file into the cache. Returns true when it needs a password. */
    suspend fun open(source: Uri): Boolean = withContext(Dispatchers.IO) {
        work.deleteRecursively()
        work.mkdirs()
        val input = app.contentResolver.openInputStream(source) ?: throw IOException("Cannot open the file")
        input.use { i -> incoming.outputStream().use { i.copyToLimited(it, Limits.BACKUP_BYTES) } }
        val head = incoming.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
        when {
            BackupCrypto.isEncrypted(head) -> true
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> false
            else -> throw IOException("This is not a Pocket Ledger backup")
        }
    }

    // ---- Restore, step 2: unlock, unpack and check ----

    /** @throws WrongPasswordException, NewerVersionException, IOException */
    suspend fun read(password: CharArray?): Summary = withContext(Dispatchers.IO) {
        restoreDir.deleteRecursively()
        try {
            if (password != null) {
                val zip = File(work, "backup.zip")
                incoming.inputStream().buffered().use { i -> zip.outputStream().buffered().use { o -> BackupCrypto.decrypt(i, o, password) } }
                zip.inputStream().buffered().use { BackupArchive.extract(it, restoreDir) }
                zip.delete()
            } else {
                incoming.inputStream().buffered().use { BackupArchive.extract(it, restoreDir) }
            }
        } catch (e: IOException) {
            restoreDir.deleteRecursively()
            throw e
        } finally {
            password?.fill(' ')
        }

        val info = JSONObject(File(restoreDir, BackupArchive.INFO).readText())
        if (info.optInt("format") > FORMAT) throw NewerVersionException()
        val current = app.database.openHelper.readableDatabase.version
        val db = File(restoreDir, BackupArchive.DATABASE)
        val (version, entries, profiles) = try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                Triple(
                    d.version,
                    d.rawQuery("SELECT COUNT(*) FROM transactions", null).use { it.moveToFirst(); it.getInt(0) },
                    d.rawQuery("SELECT COUNT(*) FROM profiles", null).use { it.moveToFirst(); it.getInt(0) },
                )
            }
        } catch (e: Exception) {
            throw IOException("The database in this backup is damaged")
        }
        if (version > current) throw NewerVersionException()
        if (version < 1) throw IOException("The database in this backup is damaged")
        val photos = File(restoreDir, BackupArchive.BILLS).listFiles()?.size ?: 0
        Summary(info.optLong("created"), entries, profiles, photos)
    }

    // ---- Restore, step 3: replace the data and restart ----

    /**
     * Replaces the database, bill photos and settings with the unpacked backup, then restarts the
     * app. If anything fails half-way, the previous data is put back.
     */
    suspend fun restoreAndRestart(context: Context) {
        withContext(Dispatchers.IO) { replaceData() }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    private fun replaceData() {
        val newDb = File(restoreDir, BackupArchive.DATABASE)
        val newBills = File(restoreDir, BackupArchive.BILLS)
        val newSettings = File(restoreDir, BackupArchive.SETTINGS)
        check(newDb.exists()) { "Nothing to restore" }

        val undo = File(app.filesDir, "restore-undo").apply { deleteRecursively(); mkdirs() }
        val oldSettings = prefsToJson(prefs)
        val dbFiles = DB_SUFFIXES.map { File(dbFile.path + it) }

        app.database.close()
        try {
            dbFiles.filter { it.exists() }.forEach { move(it, File(undo, it.name)) }
            if (billsDir.exists()) move(billsDir, File(undo, "bills"))

            dbFile.parentFile?.mkdirs()
            newDb.copyTo(dbFile, overwrite = true)
            billsDir.mkdirs()
            newBills.listFiles()?.forEach { it.copyTo(File(billsDir, it.name), overwrite = true) }
            pointPhotosHere(dbFile)
            if (newSettings.exists()) writePrefs(prefs, JSONObject(newSettings.readText()))
        } catch (e: Exception) {
            dbFiles.forEach { it.delete() }
            billsDir.deleteRecursively()
            undo.listFiles()?.forEach { move(it, if (it.name == "bills") billsDir else File(dbFile.parentFile, it.name)) }
            writePrefs(prefs, oldSettings)
            throw IOException("Restore failed; your previous data was kept (${e.message})", e)
        }
        undo.deleteRecursively()
        work.deleteRecursively()
    }

    /** Photo paths are absolute; make them point at this install's photo folder. */
    private fun pointPhotosHere(db: File) {
        val folder = billsDir.absolutePath + "/"
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            d.execSQL(
                "UPDATE transactions SET imagePath = ? || substr(imagePath, instr(imagePath, '/bills/') + 7) " +
                    "WHERE instr(imagePath, '/bills/') > 0",
                arrayOf(folder),
            )
        }
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
    }

    /** Clears the cached copies left after a cancelled restore. */
    fun discard() {
        work.deleteRecursively()
    }

    companion object {
        private const val FORMAT = 1
        private const val DB_NAME = "billbook.db"
        private const val PREFS = "settings"
        private val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        fun prefsToJson(prefs: SharedPreferences): JSONObject {
            val json = JSONObject()
            prefs.all.forEach { (key, value) ->
                val item = when (value) {
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                if (item != null) json.put(key, item)
            }
            return json
        }

        fun writePrefs(prefs: SharedPreferences, json: JSONObject) {
            val edit = prefs.edit().clear()
            json.keys().forEach { key ->
                val item = json.optJSONObject(key) ?: return@forEach
                when (item.optString("t")) {
                    "s" -> edit.putString(key, item.optString("v"))
                    "b" -> edit.putBoolean(key, item.optBoolean("v"))
                    "l" -> edit.putLong(key, item.optLong("v"))
                    "i" -> edit.putInt(key, item.optInt("v"))
                    "f" -> edit.putFloat(key, item.optDouble("v").toFloat())
                    "set" -> item.optJSONArray("v")?.let { a -> edit.putStringSet(key, (0 until a.length()).map(a::optString).toSet()) }
                }
            }
            edit.commit()
        }
    }
}
