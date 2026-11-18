package com.the151universe.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Una foto o un video del telefono, come lo dice MediaStore. */
class MediaItem(
    val id: Long,
    val uri: Uri,
    val path: String?,
    val name: String,
    val size: Long,
    /** Data da mostrare e per raggruppare: quando è stata scattata (MediaStore), o la modifica. */
    val time: Long,
    val taken: Long,
    val mtime: Long,
    val mime: String,
    val isVideo: Boolean,
    val album: String,
    val durationMs: Long,
    /** Una foto o un video del CLOUD: si scarica quando la si guarda (foto) o si guarda mentre arriva (video). */
    val cloud: CloudEntry? = null,
) {
    companion object {
        fun ofCloud(e: CloudEntry): MediaItem {
            val mime = Thumbs.mimeOf(e.name)
            return MediaItem(e.path.hashCode().toLong(), android.net.Uri.parse("cloud://" + e.path), null, e.name, e.size,
                e.mtime, 0, e.mtime, mime, mime.startsWith("video/"), e.path.substringBeforeLast('/', ""), 0, e)
        }

        /** Una foto o un video trovato in una cartella (o scaricato dal cloud): passa dal nostro FileProvider. */
        fun ofFile(ctx: Context, f: File, uri: Uri? = null): MediaItem {
            val mime = Sniff.fixFile(Thumbs.mimeOf(f.name), f) // foto o video dai byte, non dal nome
            return MediaItem(f.absolutePath.hashCode().toLong(), uri ?: Storage.uriFor(ctx, f.absolutePath), f.absolutePath, f.name, f.length(),
                f.lastModified(), 0, f.lastModified(), mime, mime.startsWith("video/"), f.parentFile?.name ?: "", 0)
        }
    }
}

/**
 * La galleria: tutte le foto e i video del telefono in UNA query su MediaStore (niente giro del disco:
 * per le foto MediaStore è la verità, e i file appena scattati ci sono subito). Fuori dal thread
 * principale; vince l'ultimo giro.
 */
object Gallery {
    private const val TAG = "151Storage"
    private val DOC_IMAGES = setOf("image/tiff", "image/vnd.adobe.photoshop", "image/x-photoshop", "image/svg+xml", "application/postscript")

    @Volatile var items: List<MediaItem> = emptyList()
        private set
    private val turno = Rilettura()
    private val tolti: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() // tolti durante una lettura
    val loading: Boolean get() = turno.busy
    @Volatile var loaded = false
        private set
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "galleria") }
    /** MediaStore com'era all'ultima lettura ([MediaGen]) */
    @Volatile private var token: String? = null
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** [onlyIfChanged]: tornando su Media si rilegge MediaStore solo se è cambiato ([MediaGen]). */
    fun refresh(ctx: Context, onlyIfChanged: Boolean = false) {
        if (!turno.start()) return
        leggi(ctx.applicationContext, onlyIfChanged)
    }

    private fun leggi(app: Context, onlyIfChanged: Boolean) {
        exec.execute {
            var ancora = false
            tolti.clear()
            try {
                val tok = MediaGen.token(app)
                if (onlyIfChanged && loaded && !Freni.mediaChanged(tok, token)) return@execute
                val t0 = System.currentTimeMillis()
                items = Freni.afterScan(query(app), { it.id.toString() }, tolti, emptyList()) { false } // niente lampo del tolto
                token = tok
                loaded = true
                Log.i(TAG, "galleria: ${items.size} in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) {
                Log.w(TAG, "galleria non leggibile", e)
            } finally {
                ancora = turno.finish()
                changed()
                if (ancora) leggi(app, false) // chiesta durante la lettura: si rifà, intera
            }
        }
    }

    /**
     * Le cartelle del cloud tenute «sempre su questo telefono» («è sul mio telefono, devo poterlo
     * sfogliare in Media»): un album per cartella, chiave [CLOUD_ALBUM] + percorso. Ogni voce è un file del cloud
     * ([MediaItem.cloud]) la cui copia è già in `files/offline/`: il visore la apre senza rete, e niente
     * Elimina/Rinomina/Carica (è una copia: lassù comanda il cloud).
     */
    @Volatile var offline: List<MediaItem> = emptyList()
        private set
    const val CLOUD_ALBUM = "\u2601"

    fun refreshOffline(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute {
            try { offline = queryOffline(app) } catch (e: Exception) { Log.w(TAG, "cartelle sul telefono non leggibili", e) }
            changed()
        }
    }

    private fun queryOffline(ctx: Context): List<MediaItem> {
        val roots = Offline.pins(ctx).map { it.path.trim('/') }.distinct()
        // una cartella tenuta dentro un'altra tenuta sta già nell'album di quella più grande
        val top = roots.filter { r -> roots.none { q -> q != r && (q.isEmpty() || r.startsWith("$q/")) } }
        val out = ArrayList<MediaItem>()
        for (r in top) {
            val base = Offline.local(ctx, r)
            if (!base.isDirectory) continue
            base.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && !it.name.startsWith(".") && Thumbs.isMedia(it.name) }.forEach { f ->
                val rel = f.relativeTo(base).invariantSeparatorsPath
                val p = if (r.isEmpty()) rel else "$r/$rel"
                val mime = Sniff.fixFile(Thumbs.mimeOf(f.name), f) // foto o video dai byte, non dal nome
                val e = CloudEntry("offline:$p", f.name, false, f.length(), f.lastModified(), mime, p)
                out += MediaItem(p.hashCode().toLong(), Uri.parse("cloud://$p"), null, f.name, f.length(), f.lastModified(), 0,
                    f.lastModified(), mime, mime.startsWith("video/"), CLOUD_ALBUM + r, 0, e)
            }
        }
        return out
    }

    fun remove(ids: Set<Long>) {
        items = items.filter { it.id !in ids }
        if (turno.busy) ids.forEach { tolti += it.toString() }
        turno.dirty() // una lettura in corso li ha visti ancora lì: alla sua fine se ne rifà una
        changed()
    }

    @Suppress("DEPRECATION")
    private fun query(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>(4096)
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, "datetaken", MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE, "bucket_display_name", "duration",
        )
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ${MediaStore.MediaColumns.SIZE} > 0"
        val c = if (Build.VERSION.SDK_INT >= 30) {
            ctx.contentResolver.query(uri, proj, Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            }, null)
        } else ctx.contentResolver.query(uri, proj, sel, null, null)
        c?.use {
            val iId = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iData = it.getColumnIndex(MediaStore.MediaColumns.DATA)
            val iName = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iTaken = it.getColumnIndex("datetaken")
            val iMod = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iType = it.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iBucket = it.getColumnIndex("bucket_display_name")
            val iDur = it.getColumnIndex("duration")
            while (it.moveToNext()) {
                val id = it.getLong(iId)
                val path = if (iData >= 0) it.getString(iData) else null
                if (path != null && path.contains("/.")) continue // cartelle nascoste, .trashed-, .thumbnails
                val video = it.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val mimeRaw = if (iMime >= 0) it.getString(iMime) else null
                if (mimeRaw != null && mimeRaw in DOC_IMAGES) continue // TIFF, PSD, SVG: documenti, stanno in Documenti
                val name = (if (iName >= 0) it.getString(iName) else null) ?: path?.substringAfterLast('/') ?: "?"
                val taken = if (iTaken >= 0) it.getLong(iTaken) else 0L
                val mtime = (if (iMod >= 0) it.getLong(iMod) else 0L) * 1000
                val mime = (if (iMime >= 0) it.getString(iMime) else null) ?: if (video) "video/*" else "image/*"
                val col = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += MediaItem(
                    id, ContentUris.withAppendedId(col, id), path, name, if (iSize >= 0) it.getLong(iSize) else -1,
                    if (taken > 0) taken else mtime, taken, mtime, mime, video,
                    albumName((if (iBucket >= 0) it.getString(iBucket) else null) ?: path?.substringBeforeLast('/')?.substringAfterLast('/') ?: "", path),
                    if (iDur >= 0) it.getLong(iDur) else 0L,
                )
            }
        }
        out.sortByDescending { it.time }
        return out
    }

    // i file sciolti nella cartella principale del telefono: il sistema chiama l'album «0» (/storage/emulated/0) →
    // lo stesso nome dei Documenti, «Internal storage» (prova sul telefono 03/11)
    private val RADICE = Regex("^/storage/emulated/\\d+$")
    private fun albumName(b: String, path: String? = null) = when {
        path != null && RADICE.matches(path.substringBeforeLast('/')) -> "Internal storage"
        b == "WhatsApp Images" || b == "WhatsApp Video" || b == "WhatsApp Animated Gifs" -> "WhatsApp"
        else -> b
    }
}

/**
 * Miniature: dal sistema (MediaStore le tiene già pronte), in memoria con un LRU da 1/8 della RAM
 * dell'app; le richieste più recenti passano per prime (chi scorre veloce vede subito dove si ferma).
 */
object Thumbs {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val exec = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e) // ultimo arrivato, primo servito
    }).apply { allowCoreThreadTimeOut(true) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun cached(key: String): Bitmap? = mem.get(key)
    /** Per il visore: un'anteprima preparata in anticipo (la foto prima e quella dopo). */
    fun remember(key: String, b: Bitmap) { mem.put(key, b) }

    /** Mette la miniatura in [iv], se nel frattempo la vista non è passata a un altro file. */
    /** [fallback] (l'icona del tipo) si mostra quando non c'è miniatura, ma NON si mette in memoria al posto di lei. */
    /** [quick], se c'è, si prova prima in una fila sua che legge SOLO il disco del telefono (le anteprime già fatte
     *  escono subito, senza aspettare dietro quelle che si scaricano); se non basta, [make] come sempre. La rete resta
     *  esattamente quella di prima: stessa fila, stessi 3 lavori insieme (0.9.2, dopo la 0.10 rotta sul telefono vero). */
    fun load(iv: ImageView, key: String, px: Int, fallback: (() -> Bitmap?)? = null, quick: (() -> Bitmap?)? = null, make: () -> Bitmap?) {
        iv.tag = key
        mem.get(key)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        val slow = Runnable {
            if (iv.tag != key) return@Runnable // già riciclata: non serve più
            val b = try { make() } catch (e: Throwable) { Log.w("151Storage", "miniatura $key", e); null }
            if (b != null) mem.put(key, b)
            val show = b ?: fallback?.invoke()
            main.post { if (iv.tag == key && show != null) { iv.setImageBitmap(show); fadeIn(iv) } }
        }
        if (quick == null) { exec.execute(slow); return }
        disk.execute {
            if (iv.tag != key) return@execute
            val b = try { quick() } catch (e: Throwable) { null }
            if (b == null) { exec.execute(slow); return@execute }
            mem.put(key, b)
            main.post { if (iv.tag == key) { iv.setImageBitmap(b); fadeIn(iv) } }
        }
    }
    /** Un po' di vita quando una miniatura arriva: dissolvenza breve, mai quando è già in memoria. */
    private fun fadeIn(iv: ImageView) { iv.alpha = 0f; iv.animate().alpha(1f).setDuration(170).start() }

    private val disk = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }).apply { allowCoreThreadTimeOut(true) }

    @Suppress("DEPRECATION")
    fun ofMedia(ctx: Context, m: MediaItem, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return try { ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), CancellationSignal()) } catch (_: Exception) { null }
        }
        val opts = BitmapFactory.Options()
        return if (m.isVideo) MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, m.id, MediaStore.Video.Thumbnails.MINI_KIND, opts)
        else MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, m.id, MediaStore.Images.Thumbnails.MINI_KIND, opts)
    }

    /** Miniatura di un file qualunque del telefono (Cartelle): foto, video. */
    fun ofFile(ctx: Context, f: File, px: Int): Bitmap? {
        val mime = Sniff.fixFile(mimeOf(f.name), f) // un video chiamato «.HEIC» restava nero
        return if (mime.startsWith("video/")) {
            if (Build.VERSION.SDK_INT >= 29) try { android.media.ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null) } catch (_: Exception) { null }
            else @Suppress("DEPRECATION") android.media.ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
        } else if (mime.startsWith("image/")) {
            if (Build.VERSION.SDK_INT >= 29) try { android.media.ThumbnailUtils.createImageThumbnail(f, Size(px, px), null) } catch (_: Exception) { null }
                ?: byContent(f, px) // ThumbnailUtils sceglie la strada dal nome: una JPEG chiamata «.HEIC» non usciva
            else decodeSampled(f.path, px)?.let { b -> // Android 8-9: l'EXIF non lo applica nessuno, la foto in piedi va girata qui
                val rot = try { when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } catch (_: Exception) { 0f }
                if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
            }
        } else null
    }

    /** La foto letta dal suo contenuto (ImageDecoder guarda i byte e gira secondo l'EXIF), ridotta al lato [px]. */
    private fun byContent(f: File, px: Int): Bitmap? = if (Build.VERSION.SDK_INT < 28) null else try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            val s = minOf(info.size.width, info.size.height).toFloat() / px
            if (s > 1f) d.setTargetSize((info.size.width / s).toInt().coerceAtLeast(1), (info.size.height / s).toInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) { null }

    /** Foto intera, ridotta al lato [maxPx], girata secondo l'EXIF. */
    fun decodeFull(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width; val h = info.size.height
                val s = maxOf(w, h).toFloat() / maxPx
                if (s > 1f) d.setTargetSize((w / s).toInt().coerceAtLeast(1), (h / s).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = try { ctx.contentResolver.openInputStream(uri)?.use { when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 } } ?: 0 } catch (_: Exception) { 0 }
        return if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    }

    private fun decodeSampled(path: String, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun mimeOf(name: String): String {
        val ext = Resolver.extOf(name)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) { "heic", "heif" -> "image/heic"; "mkv" -> "video/x-matroska"; "lrv" -> "video/mp4"; else -> "application/octet-stream" }
    }

    fun isMedia(name: String): Boolean = mimeOf(name).let { it.startsWith("image/") || it.startsWith("video/") } && Resolver.extOf(name) !in setOf("svg", "tif", "tiff", "psd")
}
