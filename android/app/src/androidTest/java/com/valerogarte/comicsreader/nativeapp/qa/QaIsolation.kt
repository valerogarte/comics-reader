package com.valerogarte.comicsreader.nativeapp.qa

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Each test gets independent files, databases and preferences; real app data is never cleared. */
internal class QaApplication(baseContext: Context) : Application() {
    private val namespace = "native-qa-${UUID.randomUUID()}-"
    val sandboxRoot = File(baseContext.cacheDir, namespace).apply { check(mkdirs()) }
    private val preferenceNames = CopyOnWriteArraySet<String>()

    init { attachBaseContext(baseContext) }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(sandboxRoot, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(sandboxRoot, "cache").apply { mkdirs() }
    override fun getDataDir(): File = sandboxRoot
    override fun getDatabasePath(name: String): File = File(File(sandboxRoot, "databases").apply { mkdirs() }, name)
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolatedName = namespace + name
        preferenceNames += isolatedName
        return baseContext.getSharedPreferences(isolatedName, mode)
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory,
            SQLiteDatabase.CREATE_IF_NECESSARY or if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0,
            errorHandler)
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))

    fun dispose() {
        preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
        sandboxRoot.deleteRecursively()
    }
}
