package com.the151universe.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/**
 * «L'app si è chiusa»: mostra lo stack trace salvato da [CrashLog] con Copia / Condividi.
 * Il rapporto si cancella con «Chiudi» (o dopo averlo condiviso/copiato e chiuso).
 * Nessuna rete: la condivisione passa dal foglio di sistema, e' l'utente a scegliere dove mandarlo.
 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val trace = CrashLog.pending(this)
        if (trace == null) { finish(); return }
        CrashLog.seen(this) // mostrato una volta: non torna a ogni apertura, comunque si esca
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.board)); setPadding(dp(20), dp(20), dp(20), dp(12)) }
        val title = TextView(this).apply {
            text = getString(R.string.crash_title)
            setTextAppearance(R.style.TextAppearance_Viewer_Headline)
            setTextColor(getColor(R.color.vermilion))
        }
        val body = TextView(this).apply {
            text = getString(R.string.crash_body) + (CrashLog.headline(trace)?.let { (v, at) -> "\n\n" + getString(R.string.crash_when, v, at) } ?: "")
            setTextAppearance(R.style.TextAppearance_Viewer_Prose)
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(8), 0, dp(12))
        }
        val traceView = TextView(this).apply {
            text = trace
            setTextAppearance(R.style.TextAppearance_Viewer_Meta)
            setTextColor(getColor(R.color.ink))
            textSize = 11f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.slip)
            addView(HorizontalScrollView(this@CrashActivity).apply { addView(traceView) })
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        fun button(label: Int, outlined: Boolean, onClick: () -> Unit) = MaterialButton(
            this, null,
            if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            text = getString(label)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        buttons.addView(button(R.string.crash_copy, outlined = true) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("151 Storage crash", trace))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        })
        buttons.addView(button(R.string.share, outlined = true) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "151 Storage: crash")
                .putExtra(Intent.EXTRA_TEXT, trace)
            try { startActivity(Intent.createChooser(send, getString(R.string.share))) } catch (_: Exception) {
                Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(button(R.string.crash_close, outlined = false) { CrashLog.clear(this); finish() })

        root.addView(title)
        root.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = b.left + dp(20), top = b.top + dp(20), right = b.right + dp(20), bottom = b.bottom + dp(12))
            insets
        }
    }
}
