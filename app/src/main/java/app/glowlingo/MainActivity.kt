package app.glowlingo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var settingsPane: ScrollView
    private lateinit var tabWords: Button
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var spSrc: Spinner
    private lateinit var spTgt: Spinner
    private var rows: List<Pair<String, String>> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Prefs.init(this); Db.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(8))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabSet = Button(this).apply { text = "Ayarlar" }
        tabWords = Button(this)
        tabs.addView(tabSet, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabWords, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)

        settingsPane = ScrollView(this).apply { addView(buildSettings()) }
        root.addView(settingsPane, LinearLayout.LayoutParams(-1, 0, 1f))

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list = ListView(this).apply { visibility = View.GONE; this.adapter = this@MainActivity.adapter }
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            AlertDialog.Builder(this)
                .setTitle(r.first)
                .setMessage("Kayıtlı kelimelerden silinsin mi?")
                .setPositiveButton("Sil") { _, _ -> Db.remove(r.first); reloadWords(); GlowService.inst?.rescan() }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        tabSet.setOnClickListener { settingsPane.visibility = View.VISIBLE; list.visibility = View.GONE }
        tabWords.setOnClickListener { reloadWords(); settingsPane.visibility = View.GONE; list.visibility = View.VISIBLE }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (GlowService.inst != null) "Servis çalışıyor ✓"
        else "Servis kapalı. Aşağıdaki düğmeyle Erişilebilirlik ayarlarını aç ve GlowLingo'yu etkinleştir."
        reloadWords()
    }

    private fun reloadWords() {
        rows = Db.all()
        adapter.clear()
        adapter.addAll(rows.map { "${it.first}  —  ${it.second}" })
        tabWords.text = "Kelimeler (${rows.size})"
    }

    private fun buildSettings(): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(24)) }
        status = TextView(this).apply { textSize = 15f }
        c.addView(status)

        c.addView(Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        c.addView(Button(this).apply {
            text = "Uygulama bilgisi (Kısıtlı ayarlara izin ver)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        c.addView(Switch(this).apply {
            text = "GlowLingo açık (balon + parlatma + seçim çevirisi)"
            isChecked = Prefs.enabled
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, on -> Prefs.enabled = on; GlowService.inst?.applyEnabled() }
        })
        c.addView(Switch(this).apply {
            text = "Yıldızlı kelimeleri parlat"
            isChecked = Prefs.glow
            setPadding(0, dp(4), 0, dp(12))
            setOnCheckedChangeListener { _, on -> Prefs.glow = on; GlowService.inst?.rescan() }
        })

        c.addView(TextView(this).apply { text = "Kaynak dil → Hedef dil"; setPadding(0, dp(8), 0, 0) })
        val names = LANGS.map { it.second }
        val codes = LANGS.map { it.first }
        spSrc = Spinner(this)
        spTgt = Spinner(this)
        for (sp in listOf(spSrc, spTgt)) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        }
        spSrc.setSelection(codes.indexOf(Prefs.src).coerceAtLeast(0))
        spTgt.setSelection(codes.indexOf(Prefs.tgt).coerceAtLeast(0))
        spSrc.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { Prefs.src = codes[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spTgt.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { Prefs.tgt = codes[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        c.addView(spSrc)
        c.addView(spTgt)
        c.addView(Button(this).apply {
            text = "⇄ Dilleri değiştir"
            setOnClickListener {
                val a = Prefs.src; Prefs.src = Prefs.tgt; Prefs.tgt = a
                spSrc.setSelection(codes.indexOf(Prefs.src).coerceAtLeast(0))
                spTgt.setSelection(codes.indexOf(Prefs.tgt).coerceAtLeast(0))
            }
        })

        c.addView(TextView(this).apply { text = "Çeviri motoru"; setPadding(0, dp(12), 0, 0) })
        val eng = Spinner(this)
        eng.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Çevrimiçi (Google) — sözlük anlamları var", "Çevrimdışı (ML Kit) — ilk seferde model iner")
        )
        eng.setSelection(if (Prefs.offline) 1 else 0)
        eng.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { Prefs.offline = pos == 1 }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        c.addView(eng)

        c.addView(TextView(this).apply {
            text = "\nKullanım:\n• Seçilebilen metinde kelimeye uzun bas → çeviri kartı kendiliğinden açılır.\n" +
                "• Seçilemeyen metinde balona dokun (yeşil olur) → kelimeye uzun bas. Çıkmak için balona tekrar dokun.\n" +
                "• Kartta ☆ ile kaydet; kayıtlı kelimeler her yerde parlar.\n" +
                "• Hızlı aç/kapat: Hızlı Ayarlar paneline GlowLingo karosunu ekle."
            textSize = 13f
        })
        return c
    }
}
