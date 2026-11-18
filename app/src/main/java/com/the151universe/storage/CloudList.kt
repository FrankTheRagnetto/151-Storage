package com.the151universe.storage

import android.content.Context
import android.media.ExifInterface
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * La lista del cloud fatta DAL TELEFONO: quando l'app si apre fa media-index, list-cache, duplicates e la tiene SOLO sul
 * telefono in `files/index-phone/` (nel cloud non si scrive niente); [Index] li legge:
 *  - cosa c'è: una richiesta sola al server SFTP (`ls -lR`, [CloudDrive.listAll]); un cloud qualunque cartella per cartella;
 *  - le date di «Oggi negli anni»: quella DENTRO il file (EXIF, video) letta mentre si fa l'anteprima ([note]), poi
 *    nome, cartella AAAA-MM-GG, data del file; in tutto il cloud;
 *  - i doppioni: stessa dimensione, poi l'impronta md5 calcolata dal server (zero download); un
 *    cloud senza md5 confronta inizio e fine del file («quasi sicuri»).
 */
object CloudList {
    private const val TAG = "151Storage"
    private val IMAGE = Regex("""^(jpe?g|png|gif|webp|avif|bmp|heic|heif)$""", RegexOption.IGNORE_CASE)
    // .lrv = l'anteprima a bassa risoluzione della GoPro, un MP4 (15/11: 66 nel cloud, aperti come documenti)
    private val VIDEO = Regex("""^(mp4|mov|mkv|webm|avi|m4v|lrv)$""", RegexOption.IGNORE_CASE)

    fun dir(ctx: Context) = File(ctx.filesDir, "index-phone")
    fun forget(ctx: Context) { dir(ctx).deleteRecursively(); dates.clear(); datesLoaded = false; synchronized(lcLock) { lcMap = null; lcKey = "" } }

    // ------------------------------------------------------------ la lista di tutto il cloud, una copia sola ([ListFile])

    private val lcLock = Any()
    @Volatile private var lcMap: Map<String, List<ListFile.Kid>>? = null
    private var lcKey = ""
    fun listFile(ctx: Context) = File(dir(ctx), "list-cache.tsv")

    /**
     * Cartella (relativa alla base) → figli, letta una riga alla volta e tenuta UNA volta per ricerca, cartelle istantanee e
     * anteprime spostate. Riletta solo se il file cambia. La lista JSON di prima (fino alla 0.10.28) si trasforma una
     * volta, a pezzi ([migrate]). null = nessuna lista.
     */
    fun kids(ctx: Context): Map<String, List<ListFile.Kid>>? = synchronized(lcLock) {
        val f = listFile(ctx)
        if (!f.isFile && !migrate(ctx)) return@synchronized null
        val key = "${f.lastModified()}|${f.length()}"
        lcMap?.let { if (lcKey == key) return@synchronized it }
        lcMap = null // la vecchia non resta in memoria insieme alla nuova
        try { f.bufferedReader().use { ListFile.read(it) }.also { lcMap = it; lcKey = key } }
        catch (x: Throwable) { Log.w(TAG, "lista non letta: ${x.message}"); null }
    }

    /** `list-cache.json` (0.10.28 e prima) → `list-cache.tsv`, a flusso: mai tutto in memoria. */
    private fun migrate(ctx: Context): Boolean {
        val old = File(dir(ctx), "list-cache.json")
        if (!old.isFile) return false
        val out = File(dir(ctx), "list-cache.tsv." + android.os.Process.myPid() + ".tmp")
        return try {
            android.util.JsonReader(old.bufferedReader()).use { r -> out.bufferedWriter().use { w ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "entries") { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        val d = r.nextName()
                        r.beginArray()
                        while (r.hasNext()) {
                            var name = ""; var isDir = false; var size = -1L; var mtime = 0L
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "name" -> name = r.nextString(); "type" -> isDir = r.nextString() == "directory"
                                "size" -> size = r.nextLong(); "mtime" -> mtime = r.nextLong(); else -> r.skipValue()
                            }
                            r.endObject()
                            if (name.isNotEmpty()) ListFile.row(w, d, name, isDir, size, mtime)
                        }
                        r.endArray()
                    }
                    r.endObject()
                }
                r.endObject()
            } }
            if (!out.renameTo(listFile(ctx))) throw java.io.IOException("not saved")
            old.delete(); true
        } catch (x: Throwable) { out.delete(); Log.w(TAG, "lista di prima non trasformata: ${x.message}"); false }
    }

    /** "image", "video" o null. */
    fun kindOf(name: String): String? {
        val ext = name.substringAfterLast('.', "")
        return when { IMAGE.matches(ext) -> "image"; VIDEO.matches(ext) -> "video"; else -> null }
    }
    private fun hidden(name: String) = name.isEmpty() || name.startsWith('.') || name.endsWith(".partial", true)

    // ------------------------------------------------------------ cosa c'è nel cloud

    /** Tutto il cloud dell'app sotto [base] (la radice: ""). */
    /** [at] = quando è COMINCIATA la lettura: una modifica fatta dopo questa ora la lista non la sa ([CloudState.prune]). */
    class Scan(val base: String, val entries: List<CloudEntry>, val complete: Boolean, val at: Long = System.currentTimeMillis())

    /**
     * Le cartelle dei backup in cima al cloud: copie da ripristinare, non foto da guardare. Restano fuori da anteprime,
     * doppioni, Random, Oggi e ricerca (si sfogliano a mano come ogni cartella): altrimenti decine di migliaia di foto
     * in più da anteprima e altrettanti «doppioni».
     */
    private val BACKUP_DIRS = setOf("backups", "backup")

    fun scan(drive: CloudDrive, stop: () -> Boolean): Scan? {
        val at = System.currentTimeMillis()
        val top = drive.list(drive.rootRef, "")
        val base = ""
        val skip = top.filter { it.isDir && it.name.lowercase() in BACKUP_DIRS }.map { it.path.trim('/') }.toSet()
        try {
            if (skip.isEmpty()) drive.listAll(base)?.let { return Scan(base, it.entries, it.complete, at) }
            else {
                // una richiesta per cartella in cima, i backup no (lista molto più piccola)
                val out = ArrayList<CloudEntry>(100_000)
                var complete = true
                out += top.filter { it.path.trim('/') !in skip }
                for (d in top) {
                    if (!d.isDir || d.path.trim('/') in skip) continue
                    if (stop()) { complete = false; break }
                    val t = drive.listAll(d.path.trim('/')) ?: throw java.io.IOException("no tree")
                    out += t.entries
                    if (!t.complete) complete = false
                }
                return Scan(base, out, complete, at)
            }
        } catch (e: Exception) { Log.w(TAG, "lista in un colpo non riuscita, si va cartella per cartella: ${e.message}") }
        val out = ArrayList<CloudEntry>(8192)
        var complete = true
        val todo = ArrayDeque<Pair<String, String>>().apply { add(drive.rootRef to base) }
        var errors = 0
        while (todo.isNotEmpty()) {
            if (stop() || errors >= 30) { complete = false; break }
            val (ref, path) = todo.removeFirst()
            val list = try { drive.list(ref, path).also { errors = 0 } } catch (e: Exception) { complete = false; errors++; continue }
            for (e in list) { if (e.isDir && e.path.trim('/') in skip) continue; out += e; if (e.isDir) todo.add(e.ref to e.path) }
        }
        return Scan(base, out, complete, at)
    }

    /**
     * Un file SPOSTATO si tiene la sua anteprima, la data di scatto già letta e l'impronta visiva (dopo il
     * trasloco del server il telefono rifaceva da capo le anteprime di 45.456 foto e video). Le anteprime hanno il nome
     * dal percorso: qui, prima di farne di nuove, un file sparito dal suo posto vecchio che ricompare altrove con lo
     * stesso nome e la stessa dimensione (e la stessa data del file, quando si sa) si porta dietro la sua. I posti vecchi:
     * la lista di prima (`list-cache.tsv`) e le date già lette (`dates.tsv`). Zero rete. Solo a lista COMPLETA: con
     * una lista a metà un file non elencato sembrerebbe sparito. Ritorna quante anteprime sono state portate.
     */
    fun carryMoved(ctx: Context, drive: CloudDrive, scan: Scan): Int {
        if (!scan.complete) return 0
        loadDates(ctx)
        val names = CloudThumbs.dir(ctx).list()?.toHashSet() ?: return 0
        if (names.isEmpty()) return 0
        val now = HashSet<String>(scan.entries.size * 2)
        for (e in scan.entries) if (!e.isDir) now += e.path.trim('/')
        class Old(val path: String, val size: Long, val mtime: Long)
        val old = HashMap<String, Old>()
        try {
            val m = meta(ctx, drive)
            val pre = m?.optString("base")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
            val lc = if (m != null) kids(ctx) else null
            if (lc != null) for ((d, a) in lc) for (k in a) {
                if (k.isDir) continue
                val p = pre + (if (d.isEmpty()) "" else "$d/") + k.name
                old[p] = Old(p, k.size, k.mtime)
            }
        } catch (x: Exception) { Log.w(TAG, "lista di prima non letta: ${x.message}") }
        for ((k, v) in dates) if (k !in old) v.substringBefore('|').toLongOrNull()?.let { old[k] = Old(k, it, -1) }
        val byKey = HashMap<String, MutableList<Old>>()
        for (o in old.values) {
            if (o.size < 0 || o.path in now || kindOf(o.path) == null) continue
            val f = CloudThumbs.fileFor(ctx, o.path)
            if (f.name !in names && CloudThumbs.noneFor(f).name !in names) continue
            byKey.getOrPut(o.path.substringAfterLast('/') + "|" + o.size) { ArrayList() } += o
        }
        if (byKey.isEmpty()) return 0
        val wants = ArrayList<Pair<CloudEntry, Old>>()
        for (e in scan.entries) {
            if (e.isDir || kindOf(e.name) == null) continue
            val f = CloudThumbs.fileFor(ctx, e.path)
            if (f.name in names || CloudThumbs.noneFor(f).name in names) continue
            val l = byKey[e.name + "|" + e.size] ?: continue
            val o = l.firstOrNull { it.mtime > 0 && it.mtime == e.mtime } ?: l.firstOrNull { it.mtime <= 0 } ?: continue
            wants += e to o
        }
        // più copie dello stesso file: ognuna la sua anteprima (copiata), l'ultima prende l'originale (spostato)
        val left = wants.groupingBy { it.second.path }.eachCount().toMutableMap()
        val renamed = HashMap<String, String>() // nome dell'anteprima vecchia → nuovo (per le impronte visive)
        var n = 0
        for ((e, o) in wants) {
            val last = (left[o.path] ?: 1) <= 1
            left[o.path] = (left[o.path] ?: 1) - 1
            val from = CloudThumbs.fileFor(ctx, o.path); val to = CloudThumbs.fileFor(ctx, e.path)
            for ((a, b) in listOf(from to to, CloudThumbs.noneFor(from) to CloudThumbs.noneFor(to))) {
                if (!a.isFile) continue
                if (last && a.renameTo(b)) continue
                try {
                    val t = File(b.parentFile, b.name + "." + android.os.Process.myPid() + ".tmp")
                    a.copyTo(t, overwrite = true); t.setLastModified(a.lastModified())
                    if (!t.renameTo(b)) t.delete()
                    if (last) a.delete()
                } catch (x: Exception) { Log.w(TAG, "anteprima non portata: ${x.message}") }
            }
            if (to.isFile) renamed[from.name] = to.name
            val np = e.path.trim('/')
            dates[o.path]?.let { d -> if (dates[np] == null) dates[np] = d; if (last) dates.remove(o.path); datesDirty = true }
            n++
        }
        saveDates(ctx)
        if (renamed.isNotEmpty()) VideoLength.renamed(ctx, renamed) // la durata dei video segue l'anteprima
        // le impronte visive (Quasi uguali) sono per nome dell'anteprima: seguono il nome nuovo
        try {
            val hf = File(dir(ctx), "dhash.tsv")
            if (renamed.isNotEmpty() && hf.isFile) {
                val out = StringBuilder()
                hf.forEachLine { l -> val i = l.indexOf('\t'); if (i > 0) { val k = l.substring(0, i); out.append(renamed[k] ?: k).append(l.substring(i)).append('\n') } }
                writeAtomic(hf, out.toString())
            }
        } catch (x: Exception) { Log.w(TAG, "impronte non portate: ${x.message}") }
        Log.i(TAG, "file spostati: $n anteprime portate al posto nuovo")
        return n
    }

    /** I file da fare in anteprima, nell'ordine di sempre: una cartella, i suoi file, poi le sottocartelle dal nome più alto
     *  («2026» prima di «2025»), in profondità. */
    fun order(scan: Scan): List<CloudEntry> {
        val kids = HashMap<String, MutableList<CloudEntry>>()
        for (e in scan.entries) kids.getOrPut(e.path.substringBeforeLast('/', "")) { ArrayList() } += e
        val out = ArrayList<CloudEntry>(scan.entries.size)
        val todo = ArrayDeque<String>().apply { add(scan.base) }
        val seen = HashSet<String>()
        while (todo.isNotEmpty()) {
            val d = todo.removeFirst()
            if (!seen.add(d)) continue
            val list = kids[d] ?: continue
            list.filter { it.isDir }.sortedBy { it.name.lowercase() }.forEach { todo.addFirst(it.path) }
            list.filter { !it.isDir }.sortedBy { it.name.lowercase() }.forEach { out += it }
        }
        return out
    }

    // ------------------------------------------------------------ le date dentro i file

    /** percorso → "byte|AAAA-MM-GG" ("" dopo la barra = guardato, nessuna data dentro). Si scrive in `dates.tsv`. */
    private val dates = ConcurrentHashMap<String, String>()
    @Volatile private var datesLoaded = false
    @Volatile private var datesDirty = false

    private fun loadDates(ctx: Context) {
        if (datesLoaded) return
        synchronized(dates) {
            if (datesLoaded) return
            try { File(dir(ctx), "dates.tsv").takeIf { it.isFile }?.forEachLine { l -> l.split('\t').takeIf { it.size == 3 }?.let { dates[it[0]] = it[1] + "|" + it[2] } } }
            catch (e: Exception) { Log.w(TAG, "date non lette", e) }
            datesLoaded = true
        }
    }

    fun saveDates(ctx: Context) {
        if (!datesDirty) return
        datesDirty = false
        writeAtomic(File(dir(ctx), "dates.tsv"), buildString { for ((k, v) in dates) { append(k).append('\t').append(v.replaceFirst('|', '\t')).append('\n') } })
    }

    /** La data che c'è DENTRO il file, o null se non si sa ancora; "" = guardato, nessuna. */
    fun embedded(ctx: Context, path: String, size: Long): String? {
        loadDates(ctx)
        val v = dates[path.trim('/')] ?: return null
        val s = v.substringBefore('|').toLongOrNull()
        return if (s == size || s == -1L) v.substringAfter('|') else null
    }

    /** Mentre si fa l'anteprima il file è già in mano: si prende la data di scatto, senza un byte in più di rete. */
    fun note(ctx: Context, e: CloudEntry, date: String?) {
        if (date == null) return
        loadDates(ctx)
        // «dentro non c'è» non cancella una data già nota per lo stesso file
        if (date.isEmpty() && embedded(ctx, e.path, e.size)?.isNotEmpty() == true) return
        dates[e.path.trim('/')] = "${e.size}|$date"
        datesDirty = true
    }

    /** per le foto: DateTimeOriginal, poi CreateDate (= DateTimeDigitized). "" = nessuna. */
    fun exifDate(ex: ExifInterface): String = listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED)
        .firstNotNullOfOrNull { ex.getAttribute(it)?.let { v -> ymd(v) } } ?: ""

    /**
     * Il video la scrive in UTC, «20261007T001010.000Z»: si porta all'ora del telefono (un video girato
     * all'1:30 del 1° novembre in Italia è del 1° novembre, non del 31 ottobre). Come [ShotDate] per le cartelle.
     * Senza ora (solo il giorno): il giorno così com'è.
     */
    fun videoDate(raw: String?, qt: String? = null): String {
        // il giorno scritto dall'iPhone COL SUO FUSO («2024-05-31T18:58:29-0400»): è il giorno dove si è girato
        // (due video del 31/05/2024 girati negli Stati Uniti finivano al 1° giugno con l'ora italiana)
        if (qt != null) return qt
        if (raw == null) return ""
        try {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            if (raw.length >= 15) fmt.parse(raw.take(15))?.time?.takeIf { it > 0 }?.let { return mtimeDate(it) ?: "" }
        } catch (_: Exception) {}
        return Regex("""^(\d{4})(\d{2})(\d{2})""").find(raw)?.let { valid(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) } ?: ""
    }

    /** La data col fuso che iPhone e altri scrivono dentro il video (`com.apple.quicktime.creationdate`):
     *  «2024-05-31T18:58:29-0400» → "2024-05-31", il giorno dell'orologio di dove si girava. null = non c'è. */
    private val QT = Regex("""(20\d{2})-(\d{2})-(\d{2})T\d{2}:\d{2}:\d{2}[+-]\d{2}:?\d{2}""")
    fun qtDate(bytes: ByteArray, len: Int = bytes.size): String? =
        QT.find(String(bytes, 0, len, Charsets.ISO_8859_1))?.let { valid(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    /** [qtDate] da un file sul telefono: l'inizio e la fine (la scatola «moov» sta in uno dei due). */
    fun qtDateOf(f: File): String? = try {
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = r.length()
            fun part(from: Long, size: Int): ByteArray { val b = ByteArray(minOf(size.toLong(), n - from).toInt().coerceAtLeast(0)); r.seek(from); r.readFully(b); return b }
            qtDate(part(0, 1 shl 20)) ?: if (n > (1 shl 20)) qtDate(part(maxOf(1L shl 20, n - (4 shl 20)), 4 shl 20)) else null
        }
    } catch (_: Exception) { null }

    /** Da un file già sul telefono (copia «Sul telefono», provvisorio dell'anteprima). */
    fun fileDate(f: File): String? = try {
        if (kindOf(f.name) == "video") {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(f.path); videoDate(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE), qtDateOf(f)) } finally { r.release() }
        } else exifDate(ExifInterface(f.path))
    } catch (_: Exception) { null }

    // le date: dentro il file, dal nome, dal percorso, dalla data del file
    private fun ymd(v: String): String? = Regex("""^\s*(\d{4})[:\-](\d{2})[:\-](\d{2})""").find(v)
        ?.let { valid(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    private fun valid(y: Int, m: Int, d: Int): String? =
        if (y < 2000 || y > 2099 || m < 1 || m > 12 || d < 1 || d > 31) null else String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    private val NAME1 = Regex("""(?:^|[^0-9])(20[0-9]{2})([01][0-9])([0-3][0-9])(?:[^0-9]|$)""")
    private val NAME2 = Regex("""(20[0-9]{2})[-_]([01][0-9])[-_]([0-3][0-9])""")
    private val PATH1 = Regex("""/(20[0-9]{2})-([01][0-9])-([0-3][0-9])/""")
    private fun g(m: MatchResult?) = m?.let { valid(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    /** «20240531_225829000_iOS.MOV»: i nomi che l'iPhone dà esportando sono in ORA INGLESE: si portano
     *  all'ora del telefono, come la data dei video. */
    val IOS_NAME = Regex("""^(20\d{2})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\d*_iOS\.""", RegexOption.IGNORE_CASE)
    fun iosNameTime(name: String): Long? = IOS_NAME.find(name)?.groupValues?.let { g ->
        try { java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false }
            .parse(g[1] + g[2] + g[3] + g[4] + g[5] + g[6])?.time } catch (_: Exception) { null } }
    fun nameDate(name: String): String? = iosNameTime(name)?.let { mtimeDate(it) } ?: g(NAME1.find(name)) ?: g(NAME2.find(name))
    fun pathDate(rel: String): String? = g(PATH1.find("/$rel/"))
    fun mtimeDate(t: Long): String? = if (t <= 0) null else Calendar.getInstance().run {
        timeInMillis = t; valid(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))
    }

    // ------------------------------------------------------------ i file della lista

    class Built(val media: Int, val dirs: Int, val files: Int)

    /** Scrive media-index.json e list-cache.tsv in `files/index-phone/` (atomici). */
    fun publish(ctx: Context, drive: CloudDrive, scan: Scan): Built {
        loadDates(ctx)
        val pre = if (scan.base.isEmpty()) "" else scan.base + "/"
        fun rel(p: String) = p.trim('/').removePrefix(pre)
        val inside = scan.entries.filter { e -> val r = rel(e.path); r.isNotEmpty() && r.split('/').none { hidden(it) } }
        // Oggi negli anni: foto e video di tutto il cloud
        val buckets = HashMap<String, JSONArray>(400)
        var media = 0
        for (e in inside) {
            if (e.isDir) continue
            val kind = kindOf(e.name) ?: continue
            val r = rel(e.path)
            val emb = embedded(ctx, e.path, e.size)?.takeIf { it.isNotEmpty() }
            val (d, src) = when {
                emb != null -> emb to "exif"
                nameDate(e.name) != null -> nameDate(e.name)!! to "name"
                pathDate(r) != null -> pathDate(r)!! to "path"
                else -> (mtimeDate(e.mtime) ?: continue) to "mtime"
            }
            buckets.getOrPut(d.substring(5)) { JSONArray() }.put(JSONObject().put("path", r).put("name", e.name).put("kind", kind)
                .put("capturedDate", d).put("year", d.substring(0, 4)).put("source", src))
            media++
        }
        val b = JSONObject(); for ((k, v) in buckets) b.put(k, v)
        val now = System.currentTimeMillis()
        writeAtomic(File(dir(ctx), "media-index.json"), JSONObject().put("version", 1).put("builtAt", now).put("by", "phone").put("buckets", b).toString())
        // la ricerca in tutto il cloud: una riga per voce, scritta a flusso ([ListFile]); prima un
        // albero JSON di tutto il cloud in memoria e poi un testo da 16 MB (memoria finita)
        val lf = listFile(ctx); lf.parentFile?.mkdirs()
        val tmp = File(lf.parentFile, lf.name + "." + android.os.Process.myPid() + "." + Thread.currentThread().id + ".tmp")
        tmp.bufferedWriter().use { w -> for (e in inside) { val r = rel(e.path)
            ListFile.row(w, r.substringBeforeLast('/', ""), e.name, e.isDir, e.size, e.mtime) } }
        if (!tmp.renameTo(lf)) { tmp.delete(); throw java.io.IOException("not saved: ${lf.name}") }
        File(dir(ctx), "list-cache.json").delete()
        val built = Built(media, inside.count { it.isDir }, inside.count { !it.isDir })
        writeMeta(ctx, drive) { it.put("builtAt", now).put("scanAt", scan.at).put("base", scan.base)
            .put("media", built.media).put("dirs", built.dirs).put("files", built.files) }
        saveDates(ctx)
        return built
    }

    // ------------------------------------------------------------ doppioni

    /**
     * le foto e i video (non nascosti) uguali byte per byte, stessa dimensione e stessa
     * impronta. L'impronta si chiede solo per i file che hanno una dimensione in comune con un altro, e si ricorda
     * (`md5.tsv`, percorso+dimensione+data): il giorno dopo si chiedono solo i nuovi.
     */
    fun duplicates(ctx: Context, drive: CloudDrive, scan: Scan, stop: () -> Boolean): Int {
        val pre = if (scan.base.isEmpty()) "" else scan.base + "/"
        val media = scan.entries.filter { e -> !e.isDir && e.size > 0 && kindOf(e.name) != null && e.path.trim('/').removePrefix(pre).split('/').none { hidden(it) } }
        val cand = media.groupBy { it.size }.values.filter { it.size > 1 }.flatten()
        val f = File(dir(ctx), "md5.tsv")
        val known = HashMap<String, String>()
        try { if (f.isFile) f.forEachLine { l -> l.split('\t').takeIf { it.size == 2 }?.let { known[it[0]] = it[1] } } } catch (_: Exception) {}
        fun key(e: CloudEntry) = e.path.trim('/') + "|" + e.size + "|" + e.mtime
        // un file spostato ha già la sua impronta sotto il percorso vecchio: stesso nome, dimensione e data
        val byId = HashMap<String, String>()
        for ((k, v) in known) { val p = k.split('|'); if (p.size == 3) byId[p[0].substringAfterLast('/') + "|" + p[1] + "|" + p[2]] = v }
        for (e in cand) if (key(e) !in known) byId[e.name + "|" + e.size + "|" + e.mtime]?.let { known[key(e)] = it }
        val todo = cand.filter { key(it) !in known }
        var exact = true
        if (todo.isNotEmpty() || known.size > cand.size) {
            val got = if (todo.isEmpty()) emptyMap() else try { drive.md5(todo.map { it.path }) } catch (e: Exception) { Log.w(TAG, "md5 dal server: ${e.message}"); null }
            if (got != null) for (e in todo) got[e.path.trim('/')]?.let { known[key(e)] = it }
            else {
                exact = false // un cloud senza impronta del server: inizio e fine del file (64 KB + 64 KB)
                for (e in todo) {
                    if (stop()) break
                    try { known[key(e)] = "p:" + partial(drive, e) } catch (x: Exception) { Log.w(TAG, "doppione ${e.name}: ${x.message}") }
                }
            }
            // si tengono solo le impronte dei candidati di oggi: i file spariti non gonfiano il file
            val now = cand.map { key(it) }.toSet()
            writeAtomic(f, buildString { for ((k, v) in known) if (k in now) { append(k).append('\t').append(v).append('\n') } })
        }
        val groups = cand.mapNotNull { e -> known[key(e)]?.let { (it + ":" + e.size) to e } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (k, l) ->
                val files = l.sortedWith(compareBy<CloudEntry> { it.mtime }.thenBy { it.path })
                val size = l[0].size
                JSONObject().put("hash", k.substringBeforeLast(':')).put("size", size).put("wasted", size * (l.size - 1))
                    .put("files", JSONArray().apply { files.forEach { put(JSONObject().put("path", it.path.trim('/').removePrefix(pre)).put("name", it.name).put("mtime", it.mtime)) } })
            }.sortedByDescending { it.getLong("wasted") }
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        writeAtomic(File(dir(ctx), "duplicates.json"), JSONObject().put("version", 1).put("builtAt", iso).put("scope", "").put("exact", exact)
            .put("totalWasted", groups.sumOf { it.getLong("wasted") }).put("groups", JSONArray(groups)).toString())
        writeMeta(ctx, drive) { it.put("dupsAt", System.currentTimeMillis()).put("dupsScanAt", scan.at).put("dupGroups", groups.size).put("dupExact", exact) }
        return groups.size
    }

    // ------------------------------------------------------------ foto quasi uguali

    /**
     * Le foto e i video QUASI uguali (raffiche, la stessa scena scattata due volte): un'impronta visiva di 64 bit
     * (dHash: 9×8 grigi, ogni punto più chiaro del vicino = 1) fatta dalle ANTEPRIME già sul telefono — zero rete.
     * Si confrontano solo foto dello stesso giorno (le date di «Oggi negli anni»): una raffica sta in un giorno, e così
     * 26.000 foto non diventano 338 milioni di confronti. Gruppo = vicine entro [SIMILAR_BITS] bit; dentro il gruppo la
     * migliore per prima: il file più grande (più dettaglio). Impronte ricordate in `dhash.tsv`.
     * I gruppi fatti SOLO di copie identiche stanno già nei doppioni esatti e qui non si ripetono.
     */
    const val SIMILAR_BITS = 10

    fun similar(ctx: Context, drive: CloudDrive, scan: Scan, stop: () -> Boolean): Int {
        val pre = if (scan.base.isEmpty()) "" else scan.base + "/"
        val media = try { JSONObject(File(dir(ctx), "media-index.json").readText()).getJSONObject("buckets") } catch (_: Exception) { return 0 }
        val sizeOf = HashMap<String, Long>(scan.entries.size)
        for (e in scan.entries) if (!e.isDir) sizeOf[e.path.trim('/')] = e.size
        val cacheF = File(dir(ctx), "dhash.tsv")
        val cache = HashMap<String, String>()
        try { if (cacheF.isFile) cacheF.forEachLine { l -> l.split('\t').takeIf { it.size == 3 }?.let { cache[it[0]] = it[1] + "|" + it[2] } } } catch (_: Exception) {}
        val fresh = HashMap<String, String>()
        val byDay = HashMap<String, MutableList<Pair<String, Long>>>() // giorno → (percorso pieno, impronta)
        for (k in media.keys()) {
            val a = media.getJSONArray(k)
            for (i in 0 until a.length()) {
                if (stop()) return -1
                val o = a.getJSONObject(i)
                val full = pre + o.getString("path").trim('/')
                val t = CloudThumbs.fileFor(ctx, full)
                if (!t.isFile) continue
                val tag = t.lastModified().toString()
                val h = cache[t.name]?.takeIf { it.substringBefore('|') == tag }?.substringAfter('|')?.toULongOrNull(16)?.toLong()
                    ?: dhash(t) ?: continue
                fresh[t.name] = tag + "|" + java.lang.Long.toHexString(h)
                byDay.getOrPut(o.getString("capturedDate")) { ArrayList() } += full to h
            }
        }
        writeAtomic(cacheF, buildString { for ((k, v) in fresh) append(k).append('\t').append(v.replaceFirst('|', '\t')).append('\n') })
        // i doppioni esatti: chi sta nello stesso gruppo esatto
        val exact = HashMap<String, Int>()
        try {
            val g = JSONObject(File(dir(ctx), "duplicates.json").readText()).getJSONArray("groups")
            for (i in 0 until g.length()) { val f = g.getJSONObject(i).getJSONArray("files"); for (j in 0 until f.length()) exact[pre + f.getJSONObject(j).getString("path").trim('/')] = i }
        } catch (_: Exception) {}
        val groups = ArrayList<JSONObject>()
        for ((day, l) in byDay) {
            if (l.size < 2) continue
            val parent = IntArray(l.size) { it }
            fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val n = parent[y]; parent[y] = r; y = n }; return r }
            for (i in l.indices) for (j in i + 1 until l.size)
                if (java.lang.Long.bitCount(l[i].second xor l[j].second) <= SIMILAR_BITS) parent[root(i)] = root(j)
            l.indices.groupBy { root(it) }.values.filter { it.size > 1 }.forEach { idx ->
                val paths = idx.map { l[it].first }
                if (paths.mapNotNull { exact[it] }.let { e -> e.size == paths.size && e.toSet().size == 1 }) return@forEach // tutte copie identiche
                val files = paths.sortedWith(compareByDescending<String> { sizeOf[it] ?: 0 }.thenBy { it })
                groups += JSONObject().put("day", day).put("files", JSONArray().apply { files.forEach { put(JSONObject().put("path", it.removePrefix(pre)).put("size", sizeOf[it] ?: -1)) } })
            }
        }
        groups.sortByDescending { it.getString("day") }
        writeAtomic(File(dir(ctx), "similar.json"), JSONObject().put("version", 1).put("builtAt", System.currentTimeMillis()).put("bits", SIMILAR_BITS).put("groups", JSONArray(groups)).toString())
        writeMeta(ctx, drive) { it.put("simScanAt", scan.at).put("simGroups", groups.size) } // per [Index.hasDups], senza leggere la lista
        return groups.size
    }

    /** dHash di un'anteprima: letta piccola (1/8), ridotta a 9×8, ogni punto confrontato col vicino a destra. */
    fun dhash(f: File): Long? = try {
        val b = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) ?: null
        b?.let { src ->
            val s = android.graphics.Bitmap.createScaledBitmap(src, 9, 8, true)
            var h = 0L; var bit = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                fun lum(px: Int) = (px shr 16 and 0xff) * 299 + (px shr 8 and 0xff) * 587 + (px and 0xff) * 114
                if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) h = h or (1L shl bit)
                bit++
            }
            if (s !== src) s.recycle(); src.recycle()
            h
        }
    } catch (_: Exception) { null }

    private fun partial(drive: CloudDrive, e: CloudEntry): String {
        val md = MessageDigest.getInstance("SHA-1")
        val n = 64 * 1024L
        drive.open(e, 0, minOf(e.size, n) - 1).use { md.update(it.readBytes()) }
        if (e.size > n) drive.open(e, maxOf(n, e.size - n), e.size - 1).use { md.update(it.readBytes()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ stato

    private fun metaFile(ctx: Context) = File(dir(ctx), "meta.json")
    private fun who(drive: CloudDrive) = drive.label

    /** La scheda della lista per QUESTO cloud (un altro cloud collegato = lista da rifare). */
    fun meta(ctx: Context, drive: CloudDrive): JSONObject? = try {
        JSONObject(metaFile(ctx).readText()).takeIf { it.optString("cloud") == who(drive) }
    } catch (_: Exception) { null }

    @Synchronized private fun writeMeta(ctx: Context, drive: CloudDrive, edit: (JSONObject) -> Unit) {
        val m = meta(ctx, drive) ?: JSONObject().put("cloud", who(drive))
        edit(m)
        writeAtomic(metaFile(ctx), m.toString())
    }

    /** La lista del telefono è pronta per questo cloud? Allora [Index] la legge. */
    fun ready(ctx: Context, drive: CloudDrive): Boolean = meta(ctx, drive)?.has("builtAt") == true && File(dir(ctx), "media-index.json").isFile

    fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val t = File(f.parentFile, f.name + "." + android.os.Process.myPid() + "." + Thread.currentThread().id + ".tmp")
        t.writeText(text)
        if (!t.renameTo(f)) { t.delete(); throw java.io.IOException("not saved: ${f.name}") }
    }
}
