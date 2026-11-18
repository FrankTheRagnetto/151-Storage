package com.the151universe.storage

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * La griglia delle foto, uguale ovunque. Media è sempre a griglia; in Telefono e nel Cloud
 * una cartella dove almeno metà dei file sono foto o video si vede a griglia (le sottocartelle e gli altri file restano
 * righe sopra e sotto), a meno che l'utente non scelga «Mostra come elenco» o «come griglia» dal menu.
 */
object MediaGrid {
    const val CELL = 2

    /**
     * La vista, uguale in Media, Cloud e Telefono: 0 = elenco, 2/3/4 = foto e video in griglia con tanti per
     * riga. Il bottone in alto la fa girare: elenco → 2 → 3 → 4 → elenco. Ogni posto ricorda la sua.
     * (Prima c'era solo «griglia o elenco», a 4: `view_<posto>` 1 = elenco, 2 = griglia; si legge una volta sola.)
     */
    fun cols(ctx: android.content.Context, place: String, default: Int): Int {
        val p = ctx.getSharedPreferences("settings", 0)
        if (p.contains("cols_$place")) return p.getInt("cols_$place", default)
        return when (p.getInt("view_$place", 0)) { 1 -> 0; 2 -> 4; else -> default }
    }

    fun cycle(ctx: android.content.Context, place: String, default: Int): Int {
        val next = when (cols(ctx, place, default)) { 0 -> 2; 2 -> 3; 3 -> 4; else -> 0 }
        ctx.getSharedPreferences("settings", 0).edit().putInt("cols_$place", next).apply()
        return next
    }

    /** L'icona del bottone dice la vista di adesso. */
    fun icon(cols: Int) = when (cols) { 0 -> R.drawable.ic_view_list; 2 -> R.drawable.ic_view_grid2; 3 -> R.drawable.ic_view_grid3; else -> R.drawable.ic_view_grid4 }

    /** Una griglia dove le righe che non sono celle prendono tutta la larghezza; [setCols] cambia quante per riga. */
    fun layout(rv: RecyclerView, cols: Int = 3): GridLayoutManager {
        val glm = GridLayoutManager(rv.context, maxOf(1, cols))
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (rv.adapter?.getItemViewType(position) == CELL) 1 else glm.spanCount
        }
        rv.layoutManager = glm
        return glm
    }

    fun setCols(rv: RecyclerView, cols: Int) {
        (rv.layoutManager as? GridLayoutManager)?.let { if (it.spanCount != maxOf(1, cols)) it.spanCount = maxOf(1, cols) }
    }

    /** Veste una cella (item_photo): miniatura o icona, durata dei video, segno, casella di selezione. */
    fun bind(v: View, key: String, px: Int, isVideo: Boolean, duration: String?, mark: Int, selecting: Boolean, on: Boolean,
             quick: (() -> Bitmap?)? = null, durationLater: (() -> String?)? = null, make: () -> Bitmap?) {
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        val dur = v.findViewById<TextView>(R.id.duration)
        val cloud = v.findViewById<FrameLayout>(R.id.cloudMark)
        val check = v.findViewById<ImageView>(R.id.check)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        val same = thumb.tag == key // la stessa cella ridisegnata (scelta, tolta): la scala si muove; una cella nuova no
        Thumbs.load(thumb, key, px, fallback = { placeholder(v, isVideo) }, quick = quick) {
            val b = make()
            // la durata arriva insieme all'anteprima: se la cella è ancora lei, si scrive
            if (isVideo && duration == null) durationLater?.invoke()?.let { s -> v.post { if (thumb.tag == key) dur.text = s } }
            b
        }
        dur.isVisible = isVideo
        dur.text = duration ?: "▶"
        cloud.isVisible = mark != 0
        if (mark != 0) (cloud.getChildAt(0) as ImageView).setImageResource(mark)
        if (same) Motion.fade(check, selecting) else Motion.setShown(check, selecting) // entrando in selezione le caselle sfumano
        check.background = ContextCompat.getDrawable(v.context, if (on) R.drawable.cell_check_on else R.drawable.cell_check_off)
        Motion.scale(thumb, if (on) 0.84f else 1f, animate = same) // rientra morbida
    }

    /** Senza miniatura (HEIC, PNG, video del cloud): l'icona del tipo, disegnata una volta sola. */
    private val placeholders = HashMap<Boolean, Bitmap>()
    private fun placeholder(v: View, video: Boolean): Bitmap? = synchronized(placeholders) {
        placeholders.getOrPut(video) {
            val ctx = v.context
            val size = ctx.dp(96)
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            c.drawColor(ContextCompat.getColor(ctx, R.color.acetate_sunk))
            val d = ContextCompat.getDrawable(ctx, if (video) R.drawable.ic_play else R.drawable.ic_photos)!!.mutate()
            d.setTint(ContextCompat.getColor(ctx, R.color.ink_muted))
            val i = ctx.dp(32); d.setBounds((size - i) / 2, (size - i) / 2, (size + i) / 2, (size + i) / 2); d.draw(c)
            b
        }
    }
}

/**
 * L'ordine, uguale in Media, Cloud, Telefono e Documenti: il bottone accanto a quello della vista lo cambia
 * a ogni tocco (niente finestre) e dice a parole com'è ordinato. Ogni posto ricorda il suo, anche chiudendo l'app.
 * 0 dal più nuovo, 1 dal più vecchio, 2 nome, 3 dal più grande, 4 tipo (non in Media: lì sono tutte foto e video).
 * Le cartelle restano sempre in cima.
 */
object Order {
    const val NEWEST = 0; const val OLDEST = 1; const val NAME = 2; const val SIZE = 3; const val TYPE = 4
    private val SHORT = arrayOf("Newest", "Oldest", "A–Z", "Largest", "Type")
    private val LONG = arrayOf("newest first", "oldest first", "by name", "largest first", "by type")

    /** Prima ogni posto aveva i suoi numeri (`clSort`/`fdSort`: 0 nome, 1 data, 2 dimensione, 3 tipo; `libSort`: 0 data,
     *  1 nome, 2 dimensione; `phSort` = questi): si leggono una volta. */
    fun get(ctx: android.content.Context, place: String, default: Int): Int {
        val p = ctx.getSharedPreferences("settings", 0)
        if (p.contains("order_$place")) return valid(place, p.getInt("order_$place", default))
        val old = mapOf("photos" to "phSort", "cloud" to "clSort", "folders" to "fdSort", "docs" to "libSort")[place] ?: return default
        if (!p.contains(old)) return default
        val v = p.getInt(old, 0)
        return when (place) {
            "photos" -> valid(place, v)
            "docs" -> when (v) { 1 -> NAME; 2 -> SIZE; else -> NEWEST }
            else -> when (v) { 1 -> NEWEST; 2 -> SIZE; 3 -> TYPE; else -> NAME }
        }
    }

    /** Gli ordini di un posto: in Media niente A–Z né tipo. */
    fun choices(place: String) = if (place == "photos") intArrayOf(NEWEST, OLDEST, SIZE) else intArrayOf(NEWEST, OLDEST, NAME, SIZE, TYPE)

    /** Un ordine che il posto non ha più (Media su «nome», di prima) torna al primo. */
    fun valid(place: String, order: Int) = if (order in choices(place)) order else choices(place)[0]

    fun cycle(ctx: android.content.Context, place: String, current: Int): Int {
        val c = choices(place)
        val next = c[(c.indexOf(current) + 1) % c.size]
        ctx.getSharedPreferences("settings", 0).edit().putInt("order_$place", next).apply()
        return next
    }

    fun short(order: Int) = SHORT[order.coerceIn(0, 4)]
    fun long(order: Int) = LONG[order.coerceIn(0, 4)]
}

/**
 * La riga del conteggio: UNA sola, sotto la barra in alto, sempre nello stesso posto qualunque sezione sia aperta
 * La linea non si sposta passando da una sezione all'altra. A sinistra il testo
 * del posto aperto («3 folders · 12 files»), a destra due bottoni fermi: l'ordine (icona + parola DENTRO il bottone,
 * larghezza fissa) e la vista (elenco, 2, 3, 4 per riga). Un tocco = il prossimo. Ogni posto registra il suo testo e
 * i suoi gesti; [activate] mostra quelli del posto aperto. Il riquadro è un velo più scuro, più scuro ancora al tocco.
 */
object StatusBar {
    private class Entry(val status: TextView, val onSort: () -> Unit, val onView: () -> Unit, val ready: () -> Boolean) {
        var order = 0; var cols = 0
    }
    private val entries = HashMap<String, Entry>()
    private var current: String? = null

    /** Quello che un posto tiene in mano: aggiorna la parola e l'icona quando cambiano. */
    class Buttons(private val act: android.app.Activity, private val place: String) {
        fun show(order: Int, cols: Int) {
            entries[place]?.let { it.order = order; it.cols = cols }
            if (current == place) render(act)
        }
    }

    fun attach(status: TextView, place: String, onSort: () -> Unit, onView: () -> Unit, ready: () -> Boolean = { true }): Buttons {
        val act = status.context as android.app.Activity
        // il foglio sotto resta staccato dal bordo della sua lastra, come quando la riga stava lì
        (status.parent as? ViewGroup)?.let { old -> old.setPadding(old.paddingLeft, act.dp(8), old.paddingRight, old.paddingBottom); old.removeView(status) }
        val slot = act.findViewById<android.widget.FrameLayout>(R.id.statusSlot)
        status.setTextColor(ink)
        status.isVisible = false
        slot.addView(status, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        entries[place] = Entry(status, onSort, onView, ready)
        act.findViewById<View>(R.id.sortBtn).setOnClickListener { current?.let { entries[it] }?.onSort?.invoke() }
        act.findViewById<View>(R.id.viewBtn).setOnClickListener { current?.let { entries[it] }?.onView?.invoke() }
        return Buttons(act, place)
    }

    private var ink = 0xFF1C1B18.toInt()

    /** Il colore del testo e dei bottoni sopra la tinta del posto aperto. */
    fun tint(act: android.app.Activity, color: Int) {
        ink = color
        for (e in entries.values) e.status.setTextColor(color)
        act.findViewById<TextView>(R.id.sortText).setTextColor(color)
        val c = android.content.res.ColorStateList.valueOf(color)
        act.findViewById<ImageView>(R.id.sortIcon).imageTintList = c
        act.findViewById<ImageView>(R.id.viewBtn).imageTintList = c
    }

    /** Il posto aperto (o cambiato il suo stato: cloud collegato, permesso dato…): testo e bottoni suoi. */
    fun activate(act: android.app.Activity, place: String) {
        current = place
        for ((k, e) in entries) e.status.isVisible = k == place
        render(act)
    }

    private fun render(act: android.app.Activity) {
        val e = current?.let { entries[it] }
        val on = e != null && e.ready()
        val sort = act.findViewById<View>(R.id.sortBtn)
        val view = act.findViewById<android.widget.ImageButton>(R.id.viewBtn)
        sort.visibility = if (on) View.VISIBLE else View.INVISIBLE // invisibili, non spariti: la riga non si muove
        view.visibility = sort.visibility
        e?.status?.isVisible = e.ready()
        if (e == null) return
        act.findViewById<TextView>(R.id.sortText).text = Order.short(e.order)
        sort.contentDescription = "Order: " + Order.long(e.order) + ". Tap to change."
        view.setImageResource(MediaGrid.icon(e.cols))
        view.contentDescription = (if (e.cols == 0) "View: list" else "View: ${e.cols} per row") + ". Tap to change."
    }
}

/**
 * I colori a cascata dello schedario (scendendo, sotto-colori della stessa famiglia, tutto collegato): la linguetta del posto ha il suo colore; sotto, le sotto-linguette (album, tipi, cartelle) stanno su quel
 * colore — quella scelta ha un sotto-colore della stessa famiglia e si fonde col foglio sotto, le altre stanno dietro,
 * un po' più scure e più corte.
 */
object Shades {
    private val steps = floatArrayOf(0.26f, 0.40f, 0.18f, 0.34f, 0.48f)
    fun lighter(c: Int, f: Float) = androidx.core.graphics.ColorUtils.blendARGB(c, android.graphics.Color.WHITE, f)
    fun darker(c: Int, f: Float) = androidx.core.graphics.ColorUtils.blendARGB(c, android.graphics.Color.BLACK, f)
    /** Il sotto-colore della linguetta numero [i]: sempre della famiglia del posto, mai uguale al posto. */
    fun sub(ctx: android.content.Context, hue: Int, i: Int) = legible(ctx, lighter(hue, steps[((i % steps.size) + steps.size) % steps.size]))

    /**
     * Il nome sopra un sotto-colore si legge almeno a 4,5:1: di notte «ALL» degli album (ottanio schiarito del 26%) stava
     * a 4,2:1. Se non basta, il colore si sposta a piccoli passi dalla parte opposta all'inchiostro.
     */
    fun legible(ctx: android.content.Context, c: Int): Int {
        var bg = c or 0xFF000000.toInt()
        repeat(8) {
            val ink = Sections.onHue(ctx, bg)
            if (androidx.core.graphics.ColorUtils.calculateContrast(ink, bg) >= 4.5) return bg
            // blendARGB arrotonda anche l'alfa (0xFE): calculateContrast vuole un fondo pieno, se no si chiude l'app
            bg = (if (androidx.core.graphics.ColorUtils.calculateLuminance(ink) < 0.5) lighter(bg, 0.04f) else darker(bg, 0.04f)) or 0xFF000000.toInt()
        }
        return bg
    }
    /** Una linguetta non scelta: dietro, un po' più scura del colore su cui sta. */
    fun behind(hue: Int) = darker(hue, 0.20f)
    /** Il foglio a profondità [d] (Cloud: più si scende nelle cartelle, più è chiaro). */
    fun depth(hue: Int, d: Int) = lighter(hue, (0.20f + 0.11f * d).coerceAtMost(0.70f))

    /** Veste una linguetta (Chip a forma di linguetta): scelta = [sub] e attaccata sotto; no = dietro e più corta. */
    fun paintTab(c: com.google.android.material.chip.Chip, on: Boolean, rowHue: Int, sub: Int) {
        val bg = if (on) sub else behind(rowHue)
        c.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bg)
        val ink = Sections.onHue(c.context, bg)
        // dietro il testo si smorza un poco, ma mai sotto 4,5:1
        val soft = androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 0xCC)
        val softOk = androidx.core.graphics.ColorUtils.calculateContrast(androidx.core.graphics.ColorUtils.compositeColors(soft, bg or 0xFF000000.toInt()), bg or 0xFF000000.toInt()) >= 4.5
        c.setTextColor(if (on || !softOk) ink else soft)
        c.chipIconTint = android.content.res.ColorStateList.valueOf(ink)
        c.translationY = if (on) 0f else c.context.dp(6).toFloat()
    }
}
