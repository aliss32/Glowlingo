package app.glowlingo

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("p", Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = sp.getBoolean("en", true)
        set(v) { sp.edit().putBoolean("en", v).apply() }

    var glow: Boolean
        get() = sp.getBoolean("glow", true)
        set(v) { sp.edit().putBoolean("glow", v).apply() }

    var offline: Boolean
        get() = sp.getBoolean("off", false)
        set(v) { sp.edit().putBoolean("off", v).apply() }

    var src: String
        get() = sp.getString("src", "en") ?: "en"
        set(v) { sp.edit().putString("src", v).apply() }

    var tgt: String
        get() = sp.getString("tgt", "tr") ?: "tr"
        set(v) { sp.edit().putString("tgt", v).apply() }

    var bx: Int
        get() = sp.getInt("bx", -1)
        set(v) { sp.edit().putInt("bx", v).apply() }

    var by: Int
        get() = sp.getInt("by", -1)
        set(v) { sp.edit().putInt("by", v).apply() }
}
