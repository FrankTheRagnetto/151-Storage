package com.the151universe.storage

import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/** Come si veste il bollino di una riga del Cloud o di Media (spec §6). */
object RowPaint {
    fun paint(act: android.content.Context, badge: TextView, icon: ImageView, thumb: ImageView, isDir: Boolean, iconRes: Int, name: String) {
        thumb.isVisible = false
        thumb.setImageDrawable(null)
        thumb.tag = null
        if (isDir) {
            badge.text = ""
            badge.background = ContextCompat.getDrawable(act, R.drawable.folder_badge)
            icon.isVisible = true
            icon.setImageResource(if (iconRes != 0) iconRes else R.drawable.ic_folder)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(act, R.color.ink))
        } else {
            icon.isVisible = false
            badge.background = null
            Sections.paintBadge(badge, Sections.catOfName(name), Sections.badgeText(name), act.dp(4).toFloat())
        }
    }
}

/** Le righe del foglio (DESIGN.md, Leaf Lines Rule): un filo da 1dp dal 70dp in poi. */
class LeafLines(ctx: android.content.Context) : RecyclerView.ItemDecoration() {
    private val paint = android.graphics.Paint().apply { color = ContextCompat.getColor(ctx, R.color.seam) }
    private val left = ctx.dp(70).toFloat()
    private val h = maxOf(1f, ctx.resources.displayMetrics.density)
    override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val ad = parent.adapter ?: return
        for (i in 0 until parent.childCount - 1) {
            val v = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(v)
            val next = parent.getChildAdapterPosition(parent.getChildAt(i + 1))
            if (pos < 0 || next < 0) continue
            if (ad.getItemViewType(pos) == MediaGrid.CELL || ad.getItemViewType(next) == MediaGrid.CELL) continue // le celle non hanno righe
            val isHeader = ad.getItemViewType(pos) == 1
            if (!isHeader && ad.getItemViewType(next) == 1) continue // niente filo sopra un'intestazione
            val y = v.bottom + v.translationY
            c.drawRect(if (isHeader) 0f else left, y - h, parent.width.toFloat(), y, paint) // sotto l'intestazione, a tutta larghezza
        }
    }
}
