package com.the151universe.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * Anteprime delle foto e dei video del cloud, fatte DAL TELEFONO: niente PC, niente server di supporto,
 * nulla scritto nel cloud. Funziona su qualunque [CloudDrive]. Restano sul telefono in `files/cloud-thumbs/`
 * (non in cache: Android non le butta), una JPEG da 512 px per file. Il nome viene dal PERCORSO (così «A caso», Oggi,
 * Preferiti e le cartelle trovano la stessa anteprima); la data del file del cloud è la data dell'anteprima sul telefono:
 * se nel cloud il file cambia, l'anteprima si rifà.
 *
 * Come si fa, dal più leggero:
 *  1. c'è già la copia del file sul telefono («Sul telefono» o aperto prima) → da lì, zero rete;
 *  2. JPEG: i primi 256 KB, dentro c'è quasi sempre la miniatura della fotocamera (EXIF), girata giusta;
 *  3. video: si legge in streaming solo il pezzo che serve per un fotogramma (non il video intero);
 *  4. il resto (HEIC, PNG, JPEG senza miniatura, video che non si legge a pezzi): il file intero in un provvisorio,
 *     anteprima, provvisorio cancellato subito. Oltre [MAX_FULL] byte non si scarica: resta l'icona.
 */
object CloudThumbs {
    private const val TAG = "151Storage"
    /** Lato lungo dell'anteprima salvata: nitida in griglia anche sui telefoni da 1440. */
    const val SIDE = 512
    private const val QUALITY = 88
    /** La miniatura della fotocamera si usa solo se è almeno così (Samsung 512×384 sì, iPhone 160×120 no). */
    private const val EXIF_MIN = 480
    private const val HEAD = 256 * 1024
    const val MAX_FULL = 60L * 1024 * 1024
    private const val SNIFF_FROM = 1_794_441_600_000L // 12/11/2026 00:00 UTC, vigilia della 0.10.35
    private const val PREFS = "cloud_thumbs"

    // ---- regole dell'utente (08/10): anche coi dati; nessun tetto di spazio («ho 1 TB sul telefono»); a ogni apertura
    // dell'app si passa TUTTO il cloud finché non ci sono tutte, poi solo le nuove ----
    /** Si smette di scrivere se sul telefono restano meno di 500 MB liberi: le anteprime non riempiono mai il telefono. */
    private const val MIN_FREE = 500L * 1024 * 1024
    /** Un giro completo riparte solo se l'ultimo è finito da più di 10 minuti (tornare dal visore non è «aprire l'app»). */
    const val SWEEP_GAP = Freni.SWEEP_GAP

    fun dir(ctx: Context) = File(ctx.filesDir, "cloud-thumbs")
    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively(); failed.clear(); VideoLength.forget()
        // via anche i freni del giro: ricollegato lo stesso cloud, le anteprime si rifanno tutte e subito
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("sweep_done").remove("sweep_tried").remove("sweep_who").remove("sweep_print").remove("sweep_print_who").apply()
    }

    /** File che non si sono lasciati fare in questa apertura dell'app: non si riprova a ogni scorrimento. */
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    /** Anteprime in lavorazione adesso (lo schermo o il giro): il giro salta quelle già in mano allo schermo. */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun fileFor(ctx: Context, path: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(path.trim('/').toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(dir(ctx), "$h.jpg")
    }
    fun noneFor(f: File) = File(f.parentFile, f.name.removeSuffix(".jpg") + ".none")
    /** «Da qui non esce» vale ancora? No per le «foto» oltre [MAX_FULL] segnate prima della 0.10.35: erano video col nome
     * di una foto e ora si leggono a pezzi; si riprovano una volta sola, senza scaricarle intere. */
    private fun noneHolds(none: File, e: CloudEntry): Boolean = none.isFile && fresh(none, e.mtime) &&
        !(none.lastModified() < SNIFF_FROM && e.size > MAX_FULL && !Thumbs.mimeOf(e.name).startsWith("video/"))
    /** Quante volte di fila l'anteprima di questo file è fallita (rete, server, file che fa cadere il lettore): il conto
     * è il contenuto, l'ora è quella del file. A [Freni.FAIL_MAX] il giro lo salta per [Freni.FAIL_PAUSE]. */
    fun failFor(f: File) = File(f.parentFile, f.name.removeSuffix(".jpg") + ".fail")

    private fun failCount(m: File): Int = try { if (m.isFile) m.readText().trim().toIntOrNull() ?: 1 else 0 } catch (_: Exception) { 0 }

    /** Un fallimento in più per [f]; non conta se il lavoro è stato fermato (Android, app chiusa): il file non ha colpe. */
    private fun noteFail(f: File, x: Throwable) {
        if (x is java.io.InterruptedIOException || x is InterruptedException || Thread.currentThread().isInterrupted) return
        try {
            val m = failFor(f)
            val n = Freni.nextFailCount(failCount(m), System.currentTimeMillis() - m.lastModified())
            m.parentFile?.mkdirs()
            m.writeText(n.toString())
        } catch (_: Exception) {}
    }

    /** Il giro salta questo file per ora? (fallito [Freni.FAIL_MAX] volte negli ultimi [Freni.FAIL_PAUSE]) */
    fun failedTooOften(f: File): Boolean {
        val m = failFor(f)
        return m.isFile && Freni.failBlocks(failCount(m), System.currentTimeMillis() - m.lastModified())
    }

    /** L'anteprima è di questa versione del file? (data sconosciuta = sì) */
    /** Un'anteprima fatta non si rifà MAI, salvo che il file nel cloud sia stato cambiato DOPO di lei (
     *  «le avevo già scaricate, perché vengono rifatte?»). Prima bastava una data diversa di 2 secondi: se il telefono
     *  non lascia scrivere la data sull'anteprima, la si rifaceva ogni volta. */
    fun fresh(f: File, mtime: Long) = mtime <= 0 || mtime <= f.lastModified() + 2000

    /** Già pronta sul telefono? Per il visore, che la mostra subito mentre arriva la foto intera. */
    /** La via veloce dello schermo: l'anteprima se è già sul telefono (solo disco, nessuna rete), altrimenti null. */
    fun onPhone(ctx: Context, e: CloudEntry): Bitmap? {
        if (!Thumbs.isMedia(e.name)) return null
        val f = fileFor(ctx, e.path)
        return if (f.isFile && fresh(f, e.mtime)) BitmapFactory.decodeFile(f.path) else null
    }

    /** C'è già l'anteprima di questo percorso degli indici? Per «Random». L'elenco si rilegge ogni 5 s. */
    fun onPhoneAt(ctx: Context, path: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - namesAt > 5000) { names = dir(ctx).list()?.toHashSet() ?: HashSet(); namesAt = now }
        return fileFor(ctx, path.trim('/')).name in names
    }
    @Volatile private var names: Set<String> = emptySet()
    @Volatile private var namesAt = 0L

    fun ready(ctx: Context, e: CloudEntry): Bitmap? = fileFor(ctx, e.path).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** L'anteprima di [e], fatta se serve. null = non c'è (icona del tipo). Da chiamare fuori dal thread principale. */
    fun get(ctx: Context, drive: CloudDrive, e0: CloudEntry): Bitmap? {
        if (!Thumbs.isMedia(e0.name)) return null
        val f = fileFor(ctx, e0.path)
        if (f.isFile && fresh(f, e0.mtime)) BitmapFactory.decodeFile(f.path)?.let { return it }
        val key = f.name
        val none = noneFor(f)
        if (key in failed || noneHolds(none, e0)) return null
        inFlight += key
        var e = e0
        val b = try {
            // dagli indici (A caso, Oggi, Preferiti, ricerca) la voce non ha né dimensione né data:
            // senza dimensione un video si scaricava INTERO (fermando tutte le altre anteprime) → si chiedono al cloud
            if (e.size < 0) drive.info(e.ref)?.let { real -> e = e.copy(size = real.size, mtime = real.mtime) }
            CloudFiles.ready(ctx, e)?.let { local -> CloudList.note(ctx, e, CloudList.fileDate(local)); VideoLength.ofLocal(ctx, e.path, local); fromFile(ctx, local) } ?: make(ctx, drive, e)
        } catch (x: Exception) {
            // rete, server, password: si riprova alla prossima apertura dell'app, non a ogni scorrimento
            Log.w(TAG, "anteprima del cloud ${e.name}: ${x.message}")
            failed += key
            noteFail(f, x)
            return null
        } catch (x: Throwable) { Log.w(TAG, "anteprima del cloud ${e.name}", x); failed += key; noteFail(f, x); return null }
        finally { inFlight -= key }
        if (b == null) {
            // il file c'è ma da lui un'anteprima non esce (HEIC su Android 8, file rotto, file enorme senza indice
            // leggibile): lo si ricorda sul telefono, o lo si riscaricherebbe a ogni apertura
            try { none.parentFile?.mkdirs(); none.createNewFile(); if (e.mtime > 0) none.setLastModified(e.mtime) } catch (_: Exception) {}
            return null
        }
        val small = fit(b)
        save(ctx, f, small, e.mtime)
        none.delete()
        failFor(f).delete()
        return small
    }

    /** Per il giro: c'è già un'anteprima (o un «da qui non esce») per questa versione del file? */
    fun done(ctx: Context, e: CloudEntry): Boolean {
        val f = fileFor(ctx, e.path)
        return (f.isFile && fresh(f, e.mtime)) || noneHolds(noneFor(f), e) || failedTooOften(f)
    }
    fun busy(ctx: Context, e: CloudEntry) = fileFor(ctx, e.path).name in inFlight
    fun failedNow(ctx: Context, e: CloudEntry) = fileFor(ctx, e.path).name in failed

    /** null = da questo file un'anteprima non esce; eccezione = rete o server (si riproverà). */
    private fun make(ctx: Context, drive: CloudDrive, e: CloudEntry): Bitmap? {
        val mime = Thumbs.mimeOf(e.name)
        val ext = Resolver.extOf(e.name)
        return when {
            mime.startsWith("video/") -> videoStream(ctx, drive, e) ?: whole(ctx, drive, e)
            ext == "jpg" || ext == "jpeg" -> exifThumb(ctx, drive, e) ?: whole(ctx, drive, e)
            else -> whole(ctx, drive, e)
        // solo se la foto non esce: forse è un video col nome di una foto (due «.HEIC» da 155-234 MB); a pezzi,
        // mai intero. Chi va già non paga niente in più.
        } ?: if (!mime.startsWith("video/")) videoStream(ctx, drive, e) else null
    }

    /** La miniatura che la fotocamera mette nell'EXIF, dai primi [HEAD] byte; girata come la foto. */
    private fun exifThumb(ctx: Context, drive: CloudDrive, e: CloudEntry): Bitmap? {
        val head = drive.open(e, 0, (HEAD - 1).toLong()).use { readAtMost(it, HEAD) }
        val ex = try { ExifInterface(ByteArrayInputStream(head)) } catch (_: Exception) { return null }
        CloudList.note(ctx, e, CloudList.exifDate(ex)) // la data di scatto è nei primi byte: gratis
        val t = try { ex.thumbnailBitmap } catch (_: Exception) { null } ?: return null
        if (maxOf(t.width, t.height) < EXIF_MIN) return null // francobollo da 160 px: sfocato, meglio la foto vera
        return rotate(t, ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
    }

    /**
     * Un fotogramma del video letto a pezzi dal server: il lettore di Android chiede i byte che gli servono (l'indice,
     * anche se sta in fondo, e un fotogramma) e noi li prendiamo a blocchi da 256 KB. Col suo lettore di rete
     * (setDataSource con l'indirizzo) ne scaricava decine di MB per un video grande: misurato 29 MB su 76 il 08/10.
     */
    private fun videoStream(ctx: Context, drive: CloudDrive, e: CloudEntry): Bitmap? {
        if (e.size <= 0) return null
        val r = MediaMetadataRetriever()
        val src = RangeSource(drive, e)
        try {
            r.setDataSource(src)
            VideoLength.fromPlayer(ctx, e.path, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
            CloudList.note(ctx, e, CloudList.videoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE), src.qt))
            return frame(r)
        } catch (x: RuntimeException) {
            src.error?.let { throw it } // la rete, non il video
            return null
        } finally { try { r.release() } catch (_: Exception) {}; src.close() }
    }

    private class RangeSource(val drive: CloudDrive, val e: CloudEntry) : android.media.MediaDataSource() {
        private val block = 256 * 1024
        private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 12
        }
        override fun getSize() = e.size
        override fun close() { blocks.clear() }
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= e.size) return -1
            var done = 0
            while (done < size && position + done < e.size) {
                val p = position + done
                val idx = p / block
                val data = blocks[idx] ?: fetch(idx).also { blocks[idx] = it }
                val inBlock = (p - idx * block).toInt()
                if (inBlock >= data.size) break
                val n = minOf(size - done, data.size - inBlock)
                System.arraycopy(data, inBlock, buffer, offset + done, n)
                done += n
            }
            return if (done == 0) -1 else done
        }
        @Volatile var error: Exception? = null
        /** La data col fuso scritta dentro il video, vista passando (il lettore legge la «moov» comunque): zero rete in più. */
        @Volatile var qt: String? = null
        private fun fetch(idx: Long): ByteArray {
            val start = idx * block
            val end = minOf(e.size, start + block) - 1
            return try { drive.open(e, start, end).use { readAtMost(it, (end - start + 1).toInt()) }.also { if (qt == null) qt = CloudList.qtDate(it) } }
            catch (x: Exception) { error = x; throw java.io.IOException(x.message) }
        }
    }

    private fun frame(r: MediaMetadataRetriever): Bitmap? {
        val at = 1_000_000L.coerceAtMost(((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000) / 2)
        return if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIDE, SIDE)
        else r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    /** Il file intero in un provvisorio (mai col nome buono), anteprima, provvisorio cancellato. */
    private fun whole(ctx: Context, drive: CloudDrive, e: CloudEntry): Bitmap? {
        if (e.size < 0 || e.size > MAX_FULL) return null // dimensione ignota: mai scaricare alla cieca
        val tmp = File(ctx.cacheDir, "cthumb-tmp").apply { mkdirs() }
        val part = File(tmp, "${android.os.Process.myTid()}-${System.nanoTime()}." + Resolver.extOf(e.name))
        try {
            var got = 0L
            drive.open(e).use { input -> part.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); got += n }
            } }
            if (e.size >= 0 && got != e.size) return null
            CloudList.note(ctx, e, CloudList.fileDate(part))
            if (Sniff.fixFile(Thumbs.mimeOf(e.name), part).startsWith("video/")) {
                val r = MediaMetadataRetriever()
                return try { r.setDataSource(part.path); VideoLength.fromPlayer(ctx, e.path, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); frame(r) } catch (_: RuntimeException) { null } finally { try { r.release() } catch (_: Exception) {} }
            }
            return fromFile(ctx, part)
        } finally { part.delete() }
    }

    /**
     * Dalla foto vera, mai dalla miniatura della fotocamera: `ThumbnailUtils` prende quella anche se è da 160 px
     * (misurato 08/10: tre anteprime da 160-320 px). ImageDecoder (Android 9+) legge anche HEIC e gira secondo l'EXIF.
     */
    private fun fromFile(ctx: Context, f: File): Bitmap? {
        if (Sniff.fixFile(Thumbs.mimeOf(f.name), f).startsWith("video/") || Build.VERSION.SDK_INT < 28) return Thumbs.ofFile(ctx, f, SIDE)
        return try {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f)) { d, info, _ ->
                val s = maxOf(info.size.width, info.size.height).toFloat() / SIDE
                if (s > 1f) d.setTargetSize((info.size.width / s).toInt().coerceAtLeast(1), (info.size.height / s).toInt().coerceAtLeast(1))
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (x: Exception) { Log.w(TAG, "anteprima: ${f.name} non si legge: ${x.message}"); null }
    }

    fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(max)
        val buf = ByteArray(32 * 1024)
        while (out.size() < max) { val n = input.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray() // se il server ignora il Range, ci si ferma qui lo stesso
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val deg = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        return if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }

    private fun fit(b: Bitmap): Bitmap {
        val s = maxOf(b.width, b.height).toFloat() / SIDE
        return if (s <= 1f) b else Bitmap.createScaledBitmap(b, (b.width / s).toInt().coerceAtLeast(1), (b.height / s).toInt().coerceAtLeast(1), true)
    }

    /** Scritta atomica (provvisorio col PID accanto, poi rename). Con meno di [MIN_FREE] liberi non si scrive. */
    private fun save(ctx: Context, f: File, b: Bitmap, mtime: Long) {
        try {
            f.parentFile?.mkdirs()
            if (ctx.filesDir.usableSpace < MIN_FREE) return
            val t = File(f.parentFile, f.name + "." + android.os.Process.myPid() + "." + android.os.Process.myTid() + ".tmp")
            t.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (mtime > 0) t.setLastModified(mtime) // la data del file del cloud: se lassù cambia, si rifà
            if (!t.renameTo(f)) t.delete()
        } catch (x: Exception) { Log.w(TAG, "anteprima non salvata: ${x.message}") }
    }

    fun lowSpace(ctx: Context) = ctx.filesDir.usableSpace < MIN_FREE
}

/**
 * La durata dei video del cloud, ricordata sul telefono
 * come le anteprime, per nome dell'anteprima ([CloudThumbs.fileFor]): `files/cloud-thumbs/durations.tsv`.
 * Si prende gratis mentre si fa l'anteprima (il lettore di Android legge comunque l'indice del video); per i video che
 * avevano già l'anteprima, [read] legge SOLO la scatola «mvhd» (misurato su un cloud vero: ~4 richieste e ~7 KB
 * a video, invece del video). -1 = non si sa (non si riprova).
 */
object VideoLength {
    private const val FILE = "durations.tsv"
    private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    @Volatile private var savedAt = 0L

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(map) {
            if (loaded) return
            var v2 = false
            try { File(CloudThumbs.dir(ctx), FILE).takeIf { it.isFile }?.forEachLine { l ->
                if (l == HEADER) v2 = true
                else l.split('\t').takeIf { it.size == 2 }?.let { map[it[0]] = it[1].toLongOrNull() ?: -1 } } }
            catch (_: Exception) {}
            // fino alla 0.10.14 un «non si sa» del lettore di Android restava per sempre (i .MOV dell'iPhone
            // senza durata): una volta, i «non si sa» si dimenticano e il giro li rilegge dall'intestazione del video
            if (!v2 && map.values.removeAll { it <= 0 }) dirty = true
            if (!v2) dirty = true
            loaded = true
        }
    }
    fun forget() { map.clear(); loaded = false }

    private fun key(ctx: Context, path: String) = CloudThumbs.fileFor(ctx, path).name.removeSuffix(".jpg")
    /** In millisecondi; null = non letta ancora; -1 = non si sa. */
    fun get(ctx: Context, path: String): Long? { load(ctx); return map[key(ctx, path)] }
    fun known(ctx: Context, path: String) = get(ctx, path) != null
    private const val HEADER = "#v2"
    /** Dal lettore di Android mentre si fa l'anteprima: solo un valore vero. Vuoto o 0 (succede coi .MOV dell'iPhone)
     * NON vuol dire «non si sa»: lo legge poi il giro dall'intestazione del video ([read]). */
    fun fromPlayer(ctx: Context, path: String, raw: String?) { raw?.toLongOrNull()?.takeIf { it > 0 }?.let { put(ctx, path, it) } }
    /** Quante durate sa e quante no, per tipo di file: per capire da qui cosa succede sul telefono. */
    fun summary(ctx: Context): String {
        load(ctx); val ok = map.values.count { it > 0 }; val no = map.size - ok
        return "$ok video lengths known" + if (no > 0) ", $no unknown" else ""
    }
    fun put(ctx: Context, path: String, ms: Long) {
        load(ctx); map[key(ctx, path)] = if (ms > 0) ms else -1; dirty = true
        if (System.currentTimeMillis() - savedAt > 5000) save(ctx)
    }
    /** Da una copia sul telefono (cache o «Sul telefono»): il lettore di Android sul file, nessuna rete. */
    fun ofLocal(ctx: Context, path: String, f: File) {
        if (!Thumbs.mimeOf(f.name).startsWith("video/") || known(ctx, path)) return
        // MP4/MOV: l'intestazione «mvhd» del file, come dal cloud; il lettore di Android solo per gli altri formati
        if (Resolver.extOf(f.name) in MP4) try {
            mvhd(f.length()) { pos, len -> java.io.RandomAccessFile(f, "r").use { raf -> raf.seek(pos); ByteArray(len).also { raf.readFully(it) } } }
                ?.let { put(ctx, path, it); return }
        } catch (_: Exception) {}
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(f.path); put(ctx, path, r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1) }
        catch (_: Exception) { put(ctx, path, -1) } finally { try { r.release() } catch (_: Exception) {} }
    }
    /** I nomi delle anteprime spostate ([CloudList.carryMoved]): la durata le segue. */
    fun renamed(ctx: Context, names: Map<String, String>) {
        load(ctx)
        for ((a, b) in names) map.remove(a.removeSuffix(".jpg"))?.let { map[b.removeSuffix(".jpg")] = it; dirty = true }
        save(ctx)
    }
    /** A fine giro: via le durate dei video spariti ([keep] = nomi delle anteprime che restano). */
    fun prune(ctx: Context, keep: Set<String>) {
        load(ctx); if (map.keys.removeAll { "$it.jpg" !in keep }) dirty = true; save(ctx)
    }
    @Synchronized fun save(ctx: Context) {
        if (!dirty) return
        dirty = false; savedAt = System.currentTimeMillis()
        try { CloudList.writeAtomic(File(CloudThumbs.dir(ctx), FILE), buildString { append(HEADER).append('\n'); for ((k, v) in map) append(k).append('\t').append(v).append('\n') }) }
        catch (x: Exception) { dirty = true }
    }

    /** «1:23», «1:02:45»; null se non si sa. */
    fun label(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        val s = (ms + 500) / 1000
        return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /**
     * Per un video che ha già l'anteprima: la durata dalla scatola «mvhd» (MP4/MOV/M4V), leggendo solo le intestazioni
     * delle scatole fino a «moov» e i primi 8 KB di lei. Gli altri formati (MKV, WebM, AVI): il lettore di Android a pezzi.
     * Eccezione = rete (si riprova la prossima volta); -1 = da qui non si sa.
     */
    private val MP4 = setOf("mp4", "mov", "m4v", "3gp", "lrv")

    /** La durata dalla scatola «mvhd» di un MP4/MOV lungo [size], leggendo i pezzi con [at] (posizione, lunghezza). */
    private fun mvhd(size: Long, at: (Long, Int) -> ByteArray): Long? {
        var pos = 0L; var hops = 0
        while (pos + 8 <= size && hops++ < 40) {
            val h = at(pos, minOf(16L, size - pos).toInt())
            if (h.size < 8) return null
            var sz = java.nio.ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
            val typ = String(h, 4, 4, Charsets.ISO_8859_1)
            if (sz == 1L && h.size >= 16) sz = java.nio.ByteBuffer.wrap(h, 8, 8).long else if (sz == 0L) sz = size - pos
            if (typ == "moov") {
                val b = at(pos, minOf(size - pos, 8192L).toInt())
                val i = String(b, Charsets.ISO_8859_1).indexOf("mvhd"); if (i < 4 || i + 36 > b.size) return null
                val bb = java.nio.ByteBuffer.wrap(b)
                val (ts, du) = if (b[i + 4].toInt() == 1) (bb.getInt(i + 24).toLong() and 0xffffffffL) to bb.getLong(i + 28)
                    else (bb.getInt(i + 16).toLong() and 0xffffffffL) to (bb.getInt(i + 20).toLong() and 0xffffffffL)
                return if (ts > 0 && du > 0) du * 1000 / ts else null
            }
            if (sz < 8) return null
            pos += sz
        }
        return null
    }

    fun read(ctx: Context, drive: CloudDrive, e: CloudEntry): Long {
        if (e.size <= 0) return -1
        if (Resolver.extOf(e.name) in MP4)
            return mvhd(e.size) { pos, len -> drive.open(e, pos, pos + len - 1).use { CloudThumbs.readAtMost(it, len) } } ?: -1
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(RangeLite(drive, e)); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1 }
        catch (_: RuntimeException) { -1 } finally { try { r.release() } catch (_: Exception) {} }
    }

    /**
     * Il video a pezzi da 256 KB, solo quelli che il lettore chiede (per MKV & co.: l'intestazione sta all'inizio).
     * ⚠️ Al più [KEEP] pezzi in memoria e [BUDGET] byte letti per file (2 MB: la durata di un MKV sta all'inizio; un .MTS senza indice resta «non si sa») (crash del 18/10 sul Samsung dell'utente, «OutOfMemory»:
     * prima ogni pezzo restava in memoria, e su un MKV o un .MTS grande il lettore di Android scorre tutto il file —
     * misurato sul telefono finto: da 54 a 187 MB in tre minuti, poi la chiusura). Oltre il budget la durata «non si sa».
     */
    private class RangeLite(val drive: CloudDrive, val e: CloudEntry) : android.media.MediaDataSource() {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > KEEP
        }
        private var fetched = 0L
        override fun getSize() = e.size
        override fun close() { blocks.clear() }
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= e.size) return -1
            val idx = position / BLOCK
            val data = blocks[idx] ?: run {
                if (fetched >= BUDGET) throw java.io.IOException("too much to read for a length")
                drive.open(e, idx * BLOCK, minOf(e.size, (idx + 1) * BLOCK) - 1).use { CloudThumbs.readAtMost(it, minOf(e.size - idx * BLOCK, BLOCK).toInt()) }
                    .also { fetched += it.size; blocks[idx] = it }
            }
            val inBlock = (position - idx * BLOCK).toInt(); if (inBlock >= data.size) return -1
            val n = minOf(size, data.size - inBlock); System.arraycopy(data, inBlock, buffer, offset, n); return n
        }
        companion object { const val BLOCK = 256 * 1024L; const val KEEP = 8; const val BUDGET = 2L * 1024 * 1024 }
    }
}
