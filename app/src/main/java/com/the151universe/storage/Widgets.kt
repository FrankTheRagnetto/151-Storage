package com.the151universe.storage

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout

/** Cella quadrata della griglia: l'altezza è la larghezza. */
class SquareFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/**
 * Foto che si pizzica: pizzico e doppio tocco fino a 8×, trascinamento quando è ingrandita.
 * Finché è a 1× non trattiene il gesto, così il ViewPager2 sfoglia le foto; ingrandita, il gesto è suo
 * finché non arriva al bordo dell'immagine.
 */
class ZoomImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatImageView(ctx, attrs) {
    private val m = Matrix()
    private var base = 1f
    private var scale = 1f
    var onTap: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    init { scaleType = ScaleType.MATRIX }

    val zoomed get() = scale > 1.01f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = (scale * d.scaleFactor).coerceIn(1f, 8f) / scale
            scale *= f
            m.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) reset() else { scale = 2.5f; m.postScale(2.5f, 2.5f, e.x, e.y); clamp() }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!zoomed && e1 != null && e2.y - e1.y > 200 && vy > 1500 && Math.abs(e2.x - e1.x) < 150) { onSwipeDown?.invoke(); return true }
            return false
        }
    })

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { reset() }
    }

    private var rot = 0f

    /** Ruota la vista di 90° (il file resta com'è). */
    fun rotateBy(deg: Float) { rot = (rot + deg) % 360f; reset() }

    fun reset() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        val sideways = rot % 180f != 0f
        base = if (sideways) minOf(width / dh, height / dw) else minOf(width / dw, height / dh)
        scale = 1f
        m.reset()
        m.postTranslate(-dw / 2f, -dh / 2f)
        m.postRotate(rot)
        m.postScale(base, base)
        m.postTranslate(width / 2f, height / 2f)
        imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); reset() }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    /** Ingrandita e non al bordo: il gesto orizzontale resta qui (il pager non deve girare pagina). */
    override fun canScrollHorizontally(direction: Int): Boolean {
        if (!zoomed) return false
        val d = drawable ?: return false
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        return if (direction > 0) r.right > width + 1 else r.left < -1
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (zoomed || e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
}

/**
 * Dove sei, a linguette: la radice e ogni cartella aperta sono
 * linguette sul colore del posto; l'ultima è la scelta e si fonde col foglio, che schiarisce scendendo. Un tocco su una
 * linguetta di prima torna lì. Uguale in Cloud e in Phone: un solo modo di dire dove sei.
 * Ritorna il colore del foglio a quella profondità.
 */
object Crumbs {
    fun tabs(row: LinearLayout, strip: HorizontalScrollView, labels: List<String>, icon: Int, hue: Int, onTap: (Int) -> Unit): Int {
        val ctx = row.context
        strip.setBackgroundColor(hue)
        strip.setPadding(0, ctx.dp(6), 0, 0)
        row.removeAllViews()
        labels.forEachIndexed { i, label ->
            val last = i == labels.size - 1
            row.addView(com.google.android.material.chip.Chip(ctx).apply {
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = ctx.dp(46).toFloat() // come le linguette degli album: bersaglio pieno anche da dietro
                shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder(ctx, R.style.Shape_Viewer_Tab, 0).build()
                chipStrokeWidth = 0f
                elevation = 0f
                stateListAnimator = null
                setTextAppearance(R.style.TextAppearance_Viewer_Tab)
                text = label.uppercase(java.util.Locale.UK)
                contentDescription = label
                if (i == 0) { chipIcon = ContextCompat.getDrawable(ctx, icon); isChipIconVisible = true; chipIconSize = ctx.dp(16).toFloat() }
                Shades.paintTab(this, last, hue, Shades.depth(hue, i))
                setOnClickListener { if (!last) onTap(i) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(3) })
        }
        // scorre morbida fino all'ultima, misurata DOPO che le linguette nuove hanno preso posto, e senza spostare il fuoco
        // (fullScroll sposta il FUOCO sull'ultima e lo ruba al campo di ricerca)
        row.doOnNextLayout { strip.smoothScrollTo(maxOf(0, row.width - strip.width), 0) }
        return Shades.depth(hue, labels.size - 1)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * Il movimento dell'app, in un posto solo: una modalità sola,
 * sempre accesa ma leggera, 150–200 ms, morbida in uscita. Con «Rimuovi animazioni» di Android tutto è istantaneo
 * ([on]); ViewPropertyAnimator e ObjectAnimator seguono comunque la scala di sistema.
 */
object Motion {
    const val SHORT = 150L
    const val MID = 200L
    val ease = android.view.animation.DecelerateInterpolator(1.5f)
    fun on() = android.animation.ValueAnimator.areAnimatorsEnabled()

    /** Un foglio che entra: da destra ([dir] > 0), da sinistra (< 0) o solo dissolvenza (0). Breve, mai una pausa. */
    fun slideIn(v: View, dir: Int, distDp: Int = 36) {
        v.animate().cancel()
        if (!on()) { v.translationX = 0f; v.alpha = 1f; return }
        v.translationX = v.context.dp(distDp) * Integer.signum(dir).toFloat()
        v.alpha = 0.35f
        v.animate().translationX(0f).alpha(1f).setDuration(MID).setInterpolator(ease).start()
    }

    /** Dissolvenza d'entrata di una vista appena riempita (una foto, un'immagine). */
    fun fadeIn(v: View, ms: Long = MID) {
        if (!on()) { v.alpha = 1f; return }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(ms).setInterpolator(ease).start()
    }

    /**
     * Mostra o nasconde [v] dissolvendo (e salendo di [riseDp] se > 0), invece di farla comparire di colpo.
     * Chiamate ripetute con lo stesso stato non rifanno niente; un cambio a metà riparte da dov'è.
     */
    fun fade(v: View, show: Boolean, riseDp: Int = 0, ms: Long = SHORT) {
        val hiding = v.getTag(R.id.motion_hiding) == true
        if (show && v.visibility == View.VISIBLE && !hiding) return
        if (!show && (v.visibility != View.VISIBLE || hiding)) return
        v.animate().cancel()
        v.setTag(R.id.motion_hiding, null)
        if (!on()) { v.alpha = 1f; v.translationY = 0f; v.visibility = if (show) View.VISIBLE else View.GONE; return }
        val rise = v.context.dp(riseDp).toFloat()
        if (show) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.translationY = rise }
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).translationY(0f).setDuration(ms).setInterpolator(ease).start()
        } else {
            v.setTag(R.id.motion_hiding, true)
            v.animate().alpha(0f).translationY(rise).setDuration(ms).setInterpolator(ease).withEndAction {
                v.setTag(R.id.motion_hiding, null); v.visibility = View.GONE; v.alpha = 1f; v.translationY = 0f
            }.start()
        }
    }

    /** Come [fade] ma subito, senza animazione: ferma una dissolvenza in corso perché non finisca al contrario. */
    fun setShown(v: View, show: Boolean) {
        if (v.getTag(R.id.motion_hiding) == true || v.alpha != 1f) { v.animate().cancel(); v.setTag(R.id.motion_hiding, null); v.alpha = 1f; v.translationY = 0f }
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** La scala di una miniatura (scelta = rientra): animata solo se [animate]; non tocca l'alpha (la dissolvenza delle miniature). */
    fun scale(v: View, s: Float, animate: Boolean) {
        (v.getTag(R.id.motion_scale) as? android.animation.Animator)?.cancel()
        v.setTag(R.id.motion_scale, null)
        if (!animate || !on() || (v.scaleX == s && v.scaleY == s)) { v.scaleX = s; v.scaleY = s; return }
        val a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, s), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, s))
        a.duration = SHORT; a.interpolator = ease
        v.setTag(R.id.motion_scale, a)
        a.start()
    }
}

/** Tirare in giù per aggiornare: la rotellina d'inchiostro su un dischetto d'acetato, piatta come il resto. */
object Pull {
    fun style(p: androidx.swiperefreshlayout.widget.SwipeRefreshLayout) {
        p.setColorSchemeColors(ContextCompat.getColor(p.context, R.color.ink))
        p.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(p.context, R.color.acetate_raised))
    }
}

/** «1 cartella», «12 cartelle» (cifre all'italiana). */
fun plural(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else String.format(java.util.Locale.UK, "%,d %s", n, many)

/** Date dell'app sempre in italiano, anche su un telefono in un'altra lingua: «12 ott 2026», «12 ott 2026, 14:32». */
object Dates {
    private val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
    private val dayTime = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK)
    fun short(t: Long): String = synchronized(day) { day.format(java.util.Date(t)) }
    fun withTime(t: Long): String = synchronized(dayTime) { dayTime.format(java.util.Date(t)) }

    /** «just now», «5 min ago», «3 hours ago», «yesterday», poi la data: sempre in inglese. */
    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        val m = (now - t) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> (m / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            m < 48 * 60 -> "yesterday"
            else -> short(t)
        }
    }
}

/** Le dimensioni sempre allo stesso modo e in italiano: «24 kB», «2,1 MB», «2,1 GB» (un decimale al massimo). */
object Sizes {
    fun short(b: Long): String {
        if (b < 0) return ""
        if (b < 1000) return "$b B"
        val units = arrayOf("kB", "MB", "GB", "TB")
        var v = b / 1000.0; var i = 0
        while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
        val s = if (v >= 100 || i == 0) String.format(java.util.Locale.UK, "%.0f", v) else String.format(java.util.Locale.UK, "%.1f", v).removeSuffix(".0")
        return "$s ${units[i]}"
    }
}
