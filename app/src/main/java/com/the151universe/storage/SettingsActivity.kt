package com.the151universe.storage

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Impostazioni: mostrare o nascondere le sezioni — i posti in alto (Media, Docs, Cloud), i tipi di Docs
 * e gli album di Media. Tutto in «settings», le stesse chiavi che leggono MainActivity e PhotosPane; al ritorno
 * MainActivity rilegge ([MainActivity.applySections]). Almeno un posto resta sempre acceso.
 */
class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private val placeSwitches = LinkedHashMap<String, MaterialSwitch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // vestita come il resto dell'app: lastra di cartone, un foglietto d'acetato per
        // gruppo, righe col bollino della tinta del posto o del tipo, filo da 1dp fra le righe dopo il bollino
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ContextCompat.getColor(context, R.color.board)) }
        val bar = MaterialToolbar(this).apply {
            title = getString(R.string.settings)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.seam)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }
        root.addView(ScrollView(this).apply { clipToPadding = false; addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // l'acetato della barra sale sotto la barra di stato, come nella schermata principale; la lista scorre sotto quella di navigazione
            v.setPadding(b.left, 0, b.right, 0); bar.setPadding(bar.paddingLeft, b.top, bar.paddingRight, bar.paddingBottom)
            list.setPadding(0, 0, 0, dp(32) + b.bottom); insets
        }

        header(R.string.set_places, null)
        for ((key, label, hue) in listOf(Triple(MainActivity.PLACE_PHOTOS, R.string.place_photos, R.color.place_photo),
                Triple(MainActivity.PLACE_DOCS, R.string.place_docs, R.color.place_docs), Triple(MainActivity.PLACE_FOLDERS, R.string.place_folders, R.color.place_folder), Triple(MainActivity.PLACE_CLOUD, R.string.place_cloud, R.color.place_cloud))) {
            placeSwitches[key] = row(getString(label), key !in hiddenPlaces(this), swatch(ContextCompat.getColor(this, hue))) { on -> setHidden(PLACES, key, !on); lockLastPlace() }
        }
        lockLastPlace()

        header(R.string.set_docs, R.string.set_docs_note)
        val savedDocs = TabDrag.saved(prefs, "libOrder")
        for (c in TabDrag.arrange(Cat.entries.filter { it != Cat.ALL }, savedDocs) { it.name }) {
            row(c.label, c.name !in hiddenDocs(this), swatch(Sections.hue(this, c))) { on -> setHidden(DOCS, c.name, !on) }
        }

        header(R.string.set_links, R.string.set_links_note)
        linksCard = card
        renderLinks()

        header(R.string.set_marks, R.string.set_marks_note)
        marksCard = card
        renderMarks()

        header(R.string.set_albums, R.string.set_albums_note)
        val hidden = prefs.getStringSet(ALBUMS, emptySet())!!
        val known = (Gallery.items + Gallery.offline).map { it.album }.filter { it.isNotEmpty() }.distinct()
        val albums = TabDrag.arrange((known + hidden.filter { it !in known }).sortedWith(String.CASE_INSENSITIVE_ORDER), TabDrag.saved(prefs, "phOrder")) { it }
        if (albums.isEmpty()) { card?.visibility = View.GONE; note(R.string.set_albums_none) } // niente foglietto vuoto
        for (a in albums) {
            val cloud = a.startsWith(Gallery.CLOUD_ALBUM)
            val label = if (cloud) a.removePrefix(Gallery.CLOUD_ALBUM).substringAfterLast('/').ifEmpty { "Cloud" } else a
            row(label, a !in hidden, icon(if (cloud) R.drawable.ic_cloud else R.drawable.ic_photos)) { on -> setHidden(ALBUMS, a, !on) }
        }
    }

    // ---- Linked folders: una riga per collegamento, con lo stato; tocco = sincronizza, decidi, scollega

    private var linksCard: LinearLayout? = null

    private fun renderLinks() {
        val box = linksCard ?: return
        box.removeAllViews()
        val all = Links.all(this)
        if (all.isEmpty()) { plainRow(box, getString(R.string.set_links_none), null) {}; return }
        for (l in all) plainRow(box, "${java.io.File(l.local).name}  ⇄  ${l.remote.substringAfterLast('/').ifEmpty { "Cloud" }}\n${LinkUi.statusLine(this, l)}",
            icon(R.drawable.ic_cloud)) { LinkUi.actions(this, l) { renderLinks() } }
    }

    override fun onResume() { super.onResume(); renderLinks() }

    // ---- Folder marks: i segni che l'utente inventa e mette sulle cartelle del cloud (Mark as…)

    private var marksCard: LinearLayout? = null

    private fun renderMarks() {
        val box = marksCard ?: return
        box.removeAllViews()
        val all = Marks.all(this)
        for (m in all) plainRow(box, m.name, swatch(m.color)) { editMark(m) }
        plainRow(box, getString(R.string.mark_add), icon(R.drawable.ic_new_folder)) { editMark(null) }
    }

    /** Una riga che si tocca (senza interruttore): bollino e nome. */
    private fun plainRow(box: LinearLayout, label: String, lead: android.graphics.drawable.Drawable?, onTap: () -> Unit) {
        if (box.childCount > 0) box.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.seam)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(50) })
        box.addView(TextView(this).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_Viewer_Body)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            minHeight = dp(56)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            lead?.let { d -> d.setBounds(0, 0, dp(20), dp(20)); setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(14) }
            background = ContextCompat.getDrawable(context, R.drawable.row_bg)
            setOnClickListener { onTap() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Nome e colore di un segno ([m] null = nuovo); «Delete» lo toglie da tutte le cartelle. */
    private fun editMark(m: Mark?) {
        val colors = Marks.PALETTE.map { ContextCompat.getColor(this, it) }
        var color = m?.color ?: colors.firstOrNull { c -> Marks.all(this).none { it.color == c } } ?: colors[0]
        val pad = dp(24)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        val til = com.google.android.material.textfield.TextInputLayout(this).apply { hint = getString(R.string.mark_name) }
        val field = com.google.android.material.textfield.TextInputEditText(til.context).apply { setText(m?.name ?: ""); setSingleLine() }
        til.addView(field); box.addView(til)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(4)) }
        fun paintDots() {
            for (i in 0 until dots.childCount) {
                val c = colors[i]
                dots.getChildAt(i).background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
                    if (c == color) setStroke(dp(3), ContextCompat.getColor(this@SettingsActivity, R.color.ink))
                }
            }
        }
        for (c in colors) dots.addView(View(this).apply { setOnClickListener { color = c; paintDots() }; contentDescription = getString(R.string.mark_color) },
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        paintDots()
        box.addView(dots)
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(if (m == null) R.string.mark_add else R.string.mark_edit_one)
            .setView(box).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val list = Marks.all(this).toMutableList()
                if (m == null) list += Mark("m" + System.currentTimeMillis().toString(36), name, color)
                else list.replaceAll { if (it.id == m.id) Mark(m.id, name, color) else it }
                Marks.saveDefs(this, list); renderMarks()
            }
        if (m != null) b.setNeutralButton(R.string.delete) { _, _ ->
            Marks.saveDefs(this, Marks.all(this).filter { it.id != m.id }); renderMarks()
        }
        b.show()
    }

    /** L'ultimo posto acceso non si spegne: senza posti l'app sarebbe vuota. */
    private fun lockLastPlace() {
        val on = placeSwitches.values.filter { it.isChecked }
        for (s in placeSwitches.values) s.isEnabled = !(s.isChecked && on.size == 1)
    }

    private fun setHidden(key: String, item: String, hide: Boolean) {
        val now = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (hide) now += item else now -= item
        prefs.edit().putStringSet(key, now).apply()
    }

    /** Il foglietto del gruppo aperto: le righe vanno dentro di lui. */
    private var card: LinearLayout? = null

    /** Titolo del gruppo sulla lastra (condensato maiuscolo, come le linguette), la nota sotto in Garamond (è una frase). */
    private fun header(title: Int, sub: Int?) {
        list.addView(TextView(this).apply {
            setText(title)
            setTextAppearance(R.style.TextAppearance_Viewer_Tab)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setPadding(dp(24), dp(28), dp(24), dp(6))
        })
        if (sub != null) note(sub)
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.slip)
            setPadding(0, dp(4), 0, dp(4))
        }
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16); marginEnd = dp(16); topMargin = dp(6)
        })
    }

    private fun note(text: Int) {
        list.addView(TextView(this).apply {
            setText(text)
            setTextAppearance(R.style.TextAppearance_Viewer_Prose) // una frase: Garamond spiega (DESIGN.md, tre voci)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
            setPadding(dp(24), 0, dp(24), dp(2))
        })
    }

    /** Il bollino della tinta (posto o tipo di Docs): 20dp, angoli tagliati da 4dp, come i bollini delle righe. */
    private fun swatch(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(4).toFloat(); setColor(color); setSize(dp(20), dp(20))
    }

    /** L'icona di un album (foto del telefono o cartella del cloud tenuta qui), smorzata. */
    private fun icon(res: Int) = ContextCompat.getDrawable(this, res)?.mutate()?.apply {
        setTint(ContextCompat.getColor(this@SettingsActivity, R.color.ink_muted)); setBounds(0, 0, dp(20), dp(20))
    }

    /** Una riga: bollino, nome, interruttore; tutta la riga si tocca (bersaglio grande, onda al tocco). */
    private fun row(label: String, on: Boolean, lead: android.graphics.drawable.Drawable?, changed: (Boolean) -> Unit): MaterialSwitch {
        val box = card ?: list
        if (box.childCount > 0) box.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.seam)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(50) }) // il filo parte dopo il bollino
        val sw = MaterialSwitch(this).apply {
            text = label
            isChecked = on
            setTextAppearance(R.style.TextAppearance_Viewer_Body)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            minHeight = dp(56)
            setPadding(dp(16), 0, dp(14), 0)
            lead?.let { d -> d.setBounds(0, 0, dp(20), dp(20)); setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(14) }
            background = ContextCompat.getDrawable(context, R.drawable.row_bg)
            setOnCheckedChangeListener { _, c -> changed(c) }
        }
        box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sw
    }

    companion object {
        const val PLACES = "hiddenPlaces"
        const val DOCS = "libHidden"
        const val ALBUMS = "phHidden"
        fun hiddenPlaces(ctx: Context): Set<String> = ctx.getSharedPreferences("settings", MODE_PRIVATE).getStringSet(PLACES, emptySet())!!
        fun hiddenDocs(ctx: Context): Set<String> = ctx.getSharedPreferences("settings", MODE_PRIVATE).getStringSet(DOCS, emptySet())!!
    }
}
