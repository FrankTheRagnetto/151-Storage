package com.the151universe.storage

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.util.Locale

/**
 * Le sezioni del manuale (DESIGN.md, «The Section Hue Rule»): ogni tipo di documento ha la sua tinta,
 * la stessa sulla linguetta della libreria, sul bollino della riga, sulla fascia del visualizzatore e
 * sul foglietto dei messaggi. Fonte unica lato nativo; il lato web la ripete in doc.css (--h-*).
 */
object Sections {
    fun hueRes(c: Cat): Int = when (c) {
        Cat.ALL -> R.color.hue_all
        Cat.PDF -> R.color.hue_pdf
        Cat.WORD -> R.color.hue_word
        Cat.EXCEL -> R.color.hue_excel
        Cat.PPT -> R.color.hue_ppt
        Cat.TEXT -> R.color.hue_text
        Cat.ARCHIVE -> R.color.hue_archive
        Cat.OTHER -> R.color.hue_other
        Cat.AUDIO -> R.color.hue_audio
    }

    fun hue(ctx: Context, c: Cat): Int = ContextCompat.getColor(ctx, hueRes(c))

    /** Sezione di un nome di file (estensione); un nome senza tipo noto va in «Altro». */
    fun catOfName(name: String): Cat = Library.catOf(Resolver.extOf(name)) ?: Cat.OTHER

    /** Inchiostro leggibile sulla tinta: scuro o chiaro, quello col contrasto più alto. */
    fun onHue(ctx: Context, hue: Int): Int {
        val dark = ContextCompat.getColor(ctx, R.color.on_hue_dark)
        val light = ContextCompat.getColor(ctx, R.color.on_hue_light)
        return if (ColorUtils.calculateContrast(dark, opaque(hue)) >= ColorUtils.calculateContrast(light, opaque(hue))) dark else light
    }

    /** Testo del bollino: l'estensione, al massimo 4 lettere. */
    fun badgeText(name: String): String {
        val ext = Resolver.extOf(name)
        return when {
            ext.isEmpty() -> "?"
            ext == "jpeg" -> "JPG"
            ext == "markdown" -> "MD"
            else -> ext.take(4).uppercase(Locale.ROOT)
        }
    }

    /** Veste un bollino (TextView) con la tinta della sua sezione. */
    fun paintBadge(v: TextView, cat: Cat, text: String, radiusPx: Float) {
        val hue = hue(v.context, cat)
        val bg = (v.background as? GradientDrawable) ?: GradientDrawable().also { v.background = it }
        bg.cornerRadius = radiusPx
        bg.setColor(hue)
        if (cat == Cat.ALL || cat == Cat.OTHER) bg.setStroke(0, Color.TRANSPARENT)
        v.text = text
        v.setTextColor(onHue(v.context, hue))
    }

    fun tint(color: Int) = ColorStateList.valueOf(color)

    private fun opaque(c: Int) = c or 0xFF000000.toInt()
}
