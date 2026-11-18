package app.glowlingo

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class Tr(val main: String, val extra: String)

/**
 * Çeviri: önce LRU önbellek (0 ms), sonra Google (çevrimiçi, bağlantı önceden ısıtılır)
 * veya ML Kit (çevrimdışı, cihaz üzerinde). Geri çağrı her zaman ana iş parçacığında gelir.
 */
object Tx {
    private val io = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Tr>(1000)
    private val kit = HashMap<String, com.google.mlkit.nl.translate.Translator>()

    fun translate(text: String, cb: (Tr?) -> Unit) {
        val key = (if (Prefs.offline) "o" else "g") + Prefs.src + ">" + Prefs.tgt + ":" + text.lowercase(Locale.ROOT)
        cache.get(key)?.let { cb(it); return }
        if (Prefs.offline) {
            offline(text, key, cb)
        } else {
            io.execute {
                val r = try { online(text) } catch (e: Exception) { null }
                if (r != null) cache.put(key, r)
                ui.post { cb(r) }
            }
        }
    }

    /** Servis açılınca TLS bağlantısını önceden kur: ilk çeviri gecikmesiz gelir. */
    fun warm() {
        if (Prefs.offline) return
        io.execute { try { online("a") } catch (e: Exception) { } }
    }

    private fun online(w: String): Tr? {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=${Prefs.src}&tl=${Prefs.tgt}&dt=t&dt=bd&q=" + URLEncoder.encode(w, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 2500
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (c.responseCode != 200) return null
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val r = JSONArray(body)
        val main = StringBuilder()
        val a0 = r.getJSONArray(0)
        for (i in 0 until a0.length()) {
            val seg = a0.optJSONArray(i) ?: continue
            if (seg.isNull(0)) continue
            main.append(seg.getString(0))
        }
        val extra = StringBuilder()
        val a1 = r.optJSONArray(1)
        if (a1 != null) {
            for (i in 0 until minOf(2, a1.length())) {
                val e = a1.getJSONArray(i)
                val t = e.getJSONArray(1)
                val terms = (0 until minOf(4, t.length())).joinToString(", ") { t.getString(it) }
                extra.append(e.getString(0)).append(": ").append(terms).append('\n')
            }
        }
        if (main.isEmpty()) return null
        return Tr(main.toString(), extra.toString().trim())
    }

    private fun offline(w: String, key: String, cb: (Tr?) -> Unit) {
        val k = Prefs.src + ">" + Prefs.tgt
        val t = kit.getOrPut(k) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.fromLanguageTag(Prefs.src) ?: TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.fromLanguageTag(Prefs.tgt) ?: TranslateLanguage.TURKISH)
                    .build()
            )
        }
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                t.translate(w)
                    .addOnSuccessListener { s -> val r = Tr(s, ""); cache.put(key, r); cb(r) }
                    .addOnFailureListener { cb(null) }
            }
            .addOnFailureListener { cb(null) }
    }
}
