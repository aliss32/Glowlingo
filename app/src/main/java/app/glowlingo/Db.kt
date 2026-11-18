package app.glowlingo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/** Yıldızlı kelimeler. Arama O(1) için bellekte tutulur, kalıcılık için SQLite. */
object Db {
    private class Helper(c: Context) : SQLiteOpenHelper(c, "words.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE w(word TEXT PRIMARY KEY, tr TEXT, ts INTEGER)")
        }
        override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
    }

    private lateinit var helper: Helper
    private val map = ConcurrentHashMap<String, String>()

    @Synchronized
    fun init(c: Context) {
        if (::helper.isInitialized) return
        helper = Helper(c.applicationContext)
        helper.readableDatabase.rawQuery("SELECT word, tr FROM w", null).use {
            while (it.moveToNext()) map[it.getString(0)] = it.getString(1) ?: ""
        }
    }

    fun has(w: String) = map.containsKey(w)
    fun size() = map.size

    fun all(): List<Pair<String, String>> =
        map.entries.map { it.key to it.value }.sortedBy { it.first }

    fun add(w: String, tr: String) {
        map[w] = tr
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO w VALUES(?,?,?)", arrayOf<Any>(w, tr, System.currentTimeMillis())
        )
    }

    fun remove(w: String) {
        map.remove(w)
        helper.writableDatabase.execSQL("DELETE FROM w WHERE word=?", arrayOf<Any>(w))
    }
}
