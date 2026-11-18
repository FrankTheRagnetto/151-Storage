package com.the151universe.storage

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Gli indici fatti DAL TELEFONO ([CloudList], `files/index-phone/`). L'app li LEGGE e basta: Today (questo giorno negli
 * anni), Random, Random day, Doppioni e la ricerca in tutto il cloud. Finché la lista non c'è queste funzioni non
 * compaiono, senza errori. I percorsi degli indici sono relativi alla radice del cloud dell'app.
 */
object Index {
    private const val TAG = "151Storage"

    class Item(val path: String, val name: String, val video: Boolean, val date: String, val year: String)
    class DupGroup(val size: Long, val files: List<String>) { val wasted get() = size * (files.size - 1) }
    /** Foto quasi uguali dello stesso giorno, la migliore (la più grande) per prima ([CloudList.similar]). */
    class SimGroup(val day: String, val files: List<Pair<String, Long>>)

    @Volatile var available = false
        private set
    /** "phone" = la lista fatta dal telefono, "" = nessuna. */
    @Volatile var source = ""
        private set
    /**
     * La lista del telefono (`index-phone/`) si legge solo quando una funzione la chiede (Random, Oggi, Un giorno a
     * caso, Doppioni), UNA volta per processo, e di nuovo solo se il file è cambiato (data + misura). Prima [load] la
     * rileggeva tutta (l'indice delle foto tre volte) a ogni apertura del Cloud e a ogni passaggio del giro delle anteprime.
     */
    @Volatile private var phoneDir: File? = null
    private val mediaMemo = FileMemo { f -> parseMedia(f.readText()) }
    private val dupsMemo = FileMemo { f -> parseDups(f.readText()) }
    private val simsMemo = FileMemo { f -> parseSims(f.readText()) }
    /** C'è almeno un gruppo di doppioni o quasi uguali, secondo la scheda della lista (senza leggere le liste). */
    @Volatile private var dupHint = false
    private val buckets: Map<String, List<Item>> get() = phoneDir?.let { mediaMemo.get(File(it, "media-index.json")) } ?: emptyMap()
    private val dupsNow: Pair<String, List<DupGroup>> get() = phoneDir?.let { dupsMemo.get(File(it, "duplicates.json")) } ?: ("" to emptyList())
    private val sims: List<SimGroup> get() = phoneDir?.let { simsMemo.get(File(it, "similar.json")) } ?: emptyList()
    val dupsBuiltAt: String get() = dupsNow.first
    @Volatile private var loadedFor = ""
    private val lock = Any()

    /** Chi mostra le funzioni (il Cloud) si fa avvisare quando la lista del telefono diventa pronta. */
    @Volatile var onChanged: (() -> Unit)? = null
    fun changed() { onChanged?.invoke() }

    fun forget(ctx: Context) {
        available = false; source = ""; loadedFor = ""
        phoneDir = null; dupHint = false; mediaMemo.forget(); dupsMemo.forget(); simsMemo.forget()
        File(ctx.filesDir, "index").deleteRecursively()
        CloudList.forget(ctx)
        CloudState.forget(ctx)
    }

    /**
     * Prepara gli indici. La lista non si legge qui: si guarda solo la sua scheda (`meta.json`, pochi byte); i file
     * grandi li legge [FileMemo] alla prima funzione che li chiede. Da chiamare fuori dal thread principale.
     */
    fun load(ctx: Context, drive: CloudDrive, force: Boolean = false) = synchronized(lock) {
        val phone = CloudList.ready(ctx, drive)
        if (!force && loadedFor == drive.label && available == phone) return@synchronized
        try {
            val meta = if (phone) CloudList.meta(ctx, drive) else null
            if (meta == null) { available = false; source = ""; phoneDir = null; loadedFor = drive.label; return@synchronized }
            val dir = CloudList.dir(ctx)
            CloudState.load(ctx)
            dupHint = meta.optInt("dupGroups", 0) > 0 ||
                (if (meta.has("simGroups")) meta.optInt("simGroups", 0) > 0 else File(dir, "similar.json").length() > 100)
            // le modifiche che la lista del telefono conosce già (fatte prima che cominciasse) non servono più nel registro
            CloudState.prune(ctx, listOf("scanAt", "dupsScanAt", "simScanAt").filter { meta.has(it) }.minOfOrNull { meta.optLong(it) } ?: 0L)
            phoneDir = dir
            available = true
            source = "phone"
            loadedFor = drive.label
        } catch (e: Exception) {
            Log.w(TAG, "indici non letti", e)
            available = phoneDir != null
        }
    }

    /** Doppioni o quasi uguali da mostrare? Dalla scheda finché le liste non sono in memoria: mai lette sul thread principale. */
    fun hasDups(): Boolean {
        val d = phoneDir ?: return false
        return if (dupsMemo.ready(File(d, "duplicates.json")) && simsMemo.ready(File(d, "similar.json"))) duplicates().isNotEmpty() || similar().isNotEmpty()
        else dupHint
    }

    /**
     * Dove sta adesso una voce della lista (percorso relativo, come negli indici), secondo [CloudState]: lo stesso, quello
     * nuovo se spostata o rinominata, null se eliminata. TUTTE le funzioni leggono da qui: la lista sul disco può essere di
     * prima di una modifica, quello che si mostra no.
     */
    private fun live(rel: String): String? = CloudState.current(rel.trim('/'))
    private fun liveItem(i: Item): Item? = live(i.path)?.let { np -> if (np == i.path.trim('/')) i else Item(np, np.substringAfterLast('/'), i.video, i.date, i.year) }

    /** L'indice delle foto letto UNA volta (prima: tre volte lo stesso testo, per la data e per i gruppi). */
    private fun parseMedia(text: String): Map<String, List<Item>> {
        val b = JSONObject(text).getJSONObject("buckets")
        val out = HashMap<String, List<Item>>(400)
        for (k in b.keys()) {
            val a = b.getJSONArray(k)
            out[k] = (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                Item(o.getString("path"), o.optString("name", o.getString("path").substringAfterLast('/')), o.optString("kind") == "video",
                    o.optString("capturedDate"), o.optString("year"))
            } }
        }
        return out
    }

    /** (data della lista, gruppi). */
    private fun parseDups(text: String): Pair<String, List<DupGroup>> {
        val o = JSONObject(text)
        val at = o.optString("builtAt").take(10)
        val g = o.optJSONArray("groups") ?: return at to emptyList()
        return at to (0 until g.length()).map { i -> g.getJSONObject(i).let { x ->
            val f = x.getJSONArray("files")
            DupGroup(x.optLong("size"), (0 until f.length()).map { f.getJSONObject(it).getString("path") })
        } }.sortedByDescending { it.wasted }
    }

    private fun parseSims(text: String): List<SimGroup> = try {
        val g = JSONObject(text).getJSONArray("groups")
        (0 until g.length()).map { i -> g.getJSONObject(i).let { x -> val f = x.getJSONArray("files")
            SimGroup(x.optString("day"), (0 until f.length()).map { j -> f.getJSONObject(j).let { it.getString("path") to it.optLong("size", -1) } }) } }
    } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------ le funzioni

    fun mmdd(cal: Calendar) = String.format(java.util.Locale.ROOT, "%02d-%02d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    /** Today: per anno, dal più vecchio, dentro l'anno per data. */
    fun onThisDay(key: String): List<Pair<String, List<Item>>> =
        (buckets[key] ?: emptyList()).mapNotNull { liveItem(it) }.groupBy { it.year }.toSortedMap().map { (y, l) -> y to l.sortedBy { it.date } }

    /** Random: [n] a caso fra tutti (Fisher-Yates parziale). Con [ready]: fra al più 600 pescati a
     *  caso, prima quelli che hanno già l'anteprima sul telefono, poi gli altri (mai 26.000 controlli a ogni tocco). */
    fun random(n: Int = 16, ready: ((String) -> Boolean)? = null): List<Item> {
        val all = ArrayList<Item>(); buckets.values.forEach { all += it }
        val k = minOf(if (ready == null) n else 600, all.size)
        for (i in 0 until k) { val j = i + (Math.random() * (all.size - i)).toInt(); val t = all[i]; all[i] = all[j]; all[j] = t }
        val picked = all.subList(0, k).mapNotNull { liveItem(it) }
        if (ready == null) return picked
        val (yes, no) = picked.partition { ready(it.path) }
        return (yes.take(n) + no.take(n - minOf(n, yes.size))).shuffled()
    }

    /** Random day: un giorno a caso fra quelli che hanno almeno una foto (uguale probabilità per giorno). */
    fun randomDay(): String? = buckets.filterValues { l -> l.any { live(it.path) != null } }.keys.randomOrNull()

    fun duplicates(): List<DupGroup> = dupsNow.second.mapNotNull { g -> g.files.mapNotNull { live(it) }.distinct().takeIf { it.size > 1 }?.let { DupGroup(g.size, it) } }
    fun similar(): List<SimGroup> = sims.mapNotNull { g -> g.files.mapNotNull { (p, sz) -> live(p)?.let { it to sz } }.distinctBy { it.first }.takeIf { it.size > 1 }?.let { SimGroup(g.day, it) } }

    /** Ricerca per nome in tutto il cloud: tutte le parole nel nome; prima i nomi uguali, poi che iniziano così. */
    private fun kids(ctx: Context): Map<String, List<ListFile.Kid>>? = if (source == "phone") CloudList.kids(ctx) else null

    fun search(ctx: Context, drive: CloudDrive, q: String, limit: Int = 500): List<CloudEntry>? {
        val lc = kids(ctx) ?: return null
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val hits = ArrayList<Triple<Int, String, Boolean>>()
        for ((dir, kids) in lc) {
            if (dir.split('/').any { it.startsWith('.') }) continue
            for (k in kids) {
                val name = k.name; val isDir = k.isDir
                if (name.startsWith('.')) continue
                val n = name.lowercase()
                if (!words.all { n.contains(it) }) continue
                val rank = when { n == q.lowercase() -> 0; n.startsWith(words[0]) -> 1; else -> 2 }
                hits += Triple(rank, if (dir.isEmpty()) name else "$dir/$name", isDir)
            }
        }
        return hits.sortedWith(compareBy<Triple<Int, String, Boolean>> { it.first }.thenBy { it.second.length })
            .mapNotNull { (r, p, d) -> live(p)?.let { Triple(r, it, d) } }.distinctBy { it.second }.take(limit)
            .map { (_, p, d) -> entry(ctx, drive, p, d) }
    }

    /**
     * La voce come la vede una cartella del Cloud: con la sua dimensione e la sua data dalla lista di tutto il cloud
     * Ogni vista speciale usa la stessa casella di una cartella. Senza, Random, Oggi, Doppioni e la
     * ricerca avevano voci senza dimensione né data: anteprima chiesta di nuovo alla rete, segno «sul telefono»,
     * scheda del visore e tocco diversi dalla stessa foto in cartella. [path] relativo alla base, come negli indici.
     */
    fun entry(ctx: Context, drive: CloudDrive, path: String, isDir: Boolean = false): CloudEntry {
        val e = entryOf(path, isDir)
        if (isDir) return e
        val rel = path.trim('/')
        val k = kids(ctx)?.get(rel.substringBeforeLast('/', ""))?.firstOrNull { it.name == rel.substringAfterLast('/') && !it.isDir } ?: return e
        return e.copy(size = k.size, mtime = k.mtime)
    }
    /** Come [entry], da un percorso pieno (dalla radice del cloud dell'app, come le stelle). */
    fun entryFull(ctx: Context, drive: CloudDrive, full: String, isDir: Boolean = false): CloudEntry? =
        entry(ctx, drive, full.trim('/'), isDir)

    /** Un percorso dell'indice come voce sfogliabile del cloud. */
    fun entryOf(path: String, isDir: Boolean = false, size: Long = -1): CloudEntry {
        val full = path.trim('/')
        val ref = WebDav.encPath(full).let { if (isDir) it else it.trimEnd('/') }
        return CloudEntry(ref, full.substringAfterLast('/'), isDir, size, 0, null, full)
    }
}
