package com.the151universe.storage

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.abs

/**
 * Da una sotto-linguetta all'altra strisciando sul foglio:
 * verso sinistra = la linguetta dopo, verso destra = quella prima. Il foglio entra scivolando dal lato giusto
 * ([Motion.slideIn], nell'ascoltatore della fila: anche toccando la linguetta).
 * Guarda soltanto: lo scorrimento in su e giù e i tocchi restano della lista.
 */
object TabSwipe {
    fun attach(list: RecyclerView, group: ChipGroup) {
        val ctx = list.context
        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1 ?: return false).x
                val dy = e2.y - e1.y
                if (abs(dx) < ctx.dp(70) || abs(dx) < abs(dy) * 2 || abs(vx) < ctx.dp(300)) return false
                return go(list, group, if (dx < 0) 1 else -1)
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean { detector.onTouchEvent(e); return false }
        })
    }

    private fun go(list: RecyclerView, group: ChipGroup, step: Int): Boolean {
        val tabs = (0 until group.childCount).map { group.getChildAt(it) as Chip }.filter { it.visibility == View.VISIBLE }
        val at = tabs.indexOfFirst { it.isChecked }
        val next = tabs.getOrNull(at + step) ?: return false
        next.isChecked = true // l'ascoltatore della fila cambia il foglio
        (group.parent as? HorizontalScrollView)?.let { s ->
            val pad = list.context.dp(28)
            if (next.left - pad < s.scrollX) s.smoothScrollTo(maxOf(0, next.left - pad), 0)
            else if (next.right + pad > s.scrollX + s.width) s.smoothScrollTo(next.right + pad - s.width, 0)
        }
        // il foglio che entra scivolando lo fa l'ascoltatore della fila, uguale per tocco e striscio
        return true
    }
}
