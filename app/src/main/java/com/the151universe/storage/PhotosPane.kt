package com.the151universe.storage

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.tabs.TabLayout
import java.util.Calendar
import java.util.Locale

/**
 * Foto (spec §4): tutte le foto e i video del telefono, a mesi, in griglia. Tutto | Foto | Video,
 * linguette degli album; tenere premuto = selezione (Elimina, Condividi, Carica nel cloud).
 */
class PhotosPane(act: MainActivity) : Pane(act) {
    private companion object {
        const val FAV = "\u2605"
        val CLOUD = Gallery.CLOUD_ALBUM
        const val ORDER_KEY = "phOrder"
        /** «Page 2» prima di «Page 10»: le cifre si confrontano come numeri (le pagine di un manga). */
        val NATURAL = Comparator<String> { a, b ->
            val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]; val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) (x.trimStart('0').length - y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                        else x.compareTo(y)
                if (c != 0) return@Comparator c
            }
            ra.size - rb.size
        }
    }
    private fun isCloudAlbum(k: String) = k.startsWith(CLOUD)
    /** La sottocartella di una voce dentro il suo album del cloud ("" = la cartella tenuta stessa). */
    private fun folderOf(m: MediaItem): String {
        val root = m.album.removePrefix(CLOUD)
        val dir = m.cloud?.path?.substringBeforeLast('/', "") ?: ""
        return if (root.isEmpty()) dir else dir.removePrefix(root).trim('/')
    }
    private val MESI = ShotDate.MONTHS_EN
    override val root: View = act.findViewById(R.id.placePhotos)
    private val grid: RecyclerView = act.findViewById(R.id.phGrid)
    private val status: TextView = act.findViewById(R.id.phStatus)
    private val empty: TextView = act.findViewById(R.id.phEmpty)
    private val albums: ChipGroup = act.findViewById(R.id.phAlbums)
    override val sheet: View get() = grid
    private val kindTabs: TabLayout = act.findViewById(R.id.phKind)
    private val board: View = act.findViewById(R.id.phBoard)
    private val prefs = act.getSharedPreferences("settings", 0)
    /** Via «All / Photos / Videos»: si vede sempre tutto. */
    private val kind = 0
    private var album = prefs.getString("phAlbum", "") ?: ""
    private var sort = Order.get(act, "photos", Order.NEWEST) // 0 dal più nuovo, 1 dal più vecchio, 3 dal più grande (niente nome)
    private var query = ""
    override val searchHint = R.string.search_photos
    private val selected = LinkedHashMap<Long, MediaItem>()
    /** Album che l'utente ha tolto da Media (tenendo premuta la linguetta). */
    private val hidden: MutableSet<String> get() = prefs.getStringSet("phHidden", emptySet())!!.toMutableSet()
    /** Le linguette degli album si spostano trascinandole; l'ordine sta in [ORDER_KEY]. */
    private val drag = TabDrag(albums, fixed = 1) { order ->
        TabDrag.save(prefs, ORDER_KEY, order.drop(1))
        albumKeys = order // già a schermo così: niente da rifare, solo i sotto-colori
        checkAlbum()
    }
    private val adapter = GridAdapter()
    /** Tieni premuto e trascina: si accendono tutte le foto fra la prima e il dito. */
    private val dragSel = DragSelect(grid, { adapter.itemAt(it) is MediaItem },
        { p -> (adapter.itemAt(p) as? MediaItem)?.let { selected.containsKey(it.id) } == true },
        { p, on -> (adapter.itemAt(p) as? MediaItem)?.let { if (on) selected[it.id] = it else selected.remove(it.id) } },
        { adapter.refreshMarks(); act.selectionChanged() })
    /** La vista: 0 elenco, 2/3/4 per riga; come Cloud e Telefono. */
    private var cols = MediaGrid.cols(act, "photos", 4)
    private lateinit var bar: StatusBar.Buttons
    private var shown: List<MediaItem> = emptyList()
    private val listener: () -> Unit = { act.runOnUiThread { if (!act.isDestroyed) render() } }
    private val upListener: () -> Unit = { act.runOnUiThread { if (!act.isDestroyed) adapter.refreshMarks() } }
    /** Finito un allineamento delle cartelle «sempre sul telefono», Media rilegge le copie. */
    private val offListener: () -> Unit = { if (!Offline.running) Gallery.refreshOffline(act) }

    override fun boardColor() = ContextCompat.getColor(act, R.color.place_photo)
    override val menuRes = R.menu.photos

    override fun onMenu(id: Int): Boolean {
        if (id != R.id.ph_hidden) return false
        val h = hidden.sorted()
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle(R.string.albums_hidden)
        if (h.isEmpty()) b.setMessage(R.string.albums_hidden_none).setPositiveButton(R.string.ok, null)
        else {
            val on = BooleanArray(h.size)
            b.setMultiChoiceItems(h.map { albumLabel(it) }.toTypedArray(), on) { _, i, c -> on[i] = c }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.albums_show_again) { _, _ ->
                    prefs.edit().putStringSet("phHidden", h.filterIndexed { i, _ -> !on[i] }.toSet()).apply()
                    albumKeys = emptyList(); render()
                }
        }
        b.show()
        return true
    }

    /**: l'album di una cartella tenuta sul telefono si toglie da qui (prima solo «nascondi»). */
    private fun removeFromPhone(key: String) {
        val root = key.removePrefix(CLOUD)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
            .setTitle(act.getString(R.string.offline_unpin_root, albumLabel(key)))
            .setMessage(R.string.offline_unpin_root_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.offline_unpin) { _, _ ->
                Offline.unpinPaths(act, setOf(root))
                if (album == key) { album = ""; prefs.edit().putString("phAlbum", "").apply() }
                albumKeys = emptyList(); clearSelection(); Gallery.refreshOffline(act); render(scrollTop = true)
                Ask.toast(act, act.getString(R.string.offline_removed))
            }.show()
    }

    private fun hideAlbum(key: String) {
        if (isCloudAlbum(key)) { removeFromPhone(key); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle(act.getString(R.string.album_hide, albumLabel(key)))
            .setMessage(R.string.album_hide_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.album_hide_ok) { _, _ ->
                prefs.edit().putStringSet("phHidden", hidden + key).apply()
                if (album == key) { album = ""; prefs.edit().putString("phAlbum", "").apply() }
                albumKeys = emptyList(); clearSelection(); render(scrollTop = true)
            }.show()
    }

    init {
        kindTabs.isVisible = false
        TabSwipe.attach(grid, albums) // strisciando si passa da un album all'altro
        // schedario: le linguette degli album stanno sul colore di Media, quella scelta colora il foglio sotto
        act.findViewById<View>(R.id.phTools).setBackgroundColor(boardColor())
        val glm = GridLayoutManager(act, maxOf(1, cols))
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.isHeader(position)) glm.spanCount else 1
        }
        grid.layoutManager = glm
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(40)
        bar = StatusBar.attach(status, "photos", onSort = { sort = Order.cycle(act, "photos", sort); bar.show(sort, cols); render(scrollTop = true) }, onView = {
            cols = MediaGrid.cycle(act, "photos", 4)
            (grid.layoutManager as GridLayoutManager).spanCount = maxOf(1, cols)
            bar.show(sort, cols)
            adapter.viewChanged()
        })
        bar.show(sort, cols)
    }

    override fun onShow() {
        Gallery.addListener(listener)
        Uploads.addListener(upListener)
        Offline.addListener(offListener)
        CloudState.addListener(listener)
        if (Storage.hasAccess(act)) Gallery.refresh(act, onlyIfChanged = true)
        Gallery.refreshOffline(act)
        render()
    }

    override fun onHide() {
        Gallery.removeListener(listener)
        Uploads.removeListener(upListener)
        Offline.removeListener(offListener)
        CloudState.removeListener(listener)
    }

    override fun onResume() {
        if (Storage.hasAccess(act)) Gallery.refresh(act, onlyIfChanged = true)
        // tornati dal visore dopo averlo sfogliato: la foto vista per ultima resta in vista
        Roll.takeSeen()?.let { m -> adapter.positionOf(m.id).takeIf { it >= 0 }?.let { Roll.reveal(grid, it) } }
    }

    private fun render(scrollTop: Boolean = false) {
        val hid = hidden
        val ofKind = { m: MediaItem -> kind == 0 || (kind == 1 && !m.isVideo) || (kind == 2 && m.isVideo) }
        val byKind = Gallery.items.filter { it.album !in hid && ofKind(it) }
        // le copie del cloud stanno solo nel loro album (e nei Preferiti): in «Tutti» non si mischiano col rullino
        // ...e passano dal registro del cloud: una foto eliminata o spostata lassù sparisce subito anche qui
        val fromCloud = Gallery.offline.filter { it.album !in hid && ofKind(it) && it.cloud?.let { c -> CloudState.current(c.path) == c.path.trim('/') } != false }
        // album: prima le cartelle del cloud tenute sul telefono (scelte dall'utente), poi quelle del telefono per quanti elementi hanno
        val cloudAlbums = fromCloud.map { it.album }.distinct().sortedWith(compareBy(NATURAL) { it.removePrefix(CLOUD).substringAfterLast('/') })
        val counts = byKind.groupingBy { it.album }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotEmpty() }
        if (album.isNotEmpty() && album != FAV && album !in counts && album !in cloudAlbums && Gallery.loaded) album = ""
        val favs = Favorites.all(act).toSet()
        val isFav = { m: MediaItem -> (m.cloud?.let { Favorites.cloud(it.path) } ?: Favorites.phone(m.path ?: "")) in favs }
        val hasFav = byKind.any(isFav) || fromCloud.any(isFav)
        // l'ordine lo decide l'utente trascinando; «All» resta prima, gli album nuovi vanno in fondo
        renderAlbums(listOf("") + TabDrag.arrange((if (hasFav) listOf(FAV) else emptyList()) + cloudAlbums + counts.take(30), TabDrag.saved(prefs, ORDER_KEY)) { it })
        var list = when {
            album == "" -> byKind
            album == FAV -> byKind.filter(isFav) + fromCloud.filter(isFav)
            isCloudAlbum(album) -> fromCloud.filter { it.album == album }
            else -> byKind.filter { it.album == album }
        }
        if (query.isNotEmpty()) { val q = query.lowercase(); list = list.filter { it.name.lowercase().contains(q) || it.album.lowercase().contains(q) } }
        // le cartelle del cloud seguono l'ordine scelto come il resto;
        // fino alla 0.9.19 si leggevano sempre «come un libro» (sottocartella e nome) e l'ordine non contava
        shown = when {
            sort == 1 -> list.sortedBy { it.time }
            sort == 3 -> list.sortedByDescending { it.size }
            album == FAV || isCloudAlbum(album) -> list.sortedByDescending { it.time }
            else -> list
        }
        adapter.headers = sort <= 1
        adapter.byFolder = false
        adapter.submit(shown)
        if (scrollTop) grid.scrollToPosition(0)
        val photos = shown.count { !it.isVideo }
        val videos = shown.size - photos
        status.text = buildString {
            append(listOfNotNull(if (photos > 0 || videos == 0) plural(photos, "photo", "photos") else null, if (videos > 0) plural(videos, "video", "videos") else null).joinToString(" · "))
            if (album.isNotEmpty()) append(" · ").append(when { album == FAV -> act.getString(R.string.favorites); isCloudAlbum(album) -> albumLabel(album) + " · on this phone"; else -> album })
            if (Gallery.loading) append(" · ").append(act.getString(R.string.lib_updating)) // era «aggiorno…»: l'app è in inglese
        }
        empty.isVisible = shown.isEmpty()
        empty.setText(if (!Gallery.loaded) R.string.photos_loading else if (kind == 2) R.string.videos_empty else R.string.photos_empty)
    }

    private var albumKeys: List<String> = emptyList()

    /** Dalle Impostazioni: album nascosti o rimessi → linguette rifatte; se era aperto uno nascosto, torna «All». */
    fun albumsChanged() {
        if (album in hidden) { album = ""; prefs.edit().putString("phAlbum", "").apply() }
        albumKeys = emptyList(); clearSelection(); render(scrollTop = true)
    }

    private fun renderAlbums(keys: List<String>) {
        if (keys == albumKeys) { checkAlbum(); return }
        albumKeys = keys
        albums.setOnCheckedStateChangeListener(null)
        albums.removeAllViews()
        for (k in keys) albums.addView(albumChip(k))
        albums.setOnCheckedStateChangeListener { g, ids ->
            val c = g.findViewById<Chip>(ids.firstOrNull() ?: return@setOnCheckedStateChangeListener) ?: return@setOnCheckedStateChangeListener
            val k = c.tag as String
            if (k == album) return@setOnCheckedStateChangeListener
            // il foglio entra dal lato dell'album scelto, toccando o strisciando
            val dir = g.indexOfChild(c) - (g.findViewWithTag<View>(album)?.let { g.indexOfChild(it) } ?: 0)
            album = k
            prefs.edit().putString("phAlbum", k).apply()
            clearSelection()
            checkAlbum()
            render(scrollTop = true)
            Motion.slideIn(grid, dir)
        }
        checkAlbum()
    }

    private fun checkAlbum() {
        val hue = boardColor()
        var boardHue = Shades.sub(act, hue, 0)
        for (i in 0 until albums.childCount) {
            val c = albums.getChildAt(i) as Chip
            val on = c.tag == album
            if (c.isChecked != on) c.isChecked = on
            Shades.paintTab(c, on, hue, Shades.sub(act, hue, i))
            if (on) boardHue = Shades.sub(act, hue, i)
        }
        board.setBackgroundColor(boardHue)
    }

    private fun albumLabel(key: String) = if (isCloudAlbum(key)) key.removePrefix(CLOUD).substringAfterLast('/').ifEmpty { "Cloud" } else key

    private fun albumChip(key: String) = Chip(act).apply {
        id = View.generateViewId()
        tag = key
        isCheckable = true
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = act.dp(46).toFloat()
        shapeAppearanceModel = ShapeAppearanceModel.builder(act, R.style.Shape_Viewer_Tab, 0).build()
        chipStrokeWidth = 0f
        setTextAppearance(R.style.TextAppearance_Viewer_Tab)
        checkedIcon = ContextCompat.getDrawable(act, R.drawable.punch_hole)
        checkedIconTint = null
        isCheckedIconVisible = true
        // un posto vuoto della misura del foro anche da non scelta: scegliendola non si allarga e le altre stanno ferme
        chipIcon = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        isChipIconVisible = true
        chipIconSize = act.dp(10).toFloat()
        elevation = 0f
        stateListAnimator = null
        text = (when (key) { "" -> act.getString(R.string.album_all); FAV -> act.getString(R.string.favorites); else -> albumLabel(key) }).uppercase(Locale.UK)
        // Preferiti: la stella, mai il foro; le cartelle del cloud: la nuvola, così si ricorda che sono scaricate
        if (key == FAV || isCloudAlbum(key)) {
            chipIcon = ContextCompat.getDrawable(act, if (key == FAV) R.drawable.ic_star else R.drawable.ic_cloud)
            isChipIconVisible = true; isCheckedIconVisible = false; chipIconSize = act.dp(14).toFloat()
        }
        contentDescription = text
        // tieni premuta e trascina = sposta; tieni premuta e lascia = nascondi (com'era)
        if (key.isNotEmpty()) drag.draggable(this, if (key != FAV) ({ hideAlbum(key) }) /* tieni premuta: nascondi (il «Sort album» è uscito col sorter) */ else null)
    }

    // ------------------------------------------------------------ selezione

    override val selectedCount get() = selected.size

    override fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        adapter.refreshMarks()
        act.selectionChanged()
    }

    private fun toggle(m: MediaItem) {
        if (selected.remove(m.id) == null) selected[m.id] = m
        adapter.refreshMarks()
        act.selectionChanged()
    }

    private fun toggleMonth(key: Any) {
        val items = shown.filter { if (key is String) folderOf(it) == key else monthKey(it.time) == key }
        if (items.all { selected.containsKey(it.id) }) items.forEach { selected.remove(it.id) } else items.forEach { selected[it.id] = it }
        adapter.refreshMarks()
        act.selectionChanged()
    }

    override fun onQuery(q: String) { if (q == query) return; query = q; clearSelection(); render(scrollTop = true) }

    override fun selectAll() { for (m in shown) selected[m.id] = m; adapter.refreshMarks(); act.selectionChanged() }

    override fun selectionActions(): List<SelAction> {
        if (selected.values.any { it.cloud != null }) {
            // le cartelle del cloud tenute sul telefono: le stesse azioni del visore,
            // anche con molti selezionati. Elimina = nel cestino del CLOUD, come dal visore.
            val clouds = selected.values.mapNotNull { it.cloud }
            val keys = clouds.map { Favorites.cloud(CloudState.current(it.path) ?: it.path) }
            val allFav = keys.isNotEmpty() && keys.all { Favorites.has(act, it) }
            return listOf(
                SelAction(R.string.sel_delete, R.drawable.ic_delete) { deleteCloud(clouds) },
                SelAction(R.string.sel_share, R.drawable.ic_share) {
                    act.shareUris(selected.values.mapNotNull { m -> shareUri(m)?.let { it to m.mime } })
                },
                SelAction(if (allFav) R.string.fav_remove else R.string.fav_add, R.drawable.ic_star, false) {
                    val on = Favorites.toggle(act, keys)
                    Ask.toast(act, act.getString(if (on) R.string.fav_added else R.string.fav_removed)); clearSelection()
                },
            ) + (if (isCloudAlbum(album)) listOf(SelAction(R.string.offline_unpin, R.drawable.ic_cloud, false) { removeFromPhone(album) }) else emptyList())
        }
        return phoneActions()
    }

    private val cloudIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Come «Elimina» nel visore e nel Cloud: stessa domanda, stesso cestino (30 giorni), via ovunque e dalla copia. */
    private fun deleteCloud(items: List<CloudEntry>) {
        val drive = Clouds.drive(act) ?: return
        cloudIo.execute {
            val trash = try { Trashes.forDrive(drive) } catch (x: Exception) { act.runOnUiThread { Ask.toast(act, x.message ?: "error") }; return@execute }
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                val title = if (items.size == 1) act.getString(R.string.cloud_del_title_one, items[0].name)
                    else act.resources.getQuantityString(R.plurals.cloud_del_title_many, items.size, items.size)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle(title)
                    .setMessage(R.string.cloud_del_body_own)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.del_trash_ok) { _, _ ->
                        cloudIo.execute {
                            val msg = try {
                                val real = items.map { c ->
                                    var e = if (c.ref.startsWith("offline:")) c.copy(ref = WebDav.encPath(c.path).trimEnd('/')) else c
                                    if (e.size < 0) drive.info(e.ref)?.let { r -> e = e.copy(size = r.size, mtime = r.mtime) }
                                    e
                                }
                                trash.delete(real)
                                CloudState.deleted(act, real.map { it.path }) // via ovunque, subito
                                real.forEach { if (Offline.isPinned(act, it.path)) Offline.local(act, it.path).delete() }
                                act.resources.getQuantityString(R.plurals.moved_many, real.size, real.size)
                            } catch (x: Exception) { act.getString(R.string.cloud_open_failed, x.message ?: "") }
                            act.runOnUiThread { if (!act.isDestroyed) { Ask.toast(act, msg); clearSelection(); Gallery.refreshOffline(act) } }
                        }
                    }.show()
            }
        }
    }

    private fun shareUri(m: MediaItem): android.net.Uri? = m.cloud?.let { c ->
        CloudFiles.ready(act, c)?.let { f -> try { Storage.uriFor(act, f.absolutePath) } catch (_: Exception) { null } }
    } ?: m.uri.takeIf { m.cloud == null }

    private fun phoneActions() = listOf(
        SelAction(R.string.sel_delete, R.drawable.ic_delete) {
            act.deleteFiles(selected.values.mapNotNull { m -> m.path?.let { LibFile(it, m.size, m.mtime) } })
        },
        SelAction(R.string.sel_share, R.drawable.ic_share) { act.shareUris(selected.values.map { it.uri to it.mime }) },
        SelAction(R.string.sel_upload, R.drawable.ic_upload) {
            act.uploadSheet(selected.values.map { UpSource(it.path ?: it.uri.toString(), it.name, it.size, it.mime, it.taken, it.mtime, it.path) }) { clearSelection() }
        },
    ) + act.phoneSecondary(selected.values.mapNotNull { it.path?.let { p -> java.io.File(p) } }) { clearSelection(); Gallery.refresh(act); render() }

    fun afterDelete() { Gallery.refresh(act); clearSelection() }

    // ------------------------------------------------------------ griglia

    private fun monthKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH)
    }

    private fun tap(m: MediaItem, cell: View?) {
        if (selected.isNotEmpty()) toggle(m)
        else if (m.cloud != null) { val roll = shown.filter { it.cloud != null }; act.openMedia(roll, roll.indexOf(m), MediaActivity.FROM_CLOUD, cell) }
        else { val roll = shown.filter { it.cloud == null }; act.openMedia(roll, roll.indexOf(m), MediaActivity.FROM_PHOTOS, cell) }
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        /** Righe: un Int = intestazione del mese (chiave), un MediaItem = cella. */
        private var rows: List<Any> = emptyList()
        private val monthCount = HashMap<Int, Int>()
        private val thumbPx get() = act.resources.displayMetrics.widthPixels / (if (cols == 2) 2 else 3)
        /** Chiave dell'anteprima in memoria: a 2 per riga si fa più grande (più nitida). */
        private fun tk(k: String) = if (cols == 2) "$k@2" else k
        @Suppress("NotifyDataSetChanged") fun viewChanged() = notifyDataSetChanged()

        /** Le intestazioni dei mesi solo quando l'ordine è per data. */
        var headers = true
        /** In una cartella del cloud le intestazioni sono le sottocartelle (String), non i mesi. */
        var byFolder = false
        private val folderCount = HashMap<String, Int>()
        fun isHeader(pos: Int) = rows.getOrNull(pos).let { it is Int || it is String }

        fun submit(items: List<MediaItem>) {
            val out = ArrayList<Any>(items.size + 64)
            monthCount.clear(); folderCount.clear()
            var last: Any? = null
            for (m in items) {
                val k: Any = if (byFolder) folderOf(m) else monthKey(m.time)
                if (headers && k != last) { out += k; last = k }
                if (k is Int) monthCount[k] = (monthCount[k] ?: 0) + 1 else folderCount[k as String] = (folderCount[k] ?: 0) + 1
                out += m
            }
            rows = out
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        fun refreshMarks() = notifyItemRangeChanged(0, rows.size, "marks")
        fun positionOf(id: Long) = rows.indexOfFirst { it is MediaItem && it.id == id }
        fun itemAt(pos: Int): Any? = rows.getOrNull(pos)

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is MediaItem) (if (cols == 0) 2 else 1) else 0
        override fun getItemId(position: Int): Long = (rows[position] as? MediaItem)?.id ?: -(rows[position].hashCode().toLong())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                0 -> HeaderVH(act.layoutInflater.inflate(R.layout.item_month, parent, false))
                2 -> RowVH(act.layoutInflater.inflate(R.layout.item_entry, parent, false))
                else -> CellVH(act.layoutInflater.inflate(R.layout.item_photo, parent, false))
            }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && h is CellVH) { h.marks(animate = true); return }
            if (payloads.isNotEmpty() && h is RowVH) { h.marks(); return }
            onBindViewHolder(h, pos)
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (h) {
                is HeaderVH -> {
                    val k = rows[pos]
                    h.key = k
                    if (k is String) {
                        // la sottocartella, a briciole; la cartella tenuta stessa col suo nome
                        h.month.text = (if (k.isEmpty()) albumLabel(album) else k.replace("/", " › ")).uppercase(Locale.UK)
                        h.count.text = String.format(Locale.UK, "%,d", folderCount[k] ?: 0)
                    } else {
                        k as Int
                        // i mesi in inglese, qualunque lingua del telefono
                        h.month.text = (MESI[k % 100] + " " + (k / 100)).uppercase(Locale.UK)
                        h.count.text = String.format(Locale.UK, "%,d", monthCount[k] ?: 0)
                    }
                }
                is CellVH -> {
                    val m = rows[pos] as MediaItem
                    h.item = m
                    val c = m.cloud
                    val px = thumbPx
                    if (c != null) Thumbs.load(h.thumb, tk("o" + c.path + m.size), px) {
                        val f = Offline.local(act, c.path)
                        // la copia «Sul telefono» non ha la durata di Android: la si legge dal file e si ricorda
                        if (m.isVideo && !VideoLength.known(act, c.path)) { VideoLength.ofLocal(act, c.path, f)
                            VideoLength.label(VideoLength.get(act, c.path))?.let { s -> h.duration.post { if (h.item === m) h.duration.text = s } } }
                        Thumbs.ofFile(act, f, px)
                    }
                    else Thumbs.load(h.thumb, tk("m${m.id}"), px) { Thumbs.ofMedia(act, m, px) }
                    h.duration.isVisible = m.isVideo
                    if (m.isVideo) h.duration.text = lengthOf(m) ?: "▶"
                    h.itemView.contentDescription = m.name
                    h.marks()
                }
                is RowVH -> {
                    // la vista a elenco: anteprima piccola, nome, data · dimensione (· durata)
                    val m = rows[pos] as MediaItem
                    h.item = m
                    RowPaint.paint(act, h.badge, h.icon, h.thumb, false, 0, m.name)
                    h.thumb.isVisible = true
                    val px = act.resources.displayMetrics.widthPixels / 3
                    val c = m.cloud
                    if (c != null) Thumbs.load(h.thumb, "o" + c.path + m.size, px) { Thumbs.ofFile(act, Offline.local(act, c.path), px) }
                    else Thumbs.load(h.thumb, "m${m.id}", px) { Thumbs.ofMedia(act, m, px) }
                    h.name.text = m.name
                    h.meta.text = listOfNotNull(Dates.short(m.time), Sizes.short(m.size).takeIf { m.size > 0 },
                        if (m.isVideo) lengthOf(m) else null).joinToString(" · ")
                    h.itemView.contentDescription = m.name
                    h.marks()
                }
            }
        }

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView = v.findViewById(R.id.badge)
            val icon: ImageView = v.findViewById(R.id.icon)
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val mark: ImageView = v.findViewById(R.id.mark)
            val check: com.google.android.material.checkbox.MaterialCheckBox = v.findViewById(R.id.check)
            var item: MediaItem? = null
            init {
                v.setOnClickListener { item?.let { tap(it, v) } }
                v.setOnLongClickListener { item?.let { toggle(it); dragSel.start(bindingAdapterPosition) }; true }
            }
            fun marks() {
                val m = item ?: return
                val on = selected.containsKey(m.id)
                itemView.isActivated = on
                check.isVisible = selected.isNotEmpty()
                check.isChecked = on
                val up = m.cloud == null && Uploads.isUploaded(act, m.path ?: m.uri.toString(), m.size)
                mark.isVisible = up || m.cloud != null
                mark.setImageResource(if (up) R.drawable.ic_cloud_done else R.drawable.ic_cloud)
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val month: TextView = v.findViewById(R.id.month)
            val count: TextView = v.findViewById(R.id.count)
            var key: Any = 0
            init { v.setOnClickListener { if (selected.isNotEmpty()) toggleMonth(key) } }
        }

        inner class CellVH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val duration: TextView = v.findViewById(R.id.duration)
            val cloud: FrameLayout = v.findViewById(R.id.cloudMark)
            val check: ImageView = v.findViewById(R.id.check)
            var item: MediaItem? = null

            init {
                v.setOnClickListener { item?.let { tap(it, v) } }
                v.setOnLongClickListener { item?.let { toggle(it); dragSel.start(bindingAdapterPosition) }; true }
            }

            fun marks(animate: Boolean = false) {
                val m = item ?: return
                val on = selected.containsKey(m.id)
                val sel = selected.isNotEmpty()
                if (animate) Motion.fade(check, sel) else Motion.setShown(check, sel) // le caselle compaiono sfumando
                check.setImageResource(0)
                check.background = ContextCompat.getDrawable(act, if (on) R.drawable.cell_check_on else R.drawable.cell_check_off)
                val inset = if (on) act.dp(8).toFloat() else 0f
                val s = if (on && thumb.width > 0) (thumb.width - 2 * inset) / thumb.width else 1f
                Motion.scale(thumb, s, animate) // scelta: la miniatura rientra morbida, non a scatto
                cloud.isVisible = m.cloud == null && Uploads.isUploaded(act, m.path ?: m.uri.toString(), m.size)
            }
        }
    }

    /** «1:23» / «1:02:45»: del telefono da Android, delle copie del cloud da [VideoLength]. */
    private fun lengthOf(m: MediaItem): String? =
        if (m.durationMs > 0) VideoLength.label(m.durationMs) else m.cloud?.let { VideoLength.label(VideoLength.get(act, it.path)) }

}
