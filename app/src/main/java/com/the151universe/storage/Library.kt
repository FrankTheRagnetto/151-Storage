package com.the151universe.storage

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Filtri della libreria. [label] = testo del chip, nell'ordine dei chip.
 * v5: niente piu' foto e video (decisione dell'utente: 151 Viewer e' per "tutti gli altri file").
 * L'audio resta, ultimo e fuori da «Tutti».
 */
/**
 * Le linguette, nell'ordine in cui compaiono (v6.1): solo quelle che si capiscono a colpo
 * d'occhio. Un foglio di calcolo sta con Excel e un testo con Word anche se viene da LibreOffice o da Apple;
 * email, ebook, fumetti, disegni e il resto vanno in «Altro» (si aprono come prima, cambia solo dove stanno).
 */
enum class Cat(val label: String) {
    ALL("All"), PDF("PDF"), WORD("Word"), EXCEL("Excel"), PPT("PowerPoint"), AUDIO("Audio"), TEXT("Text"),
    ARCHIVE("Archives"), OTHER("Other");

    /** L'audio puo' essere migliaia di brani: sta solo nel suo chip, non in «Tutti». */
    val isMedia get() = this == AUDIO
}

class LibFile(val path: String, val size: Long, val mtime: Long) {
    val name: String = path.substringAfterLast('/')
    val ext: String = Resolver.extOf(name)
    val cat: Cat = Library.catOf(ext) ?: Cat.OTHER
    val nameKey: String = name.lowercase(Locale.ROOT)
    val folder: String by lazy { Storage.folderLabel(path) }
}

/**
 * «Tutti i documenti»: elenco dei file che 151 Viewer sa aprire, su TUTTO lo storage condiviso.
 * 1) cache su disco (apertura istantanea), 2) MediaStore.Files in una query, 3) giro del file
 * system in background per quello che MediaStore non ha indicizzato. Salta Android/data,
 * Android/obb, le cartelle nascoste e .thumbnails. Niente di tutto questo gira sul thread principale.
 */
object Library {
    private const val TAG = "151Viewer"
    private const val CACHE = "library.bin"
    /** quando si è fatto l'ultimo giro del disco e com'era MediaStore ([Freni]) */
    private const val FRENI = "library_freni"
    /** v5 = 2: la cache della v4 conteneva foto e video, e si butta (vedi [migrate]). */
    private const val CACHE_VERSION = 2

    private val EXT_CAT: Map<String, Cat> = buildMap {
        fun put(c: Cat, vararg e: String) = e.forEach { put(it, c) }
        put(Cat.PDF, "pdf")
        put(Cat.WORD, "doc", "docx", "docm", "dot", "dotx", "odt", "ott", "rtf", "pages")
        put(Cat.EXCEL, "xls", "xlsx", "xlsm", "xlsb", "xltx", "ods", "fods", "csv", "tsv", "numbers")
        put(Cat.PPT, "ppt", "pptx", "pptm", "pps", "ppsx", "potx", "odp", "key")
        // testo semplice, note, pagine web e codice: tutto quello che si legge come testo
        put(Cat.TEXT, "txt", "text", "md", "markdown", "log", "html", "htm", "xhtml", "mht", "mhtml", "srt", "vtt",
            "tex", "bib", "rst", "org", "adoc", "asciidoc", "nfo",
            "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env",
            "sql", "diff", "patch", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts",
            "java", "gradle", "py", "sh", "bash", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "lua", "swift",
            "dart", "css", "scss", "gd", "ps1", "bat", "dockerfile", "makefile", "cmake", "vue", "svelte")
        put(Cat.ARCHIVE, "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "apk")
        put(Cat.OTHER, "svg", "tif", "tiff", "psd", "eps", "ai", "xps", "oxps", "drawio", "dio", "excalidraw",
            "url", "webloc", "desktop", "torrent", "pem", "crt", "cer", "p7m", "p7s", "ttf", "otf", "woff", "woff2",
            "eml", "msg", "vcf", "ics", "epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "cb7",
            "ipynb", "geojson", "gpx", "kml", "kmz")
        put(Cat.AUDIO, "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "mid", "midi")
    }

    /** Categoria per estensione, o null se 151 Viewer non sa aprire quel tipo (il file non entra). */
    fun catOf(ext: String): Cat? = EXT_CAT[ext]

    /** Il percorso va escluso? Cartelle nascoste, Android/data, Android/obb, .thumbnails, file nascosti. */
    fun skipPath(path: String): Boolean {
        if (!Storage.isShared(path)) return true
        return path.contains("/.") // qualunque segmento nascosto (compresi .thumbnails, .trashed-…, .pending-…)
    }

    // ------------------------------------------------------------ stato condiviso

    @Volatile var files: List<LibFile> = emptyList()
        private set
    @Volatile var loadedFromCache = false
        private set
    @Volatile var scanning = false
        private set
    @Volatile var lastScanEnd = 0L
        private set
    private val generation = AtomicInteger()
    @Volatile private var forzaDopo = false
    // cancellati e rimessi MENTRE una scansione gira (lei li ha visti com'erano prima): Freni.afterScan
    private val removedDuring: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val restoredDuring = java.util.concurrent.ConcurrentHashMap<String, LibFile>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "libreria").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyAll_() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** Legge la cache (una volta) e poi, se serve, rifà la scansione. Chiamabile dal thread principale. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!Storage.hasAccess(app)) return
        if (scanning) { if (force) forzaDopo = true; return } // chiesta durante una scansione, si rifà alla fine
        if (!force && loadedFromCache && System.currentTimeMillis() - lastScanEnd < 30_000) return
        removedDuring.clear(); restoredDuring.clear()
        scanning = true
        val gen = generation.incrementAndGet()
        notifyAll_()
        exec.execute {
            try {
                if (!loadedFromCache) {
                    val cached = readCache(app)
                    loadedFromCache = true
                    if (cached != null && files.isEmpty()) { files = cached; notifyAll_() }
                }
                // a ogni ritorno nell'app si rifacevano MediaStore intero e il giro di tutto il disco. Ora
                // MediaStore solo se è cambiato (la sua «generazione»), il disco al più ogni 6 ore; «aggiorna» (force)
                // rifà tutto come prima
                val fr = app.getSharedPreferences(FRENI, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val token = MediaGen.token(app)
                val walkDue = force || files.isEmpty() || Freni.walkDue(now, fr.getLong("walk_at", 0))
                if (!walkDue && !Freni.mediaChanged(token, fr.getString("ms_token", null))) {
                    Log.i(TAG, "libreria: niente di cambiato, nessuna scansione")
                    return@execute
                }
                val t0 = System.currentTimeMillis()
                val fromMs = queryMediaStore(app)
                if (gen != generation.get()) return@execute
                // se la cache era vuota (primo giro) mostriamo subito quello che dice MediaStore
                if (files.isEmpty()) { files = fromMs.values.toList(); notifyAll_() }
                val t1 = System.currentTimeMillis()
                val walked = if (walkDue) walk(app) else null
                if (gen != generation.get()) return@execute
                val nuovi = if (walked != null) {
                    // il giro del disco e' la verita': un file che non c'e' piu' sparisce anche se MediaStore lo ricorda
                    // (se il giro non ha visto niente ma MediaStore si', il disco non era leggibile: meglio MediaStore)
                    if (walked.isNotEmpty()) fr.edit().putLong("walk_at", now).apply()
                    (if (walked.isEmpty()) fromMs else walked).values.toList()
                } else {
                    // solo MediaStore: i suoi file come dice lui, gli altri (visti dal giro del disco) se ci sono ancora
                    fromMs.values.toList() + files.filter { it.path !in fromMs && File(it.path).exists() }
                }
                files = Freni.afterScan(nuovi, { it.path }, removedDuring, restoredDuring.values.toList()) { File(it).exists() }
                if (token != null && fromMs.isNotEmpty()) fr.edit().putString("ms_token", token).apply()
                val t2 = System.currentTimeMillis()
                Log.i(TAG, "libreria: MediaStore ${fromMs.size} in ${t1 - t0} ms, disco ${walked?.size ?: "saltato"} in ${t2 - t1} ms")
                writeCache(app, files)
            } catch (e: Throwable) {
                Log.w(TAG, "scansione libreria fallita", e)
            } finally {
                if (gen == generation.get()) {
                    scanning = false
                    lastScanEnd = System.currentTimeMillis()
                    notifyAll_()
                    if (forzaDopo) { forzaDopo = false; android.os.Handler(android.os.Looper.getMainLooper()).post { refresh(app, force = true) } }
                }
            }
        }
    }

    /** Toglie un file dall'elenco (dopo la cancellazione) senza aspettare la prossima scansione. */
    fun remove(ctx: Context, path: String) {
        val cur = files
        if (cur.none { it.path == path }) return
        files = cur.filter { it.path != path }
        if (scanning) { removedDuring += path; restoredDuring.remove(path) }
        val app = ctx.applicationContext
        exec.execute { if (!scanning) writeCache(app, files) }
        notifyAll_()
    }

    /** Tanti insieme: una lista nuova e una scrittura sola (una cartella di migliaia di file). */
    fun removeMany(ctx: Context, paths: Set<String>) {
        val cur = files
        if (cur.none { it.path in paths }) return
        files = cur.filter { it.path !in paths }
        if (scanning) { removedDuring += paths; paths.forEach { restoredDuring.remove(it) } }
        val app = ctx.applicationContext
        exec.execute { if (!scanning) writeCache(app, files) }
        notifyAll_()
    }

    /** Rimette un file (Annulla) leggendolo dal disco. */
    fun restore(ctx: Context, path: String) {
        val f = File(path)
        if (!f.isFile || skipPath(path) || catOf(Resolver.extOf(f.name)) == null) return
        val lf = LibFile(path, f.length(), f.lastModified())
        files = files.filter { it.path != path } + lf
        if (scanning) { restoredDuring[path] = lf; removedDuring -= path }
        val app = ctx.applicationContext
        exec.execute { if (!scanning) writeCache(app, files) }
        notifyAll_()
    }

    // ------------------------------------------------------------ MediaStore

    @Suppress("DEPRECATION")
    private fun queryMediaStore(ctx: Context): Map<String, LibFile> {
        val out = HashMap<String, LibFile>(4096)
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        try {
            val c = if (Build.VERSION.SDK_INT >= 30) {
                ctx.contentResolver.query(uri, proj, Bundle(), null)
            } else ctx.contentResolver.query(uri, proj, null, null, null)
            c?.use {
                val di = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val si = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mi = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (it.moveToNext()) {
                    val p = it.getString(di) ?: continue
                    val ext = Resolver.extOf(p.substringAfterLast('/'))
                    if (catOf(ext) == null || skipPath(p)) continue
                    out[p] = LibFile(p, it.getLong(si), it.getLong(mi) * 1000)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore non leggibile", e)
        }
        return out
    }

    // ------------------------------------------------------------ file system

    private fun walk(ctx: Context): Map<String, LibFile> {
        val out = HashMap<String, LibFile>(4096)
        for (root in Storage.roots(ctx)) {
            val stack = ArrayDeque<File>()
            stack.addLast(root)
            val rootPath = root.absolutePath
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val kids = dir.listFiles() ?: continue
                for (f in kids) {
                    val n = f.name
                    if (n.startsWith('.')) continue
                    if (f.isDirectory) {
                        if (dir.absolutePath == "$rootPath/Android" && (n == "data" || n == "obb")) continue
                        stack.addLast(f)
                    } else {
                        if (catOf(Resolver.extOf(n)) == null) continue
                        val p = f.absolutePath
                        out[p] = LibFile(p, f.length(), f.lastModified())
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ cache

    private fun readCache(ctx: Context): List<LibFile>? = try {
        val f = File(ctx.filesDir, CACHE)
        if (!f.isFile) null else DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { d ->
            if (d.readInt() != CACHE_VERSION) return null
            val n = d.readInt()
            ArrayList<LibFile>(n).apply {
                repeat(n) {
                    val p = d.readUTF(); val sz = d.readLong(); val mt = d.readLong()
                    if (catOf(Resolver.extOf(p.substringAfterLast('/'))) != null) add(LibFile(p, sz, mt))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "cache libreria illeggibile", e)
        null
    }

    private fun writeCache(ctx: Context, list: List<LibFile>) {
        try {
            val tmp = File(ctx.filesDir, "$CACHE.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { d ->
                d.writeInt(CACHE_VERSION)
                d.writeInt(list.size)
                for (x in list) { d.writeUTF(x.path); d.writeLong(x.size); d.writeLong(x.mtime) }
            }
            tmp.renameTo(File(ctx.filesDir, CACHE))
        } catch (e: Exception) {
            Log.w(TAG, "cache libreria non scritta", e)
        }
    }

    fun hasCache(ctx: Context) = File(ctx.filesDir, CACHE).exists()

    /**
     * Primo avvio di una versione nuova (v5): via la cache vecchia (conteneva foto e video) e via dai
     * recenti le foto e i video. Gira una volta sola, prima che la libreria si legga.
     */
    fun migrate(ctx: Context) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getInt("libSchema", 1) >= CACHE_VERSION) return
        File(ctx.filesDir, CACHE).delete()
        File(ctx.filesDir, "$CACHE.tmp").delete()
        Recents.dropNonDocuments(ctx)
        prefs.edit().putInt("libSchema", CACHE_VERSION)
            .apply { if (prefs.getString("libCat", null) in setOf("IMAGE", "VIDEO")) putString("libCat", Cat.ALL.name) }
            .apply()
    }

    /** Svuota tutto (permesso revocato): la cache non deve mostrare file che non possiamo piu' leggere. */
    fun forget(ctx: Context) {
        generation.incrementAndGet()
        files = emptyList()
        scanning = false
        loadedFromCache = false
        File(ctx.filesDir, CACHE).delete()
        ctx.getSharedPreferences(FRENI, Context.MODE_PRIVATE).edit().clear().apply()
        notifyAll_()
    }
}

/**
 * Com'è MediaStore adesso, in una riga: versione + «generazione» di ogni memoria. Cambia quando un file è
 * aggiunto, tolto o cambiato; uguale = non serve rileggere tutto. null prima di Android 11 (lì si rilegge come prima).
 */
object MediaGen {
    fun token(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            MediaStore.getVersion(ctx) + "|" + MediaStore.getExternalVolumeNames(ctx).sorted().joinToString(",") { it + "=" + MediaStore.getGeneration(ctx, it) }
        } catch (_: Exception) { null }
    }
}
