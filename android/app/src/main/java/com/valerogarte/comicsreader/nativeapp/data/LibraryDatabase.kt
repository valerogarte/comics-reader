package com.valerogarte.comicsreader.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.valerogarte.comicsreader.nativeapp.model.BookEntry
import com.valerogarte.comicsreader.nativeapp.model.BookFormats
import com.valerogarte.comicsreader.nativeapp.model.ReadingStatus
import com.valerogarte.comicsreader.nativeapp.model.RemoteEntry
import com.valerogarte.comicsreader.nativeapp.model.RemoteFolder
import com.valerogarte.comicsreader.nativeapp.model.ShelfEntry

/** The native library has its own database; it never opens or migrates browser storage. */
internal class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "native-library.sqlite", null, 2) {
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
            PRIMARY KEY(source_id,path))""")
        db.execSQL("CREATE INDEX remote_parent ON remote_entries(source_id,parent_path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported native library database version" }
        // A nullable addition preserves every book, shelf, cached entry and existing thumbnail.
        db.execSQL("ALTER TABLE remote_entries ADD COLUMN file_count INTEGER")
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

    fun linkSource(id: String, sourceId: String, path: String, shelf: String) = transaction {
        createShelf(shelf)
        writableDatabase.update("books", ContentValues().apply {
            put("source_id", sourceId); put("remote_path", path); put("shelf_name", shelf)
        }, "id=?", arrayOf(id))
    }

    fun deleteBook(id: String) { writableDatabase.delete("books", "id=?", arrayOf(id)) }

    fun progress(id: String, page: Int, total: Int) {
        writableDatabase.update("books", ContentValues().apply {
            put("current_page", page)
            put("page_count", total)
            put("reading_status", when {
                total > 0 && page >= total - 1 -> ReadingStatus.FINISHED.name
                else -> ReadingStatus.READING.name
            })
        }, "id=?", arrayOf(id))
    }

    fun remoteFolder(sourceId: String, path: String): RemoteFolder? {
        val cachedAt = readableDatabase.rawQuery(
            "SELECT cached_at FROM remote_folders WHERE source_id=? AND path=?", arrayOf(sourceId, path),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val entries = readableDatabase.rawQuery("""SELECT r.*,b.id AS downloaded_book_id
            FROM remote_entries r LEFT JOIN books b ON b.source_id=r.source_id AND b.remote_path=r.path
            WHERE r.source_id=? AND r.parent_path=?
            ORDER BY r.is_directory DESC,r.name COLLATE NOCASE""", arrayOf(sourceId, path)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.remoteEntry()) }
        }
        return RemoteFolder(path, entries, cachedAt, sourceId)
    }

    fun cacheFolder(folder: RemoteFolder) = transaction {
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

    private fun Cursor.remoteEntry() = RemoteEntry(
        name = string("name"), path = string("path"), isDirectory = int("is_directory") != 0,
        size = long("size"), modified = long("modified"), title = string("title"), format = string("format"),
        coverPath = optional("cover_path"), author = string("author"), description = string("description"),
        publisher = string("publisher"), pageCount = int("page_count"), downloadedBookId = optional("downloaded_book_id"),
        sourceId = string("source_id"),
        fileCount = optionalInt("file_count"),
    )

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)) ?: ""
    private fun Cursor.optional(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.optionalInt(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
}
