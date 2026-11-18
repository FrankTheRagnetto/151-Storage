package com.the151universe.storage

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "151Viewer"
        private const val ORIGIN = "https://" + WebViewAssetLoader.DEFAULT_DOMAIN
        /** Oltre questa soglia il testo si tronca: una WebView con 50 MB di testo non e' "istantanea". */
        private const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        /** Formati di doc.html che sono testo: per loro ha senso «Testo sorgente». */
        private val SOURCE_FMTS = setOf("md", "eml", "mhtml", "vcf", "ics", "srt", "vtt", "csv", "tsv", "rtf",
            "ipynb", "fb2", "drawio", "excalidraw", "geo", "link", "cert", "fattura")
        /** Aperto dalla nostra schermata principale: dopo «Elimina» la Snackbar con «Annulla» la mostra lei. */
        const val EXTRA_FROM_MAIN = "from_main"
        /** Percorso vero, se lo sa gia' chi ci apre (libreria). */
        const val EXTRA_PATH = "path"
    }

    private val deleter = Deleter(this, onDeleted = ::afterDelete)

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var info: DocInfo? = null
    @Volatile private var server: DocServer? = null
    private var web: WebView? = null
    private var pdfPages = 0
    private var pdfPage = 1
    private var chromeHidden = false
    /** Esiste almeno un'altra app (non noi) che apre questo file? Decide se mostrare «Apri con». */
    @Volatile private var otherApps = false
    /** Vista "testo sorgente" al posto di quella formattata (Markdown, HTML, email…). */
    private var sourceMode = false
    /** Script della pagina HTML: spenti di default, accesi solo per questa apertura. */
    private var scriptsOn = false
    private var video: VideoView? = null
    private val showProgress = Runnable { progress.isVisible = true }
    private val runFind = Runnable { find(again = false, prev = false) }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---------------------------------------------------------------- ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) {
            // niente splash: via subito la finestra di avvio di sistema
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        setContentView(R.layout.activity_viewer)
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        content = findViewById(R.id.content)
        progress = findViewById(R.id.progress)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchCount = findViewById(R.id.searchCount)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(left = bars.left, top = if (chromeHidden) 0 else bars.top, right = bars.right)
            searchBar.updatePadding(left = bars.left + dp(16), right = bars.right + dp(4))
            root.updatePadding(bottom = max(bars.bottom, ime.bottom))
            insets
        }

        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.viewer)
        toolbar.setOnMenuItemClickListener(::onMenu)
        toolbar.title = ""

        setupSearch()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searchBar.isVisible -> closeSearch()
                    chromeHidden -> toggleChrome()
                    info?.kind == Kind.DOC && !sourceMode && web != null ->
                        // dentro uno ZIP o un allegato: «indietro» torna all'elenco, poi esce
                        web?.evaluateJavascript("(window.V && V.back && V.back()) || false") { r -> if (r != "true") finish() }
                    else -> finish()
                }
            }
        })

        load(intent)
        CrashLog.showIfPending(this) // se l'ultima volta l'app e' morta, il rapporto compare sopra
    }

    override fun onPause() {
        super.onPause()
        val i = info ?: return
        if (i.kind == Kind.PDF && i.uri != null && pdfPages > 0) Recents.setPage(this, i.uri, pdfPage)
    }

    /** Il pannello dell'audio resta sempre aperto: alla chiusura va tolto, o la finestra resta appesa (WindowLeaked). */
    private var closing = false
    private var audioController: MediaController? = null

    override fun onDestroy() {
        closing = true
        try { audioController?.hide() } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        video?.stopPlayback()
        server?.close()
        apkTemp?.delete()
        io.shutdownNow()
        deleter.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- apertura

    private fun load(intent: Intent) {
        var uri: Uri? = null
        var text: String? = null
        if (intent.action == Intent.ACTION_SEND) {
            uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri == null) text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } else {
            uri = intent.data
        }

        if (uri == null) {
            if (text == null) {
                showMessage(getString(R.string.error_no_file), getString(R.string.error_no_file_body), null, openWith = false)
                return
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val i = DocInfo(null, getString(R.string.shared_text), "text/plain", bytes.size.toLong(), Kind.TEXT, text)
            safeShow(i, DocServer(this, null, "text/plain", bytes))
            return
        }

        toolbar.title = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        main.postDelayed(showProgress, 250) // lo spinner compare solo se serve davvero
        val type = intent.type
        val u: Uri = uri
        io.execute {
            try {
                Recents.tryPersist(this, u)
                val i = Resolver.resolve(this, u, type)
                val s = DocServer(this, u, serveMime(i)).apply { open() }
                main.post { if (!isDestroyed) safeShow(i, s) else s.close() }
            } catch (e: SecurityException) {
                Log.w(TAG, "permesso negato su $u", e)
                main.post {
                    Recents.remove(this, u.toString())
                    showMessage(getString(R.string.error_permission), null, null, openWith = false)
                }
            } catch (e: Throwable) { // anche OutOfMemoryError & co.: un file non deve mai chiudere l'app
                Log.w(TAG, "lettura fallita su $u", e)
                main.post { showMessage(getString(R.string.error_read), getString(R.string.error_read_body), e.javaClass.simpleName, openWith = false) }
            }
        }
    }

    /** show() con rete di sicurezza: se mostrare il file fallisce, messaggio invece di crash. */
    private fun safeShow(i: DocInfo, s: DocServer) {
        try {
            show(i, s)
        } catch (e: Throwable) {
            Log.w(TAG, "visualizzazione fallita", e)
            try {
                showMessage(getString(R.string.error_open), getString(R.string.error_open_body), e.javaClass.simpleName, openWith = true)
            } catch (e2: Throwable) {
                Log.w(TAG, "anche il messaggio e' fallito", e2)
                Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun serveMime(i: DocInfo): String = when (i.kind) {
        Kind.PDF -> "application/pdf"
        Kind.SVG -> "image/svg+xml"
        Kind.TEXT -> "text/plain" // i byte li decodifica la pagina (UTF-8/UTF-16/Latin-1)
        Kind.DOC -> "application/octet-stream" // li interpreta doc.html
        Kind.HTML -> "text/html"
        else -> i.mime
    }

    // ---------------------------------------------------------------- APK: scheda del pacchetto (PackageManager)

    private var apkTemp: java.io.File? = null

    private fun showApk() {
        val i = info ?: return
        val uri = i.uri ?: return
        clearContent()
        main.postDelayed(showProgress, 250)
        if (io.isShutdown) return
        val known = intent?.getStringExtra(EXTRA_PATH)
        io.execute {
            val r = try { ApkInspector.inspect(this, uri, known) } catch (e: Throwable) { Log.w(TAG, "APK non leggibile", e); null }
            main.post {
                if (isDestroyed) { r?.temp?.delete(); return@post }
                main.removeCallbacks(showProgress); progress.isVisible = false
                apkTemp = r?.temp
                if (r == null || r.pkg == null) {
                    showMessage(getString(R.string.apk_unreadable), getString(R.string.apk_unreadable_body), null, openWith = true)
                } else try {
                    content.addView(ApkInspector.view(this, r), 0)
                } catch (e: Throwable) {
                    Log.w(TAG, "scheda APK fallita", e)
                    showMessage(getString(R.string.apk_unreadable), null, e.javaClass.simpleName, openWith = true)
                }
            }
        }
    }

    private fun show(i: DocInfo, s: DocServer) {
        if (i.kind == Kind.NOT_DOC && i.uri != null) {
            // 151 Storage: foto e video vanno al visore della galleria (nel Viewer erano «non è un documento»)
            s.close()
            try {
                startActivity(Intent(this, MediaActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(i.uri, i.mime)
                    .putExtra(MediaActivity.EXTRA_FROM, MediaActivity.FROM_FOLDERS).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                finish()
                return
            } catch (e: Exception) {
                android.util.Log.w("151Storage", "visore foto non aperto", e)
            }
        }
        main.removeCallbacks(showProgress)
        progress.isVisible = false
        info = i
        server?.close()
        server = s
        sourceMode = false
        scriptsOn = false
        otherApps = false
        checkOtherApps(i)
        toolbar.title = i.name
        toolbar.subtitle = subtitleFor(i)
        paintBand(i)
        if (i.uri != null) Recents.add(this, i)
        when (i.kind) {
            Kind.PDF -> showPdf()
            Kind.TEXT -> showText()
            Kind.SVG -> showSvg()
            Kind.DOC -> showDoc()
            Kind.HTML -> showHtml()
            Kind.MEDIA -> showMedia()
            Kind.APK -> showApk()
            Kind.NOT_DOC -> {
                // v5: foto e video non sono affar nostro (c'e' la Galleria). Niente «Mostra come testo».
                val video = i.mime.startsWith("video/")
                showMessage(getString(if (video) R.string.notdoc_video_title else R.string.notdoc_photo_title),
                    getString(R.string.notdoc_body), null, openWith = true, errata = false)
            }
            Kind.UNSUPPORTED -> showMessage(getString(R.string.unsupported_title),
                getString(if (i.fmt == "djvu") R.string.unsupported_djvu else R.string.unsupported_body), null, openWith = true, asText = i.fmt != "djvu", errata = false)
        }
        updateMenu()
    }

    /** La fascia sotto la barra prende la tinta della sezione del documento (come la sua linguetta). */
    private fun paintBand(i: DocInfo) {
        val c = when (i.kind) {
            Kind.PDF -> Cat.PDF
            Kind.TEXT -> Library.catOf(Resolver.extOf(i.name)) ?: Cat.TEXT
            Kind.SVG, Kind.NOT_DOC, Kind.UNSUPPORTED, Kind.APK -> Library.catOf(Resolver.extOf(i.name)) ?: Cat.OTHER
            else -> Sections.catOfName(i.name)
        }
        findViewById<View>(R.id.sectionBand).setBackgroundColor(Sections.hue(this, c))
    }

    private fun kindLabel(i: DocInfo): String = when (i.kind) {
        Kind.PDF -> "PDF"
        Kind.SVG -> "SVG"
        Kind.NOT_DOC -> i.mime.substringAfter('/').removePrefix("x-").substringBefore('+').uppercase(Locale.ROOT)
        Kind.APK -> "Android app (APK)"
        Kind.TEXT -> Resolver.extOf(i.name).takeIf { it.isNotEmpty() && it.length <= 8 }?.uppercase(Locale.ROOT) ?: "Text"
        Kind.DOC -> docLabel ?: Resolver.extOf(i.name).takeIf { it.isNotEmpty() && it.length <= 8 }?.uppercase(Locale.ROOT) ?: "Document"
        Kind.HTML -> "HTML" + if (scriptsOn) " · scripts on" else ""
        Kind.MEDIA -> "Audio " + i.mime.substringAfter('/').removePrefix("x-").uppercase(Locale.ROOT).let { if (it == "MPEG") "MP3" else it }
        Kind.UNSUPPORTED -> i.mime
    }

    /** Etichetta del formato decisa da doc.html (es. "Word", "Calendario · 3 eventi"). */
    private var docLabel: String? = null

    private fun subtitleFor(i: DocInfo): String =
        if (i.size >= 0) kindLabel(i) + " · " + Sizes.short(i.size) else kindLabel(i)

    private fun clearContent() {
        web?.let { content.removeView(it); it.destroy() }
        web = null
        video?.let { it.stopPlayback() }
        video = null
        for (idx in content.childCount - 1 downTo 0) {
            val v = content.getChildAt(idx)
            if (v !== progress) content.removeViewAt(idx)
        }
        if (searchBar.isVisible) closeSearch()
    }

    // ---------------------------------------------------------------- WebView (PDF, testo, SVG)

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(zoomable: Boolean, pageScripts: Boolean = true, bridge: Boolean = true): WebView {
        clearContent()
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = pageScripts
            defaultTextEncodingName = "utf-8"
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100 // la dimensione di sistema dei caratteri sfaserebbe il text layer di pdf.js
            setSupportZoom(zoomable)
            builtInZoomControls = zoomable
            displayZoomControls = false
            useWideViewPort = zoomable
            loadWithOverviewMode = zoomable
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(w.settings, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, false) // il tema scuro lo gestiscono le pagine
        }
        w.setBackgroundColor(ContextCompat.getColor(this, R.color.board))
        w.webViewClient = client
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "js ${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        // il ponte verso l'app solo sulle NOSTRE pagine: una pagina HTML aperta non lo vede mai
        if (bridge) w.addJavascriptInterface(Bridge(), "Android")
        content.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        return w
    }

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val u = request.url
            // Solo la nostra origine locale. Qualunque altra richiesta muore qui (e comunque non c'e' INTERNET).
            if (u.scheme == "https" && u.host == DocServer.HTML_HOST) {
                val s = server
                val i = info
                return if (s != null && i != null && s.handlesHtml(u)) s.serveHtml(u, htmlName(i), scriptsOn) else DocServer.error(403, "Forbidden")
            }
            if (u.scheme != "https" || u.host != WebViewAssetLoader.DEFAULT_DOMAIN) return DocServer.error(403, "Forbidden")
            server?.let { if (it.handles(u.path)) return it.serve(u) }
            return assetLoader.shouldInterceptRequest(u) ?: DocServer.error(404, "Not Found")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "https" && u.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
            if (u.scheme == "https" && u.host == DocServer.HTML_HOST) return false // pagina accanto, stesso albero
            if (u.scheme == "about" || u.scheme == "javascript" || u.scheme == "data" || u.scheme == "blob") return true
            confirmExternalLink(u) // link dentro un PDF / una pagina
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Il motore web e' morto (tipicamente memoria finita su un PDF enorme): non far crashare l'app.
            Log.w(TAG, "renderer morto, crash=${detail.didCrash()}")
            if (web === view) web = null
            content.removeView(view)
            view.destroy()
            showMessage("This document is too heavy for the viewer.", "Try opening it with another app.", null, openWith = true)
            return true
        }
    }

    private fun pageUrl(page: String, params: Map<String, String>): String {
        val b = Uri.Builder().path("/assets/viewer/$page")
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return ORIGIN + b.build().toString()
    }

    private fun showPdf() {
        val i = info ?: return
        val s = server ?: return
        pdfPages = 0
        pdfPage = i.uri?.let { Recents.find(this, it)?.page } ?: 1
        val size = if (s.fdSize > 0) s.fdSize else i.size
        val w = newWebView(zoomable = false)
        main.postDelayed(showProgress, 250)
        w.loadUrl(
            pageUrl(
                "pdf.html", mapOf(
                    "src" to s.basePath,
                    "size" to size.toString(),
                    "seek" to if (s.seekable && size > 0) "1" else "0",
                    "page" to pdfPage.toString(),
                    "dark" to if (prefs.getBoolean("pdfDark", false)) "1" else "0",
                    "theme" to if (isNight) "dark" else "light",
                )
            )
        )
    }

    private fun showText(lang: String? = null) {
        val i = info ?: return
        val s = server ?: return
        val w = newWebView(zoomable = false)
        w.setFindListener { active, total, done ->
            if (done || total > 0) searchCount.text = findLabel(if (total > 0) active + 1 else 0, total)
        }
        w.loadUrl(
            pageUrl(
                "text.html", mapOf(
                    "src" to s.basePath,
                    "size" to i.size.toString(),
                    "max" to MAX_TEXT_BYTES.toString(),
                    "name" to i.name,
                    "json" to if (i.mime.contains("json") || Resolver.extOf(i.name) == "json") "1" else "0",
                    "fs" to prefs.getFloat("textSize", 14f).toString(),
                    "lang" to (lang ?: i.fmt),
                    "theme" to if (isNight) "dark" else "light",
                )
            )
        )
    }

    // ---------------------------------------------------------------- documenti (doc.html), HTML, audio/video

    private fun showDoc() {
        val i = info ?: return
        val s = server ?: return
        docLabel = null
        val w = newWebView(zoomable = true)
        w.setFindListener { active, total, done ->
            if (done || total > 0) searchCount.text = findLabel(if (total > 0) active + 1 else 0, total)
        }
        main.postDelayed(showProgress, 250)
        w.loadUrl(
            pageUrl(
                "doc.html", mapOf(
                    "src" to s.basePath,
                    "size" to (if (s.fdSize > 0) s.fdSize else i.size).toString(),
                    "name" to i.name,
                    "fmt" to i.fmt.ifBlank { "auto" },
                    "fs" to prefs.getFloat("docTextSize", 16f).toString(),
                    "theme" to if (isNight) "dark" else "light",
                )
            )
        )
    }

    private fun htmlName(i: DocInfo): String = i.name.replace('/', '_').ifBlank { "pagina.html" }

    private fun showHtml() {
        val i = info ?: return
        val s = server ?: return
        val w = newWebView(zoomable = true, pageScripts = scriptsOn, bridge = false)
        w.setBackgroundColor(0xFFFFFFFF.toInt())
        w.setFindListener { active, total, done ->
            if (done || total > 0) searchCount.text = findLabel(if (total > 0) active + 1 else 0, total)
        }
        w.loadUrl(s.htmlUrl(htmlName(i)))
    }

    /** Vista sorgente: la pagina del testo, con i colori della sintassi quando ha senso. */
    private fun showSource() {
        val i = info ?: return
        val ext = Resolver.extOf(i.name)
        val lang = when {
            i.kind == Kind.HTML -> "xml"
            i.fmt == "md" -> "markdown"
            i.fmt == "ipynb" || i.fmt == "excalidraw" || ext == "geojson" || ext == "json" -> "json"
            i.fmt == "drawio" || i.fmt == "fb2" || i.fmt == "fattura" || ext == "gpx" || ext == "kml" || ext == "webloc" -> "xml"
            i.fmt == "link" -> "ini"
            else -> ""
        }
        showText(lang)
    }

    private fun showMedia() {
        val i = info ?: return
        val uri = i.uri ?: return
        clearContent()
        val box = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val v = VideoView(this)
        box.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        val isAudio = true // v5: i video non si aprono piu' qui (Kind.NOT_DOC)
        run {
            box.addView(TextView(this).apply {
                text = i.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }
        content.addView(box, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val mc = object : MediaController(this) {
            // il pannello resta visibile (audio: non c'e' immagine da guardare); «indietro» chiude la schermata
            override fun hide() { if (closing || (!isAudio && video?.isPlaying == true)) super.hide() }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { finish(); return true }
                return super.dispatchKeyEvent(event)
            }
        }
        mc.setAnchorView(box)
        audioController = mc
        v.setMediaController(mc)
        v.setOnPreparedListener { mp ->
            main.removeCallbacks(showProgress); progress.isVisible = false
            mp.isLooping = false
            v.start()
            mc.show(0)
        }
        v.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "media errore $what/$extra")
            main.post { showMessage("Can't play this file.", "This phone doesn't support its format or codec.", "$what/$extra", openWith = true) }
            true
        }
        v.setOnCompletionListener { mc.show(0) }
        box.setOnClickListener { mc.show(0) }
        video = v
        main.postDelayed(showProgress, 250)
        v.setVideoURI(uri)
    }

    /** queryIntentActivities escludendo noi: se nessun'altra app apre il file, «Apri con» non compare. */
    private fun checkOtherApps(i: DocInfo) {
        val uri = i.uri ?: return
        if (io.isShutdown) return
        io.execute {
            val found = try {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, i.mime.takeIf { it.isNotBlank() } ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                } else {
                    @Suppress("DEPRECATION") packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                }
                list.any { it.activityInfo?.packageName != packageName }
            } catch (e: Exception) {
                Log.w(TAG, "queryIntentActivities fallita", e)
                false
            }
            main.post {
                if (isDestroyed || info?.uri != uri) return@post
                otherApps = found
                updateMenu()
                content.findViewById<Button>(R.id.msgPrimary)?.let { b -> b.isVisible = found && b.tag == "openWith" }
            }
        }
    }

    private fun showSvg() {
        val s = server ?: return
        val w = newWebView(zoomable = true)
        w.loadUrl(pageUrl("svg.html", mapOf("src" to s.basePath, "theme" to if (isNight) "dark" else "light")))
    }

    private fun js(code: String) {
        web?.evaluateJavascript("window.V && ($code)", null)
    }

    /** Chiamate dalle pagine web (thread del bridge -> UI thread). */
    inner class Bridge {
        @JavascriptInterface fun onLoaded(pages: Int) = runOnUiThread {
            main.removeCallbacks(showProgress); progress.isVisible = false
            pdfPages = pages; updateMenu()
        }
        @JavascriptInterface fun onPage(page: Int, pages: Int) = runOnUiThread { pdfPage = page; pdfPages = pages }
        @JavascriptInterface fun onFind(current: Int, total: Int, state: Int) = runOnUiThread {
            searchCount.text = if (state == 3) "…" else findLabel(current, total)
        }
        @JavascriptInterface fun onError(msg: String) = runOnUiThread {
            main.removeCallbacks(showProgress); progress.isVisible = false
            Log.w(TAG, "errore pagina: $msg")
        }
        @JavascriptInterface fun askPassword(wrong: Boolean) = runOnUiThread { passwordDialog(wrong) }
        @JavascriptInterface fun gotoPage() = runOnUiThread { gotoDialog() }
        @JavascriptInterface fun setTextSize(px: Float) { prefs.edit().putFloat("textSize", px).apply() }
        @JavascriptInterface fun toggleChrome() = runOnUiThread { this@ViewerActivity.toggleChrome() }
        @JavascriptInterface fun setDocTextSize(px: Float) { prefs.edit().putFloat("docTextSize", px).apply() }
        @JavascriptInterface fun onFormat(label: String) = runOnUiThread {
            docLabel = label.take(80)
            info?.let { toolbar.subtitle = subtitleFor(it) }
        }
        /** doc.html non ce l'ha fatta: schermata nativa con «Apri con» e «Mostra come testo». */
        @JavascriptInterface fun onFailed(msg: String) = runOnUiThread {
            main.removeCallbacks(showProgress); progress.isVisible = false
            Log.w(TAG, "documento non mostrabile: $msg")
            if (info?.kind == Kind.DOC) showMessage("Can't show this file", msg.take(300), null, openWith = true, asText = true)
        }
        @JavascriptInterface fun openLink(url: String) = runOnUiThread {
            val u = Uri.parse(url)
            if (u.scheme in setOf("http", "https", "mailto", "tel", "geo")) confirmExternalLink(u)
        }
        @JavascriptInterface fun toast(msg: String) = runOnUiThread { Toast.makeText(this@ViewerActivity, msg.take(200), Toast.LENGTH_LONG).show() }
    }

    private fun toggleChrome() {
        val k = info?.kind
        if (!chromeHidden && k != Kind.SVG) return
        chromeHidden = !chromeHidden
        toolbar.isVisible = !chromeHidden
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (chromeHidden) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(root)
    }

    // ---------------------------------------------------------------- messaggi (formato non supportato, errori)

    /**
     * Il foglietto. [errata] = qualcosa è andato storto (lettura, permesso, file rotto): fondo e filo
     * vermiglio. Senza errata è solo un formato che qui non si apre, o una foto/un video: foglietto normale.
     */
    private fun showMessage(title: String, body: String?, extra: String?, openWith: Boolean, asText: Boolean = false, errata: Boolean = true) {
        main.removeCallbacks(showProgress)
        progress.isVisible = false
        clearContent()
        val v = layoutInflater.inflate(R.layout.view_message, content, false)
        val i = info
        v.findViewById<TextView>(R.id.msgTitle).text = title
        v.findViewById<TextView>(R.id.msgName).apply {
            text = i?.name ?: ""
            isVisible = i != null
        }
        v.findViewById<TextView>(R.id.msgMeta).apply {
            val parts = mutableListOf<String>()
            if (i != null) parts += i.mime
            if (i != null && i.size >= 0) parts += Sizes.short(i.size)
            if (extra != null && extra != i?.mime) parts += extra
            text = parts.joinToString(" · ")
            isVisible = parts.isNotEmpty()
        }
        v.findViewById<TextView>(R.id.msgBody).apply { text = body ?: ""; isVisible = body != null }
        v.findViewById<View>(R.id.msgSlip).setBackgroundResource(if (errata) R.drawable.slip_errata else R.drawable.slip)
        v.findViewById<TextView>(R.id.msgBadge).let { b ->
            // senza DocInfo (file illeggibile) il nome viene dall'indirizzo: «non-esiste.pdf» → PDF
            val name = i?.name ?: (intent?.data?.lastPathSegment ?: "")
            val label = Sections.badgeText(name).let { if (it == "?") "FILE" else it }
            Sections.paintBadge(b, Sections.catOfName(name), label, dp(4).toFloat())
            if (errata) {
                (b.background as android.graphics.drawable.GradientDrawable).setColor(ContextCompat.getColor(this, R.color.vermilion))
                b.setTextColor(ContextCompat.getColor(this, R.color.acetate))
            }
        }
        v.findViewById<Button>(R.id.msgPrimary).apply {
            tag = if (openWith && i?.uri != null) "openWith" else null
            isVisible = openWith && i?.uri != null && otherApps
            setOnClickListener { openWithOther() }
        }
        v.findViewById<Button>(R.id.msgSecondary).apply {
            isVisible = asText && i?.uri != null
            setOnClickListener {
                val cur = info ?: return@setOnClickListener
                try {
                    info = cur.copy(kind = Kind.TEXT, fmt = "")
                    server?.close()
                    server = DocServer(this@ViewerActivity, cur.uri, "text/plain").apply { open() }
                    toolbar.subtitle = subtitleFor(info!!)
                    showText()
                    updateMenu()
                } catch (e: Throwable) {
                    Log.w(TAG, "mostra come testo fallito", e)
                    Toast.makeText(this@ViewerActivity, R.string.error_open, Toast.LENGTH_LONG).show()
                }
            }
        }
        content.addView(v, 0)
        updateMenu()
    }

    // ---------------------------------------------------------------- menu

    private fun updateMenu() {
        val m = toolbar.menu
        val i = info
        val k = i?.kind
        val hasFile = i?.uri != null
        val isPdf = k == Kind.PDF && web != null
        m.findItem(R.id.action_search).isVisible = web != null && (isPdf || k == Kind.TEXT || k == Kind.DOC || k == Kind.HTML)
        m.findItem(R.id.action_share).isVisible = hasFile || i?.sharedText != null
        m.findItem(R.id.action_open_with).isVisible = hasFile && otherApps && k != Kind.UNSUPPORTED
        m.findItem(R.id.action_source).apply {
            isVisible = web != null || sourceMode
            isVisible = isVisible && (k == Kind.HTML || (k == Kind.DOC && i?.fmt in SOURCE_FMTS))
            isChecked = sourceMode
        }
        m.findItem(R.id.action_scripts).apply {
            isVisible = k == Kind.HTML && !sourceMode
            isChecked = scriptsOn
        }
        m.findItem(R.id.action_goto).isVisible = isPdf && pdfPages > 1
        m.findItem(R.id.action_dark).apply {
            isVisible = isPdf
            isChecked = prefs.getBoolean("pdfDark", false)
        }
        m.findItem(R.id.action_rotate).isVisible = isPdf
        m.findItem(R.id.action_info).isVisible = i != null
        m.findItem(R.id.action_delete).isVisible = hasFile
        // 151 Storage: preferito e rinomina quando sappiamo il percorso vero del file
        val path = intent?.getStringExtra(EXTRA_PATH)
        m.findItem(R.id.action_fav).apply {
            isVisible = path != null
            if (path != null) setIcon(if (Favorites.has(this@ViewerActivity, Favorites.phone(path))) R.drawable.ic_star else R.drawable.ic_star_border)
        }
        m.findItem(R.id.action_rename).isVisible = path != null
    }

    private fun onMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> openSearch()
            R.id.action_share -> share()
            R.id.action_open_with -> openWithOther()
            R.id.action_goto -> gotoDialog()
            R.id.action_dark -> {
                val v = !prefs.getBoolean("pdfDark", false)
                prefs.edit().putBoolean("pdfDark", v).apply()
                item.isChecked = v
                js("V.setDark($v)")
            }
            R.id.action_rotate -> js("V.rotate()")
            R.id.action_source -> {
                val i = info ?: return true
                sourceMode = !sourceMode
                if (sourceMode) showSource() else if (i.kind == Kind.HTML) showHtml() else showDoc()
                toolbar.subtitle = subtitleFor(i)
                updateMenu()
            }
            R.id.action_scripts -> {
                val i = info ?: return true
                if (!scriptsOn) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.scripts_title)
                        .setMessage(R.string.scripts_body)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.scripts_on) { _, _ ->
                            scriptsOn = true; showHtml(); toolbar.subtitle = subtitleFor(i); updateMenu()
                        }
                        .show()
                } else {
                    scriptsOn = false; showHtml(); toolbar.subtitle = subtitleFor(i); updateMenu()
                }
            }
            R.id.action_info -> infoDialog()
            R.id.action_fav -> intent?.getStringExtra(EXTRA_PATH)?.let { p ->
                val on = Favorites.toggle(this, listOf(Favorites.phone(p)))
                Toast.makeText(this, if (on) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
                updateMenu()
            }
            R.id.action_rename -> intent?.getStringExtra(EXTRA_PATH)?.let { p ->
                val f = java.io.File(p)
                Ask.name(this, R.string.rename, f.name) { n ->
                    try {
                        val nf = PhoneOps.rename(this, f, n)
                        Favorites.rename(this, Favorites.phone(p), Favorites.phone(nf.absolutePath))
                        intent.putExtra(EXTRA_PATH, nf.absolutePath)
                        toolbar.title = nf.name
                        Library.refresh(this, force = true)
                        Toast.makeText(this, R.string.done_renamed, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
                }
            }
            R.id.action_delete -> {
                val i = info ?: return true
                val ref = referrer?.takeIf { it.scheme == "android-app" }?.host
                deleter.start(i.uri, intent?.getStringExtra(EXTRA_PATH), i.name, ref)
            }
            else -> return false
        }
        return true
    }

    /** File cancellato: via dal visualizzatore. L'avviso (con «Annulla» se si puo') lo da' chi ci ha aperto. */
    private fun afterDelete(d: Deleted) {
        video?.stopPlayback()
        if (intent?.getBooleanExtra(EXTRA_FROM_MAIN, false) == true) {
            TrashHandoff.pending = d
        } else {
            Toast.makeText(applicationContext, if (d.inTrash) R.string.moved_to_trash else R.string.deleted, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun share() {
        val i = info ?: return
        val send = Intent(Intent.ACTION_SEND)
        if (i.uri != null) {
            send.type = i.mime
            send.putExtra(Intent.EXTRA_STREAM, i.uri)
            send.clipData = ClipData.newRawUri(i.name, i.uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, i.sharedText)
        }
        startChooser(send, getString(R.string.share))
    }

    private fun openWithOther() {
        val i = info ?: return
        val uri = i.uri ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, i.mime.takeIf { it.isNotBlank() } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startChooser(view, getString(R.string.open_with))
    }

    /** Chooser che esclude 151 Viewer stesso (altrimenti "Apri con" riaprirebbe noi). */
    private fun startChooser(target: Intent, title: String) {
        val chooser = Intent.createChooser(target, title)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ViewerActivity::class.java)))
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // es. file:// verso altre app (FileUriExposedException)
            Log.w(TAG, "chooser fallito", e)
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmExternalLink(u: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Open the link?")
            .setMessage(u.toString())
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Open") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun gotoDialog() {
        if (pdfPages < 1) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "$pdfPage"
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.page_of, pdfPages))
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.go) { _, _ -> goPage(input.text.toString()) }
            .create()
        input.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_GO) { goPage(input.text.toString()); dlg.dismiss(); true } else false
        }
        dlg.setOnShowListener {
            input.requestFocus()
            WindowCompat.getInsetsController(dlg.window!!, input).show(WindowInsetsCompat.Type.ime())
        }
        dlg.show()
    }

    private fun goPage(s: String) {
        val n = s.trim().toIntOrNull() ?: return
        js("V.goto(${n.coerceIn(1, max(1, pdfPages))})")
    }

    private fun passwordDialog(wrong: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.password_title)
            .setMessage(if (wrong) getString(R.string.password_wrong) else null)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> js("V.passwordCancel()") }
            .setPositiveButton(R.string.ok) { _, _ -> js("V.password(${JSONObject.quote(input.text.toString())})") }
            .show()
    }

    private fun infoDialog() {
        val i = info ?: return
        val lines = mutableListOf("Name: ${i.name}", "Type: ${i.mime}")
        if (i.size >= 0) lines += "Size: ${Sizes.short(i.size)} (${i.size} bytes)"
        if (i.kind == Kind.PDF && pdfPages > 0) lines += "Pages: $pdfPages"
        i.uri?.let { lines += "Origine: ${it.scheme}://${it.authority ?: ""}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_info)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- ricerca

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(runFind)
                main.postDelayed(runFind, 300)
            }
        })
        searchInput.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                main.removeCallbacks(runFind)
                find(again = true, prev = false)
                true
            } else false
        }
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { find(again = true, prev = false) }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { find(again = true, prev = true) }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { closeSearch() }
    }

    private var lastQuery = ""

    private fun find(again: Boolean, prev: Boolean) {
        val q = searchInput.text.toString()
        val w = web ?: return
        if (q.isEmpty()) {
            searchCount.text = ""
            if (info?.kind == Kind.PDF) js("V.findClose()") else w.clearMatches()
            lastQuery = ""
            return
        }
        val isAgain = again && q == lastQuery
        lastQuery = q
        if (info?.kind == Kind.PDF) {
            js("V.find(${JSONObject.quote(q)}, $prev, $isAgain)")
        } else {
            if (isAgain) w.findNext(!prev) else w.findAllAsync(q)
        }
    }

    private fun findLabel(current: Int, total: Int): String =
        if (total <= 0) "No matches" else "$current of $total"

    private fun openSearch() {
        searchBar.isVisible = true
        searchInput.requestFocus()
        searchInput.selectAll()
        WindowCompat.getInsetsController(window, searchInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        searchBar.isVisible = false
        main.removeCallbacks(runFind)
        WindowCompat.getInsetsController(window, searchInput).hide(WindowInsetsCompat.Type.ime())
        searchCount.text = ""
        lastQuery = ""
        if (info?.kind == Kind.PDF) js("V.findClose()") else web?.clearMatches()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
