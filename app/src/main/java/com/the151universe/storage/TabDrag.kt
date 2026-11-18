package com.the151universe.storage

import android.content.SharedPreferences
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray

/**
 * Le sotto-linguette (album di Media, tipi di Docs) si spostano trascinandole e l'ordine resta.
 * Tieni premuta una linguetta → si stacca e segue il dito, le altre le fanno posto; lasciata → [onOrder] con i tag
 * nell'ordine nuovo. Lasciata dov'era → il suo vecchio «tieni premuta» ([still]), così nascondere un album resta.
 * Le prime [fixed] linguette («All») non si spostano e non si scavalcano.
 */
class TabDrag(private val group: ChipGroup, private val fixed: Int, private val onOrder: (List<String>) -> Unit) {
    private val strip = group.parent as? HorizontalScrollView
    private var dragged: Chip? = null
    private var before: List<String> = emptyList()
    private var still: (() -> Unit)? = null

    init {
        (strip ?: group).setOnDragListener { _, e -> onDrag(e) }
    }

    /** Rende [chip] trascinabile; [still] = cosa fa tenerla premuta senza spostarla (null = niente). */
    fun draggable(chip: Chip, still: (() -> Unit)? = null) {
        chip.setOnLongClickListener {
            if (group.indexOfChild(chip) < fixed) { still?.invoke(); return@setOnLongClickListener still != null }
            dragged = chip
            before = tags()
            this.still = still
            chip.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (!chip.startDragAndDrop(null, View.DragShadowBuilder(chip), chip, 0)) { dragged = null; still?.invoke(); return@setOnLongClickListener true }
            chip.alpha = 0.3f
            true
        }
    }

    private fun tags() = (0 until group.childCount).map { group.getChildAt(it).tag.toString() }

    private fun onDrag(e: DragEvent): Boolean {
        val chip = dragged ?: return false
        if (e.localState !== chip) return false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                // coordinate dell'evento: della striscia (che scorre) → della fila
                val x = if (strip != null) e.x + strip.scrollX - group.left else e.x
                follow(chip, x)
                strip?.let { s ->
                    val edge = group.context.dp(40)
                    if (e.x < edge) s.scrollBy(-group.context.dp(14), 0) else if (e.x > s.width - edge) s.scrollBy(group.context.dp(14), 0)
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                chip.alpha = 1f
                dragged = null
                val after = tags()
                if (after != before) onOrder(after) else still?.invoke()
                still = null
            }
        }
        return true
    }

    /** La linguetta va dove sta il dito: dopo ogni linguetta visibile il cui centro è alla sua sinistra. */
    private fun follow(chip: Chip, x: Float) {
        if (group.isLayoutRequested) return // le posizioni di prima dello scambio mentirebbero
        val others = (0 until group.childCount).map { group.getChildAt(it) }.filter { it !== chip }
        var at = fixed.coerceAtMost(others.size)
        for (j in fixed until others.size) {
            val o = others[j]
            if (o.visibility == View.VISIBLE && x > o.left + o.width / 2f) at = j + 1
        }
        if (group.indexOfChild(chip) == at) return
        group.removeView(chip)
        group.addView(chip, at)
    }

    companion object {
        /** L'ordine salvato sotto [key]: tag delle linguette, dalla prima. */
        fun saved(prefs: SharedPreferences, key: String): List<String> = try {
            val a = JSONArray(prefs.getString(key, "[]")); (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) { emptyList() }

        /**
         * Salva [shown] (l'ordine a schermo) senza perdere le linguette che ora non si vedono (album nascosti o vuoti):
         * quelle restano dopo, nell'ordine di prima.
         */
        fun save(prefs: SharedPreferences, key: String, shown: List<String>) {
            val all = shown + saved(prefs, key).filter { it !in shown }
            prefs.edit().putString(key, JSONArray(all.take(300)).toString()).apply()
        }

        /** [items] nell'ordine salvato; quelle mai spostate (album nuovi) dopo, nell'ordine di sempre. */
        fun <T> arrange(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
            if (order.isEmpty()) return items
            val rank = order.withIndex().associate { it.value to it.index }
            return items.withIndex().sortedBy { (i, t) -> rank[key(t)] ?: (order.size + i) }.map { it.value }
        }
    }
}
