package com.the151universe.storage

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cloud (spec §9). Non collegato: foglietto «Collega il tuo cloud» (WebDAV oppure Proton Drive).
 * WebDAV/SFTP: si sfoglia come Cartelle (subito, da [DirCache]; poi la rete aggiorna); un tocco scarica e apre.
 * Caricare: dalla selezione di Media/Telefono (niente più «Carica qui»). Proton: niente API pubblica,
 * si passa dall'app Proton Drive (ricerca 07/10/2026). Elimina, sposta, rinomina passano dal registro [CloudState].
 */
class CloudPane(act: MainActivity) : Pane(act) {
    override val root: View = act.findViewById(R.id.placeCloud)
    private val browse: View = act.findViewById(R.id.clBrowse)
    private val list: RecyclerView = act.findViewById(R.id.clList)
    override val sheet: View get() = list
    private val status: TextView = act.findViewById(R.id.clStatus)
    private val empty: TextView = act.findViewById(R.id.clEmpty)
    private val spin: ProgressBar = act.findViewById(R.id.clSpin)
    private val pull: androidx.swiperefreshlayout.widget.SwipeRefreshLayout = act.findViewById(R.id.clPull)
    private val crumbs: LinearLayout = act.findViewById(R.id.clCrumbs)
    private val strip: HorizontalScrollView = act.findViewById(R.id.clCrumbStrip)
    private val fnStrip: HorizontalScrollView = act.findViewById(R.id.clFnStrip)
    private val fns: LinearLayout = act.findViewById(R.id.clFns)
    private val board: View = act.findViewById(R.id.clBoard)
    private val slipScroll: ScrollView = act.findViewById(R.id.clSlipScroll)
    private val slip: LinearLayout = act.findViewById(R.id.clSlip)
    private val slipBadge: FrameLayout = act.findViewById(R.id.clSlipBadge)
    private val slipIcon: ImageView = act.findViewById(R.id.clSlipIcon)
    private val slipTitle: TextView = act.findViewById(R.id.clSlipTitle)
    private val slipBody: TextView = act.findViewById(R.id.clSlipBody)
    private val slipAddress: TextView = act.findViewById(R.id.clSlipAddress)
    private val choices: View = act.findViewById(R.id.clChoices)
    private val form: View = act.findViewById(R.id.clForm)
    private val urlIn: EditText = act.findViewById(R.id.clUrl)
    private val userIn: EditText = act.findViewById(R.id.clUser)
    private val passIn: EditText = act.findViewById(R.id.clPass)
    private val primary: Button = act.findViewById(R.id.clPrimary)
    private val secondary: Button = act.findViewById(R.id.clSecondary)
    private val io = Executors.newFixedThreadPool(2)
    /** Random, Oggi, Un giorno a caso, Doppioni si fanno dalla lista già in memoria: un filo loro, mai in coda dietro
     * a una richiesta di rete (cartella, segni) dei due di [io]. */
    private val quick = Executors.newSingleThreadExecutor()
    private val DAYNAV = "v:daynav"
    /** Tipo di riga della riga ‹ giorno ›: diverso da 0, 1 (intestazioni) e da [MediaGrid.CELL] (= 2). Nella 0.10.8 era 2:
     *  ogni foto della griglia veniva fatta come riga del giorno e l'app si chiudeva al primo tocco su Random (18/10). */
    private val DAYNAV_TYPE = MediaGrid.CELL + 1
    @Volatile private var gen = 0

    /** La pila delle cartelle aperte: (ref, percorso leggibile). */
    private val stack = ArrayList<Pair<String, String>>()
    private val cache = HashMap<String, List<CloudEntry>>()
    private val adapter = EntryAdapter()
    /** Tieni premuto e trascina per sceglierne tante. */
    private val dragSel = DragSelect(list, { adapter.at(it)?.let { e -> pickable(e) } == true },
        { p -> adapter.at(p)?.let { selected.containsKey(it.ref) } == true },
        { p, on -> adapter.at(p)?.let { if (on) selected[it.ref] = it else selected.remove(it.ref) } },
        { adapter.refresh(); act.selectionChanged() })
    private fun pickable(e: CloudEntry) = !e.ref.startsWith("offline:") && !e.ref.startsWith("v:") && !e.ref.startsWith("h:")
    private val selected = LinkedHashMap<String, CloudEntry>()
    private val downloading = HashMap<String, Int>()

    override fun boardColor() = ContextCompat.getColor(act, R.color.place_cloud)
    override val searchHint = R.string.search_cloud
    private val prefs = act.getSharedPreferences("settings", 0)
    /** La vista: 0 elenco, 2/3/4 per riga. */
    private var cols = MediaGrid.cols(act, "cloud", 3)
    private lateinit var bar: StatusBar.Buttons
    private var sort = Order.get(act, "cloud", Order.NAME)
    private var query = ""
    /** La riga di spiegazione di una voce speciale (Oggi negli anni, A caso…). */
    private val specialMeta = HashMap<String, String>()
    private var todayKey = ""
    override val menuRes get() = if (Clouds.drive(act) != null) R.menu.cloud else if (Clouds.account(act) != null) R.menu.cloud_proton else 0

    init {
        MediaGrid.layout(list, cols)
        list.adapter = adapter
        list.addItemDecoration(LeafLines(act))
        bar = StatusBar.attach(status, "cloud", ready = { browse.visibility == View.VISIBLE }, onSort = { sort = Order.cycle(act, "cloud", sort); bar.show(sort, cols); show(shownItems) }, onView = {
            cols = MediaGrid.cycle(act, "cloud", 3); MediaGrid.setCols(list, cols); bar.show(sort, cols); show(shownItems)
        })
        bar.show(sort, cols)
        slipBadge.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = act.dp(4).toFloat(); setColor(boardColor()) }
        slipIcon.imageTintList = android.content.res.ColorStateList.valueOf(Sections.onHue(act, boardColor()))
        // tirare in giù = «Refresh» del menu; la rotellina resta finché la rete non ha risposto
        Pull.style(pull)
        pull.setOnRefreshListener { refresh() }
        act.findViewById<View>(R.id.clChooseDav).setOnClickListener { showForm() }
        act.findViewById<View>(R.id.clChooseProton).setOnClickListener { connectProton() }
    }

    private val offListener: () -> Unit = { act.runOnUiThread { if (!act.isDestroyed && browse.isShown) { statusLine(); adapter.refresh() } } }

    /** Le foto di «Random» lasciate andando in un altro posto: tornando si ritrovano quelle. */
    private var keepRandom: List<CloudEntry>? = null

    override fun onShow() {
        Offline.addListener(offListener)
        CloudState.addListener(stateListener)
        seenState = CloudState.version
        renderState()
        keepRandom = null
        // i segni delle cartelle, allineati col cloud (un altro telefono può averne messi)
        Clouds.drive(act)?.let { d -> io.execute { if (Marks.sync(act, d)) act.runOnUiThread { if (!act.isDestroyed) adapter.notifyDataSetChanged() } } }
        Clouds.drive(act)?.let { Offline.sync(act, it) }
    }
    override fun onHide() {
        keepRandom = if (stack.lastOrNull()?.first == "v:random" && shownItems.isNotEmpty()) shownItems else null
        Offline.removeListener(offListener); CloudState.removeListener(stateListener)
    }

    // ------------------------------------------------------------ lo stato vero

    /** Un'eliminazione, uno spostamento, una rinomina fatti ovunque nell'app: quello che si vede si aggiorna subito. */
    private val stateListener: () -> Unit = { act.runOnUiThread { if (!act.isDestroyed) applyState() } }
    private var seenState = -1L
    private var seenAt = System.currentTimeMillis()

    /**
     * Le copie del Cloud passano dal registro ([CloudState]): le cartelle già lette perdono quello che non c'è più (e quelle
     * dove qualcosa è ARRIVATO si rileggono), le viste costruite dalla lista (Doppioni, Preferiti) si rifanno, Random e
     * Oggi tolgono o aggiornano solo le voci toccate (non si rimescolano).
     */
    private fun applyState() {
        if (seenState == CloudState.version) return
        for (d in CloudState.touchedDirs(seenAt - 1000)) { cache.remove(if (d.isEmpty()) "/" else WebDav.encPath(d)); cache.remove(WebDav.encPath(d).trimEnd('/')) }
        seenAt = System.currentTimeMillis()
        for (k in cache.keys.toList()) cache[k] = cache[k]!!.filter { real(it) == null || CloudState.current(it.path) == it.path.trim('/') }
        // sotto il visore la vista non si ridisegna: «vista» solo quando è davvero aggiornata (se no ci pensa onResume)
        if (!browse.isShown || stack.isEmpty()) return
        seenState = CloudState.version
        val top = stack.last().first
        if (top == "v:dups" || top == "v:favs" || top == "v:trash") { load(); return }
        val virtual = top.startsWith("v:")
        val now = shownItems.mapNotNull { e ->
            if (real(e) == null) return@mapNotNull e
            val cur = CloudState.current(e.path) ?: return@mapNotNull null
            when {
                cur == e.path.trim('/') -> e
                virtual -> e.copy(ref = WebDav.encPath(cur).let { if (e.isDir) it else it.trimEnd('/') }, name = cur.substringAfterLast('/'), path = cur)
                else -> null // spostato via da questa cartella
            }
        }
        if (now != shownItems) { val keep = shownItems; shownItems = now; adapter.submit(now); statusLine(); empty.isVisible = now.isEmpty() && keep.isNotEmpty() }
    }

    /** Una voce vera del cloud (non un'intestazione, una funzione, una voce del cestino). */
    private fun real(e: CloudEntry): CloudEntry? = e.takeIf { !it.ref.startsWith("v:") && !it.ref.startsWith("h:") && !it.ref.startsWith("t:") && it.path.isNotEmpty() }
    override fun destroy() { io.shutdownNow(); quick.shutdownNow() }

    /** Il cloud è cambiato da fuori (l'accesso arrivato dal PC dell'utente): si riparte dalla radice. */
    fun accountChanged() { stack.clear(); cache.clear(); DirCache.forget(act); renderState() }

    fun renderState() {
        val acc = Clouds.account(act)
        when {
            acc == null -> showConnect()
            acc.kind == Clouds.KIND_PROTON -> showProton()
            else -> {
                slipScroll.isVisible = false
                browse.isVisible = true
                if (stack.isEmpty()) stack += "/" to ""
                load()
                // la lista del telefono appena è pronta: Today, Random, Doppioni, Preferiti, ricerca
                Index.onChanged = { act.runOnUiThread { if (!act.isDestroyed && browse.isShown && stack.size == 1 && selected.isEmpty()) load() } }
                Clouds.drive(act)?.let { d -> io.execute { VideoLength.known(act, ""); val was = Index.available; Index.load(act, d)
                    act.runOnUiThread { if (!act.isDestroyed && Index.available != was && stack.size == 1) load() } } }
            }
        }
        act.placeChromeChanged()
    }

    override fun onBack(): Boolean {
        if (selected.isNotEmpty()) { clearSelection(); return true }
        if (form.isShown) { showConnect(); return true }
        if (browse.isShown && stack.size > 1) { popTo(stack.size - 1); return true }
        return false
    }

    override fun onMenu(id: Int): Boolean {
        when (id) {
            R.id.cl_refresh -> refresh()
            R.id.cl_new -> newFolder()
            R.id.cl_account -> account()
            R.id.cl_trash -> { clearSelection(); push("v:trash", act.getString(R.string.cloud_trash)) }
            R.id.cl_disconnect -> disconnect()
            R.id.cl_list -> listInfo()
            else -> return false
        }
        return true
    }

    private fun refresh() {
        if (Clouds.drive(act) == null) { pull.isRefreshing = false; return }
        cache.clear(); load(); Clouds.drive(act)?.let { d -> Offline.sync(act, d, force = true); io.execute { Index.load(act, d, force = true) } }
    }

    // ------------------------------------------------------------ navigare: il foglio entra dal lato giusto, e tornando su si ritrova il punto

    /** +1 si è entrati in una cartella, -1 si è tornati su: il prossimo [load] fa scivolare il foglio da lì. */
    private var navDir = 0
    /** Dove si era nella cartella di sopra, per profondità della pila. */
    private val scrollAt = HashMap<Int, android.os.Parcelable?>()
    private var restore: android.os.Parcelable? = null

    private fun push(ref: String, path: String) {
        scrollAt.keys.removeAll { it >= stack.size }
        scrollAt[stack.size] = list.layoutManager?.onSaveInstanceState()
        stack += ref to path
        navDir = 1
        load()
    }

    /** Torna alla profondità [size] (1 = la radice). */
    private fun popTo(size: Int) {
        if (stack.size <= size) return
        while (stack.size > size) stack.removeAt(stack.size - 1)
        restore = scrollAt.remove(size)
        scrollAt.keys.removeAll { it > size }
        navDir = -1
        load()
    }

    /** All'inizio di [load]: il foglio scivola dentro (avanti = da destra, indietro = da sinistra) e riparte dall'alto. */
    private fun navStart() {
        if (navDir == 0) return
        if (navDir > 0) list.scrollToPosition(0)
        Motion.slideIn(list, navDir)
        navDir = 0
    }

    // ------------------------------------------------------------ collegare

    private fun slipMode(title: Int, body: CharSequence, errata: Boolean) {
        browse.isVisible = false
        slipScroll.isVisible = true
        slip.background = ContextCompat.getDrawable(act, if (errata) R.drawable.slip_errata else R.drawable.slip)
        // errata (DESIGN.md, Errata Slip): il bollino diventa vermiglio con l'icona d'acetato
        (slipBadge.background as? android.graphics.drawable.GradientDrawable)?.setColor(ContextCompat.getColor(act, if (errata) R.color.vermilion else R.color.place_cloud))
        slipIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (errata) ContextCompat.getColor(act, R.color.acetate) else Sections.onHue(act, boardColor()))
        val acc = Clouds.account(act)
        slipAddress.isVisible = errata && acc != null && acc.url.isNotEmpty()
        slipAddress.text = acc?.url?.removePrefix("https://")?.removePrefix("http://").orEmpty()
        slipTitle.setText(title)
        slipBody.text = body
        choices.isVisible = false
        form.isVisible = false
        primary.isVisible = false
        secondary.isVisible = false
        primary.isEnabled = true
    }

    private fun showConnect() {
        slipMode(R.string.cloud_connect_title, act.getString(R.string.cloud_connect_body), false)
        choices.isVisible = true
        act.placeChromeChanged()
    }

    private fun showForm(error: String? = null) {
        slipMode(if (error != null) R.string.cloud_failed_title else R.string.cloud_connect_title, error ?: act.getString(R.string.cloud_connect_body), error != null)
        form.isVisible = true
        primary.isVisible = true
        primary.setText(if (error != null) R.string.cloud_retry else R.string.cloud_connect)
        primary.setOnClickListener { tryConnect() }
        secondary.isVisible = true
        secondary.setText(R.string.cloud_back)
        secondary.setOnClickListener { showConnect() }
    }

    private fun tryConnect() {
        var url = urlIn.text.toString().trim()
        val user = userIn.text.toString().trim()
        val pass = passIn.text.toString()
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) { Toast.makeText(act, R.string.cloud_fill_all, Toast.LENGTH_LONG).show(); return }
        if (!url.contains("://")) url = "https://$url"
        if (!url.startsWith("https://") && !isLocalTest(url)) { showForm(act.getString(R.string.cloud_need_https)); return }
        primary.isEnabled = false
        primary.setText(R.string.cloud_trying)
        io.execute {
            val err = WebDav(url, user, pass, "").check()
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                if (err != null) { showForm(err); return@runOnUiThread }
                Clouds.saveWebDav(act, url, user, pass)
                Uploads.forgetUploaded(act) // un cloud nuovo: i segni «nel cloud» di prima non valgono
                Offline.forget(act)
                passIn.setText("")
                stack.clear(); cache.clear()
                renderState()
            }
        }
    }

    /** Solo per le prove sull'emulatore (10.0.2.2 = il PC): la build di debug lo permette in chiaro. */
    private fun isLocalTest(url: String) = url.startsWith("http://10.0.2.2")

    private fun connectProton() {
        Clouds.saveProton(act)
        Uploads.forgetUploaded(act)
        renderState()
    }

    private fun showProton() {
        slipMode(R.string.proton_title, act.getString(R.string.proton_body), false)
        primary.isVisible = true
        primary.setText(R.string.proton_open)
        primary.setOnClickListener { act.openFromProton() }
        secondary.isVisible = true
        secondary.setText(R.string.cloud_disconnect)
        secondary.setOnClickListener { disconnect() }
        if (!protonInstalled()) slipBody.text = act.getString(R.string.proton_body) + "\n\n" + act.getString(R.string.proton_missing)
    }

    fun protonInstalled(): Boolean = try { act.packageManager.getPackageInfo(Clouds.PROTON_PACKAGE, 0); true } catch (_: Exception) { false }

    private fun account() {
        val a = Clouds.account(act) ?: return
        MaterialAlertDialogBuilder(act).setTitle(R.string.cloud_account)
            .setMessage(if (a.kind == Clouds.KIND_PROTON) a.label else "${a.label}\n${a.url}")
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun disconnect() {
        MaterialAlertDialogBuilder(act).setTitle(R.string.cloud_disconnect).setMessage(R.string.cloud_disconnect_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.cloud_disconnect) { _, _ ->
                Clouds.forget(act); Uploads.forgetUploaded(act); Offline.forget(act); DirCache.forget(act); stack.clear(); cache.clear(); renderState()
            }.show()
    }

    // ------------------------------------------------------------ sfogliare

    val currentPath: String get() = stack.lastOrNull()?.second ?: ""

    private fun load() {
        val drive = Clouds.drive(act) ?: return renderState()
        val (ref0, path) = stack.last()
        act.refreshFab()
        navStart()
        val back = restore; restore = null
        if (ref0 == "v:trash") { renderCrumbs(drive); clearSelection(); loadTrash(drive); return }
        if (ref0.startsWith("v:")) { renderCrumbs(drive); clearSelection(); loadVirtual(drive, ref0); return }
        if (query.isNotEmpty()) { renderCrumbs(drive); searchCloud(drive, query); return }
        // una cartella aperta dalla copia (senza rete) torna a essere quella vera appena c'è rete
        val ref = if (ref0.startsWith("offline:")) WebDav.encPath(path) else ref0
        renderCrumbs(drive)
        clearSelection()
        val mem = cache[ref]
        mem?.let { show(if (stack.size == 1) decorateRoot(it) else it) } ?: run { adapter.submit(emptyList()); empty.isVisible = false }
        // tornati su una cartella già letta: lo stesso punto di prima (solo se il contenuto c'è già, se no varrebbe sul vuoto)
        if (mem != null && back != null) list.layoutManager?.onRestoreInstanceState(back)
        spin.isVisible = mem == null && !pull.isRefreshing
        val g = ++gen
        io.execute {
            // subito, senza rete: l'ultima lettura di questa cartella o la lista di tutto il cloud ([DirCache])
            if (mem == null) DirCache.get(act, drive, path)?.let { pre -> act.runOnUiThread {
                if (g != gen || act.isDestroyed || cache[ref] != null) return@runOnUiThread
                cache[ref] = pre; spin.isVisible = false; show(if (stack.size == 1) decorateRoot(pre) else pre)
            } }
            val listedFrom = System.currentTimeMillis()
            val r = try { Result.success(drive.list(ref, path)) } catch (e: CloudException) { Result.failure(e) } catch (e: IOException) {
                Result.failure(CloudException(WebDav.netMessage(e)))
            }
            // quello che il server ha adesso c'è, anche se il telefono l'aveva eliminato prima ([CloudState.seenLive])
            r.onSuccess { CloudState.seenLive(act, it.map { e -> e.path }, listedFrom); DirCache.put(act, drive, path, it) }
            act.runOnUiThread {
                if (g != gen || act.isDestroyed) return@runOnUiThread
                spin.isVisible = false
                pull.isRefreshing = false
                r.onSuccess {
                    // la rete ha riletto: si ridisegna solo se qualcosa è cambiato (niente salto sotto il pollice)
                    val same = cache[ref] == it
                    cache[ref] = it
                    if (!same) show(if (stack.size == 1) decorateRoot(it) else it)
                }.onFailure { e ->
                    // senza rete: le cartelle tenute sul telefono si sfogliano dalla copia
                    Offline.listLocal(act, path)?.let { show(it); Toast.makeText(act, R.string.offline_from_copy, Toast.LENGTH_SHORT).show(); return@onFailure }
                    if (cache[ref] != null) { Toast.makeText(act, e.message, Toast.LENGTH_LONG).show(); return@onFailure }
                    val offline = e.message?.startsWith("No connection") == true
                    val refused = (e as? CloudException)?.retry == false
                    slipMode(if (offline) R.string.cloud_offline_title else if (refused) R.string.cloud_failed_title else R.string.cloud_noanswer_title,
                        if (offline) act.getString(R.string.cloud_offline_body) else e.message ?: "", !offline)
                    primary.isVisible = true
                    primary.setText(R.string.cloud_retry)
                    primary.setOnClickListener { renderState() }
                    if (!offline) { // indirizzo o password da cambiare: la via d'uscita si vede sempre
                        secondary.isVisible = true
                        secondary.setText(R.string.cloud_reconnect)
                        secondary.setOnClickListener {
                            Clouds.account(act)?.let { a -> urlIn.setText(a.url); userIn.setText(a.user) }
                            showForm()
                        }
                    }
                    act.placeChromeChanged()
                }
            }
        }
    }

    private var shownItems: List<CloudEntry> = emptyList()

    private fun show(items00: List<CloudEntry>) {
        seenState = CloudState.version
        val items0 = items00.filter { real(it) == null || it.ref.startsWith("offline:") || CloudState.current(it.path) == it.path.trim('/') }
        val virtual = stack.lastOrNull()?.first?.startsWith("v:") == true || items0.any { it.ref.startsWith("h:") }
        val specials = items0.filter { it.ref.startsWith("v:") }
        val rest = items0.filter { !it.ref.startsWith("v:") }
        val byName = compareBy<CloudEntry> { it.name.lowercase() }
        val items = if (virtual) items0 else specials + rest.sortedWith(compareByDescending<CloudEntry> { it.isDir }.then(when (sort) {
            Order.NEWEST -> compareByDescending<CloudEntry> { it.mtime }.then(byName)
            Order.OLDEST -> compareBy<CloudEntry> { it.mtime }.then(byName)
            Order.SIZE -> compareByDescending<CloudEntry> { it.size }.then(byName)
            Order.TYPE -> compareBy<CloudEntry> { Resolver.extOf(it.name) }.then(byName)
            else -> byName
        }))
        shownItems = items
        adapter.submit(items)
        renderFns()
        statusLine()
        empty.isVisible = items.isEmpty()
    }

    /** La riga sopra il foglio: quanti, e se questa cartella sta anche sul telefono (o si sta allineando). */
    private fun statusLine() {
        val items = shownItems.filter { !it.ref.startsWith("v:") && !it.ref.startsWith("h:") }
        if (query.isNotEmpty()) { status.text = if (items.isEmpty()) act.getString(R.string.search_none) else plural(items.size, "result", "results"); return }
        if (stack.lastOrNull()?.first == "v:dups") {
            val d = Index.duplicates()
            status.text = plural(d.size, "group", "groups") + " · " + Sizes.short(d.sumOf { it.wasted }) + " to free" +
                Index.similar().size.let { n -> if (n > 0) " · $n similar" else "" }; return
        }
        val nd = items.count { it.isDir }
        val nf = items.size - nd
        val parts = mutableListOf<String>()
        if (nd > 0) parts += plural(nd, "folder", "folders")
        if (nf > 0 || nd == 0) parts += plural(nf, "file", "files")
        when {
            Offline.running && Offline.status.isNotEmpty() -> parts += Offline.status
            Offline.isPinned(act, currentPath) -> parts += act.getString(R.string.offline_here)
            Offline.lastError.isNotEmpty() && Offline.pins(act).isNotEmpty() -> parts += act.getString(R.string.offline_failed, Offline.lastError)
        }
        status.text = parts.joinToString(" · ")
    }

    // ------------------------------------------------------------ cestino del cloud (lo fa l'app)

    fun menuReady(menu: android.view.Menu) {}

    private fun markDirs(dirs: List<CloudEntry>) {
        val marks = Marks.all(act)
        val current = dirs.map { Marks.of(act, it.path)?.id }.distinct().singleOrNull()
        val labels = marks.map { m ->
            android.text.SpannableStringBuilder("●  ").apply {
                setSpan(android.text.style.ForegroundColorSpan(m.color), 0, 1, 0); append(m.name)
            }
        } + act.getString(R.string.mark_none)
        MaterialAlertDialogBuilder(act).setTitle(R.string.mark_as)
            .setSingleChoiceItems(labels.toTypedArray<CharSequence>(), marks.indexOfFirst { it.id == current }) { d, i ->
                Marks.set(act, dirs.map { it.path }, marks.getOrNull(i)?.id)
                d.dismiss(); clearSelection(); adapter.notifyDataSetChanged()
            }
            .setNeutralButton(R.string.mark_edit) { _, _ -> act.startActivity(android.content.Intent(act, SettingsActivity::class.java)) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun deleteSelected() {
        val items = selected.values.toList()
        val drive = Clouds.drive(act) ?: return
        io.execute {
            val trash = try { Trashes.forDrive(drive) } catch (e: Exception) { act.runOnUiThread { Ask.toast(act, e.message ?: "error") }; return@execute }
            act.runOnUiThread {
                val title = if (items.size == 1) act.getString(R.string.cloud_del_title_one, items[0].name)
                    else act.resources.getQuantityString(R.plurals.cloud_del_title_many, items.size, items.size)
                MaterialAlertDialogBuilder(act).setTitle(title)
                    .setMessage(R.string.cloud_del_body_own)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.del_trash_ok) { _, _ ->
                        cloudOp(removed = items.map { it.ref }.toSet()) {
                            trash.delete(items)
                            CloudState.deleted(act, items.map { it.path }) // via ovunque: Doppioni, Random, Oggi, Preferiti, cartelle
                            act.resources.getQuantityString(R.plurals.moved_many, items.size, items.size)
                        }
                    }.show()
            }
        }
    }

    private var trashItems: Map<String, TrashItem> = emptyMap()

    private fun loadTrash(drive: CloudDrive) {
        spin.isVisible = !pull.isRefreshing
        val g = ++gen
        io.execute {
            val res = try { Result.success(Trashes.forDrive(drive).list()) } catch (e: Exception) { Result.failure(e) }
            act.runOnUiThread {
                if (g != gen || act.isDestroyed) return@runOnUiThread
                spin.isVisible = false
                pull.isRefreshing = false
                res.onSuccess { l ->
                    trashItems = l.associateBy { "t:" + it.id }
                    val note = header(act.getString(R.string.cloud_trash_note_own) +
                        if (l.isEmpty()) "" else "\n" + plural(l.size, "item", "items") + (l.sumOf { it.size.coerceAtLeast(0) }.takeIf { it > 0 }?.let { " · " + Sizes.short(it) } ?: ""), -1)
                    val emptyRow = if (l.isEmpty()) emptyList() else listOf(special("v:emptytrash", R.string.trash_empty_all, act.getString(R.string.trash_empty_all_meta)))
                    show(listOf(note) + emptyRow + l.map { t ->
                        CloudEntry("t:" + t.id, t.name, t.isDir, if (t.isDir) -1 else t.size, t.deletedAt, null, t.origRel).also {
                            specialMeta[it.ref] = act.getString(R.string.trash_deleted_on, Dates.short(t.deletedAt), t.origRel.substringBeforeLast('/', ""))
                        }
                    })
                    if (l.isEmpty()) { empty.setText(R.string.trash_empty); empty.isVisible = true }
                }.onFailure { e -> show(emptyList()); empty.text = e.message; empty.isVisible = true }
            }
        }
    }

    /** «Empty trash»: tutto il cestino del cloud per sempre, dopo una domanda. */
    private fun emptyTrash() {
        val drive = Clouds.drive(act) ?: return
        val items = trashItems.values.toList()
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(act).setTitle(act.resources.getQuantityString(R.plurals.trash_empty_items, items.size, items.size))
            .setMessage(R.string.cloud_purge_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.trash_empty_all) { _, _ ->
                cloudOp { val trash = Trashes.forDrive(drive); items.forEach { trash.purge(it) }; act.getString(R.string.trash_emptied) }
            }.show()
    }

    private fun trashAction(restore: Boolean) {
        val drive = Clouds.drive(act) ?: return
        val items = selected.values.mapNotNull { trashItems[it.ref] }
        val run = {
            cloudOp {
                val trash = Trashes.forDrive(drive)
                items.forEach { if (restore) { val back = trash.restore(it); CloudState.revived(act, listOf(back, it.origRel)) } else trash.purge(it) }
                act.getString(if (restore) R.string.restored else R.string.deleted)
            }
        }
        if (restore) run() else MaterialAlertDialogBuilder(act).setTitle(R.string.cloud_purge_title).setMessage(R.string.cloud_purge_body)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.cloud_purge) { _, _ -> run() }.show()
    }

    // ------------------------------------------------------------ navigazione intelligente e funzioni della lista

    private fun special(ref: String, label: Int, meta: String): CloudEntry {
        specialMeta[ref] = meta
        return CloudEntry(ref, act.getString(label), true, -1, 0, null, act.getString(label))
    }

    /** Com'è la lista del cloud fatta dal telefono. Si legge e basta. */
    private fun listInfo() {
        val d = Clouds.drive(act) ?: return
        val m = CloudList.meta(act, d)
        val n = { k: String -> String.format(java.util.Locale.UK, "%,d", m?.optLong(k) ?: 0) }
        val msg = if (m == null || !m.has("builtAt")) "This phone hasn't made its list yet: it does it when the app opens." else buildString {
            append("Made by this phone ").append(Dates.withTime(m.optLong("builtAt"))).append(".\n")
            append(n("media")).append(" photos and videos for Today · ").append(n("files")).append(" files in ").append(n("dirs")).append(" folders.\n")
            if (m.has("dupsAt")) append(plural(m.optInt("dupGroups"), "group", "groups")).append(" of duplicates").append(if (m.optBoolean("dupExact")) " (checked by the cloud, exact)" else " (start and end compared)").append(".\n")
            append("In use now: ").append(if (Index.source == "phone") "this phone's list" else "none").append(".\n")
            if (lastOpen.isNotEmpty()) append("\nLast opened · ").append(lastOpen).append(".\n")
            append("\n").append(VideoLength.summary(act)).append(".\n")
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle(R.string.cloud_list).setMessage(msg).setPositiveButton(R.string.ok, null).show()
    }

    /**
     * Alla radice: in cima i Preferiti, sotto le cartelle. Le funzioni (Random, Un giorno a caso, Oggi negli
     * anni, Doppioni) non sono più righe come cartelle: stanno nella riga di bottoni sopra il foglio ([renderFns]).
     */
    private fun decorateRoot(items: List<CloudEntry>): List<CloudEntry> {
        val mine = Favorites.all(act).count { it.startsWith("cloud:") }
        // il cestino sempre in vista, non solo nel menu
        val trash = special("v:trash", R.string.cloud_trash, act.getString(R.string.trash_row_meta))
        if (mine > 0)
            return listOf(special("v:favs", R.string.v_favs, plural(mine, "item", "items")), trash) + items
        return listOf(trash) + items
    }

    /** La riga delle funzioni: solo alla radice, senza ricerca, e solo se c'è la lista del cloud. */
    private fun renderFns() {
        val on = stack.size == 1 && query.isEmpty() && Index.available
        fnStrip.isVisible = on
        if (!on) return
        val want = listOfNotNull("v:random" to R.string.v_random, "v:randomday" to R.string.v_random_day, "v:today" to R.string.v_today,
            // dalla scheda della lista, non dalle liste (qui siamo sul thread principale)
            ("v:dups" to R.string.v_dups).takeIf { Index.hasDups() })
        if (fns.childCount == want.size && (0 until fns.childCount).all { fns.getChildAt(it).tag == want[it].first }) return
        fns.removeAllViews()
        for ((ref, label) in want) fns.addView(fnChip(ref, label))
        paintFns(Shades.depth(boardColor(), 0))
    }

    private fun fnChip(ref: String, label: Int) = com.google.android.material.chip.Chip(act).apply {
        tag = ref
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = act.dp(46).toFloat()
        setTextAppearance(R.style.TextAppearance_Viewer_Tab)
        chipStrokeWidth = 0f
        // il colore del Cloud: si vede che sono bottoni (funzioni), non cartelle né linguette
        chipBackgroundColor = android.content.res.ColorStateList.valueOf(boardColor())
        Sections.onHue(act, boardColor()).let { ink -> setTextColor(ink); chipIconTint = android.content.res.ColorStateList.valueOf(ink) }
        elevation = 0f
        stateListAnimator = null
        text = act.getString(label).uppercase(Locale.UK)
        chipIcon = ContextCompat.getDrawable(act, when (ref) { "v:random" -> R.drawable.ic_shuffle; "v:dups" -> R.drawable.ic_copy; else -> R.drawable.ic_today })
        isChipIconVisible = true
        chipIconSize = act.dp(16).toFloat()
        contentDescription = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = act.dp(3) }
        setOnClickListener { if (stack.size == 1) { todayKey = ""; push(ref, act.getString(label)) } }
    }

    /** Intestazione di gruppo: a sinistra il titolo (Archivo), a destra la misura (Mono), come «OTTOBRE 2026 … 17». */
    private fun header(label: String, n: Int, count: String = "") = CloudEntry("h:$n:$label", label, false, -1, 0, count.ifEmpty { null }, "")

    private fun loadVirtual(drive: CloudDrive, ref: String) {
        val kept = keepRandom.takeIf { ref == "v:random" }
        keepRandom = null
        if (kept != null) { ++gen; spin.isVisible = false; show(kept); return }
        spin.isVisible = !pull.isRefreshing
        val g = ++gen
        val t0 = android.os.SystemClock.elapsedRealtime()
        (if (ref == "v:favs") io else quick).execute {
            val t1 = android.os.SystemClock.elapsedRealtime()
            val items: List<CloudEntry> = try {
                when {
                    // «Random» pesca prima fra le foto che hanno già l'anteprima sul telefono: istantaneo anche a giro in corso
                    ref == "v:random" -> listOf(special("v:again", R.string.v_again, "")) + Index.random(16) { CloudThumbs.onPhoneAt(act, it) }.map { Index.entry(act, drive, it.path) }
                    ref == "v:today" || ref == "v:randomday" -> {
                        if (todayKey.isEmpty()) todayKey = if (ref == "v:today") Index.mmdd(java.util.Calendar.getInstance()) else Index.randomDay() ?: ""
                        val days = Index.onThisDay(todayKey)
                        // una riga sola in cima: ‹ giorno ›
                        listOf(CloudEntry(DAYNAV, dayLabel(todayKey), false, -1, 0, null, "")) +
                            days.flatMapIndexed { i, (y, l) -> listOf(header(y, i, plural(l.size, "item", "items"))) + l.map { Index.entry(act, drive, it.path) } }
                    }
                    ref == "v:dups" -> listOf(header(act.getString(R.string.dups_note, try { Dates.short(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(Index.dupsBuiltAt)!!.time) } catch (_: Exception) { Index.dupsBuiltAt }), -1)) +
                        Index.duplicates().take(200).flatMapIndexed { i, gr ->
                            listOf(header("Same file · " + plural(gr.files.size, "copy", "copies"), i, Sizes.short(gr.size) + " each")) +
                                gr.files.map { Index.entry(act, drive, it) }
                        } +
                        // le foto quasi uguali vicine, la migliore (la più grande) per prima
                        Index.similar().take(300).flatMapIndexed { i, gr ->
                            listOf(header("Similar · " + plural(gr.files.size, "photo", "photos"), 10_000 + i, gr.day.let { d -> try { Dates.short(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(d)!!.time) } catch (_: Exception) { d } })) +
                                gr.files.map { (p, _) -> Index.entry(act, drive, p) }
                        }
                    ref == "v:favs" -> {
                        val mine = Favorites.all(act).filter { it.startsWith("cloud:") }.map { it.removePrefix("cloud:") }
                            .mapNotNull { k -> CloudState.current(k.trimEnd('/'))?.let { if (k.endsWith("/")) "$it/" else it } }
                        (mine.map { k -> val dir = k.endsWith("/"); val p = k.trimEnd('/')
                            (if (dir) null else Index.entryFull(act, drive, p)) ?: CloudEntry(WebDav.encPath(p).let { if (dir) it else it.trimEnd('/') }, p.substringAfterLast('/'), dir, -1, 0, null, p) }).distinctBy { it.path }
                            // un preferito spostato o cancellato nel cloud non si mostra: si aprirebbe solo «Can't show this photo»
                            .filter { e -> try { drive.info(e.ref) != null } catch (_: Exception) { true } }
                    }
                    else -> emptyList()
                }
            } catch (e: Exception) { emptyList() }
            act.runOnUiThread {
                if (g != gen || act.isDestroyed) return@runOnUiThread
                spin.isVisible = false
                pull.isRefreshing = false
                show(items)
                trace(ref, items, t0, t1)
                if (ref == "v:random") popIn() // «Random» e «More at random»: le foto entrano una dopo l'altra
            }
        }
    }

    /**
     * Il tracciante delle aperture: attesa in coda, lista fatta, e quante anteprime
     * erano già sul telefono (quelle che mancano arrivano dalla rete, una per una). Si legge in ⋮ › About the cloud list.
     */
    private fun trace(ref: String, items: List<CloudEntry>, t0: Long, t1: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val media = items.filter { !it.ref.startsWith("v:") && !it.ref.startsWith("h:") && Thumbs.isMedia(it.name) }.take(48) // le prime sullo schermo
        val ready = media.count { CloudThumbs.fileFor(act, it.path).isFile }
        val name = when (ref) { "v:random" -> "Random"; "v:today" -> "On this day"; "v:randomday" -> "Random day"; "v:dups" -> "Duplicates"; else -> "Favourites" }
        lastOpen = String.format(java.util.Locale.UK, "%s: waited %.2f s, list %.2f s, %d of %d previews already on the phone", name,
            (t1 - t0) / 1000.0, (now - t1) / 1000.0, ready, media.size)
        android.util.Log.i("151Storage", "apertura $lastOpen")
    }
    private var lastOpen = ""

    /** Entrata leggera della griglia (dissolvenza + piccolo ingrandimento, 16 celle in ~0,4 s): non ferma niente. */
    private fun popIn() {
        val a = android.view.animation.AnimationSet(true).apply {
            addAnimation(android.view.animation.AlphaAnimation(0f, 1f))
            addAnimation(android.view.animation.ScaleAnimation(0.92f, 1f, 0.92f, 1f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f))
            duration = 220; interpolator = android.view.animation.DecelerateInterpolator()
        }
        list.layoutAnimation = android.view.animation.LayoutAnimationController(a, 0.08f)
        list.scheduleLayoutAnimation()
    }

    private fun dayLabel(key: String): String {
        val p = key.split('-'); if (p.size != 2) return ""
        val mesi = ShotDate.MONTHS_EN
        return "${p[1].toInt()} ${mesi[p[0].toInt() - 1]}".uppercase(java.util.Locale.UK)
    }

    private fun shiftDay(by: Int) {
        val p = todayKey.split('-'); if (p.size != 2) return
        val c = java.util.Calendar.getInstance().apply { set(2024, p[0].toInt() - 1, p[1].toInt()); add(java.util.Calendar.DAY_OF_MONTH, by) }
        todayKey = Index.mmdd(c)
        load()
    }

    /** Ricerca: con la lista del telefono in tutto il cloud, altrimenti dentro la cartella aperta e sotto (fino a 300 cartelle). */
    private fun searchCloud(drive: CloudDrive, q: String) {
        spin.isVisible = !pull.isRefreshing
        val g = ++gen
        val (ref0, path) = stack.last()
        io.execute {
            val res = (if (Index.available) Index.search(act, drive, q) else null) ?: run {
                val words = q.lowercase().split(' ').filter { it.isNotBlank() }
                val out = ArrayList<CloudEntry>()
                val todo = ArrayDeque(listOf(if (ref0.startsWith("v:")) "/" to "" else ref0 to path))
                var n = 0
                while (todo.isNotEmpty() && n < 300 && out.size < 500 && q == query) {
                    val (r, p) = todo.removeFirst(); n++
                    val kids = try { drive.list(r, p) } catch (_: Exception) { continue }
                    for (k in kids) { if (k.isDir) todo.addLast(k.ref to k.path); if (words.all { k.name.lowercase().contains(it) }) out += k }
                }
                out
            }
            act.runOnUiThread { if (g == gen && !act.isDestroyed) { spin.isVisible = false; pull.isRefreshing = false; show(res) } }
        }
    }

    override fun onQuery(q: String) { if (q == query) return; query = q; clearSelection(); load() }

    override fun selectAll() { shownItems.filter { !it.ref.startsWith("v:") && !it.ref.startsWith("h:") }.forEach { selected[it.ref] = it }; adapter.refresh(); act.selectionChanged() }

    /**
     * Dove sei, a linguette: la radice e ogni cartella
     * aperta sono linguette sul blu del Cloud; l'ultima è la scelta e si fonde col foglio, che schiarisce scendendo.
     * Un tocco su una linguetta di prima torna lì.
     */
    private fun renderCrumbs(drive: CloudDrive) {
        val first = drive.label.substringBefore(" · ").ifEmpty { "Cloud" }
        val labels = listOf(first) + stack.drop(1).map { it.second.substringAfterLast('/').ifEmpty { it.second } }
        val boardHue = Crumbs.tabs(crumbs, strip, labels, R.drawable.ic_cloud, boardColor()) { i -> popTo(i + 1) }
        board.setBackgroundColor(boardHue)
        fnStrip.setBackgroundColor(boardHue)
        paintFns(boardHue)
    }

    /** I bottoni delle funzioni sul foglio: lo stesso velo dei bottoni di ordine e vista. */
    private fun paintFns(boardHue: Int) {
        val ink = Sections.onHue(act, boardHue)
        // il velo è trasparente: un Chip lo stende sopra il SUO fondo (colorSurface, scuro di notte), non sopra il foglio.
        // Di notte usciva #34322E con l'inchiostro scuro sopra, 1,1:1 → il velo si fonde qui col foglio, pieno
        val veil = ContextCompat.getColor(act, R.color.soft_button)
        val veilDown = ContextCompat.getColor(act, R.color.soft_button_pressed)
        val opaque = boardHue or 0xFF000000.toInt()
        val bg = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(androidx.core.graphics.ColorUtils.compositeColors(veilDown, opaque), androidx.core.graphics.ColorUtils.compositeColors(veil, opaque)),
        )
        for (k in 0 until fns.childCount) (fns.getChildAt(k) as? com.google.android.material.chip.Chip)?.apply {
            chipBackgroundColor = bg
            chipStrokeWidth = act.dp(1).toFloat()
            chipStrokeColor = ContextCompat.getColorStateList(act, R.color.soft_button_edge)
            setTextColor(ink); chipIconTint = android.content.res.ColorStateList.valueOf(ink)
        }
    }

    private fun newFolder() {
        val drive = Clouds.drive(act) ?: return
        val input = EditText(act).apply { setSingleLine(); hint = act.getString(R.string.new_folder_name) }
        val box = FrameLayout(act).apply { setPadding(act.dp(22), act.dp(8), act.dp(22), 0); addView(input) }
        MaterialAlertDialogBuilder(act).setTitle(R.string.new_folder).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().replace('/', '-')
                if (name.isEmpty() || name.startsWith('.')) return@setPositiveButton
                val path = (currentPath.trim('/') + "/" + name).trim('/')
                io.execute {
                    val err = try { drive.resolveDir(path, create = true); null } catch (e: Exception) { e.message ?: act.getString(R.string.folder_not_created) }
                    act.runOnUiThread { if (err != null) Toast.makeText(act, err, Toast.LENGTH_LONG).show(); cache.remove(stack.last().first); load() }
                }
            }.show()
    }


    // ------------------------------------------------------------ aprire, scaricare

    private fun localFor(e: CloudEntry): File = CloudFiles.localFor(act, e)

    private fun open(e: CloudEntry, cell: View? = null) {
        when {
            e.ref.startsWith("h:") -> return
            e.ref.startsWith("t:") -> { toggle(e); return } // nel cestino un tocco sceglie: Ripristina / Elimina per sempre
            e.ref == DAYNAV -> return // si tocca sulle frecce
            e.ref == "v:again" -> { load(); return }
            e.ref == "v:emptytrash" -> { emptyTrash(); return }
            e.ref.startsWith("v:") -> { todayKey = ""; push(e.ref, e.name); return }
        }
        if (e.isDir) { push(e.ref, e.path); return }
        val drive = Clouds.drive(act) ?: return
        val mime = Thumbs.mimeOf(e.name)
        if (Thumbs.isMedia(e.name)) {
            // foto e video: lo stesso visore di Media, e si scorre fra tutte le foto e i video della cartella
            val roll = shownItems.filter { !it.isDir && !it.ref.startsWith("v:") && !it.ref.startsWith("h:") && Thumbs.isMedia(it.name) }
            act.openMedia(roll.map { MediaItem.ofCloud(it) }, roll.indexOfFirst { it.ref == e.ref }.coerceAtLeast(0), MediaActivity.FROM_CLOUD, cell)
            return
        }
        Offline.copyOf(act, e)?.let { local ->
            openLocal(local, mime)
            return
        }
        if (e.ref.startsWith("offline:")) { Toast.makeText(act, R.string.cloud_offline_title, Toast.LENGTH_SHORT).show(); return }
        val f = localFor(e)
        if (f.isFile && f.length() == e.size) { openLocal(f, mime); return }
        if (downloading.containsKey(e.ref)) return
        downloading[e.ref] = 0
        adapter.refresh()
        io.execute {
            val err = download(drive, e, f) { pct -> act.runOnUiThread { downloading[e.ref] = pct; adapter.refresh() } }
            act.runOnUiThread {
                downloading.remove(e.ref)
                adapter.refresh()
                if (err != null) Toast.makeText(act, act.getString(R.string.cloud_open_failed, err), Toast.LENGTH_LONG).show()
                else if (root.isShown) openLocal(f, mime)
            }
        }
    }

    /** Un download si guadagna il nome: `.part`, poi dimensione controllata, poi rename. */
    private fun download(drive: CloudDrive, e: CloudEntry, f: File, progress: (Int) -> Unit): String? {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        return try {
            var got = 0L
            var lastPct = -1
            drive.open(e).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val pct = if (e.size > 0) (got * 100 / e.size).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
            if (e.size >= 0 && got != e.size) { part.delete(); return "arrived incomplete" }
            // la data del file del cloud, mai «oggi»; dalle liste (Random, Oggi) la voce non ce l'ha: si chiede
            val t = if (e.mtime > 0) e.mtime else try { drive.info(e.ref)?.mtime ?: 0L } catch (_: Exception) { 0L }
            if (t > 0) part.setLastModified(t)
            if (!part.renameTo(f)) { part.delete(); return "can't be written" }
            null
        } catch (x: CloudException) { part.delete(); x.message } catch (x: IOException) { part.delete(); WebDav.netMessage(x) }
    }

    private fun openLocal(f: File, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(act, Storage.authority(act), f)
        if (mime.startsWith("image/") && Thumbs.isMedia(f.name)) act.openMedia(listOf(MediaItem.ofFile(act, f, uri)), 0, MediaActivity.FROM_CLOUD)
        else act.openUri(uri)
    }

    /** Un'operazione sul cloud fuori dal thread principale, poi un messaggio e la cartella riletta. */
    /**
     * Un'operazione sul cloud fuori dal thread principale, poi un messaggio. Con [removed] (cancellare) e tutto andato
     * bene: spariscono SOLO quei file, il resto resta com'è e dov'è — prima si ricaricava la vista e «Random» pescava 16
     * foto nuove. Altrimenti la vista si ricarica.
     */
    /** Tornati dal visore (che vive sopra il Cloud): quello che lì si è eliminato, spostato o rinominato. */
    override fun onResume() {
        applyState()
        adapter.notifyDataSetChanged() // tornati da Settings: segni rinominati o ricolorati
        // tornati dal visore dopo averlo sfogliato: la foto vista per ultima resta in vista nel foglio
        Roll.takeSeen()?.cloud?.let { c -> adapter.positionOf(c.ref).takeIf { it >= 0 }?.let { Roll.reveal(list, it) } }
    }

    private fun cloudOp(removed: Set<String>? = null, work: () -> String) {
        clearSelection()
        io.execute {
            var ok = true
            val msg = try { work() } catch (e: CloudException) { ok = false; e.message ?: "error" } catch (e: IOException) { ok = false; WebDav.netMessage(e) }
            act.runOnUiThread {
                Ask.toast(act, msg)
                if (ok && removed != null) {
                    shownItems = shownItems.filter { it.ref !in removed }
                    for (k in cache.keys.toList()) cache[k] = cache[k]!!.filter { it.ref !in removed }
                    adapter.remove(removed)
                } else { cache.clear(); load() }
            }
        }
    }

    private fun uriOf(f: File) = androidx.core.content.FileProvider.getUriForFile(act, Storage.authority(act), f)

    /** I file scelti, sul telefono (copia «sul telefono» o scaricati in cache), poi [then]. */
    private fun withLocal(items: List<CloudEntry>, then: (List<File>) -> Unit) {
        val drive = Clouds.drive(act) ?: return
        clearSelection()
        Ask.toast(act, act.getString(R.string.cloud_downloading, 0))
        io.execute {
            val out = items.mapNotNull { e -> Offline.copyOf(act, e) ?: localFor(e).takeIf { (it.isFile && it.length() == e.size) || download(drive, e, it) {} == null } }
            act.runOnUiThread { if (out.isNotEmpty()) then(out) }
        }
    }

    private fun downloadTo(items: List<CloudEntry>, dir: File) {
        val drive = Clouds.drive(act) ?: return
        clearSelection()
        if (items.isNotEmpty()) Ask.toast(act, act.resources.getQuantityString(R.plurals.cloud_copying, items.size, items.size))
        io.execute {
            var ok = 0; var err: String? = null
            for (e in items) {
                val t = File(dir, freeName(e.name) { File(dir, it).exists() })
                val r = download(drive, e, t) {}
                if (r == null) { ok++; android.media.MediaScannerConnection.scanFile(act, arrayOf(t.path), null, null) } else err = r
            }
            act.runOnUiThread { Ask.toast(act, err?.let { act.getString(R.string.cloud_open_failed, it) } ?: act.getString(R.string.done_copied, ok)) }
        }
    }

    /** «Scarica»: i file scelti in Download/151 Storage, sul telefono. */
    private fun saveSelected() {
        val drive = Clouds.drive(act) ?: return
        val items = selected.values.filter { !it.isDir }
        clearSelection()
        @Suppress("DEPRECATION") val dest = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "151 Storage")
        if (items.isEmpty()) return
        Ask.toast(act, act.resources.getQuantityString(R.plurals.cloud_saving, items.size, items.size)) // subito un segno
        io.execute {
            var ok = 0
            var lastErr: String? = null
            for (e in items) {
                val target = File(dest, freeName(e.name) { File(dest, it).exists() })
                val err = download(drive, e, target) {}
                if (err == null) { ok++; android.media.MediaScannerConnection.scanFile(act, arrayOf(target.path), null, null) } else lastErr = err
            }
            act.runOnUiThread {
                Toast.makeText(act, if (lastErr == null) act.getString(R.string.cloud_saved) else act.getString(R.string.cloud_open_failed, lastErr), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ anteprime: le fa il telefono (CloudThumbs)

    private fun thumb(drive: CloudDrive, e: CloudEntry): Bitmap? = CloudThumbs.get(act, drive, e)

    // ------------------------------------------------------------ selezione

    override val selectedCount get() = selected.size
    override fun clearSelection() { if (selected.isEmpty()) return; selected.clear(); adapter.refresh(); act.selectionChanged() }
    private fun toggle(e: CloudEntry) { if (selected.remove(e.ref) == null) selected[e.ref] = e; adapter.refresh(); act.selectionChanged() }

    /** Cartelle e file si scelgono insieme: le cartelle si tengono sul telefono, i file si scaricano. */
    override fun selectionActions(): List<SelAction> {
        val out = ArrayList<SelAction>()
        if (stack.lastOrNull()?.first == "v:trash") {
            out += SelAction(R.string.trash_restore, R.drawable.ic_retry) { trashAction(restore = true) }
            out += SelAction(R.string.cloud_purge, R.drawable.ic_delete) { trashAction(restore = false) }
            return out
        }
        out += SelAction(R.string.sel_delete, R.drawable.ic_delete) { deleteSelected() }
        val dirs = selected.values.filter { it.isDir }
        val files = selected.values.filter { !it.isDir }
        if (dirs.isNotEmpty()) {
            // anche una cartella DENTRO una tenuta è già sul telefono → «Remove from phone» (della tenuta)
            if (dirs.all { Offline.isPinned(act, it.path) }) out += SelAction(R.string.offline_unpin, R.drawable.ic_cloud) {
                val roots = dirs.flatMap { Offline.rootsOf(act, it.path) }.toSet()
                val sel = dirs.map { it.path.trim('/') }.toSet()
                val go = { Offline.unpinPaths(act, roots); clearSelection(); statusLine(); Ask.toast(act, act.getString(R.string.offline_removed)) }
                if (roots == sel) go()
                else MaterialAlertDialogBuilder(act)
                    .setTitle(act.getString(R.string.offline_unpin_root, roots.joinToString(", ") { it.substringAfterLast('/').ifEmpty { "Cloud" } }))
                    .setMessage(R.string.offline_unpin_root_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.offline_unpin) { _, _ -> go() }.show()
            } else out += SelAction(R.string.offline_pin, R.drawable.ic_phone_storage) {
                act.askNotifications(); Offline.pin(act, dirs.filter { !Offline.isPinRoot(act, it.path) }); clearSelection()
                Toast.makeText(act, R.string.offline_pinned, Toast.LENGTH_LONG).show()
            }
        }
        // solo cartelle: «Mark as…», segni scelti in Settings
        if (files.isEmpty() && dirs.isNotEmpty()) out += SelAction(R.string.mark_as, R.drawable.ic_label, false) { markDirs(dirs) }
        if (files.isNotEmpty()) out += SelAction(R.string.sel_download, R.drawable.ic_download) { saveSelected() }
        if (files.isNotEmpty()) out += SelAction(R.string.sel_share, R.drawable.ic_share) { withLocal(files) { fs -> act.shareUris(fs.map { uriOf(it) to Thumbs.mimeOf(it.name) }) } }
        val all = selected.values.toList()
        val one = all.singleOrNull()
        val drive = Clouds.drive(act) ?: return out
        if (one != null) out += SelAction(R.string.rename, R.drawable.ic_edit, false) {
            Ask.name(act, R.string.rename, one.name) { n -> cloudOp { val nr = drive.rename(one, n); CloudState.moved(act, one.path, android.net.Uri.decode(nr).trim('/')); Favorites.rename(act, Favorites.cloud(one.path, one.isDir), Favorites.cloud(one.path.substringBeforeLast('/', "").let { if (it.isEmpty()) n else "$it/$n" }, one.isDir)); act.getString(R.string.done_renamed) } }
        }
        for (copy in listOf(false, true)) out += SelAction(if (copy) R.string.copy_to else R.string.move, R.drawable.ic_folder, false) {
            FolderPicker.show(act, drive, currentPath, if (copy) R.string.copy_here else R.string.move_here) { dest ->
                cloudOp { all.forEach { val nr = drive.transfer(it, dest, copy); if (!copy) CloudState.moved(act, it.path, android.net.Uri.decode(nr).trim('/')) }; act.getString(if (copy) R.string.done_copied else R.string.done_moved, all.size) }
            }
        }
        if (files.isNotEmpty()) out += SelAction(R.string.to_phone, R.drawable.ic_download, false) {
            PhonePicker.show(act, R.string.copy_title, R.string.download_here, null) { dir -> downloadTo(files, dir) }
        }
        if (one != null) out += SelAction(R.string.file_info, R.drawable.ic_info, false) {
            Ask.info(act, one.name, listOfNotNull(
                if (one.isDir) null else listOfNotNull(if (one.size >= 0) Sizes.short(one.size) else null, PhoneOps.kindLabel(one.name)).joinToString(" · "),
                if (one.mtime > 0) "Modified: " + Dates.withTime(one.mtime) else null,
                "In the cloud › " + one.path.substringBeforeLast('/', "").replace("/", " › ").ifEmpty { "Cloud" },
                if (Offline.isPinned(act, one.path)) act.getString(R.string.offline_here) else null,
            ).joinToString("\n"))
        }
        if (one != null && !one.isDir) out += SelAction(R.string.open_with, R.drawable.ic_open_with, false) {
            withLocal(listOf(one)) { fs -> Ask.openWith(act, uriOf(fs[0]), Thumbs.mimeOf(one.name), one.name) }
        }
        val allFav = all.isNotEmpty() && all.all { Favorites.has(act, Favorites.cloud(it.path, it.isDir)) }
        out += SelAction(if (allFav) R.string.fav_remove else R.string.fav_add, R.drawable.ic_star, false) {
            val on = Favorites.toggle(act, all.map { Favorites.cloud(it.path, it.isDir) }); clearSelection()
            Ask.toast(act, act.getString(if (on) R.string.fav_added else R.string.fav_removed)); cache.clear(); load()
        }
        return out
    }

    /** Dopo un caricamento nella cartella aperta: la si rilegge. */
    fun uploadsChanged() { if (root.isShown && browse.isShown) { cache.remove(stack.lastOrNull()?.first); load() } }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {
        private var rows: List<CloudEntry> = emptyList()
        private val px = act.dp(88)
        var grid = false
        fun submit(l: List<CloudEntry>) {
            rows = l
            grid = cols > 0 && query.isEmpty() && stack.lastOrNull()?.first != "v:trash"
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }
        fun refresh() = notifyItemRangeChanged(0, rows.size)
        fun positionOf(ref: String) = rows.indexOfFirst { it.ref == ref }
        fun at(pos: Int): CloudEntry? = rows.getOrNull(pos)
        /** Toglie solo queste voci, con l'animazione di Android (le vicine scorrono al posto lasciato libero). */
        fun remove(refs: Set<String>) {
            for (i in rows.indices.reversed()) if (rows[i].ref in refs) { rows = rows.toMutableList().also { it.removeAt(i) }; notifyItemRemoved(i) }
        }
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = rows[position].let {
            if (it.ref.startsWith("h:")) 1 else if (it.ref == DAYNAV) DAYNAV_TYPE else if (grid && !it.isDir && !it.ref.startsWith("v:") && Thumbs.isMedia(it.name)) MediaGrid.CELL else 0
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(act.layoutInflater.inflate(when (viewType) { 1 -> R.layout.item_month; DAYNAV_TYPE -> R.layout.item_daynav; MediaGrid.CELL -> R.layout.item_photo; else -> R.layout.item_entry }, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = rows[pos]
            h.entry = e
            if (getItemViewType(pos) == MediaGrid.CELL) {
                val fav = Favorites.has(act, Favorites.cloud(e.path, e.isDir))
                val onPhone = Offline.copyOf(act, e) != null
                val d = Clouds.drive(act)
                val cellPx = act.resources.displayMetrics.widthPixels / maxOf(2, cols)
                val video = Thumbs.mimeOf(e.name).startsWith("video/")
                MediaGrid.bind(h.itemView, "c" + e.path + e.size + (if (cols == 2) "@2" else ""), cellPx, video,
                    if (video) VideoLength.label(VideoLength.get(act, e.path)) else null,
                    if (fav) R.drawable.ic_star else if (onPhone) R.drawable.ic_phone_storage else 0,
                    selected.isNotEmpty(), selected.containsKey(e.ref), quick = { CloudThumbs.onPhone(act, e) },
                    durationLater = { VideoLength.label(VideoLength.get(act, e.path)) }) { d?.let { thumb(it, e) } }
                h.itemView.contentDescription = e.name
                return
            }
            if (e.ref == DAYNAV) {
                h.itemView.findViewById<TextView>(R.id.dayName).text = e.name
                h.itemView.findViewById<View>(R.id.dayPrev).setOnClickListener { shiftDay(-1) }
                h.itemView.findViewById<View>(R.id.dayNext).setOnClickListener { shiftDay(1) }
                return
            }
            if (e.ref.startsWith("h:")) {
                val t = h.itemView.findViewById<TextView>(R.id.month)
                val note = e.ref.startsWith("h:-1:") // la spiegazione dei Doppioni: una frase, non un'intestazione
                t.text = e.name; t.isSingleLine = !note; t.maxLines = if (note) 5 else 1; t.isAllCaps = !note
                t.setTextAppearance(if (note) R.style.TextAppearance_Viewer_Prose else R.style.TextAppearance_Viewer_Tab)
                h.itemView.layoutParams.height = if (note) ViewGroup.LayoutParams.WRAP_CONTENT else act.dp(44)
                h.itemView.setPadding(act.dp(12), if (note) act.dp(10) else 0, act.dp(12), if (note) act.dp(10) else 0)
                h.itemView.findViewById<TextView>(R.id.count).text = e.mime ?: ""; return
            }
            h.name.text = e.name
            val icon = when {
                e.ref == "v:today" -> R.drawable.ic_today; e.ref == "v:random" || e.ref == "v:again" -> R.drawable.ic_shuffle
                e.ref == "v:randomday" -> R.drawable.ic_today; e.ref == "v:dups" -> R.drawable.ic_copy; e.ref == "v:favs" -> R.drawable.ic_star
                e.ref == "v:trash" || e.ref == "v:emptytrash" -> R.drawable.ic_delete
                else -> 0
            }
            RowPaint.paint(act, h.badge, h.icon, h.thumb, e.isDir, icon, e.name)
            // il segno della cartella: il bollino prende il suo colore, il nome del segno apre la riga sotto
            val mark = if (e.isDir && icon == 0 && real(e) != null) Marks.of(act, e.path) else null
            h.badge.backgroundTintList = mark?.let { android.content.res.ColorStateList.valueOf(it.color) }
            if (mark != null) h.icon.imageTintList = android.content.res.ColorStateList.valueOf(Sections.onHue(act, mark.color))
            val date = if (e.mtime > 0) Dates.short(e.mtime) else ""
            val pct = downloading[e.ref]
            h.meta.text = when {
                specialMeta[e.ref] != null -> specialMeta[e.ref]
                e.ref.startsWith("v:") -> ""
                pct != null -> act.getString(R.string.cloud_downloading, pct)
                (query.isNotEmpty() || stack.lastOrNull()?.first?.startsWith("v:") == true) && e.path.contains('/') -> "/" + e.path.substringBeforeLast('/')
                e.isDir -> listOf(mark?.name?.uppercase(java.util.Locale.UK) ?: "", date).filter { it.isNotEmpty() }.joinToString(" · ")
                else -> listOf(Sizes.short(e.size), date).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            h.meta.isVisible = !h.meta.text.isNullOrEmpty()
            h.progress.isVisible = pct != null
            if (pct != null) h.progress.setProgressCompat(pct, false)
            if (!e.isDir) Clouds.drive(act)?.let { d ->
                if (Thumbs.isMedia(e.name)) { // foto E video (prima solo le foto)
                    h.thumb.isVisible = true
                    val t = if (e.ref.startsWith("t:")) trashItems[e.ref] else null
                    if (t == null) Thumbs.load(h.thumb, "c" + e.path + e.size, px, quick = { CloudThumbs.onPhone(act, e) }) { thumb(d, e) }
                    else {
                        // nel cestino: l'anteprima di quando la foto era al suo posto (stesso percorso) se c'è; se no si fa
                        // dal file dentro il cestino e si salva col percorso di prima, così torna buona dopo il ripristino
                        val before = CloudEntry(WebDav.encPath(t.loc).trimEnd('/'), t.name, false, t.size, 0, null, t.origRel)
                        Thumbs.load(h.thumb, "c" + t.origRel + t.size, px, quick = { CloudThumbs.onPhone(act, before) }) { thumb(d, before) }
                    }
                }
            }
            // segno «sul telefono»: la cartella tenuta, e ogni file che ha la sua copia
            val onPhone = !e.ref.startsWith("v:") && (if (e.isDir) Offline.isPinned(act, e.path) else Offline.copyOf(act, e) != null)
            val fav = !e.ref.startsWith("v:") && Favorites.has(act, Favorites.cloud(e.path, e.isDir))
            h.mark.isVisible = onPhone || fav
            h.mark.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_phone_storage)
            val on = selected.containsKey(e.ref)
            h.itemView.isActivated = on
            h.check.isVisible = selected.isNotEmpty() && !e.ref.startsWith("v:")
            h.check.isChecked = on
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView by lazy { v.findViewById(R.id.badge) }
            val icon: ImageView by lazy { v.findViewById(R.id.icon) }
            val thumb: ImageView by lazy { v.findViewById(R.id.thumb) }
            val name: TextView by lazy { v.findViewById(R.id.name) }
            val meta: TextView by lazy { v.findViewById(R.id.meta) }
            val mark: ImageView by lazy { v.findViewById(R.id.mark) }
            val progress: LinearProgressIndicator by lazy { v.findViewById(R.id.progress) }
            val check: MaterialCheckBox by lazy { v.findViewById(R.id.check) }
            var entry: CloudEntry? = null
            init {
                v.setOnClickListener { val e = entry ?: return@setOnClickListener; if (selected.isNotEmpty()) { if (!e.ref.startsWith("v:") && !e.ref.startsWith("h:")) toggle(e) } else open(e, v) }
                v.setOnLongClickListener { entry?.takeIf { pickable(it) }?.let { toggle(it); dragSel.start(bindingAdapterPosition) }; true }
                // nelle celle della griglia «check» è un'immagine, non una casella: il tocco lo gestisce la cella
                if (v.findViewById<View?>(R.id.check) is MaterialCheckBox) check.setOnClickListener { entry?.let { toggle(it) } }
            }
        }
    }
}
