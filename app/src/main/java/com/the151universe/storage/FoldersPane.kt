package com.the151universe.storage

import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cartelle (spec §7): il telefono come un gestore di file. In cima i Posti (memoria interna, SD,
 * Download, DCIM, Documents), poi cartelle e file con le briciole. Un tocco su un file lo apre:
 * documenti col lettore, foto e video col visore. Si leggono solo nomi e misure: niente si sposta.
 */
class FoldersPane(act: MainActivity) : Pane(act) {
    override val root: View = act.findViewById(R.id.placeFolders)
    private val list: RecyclerView = act.findViewById(R.id.fdList)
    private val status: TextView = act.findViewById(R.id.fdStatus)
    private val empty: TextView = act.findViewById(R.id.fdEmpty)
    private val crumbs: LinearLayout = act.findViewById(R.id.fdCrumbs)
    private val strip: HorizontalScrollView = act.findViewById(R.id.fdCrumbStrip)
    private val board: View = act.findViewById(R.id.fdBoard)
    private val prefs = act.getSharedPreferences("settings", 0)
    /** La vista: 0 elenco, 2/3/4 per riga. */
    private var cols = MediaGrid.cols(act, "folders", 3)
    private lateinit var bar: StatusBar.Buttons
    private var sort = Order.get(act, "folders", Order.NAME)
    /** null = i Posti. */
    /** La memoria del telefono: Phone si apre lì, senza la pagina dei Posti. */
    private val home: File = Storage.roots(act).first()
    private var dir: File? = (prefs.getString("fdDir", null)?.let { File(it) }?.takeIf { it.isDirectory }) ?: home
    /** Ogni cartella già vista si mostra SUBITO com'era, poi si rilegge dietro. */
    private val shown = HashMap<String, List<Row>>()
    /** «12 items · 9 Nov» delle sottocartelle: contato dopo, dietro, e ricordato */
    private val counts = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** cartelle che contengono solo roba nel cestino, a ogni profondità: si nascondono (contato dietro, come i numeri) */
    private val trashOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /**
     * Cartelle eliminate che il disco tiene ancora perché dentro ci sono i file nel cestino di Android (30 giorni):
     * restano nascoste anche dove non si nasconderebbero da sole (sotto Download, DCIM…), e quando Android svuota il
     * cestino si tolgono davvero. Tornano se dentro torna qualcosa (Annulla, Restore).
     */
    private val gone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(prefs.getStringSet("fdGone", emptySet())!!) }
    private fun saveGone() = prefs.edit().putStringSet("fdGone", HashSet(gone)).apply()
    /** Quello che la lista non mostra: nomi col punto (il cestino compreso) e le librerie delle altre app. */
    private val hidden: (File) -> Boolean = { it.name.startsWith('.') }
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var gen = 0
    private val adapter = EntryAdapter()
    /** Tieni premuto e trascina per sceglierne tante, cartelle comprese. */
    private val dragSel = DragSelect(list, { adapter.rows.getOrNull(it)?.let { r -> pickable(r) } == true },
        { p -> adapter.rows.getOrNull(p)?.let { selected.containsKey(it.file.absolutePath) } == true },
        { p, on -> adapter.rows.getOrNull(p)?.let { if (on) selected[it.file.absolutePath] = it.file else selected.remove(it.file.absolutePath) } },
        { adapter.refresh(); act.selectionChanged() })
    private fun pickable(r: Row) = r.special == null && (dir != null || favView || trashView || query.isNotEmpty())
    /** true = il cestino del telefono, come una cartella. */
    private var trashView = false
    /** file nel cestino → la sua voce in MediaStore (per ripristinare) */
    private val trashUris = java.util.concurrent.ConcurrentHashMap<String, android.net.Uri>()
    /** cartelle scelte per l'eliminazione: svuotate, si tolgono anche loro */
    private var deletingDirs: List<File> = emptyList()
    private val selected = LinkedHashMap<String, File>()
    private val positions = HashMap<String, Int>()
    private var query = ""
    /** true = la vista «Preferiti» (file e cartelle con la stella). */
    private var favView = false
    override val searchHint = R.string.search_folder
    private var seenPhoneChanges = Uploads.phoneChanges
    private val upListener: () -> Unit = { act.runOnUiThread {
        if (act.isDestroyed) return@runOnUiThread
        if (Uploads.phoneChanges != seenPhoneChanges) { seenPhoneChanges = Uploads.phoneChanges; load(keepScroll = true) } else adapter.refresh()
    } }

    override fun boardColor() = ContextCompat.getColor(act, R.color.place_folder)
    override val menuRes = R.menu.folders

    init {
        MediaGrid.layout(list, cols)
        list.adapter = adapter
        list.addItemDecoration(LeafLines(act))
        bar = StatusBar.attach(status, "folders", onSort = { sort = Order.cycle(act, "folders", sort); bar.show(sort, cols); load(keepScroll = true) }, onView = {
            cols = MediaGrid.cycle(act, "folders", 3); MediaGrid.setCols(list, cols); bar.show(sort, cols); load(keepScroll = true)
        })
        bar.show(sort, cols)
    }

    /** Il cestino del telefono, aperto anche da Docs (un cestino solo). */
    fun openTrash() {
        if (!Storage.hasAccess(act)) { MaterialAlertDialogBuilder(act).setTitle(R.string.trash_title).setMessage(R.string.trash_need_access).setPositiveButton(R.string.ok, null).show(); return }
        favView = false; trashView = true; clearSelection(); load()
    }

    override fun onShow() { Uploads.addListener(upListener); load() }
    override fun onHide() { Uploads.removeListener(upListener) }
    override fun onResume() { if (root.isShown) load(keepScroll = true) }
    override fun destroy() { io.shutdownNow() }

    override fun onBack(): Boolean {
        if (selected.isNotEmpty()) { clearSelection(); return true }
        if (favView) { favView = false; load(); return true }
        if (trashView) { trashView = false; clearSelection(); load(); return true }
        val d = dir ?: return false
        if (d.absolutePath == home.absolutePath) return false
        val roots = Storage.roots(act)
        go(if (roots.any { it.absolutePath == d.absolutePath }) home else d.parentFile)
        return true
    }

    override fun onMenu(id: Int): Boolean {
        when (id) {
            R.id.fd_new -> newFolder()
            else -> return false
        }
        return true
    }

    private fun go(d0: File?) {
        val d = d0 ?: home
        dir?.let { positions[it.absolutePath] = (list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition() }
        dir = d
        favView = false
        trashView = false
        prefs.edit().putString("fdDir", d?.absolutePath).apply()
        clearSelection()
        load()
    }

    private class Row(val file: File, val isDir: Boolean, val label: String, val meta: String, val icon: Int = 0, val size: Long = 0, val mtime: Long = 0, val special: String? = null)

    private fun load(keepScroll: Boolean = false) {
        val d = dir
        val g = ++gen
        renderCrumbs()
        if (io.isShutdown) return
        // subito quello che si sa già (cartella vista prima): niente schermo vuoto mentre si rilegge
        val key = listKey()
        shown[key]?.let { cached -> if (!keepScroll || adapter.rows.isEmpty()) { adapter.submit(cached); showStatus(cached, d, query); empty.isVisible = cached.isEmpty()
            if (!keepScroll) list.scrollToPosition(d?.let { positions[it.absolutePath] }?.coerceAtLeast(0) ?: 0) } }
        io.execute {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val q = query
            val rows = when {
                q.isNotEmpty() -> search(d, q)
                favView -> favorites()
                trashView -> PhoneTrashList.rows(act, trashUris).map { t ->
                    if (t.special) Row(File("/"), true, act.getString(R.string.trash_empty_all), act.getString(R.string.trash_empty_all_meta), R.drawable.ic_delete, special = "empty")
                    else Row(File(t.path), false, t.name, t.meta, size = t.size, mtime = t.mtime) }
                d == null -> listDir(home)
                else -> listDir(d)
            }
            android.util.Log.i("151Storage", "cartelle: ${d?.name ?: "posti"} ${rows.size} righe in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val hadCache = shown[key] != null
            shown[key] = rows
            act.runOnUiThread {
                if (g != gen || act.isDestroyed) return@runOnUiThread
                adapter.submit(rows)
                showStatus(rows, d, q)
                empty.isVisible = rows.isEmpty()
                if (!keepScroll && !hadCache) list.scrollToPosition(d?.let { positions[it.absolutePath] }?.coerceAtLeast(0) ?: 0)
            }
            // poi, dietro: quanti elementi ha ogni sottocartella e quali tengono solo il cestino
            if (d != null && q.isEmpty() && !favView && !trashView) fillCounts(rows, g, key)
        }
    }

    private fun listKey() = when { query.isNotEmpty() -> "?" + (dir?.absolutePath ?: "") + query; favView -> "*fav"; trashView -> "*trash"; else -> (dir ?: home).absolutePath }

    private fun showStatus(rows: List<Row>, d: File?, q: String) {
        val nd = rows.count { it.isDir && it.special == null }
        val nf = rows.count { !it.isDir && it.special == null }
        status.text = if (q.isNotEmpty()) (if (rows.isEmpty()) act.getString(R.string.search_none) else plural(rows.size, "result", "results"))
            else if (favView) act.getString(R.string.favorites).uppercase(Locale.UK)
            else if (trashView) act.getString(R.string.trash_title).uppercase(Locale.UK) + " · " + plural(nf, "file", "files") + " · " + Sizes.short(rows.sumOf { it.size })
            else (listOfNotNull(if (nd > 0) plural(nd, "folder", "folders") else null, if (nf > 0 || nd == 0) plural(nf, "file", "files") else null).joinToString(" · "))
    }

    /** I numeri delle sottocartelle, una alla volta; lo schermo si aggiorna a gruppi, mai tutto da capo. */
    private fun fillCounts(rows0: List<Row>, g: Int, key: String) {
        var rows = rows0
        var changed = false
        if (rows.any { it.special == "trash" }) {
            val m = PhoneTrashList.summary(act)
            if (m != trashMeta) { trashMeta = m; changed = true
                rows = rows.map { if (it.special == "trash") Row(it.file, true, it.label, m, it.icon, special = "trash") else it } }
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        var last = t0
        val roots = Storage.roots(act).map { it.absolutePath }
        fun flush() {
            if (!changed) return; changed = false
            val fresh = rows.filter { !(it.isDir && it.special == null && it.file.absolutePath in trashOnly) }.map { r ->
                if (r.isDir && r.special == null) counts[r.file.absolutePath]?.let { Row(r.file, true, r.label, it, r.icon, r.size, r.mtime) } ?: r else r }
            shown[key] = fresh
            act.runOnUiThread { if (g == gen && !act.isDestroyed) { adapter.submit(fresh); showStatus(fresh, dir, query) } }
        }
        for (r in rows) {
            if (g != gen) return
            if (!r.isDir || r.special != null) continue
            val k = r.file.absolutePath
            // i file nel cestino e le cartelle che tengono solo loro non contano («2 items» e dentro niente)
            val look = Trashed.look(r.file, Trashed.Budget(), hidden)
            val label = itemsLabel(look?.visible ?: 0) + " · " + date(r.mtime)
            // Download, DCIM… (subito sotto la memoria) non spariscono mai da sole: si mostrano «0 items»
            val hide = look != null && look.ghost && (r.file.parent ?: "") !in roots
            if (counts[k] != label) { counts[k] = label; changed = true }
            if (hide != (k in trashOnly)) { if (hide) trashOnly += k else trashOnly -= k; changed = true }
            if (android.os.SystemClock.elapsedRealtime() - last > 150) { flush(); last = android.os.SystemClock.elapsedRealtime() }
        }
        flush()
        android.util.Log.i("151Storage", "cartelle: numeri di ${rows.count { it.isDir && it.special == null }} sottocartelle in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        // le cartelle nascoste perché tenevano solo il cestino: se qualcosa è tornato (Restore), si rimostrano
        val here = dir?.absolutePath
        val back = trashOnly.filter { File(it).parent == here && !Trashed.ghost(File(it), Trashed.Budget(), hidden) }
        // le cartelle eliminate (tutte, anche dentro cartelle nascoste): Android ha svuotato il cestino → si tolgono
        // davvero; dentro è tornato qualcosa (Annulla, Restore) → si rimostrano
        var goneChanged = false
        for (p in gone.toList()) {
            val f = File(p)
            if (f.exists() && Trashed.noFiles(f, Trashed.Budget(200))) FolderUpload.pruneEmpty(f)
            if (!f.exists() || (Trashed.look(f, Trashed.Budget(), hidden)?.visible ?: 0) > 0) { gone -= p; goneChanged = true }
        }
        if (goneChanged) saveGone()
        if ((back.isNotEmpty() || goneChanged) && g == gen) { trashOnly.removeAll(back.toSet()); act.runOnUiThread { if (g == gen && !act.isDestroyed) load(keepScroll = true) } }
    }

    @Volatile private var trashMeta: String? = null

    private fun itemsLabel(n: Int) = if (n == 1) "1 item" else String.format(Locale.UK, "%,d items", n)

    private fun countLabel(f: File): String = itemsLabel(Trashed.look(f, Trashed.Budget(), hidden)?.visible ?: 0) + " · " + date(f.lastModified())

    /** Non si mostra: tiene solo il cestino di Android, o l'abbiamo eliminata e il disco la tiene per il cestino. */
    private fun ghostDir(f: File) = f.absolutePath in gone || f.absolutePath in trashOnly || Trashed.ghost(f, Trashed.Budget(), hidden)

    private fun date(t: Long) = Dates.short(t)

    private fun favorites(): List<Row> = Favorites.all(act).filter { it.startsWith("phone:") }.map { File(it.removePrefix("phone:")) }
        .filter { it.exists() && !(it.isDirectory && ghostDir(it)) }.map { f ->
            if (f.isDirectory) Row(f, true, f.name, countLabel(f), mtime = f.lastModified())
            else Row(f, false, f.name, Sizes.short(f.length()) + " · " + Storage.folderLabel(f.absolutePath), size = f.length(), mtime = f.lastModified())
        }

    /** Ricerca per nome dentro la cartella aperta (o in tutto il telefono dai Posti), al massimo 500 risultati. */
    private fun search(from: File?, q: String): List<Row> {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        val out = ArrayList<Row>()
        val stack = ArrayDeque<File>()
        (from?.let { listOf(it) } ?: Storage.roots(act)).forEach { stack.addLast(it) }
        while (stack.isNotEmpty() && out.size < 500 && q == query) {
            val dir = stack.removeLast()
            for (f in dir.listFiles() ?: continue) {
                if (f.name.startsWith('.')) continue
                if (f.isDirectory) { if (!(f.name == "data" || f.name == "obb") || f.parentFile?.name != "Android") stack.addLast(f) }
                val n = f.name.lowercase()
                if (words.all { n.contains(it) } && !(f.isDirectory && ghostDir(f))) out += if (f.isDirectory) Row(f, true, f.name, Storage.folderLabel(f.absolutePath + "/x"), mtime = f.lastModified())
                else Row(f, false, f.name, Sizes.short(f.length()) + " · " + Storage.folderLabel(f.absolutePath), size = f.length(), mtime = f.lastModified())
            }
        }
        return out.sortedWith(compareBy<Row> { if (it.label.lowercase() == q.lowercase()) 0 else if (it.label.lowercase().startsWith(words[0])) 1 else 2 }.thenBy { it.file.path.length })
    }

    override fun onQuery(q: String) { if (q == query) return; query = q; clearSelection(); load() }

    override fun selectAll() { for (r in adapter.rows) if (r.special == null && (dir != null || favView || trashView || query.isNotEmpty())) selected[r.file.absolutePath] = r.file; adapter.refresh(); act.selectionChanged() }

    private fun listDir(d: File): List<Row> {
        // una lettura per figlio (tipo, misura e data insieme); i numeri delle sottocartelle arrivano dopo ([fillCounts])
        val dirs = ArrayList<Row>(); val files = ArrayList<Row>()
        for (f in d.listFiles() ?: emptyArray()) {
            if (f.name.startsWith('.') || f.absolutePath in trashOnly || f.absolutePath in gone) continue
            val a = try { java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java) } catch (_: Exception) { continue }
            val t = a.lastModifiedTime().toMillis()
            if (a.isDirectory) dirs += Row(f, true, f.name, counts[f.absolutePath] ?: date(t), mtime = t)
            else files += Row(f, false, f.name, Sizes.short(a.size()) + " · " + date(t), size = a.size(), mtime = t)
        }
        val byName = compareBy<Row> { it.label.lowercase(Locale.ROOT) }
        val cmp = when (sort) {
            Order.NEWEST -> compareByDescending<Row> { it.mtime }.then(byName)
            Order.OLDEST -> compareBy<Row> { it.mtime }.then(byName)
            Order.SIZE -> compareByDescending<Row> { it.size }.then(byName)
            Order.TYPE -> compareBy<Row> { Resolver.extOf(it.label) }.then(byName)
            else -> byName
        }
        val body = dirs.sortedWith(if (sort == Order.SIZE || sort == Order.TYPE) byName else cmp) + files.sortedWith(cmp)
        if (d.absolutePath != home.absolutePath) return body
        // in cima alla memoria del telefono: Preferiti, Cestino e la scheda SD se c'è (niente più pagina dei Posti)
        val top = ArrayList<Row>()
        val favs = favorites().size
        if (favs > 0) top += Row(File("/"), true, act.getString(R.string.favorites), plural(favs, "item", "items"), R.drawable.ic_star, special = "fav")
        // il conto del cestino (52.000 voci sul telefono dell'utente) si fa dopo, dietro: qui l'ultimo saputo
        if (Build.VERSION.SDK_INT >= 30) top += Row(File("/"), true, act.getString(R.string.trash_title), trashMeta ?: act.getString(R.string.trash_row_meta), R.drawable.ic_delete, special = "trash")
        Storage.roots(act).drop(1).forEach { sd -> top += Row(sd, true, act.getString(R.string.sd_card), "", R.drawable.ic_sd_card, special = "sd") }
        return top + body
    }

    private fun renderCrumbs() {
        // come il Cloud: linguette sul colore di Phone, il foglio schiarisce scendendo
        val hue = boardColor()
        val tap: (Int) -> Unit
        val labels: List<String>
        if (favView || trashView) {
            labels = listOf("Internal", act.getString(if (favView) R.string.favorites else R.string.trash_title))
            tap = { favView = false; trashView = false; clearSelection(); load() }
        } else {
            val d = dir ?: home
            val roots = Storage.roots(act)
            val root = roots.firstOrNull { d.absolutePath.startsWith(it.absolutePath) } ?: d
            val rel = d.absolutePath.removePrefix(root.absolutePath).trim('/')
            val segs = if (rel.isEmpty()) emptyList() else rel.split('/')
            labels = listOf(if (root == roots.firstOrNull()) "Internal" else "SD") + segs
            tap = { i -> go(if (i == 0) root else File(root, segs.take(i).joinToString("/"))) }
        }
        board.setBackgroundColor(Crumbs.tabs(crumbs, strip, labels, R.drawable.ic_folder, hue, tap))
    }

    private fun newFolder() {
        val d = dir ?: return
        val input = EditText(act).apply { setSingleLine(); hint = act.getString(R.string.new_folder_name) }
        val box = android.widget.FrameLayout(act).apply { setPadding(act.dp(22), act.dp(8), act.dp(22), 0); addView(input) }
        MaterialAlertDialogBuilder(act).setTitle(R.string.new_folder).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().replace('/', '-')
                if (name.isEmpty() || name.startsWith('.')) return@setPositiveButton
                val f = File(d, name)
                when {
                    f.exists() -> Toast.makeText(act, R.string.folder_exists, Toast.LENGTH_LONG).show()
                    !f.mkdir() -> Toast.makeText(act, R.string.folder_not_created, Toast.LENGTH_LONG).show()
                    else -> load(keepScroll = true)
                }
            }.show()
    }

    // ------------------------------------------------------------ selezione (solo file)

    override val selectedCount get() = selected.size

    override fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear(); adapter.refresh(); act.selectionChanged()
    }

    private fun toggle(f: File) {
        if (selected.remove(f.absolutePath) == null) selected[f.absolutePath] = f
        adapter.refresh(); act.selectionChanged()
    }

    override fun selectionActions(): List<SelAction> = if (trashView) listOf(
        SelAction(R.string.trash_restore, R.drawable.ic_retry) { trashRun(restore = true, selected.keys.toList()) },
        SelAction(R.string.cloud_purge, R.drawable.ic_delete) { trashRun(restore = false, selected.keys.toList()) },
    ) else listOf(
        SelAction(R.string.sel_delete, R.drawable.ic_delete) {
            // una cartella va nel cestino con tutto quello che contiene (file per file, recuperabili); i file si contano
            // dietro lo schermo, e una cartella già vuota si toglie e basta
            val picked = selected.values.toList()
            deletingDirs = picked.filter { it.isDirectory }
            // spariscono SUBITO dallo schermo; il cestino lavora dietro
            // e «Annulla» resta 8 secondi; se qualcosa non va, la rilettura della cartella li rimette
            val gone = picked.mapTo(HashSet()) { it.absolutePath }
            val left = adapter.rows.filter { it.file.absolutePath !in gone || it.special != null }
            shown[listKey()] = left
            clearSelection(); adapter.submit(left); showStatus(left, dir, query)
            Thread({
                val files = picked.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile && !Trashed.isTrashed(it.name) }.toList() else listOf(f) }
                    .map { LibFile(it.absolutePath, it.length(), it.lastModified()) }
                act.runOnUiThread {
                    if (act.isDestroyed) return@runOnUiThread
                    if (files.isEmpty()) afterDelete() else act.deleteFiles(files, deletingDirs.map { it.name }, ask = false)
                }
            }, "da-cancellare").start()
        },
        SelAction(R.string.sel_share, R.drawable.ic_share) {
            // una cartella si condivide coi file che contiene (le altre app non sanno aprire una cartella)
            act.shareUris(selected.values.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile && !it.name.startsWith('.') }.toList() else listOf(f) }
                .take(100).mapNotNull { f -> try { Storage.uriFor(act, f.absolutePath) to Thumbs.mimeOf(f.name) } catch (_: Exception) { null } })
        },
        SelAction(R.string.sel_upload, R.drawable.ic_upload) {
            // una cartella arriva nel cloud così com'è: col suo nome, le sottocartelle e tutto dentro
            // contare 18.000 file sullo schermo lo bloccava fino all'«app non risponde»:
            // si contano dietro, e il foglietto si apre quando sono pronti
            val picked = selected.values.toList()
            Toast.makeText(act, R.string.up_preparing, Toast.LENGTH_LONG).show()
            Thread({
                val (items, empty) = FolderUpload.collectSources(picked)
                act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) act.uploadSheet(items, empty) { clearSelection() } }
            }, "cartelle-da-caricare").start()
        },
    ) + act.phoneSecondary(selected.values.toList()) { clearSelection(); load(keepScroll = true) }

    fun afterDelete() {
        val dirs = deletingDirs; deletingDirs = emptyList()
        clearSelection()
        if (dirs.isEmpty()) { load(keepScroll = true); return }
        // le cartelle scelte, ormai vuote, se ne vanno anche loro (dal fondo); una che tiene solo il cestino di Android
        // (i «.trashed-», 30 giorni) per il disco non è vuota: si nasconde e si toglie quando Android lo svuota.
        // Una che tiene ancora file veri (eliminazione annullata o fallita) resta.
        Thread({
            for (d in dirs) {
                FolderUpload.pruneEmpty(d)
                if (d.exists() && (Trashed.look(d, Trashed.Budget(), hidden)?.visible ?: 1) == 0) gone += d.absolutePath
            }
            saveGone()
            act.runOnUiThread { if (!act.isDestroyed) load(keepScroll = true) }
        }, "cartelle-vuote").start()
    }

    /** Ripristina o elimina per sempre i file scelti nel cestino del telefono. */
    private fun trashRun(restore: Boolean, paths: List<String>) {
        if (paths.isEmpty()) return
        val go = {
            clearSelection()
            Toast.makeText(act, act.getString(if (restore) R.string.trash_restoring else R.string.trash_deleting, paths.size), Toast.LENGTH_SHORT).show()
            Thread({
                val (ok, bad) = PhoneTrashList.apply(act, paths, trashUris, restore)
                act.runOnUiThread {
                    if (act.isDestroyed) return@runOnUiThread
                    Toast.makeText(act, if (bad == 0) act.getString(if (restore) R.string.restored else R.string.trash_emptied) else act.getString(R.string.trash_some_failed, ok, bad), Toast.LENGTH_LONG).show()
                    if (restore) Library.refresh(act, force = true)
                    load(keepScroll = true)
                }
            }, "cestino").start()
        }
        if (restore) { go(); return }
        MaterialAlertDialogBuilder(act).setTitle(act.resources.getQuantityString(R.plurals.trash_empty_title, paths.size, paths.size))
            .setMessage(R.string.cloud_purge_body).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.cloud_purge) { _, _ -> go() }.show()
    }

    private fun open(r: Row) {
        if (r.special == "fav") { favView = true; clearSelection(); load(); return }
        if (r.special == "sd") { go(r.file); return }
        if (r.special == "trash") {
            if (!Storage.hasAccess(act)) { MaterialAlertDialogBuilder(act).setTitle(R.string.trash_title).setMessage(R.string.trash_need_access).setPositiveButton(R.string.ok, null).show(); return }
            trashView = true; clearSelection(); load(); return
        }
        if (r.special == "empty") { trashRun(restore = false, adapter.rows.filter { it.special == null }.map { it.file.absolutePath }); return }
        if (trashView) { toggle(r.file); return } // nel cestino un tocco sceglie: Restore / Delete forever
        if (r.isDir) { go(r.file); return }
        if (Thumbs.isMedia(r.file.name)) {
            // il rullino è la cartella: si sfoglia avanti e indietro fra le sue foto e i suoi video
            val media = adapter.rows.filter { !it.isDir && Thumbs.isMedia(it.file.name) }.map { MediaItem.ofFile(act, it.file) }
            if (media.isEmpty()) act.openMedia(listOf(MediaItem.ofFile(act, r.file)), 0, MediaActivity.FROM_FOLDERS)
            else act.openMedia(media, media.indexOfFirst { it.path == r.file.absolutePath }.coerceAtLeast(0), MediaActivity.FROM_FOLDERS)
        } else act.openPath(r.file.absolutePath)
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {
        var rows: List<Row> = emptyList()
        private val thumbPx = act.dp(88)

        var grid = false
        fun submit(l: List<Row>) {
            rows = l
            grid = cols > 0 && dir != null && query.isEmpty()
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }
        override fun getItemViewType(position: Int) = rows[position].let { if (grid && !it.isDir && it.special == null && Thumbs.isMedia(it.file.name)) MediaGrid.CELL else 0 }
        fun refresh() = notifyItemRangeChanged(0, rows.size)
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(act.layoutInflater.inflate(if (viewType == MediaGrid.CELL) R.layout.item_photo else R.layout.item_entry, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.row = r
            if (getItemViewType(pos) == MediaGrid.CELL) {
                val fav = Favorites.has(act, Favorites.phone(r.file.absolutePath))
                val cellPx = act.resources.displayMetrics.widthPixels / maxOf(2, cols)
                MediaGrid.bind(h.itemView, "f" + r.file.absolutePath + r.mtime + (if (cols == 2) "@2" else ""), cellPx,
                    Thumbs.mimeOf(r.file.name).startsWith("video/"), null,
                    if (fav) R.drawable.ic_star else if (Uploads.isUploaded(act, r.file.absolutePath, r.size)) R.drawable.ic_cloud_done else 0,
                    selected.isNotEmpty(), selected.containsKey(r.file.absolutePath)) { Thumbs.ofFile(act, r.file, cellPx) }
                h.itemView.contentDescription = r.label
                return
            }
            h.name.text = r.label
            h.meta.text = r.meta
            h.meta.isVisible = r.meta.isNotEmpty()
            RowPaint.paint(act, h.badge, h.icon, h.thumb, r.isDir, r.icon, r.file.name)
            if (!r.isDir && Thumbs.isMedia(r.file.name)) {
                h.thumb.isVisible = true
                Thumbs.load(h.thumb, "f" + r.file.absolutePath + r.mtime, thumbPx) { Thumbs.ofFile(act, r.file, thumbPx) }
            }
            val fav = r.special == null && Favorites.has(act, Favorites.phone(r.file.absolutePath))
            h.mark.isVisible = fav || (!r.isDir && Uploads.isUploaded(act, r.file.absolutePath, r.size))
            h.mark.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_cloud_done)
            val on = selected.containsKey(r.file.absolutePath)
            h.itemView.isActivated = on
            h.check.isVisible = selected.isNotEmpty() && r.special == null && (dir != null || favView || trashView || query.isNotEmpty())
            h.check.isChecked = on
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView by lazy { v.findViewById(R.id.badge) }
            val icon: ImageView by lazy { v.findViewById(R.id.icon) }
            val thumb: ImageView by lazy { v.findViewById(R.id.thumb) }
            val name: TextView by lazy { v.findViewById(R.id.name) }
            val meta: TextView by lazy { v.findViewById(R.id.meta) }
            val mark: ImageView by lazy { v.findViewById(R.id.mark) }
            val check: MaterialCheckBox by lazy { v.findViewById(R.id.check) }
            var row: Row? = null
            init {
                v.setOnClickListener { val r = row ?: return@setOnClickListener; if (selected.isNotEmpty()) { if (r.special == null) toggle(r.file) } else open(r) }
                v.setOnLongClickListener { row?.takeIf { pickable(it) }?.let { toggle(it.file); dragSel.start(bindingAdapterPosition) }; true }
                if (v.findViewById<View?>(R.id.check) is MaterialCheckBox) check.setOnClickListener { row?.let { toggle(it.file) } }
            }
        }
    }
}

/** Cosa mandare nel cloud per una scelta di cartelle e file: ogni file con la sua cartella relativa, e le cartelle vuote. */
object FolderUpload {
    /** Il cestino del telefono e le miniature di Android non sono «la cartella». */
    private fun skip(f: File) = Trashed.isTrashed(f.name) || f.name == ".thumbnails"

    fun collect(picked: List<File>): Pair<List<Pair<File, String?>>, List<String>> {
        val files = ArrayList<Pair<File, String?>>()
        val empty = ArrayList<String>()
        for (top in picked) {
            if (!top.isDirectory) { files += top to null; continue }
            val base = top.parentFile ?: continue
            top.walkTopDown().onEnter { !skip(it) }.forEach { f ->
                if (skip(f)) return@forEach
                if (f.isFile) files += f to (f.parentFile ?: top).relativeTo(base).path
                else if (f.isDirectory && (f.listFiles()?.none { !skip(it) } != false)) empty += f.relativeTo(base).path
            }
        }
        return files to empty
    }

    /**
     * Come [collect], ma una sola lettura del disco per file (dimensione e data insieme): 18.000 file sul telefono di prova
     * erano ~5 s con tre letture a file (08/11).
     */
    fun collectSources(picked: List<File>): Pair<List<UpSource>, List<String>> {
        val out = ArrayList<UpSource>()
        val empty = ArrayList<String>()
        for (top in picked) {
            if (!top.isDirectory) { out += UpSource(top.absolutePath, top.name, top.length(), Thumbs.mimeOf(top.name), 0, top.lastModified(), top.absolutePath, null); continue }
            val base = (top.parentFile ?: continue).toPath()
            java.nio.file.Files.walkFileTree(top.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                val seen = ArrayDeque<Int>()
                override fun preVisitDirectory(d: java.nio.file.Path, a: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (d != top.toPath() && skip(d.toFile())) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    seen.addLast(0); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFile(f: java.nio.file.Path, a: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    val file = f.toFile()
                    if (skip(file)) return java.nio.file.FileVisitResult.CONTINUE
                    if (seen.isNotEmpty()) seen.addLast(seen.removeLast() + 1)
                    if (a.isRegularFile) out += UpSource(file.absolutePath, file.name, a.size(), Thumbs.mimeOf(file.name), 0, a.lastModifiedTime().toMillis(), file.absolutePath, base.relativize(f.parent).toString())
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(f: java.nio.file.Path, e: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
                override fun postVisitDirectory(d: java.nio.file.Path, e: java.io.IOException?): java.nio.file.FileVisitResult {
                    val n = seen.removeLast()
                    if (n == 0) empty += base.relativize(d).toString()
                    else if (seen.isNotEmpty()) seen.addLast(seen.removeLast() + n)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
        return out to empty
    }

    /** Toglie [top] e le sue sottocartelle che sono davvero vuote (nessuna voce, nemmeno nascosta), dal fondo in su. */
    fun pruneEmpty(top: File) {
        if (!top.isDirectory) return
        top.listFiles()?.filter { it.isDirectory }?.forEach { pruneEmpty(it) }
        if (top.listFiles()?.isEmpty() == true) top.delete()
    }
}
