package com.the151universe.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * «Tieni sempre su questo telefono»: cartelle del cloud copiate e tenute allineate.
 * La copia vive SOLO dentro l'app (`files/offline/`, memoria privata): non la vedono Media, Telefono,
 * la Galleria né le altre app; si apre dalla sezione Cloud, anche senza rete. È uno specchio: un file
 * cambiato sul cloud si riscarica, uno sparito sul cloud sparisce anche qui. Sul cloud non si scrive niente.
 * Si allinea quando si apre il Cloud (al massimo ogni 6 ore), quando si sceglie una cartella e con «Aggiorna»;
 * il lavoro gira in [OfflineService] (primo piano, notifica), così non si ferma uscendo dall'app.
 */
object Offline {
    private const val TAG = "151Storage"
    private const val PREFS = "offline"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "sempre-sul-telefono") }
    private val listeners = mutableSetOf<() -> Unit>()

    data class Pin(val ref: String, val path: String)

    @Volatile var running = false
        private set
    /** Cosa sta facendo, in una riga («Scarico 3 di 40») o "" se niente. */
    @Volatile var status = ""
        private set
    @Volatile var lastError = ""
        private set
    @Volatile private var lastSync = 0L
    private const val SYNC_GAP = 6 * 3_600_000L
    /** Messo dal servizio quando Android lo chiude: il file in corso finisce, poi ci si ferma. */
    @Volatile internal var stop = false

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun root(ctx: Context) = File(ctx.filesDir, "offline")

    /** Il file della copia per un percorso leggibile del cloud ("Documents/Soldi/x.pdf"). */
    fun local(ctx: Context, path: String) = File(root(ctx), path.trim('/'))

    fun pins(ctx: Context): List<Pin> {
        val raw = try {
            val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pins", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Pin(o.getString("ref"), o.getString("path")) } }
        } catch (_: Exception) { emptyList() }
        // le cartelle tenute seguono quello che l'app ha fatto lassù ([CloudState]): spostata o rinominata →
        // la copia si sposta con lei (niente da riscaricare); eliminata → la copia sparisce
        val live = raw.mapNotNull { p ->
            val cur = CloudState.current(p.path) ?: return@mapNotNull null.also { local(ctx, p.path).deleteRecursively() }
            if (cur == p.path.trim('/')) p else {
                val from = local(ctx, p.path); val to = local(ctx, cur)
                if (from.exists() && !to.exists()) { to.parentFile?.mkdirs(); from.renameTo(to) }
                Pin(WebDav.encPath(cur), cur)
            }
        }
        if (live != raw) savePins(ctx, live)
        return live
    }

    private fun savePins(ctx: Context, l: List<Pin>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("ref", it.ref).put("path", it.path)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pins", a.toString()).apply()
    }

    /** La cartella (o una che la contiene) è tenuta sul telefono? */
    fun isPinned(ctx: Context, path: String): Boolean {
        val p = path.trim('/')
        return pins(ctx).any { val q = it.path.trim('/'); p == q || p.startsWith("$q/") || q.isEmpty() }
    }

    fun isPinRoot(ctx: Context, path: String) = pins(ctx).any { it.path.trim('/') == path.trim('/') }

    /** La copia locale di un file del cloud, se c'è ed è uguale (stessa dimensione). */
    fun copyOf(ctx: Context, e: CloudEntry): File? {
        if (e.isDir || !isPinned(ctx, e.path)) return null
        val f = local(ctx, e.path)
        return f.takeIf { it.isFile && (e.size < 0 || it.length() == e.size) }
    }

    fun pin(ctx: Context, items: List<CloudEntry>) {
        val cur = pins(ctx).toMutableList()
        for (e in items) if (e.isDir && cur.none { it.path.trim('/') == e.path.trim('/') }) cur += Pin(e.ref, e.path)
        savePins(ctx, cur)
        changed()
        Clouds.drive(ctx)?.let { sync(ctx, it, force = true) }
    }

    /**
     * Le cartelle tenute che contengono [path] (o sono lei). («Keep on phone che non riesco a
     * togliere»): «Remove from phone» compariva solo selezionando ESATTAMENTE la cartella tenuta; dentro, l'app
     * riproponeva «Keep on phone». Ora da qualunque punto dentro si risale alla cartella tenuta.
     */
    fun rootsOf(ctx: Context, path: String): List<String> {
        val p = path.trim('/')
        return pins(ctx).map { it.path.trim('/') }.filter { q -> p == q || p.startsWith("$q/") || q.isEmpty() }.distinct()
    }

    /** Toglie la cartella: la copia sul telefono si cancella (solo la copia: sul cloud resta tutto). */
    fun unpin(ctx: Context, items: List<CloudEntry>) = unpinPaths(ctx, items.map { it.path.trim('/') }.toSet())

    fun unpinPaths(ctx: Context, paths: Set<String>) {
        val drop = paths.map { it.trim('/') }.toSet()
        val keep = pins(ctx).filter { it.path.trim('/') !in drop }
        savePins(ctx, keep)
        exec.execute {
            for (p in drop) {
                // se la cartella sta dentro un'altra ancora tenuta, la copia serve ancora
                if (keep.any { val q = it.path.trim('/'); p.startsWith("$q/") || q.isEmpty() }) continue
                local(ctx, p).deleteRecursively()
            }
            changed()
        }
        changed()
    }

    /** Scollegato il cloud: via tutte le copie. */
    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        exec.execute { root(ctx).deleteRecursively(); changed() }
    }

    fun sync(ctx: Context, drive: CloudDrive, force: Boolean = false) {
        if (running) return
        // al più ogni 6 ore aprendo il Cloud (erano 2 minuti, e un giro rilegge tutte le cartelle tenute);
        // anche se l'ultimo è fallito. Scegliere una cartella e «Aggiorna» (force) allineano subito
        // (ricordato anche fra un'apertura e l'altra: Android chiude spesso l'app in sottofondo)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - maxOf(lastSync, prefs.getLong("synced_at", 0)) in 0 until SYNC_GAP) return
        val pins = pins(ctx)
        if (pins.isEmpty()) return
        running = true
        lastSync = System.currentTimeMillis()
        prefs.edit().putLong("synced_at", lastSync).apply()
        lastError = ""
        status = "Checking the folders kept on this phone…"
        changed()
        val app = ctx.applicationContext
        // in primo piano con la sua notifica: continua anche fuori dall'app; se Android non lo lascia partire, si fa come prima, finché l'app è viva
        if (!OfflineService.start(app)) exec.execute { work(app, drive, pins) }
    }

    /** Il servizio è partito ma il cloud non c'è più: si chiude il giro senza far niente. */
    internal fun abandon() { running = false; status = ""; changed() }

    /** L'allineamento vero e proprio: blocca chi lo chiama (il servizio o [exec]). */
    internal fun work(app: Context, drive: CloudDrive, pins0: List<Pin> = pins(app)) {
        stop = false
        try {
            val pins = pins0
            // 1) cosa c'è lassù (tutto l'albero di ogni cartella tenuta)
            val remote = LinkedHashMap<String, CloudEntry>()
            val dirs = HashSet<String>()
            for (p in pins) walk(drive, p.ref, p.path, remote, dirs)
            // 2) quello che manca o è cambiato si scarica
            val todo = remote.values.filter { e ->
                val f = local(app, e.path)
                !f.isFile || f.length() != e.size || (e.mtime > 0 && Math.abs(f.lastModified() - e.mtime) > 2000)
            }
            todo.forEachIndexed { i, e ->
                if (stop) throw IOException("stopped") // Android chiude il servizio: si riprende alla prossima apertura
                status = "Downloading ${i + 1} of ${todo.size} · ${e.name}"
                changed()
                download(drive, e, local(app, e.path))
            }
            // 3) quello che lassù non c'è più sparisce anche qui (solo dentro la copia)
            val keepFiles = remote.keys.map { local(app, it).absolutePath }.toSet()
            for (p in pins) {
                val base = local(app, p.path)
                base.walkBottomUp().forEach { f ->
                    if (f.isFile && f.absolutePath !in keepFiles && !f.name.endsWith(".part")) f.delete()
                    if (f.isFile && f.name.endsWith(".part")) f.delete()
                    if (f.isDirectory && f != base && f.list()?.isEmpty() == true) f.delete()
                }
            }
            lastSync = System.currentTimeMillis()
            status = ""
        } catch (e: CloudException) {
            lastError = e.message ?: "error"; status = ""
        } catch (e: IOException) {
            lastError = if (stop) "" else WebDav.netMessage(e); status = ""
        } catch (e: Throwable) { // anche OutOfMemory: dal ripiego senza servizio ([exec]) chiudeva l'app
            Log.w(TAG, "allineamento offline", e); lastError = "Sync interrupted"; status = ""
        } finally {
            running = false
            changed()
        }
    }

    private fun walk(drive: CloudDrive, ref: String, path: String, out: MutableMap<String, CloudEntry>, dirs: MutableSet<String>) {
        if (!dirs.add(path.trim('/'))) return
        for (e in drive.list(ref, path)) {
            if (e.isDir) walk(drive, e.ref, e.path, out, dirs) else out[e.path.trim('/')] = e
        }
    }

    /** Un download si guadagna il nome: `.part`, dimensione controllata, poi rename; la data è quella del cloud. */
    private fun download(drive: CloudDrive, e: CloudEntry, f: File) {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        var got = 0L
        drive.open(e).use { input -> part.outputStream().use { out -> got = input.copyTo(out, 128 * 1024) } }
        if (e.size >= 0 && got != e.size) { part.delete(); throw IOException("arrived incomplete: ${e.name}") }
        if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) { part.delete(); throw IOException("can't be written") } }
        if (e.mtime > 0) f.setLastModified(e.mtime)
    }

    /** Senza rete: l'elenco di una cartella tenuta, letto dalla copia. */
    fun listLocal(ctx: Context, path: String): List<CloudEntry>? {
        if (!isPinned(ctx, path)) return null
        val d = local(ctx, path)
        val kids = d.listFiles() ?: return null
        val base = path.trim('/')
        return kids.filter { !it.name.endsWith(".part") }.map { f ->
            val p = if (base.isEmpty()) f.name else "$base/${f.name}"
            CloudEntry("offline:$p", f.name, f.isDirectory, if (f.isDirectory) -1 else f.length(), f.lastModified(), null, p)
        }.sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Quanto pesa la copia sul telefono. */
    fun usedBytes(ctx: Context): Long = root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
