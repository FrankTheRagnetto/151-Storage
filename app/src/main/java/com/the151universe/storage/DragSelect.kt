package com.the151universe.storage

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * Selezione trascinando il dito: si tiene premuta una voce, poi senza alzare il dito
 * si scorre sulle altre e si accendono tutte quelle fra la prima e il dito; tornando indietro si spengono
 * di nuovo (tornano com'erano). Vicino al bordo alto o basso la lista scorre da sola.
 * Il posto dice solo chi si può scegliere, com'è adesso, e cosa mettere; il ridisegno lo fa [refresh].
 */
class DragSelect(
    private val list: RecyclerView,
    private val selectable: (Int) -> Boolean,
    private val isOn: (Int) -> Boolean,
    private val set: (Int, Boolean) -> Unit,
    private val refresh: () -> Unit,
) : RecyclerView.OnItemTouchListener {
    private var anchor = -1
    private var last = -1
    private var target = true
    /** com'erano le voci toccate prima del trascinamento, per rimetterle se il dito torna indietro */
    private val before = HashMap<Int, Boolean>()
    private var lastX = 0f
    private var lastY = 0f
    private var speed = 0
    private val edge = list.context.dp(64)
    private val scroller = object : Runnable {
        override fun run() {
            if (anchor < 0 || speed == 0) return
            list.scrollBy(0, speed)
            update(lastX, lastY)
            list.postOnAnimation(this)
        }
    }

    init { list.addOnItemTouchListener(this) }

    /** Da chiamare nel tocco lungo, DOPO aver acceso/spento la voce: il dito che resta giù continua da lì. */
    fun start(pos: Int) {
        if (pos == RecyclerView.NO_POSITION) return
        anchor = pos; last = pos; target = isOn(pos)
        before.clear()
        list.parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (anchor < 0) return false
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { stop(); return false }
        return e.actionMasked == MotionEvent.ACTION_MOVE
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x; lastY = e.y
                update(e.x, e.y)
                val s = when {
                    e.y < edge -> -scrollStep(edge - e.y)
                    e.y > rv.height - edge -> scrollStep(e.y - (rv.height - edge))
                    else -> 0
                }
                val was = speed
                speed = s
                if (s != 0 && was == 0) rv.postOnAnimation(scroller)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun scrollStep(depth: Float) = (4 + 28 * (depth.coerceIn(0f, edge.toFloat()) / edge)).toInt()

    private fun update(x: Float, y: Float) {
        val v = list.findChildViewUnder(x.coerceIn(1f, list.width - 1f), y.coerceIn(1f, list.height - 1f)) ?: return
        val pos = list.getChildAdapterPosition(v)
        if (pos == RecyclerView.NO_POSITION || pos == last) return
        val lo = minOf(anchor, pos); val hi = maxOf(anchor, pos)
        // chi esce dal tratto torna com'era
        for (p in before.keys.toList()) if (p < lo || p > hi) { set(p, before.getValue(p)); before.remove(p) }
        for (p in lo..hi) if (p != anchor && selectable(p)) {
            if (p !in before) before[p] = isOn(p)
            if (isOn(p) != target) set(p, target)
        }
        last = pos
        refresh()
    }

    private fun stop() {
        anchor = -1; last = -1; speed = 0; before.clear()
        list.removeCallbacks(scroller)
    }

}
