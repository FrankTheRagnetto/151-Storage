package com.the151universe.storage

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * Il visore (spec §8): foto e video a tutto schermo, si sfoglia a destra e sinistra, si pizzica.
 * Il rullino arriva da chi apre (Foto, una cartella, il cloud) via [Roll]; da un'altra app arriva un
 * file solo («Apri con»). Barre d'acetato in alto e in basso, un tocco le nasconde.
 */
class MediaActivity : AppCompatActivity() {
    private lateinit var pager: ViewPager2
    private lateinit var toolbar: MaterialToolbar
    private lateinit var top: View
    private lateinit var bottom: View
    private var items: MutableList<MediaItem> = mutableListOf()
    private var from = FROM_PHOTOS
    private var chrome = true
    private val io = Executors.newFixedThreadPool(2)
    /**
     * Pronte prima di arrivarci: mentre guardi una foto, le anteprime di quelle accanto
     * (±1, ±2) stanno già in memoria e quelle del cloud vicine (+1, +2, −1) già scaricate, così scorrendo compaiono subito.
     * Una fila sola, a bassa priorità: non ruba niente alla foto che stai guardando.
     */
    private val pre = java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
        object : java.util.concurrent.LinkedBlockingDeque<Runnable>() { override fun offer(e: Runnable) = offerFirst(e) /* l'ultima chiesta per prima */ }) { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "visore-prima") }
    private val preDone = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Un video parte da solo solo quando ti FERMI su di lui (non mentre scorri via veloce). */
    private val autoPlay = Runnable { startIfVideo() }
    private val deleter by lazy { Deleter(this, onDeleted = {}, onDeletedMany = ::afterDelete) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deleter // registra il launcher prima di onStart
        setContentView(R.layout.activity_media)
        pager = findViewById(R.id.mPager)
        toolbar = findViewById(R.id.mToolbar)
        top = findViewById(R.id.mTop)
        bottom = findViewById(R.id.mBottom)
        from = intent.getIntExtra(EXTRA_FROM, FROM_PHOTOS)

        val external = intent.action == Intent.ACTION_VIEW && intent.data != null && Roll.items == null
        items = if (external) {
            val u = intent.data!!
            val (n, s) = Storage.nameAndSize(this, u)
            val name = n ?: u.lastPathSegment ?: "?"
            val mime = intent.type ?: contentResolver.getType(u) ?: Thumbs.mimeOf(name)
            val path = Storage.realPath(this, u, name, s)
            mutableListOf(MediaItem(u.hashCode().toLong(), u, path, name, s, 0, 0, 0, mime, mime.startsWith("video/"), "", 0))
        } else (Roll.items ?: emptyList()).toMutableList()
        Roll.items = null
        if (items.isEmpty()) { finish(); return }
        fromApp = !external
        // chiudendo, il visore sfuma e si ritira un poco invece di sparire di colpo; Android 14+ lo vuole qui
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.media_stay, R.anim.media_close)

        // UN visore solo: stesso aspetto da Media, Phone, Cloud, Recenti e «Apri con» — la fascia è sempre quella di Media
        findViewById<View>(R.id.mBand).setBackgroundColor(ContextCompat.getColor(this, R.color.place_photo))
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationContentDescription(R.string.close)
        toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mRoot)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.updatePadding(left = b.left, top = b.top, right = b.right)
            bottom.updatePadding(left = b.left, right = b.right, bottom = b.bottom)
            insets
        }
        buildActions()
        pager.adapter = PageAdapter()
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (fromApp) Roll.seen = items.getOrNull(position) // tornando all'app il foglio la tiene in vista
                stopVideo(); updateTitle(); prefetch(position)
                tick.removeCallbacks(autoPlay); tick.postDelayed(autoPlay, SETTLE_MS)
            }
            override fun onPageScrollStateChanged(state: Int) {
                // si riprende a contare solo da fermi: un dito che scorre non fa partire niente
                if (state != ViewPager2.SCROLL_STATE_IDLE) tick.removeCallbacks(autoPlay)
                else { tick.removeCallbacks(autoPlay); tick.postDelayed(autoPlay, SETTLE_MS) }
            }
        })
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleFav()
                2 -> rotate()
            }
            true
        }
        updateTitle()
        prefetch(pager.currentItem)
        tick.postDelayed(autoPlay, SETTLE_MS) // aperto su un video: parte da solo
    }

    private var fromApp = false

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT < 34) @Suppress("DEPRECATION") overridePendingTransition(R.anim.media_stay, R.anim.media_close)
    }

    override fun onDestroy() { tick.removeCallbacks(autoPlay); stopVideo(); io.shutdownNow(); pre.shutdownNow(); super.onDestroy() }

    private fun pageHolder(pos: Int) = (pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos) as? PageAdapter.VH

    private fun startIfVideo() {
        if (isDestroyed || video != null || pager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        val pos = pager.currentItem
        val m = items.getOrNull(pos)?.takeIf { it.isVideo } ?: return
        val h = pageHolder(pos) ?: return
        if (h.item === m && h.error.isVisible.not()) playInline(h, m)
    }

    /** Prepara le vicine: anteprime in memoria (telefono), file scaricati (cloud, solo foto fino a 30 MB). */
    private fun prefetch(pos: Int) {
        val px = 512
        for (d in intArrayOf(-2, 2, -1, 1)) { // la fila serve l'ultima chiesta per prima: +1 prima di tutte
            val m = items.getOrNull(pos + d) ?: continue
            val c = m.cloud
            val key = if (c != null) "c" + c.path + c.size else "m${m.id}"
            if (!preDone.add(key)) continue
            pre.execute {
                try {
                    if (c == null) {
                        if (Thumbs.cached(key) == null) (Thumbs.ofMedia(this, m, px) ?: m.path?.let { Thumbs.ofFile(this, java.io.File(it), px) })?.let { Thumbs.remember(key, it) }
                    } else {
                        if (Thumbs.cached(key) == null) CloudThumbs.ready(this, c)?.let { Thumbs.remember(key, it) }
                        if (!m.isVideo && d != -2 && c.size in 0..PREFETCH_MAX && CloudFiles.ready(this, c) == null)
                            Clouds.drive(this)?.let { CloudFiles.fetch(this, it, c) }
                    }
                } catch (_: Exception) { preDone.remove(key) }
            }
        }
    }
    override fun onPause() { video?.pause(); playing = false; progress?.let { tick.removeCallbacks(it) }; super.onPause() }

    // ------------------------------------------------------------ preferito, ruota, rinomina, apri con

    private fun favKey(m: MediaItem) = m.cloud?.let { Favorites.cloud(it.path) } ?: m.path?.let { Favorites.phone(it) }

    /** L'URI da dare alle altre app: per il cloud prima si scarica (copia in cache), poi il nostro FileProvider. */
    private fun withUri(m: MediaItem, then: (android.net.Uri) -> Unit) {
        val c = m.cloud ?: return then(m.uri)
        val drive = Clouds.drive(this) ?: return
        io.execute {
            val f = try { CloudFiles.fetch(this, drive, c) } catch (e: Exception) { runOnUiThread { Ask.toast(this, getString(R.string.cloud_open_failed, e.message ?: "")) }; null }
            if (f != null) runOnUiThread { then(androidx.core.content.FileProvider.getUriForFile(this, Storage.authority(this), f)) }
        }
    }

    private fun toggleFav() {
        val k = current?.let { favKey(it) } ?: return
        val on = Favorites.toggle(this, listOf(k))
        Ask.toast(this, getString(if (on) R.string.fav_added else R.string.fav_removed))
        updateTitle()
    }

    /** Ruota solo la vista (il file non cambia). */
    private fun rotate() {
        val iv = pager.findViewWithTag<View>("pagehost${pager.currentItem}")?.findViewById<ZoomImageView>(R.id.pImage) ?: return
        iv.rotateBy(90f)
    }

    private fun renameCurrent() {
        val m = current ?: return
        m.cloud?.let { renameCloud(m, it); return }
        val f = m.path?.let { java.io.File(it) } ?: return
        Ask.name(this, R.string.rename, m.name) { n ->
            io.execute {
                val r = try { val nf = PhoneOps.rename(this, f, n); Favorites.rename(this, Favorites.phone(f.absolutePath), Favorites.phone(nf.absolutePath)); nf } catch (e: Exception) { runOnUiThread { Ask.toast(this, e.message ?: "error") }; null }
                if (r != null) runOnUiThread {
                    val i = pager.currentItem
                    items[i] = MediaItem.ofFile(this, r)
                    pager.adapter?.notifyItemChanged(i)
                    updateTitle(); Gallery.refresh(this)
                }
            }
        }
    }

    /** Rinomina nel cloud (come dal Cloud): il file lassù, la stella lo segue. */
    private fun renameCloud(m: MediaItem, c: CloudEntry) {
        val drive = Clouds.drive(this) ?: return
        val e = realEntry(c)
        Ask.name(this, R.string.rename, m.name) { n ->
            io.execute {
                val np = e.path.substringBeforeLast('/', "").let { if (it.isEmpty()) n else "$it/$n" }
                val ok = try { drive.rename(e, n); Favorites.rename(this, Favorites.cloud(e.path), Favorites.cloud(np)); CloudState.moved(this, e.path, np); true }
                    catch (x: Exception) { runOnUiThread { Ask.toast(this, x.message ?: "error") }; false }
                if (ok) runOnUiThread {
                    val i = items.indexOf(m).takeIf { it >= 0 } ?: return@runOnUiThread
                    items[i] = MediaItem.ofCloud(CloudEntry(WebDav.encPath(np).trimEnd('/'), n, false, e.size, e.mtime, null, np))
                    pager.adapter?.notifyItemChanged(i); updateTitle()
                    Ask.toast(this, getString(R.string.done_renamed))
                }
            }
        }
    }

    // ------------------------------------------------------------ «Sposta in…»

    /** Le ultime 3 cartelle dove si è spostato (telefono e cloud separati): in cima, a un tocco. */
    private fun recentDests(kind: String): List<String> = try {
        org.json.JSONArray(getSharedPreferences("settings", 0).getString("moveDest_$kind", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }
    private fun rememberDest(kind: String, d: String) = getSharedPreferences("settings", 0).edit()
        .putString("moveDest_$kind", org.json.JSONArray((listOf(d) + recentDests(kind).filter { it != d }).take(3)).toString()).apply()

    private fun moveCurrent() {
        val m = current ?: return
        val c = m.cloud
        val kind = if (c != null) "cloud" else "phone"
        val here = c?.path?.substringBeforeLast('/', "") ?: m.path?.substringBeforeLast('/') ?: return
        val recent = recentDests(kind).filter { it.trimEnd('/') != here.trimEnd('/') }
        val labels = recent.map { d ->
            val name = d.trimEnd('/').substringAfterLast('/').ifEmpty { "Cloud" }
            val where = if (c != null) d.substringBeforeLast('/', "").replace("/", " › ") else PhoneOps.placeLabel(d)
            android.text.SpannableStringBuilder(name).also { if (where.isNotEmpty()) it.append("\n").append(where, android.text.style.RelativeSizeSpan(0.8f), 0) }
        } + getString(R.string.move_other)
        MaterialAlertDialogBuilder(this).setTitle(R.string.move_title).setItems(labels.toTypedArray<CharSequence>()) { _, i ->
            if (i < recent.size) moveTo(m, recent[i])
            else if (c != null) Clouds.drive(this)?.let { d -> FolderPicker.show(this, d, here, R.string.move_here) { dest -> moveTo(m, dest) } }
            else PhonePicker.show(this, R.string.move_title, R.string.move_here, java.io.File(here)) { dest -> moveTo(m, dest.absolutePath) }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun moveTo(m: MediaItem, dest: String) {
        val c = m.cloud
        io.execute {
            val ok = try {
                if (c != null) {
                    val drive = Clouds.drive(this) ?: return@execute
                    val e = realEntry(c)
                    val newRef = drive.transfer(e, dest, false)
                    val np = android.net.Uri.decode(newRef).trim('/')
                    Favorites.rename(this, Favorites.cloud(e.path), Favorites.cloud(np))
                    CloudState.moved(this, e.path, np) // Doppioni, Random, Oggi, Preferiti, cartelle: tutti lo vedono al posto nuovo
                    rememberDest("cloud", dest.trim('/'))
                } else {
                    val f = java.io.File(m.path ?: return@execute)
                    val nf = PhoneOps.transfer(this, f, java.io.File(dest), false)
                    Favorites.rename(this, Favorites.phone(f.absolutePath), Favorites.phone(nf.absolutePath))
                    rememberDest("phone", dest)
                    Gallery.refresh(this)
                }
                true
            } catch (x: Exception) { runOnUiThread { Ask.toast(this, x.message ?: "error") }; false }
            if (ok) runOnUiThread {
                removeCurrent(m)
                Toast.makeText(this, getString(R.string.moved_to, dest.trimEnd('/').substringAfterLast('/').ifEmpty { "Cloud" }), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** La voce vera del cloud: le copie «Sul telefono» (Media) arrivano con un riferimento «offline:». */
    private fun realEntry(c: CloudEntry) = if (c.ref.startsWith("offline:")) c.copy(ref = WebDav.encPath(c.path).trimEnd('/')) else c

    private fun more(anchor: View) {
        val m = current ?: return
        val pm = androidx.appcompat.widget.PopupMenu(this, anchor)
        if (m.path != null || m.cloud != null) pm.menu.add(0, 1, 0, R.string.rename)
        if (m.path != null || m.cloud != null) pm.menu.add(0, 4, 0, R.string.move_title)
        pm.menu.add(0, 2, 1, R.string.open_with)
        if (!m.isVideo) pm.menu.add(0, 3, 2, R.string.rotate)
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> renameCurrent(); 2 -> withUri(m) { u -> Ask.openWith(this, u, m.mime, m.name) }; 3 -> rotate(); 4 -> moveCurrent() }
            true
        }
        pm.show()
    }

    private val current get() = items.getOrNull(pager.currentItem)

    private fun updateTitle() {
        val m = current ?: return
        toolbar.title = m.name
        val parts = ArrayList<String>()
        if (items.size > 1) parts += "${pager.currentItem + 1} of ${items.size}"
        if (m.time > 0) parts += Dates.withTime(m.time)
        if (m.size > 0) parts += Sizes.short(m.size)
        toolbar.subtitle = parts.joinToString(" · ")
        // la stella e il ruota si rifanno DOPO il layout: dentro il cambio di pagina restavano invisibili sulla prima foto
        pager.post { if (!isDestroyed) buildMenu() }
        // il cambio di pagina arriva durante il layout del pager: ricostruire la barra lì la lascia a 0 px
        pager.post { if (!isDestroyed) buildActions() }
    }

    private fun buildMenu() {
        toolbar.menu.clear()
        val m0 = current
        if (m0 != null && favKey(m0) != null) toolbar.menu.add(0, 1, 0, R.string.fav_toggle)
            .setIcon(if (Favorites.has(this, favKey(m0)!!)) R.drawable.ic_star else R.drawable.ic_star_border)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (m0 != null && !m0.isVideo) toolbar.menu.add(0, 2, 1, R.string.rotate).setIcon(R.drawable.ic_rotate).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    private fun buildActions() {
        val row = findViewById<LinearLayout>(R.id.mActions)
        row.removeAllViews()
        val m = current
        val inCloud = m != null && Uploads.isUploaded(this, m.path ?: m.uri.toString(), m.size)
        val acts = ArrayList<Triple<Int, Int, () -> Unit>>()
        // la stessa barra ovunque, negli stessi posti: Condividi · Carica/Scarica · Elimina ·
        // Info · Altro. Cambia solo la seconda: una foto del telefono si carica, una del cloud si scarica.
        acts += Triple(R.string.sel_share, R.drawable.ic_share) { share() }
        if (m?.cloud != null) acts += Triple(R.string.sel_download, R.drawable.ic_download) { saveCloud() }
        else acts += Triple(if (inCloud) R.string.media_in_cloud else R.string.upload_short, if (inCloud) R.drawable.ic_cloud_done else R.drawable.ic_upload) { upload() }
        acts += Triple(R.string.sel_delete, R.drawable.ic_delete) { delete() }
        acts += Triple(R.string.media_info, R.drawable.ic_info) { info() }
        val canDelete = m != null && (m.cloud != null || m.path != null)
        for ((label, icon, run) in acts) row.addView(actionButton(this, label, icon, run,
            enabled = !(label == R.string.media_in_cloud) && !(label == R.string.sel_delete && !canDelete)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        lateinit var moreBtn: View
        moreBtn = actionButton(this, R.string.more, R.drawable.ic_more, { more(moreBtn) })
        row.addView(moreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun toggleChrome() {
        chrome = !chrome
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        // le barre sfumano invece di sparire a scatto
        Motion.fade(top, chrome)
        Motion.fade(bottom, chrome)
        controls?.let { Motion.fade(it, chrome && video != null) }
    }

    private fun share() {
        val m = current ?: return
        withUri(m) { u ->
            val send = Intent(Intent.ACTION_SEND).setType(m.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(m.name, u)
            try { startActivity(Intent.createChooser(send, getString(R.string.share))) } catch (_: Exception) { Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show() }
        }
    }

    /** «Scarica» dal visore del cloud: in Download/151 Storage, come la Scarica del Cloud. */
    private fun saveCloud() {
        val m = current ?: return
        val c = m.cloud ?: return
        val drive = Clouds.drive(this) ?: return
        @Suppress("DEPRECATION") val dest = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "151 Storage")
        io.execute {
            val msg = try {
                val f = CloudFiles.fetch(this, drive, c)
                dest.mkdirs()
                val t = java.io.File(dest, freeName(c.name) { java.io.File(dest, it).exists() })
                f.copyTo(t)
                // la data del file del cloud, mai «oggi»
                val mt = if (c.mtime > 0) c.mtime else try { drive.info(realEntry(c).ref)?.mtime ?: 0L } catch (_: Exception) { 0L }
                if (mt > 0) t.setLastModified(mt)
                android.media.MediaScannerConnection.scanFile(this, arrayOf(t.path), null, null)
                getString(R.string.cloud_saved)
            } catch (e: Exception) { getString(R.string.cloud_open_failed, e.message ?: "") }
            runOnUiThread { Ask.toast(this, msg) }
        }
    }

    private fun upload() {
        val m = current ?: return
        if (Uploads.isUploaded(this, m.path ?: m.uri.toString(), m.size)) return
        UploadSheet.show(this, listOf(UpSource(m.path ?: m.uri.toString(), m.name, m.size, m.mime, m.taken, m.mtime, m.path)), null) { buildActions() }
    }

    /** Elimina da dentro il visore: del telefono → il cestino del telefono; del cloud → il cestino del cloud. */
    private fun delete() {
        val m = current ?: return
        m.cloud?.let { deleteCloud(m, it); return }
        val p = m.path ?: return
        deleter.startMany(listOf(LibFile(p, m.size, m.mtime)))
    }

    /** Come «Elimina» nel Cloud: stessa domanda, stesso cestino (30 giorni, si ripristina), sparisce anche da Random/Oggi/Preferiti. */
    private fun deleteCloud(m: MediaItem, c: CloudEntry) {
        val drive = Clouds.drive(this) ?: return
        io.execute {
            val trash = try { Trashes.forDrive(drive) } catch (x: Exception) { runOnUiThread { Ask.toast(this, x.message ?: "error") }; return@execute }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(this).setTitle(getString(R.string.cloud_del_title_one, m.name))
                    .setMessage(R.string.cloud_del_body_own)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.del_trash_ok) { _, _ ->
                        io.execute {
                            val ok = try {
                                var e = realEntry(c)
                                if (e.size < 0) drive.info(e.ref)?.let { real -> e = e.copy(size = real.size, mtime = real.mtime) }
                                trash.delete(listOf(e))
                                CloudState.deleted(this, listOf(e.path)) // via ovunque, subito
                                // la copia «Sul telefono» non serve più: via subito anche da Media
                                if (Offline.isPinned(this, e.path)) { Offline.local(this, e.path).delete(); Gallery.refreshOffline(this) }
                                true
                            } catch (x: Exception) { runOnUiThread { Ask.toast(this, getString(R.string.cloud_open_failed, x.message ?: "")) }; false }
                            if (ok) runOnUiThread { removeCurrent(m); Toast.makeText(this, R.string.moved_to_trash, Toast.LENGTH_SHORT).show() }
                        }
                    }.show()
            }
        }
    }

    private fun removeCurrent(m: MediaItem) {
        if (isDestroyed) return
        val i = items.indexOf(m).takeIf { it >= 0 } ?: return
        stopVideo()
        items.removeAt(i)
        if (items.isEmpty()) { finish(); return }
        pager.adapter?.notifyItemRemoved(i)
        updateTitle()
    }

    private fun afterDelete(d: DeletedMany) {
        val m = current ?: return
        if (d.trashed.isEmpty() && d.gone == 0) return
        Gallery.remove(setOf(m.id))
        removeCurrent(m)
        Toast.makeText(this, if (d.trashed.isNotEmpty()) R.string.moved_to_trash else R.string.deleted, Toast.LENGTH_SHORT).show()
    }

    private fun info() {
        val m = current ?: return
        val lines = buildList {
            if (m.time > 0) add(Dates.withTime(m.time))
            if (m.size > 0) add(Sizes.short(m.size))
            if (m.album.isNotEmpty()) add("Album: " + m.album)
            m.path?.let { add(PhoneOps.placeLabel(it)) }
            m.cloud?.let { add("In the cloud › " + it.path.substringBeforeLast('/', "").replace("/", " › ").ifEmpty { "Cloud" }) }
            if (Uploads.isUploaded(this@MediaActivity, m.path ?: m.uri.toString(), m.size)) add(getString(R.string.already_in_cloud))
        }
        MaterialAlertDialogBuilder(this).setTitle(m.name).setMessage(lines.joinToString("\n")).setPositiveButton(R.string.ok, null).show()
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        private val maxPx = minOf(2560, maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2)
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(layoutInflater.inflate(R.layout.page_media, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = items[pos]
            h.item = m
            h.image.setImageDrawable(null)
            h.error.isVisible = false
            h.play.isVisible = false
            h.spin.isVisible = true
            h.image.onTap = { toggleChrome() }
            h.image.tag = null
            h.itemView.tag = "pagehost$pos"
            h.videoHost.isVisible = false; h.videoHost.removeAllViews(); h.controls.isVisible = false
            h.image.isVisible = true
            h.image.onSwipeDown = { finish() }
            // subito qualcosa da guardare: la miniatura in memoria o l'anteprima del cloud già sul telefono, poi la foto intera
            val early = Thumbs.cached("m${m.id}") ?: m.cloud?.let { Thumbs.cached("c" + it.path + it.size) ?: CloudThumbs.ready(this@MediaActivity, it) }
            early?.let { h.image.setImageBitmap(it) }
            if (early == null && m.cloud == null) pre.execute {
                // nessuna anteprima pronta: quella del sistema (veloce) mentre arriva la foto intera
                val b = try { Thumbs.ofMedia(this@MediaActivity, m, 512) } catch (_: Exception) { null } ?: return@execute
                Thumbs.remember("m${m.id}", b)
                runOnUiThread { if (h.item === m && h.image.drawable == null) { h.image.setImageBitmap(b); Motion.fadeIn(h.image, Motion.SHORT) } }
            }
            val key = m.uri.toString()
            h.image.tag = key
            io.execute {
                val b = try {
                    val c = m.cloud
                    if (c != null) {
                        // foto del cloud: si scarica adesso (o si usa la copia già sul telefono); il video si guarda mentre arriva
                        if (m.isVideo) CloudFiles.ready(this@MediaActivity, c)?.let { Thumbs.ofFile(this@MediaActivity, it, 1024) }
                        else Clouds.drive(this@MediaActivity)?.let { d ->
                            val f = CloudFiles.fetch(this@MediaActivity, d, c)
                            Thumbs.decodeFull(this@MediaActivity, android.net.Uri.fromFile(f), maxPx)
                        }
                    }
                    else if (m.isVideo) Thumbs.ofMedia(this@MediaActivity, m, 1024) ?: m.path?.let { Thumbs.ofFile(this@MediaActivity, java.io.File(it), 1024) }
                    else Thumbs.decodeFull(this@MediaActivity, m.uri, maxPx)
                } catch (e: Throwable) { null }
                runOnUiThread {
                    if (h.image.tag != key || isDestroyed) return@runOnUiThread
                    h.spin.isVisible = false
                    // la prima immagine della pagina entra sfumando; quella intera sopra l'anteprima si sostituisce e basta
                    if (b != null) { val first = h.image.drawable == null; h.image.setImageBitmap(b); if (first) Motion.fadeIn(h.image, Motion.SHORT) }
                    if (m.isVideo) h.play.isVisible = true
                    else if (b == null) { h.error.isVisible = true; h.error.setText(R.string.media_error) }
                }
            }
            h.play.setOnClickListener { playInline(h, m) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ZoomImageView = v.findViewById(R.id.pImage)
            val spin: ProgressBar = v.findViewById(R.id.pSpin)
            val play: FrameLayout = v.findViewById(R.id.pPlay)
            val error: TextView = v.findViewById(R.id.pError)
            val videoHost: FrameLayout = v.findViewById(R.id.pVideoHost)
            val controls: View = v.findViewById(R.id.pControls)
            var item: MediaItem? = null
        }
    }

    // ------------------------------------------------------------ video dentro la pagina

    private var video: android.widget.VideoView? = null
    private var controls: View? = null
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private var full = false
    /**
     * Il tempo e la barra del video, 4 volte al secondo SOLO mentre il video va (prima giravano sempre, anche in
     * pausa e con l'app in sottofondo). Fermo: un aggiornamento solo, dopo un salto (−10/+10) o alla fine.
     */
    private var progress: Runnable? = null
    private var playing = false
    private fun kick(delay: Long = 0) { progress?.let { tick.removeCallbacks(it); tick.postDelayed(it, delay) } }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "${s / 60}:" + String.format(java.util.Locale.ROOT, "%02d", s % 60) }

    private fun playInline(h: PageAdapter.VH, m: MediaItem) {
        stopVideo()
        val v = android.widget.VideoView(this)
        h.videoHost.removeAllViews()
        h.videoHost.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))   // alto quanto lo schermo: VideoView tiene le proporzioni (con WRAP_CONTENT un video verticale col telefono girato usciva allargato, giro 03/11)
        h.videoHost.isVisible = true
        h.play.isVisible = false
        h.spin.isVisible = true
        video = v; controls = h.controls
        val seek = h.controls.findViewById<android.widget.SeekBar>(R.id.pSeek)
        val time = h.controls.findViewById<TextView>(R.id.pTime)
        val dur = h.controls.findViewById<TextView>(R.id.pDur)
        val toggle = h.controls.findViewById<android.widget.ImageButton>(R.id.pToggle)
        val c = m.cloud
        val local = c?.let { CloudFiles.ready(this, it) }
        when {
            c == null -> v.setVideoURI(m.uri)
            local != null -> v.setVideoURI(android.net.Uri.fromFile(local))
            else -> Clouds.drive(this)?.streamUri(c)?.let { (u, hd) -> v.setVideoURI(u, hd) }
        }
        v.setOnPreparedListener { mp ->
            h.spin.isVisible = false
            h.image.isVisible = false
            seek.max = v.duration.coerceAtLeast(1)
            dur.text = fmt(v.duration)
            h.controls.isVisible = chrome
            v.start()
            toggle.setImageResource(R.drawable.ic_pause)
            mp.setOnCompletionListener { toggle.setImageResource(R.drawable.ic_play); playing = false; kick() }
            progress?.let { tick.removeCallbacks(it) }
            val r = object : Runnable { override fun run() { if (video === v) { seek.progress = v.currentPosition; time.text = fmt(v.currentPosition); if (playing) tick.postDelayed(this, 250) } } }
            progress = r
            playing = true
            tick.post(r)
        }
        v.setOnErrorListener { _, _, _ -> h.spin.isVisible = false; h.error.isVisible = true; h.error.setText(R.string.media_video_error); stopVideo(); true }
        v.setOnClickListener { toggleChrome() }
        v.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_UP) toggleChrome(); true }
        toggle.setOnClickListener { if (v.isPlaying) { v.pause(); playing = false; toggle.setImageResource(R.drawable.ic_play) } else { v.start(); playing = true; kick(); toggle.setImageResource(R.drawable.ic_pause) } }
        h.controls.findViewById<View>(R.id.pBack).setOnClickListener { v.seekTo((v.currentPosition - 10_000).coerceAtLeast(0)); if (!playing) kick(250) }
        h.controls.findViewById<View>(R.id.pFwd).setOnClickListener { v.seekTo((v.currentPosition + 10_000).coerceAtMost(v.duration)); if (!playing) kick(250) }
        h.controls.findViewById<View>(R.id.pFull).setOnClickListener { fullscreen(!full) }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, user: Boolean) { if (user) { v.seekTo(p); time.text = fmt(p) } }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
    }

    /** Schermo intero: il telefono si gira col video e le barre spariscono. */
    private fun fullscreen(on: Boolean) {
        full = on
        requestedOrientation = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (on == chrome) toggleChrome()
    }

    private fun stopVideo() {
        playing = false; progress?.let { tick.removeCallbacks(it) }; progress = null
        val v = video ?: return
        try { v.stopPlayback() } catch (_: Exception) {}
        (v.parent as? ViewGroup)?.let { host -> host.removeView(v); host.visibility = View.GONE }
        controls?.isVisible = false
        video = null; controls = null
        if (full) fullscreen(false)
        val i = pager.currentItem
        pager.post { if (!isDestroyed) pager.adapter?.notifyItemChanged(i) }
    }

    companion object {
        const val EXTRA_FROM = "from"
        /** Quanto stare fermi su un video perché parta (un programma da tastiera usa 140 ms con la tastiera; col dito un po' di più). */
        const val SETTLE_MS = 450L
        const val PREFETCH_MAX = 30L * 1024 * 1024
        const val EXTRA_INDEX = "index"
        const val FROM_PHOTOS = 0
        const val FROM_FOLDERS = 1
        const val FROM_CLOUD = 2

        /** Pulsante della barra in basso: icona sopra, etichetta sotto (spec §5, §8). */
        fun actionButton(act: AppCompatActivity, label: Int, icon: Int, run: () -> Unit, enabled: Boolean = true): Button =
            Button(act, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(label)
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(act, if (enabled) R.color.ink else R.color.ink_muted))
                setTextColor(ContextCompat.getColor(act, if (enabled) R.color.ink else R.color.ink_muted))
                textSize = 13f
                isAllCaps = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, act.dp(6), 0, act.dp(4))
                compoundDrawablePadding = act.dp(2)
                isEnabled = enabled
                tooltipText = act.getString(label)
                setOnClickListener { run() }
            }
    }
}

/** Il rullino da mostrare nel visore: troppi elementi per un Intent, si passano in memoria. */
object Roll {
    @Volatile var items: List<MediaItem>? = null
    /** L'ultima foto guardata nel visore aperto dall'app: tornando, il foglio la tiene in vista. */
    @Volatile var seen: MediaItem? = null
    fun takeSeen(): MediaItem? = seen.also { seen = null }

    /** Porta la riga [pos] in vista (a un terzo dall'alto) solo se ora non si vede affatto: niente salti se ne spunta anche un pezzo. */
    fun reveal(rv: RecyclerView, pos: Int) {
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        rv.post {
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION || pos < first || pos > last) lm.scrollToPositionWithOffset(pos, rv.height / 3)
        }
    }
}
