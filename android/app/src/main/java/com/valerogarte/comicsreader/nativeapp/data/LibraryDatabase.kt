package com.valerogarte.comicsreader.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.valerogarte.comicsreader.nativeapp.model.BookEntry
import com.valerogarte.comicsreader.nativeapp.model.BookFormats
import com.valerogarte.comicsreader.nativeapp.model.ReadingLogEntry
import com.valerogarte.comicsreader.nativeapp.model.ReadingStatus
import com.valerogarte.comicsreader.nativeapp.model.RemoteEntry
import com.valerogarte.comicsreader.nativeapp.model.RemoteFolder
import com.valerogarte.comicsreader.nativeapp.model.ShelfEntry
import com.valerogarte.comicsreader.nativeapp.model.ShelfPath
import com.valerogarte.comicsreader.nativeapp.model.StoredGuide

/** The native library has its own database; it never opens or migrates browser storage. */
internal class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "native-library.sqlite", null, 6) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shelves(name TEXT PRIMARY KEY NOT NULL)""")
        db.execSQL("""CREATE TABLE books(
            id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, file_name TEXT NOT NULL,
            format TEXT NOT NULL, local_path TEXT NOT NULL UNIQUE, cover_path TEXT,
            author TEXT NOT NULL, description TEXT NOT NULL, publisher TEXT NOT NULL,
            page_count INTEGER NOT NULL, current_page INTEGER NOT NULL DEFAULT 0,
            reading_status TEXT NOT NULL DEFAULT 'UNREAD', shelf_name TEXT REFERENCES shelves(name) ON DELETE SET NULL,
            source_id TEXT, remote_path TEXT, size INTEGER NOT NULL, modified INTEGER NOT NULL,
            content_hash TEXT NOT NULL, UNIQUE(source_id,remote_path))""")
        db.execSQL("CREATE INDEX books_hash ON books(content_hash)")
        db.execSQL("""CREATE TABLE remote_folders(
            source_id TEXT NOT NULL, path TEXT NOT NULL, cached_at INTEGER NOT NULL,
            PRIMARY KEY(source_id,path))""")
        db.execSQL("""CREATE TABLE remote_entries(
            source_id TEXT NOT NULL, path TEXT NOT NULL, parent_path TEXT NOT NULL,
            name TEXT NOT NULL, is_directory INTEGER NOT NULL, size INTEGER NOT NULL,
            modified INTEGER NOT NULL, title TEXT NOT NULL, format TEXT NOT NULL,
            cover_path TEXT, author TEXT NOT NULL, description TEXT NOT NULL,
            publisher TEXT NOT NULL, page_count INTEGER NOT NULL, file_count INTEGER,
            preview_version INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(source_id,path))""")
        db.execSQL("CREATE INDEX remote_parent ON remote_entries(source_id,parent_path)")
        createReadingLog(db)
        upgradeToV5(db)
        upgradeToV6(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..5 && newVersion == 6) { "Unsupported native library database version" }
        // Nullable/defaulted additions preserve every book, shelf, cached entry and thumbnail.
        if (oldVersion < 2) db.execSQL("ALTER TABLE remote_entries ADD COLUMN file_count INTEGER")
        if (oldVersion < 3) db.execSQL("ALTER TABLE remote_entries ADD COLUMN preview_version INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4) {
            createReadingLog(db)
            // Opening a book used to mark it as being read; a book still on its first page was only opened.
            db.execSQL("UPDATE books SET reading_status='UNREAD' WHERE reading_status='READING' AND current_page=0")
        }
        if (oldVersion < 5) upgradeToV5(db)
        if (oldVersion < 6) upgradeToV6(db)
    }

    /** Chronology covers and origins, shelves as server folders, and reading guides. */
    private fun upgradeToV5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_log ADD COLUMN cover_path TEXT")
        db.execSQL("ALTER TABLE reading_log ADD COLUMN source_id TEXT")
        db.execSQL("ALTER TABLE reading_log ADD COLUMN remote_path TEXT")
        db.execSQL("""CREATE TABLE shelf_paths(
            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, profile_id TEXT NOT NULL,
            source_id TEXT NOT NULL, path TEXT NOT NULL, created_at INTEGER NOT NULL,
            UNIQUE(profile_id,path))""")
        db.execSQL("""CREATE TABLE reading_guides(
            id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL)""")
    }

    /** Guides come from the server: each row remembers its file, and size and date to spot changes. */
    private fun upgradeToV6(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_guides ADD COLUMN profile_id TEXT")
        db.execSQL("ALTER TABLE reading_guides ADD COLUMN remote_path TEXT")
        db.execSQL("ALTER TABLE reading_guides ADD COLUMN remote_size INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reading_guides ADD COLUMN remote_modified INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * Reading history outlives the local copy: deleting a downloaded file frees space without
     * erasing when the book was read. Titles are kept for that reason.
     */
    private fun createReadingLog(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE reading_log(
            book_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, seconds INTEGER NOT NULL DEFAULT 0,
            furthest_page INTEGER NOT NULL DEFAULT 0, page_count INTEGER NOT NULL DEFAULT 0,
            started_at INTEGER, finished_at INTEGER, last_read_at INTEGER NOT NULL)""")
    }

    fun books(shelfName: String? = null): List<BookEntry> = readableDatabase.query(
        "books", null, if (shelfName == null) null else "shelf_name=?",
        shelfName?.let { arrayOf(it) }, null, null, "title COLLATE NOCASE, id",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.book()) } }

    fun book(id: String): BookEntry? = findBook("id=?", arrayOf(id))
    fun downloaded(sourceId: String, path: String): BookEntry? = findBook("source_id=? AND remote_path=?", arrayOf(sourceId, path))
    fun byHash(hash: String): BookEntry? = findBook("content_hash=?", arrayOf(hash))

    private fun findBook(selection: String, args: Array<String>): BookEntry? = readableDatabase.query(
        "books", null, selection, args, null, null, null, "1",
    ).use { if (it.moveToFirst()) it.book() else null }

    fun insertBook(book: BookEntry, hash: String) = transaction {
        book.shelfName?.let { createShelf(it) }
        writableDatabase.insertOrThrow("books", null, ContentValues().apply {
            put("id", book.id); put("title", book.title); put("file_name", book.fileName)
            put("format", book.format); put("local_path", book.localPath); put("cover_path", book.coverPath)
            put("author", book.author); put("description", book.description); put("publisher", book.publisher)
            put("page_count", book.pageCount); put("current_page", book.currentPage)
            put("reading_status", book.readingStatus.name); put("shelf_name", book.shelfName)
            put("source_id", book.sourceId); put("remote_path", book.remotePath)
            put("size", book.size); put("modified", book.modified); put("content_hash", hash)
        })
    }

    fun shelves(): List<ShelfEntry> = readableDatabase.rawQuery(
        "SELECT s.name,COUNT(b.id) AS book_count FROM shelves s LEFT JOIN books b ON b.shelf_name=s.name GROUP BY s.name ORDER BY s.name COLLATE NOCASE", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(ShelfEntry(cursor.string("name"), cursor.int("book_count"))) } }

    fun createShelf(name: String) {
        writableDatabase.insertWithOnConflict("shelves", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteShelf(name: String) { writableDatabase.delete("shelves", "name=?", arrayOf(name)) }

    fun addBookToShelf(id: String, name: String) = transaction {
        createShelf(name)
        check(writableDatabase.update("books", ContentValues().apply { put("shelf_name", name) }, "id=?", arrayOf(id)) == 1) { "Book no longer exists" }
    }

    data class ShelfRename(val bookId: String, val previousName: String, val name: String)

    fun renameBookShelves(renames: List<ShelfRename>) = transaction {
        renames.forEach { rename ->
            createShelf(rename.name)
            check(writableDatabase.update("books", ContentValues().apply { put("shelf_name", rename.name) },
                "id=? AND shelf_name=?", arrayOf(rename.bookId, rename.previousName)) == 1) { "Book shelf changed" }
        }
        renames.map { it.previousName }.distinct().forEach { name ->
            writableDatabase.delete("shelves", "name=? AND NOT EXISTS (SELECT 1 FROM books WHERE shelf_name=?)", arrayOf(name, name))
        }
    }

    fun linkSource(id: String, sourceId: String, path: String, shelf: String) = transaction {
        createShelf(shelf)
        writableDatabase.update("books", ContentValues().apply {
            put("source_id", sourceId); put("remote_path", path); put("shelf_name", shelf)
        }, "id=?", arrayOf(id))
    }

    fun repairBook(id: String, coverPath: String, pageCount: Int) {
        writableDatabase.execSQL("UPDATE books SET cover_path=?, page_count=CASE WHEN page_count>0 THEN page_count ELSE ? END WHERE id=?",
            arrayOf<Any>(coverPath, pageCount.coerceAtLeast(0), id))
    }

    fun deleteBook(id: String) { writableDatabase.delete("books", "id=?", arrayOf(id)) }

    /**
     * Saves the position and adds reading time. Opening a book is not reading it: a book is being
     * read after [READING_SECONDS] past its first pages, and finished on its last page after
     * [FINISHED_SECONDS]. A status never goes back, so rereading keeps a finished book finished.
     */
    fun progress(id: String, page: Int, total: Int, readSeconds: Long) = transaction {
        val book = book(id) ?: return@transaction
        val now = System.currentTimeMillis()
        val log = readingLog(id)
        val seconds = (log?.seconds ?: 0L) + readSeconds.coerceAtLeast(0L)
        val furthest = maxOf(log?.furthestPage ?: 0, page)
        val earned = when {
            total > 0 && furthest >= total - 1 && seconds >= FINISHED_SECONDS -> ReadingStatus.FINISHED
            seconds >= READING_SECONDS && furthest >= minOf(2, (total - 1).coerceAtLeast(0)) -> ReadingStatus.READING
            else -> ReadingStatus.UNREAD
        }
        val status = maxOf(book.readingStatus, earned)
        writableDatabase.update("books", ContentValues().apply {
            put("current_page", page)
            put("page_count", total)
            put("reading_status", status.name)
        }, "id=?", arrayOf(id))
        val started = log?.startedAt ?: now.takeIf { earned >= ReadingStatus.READING }
        writeReadingLog(ReadingLogEntry(id, book.title, seconds, furthest, total, started,
            log?.finishedAt ?: now.takeIf { earned == ReadingStatus.FINISHED }, now,
            log?.coverPath, log?.sourceId ?: book.sourceId, log?.remotePath ?: book.remotePath))
    }

    /** An explicit choice: it records the finish even without the reading time. */
    fun markFinished(id: String) = transaction {
        val book = book(id) ?: return@transaction
        val now = System.currentTimeMillis()
        val last = (book.pageCount - 1).coerceAtLeast(0)
        writableDatabase.update("books", ContentValues().apply {
            put("reading_status", ReadingStatus.FINISHED.name)
            put("current_page", last)
        }, "id=?", arrayOf(id))
        val log = readingLog(id)
        writeReadingLog(ReadingLogEntry(id, book.title, log?.seconds ?: 0L, last, book.pageCount,
            log?.startedAt ?: now, log?.finishedAt ?: now, now,
            log?.coverPath, log?.sourceId ?: book.sourceId, log?.remotePath ?: book.remotePath))
    }

    /** Undoes "read": the book is unread again; a finish without real reading leaves the chronology. */
    fun markUnread(id: String) = transaction {
        val book = book(id) ?: return@transaction
        writableDatabase.update("books", ContentValues().apply {
            put("reading_status", ReadingStatus.UNREAD.name)
            put("current_page", 0)
        }, "id=?", arrayOf(id))
        val log = readingLog(id) ?: return@transaction
        if (log.seconds < READING_SECONDS) writableDatabase.delete("reading_log", "book_id=?", arrayOf(book.id))
        else writeReadingLog(log.copy(finishedAt = null, furthestPage = 0))
    }

    fun readingLogEntry(id: String): ReadingLogEntry? = readingLog(id)

    fun readingLogForRemote(sourceId: String, path: String): ReadingLogEntry? = readableDatabase.rawQuery(
        "SELECT * FROM reading_log WHERE source_id=? AND remote_path=? ORDER BY last_read_at DESC LIMIT 1",
        arrayOf(sourceId, path),
    ).use { if (it.moveToFirst()) it.readingLogEntry() else null }

    /** A guide can record a finished remote comic even before its file is downloaded. */
    fun markRemoteFinished(sourceId: String, path: String, title: String, coverPath: String?) = transaction {
        val existing = readingLogForRemote(sourceId, path)
        val now = System.currentTimeMillis()
        val log = existing ?: ReadingLogEntry("sftp:$sourceId:$path", title, 0L, 0, 0, now, now, now,
            coverPath, sourceId, path)
        writeReadingLog(log.copy(
            title = title.ifBlank { log.title },
            furthestPage = maxOf(log.furthestPage, (log.pageCount - 1).coerceAtLeast(0)),
            startedAt = log.startedAt ?: now,
            finishedAt = log.finishedAt ?: now,
            lastReadAt = now,
            coverPath = log.coverPath ?: coverPath,
            sourceId = sourceId,
            remotePath = path,
        ))
    }

    /** Mirrors the local book action: undo the finish but preserve substantial reading history. */
    fun markRemoteUnread(sourceId: String, path: String) = transaction {
        val log = readingLogForRemote(sourceId, path) ?: return@transaction
        if (log.seconds < READING_SECONDS) writableDatabase.delete("reading_log", "book_id=?", arrayOf(log.bookId))
        else writeReadingLog(log.copy(finishedAt = null, furthestPage = 0))
    }

    /**
     * A book downloaded again takes over the chronology of the same server file: one entry per
     * book, and the read state it already had.
     */
    fun adoptReadingLog(bookId: String, sourceId: String, path: String) = transaction {
        if (readingLog(bookId) != null) return@transaction
        val log = readableDatabase.rawQuery("SELECT * FROM reading_log WHERE source_id=? AND remote_path=? ORDER BY last_read_at DESC LIMIT 1",
            arrayOf(sourceId, path)).use { if (it.moveToFirst()) it.readingLogEntry() else null } ?: return@transaction
        writableDatabase.update("reading_log", ContentValues().apply { put("book_id", bookId) }, "book_id=?", arrayOf(log.bookId))
        val book = book(bookId) ?: return@transaction
        when {
            log.finishedAt != null -> writableDatabase.update("books", ContentValues().apply {
                put("reading_status", ReadingStatus.FINISHED.name); put("current_page", (book.pageCount - 1).coerceAtLeast(0))
            }, "id=?", arrayOf(bookId))
            log.startedAt != null && log.furthestPage > 0 -> writableDatabase.update("books", ContentValues().apply {
                put("reading_status", ReadingStatus.READING.name); put("current_page", log.furthestPage)
            }, "id=?", arrayOf(bookId))
        }
    }

    fun allReadingLog(): List<ReadingLogEntry> = readableDatabase.rawQuery("SELECT * FROM reading_log", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readingLogEntry()) } }

    fun updateLogOrigin(id: String, coverPath: String?, sourceId: String?, remotePath: String?) {
        writableDatabase.update("reading_log", ContentValues().apply {
            put("cover_path", coverPath); put("source_id", sourceId); put("remote_path", remotePath)
        }, "book_id=?", arrayOf(id))
    }

    fun shelfPaths(): List<ShelfPath> = readableDatabase.rawQuery(
        "SELECT * FROM shelf_paths ORDER BY name COLLATE NOCASE", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(ShelfPath(cursor.string("id"), cursor.string("name"),
        cursor.string("profile_id"), cursor.string("source_id"), cursor.string("path"))) } }

    fun addShelfPath(shelf: ShelfPath) {
        writableDatabase.insertWithOnConflict("shelf_paths", null, ContentValues().apply {
            put("id", shelf.id); put("name", shelf.name); put("profile_id", shelf.profileId)
            put("source_id", shelf.sourceId); put("path", shelf.path); put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteShelfPath(id: String) { writableDatabase.delete("shelf_paths", "id=?", arrayOf(id)) }

    fun guides(): List<StoredGuide> = readableDatabase.rawQuery(
        "SELECT * FROM reading_guides ORDER BY remote_path COLLATE NOCASE, title COLLATE NOCASE", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(StoredGuide(cursor.string("id"), cursor.string("title"),
        cursor.string("json"), cursor.long("updated_at"), cursor.optional("profile_id"), cursor.optional("remote_path"),
        cursor.long("remote_size"), cursor.long("remote_modified"))) } }

    fun saveGuide(guide: StoredGuide) {
        writableDatabase.insertWithOnConflict("reading_guides", null, ContentValues().apply {
            put("id", guide.id); put("title", guide.title); put("json", guide.json); put("updated_at", guide.updatedAt)
            put("profile_id", guide.profileId); put("remote_path", guide.remotePath)
            put("remote_size", guide.remoteSize); put("remote_modified", guide.remoteModified)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteGuide(id: String) { writableDatabase.delete("reading_guides", "id=?", arrayOf(id)) }

    /** Catalog files with this title, to find where a cleaned book of the chronology came from. */
    fun remoteFilesByTitle(title: String): List<RemoteEntry> = readableDatabase.rawQuery("""SELECT r.*,NULL AS downloaded_book_id
            FROM remote_entries r WHERE r.is_directory=0 AND r.title=?""", arrayOf(title)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.remoteEntry()) }
    }

    fun isFolderCached(sourceId: String, path: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM remote_folders WHERE source_id=? AND path=? LIMIT 1", arrayOf(sourceId, path)).use { it.moveToFirst() }

    /** A cached catalog entry, for guides and the chronology that know only its path. */
    fun remoteEntry(sourceId: String, path: String): RemoteEntry? = readableDatabase.rawQuery("""SELECT r.*,b.id AS downloaded_book_id
            FROM remote_entries r LEFT JOIN books b ON b.source_id=r.source_id AND b.remote_path=r.path
            WHERE r.source_id=? AND r.path=? LIMIT 1""", arrayOf(sourceId, path)).use { if (it.moveToFirst()) it.remoteEntry() else null }

    fun readingLog(): List<ReadingLogEntry> = readableDatabase.rawQuery(
        "SELECT * FROM reading_log WHERE started_at IS NOT NULL ORDER BY COALESCE(finished_at,started_at) DESC", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readingLogEntry()) } }

    private fun readingLog(id: String): ReadingLogEntry? = readableDatabase.query(
        "reading_log", null, "book_id=?", arrayOf(id), null, null, null, "1",
    ).use { if (it.moveToFirst()) it.readingLogEntry() else null }

    private fun writeReadingLog(entry: ReadingLogEntry) {
        writableDatabase.insertWithOnConflict("reading_log", null, ContentValues().apply {
            put("book_id", entry.bookId); put("title", entry.title); put("seconds", entry.seconds)
            put("furthest_page", entry.furthestPage); put("page_count", entry.pageCount)
            put("started_at", entry.startedAt); put("finished_at", entry.finishedAt); put("last_read_at", entry.lastReadAt)
            put("cover_path", entry.coverPath); put("source_id", entry.sourceId); put("remote_path", entry.remotePath)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remoteFolder(sourceId: String, path: String): RemoteFolder? {
        val cachedAt = readableDatabase.rawQuery(
            "SELECT cached_at FROM remote_folders WHERE source_id=? AND path=?", arrayOf(sourceId, path),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val entries = remoteEntries(sourceId, path).map { if (it.isDirectory) it.copy(previewCovers = folderCovers(sourceId, it.path)) else it }
        return RemoteFolder(path, entries, cachedAt, sourceId)
    }

    /** Entries stored for a folder, including thumbnails prepared before the folder was opened. */
    fun remoteEntries(sourceId: String, path: String): List<RemoteEntry> = readableDatabase.rawQuery("""SELECT r.*,b.id AS downloaded_book_id
            FROM remote_entries r LEFT JOIN books b ON b.source_id=r.source_id AND b.remote_path=r.path
            WHERE r.source_id=? AND r.parent_path=?
            ORDER BY r.is_directory DESC,r.name COLLATE NOCASE""", arrayOf(sourceId, path)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.remoteEntry()) }
    }

    /** Thumbnails of books in a folder, then in its subfolders, as cached so far. */
    fun folderCovers(sourceId: String, path: String, limit: Int = 3): List<String> {
        val prefix = "${path.trimEnd('/')}/"
        return readableDatabase.rawQuery("""SELECT cover_path FROM remote_entries
            WHERE source_id=? AND is_directory=0 AND cover_path IS NOT NULL
            AND (parent_path=? OR substr(parent_path,1,length(?))=?)
            ORDER BY parent_path<>?, parent_path COLLATE NOCASE, name COLLATE NOCASE LIMIT ?""",
            arrayOf(sourceId, path, prefix, prefix, path, (limit * 2).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.filter { java.io.File(it).isFile }.take(limit)
    }

    fun cacheFolder(folder: RemoteFolder) = transaction {
        val directories = folder.entries.filter { it.isDirectory }.mapTo(HashSet()) { it.path }
        val removedDirectories = readableDatabase.query("remote_entries", arrayOf("path"),
            "source_id=? AND parent_path=? AND is_directory=1", arrayOf(folder.sourceId, folder.path),
            null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0).takeUnless { it in directories }?.let { add(it) } }
        }
        // A removed directory (or one replaced by a file) must not keep stale descendants and
        // their thumbnails forever. Prefix comparison treats names containing '%'/'_' literally.
        removedDirectories.forEach { path ->
            val prefix = "$path/"
            val args = arrayOf(folder.sourceId, path, prefix, prefix)
            writableDatabase.delete("remote_folders", "source_id=? AND (path=? OR substr(path,1,length(?))=?)", args)
            writableDatabase.delete("remote_entries", "source_id=? AND (parent_path=? OR substr(parent_path,1,length(?))=?)", args)
        }
        writableDatabase.delete("remote_entries", "source_id=? AND parent_path=?", arrayOf(folder.sourceId, folder.path))
        folder.entries.forEach { cacheEntry(folder.sourceId, folder.path, it) }
        writableDatabase.insertWithOnConflict("remote_folders", null, ContentValues().apply {
            put("source_id", folder.sourceId); put("path", folder.path); put("cached_at", folder.cachedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun cacheEntry(sourceId: String, parentPath: String, entry: RemoteEntry) {
        writableDatabase.insertWithOnConflict("remote_entries", null, ContentValues().apply {
            put("source_id", sourceId); put("path", entry.path); put("parent_path", parentPath)
            put("name", entry.name); put("is_directory", if (entry.isDirectory) 1 else 0)
            put("size", entry.size); put("modified", entry.modified); put("title", entry.title)
            put("format", BookFormats.format(entry.name)); put("cover_path", entry.coverPath)
            put("author", entry.author); put("description", entry.description)
            put("publisher", entry.publisher); put("page_count", entry.pageCount)
            put("preview_version", entry.previewVersion)
            put("file_count", entry.fileCount)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Updates only an already cached directory; never creates a partial folder listing. */
    fun updateRemoteFileCount(sourceId: String, path: String, count: Int): Int {
        require(count >= 0) { "El número de archivos no puede ser negativo" }
        return writableDatabase.update("remote_entries", ContentValues().apply { put("file_count", count) },
            "source_id=? AND path=? AND is_directory=1", arrayOf(sourceId, path))
    }

    fun remoteCoverPaths(): Set<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT cover_path FROM remote_entries WHERE cover_path IS NOT NULL", null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    companion object {
        const val READING_SECONDS = 2 * 60L
        const val FINISHED_SECONDS = 10 * 60L
    }

    private inline fun <T> transaction(action: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try { action().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private fun Cursor.book() = BookEntry(
        id = string("id"), title = string("title"), fileName = string("file_name"),
        format = string("format"), localPath = string("local_path"), coverPath = optional("cover_path"),
        author = string("author"), description = string("description"), publisher = string("publisher"),
        pageCount = int("page_count"), currentPage = int("current_page"),
        readingStatus = runCatching { ReadingStatus.valueOf(string("reading_status")) }.getOrDefault(ReadingStatus.UNREAD),
        shelfName = optional("shelf_name"), sourceId = optional("source_id"), remotePath = optional("remote_path"),
        size = long("size"), modified = long("modified"),
    )

    private fun Cursor.readingLogEntry() = ReadingLogEntry(
        bookId = string("book_id"), title = string("title"), seconds = long("seconds"),
        furthestPage = int("furthest_page"), pageCount = int("page_count"),
        startedAt = optionalLong("started_at"), finishedAt = optionalLong("finished_at"), lastReadAt = long("last_read_at"),
        coverPath = optional("cover_path"), sourceId = optional("source_id"), remotePath = optional("remote_path"),
    )

    private fun Cursor.remoteEntry() = RemoteEntry(
        name = string("name"), path = string("path"), isDirectory = int("is_directory") != 0,
        size = long("size"), modified = long("modified"), title = string("title"), format = string("format"),
        coverPath = optional("cover_path"), author = string("author"), description = string("description"),
        publisher = string("publisher"), pageCount = int("page_count"), downloadedBookId = optional("downloaded_book_id"),
        sourceId = string("source_id"), previewVersion = int("preview_version"),
        fileCount = optionalInt("file_count"),
    )

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)) ?: ""
    private fun Cursor.optional(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.optionalInt(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.optionalLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
}
