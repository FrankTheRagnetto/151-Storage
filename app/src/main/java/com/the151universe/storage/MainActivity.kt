package com.the151universe.storage

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.animation.ArgbEvaluator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Schermata dell'icona di 151 Storage: quattro posti con le linguette in basso (design/storage-spec.md §3).
 * Foto, Cartelle e Cloud sono [Pane]; Documenti è la libreria del 151 Viewer, com'era: due schede,
 * «Documenti» (serve «Accesso a tutti i file») e «Recenti», e «Apri un file».
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var scroll: View
    private lateinit var libPane: View
    private lateinit var libInvite: View
    private lateinit var libList: RecyclerView
    private lateinit var libEmpty: TextView
    private lateinit var libStatus: TextView
    private lateinit var libSearch: EditText
    private lateinit var chips: ChipGroup
    private lateinit var libBoard: View
    private lateinit var libPull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var boardAnim: ValueAnimator? = null
    private val monoFace: Typeface? by lazy { try { ResourcesCompat.getFont(this, R.font.mono_medium) } catch (_: Exception) { null } }
    private val chipOf = HashMap<Cat, Chip>()

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val filterExec = Executors.newSingleThreadExecutor()
    @Volatile private var filterGen = 0
    private val adapter = DocAdapter()

    private var tabDocs = true
    private var cat = Cat.ALL
    private var sort = Order.NEWEST
    /** La vista dei Documenti: 0 elenco, 2/3/4 per riga, come negli altri posti. */
    private var docCols = 0
    private lateinit var libBar: StatusBar.Buttons
    private var query = ""

    private val deleter = Deleter(this, onDeleted = ::afterDelete, onRestored = { renderLibrary(); Gallery.refresh(this); if (::folders.isInitialized) folders.afterDelete() }, onDeletedMany = ::afterDeleteMany)

    // ---- 151 Storage: i posti
    private var place = PLACE_PHOTOS
    private lateinit var photos: PhotosPane
    private lateinit var folders: FoldersPane
    private lateinit var cloud: CloudPane
    private val panes get() = mapOf(PLACE_PHOTOS to photos, PLACE_FOLDERS to folders, PLACE_CLOUD to cloud)
    private val pane: Pane? get() = panes[place]
    private lateinit var placesRow: LinearLayout
    private lateinit var selBar: LinearLayout
    private val placeTabs = HashMap<String, View>()
    private val upListener: () -> Unit = { main.post { if (!isDestroyed) renderUploads() } }
    private var upDoneShownUntil = 0L
    private val protonPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { Recents.tryPersist(this, uri); open(uri) }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, R.string.notif_needed, Toast.LENGTH_LONG).show()
    }

    /** Selezione (v6.1): tenere premuto un file lo seleziona; i percorsi scelti, nell'ordine. */
    private val selected = LinkedHashMap<String, LibFile>()
    private val selecting get() = selected.isNotEmpty()
    private val backSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }
    private val libListener: () -> Unit = { main.post { if (!isDestroyed) renderLibrary() } }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // il selettore concede sempre un permesso persistente: così il file resta nei recenti
            Recents.tryPersist(this, uri)
            open(uri)
        }
    }

    /** Android 8–10: niente «Accesso a tutti i file», bastano i permessi di storage classici. */
    private val legacyPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updateAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Prova del registratore di crash, SOLO su APK debuggable e solo da adb:
        //   adb shell am start -n com.the151universe.storage/.MainActivity --ez crash_test true
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra("crash_test", false) == true
        ) {
            intent.removeExtra("crash_test")
            throw IllegalStateException("test crash (crash_test)")
        }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.fab)
        scroll = findViewById(R.id.scroll)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        libPane = findViewById(R.id.libPane)
        libInvite = findViewById(R.id.libInvite)
        libList = findViewById(R.id.libList)
        libEmpty = findViewById(R.id.libEmpty)
        libStatus = findViewById(R.id.libStatus)
        libSearch = findViewById(R.id.libSearch)
        chips = findViewById(R.id.chips)
        libBoard = findViewById(R.id.libBoard)
        libPull = findViewById(R.id.libPull)

        placesRow = findViewById(R.id.places)
        selBar = findViewById(R.id.selBar)
        photos = PhotosPane(this)
        folders = FoldersPane(this)
        cloud = CloudPane(this)
        // il posto chiesto da fuori (notifica) vale solo al primo avvio, non quando Android ricrea la schermata
        place = (if (savedInstanceState == null) intent?.getStringExtra(EXTRA_PLACE) else null) ?: prefs.getString("place", PLACE_PHOTOS) ?: PLACE_PHOTOS
        cat = try { Cat.valueOf(prefs.getString("libCat", Cat.ALL.name)!!) } catch (_: Exception) { Cat.ALL }
        if (cat.name in SettingsActivity.hiddenDocs(this)) cat = Cat.ALL
        sort = Order.get(this, "docs", Order.NEWEST)
        docCols = MediaGrid.cols(this, "docs", 0)
        tabDocs = true // via «Recent»: i Documenti sono solo la libreria

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.updatePadding(left = b.left, top = b.top, right = b.right)
            findViewById<View>(R.id.placeHost).updatePadding(left = b.left, right = b.right)
            scroll.updatePadding(bottom = dp(96))
            libList.updatePadding(bottom = dp(96))
            findViewById<View>(R.id.bottomHost).updatePadding(left = b.left, right = b.right, bottom = b.bottom)
            insets
        }

        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) { settingsPage.launch(android.content.Intent(this, SettingsActivity::class.java)); return@setOnMenuItemClickListener true }
            if (place != PLACE_DOCS) {
                if (it.itemId == R.id.sel_all) { pane?.selectAll(); return@setOnMenuItemClickListener true }
                return@setOnMenuItemClickListener pane?.onMenu(it.itemId) ?: false
            }
            when (it.itemId) {
                R.id.action_clear -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear_recents)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok) { _, _ -> Recents.clear(this); render() }
                    .show()
                R.id.action_rescan -> Library.refresh(this, force = true)
                // un cestino solo: quello di Phone, dove si sceglie, si ripristina e si svuota;
                // il foglietto resta solo se Phone è nascosto nelle Impostazioni
                R.id.action_trash -> if (PLACE_FOLDERS in SettingsActivity.hiddenPlaces(this)) deleter.showTrash() else { showPlace(PLACE_FOLDERS, true); folders.openTrash() }
                R.id.sel_all -> { for (f in adapter.visible()) selected[f.path] = f; docsSelectionChanged() }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        fab.setOnClickListener { if (place == PLACE_DOCS) picker.launch(arrayOf("*/*")) else pane?.onFab() }
        onBackPressedDispatcher.addCallback(this, backSelection)
        onBackPressedDispatcher.addCallback(this, backPlace)
        fab.stateListAnimator = null // il pulsante è cartoncino col filo d'inchiostro: nessuna ombra
        fab.elevation = 0f

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addTab(tabs.newTab().setText(R.string.tab_docs), tabDocs)
        tabs.addTab(tabs.newTab().setText(R.string.tab_recents), !tabDocs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tabDocs = t.position == 0
                clearSelection()
                prefs.edit().putBoolean("tabDocs", tabDocs).apply()
                hideKeyboard()
                showTab()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { if (tabDocs) libList.scrollToPosition(0) }
        })

        setupLibrary()
        findViewById<View>(R.id.inviteGrant).setOnClickListener { requestAccess() }
        findViewById<View>(R.id.upRow).setOnClickListener { JobsSheet.show(this) }
        buildPlaces()
        showPlace(place, animate = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PLACE)?.let { showPlace(it, animate = false) }
    }

    override fun onStart() {
        super.onStart()
        Library.addListener(libListener)
        Uploads.addListener(upListener)
        renderUploads()
        // una coda lasciata a metà (app chiusa, aggiornata, tempo di Android finito) riparte da sola all'apertura
        // anteprime di tutto il cloud: a ogni apertura, finché non ci sono tutte; ma non mentre si carica
        // (prima partivano insieme e le anteprime aspettavano col telefono sveglio)
        val app = applicationContext
        Thread({
            try { if (Uploads.tidy(app).any { it.state == UpJob.WAIT }) UploadService.start(app) else ThumbService.start(app) }
            catch (x: Throwable) { android.util.Log.w("151Storage", "coda/anteprime all'apertura", x) }
        }, "coda-riprende").start()
        LinkJob.kick(this) // cartelle collegate: un giro all'apertura, poi da sole (LinkJob)
        Trashes.sweepIfDue(this) // il cestino del cloud si svuota da solo dopo 30 giorni (lo fa l'app)
    }

    override fun onStop() {
        Library.removeListener(libListener)
        Uploads.removeListener(upListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (CrashLog.showIfPending(this)) return
        render()
        updateAccess()
        TrashHandoff.pending?.let { TrashHandoff.pending = null; afterDelete(it) }
        pane?.onResume()
        showAccessInvite()
    }

    override fun onDestroy() {
        filterExec.shutdownNow()
        deleter.shutdown()
        panes.values.forEach { it.destroy() }
        super.onDestroy()
    }

    private fun showTab() {
        if (place != PLACE_DOCS) return
        scroll.isVisible = !tabDocs
        empty.isVisible = !tabDocs && Recents.list(this).isEmpty()
        val access = Storage.hasAccess(this)
        libPane.isVisible = tabDocs && access
        libInvite.isVisible = tabDocs && !access
        toolbar.menu.findItem(R.id.action_rescan)?.isVisible = tabDocs && access
        toolbar.menu.findItem(R.id.action_trash)?.isVisible = tabDocs && access && Build.VERSION.SDK_INT >= 30
        toolbar.menu.findItem(R.id.action_clear)?.isVisible = !tabDocs
        StatusBar.activate(this, place)
        if (tabDocs && !access && !prefs.getBoolean("accessAsked", false)) {
            // primo uso: chiediamo una volta, con il perché in una riga. Se dice no resta l'invito.
            prefs.edit().putBoolean("accessAsked", true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.access_title)
                .setMessage(R.string.access_why)
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.grant_access) { _, _ -> requestAccess() }
                .show()
        }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) Deleter.openAccessSettings(this)
        else legacyPerm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    /** Il permesso puo' cambiare mentre siamo fuori (Impostazioni): lo si ricontrolla a ogni ritorno. */
    private fun updateAccess() {
        if (Storage.hasAccess(this)) {
            Library.refresh(this)
        } else if (Library.files.isNotEmpty() || Library.loadedFromCache || Library.hasCache(this)) {
            Library.forget(this) // revocato: niente elenco di file che non possiamo piu' leggere
        }
        showTab()
        renderLibrary()
    }

    // ---------------------------------------------------------------- libreria

    private fun setupLibrary() {
        libList.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, maxOf(1, docCols))
        libBar = StatusBar.attach(libStatus, PLACE_DOCS, ready = { tabDocs && Storage.hasAccess(this) }, onSort = {
            sort = Order.cycle(this, "docs", sort); libBar.show(sort, docCols); renderLibrary(scrollTop = true)
        }, onView = {
            docCols = MediaGrid.cycle(this, "docs", 0)
            (libList.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount = maxOf(1, docCols)
            libBar.show(sort, docCols); adapter.viewChanged(); libList.invalidateItemDecorations()
        })
        libBar.show(sort, docCols)
        libList.adapter = adapter
        libList.setHasFixedSize(true)
        libList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
        findViewById<View>(R.id.libGrant).setOnClickListener { requestAccess() }
        // i tipi si spostano trascinandoli e l'ordine resta; «All» resta prima
        val drag = TabDrag(chips, fixed = 1) { order -> TabDrag.save(prefs, "libOrder", order.drop(1)); chips.post { revealTab() } }
        val kinds = listOf(Cat.ALL) + TabDrag.arrange(Cat.entries.filter { it != Cat.ALL }, TabDrag.saved(prefs, "libOrder")) { it.name }
        for (c in kinds) {
            val chip = sectionTab(c)
            chipOf[c] = chip
            chips.addView(chip)
            if (c != Cat.ALL) drag.draggable(chip)
        }
        paintBoard(animate = false)
        chips.setOnCheckedStateChangeListener { _, ids ->
            val c = chipOf.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            if (c == cat) return@setOnCheckedStateChangeListener
            // il foglio entra dal lato della linguetta scelta, sia toccandola sia strisciando
            val dir = chips.indexOfChild(chipOf[c]) - chips.indexOfChild(chipOf[cat])
            cat = c
            clearSelection()
            prefs.edit().putString("libCat", c.name).apply()
            paintBoard(animate = true)
            renderLibrary(scrollTop = true, enter = dir)
        }
        libList.addItemDecoration(LeafLines())
        TabSwipe.attach(libList, chips)
        // tirare in giù il foglio rilegge i documenti, come «Rescan» nel menu
        Pull.style(libPull)
        libPull.setOnRefreshListener { if (Storage.hasAccess(this)) Library.refresh(this, force = true) else libPull.isRefreshing = false }
        chips.post { revealTab() }
    }

    /** Filtra, conta e ordina FUORI dal thread principale; l'ultimo giro vince. */
    private fun renderLibrary(scrollTop: Boolean = false, enter: Int? = null) {
        if (!Storage.hasAccess(this)) return
        val all = Library.files
        val c = cat
        val s = sort
        val q = query.lowercase(Locale.ROOT)
        val hid = SettingsActivity.hiddenDocs(this) // tipi nascosti nelle Impostazioni: via anche da All
        val gen = ++filterGen
        if (filterExec.isShutdown) return
        filterExec.execute {
            val counts = IntArray(Cat.entries.size)
            val out = ArrayList<LibFile>()
            for (f in all) {
                if (q.isNotEmpty() && !f.nameKey.contains(q)) continue
                if (f.cat.name in hid) continue
                counts[f.cat.ordinal]++
                if (!f.cat.isMedia) counts[Cat.ALL.ordinal]++
                if (c == Cat.ALL) { if (!f.cat.isMedia) out += f } else if (f.cat == c) out += f
            }
            when (s) {
                Order.NAME -> out.sortWith(compareBy<LibFile> { it.nameKey }.thenByDescending { it.mtime })
                Order.SIZE -> out.sortWith(compareByDescending<LibFile> { it.size }.thenBy { it.nameKey })
                Order.OLDEST -> out.sortWith(compareBy<LibFile> { it.mtime }.thenBy { it.nameKey })
                Order.TYPE -> out.sortWith(compareBy<LibFile> { it.ext }.thenBy { it.nameKey })
                else -> out.sortWith(compareByDescending<LibFile> { it.mtime }.thenBy { it.nameKey })
            }
            main.post {
                if (gen != filterGen || isDestroyed) return@post
                // le linguette vuote non si vedono;
                // restano «All» e quella aperta. Mentre si cerca contano i risultati: si nasconde anche lì quello che è vuoto
                for ((k, chip) in chipOf) { chip.text = tabLabel(k, counts[k.ordinal]); chip.isVisible = k == Cat.ALL || (k.name !in hid && (k == c || counts[k.ordinal] > 0)) }
                adapter.submit(out)
                if (scrollTop) libList.scrollToPosition(0)
                if (enter != null) Motion.slideIn(libList, enter)
                val scanning = Library.scanning
                if (!scanning) libPull.isRefreshing = false
                libEmpty.isVisible = out.isEmpty()
                libEmpty.setText(
                    when {
                        out.isNotEmpty() -> R.string.lib_nothing
                        // prima della prima lettura «No files of this kind» lampeggiava all'apertura
                        all.isEmpty() && (scanning || !Library.loadedFromCache) -> R.string.lib_scanning
                        q.isNotEmpty() -> R.string.lib_no_match
                        else -> R.string.lib_nothing
                    }
                )
                val n = out.size
                libStatus.text = buildString {
                    append(if (n == 1) "1 file" else String.format(Locale.UK, "%,d files", n))
                    if (scanning) append(" · ").append(getString(R.string.lib_updating))
                }
            }
        }
    }

    private fun openFile(f: LibFile) {
        val uri = try { Storage.uriFor(this, f.path) } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show(); return
        }
        open(uri, path = f.path)
    }

    // ---------------------------------------------------------------- selezione

    private fun toggle(f: LibFile) {
        if (selected.remove(f.path) == null) selected[f.path] = f
        docsSelectionChanged()
    }

    /** Casella tenuta premuta: se sono già tutti scelti li toglie, altrimenti sceglie tutti quelli in vista. */
    private fun toggleAll() {
        val visible = adapter.visible()
        if (visible.isNotEmpty() && visible.all { selected.containsKey(it.path) }) selected.clear()
        else for (f in visible) selected[f.path] = f
        docsSelectionChanged()
    }

    private fun clearSelection() {
        if (!selecting) return
        selected.clear()
        docsSelectionChanged()
    }

    private fun docsSelectionChanged() {
        val on = selecting
        backSelection.isEnabled = on
        backPlace.isEnabled = !on
        val files = selected.values.map { java.io.File(it.path) }
        val acts = if (!on) emptyList() else listOf(
            SelAction(R.string.sel_delete, R.drawable.ic_delete) { deleter.startMany(selected.values.toList()) },
            SelAction(R.string.sel_share, R.drawable.ic_share) { shareUris(files.mapNotNull { f -> try { Storage.uriFor(this, f.path) to Thumbs.mimeOf(f.name) } catch (_: Exception) { null } }) },
            SelAction(R.string.sel_upload, R.drawable.ic_upload) {
                uploadSheet(selected.values.map { UpSource(it.path, it.name, it.size, Thumbs.mimeOf(it.name), 0, it.mtime, it.path) }) { clearSelection() }
            },
        ) + phoneSecondary(files) { clearSelection(); Library.refresh(this, force = true) }
        renderSelBar(selected.size, acts) { clearSelection() }
        if (on && !prefs.getBoolean("selHint", false)) {
            prefs.edit().putBoolean("selHint", true).apply()
            Snackbar.make(root, R.string.select_hint, Snackbar.LENGTH_LONG).show()
        }
        if (!on) { fab.show(); showTab() }
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
    }

    private fun afterDeleteMany(m: DeletedMany) {
        if (place == PLACE_PHOTOS) photos.afterDelete()
        if (place == PLACE_FOLDERS) folders.afterDelete()
        Gallery.refresh(this)
        selected.clear()
        if (place == PLACE_DOCS) docsSelectionChanged() else placeChromeChanged()
        render()
        renderLibrary()
        val parts = mutableListOf<String>()
        if (m.trashed.isNotEmpty()) parts += resources.getQuantityString(R.plurals.moved_many, m.trashed.size, m.trashed.size)
        if (m.gone > 0) parts += resources.getQuantityString(R.plurals.deleted_many, m.gone, m.gone)
        if (m.failed > 0) parts += resources.getQuantityString(R.plurals.failed_many, m.failed, m.failed)
        if (parts.isEmpty()) return
        val bar = Snackbar.make(root, parts.joinToString(" · "), Snackbar.LENGTH_LONG)
        if (m.trashed.isNotEmpty()) bar.setAction(R.string.undo) { deleter.undoMany(m) }
        bar.duration = 8000 // «Annulla» deve restare il tempo di pensarci
        bar.anchorView = if (fab.isShown) fab else findViewById(R.id.bottomHost)
        bar.show()
    }

    private fun afterDelete(d: Deleted) {
        render()
        renderLibrary()
        val bar = Snackbar.make(root, if (d.inTrash) R.string.moved_to_trash else R.string.deleted, Snackbar.LENGTH_LONG)
        if (d.inTrash) bar.setAction(R.string.undo) { deleter.undo(d) }
        bar.duration = 8000 // «Annulla» deve restare il tempo di pensarci
        bar.anchorView = if (fab.isShown) fab else findViewById(R.id.bottomHost)
        bar.show()
    }

    private inner class DocAdapter : RecyclerView.Adapter<DocAdapter.VH>() {
        private var items: List<LibFile> = emptyList()
        private val now = System.currentTimeMillis()

        fun submit(l: List<LibFile>) {
            items = l
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        fun visible(): List<LibFile> = items
        @Suppress("NotifyDataSetChanged") fun viewChanged() = notifyDataSetChanged()

        override fun getItemViewType(position: Int) = if (docCols > 0) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(if (viewType == 1) R.layout.item_doc_tile else R.layout.item_doc, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = items[pos]
            h.file = f
            Sections.paintBadge(h.badge, f.cat, badgeText(f), dp(if (docCols > 0) 8 else 4).toFloat())
            h.name.text = f.name
            val date = Dates.short(f.mtime)
            if (docCols > 0) {
                h.badge.textSize = when (docCols) { 2 -> 26f; 3 -> 19f; else -> 15f }
                h.meta.text = date
            } else h.meta.text = "${f.folder} · $date · ${Sizes.short(f.size)}"
            val on = selected.containsKey(f.path)
            h.itemView.isActivated = on
            h.check.isVisible = selecting
            h.check.isChecked = on
            h.check.contentDescription = getString(R.string.select_file) + " " + f.name
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView = v.findViewById(R.id.badge)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val check: MaterialCheckBox = v.findViewById(R.id.check)
            var file: LibFile? = null

            init {
                v.setOnClickListener { file?.let { if (selecting) toggle(it) else openFile(it) } }
                v.setOnLongClickListener { file?.let { toggle(it) }; true }
                check.setOnClickListener { file?.let { toggle(it) } }
                check.setOnLongClickListener { toggleAll(); true }
            }
        }
    }

    private fun badgeText(f: LibFile): String = when {
        f.ext.isEmpty() -> "?"
        f.ext == "jpeg" -> "JPG"
        f.ext == "markdown" -> "MD"
        else -> f.ext.take(4).uppercase(Locale.ROOT)
    }

    // ---------------------------------------------------------------- recenti

    /**
     * Apre un URI nel visualizzatore. v1 crashava qui (SecurityException da startActivity) quando
     * si toccava un recente arrivato da "Apri con": il permesso temporaneo era gia' morto e il flag
     * FLAG_GRANT_READ_URI_PERMISSION chiedeva di ri-concedere un permesso che non avevamo piu'.
     * Ora il flag si mette solo se il permesso c'e' davvero, e ogni errore diventa un messaggio.
     */
    private fun open(uri: Uri, fromRecents: Boolean = false, path: String? = null) {
        val i = Intent(this, ViewerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
            .putExtra(ViewerActivity.EXTRA_FROM_MAIN, true)
        if (path != null) i.putExtra(ViewerActivity.EXTRA_PATH, path)
        if (Recents.canRead(this, uri)) i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else if (fromRecents) {
            Recents.remove(this, uri.toString())
            render()
            Toast.makeText(this, R.string.recent_gone, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            android.util.Log.w("151Viewer", "apertura fallita: $uri", e)
            if (fromRecents) { Recents.remove(this, uri.toString()); render() }
            Toast.makeText(this, if (e is SecurityException) R.string.recent_gone else R.string.error_open, Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        Recents.prune(this) // via le voci senza permesso (es. arrivate da "Apri con" con la v1)
        val items = Recents.list(this)
        list.removeAllViews()
        if (list.dividerDrawable == null) {
            list.dividerDrawable = InsetDrawable(ContextCompat.getDrawable(this, R.drawable.seam_line), dp(70), 0, 0, 0)
            list.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        list.isVisible = items.isNotEmpty()
        empty.isVisible = !tabDocs && items.isEmpty()
        val now = System.currentTimeMillis()
        for (r in items) {
            val row = layoutInflater.inflate(R.layout.item_recent, list, false)
            Sections.paintBadge(row.findViewById(R.id.badge), Sections.catOfName(r.name), Sections.badgeText(r.name), dp(4).toFloat())
            row.findViewById<TextView>(R.id.name).text = r.name
            val meta = mutableListOf<String>()
            if (r.size >= 0) meta += Sizes.short(r.size)
            if (r.kind == Kind.PDF.name && r.page > 1) meta += "pag. ${r.page}"
            meta += Dates.ago(r.time, now)
            row.findViewById<TextView>(R.id.meta).text = meta.joinToString(" · ")
            row.setOnClickListener { open(Uri.parse(r.uri), fromRecents = true, path = Storage.ownPath(this, Uri.parse(r.uri))) }
            row.setOnLongClickListener {
                MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Viewer_Dialog_List)
                    .setTitle(r.name)
                    .setItems(arrayOf(getString(R.string.remove_from_recents))) { _, _ ->
                        Recents.remove(this, r.uri); render()
                    }
                    .show()
                true
            }
            list.addView(row)
        }
    }

    private fun hideKeyboard() {
        if (libSearch.hasFocus()) {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(libSearch.windowToken, 0)
            libSearch.clearFocus()
        }
    }

    // ---------------------------------------------------------------- 151 Storage: i quattro posti

    /** Indietro: prima il posto (cartella su di un livello, selezione), poi l'app si chiude. */
    private val backPlace = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (pane?.onBack() == true) return
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    /** Le Impostazioni: al ritorno si rilegge cosa è nascosto (posti, tipi di Docs, album). */
    private val settingsPage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { applySections() }

    fun applySections() {
        val off = SettingsActivity.hiddenPlaces(this)
        for ((k, tab) in placeTabs) tab.isVisible = k !in off
        if (place in off) showPlace(place, animate = false)
        if (cat.name in SettingsActivity.hiddenDocs(this)) chipOf[Cat.ALL]?.isChecked = true // il suo ascoltatore ridisegna
        renderLibrary()
        photos.albumsChanged()
    }

    private fun buildPlaces() {
        placesRow.removeAllViews()
        val defs = listOf(
            Triple(PLACE_PHOTOS, R.string.place_photos, R.drawable.ic_photos),
            Triple(PLACE_DOCS, R.string.place_docs, R.drawable.ic_documents),
            // Telefono di nuovo: le cartelle vere del telefono, da spostare e caricare intere
            Triple(PLACE_FOLDERS, R.string.place_folders, R.drawable.ic_folder),
            Triple(PLACE_CLOUD, R.string.place_cloud, R.drawable.ic_cloud),
        )
        for ((key, label, icon) in defs) {
            // icona e nome uno accanto all'altro, con aria intorno
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), 0)
                background = ContextCompat.getDrawable(context, R.drawable.place_tab_top)?.mutate()
                isClickable = true
                isFocusable = true
                contentDescription = getString(label)
                setOnClickListener { if (place != key) showPlace(key, animate = true) else pane?.onBack() }
            }
            val iv = ImageView(this).apply { setImageResource(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val hole = ImageView(this).apply { setImageResource(R.drawable.punch_hole); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val tv = TextView(this).apply {
                setText(label)
                setTextAppearance(R.style.TextAppearance_Viewer_Tab)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END // «CLOUD · 1,234» su un telefono stretto: puntini, non lettere tagliate
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(hole, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            row.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(7) })
            row.addView(tv)
            tab.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tab.tag = Triple(iv, tv, hole)
            placesRow.addView(tab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            tab.isVisible = key !in SettingsActivity.hiddenPlaces(this)
            placeTabs[key] = tab
        }
    }

    /** La tinta della lastra del posto aperto: Documenti prende la sezione scelta. */
    private fun placeHue(key: String): Int = if (key == PLACE_DOCS) ContextCompat.getColor(this, R.color.place_docs) else panes[key]!!.boardColor()

    /** La riga del conteggio prende il colore del posto aperto: la linguetta scelta ci scende dentro (schedario). */
    private fun paintStatusRow() {
        val hue = placeHue(place)
        findViewById<View>(R.id.statusRow).setBackgroundColor(hue)
        StatusBar.tint(this, Sections.onHue(this, hue))
    }

    private fun paintPlaces() {
        paintStatusRow()
        for ((key, tab) in placeTabs) {
            @Suppress("UNCHECKED_CAST") val parts = tab.tag as Triple<ImageView, TextView, ImageView>
            val (iv, tv, hole) = parts
            val on = key == place
            val hue = placeHue(key)
            val ink = if (on) Sections.onHue(this, hue) else ContextCompat.getColor(this, R.color.ink_muted)
            (tab.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (on) hue else ContextCompat.getColor(this, R.color.acetate_sunk))
            iv.imageTintList = Sections.tint(ink)
            tv.setTextColor(ink)
            // la scelta ha il foro AL POSTO dell'icona, come le sotto-linguette («● ALL»): foro e icona insieme non ci
            // stavano e il nome si tagliava («ME…», «PH…», «CL…» sul telefono di prova); così la scelta non si allarga mai
            hole.isVisible = on
            iv.isVisible = !on
            tab.isSelected = on
            tab.elevation = 0f
            val target = if (on) 0f else dp(6).toFloat()
            tab.animate().translationY(target).setDuration(HINGE_MS).setInterpolator(HINGE).start()
        }
    }

    fun showPlace(key0: String, animate: Boolean) {
        val key1 = key0
        if (key1 !in PLACES) return
        // un posto nascosto nelle Impostazioni non si apre: si va al primo acceso
        val off = SettingsActivity.hiddenPlaces(this)
        val key = if (key1 !in off) key1 else PLACES.firstOrNull { it !in off } ?: PLACE_PHOTOS
        clearSelection()
        pane?.clearSelection()
        pane?.onHide()
        val order = PLACES
        val dir = order.indexOf(key) - order.indexOf(place)
        place = key
        prefs.edit().putString("place", key).apply()
        hideKeyboard()
        findViewById<View>(R.id.placeDocs).isVisible = key == PLACE_DOCS
        for ((k, p) in panes) p.root.isVisible = k == key
        pane?.onShow()
        applyQuery(key, searchText) // la ricerca segue chi cambia posto
        if (key == PLACE_DOCS) { showTab(); renderLibrary() }
        placeChromeChanged()
        showAccessInvite()
        // il posto nuovo entra scivolando dal lato della sua linguetta
        if (animate && dir != 0) {
            val shown = when {
                findViewById<View>(R.id.accessInvite).isVisible -> findViewById<View>(R.id.accessInvite)
                key == PLACE_DOCS -> libList
                else -> pane?.sheet
            }
            shown?.let { Motion.slideIn(it, dir, distDp = 24) }
        }
    }

    /** Barra in alto, pulsante e linguette del posto aperto (cambiano anche dentro un posto, es. Cloud collegato). */
    fun placeChromeChanged() {
        StatusBar.activate(this, place)
        if (place == PLACE_DOCS) {
            if (!selecting) { rebuildingMenu = true; toolbar.menu.clear(); rebuildingMenu = false; toolbar.inflateMenu(R.menu.main); showTab(); wireSearch() }
            fab.text = getString(R.string.open_file); fab.setIconResource(R.drawable.ic_folder_open); fab.show()
        } else {
            rebuildingMenu = true
            toolbar.menu.clear()
            rebuildingMenu = false
            toolbar.title = getString(R.string.app_name)
            toolbar.navigationIcon = null
            pane?.menuRes?.takeIf { it != 0 }?.let { toolbar.inflateMenu(it) }
            if (place == PLACE_CLOUD) cloud.menuReady(toolbar.menu)
            wireSearch()
            val f = pane?.fab
            if (f != null) { fab.setText(f.first); fab.setIconResource(f.second); fab.show() } else fab.hide()
        }
        paintPlaces()
    }

    /** La lente nella barra in alto: ogni posto cerca a modo suo ([Pane.onQuery]). */
    // ---- una ricerca sola: la lente in alto a destra in ogni posto; cerca nel posto aperto e, sulle
    // linguette, dice quanti risultati ci sono negli altri. Cambiando posto la ricerca resta aperta, con lo stesso testo.

    private var searchText = ""
    private var rebuildingMenu = false
    private val searchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var searchGen = 0

    private fun applyQuery(key: String, q: String) {
        if (key == PLACE_DOCS) { if (q != query) { query = q; clearSelection(); renderLibrary(scrollTop = true) } }
        else panes[key]?.onQuery(q)
    }

    private fun wireSearch() {
        val item = toolbar.menu.findItem(R.id.search) ?: return
        val sv = item.actionView as? androidx.appcompat.widget.SearchView ?: return
        sv.queryHint = getString(pane?.searchHint ?: R.string.lib_search_hint)
        sv.maxWidth = Int.MAX_VALUE
        sv.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { sv.clearFocus(); return true }
            override fun onQueryTextChange(q: String?): Boolean {
                searchText = q.orEmpty().trim(); applyQuery(place, searchText); countEverywhere(); return true
            }
        })
        item.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(i: android.view.MenuItem) = true
            override fun onMenuItemActionCollapse(i: android.view.MenuItem): Boolean {
                if (rebuildingMenu) return true // il menu si rifà cambiando posto: la ricerca resta
                searchText = ""; for (k in PLACES) applyQuery(k, ""); countEverywhere(); return true
            }
        })
        if (searchText.isNotEmpty()) { item.expandActionView(); sv.setQuery(searchText, false); sv.clearFocus() }
    }

    /** Quanti risultati in ogni posto, scritti sulle linguette («Cloud · 5»); senza ricerca, i nomi soli. */
    private fun countEverywhere() {
        val q = searchText.lowercase(Locale.ROOT)
        val g = ++searchGen
        if (q.isEmpty()) { paintPlaceLabels(emptyMap()); return }
        searchExec.execute {
            val n = HashMap<String, Int>()
            n[PLACE_PHOTOS] = (Gallery.items + Gallery.offline).count { it.name.lowercase().contains(q) || it.album.lowercase().contains(q) }
            n[PLACE_DOCS] = Library.files.count { !it.cat.isMedia && it.nameKey.contains(q) }
            n[PLACE_CLOUD] = Clouds.drive(this)?.let { d -> try { Index.search(this, d, q)?.size } catch (_: Exception) { null } } ?: 0
            main.post { if (g == searchGen && !isDestroyed) paintPlaceLabels(n) }
        }
    }

    private fun paintPlaceLabels(n: Map<String, Int>) {
        for ((key, tab) in placeTabs) {
            @Suppress("UNCHECKED_CAST") val tv = (tab.tag as Triple<ImageView, TextView, ImageView>).second
            val base = getString(when (key) { PLACE_PHOTOS -> R.string.place_photos; PLACE_DOCS -> R.string.place_docs; PLACE_FOLDERS -> R.string.place_folders; else -> R.string.place_cloud })
            tv.text = n[key]?.let { "$base · ${String.format(Locale.UK, "%,d", it)}" } ?: base
        }
    }

    /** Solo il pulsante in basso a destra (senza toccare il menu: rifarlo chiuderebbe la ricerca mentre si scrive). */
    fun refreshFab() {
        if (place == PLACE_DOCS || selecting || (pane?.selectedCount ?: 0) > 0) return
        val f = pane?.fab
        if (f != null) { fab.setText(f.first); fab.setIconResource(f.second); fab.show() } else fab.hide()
    }

    /** Foto e Cartelle senza «Accesso a tutti i file»: il foglietto-invito al posto della lastra. */
    private fun showAccessInvite() {
        val need = (place == PLACE_PHOTOS || place == PLACE_FOLDERS) && !Storage.hasAccess(this)
        findViewById<View>(R.id.accessInvite).isVisible = need
        if (need) {
            pane?.root?.isVisible = false
            findViewById<TextView>(R.id.inviteTitle).setText(if (place == PLACE_PHOTOS) R.string.photos_invite_title else R.string.folders_invite_title)
            findViewById<ImageView>(R.id.inviteIcon).apply {
                setImageResource(if (place == PLACE_PHOTOS) R.drawable.ic_photos else R.drawable.ic_folder)
                imageTintList = Sections.tint(placeHue(place))
            }
        } else pane?.root?.isVisible = true
    }

    /** Selezione in Foto, Cartelle o Cloud: la barra delle azioni prende il posto delle linguette (spec §5). */
    fun selectionChanged() {
        val p = pane ?: return
        backSelection.isEnabled = false
        renderSelBar(p.selectedCount, if (p.selectedCount > 0) p.selectionActions() else emptyList()) { p.clearSelection() }
    }

    /**
     * La selezione è uguale in tutti e quattro i posti: in alto «N SELEZIONATI», la X e
     * «Seleziona tutto»; in basso, al posto delle linguette, le azioni principali e «Altro».
     */
    private fun renderSelBar(n: Int, acts: List<SelAction>, close: () -> Unit) {
        // svuotata solo per rifarla: uscendo dalla selezione i bottoni restano mentre la barra sfuma
        if (n > 0) selBar.removeAllViews()
        if (n > 0) {
            // Share, Upload/Download, Delete, More; le altre azioni dopo, nel loro ordine
            val rank = { a: SelAction -> when (a.label) { R.string.sel_share -> 0; R.string.sel_upload, R.string.sel_download -> 1; R.string.sel_delete -> 2; else -> 3 } }
            val main = acts.filter { it.primary }.sortedBy(rank).take(3)
            val more = acts - main.toSet()
            for (a in main) selBar.addView(MediaActivity.actionButton(this, a.label, a.icon, a.run),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            if (more.isNotEmpty()) {
                lateinit var btn: View
                btn = MediaActivity.actionButton(this, R.string.more, R.drawable.ic_more, {
                    val pm = androidx.appcompat.widget.PopupMenu(this, btn, android.view.Gravity.END)
                    more.forEachIndexed { i, a -> pm.menu.add(0, i, i, a.label) }
                    pm.setOnMenuItemClickListener { more[it.itemId].run(); true }
                    pm.show()
                })
                selBar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            toolbar.title = resources.getQuantityString(R.plurals.selected_count, n, n)
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.setNavigationContentDescription(R.string.selection_close)
            toolbar.setNavigationOnClickListener { close() }
            rebuildingMenu = true; toolbar.menu.clear(); rebuildingMenu = false
            toolbar.menu.add(0, R.id.sel_all, 0, R.string.select_all).setIcon(R.drawable.ic_select_all)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            fab.hide()
            findViewById<View>(R.id.bottomSeam).setBackgroundColor(ContextCompat.getColor(this, R.color.ink))
        } else {
            findViewById<View>(R.id.bottomSeam).setBackgroundColor(ContextCompat.getColor(this, R.color.seam))
            placeChromeChanged()
        }
        Motion.fade(selBar, n > 0, riseDp = 12)
        // in basso resta solo la barra della selezione (e lo spazio della barra di Android); il filo solo con lei
        Motion.fade(findViewById(R.id.bottomSeam), n > 0)
    }

    // ---- azioni comuni ai posti

    private val ops = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Le azioni «Altro» per file e cartelle del telefono: rinomina, sposta, copia, info, apri con, preferito. */
    fun phoneSecondary(files: List<java.io.File>, after: () -> Unit): List<SelAction> {
        val out = ArrayList<SelAction>()
        val one = files.singleOrNull()
        fun done(msg: String) { main.post { if (!isDestroyed) { Ask.toast(this, msg); after(); Gallery.refresh(this) } } }
        if (one != null) out += SelAction(R.string.rename, R.drawable.ic_edit, false) {
            Ask.name(this, R.string.rename, one.name) { n ->
                ops.execute {
                    val r = try { val nf = PhoneOps.rename(this, one, n); Favorites.rename(this, Favorites.phone(one.absolutePath), Favorites.phone(nf.absolutePath)); getString(R.string.done_renamed) } catch (e: Exception) { e.message ?: "error" }
                    done(r)
                }
            }
        }
        for (copy in listOf(false, true)) out += SelAction(if (copy) R.string.copy_to else R.string.move, if (copy) R.drawable.ic_copy else R.drawable.ic_folder, false) {
            PhonePicker.show(this, if (copy) R.string.copy_title else R.string.move_title, if (copy) R.string.copy_here else R.string.move_here, files.first().parentFile) { dest ->
                ops.execute {
                    var n = 0; var err: String? = null
                    for (f in files) try { PhoneOps.transfer(this, f, dest, copy); n++ } catch (e: Exception) { err = e.message }
                    done(err?.let { "$it ($n done)" } ?: getString(if (copy) R.string.done_copied else R.string.done_moved, n))
                }
            }
        }
        if (one != null && one.isDirectory) out += SelAction(R.string.link_folder, R.drawable.ic_cloud, false) { after(); LinkUi.start(this, one) }
        if (one != null) out += SelAction(R.string.file_info, R.drawable.ic_info, false) {
            ops.execute { val t = PhoneOps.info(this, one); main.post { Ask.info(this, one.name, t) } }
        }
        if (one != null && one.isFile) out += SelAction(R.string.open_with, R.drawable.ic_open_with, false) {
            try { Ask.openWith(this, Storage.uriFor(this, one.absolutePath), Thumbs.mimeOf(one.name), one.name) } catch (_: Exception) {}
        }
        val allFav = files.isNotEmpty() && files.all { Favorites.has(this, Favorites.phone(it.absolutePath)) }
        out += SelAction(if (allFav) R.string.fav_remove else R.string.fav_add, R.drawable.ic_star, false) {
            val on = Favorites.toggle(this, files.map { Favorites.phone(it.absolutePath) })
            Ask.toast(this, getString(if (on) R.string.fav_added else R.string.fav_removed)); after()
        }
        return out
    }

    fun openMedia(items: List<MediaItem>, index: Int, from: Int, cell: View? = null) {
        if (items.isEmpty()) return
        Roll.items = items
        // il visore si allarga dalla miniatura toccata; senza miniatura sale piano dissolvendo
        val opts = if (cell != null && cell.width > 0) android.app.ActivityOptions.makeScaleUpAnimation(cell, 0, 0, cell.width, cell.height)
            else android.app.ActivityOptions.makeCustomAnimation(this, R.anim.media_open, R.anim.media_stay)
        startActivity(Intent(this, MediaActivity::class.java).putExtra(MediaActivity.EXTRA_INDEX, index).putExtra(MediaActivity.EXTRA_FROM, from), opts.toBundle())
    }

    fun openPath(path: String) {
        val uri = try { Storage.uriFor(this, path) } catch (e: Exception) { Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show(); return }
        open(uri, path = path)
    }

    fun openUri(uri: Uri) = open(uri)

    fun deleteFiles(files: List<LibFile>, folders: List<String> = emptyList(), ask: Boolean = true) = deleter.startMany(files, folders, ask)

    fun shareUris(list: List<Pair<Uri, String>>) {
        if (list.isEmpty()) return
        val uris = ArrayList(list.map { it.first })
        val mime = list.map { it.second }.distinct().singleOrNull() ?: if (list.all { it.second.startsWith("image/") }) "image/*" else "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        try { startActivity(Intent.createChooser(send, getString(R.string.share))) } catch (_: Exception) { Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show() }
    }

    fun uploadSheet(items: List<UpSource>, emptyDirs: List<String> = emptyList(), onQueued: () -> Unit) = UploadSheet.show(this, items, null, emptyDirs, onQueued)

    fun openFromProton() {
        if (!cloud.protonInstalled()) { Toast.makeText(this, R.string.proton_missing, Toast.LENGTH_LONG).show(); return }
        protonPicker.launch(arrayOf("*/*"))
    }

    /** Android 13+: la notifica dei caricamenti chiede il permesso, una volta, al primo caricamento. */
    fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("notifAsked", false)) return
        prefs.edit().putBoolean("notifAsked", true).apply()
        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** La riga dei caricamenti sopra le linguette (spec §11). */
    private fun renderUploads() {
        val strip = findViewById<View>(R.id.upStrip)
        val s = Uploads.summaryIfReady(this) ?: return  // coda non ancora letta: la legge «coda-riprende» e poi avvisa
        val running = s.total - s.finished - s.failed > 0
        val now = System.currentTimeMillis()
        val text = findViewById<TextView>(R.id.upText)
        val meta = findViewById<TextView>(R.id.upMeta)
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.upBar)
        val icon = findViewById<ImageView>(R.id.upIcon)
        when {
            running -> {
                strip.isVisible = true
                strip.setBackgroundColor(ContextCompat.getColor(this, R.color.acetate_raised))
                icon.setImageResource(R.drawable.ic_upload)
                text.text = getString(R.string.up_strip_running, (s.finished + s.failed + 1).coerceAtMost(s.total), s.total)
                val cur = s.running
                val pct = if (cur != null && cur.size > 0) (cur.sent * 100 / cur.size).toInt() else 0
                // con migliaia di file piccoli la percentuale del file in corso era sempre «100 %»: conta cosa manca
                meta.text = Sizes.short(s.bytesLeft) + " left"
                bar.isVisible = true
                bar.setProgressCompat(if (s.total > 0) ((s.finished + s.failed) * 100 + pct) / s.total else 0, false)
                upDoneShownUntil = now + 4000
            }
            s.failed > 0 -> {
                strip.isVisible = true
                strip.setBackgroundColor(ContextCompat.getColor(this, R.color.vermilion_wash))
                icon.setImageResource(R.drawable.ic_retry)
                text.text = resources.getQuantityString(R.plurals.up_failed, s.failed, s.failed)
                meta.text = getString(R.string.jobs_retry)
                bar.isVisible = false
            }
            now < upDoneShownUntil && s.total > 0 -> {
                strip.isVisible = true
                strip.setBackgroundColor(ContextCompat.getColor(this, R.color.acetate_raised))
                icon.setImageResource(R.drawable.ic_cloud_done)
                text.text = getString(R.string.up_strip_done, s.finished - s.skipped, s.skipped)
                meta.text = ""
                bar.isVisible = false
                main.postDelayed({ if (!isDestroyed) renderUploads() }, upDoneShownUntil - now + 50)
                cloud.uploadsChanged()
            }
            else -> strip.isVisible = false
        }
    }

    // ---------------------------------------------------------------- il manuale: linguette e lastra

    /** Una linguetta di sezione: tinta piena, angoli alti tagliati, il foro quando è scelta. */
    private fun sectionTab(c: Cat): Chip = Chip(this).apply {
        id = View.generateViewId()
        tag = c.name
        val hue = Sections.hue(context, c)
        val ink = Sections.onHue(context, hue)
        isCheckable = true
        isChecked = c == cat
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = dp(TAB_H).toFloat()
        shapeAppearanceModel = ShapeAppearanceModel.builder(context, R.style.Shape_Viewer_Tab, 0).build()
        chipBackgroundColor = Sections.tint(hue)
        chipStrokeWidth = 0f
        rippleColor = Sections.tint(if (ink == ContextCompat.getColor(context, R.color.on_hue_dark)) 0x29000000 else 0x33FFFFFF)
        setTextAppearance(R.style.TextAppearance_Viewer_Tab)
        isAllCaps = false // le maiuscole le mettiamo noi: textAllCaps butterebbe via il conteggio in mono
        setTextColor(ink)
        checkedIcon = ContextCompat.getDrawable(context, R.drawable.punch_hole)
        checkedIconTint = null
        isCheckedIconVisible = true
        // un posto vuoto della misura del foro anche da non scelta: scegliendola non si allarga e le altre stanno ferme
        chipIcon = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        isChipIconVisible = true
        chipIconSize = dp(10).toFloat()
        iconStartPadding = dp(2).toFloat()
        chipStartPadding = dp(8).toFloat()
        chipEndPadding = dp(10).toFloat()
        textStartPadding = dp(6).toFloat()
        textEndPadding = dp(2).toFloat()
        elevation = 0f
        stateListAnimator = null
        text = tabLabel(c, null)
        contentDescription = c.label
        translationY = if (c == cat) 0f else dp(TAB_DROP).toFloat()
    }

    /** «PDF 5»: nome della sezione in Archivo condensato, quanti file in mono. */
    private fun tabLabel(c: Cat, n: Int?): CharSequence {
        val name = c.label.uppercase(Locale.UK)
        if (n == null) return name
        val num = String.format(Locale.UK, "%,d", n)
        val s = SpannableString("$name  $num")
        monoFace?.let { s.setSpan(FaceSpan(it, 0.92f), name.length + 2, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return s
    }

    /**
     * La sezione scelta diventa la lastra: la linguetta sale, le altre restano dietro, e la lastra
     * prende la tinta. Niente curve: due scatti in 90 ms, come un foglio che gira sui fori
     * (con «Rimuovi animazioni» di Android il cambio è istantaneo).
     */
    private fun paintBoard(animate: Boolean) {
        val hue = Sections.hue(this, cat)
        val ink = Sections.onHue(this, hue)
        boardAnim?.cancel()
        val from = (libBoard.background as? android.graphics.drawable.ColorDrawable)?.color ?: hue
        for ((k, chip) in chipOf) {
            val target = if (k == cat) 0f else dp(TAB_DROP).toFloat()
            if (animate) chip.animate().translationY(target).setDuration(HINGE_MS).setInterpolator(HINGE).start()
            else chip.translationY = target
        }
        if (!animate || from == hue) { libBoard.setBackgroundColor(hue); return }
        boardAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, hue).apply {
            duration = HINGE_MS
            interpolator = TimeInterpolator { t -> if (t < 0.5f) 0f else 1f }
            addUpdateListener { libBoard.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
        // toccare una linguetta non fa più scorrere la fila verso di lei;
        // la fila si sposta solo strisciando sul foglio (TabSwipe) e all'apertura
        if (placeTabs.isNotEmpty()) paintPlaces()
    }

    /**
     * La linguetta scelta resta in vista e nessuna resta tagliata a metà parola: a sinistra si mostra
     * intera anche quella prima, a destra quella dopo (se ci stanno).
     */
    private fun revealTab() {
        val p = chips.parent as? View ?: return
        val c = chipOf[cat] ?: return
        val i = chips.indexOfChild(c)
        val prev = if (i > 0) chips.getChildAt(i - 1) else null
        val next = if (i < chips.childCount - 1) chips.getChildAt(i + 1) else null
        val pad = dp(8)
        val left = maxOf(0, (prev ?: c).left - pad)
        val right = (next ?: c).right + pad - p.width
        val x = p.scrollX
        when {
            left < x -> (p as? android.widget.HorizontalScrollView)?.smoothScrollTo(maxOf(0, left), 0) ?: p.scrollTo(maxOf(0, left), 0)
            right > x -> (p as? android.widget.HorizontalScrollView)?.smoothScrollTo(minOf(right, maxOf(0, left)), 0) ?: p.scrollTo(minOf(right, maxOf(0, left)), 0)
        }
    }

    /** Righe del foglio: un filo da 1 dp fra le voci, dal nome in poi (il bollino resta libero). */
    private inner class LeafLines : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply { color = ContextCompat.getColor(this@MainActivity, R.color.seam) }
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val left = dp(70).toFloat()
            val right = parent.width.toFloat()
            val h = maxOf(1f, resources.displayMetrics.density)
            if (docCols > 0) return // nella griglia niente righe: i riquadri bastano
            for (i in 0 until parent.childCount - 1) {
                val v = parent.getChildAt(i)
                val y = v.bottom + v.translationY
                c.drawRect(left, y - h, right, y, paint)
            }
        }
    }

    private class FaceSpan(private val face: Typeface, private val scale: Float) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) { tp.typeface = face; tp.textSize *= scale }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_H = 46 // come album (PhotosPane) e briciole del Cloud: stessa linguetta, stessa altezza
        private const val TAB_DROP = 6
        private const val HINGE_MS = 90L
        private val HINGE = TimeInterpolator { t -> if (t < 0.5f) 0.5f else 1f }
        const val EXTRA_PLACE = "place"
        const val PLACE_PHOTOS = "photos"
        const val PLACE_DOCS = "docs"
        const val PLACE_FOLDERS = "folders"
        /** le linguette in basso, in quest'ordine */
        val PLACES get() = listOf(PLACE_PHOTOS, PLACE_DOCS, PLACE_FOLDERS, PLACE_CLOUD)
        const val PLACE_CLOUD = "cloud"

    }
}
