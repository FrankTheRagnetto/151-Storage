package com.the151universe.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.the151universe.storage.LinkPlan.Act
import com.the151universe.storage.LinkPlan.Base
import com.the151universe.storage.LinkPlan.Meta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cartelle collegate: una cartella del telefono ⇄ la sua gemella nel cloud, uguali nei due sensi,
 * da sole quando un file cambia, dall'icona che apre l'app, ogni 6 ore ([LinkJob]) e all'apertura di Storage, senza PC. Per esempio le note di un'app di appunti.
 *
 * Cosa decide: [LinkPlan]. Qui si esegue, e niente si perde:
 * - un file del telefono che viene sostituito o cancellato va prima in `files/link-trash/<id>/<giorno>/` (30 giorni);
 * - uno del cloud va in `<cartella>/.151-link-old/<giorno>/` (nascosta, 30 giorni): chi sincronizza solo i nomi visibili non la vede;
 * - prima di scrivere sopra si ricontrolla che il file non sia cambiato nel frattempo (un'app che salva): se sì, si
 *   salta e il giro dopo lo vede;
 * - più di [LinkPlan.MAX_DELETE] cancellazioni in un giro, o un lato vuoto quando prima non lo era: le cancellazioni si
 *   fermano e decide la persona (Settings › Linked folders).
 * File e cartelle nascosti («.obsidian», «.trash») restano fuori: ogni apparecchio tiene le sue impostazioni.
 */
data class Link(val id: String, val local: String, val remote: String, val created: Long)

class LinkStatus(val lastOk: Long, val lastTry: Long, val message: String?, val heldDeletes: Int, val conflicts: Int)

object Links {
    private const val TAG = "151Storage"
    private const val OLD_DIR = ".151-link-old"
    private const val KEEP_DAYS = 30
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "links").apply { mkdirs() }
    private fun listFile(ctx: Context) = File(dir(ctx), "links.json")
    private fun stateFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun day() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    // ------------------------------------------------------------------ elenco dei collegamenti (scritto atomico)

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, "${f.name}.${android.os.Process.myPid()}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("could not save ${f.name}") }
    }

    fun all(ctx: Context): List<Link> = synchronized(lock) {
        val f = listFile(ctx); if (!f.exists()) return emptyList()
        try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Link(it.getString("id"), it.getString("local"), it.getString("remote"), it.optLong("created")) }
        } catch (e: Exception) { Log.w(TAG, "links.json illeggibile: ${e.message}"); emptyList() }
    }

    private fun saveAll(ctx: Context, l: List<Link>) = synchronized(lock) {
        writeAtomic(listFile(ctx), JSONArray(l.map { JSONObject().put("id", it.id).put("local", it.local).put("remote", it.remote).put("created", it.created) }).toString())
    }

    fun add(ctx: Context, local: String, remote: String): Link {
        val l = Link(Trashes.newId(), local.trimEnd('/'), remote.trim('/'), System.currentTimeMillis())
        saveAll(ctx, all(ctx).filter { it.local != l.local } + l)
        LinkJob.schedule(ctx)
        return l
    }

    /** Scollega: i file restano dove sono, da tutte e due le parti. */
    fun remove(ctx: Context, id: String) {
        saveAll(ctx, all(ctx).filter { it.id != id })
        stateFile(ctx, id).delete()
        if (all(ctx).isEmpty()) LinkJob.cancel(ctx)
    }

    // ------------------------------------------------------------------ memoria di ogni collegamento

    private class State(var base: HashMap<String, Base>?, var lastOk: Long, var lastTry: Long, var message: String?, var heldDeletes: Int,
                        var conflicts: Int, var swept: Long)

    private fun load(ctx: Context, id: String): State {
        val f = stateFile(ctx, id)
        if (!f.exists()) return State(null, 0, 0, null, 0, 0, 0)
        val j = JSONObject(f.readText())
        val base = j.optJSONObject("base")?.let { b ->
            HashMap<String, Base>(b.length() * 2).apply {
                for (k in b.keys()) { val a = b.getJSONArray(k); put(k, Base(Meta(a.getLong(0), a.getLong(1)), Meta(a.getLong(2), a.getLong(3)))) }
            }
        }
        return State(base, j.optLong("lastOk"), j.optLong("lastTry"), j.optString("message").ifEmpty { null }, j.optInt("held"), j.optInt("conflicts"), j.optLong("swept"))
    }

    private fun save(ctx: Context, id: String, s: State) {
        val j = JSONObject().put("lastOk", s.lastOk).put("lastTry", s.lastTry).put("message", s.message ?: "").put("held", s.heldDeletes)
            .put("conflicts", s.conflicts).put("swept", s.swept)
        s.base?.let { b -> j.put("base", JSONObject().apply { for ((k, v) in b) put(k, JSONArray().put(v.local.size).put(v.local.mtime).put(v.remote.size).put(v.remote.mtime)) }) }
        writeAtomic(stateFile(ctx, id), j.toString())
    }

    fun status(ctx: Context, id: String): LinkStatus = load(ctx, id).let { LinkStatus(it.lastOk, it.lastTry, it.message, it.heldDeletes, it.conflicts) }

    // ------------------------------------------------------------------ i due elenchi

    private fun hidden(name: String) = name.startsWith('.')

    fun listLocal(root: File): Map<String, Meta> {
        val out = HashMap<String, Meta>()
        root.walkTopDown().onEnter { it == root || !hidden(it.name) }.forEach { f ->
            if (f.isFile && !hidden(f.name)) out[f.relativeTo(root).invariantSeparatorsPath] = Meta(f.length(), f.lastModified())
        }
        return out
    }

    /** Il cloud: percorso relativo alla cartella collegata → voce. Lancia se l'elenco non è completo (mai decidere su metà). */
    fun listRemote(drive: CloudDrive, rel: String): Map<String, CloudEntry> {
        val out = HashMap<String, CloudEntry>()
        val prefix = "$rel/"
        val tree = drive.listAll(rel)
        if (tree != null) {
            if (!tree.complete) throw IOException("The cloud didn't list everything: trying again later")
            for (e in tree.entries) if (!e.isDir && e.path.startsWith(prefix)) out[e.path.removePrefix(prefix)] = e
            return out
        }
        fun walk(p: String) {
            val ref = drive.resolveDir(p, create = false) ?: return
            for (e in drive.list(ref, p)) { if (e.isDir) walk(e.path.trim('/')) else out[e.path.trim('/').removePrefix(prefix)] = e }
        }
        walk(rel)
        return out
    }

    private fun meta(e: CloudEntry) = Meta(e.size, e.mtime)

    // ------------------------------------------------------------------ anteprima della prima volta

    class Preview(val onlyPhone: List<String>, val onlyCloud: List<String>, val different: List<String>, val same: Int)

    /** Cosa farebbe il primo giro: niente si scrive. Fuori dal thread principale. */
    fun preview(drive: CloudDrive, local: File, remote: String): Preview {
        val l = listLocal(local)
        val r = if (drive.resolveDir(remote, create = false) == null) emptyMap() else listRemote(drive, remote).mapValues { meta(it.value) }
        val steps = LinkPlan.plan(l, r, null)
        val by = steps.groupBy({ it.act }, { it.path })
        // «stessa dimensione, data diversa»: qui si guarda il contenuto solo dove il cloud lo sa dire da sé
        val check = by[Act.SAME_CHECK].orEmpty()
        val sums = if (check.isEmpty()) emptyMap() else (drive.md5(check.map { "$remote/$it" }) ?: emptyMap())
        val reallyDiff = check.filter { p -> sums["$remote/$p"]?.let { it != md5(File(local, p)) } ?: true }
        return Preview(by[Act.UPLOAD].orEmpty(), by[Act.DOWNLOAD].orEmpty(), by[Act.KEEP_BOTH].orEmpty() + reallyDiff,
            by[Act.ADOPT].orEmpty().size + (check.size - reallyDiff.size))
    }

    private fun md5(f: File): String {
        val d = MessageDigest.getInstance("MD5")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ il giro

    /** Tutti i collegamenti, uno alla volta (mai due giri insieme). [reason] per il registro. */
    fun runAll(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        val links = all(app); if (links.isEmpty()) return
        synchronized(running) {
            val drive = Clouds.freshDrive(app) ?: return
            try { for (l in links) run(app, l, drive, reason) } finally { (drive as? SftpDrive)?.shutdown() }
        }
    }
    private val running = Any()

    enum class Held { ASK, DELETE, RESTORE }

    fun runOne(ctx: Context, id: String, held: Held) {
        val app = ctx.applicationContext
        val l = all(app).firstOrNull { it.id == id } ?: return
        synchronized(running) {
            val drive = Clouds.freshDrive(app) ?: return
            try { run(app, l, drive, "by hand", held) } finally { (drive as? SftpDrive)?.shutdown() }
        }
    }

    private fun run(ctx: Context, link: Link, drive: CloudDrive, reason: String, held: Held = Held.ASK) {
        val s = load(ctx, link.id)
        s.lastTry = System.currentTimeMillis()
        try {
            val root = File(link.local)
            if (!root.isDirectory) throw IOException("The phone folder isn't there any more")
            val first = s.base == null
            if (drive.resolveDir(link.remote, create = first) == null) throw IOException("The cloud folder isn't there any more")
            val base = s.base ?: HashMap()
            var conflicts = 0
            var sentUp = 0
            for (pass in 1..2) { // il secondo passo porta dall'altra parte le copie dei conflitti appena nate
                val local = listLocal(root)
                val remote = listRemote(drive, link.remote)
                if (base.isNotEmpty() && (local.isEmpty() || remote.isEmpty()))
                    throw IOException(if (local.isEmpty()) "The phone folder looks empty: nothing deleted, check it" else "The cloud folder looks empty: nothing deleted, check it")
                // la prima volta (nessuna memoria) si unisce soltanto: LinkPlan con base null non cancella mai
                var steps = LinkPlan.plan(local, remote.mapValues { meta(it.value) }, if (first && pass == 1) null else base)
                val dels = LinkPlan.deletions(steps)
                if (pass == 1) s.heldDeletes = 0
                if (dels > LinkPlan.MAX_DELETE) when (held) {
                    Held.ASK -> { s.heldDeletes = dels; steps = steps.filter { it.act != Act.DEL_LOCAL && it.act != Act.DEL_REMOTE } }
                    Held.RESTORE -> steps = steps.map { when (it.act) { Act.DEL_LOCAL -> it.copy(act = Act.UPLOAD); Act.DEL_REMOTE -> it.copy(act = Act.DOWNLOAD); else -> it } }
                    Held.DELETE -> {}
                }
                if (steps.isEmpty()) break
                val ex = Exec(ctx, link, drive, root, local, remote, base)
                s.base = base // da qui il collegamento ha una memoria: le prossime volte si sa chi è cambiato
                try {
                    for ((i, st) in steps.withIndex()) {
                        try { ex.apply(st) } catch (e: IOException) { throw e } catch (e: Exception) { Log.w(TAG, "collegata ${st.act} ${st.path}: ${e.message}") }
                        if (i % 50 == 49) save(ctx, link.id, s)
                    }
                } finally { conflicts += ex.conflicts; sentUp += ex.sentUp; save(ctx, link.id, s) }
            }
            s.base = base
            s.lastOk = System.currentTimeMillis()
            s.message = null
            s.conflicts += conflicts
            if (System.currentTimeMillis() - s.swept > 20 * 3_600_000L) { sweep(ctx, link, drive); s.swept = System.currentTimeMillis() }
            save(ctx, link.id, s)
            if (conflicts > 0) notifyConflicts(ctx, link, conflicts)
            if (sentUp > 0) ping(drive, link)
        } catch (e: Exception) {
            s.message = e.message ?: "Couldn't sync"
            Log.w(TAG, "collegata ${link.local} ($reason): ${s.message}")
            save(ctx, link.id, s)
        }
    }

    /**
     * Il campanello: se nella cartella del cloud c'è `.151-ping.json` (lo mette il computer:
     * indirizzo + chiave), dopo aver mandato qualcosa il telefono suona, e il computer prende subito. Computer spento o
     * fuori rete: niente di male, le note aspettano nel cloud. Mai un errore mostrato.
     */
    private fun ping(drive: CloudDrive, link: Link) {
        try {
            val j = JSONObject(String(drive.readSmall("${link.remote}/.151-ping.json") ?: return, Charsets.UTF_8))
            val url = j.optString("url").takeIf { it.startsWith("http") } ?: return
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 3_000; c.readTimeout = 3_000
            c.setRequestProperty("X-151-Token", j.optString("token"))
            c.doOutput = true; c.outputStream.use { }
            Log.i(TAG, "campanello del computer: ${c.responseCode}")
            c.disconnect()
        } catch (e: Exception) { Log.i(TAG, "campanello del computer non suonato: ${e.message}") }
    }

    /** Via le copie tenute da parte più vecchie di 30 giorni, sul telefono e nel cloud. */
    private fun sweep(ctx: Context, link: Link, drive: CloudDrive) {
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L))
        val day = Regex("""\d{4}-\d{2}-\d{2}""")
        File(ctx.filesDir, "link-trash/${link.id}").listFiles()?.forEach { if (it.name.matches(day) && it.name < cutoff) it.deleteRecursively() }
        try {
            val p = "${link.remote}/$OLD_DIR"
            val ref = drive.resolveDir(p, create = false) ?: return
            for (e in drive.list(ref, p)) if (e.isDir && e.name.matches(day) && e.name < cutoff) drive.deletePath("$p/${e.name}", isDir = true)
        } catch (e: Exception) { Log.w(TAG, "copie vecchie del cloud non tolte: ${e.message}") }
    }

    /** Esegue i passi di un giro, aggiornando [base] file per file. */
    private class Exec(val ctx: Context, val link: Link, val drive: CloudDrive, val root: File,
                       val local: Map<String, Meta>, val remote: Map<String, CloudEntry>, val base: HashMap<String, Base>) {
        var conflicts = 0
        /** Quante cose sono cambiate lassù in questo giro (per avvisare il computer: [ping]). */
        var sentUp = 0
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val stamp = SimpleDateFormat("HHmmss", Locale.ROOT).format(Date())

        fun lf(p: String) = File(root, p)
        fun rp(p: String) = "${link.remote}/$p"
        fun now(f: File) = Meta(f.length(), f.lastModified())
        /** Il file del telefono è ancora com'era nell'elenco? (un'app può salvare proprio adesso) */
        fun localSame(p: String): Boolean { val f = lf(p); val m = local[p]; return if (m == null) !f.exists() else f.isFile && now(f) == m }
        fun remoteSame(p: String): Boolean {
            val was = remote[p]; val cur = try { drive.info(was?.ref ?: WebDav.encPath(rp(p)).trimEnd('/')) } catch (_: CloudException) { null }
            return if (was == null) cur == null else cur != null && cur.size == was.size && kotlin.math.abs(cur.mtime - was.mtime) < 2_000
        }

        fun apply(st: LinkPlan.Step) {
            val p = st.path
            when (st.act) {
                Act.ADOPT -> base[p] = Base(local.getValue(p), meta(remote.getValue(p)))
                Act.FORGET -> base.remove(p)
                Act.UPLOAD -> upload(p)
                Act.DOWNLOAD -> download(p)
                Act.DEL_LOCAL -> { if (localSame(p)) { keepLocal(p); lf(p).delete(); prune(lf(p).parentFile); base.remove(p) } }
                Act.DEL_REMOTE -> { val e = remote.getValue(p); if (remoteSame(p)) { keepRemote(p, e); base.remove(p); sentUp++ } }
                Act.SAME_CHECK -> {
                    val sum = drive.md5(listOf(rp(p)))?.get(rp(p)) ?: remoteMd5(remote.getValue(p))
                    if (sum == md5(lf(p))) base[p] = Base(local.getValue(p), meta(remote.getValue(p))) else keepBoth(p)
                }
                Act.KEEP_BOTH -> keepBoth(p)
            }
        }

        fun remoteMd5(e: CloudEntry): String {
            val d = MessageDigest.getInstance("MD5")
            drive.open(e).use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
            return d.digest().joinToString("") { "%02x".format(it) }
        }

        /** Cambiato da tutte e due le parti: il più nuovo tiene il nome, l'altro diventa «(conflict …)» accanto. */
        fun keepBoth(p: String) {
            val l = local.getValue(p); val r = remote.getValue(p)
            if (!localSame(p) || !remoteSame(p)) return
            conflicts++
            if (l.mtime >= r.mtime) {
                val n = LinkPlan.conflictName(p, "cloud", today).substringAfterLast('/')
                drive.rename(r, n) // la versione del cloud resta lassù col nome nuovo; il giro dopo la porta qui
                upload(p, replacing = false)
            } else {
                val dest = lf(LinkPlan.conflictName(p, "phone", today))
                if (dest.exists() || !lf(p).renameTo(dest)) throw IOException("could not keep both versions of $p")
                download(p, replacing = false)
            }
        }

        fun upload(p: String, replacing: Boolean = true) {
            val f = lf(p)
            if (!localSame(p)) return
            val e = remote[p]
            // cambiata solo la data, non il contenuto (14/11: su un telefono vero una nota appena scaricata tornava con la data vecchia
            // e il giro dopo la rimandava su): si confronta il contenuto e, se è uguale, si aggiorna solo la memoria
            if (replacing && e != null && e.size == f.length()) {
                val sum = drive.md5(listOf(rp(p)))?.get(rp(p)) ?: remoteMd5(e)
                if (sum == md5(f)) { base[p] = Base(now(f), meta(e)); return }
            }
            if (replacing && e != null) { if (!remoteSame(p)) return; keepRemote(p, e) }
            val dir = rp(p).substringBeforeLast('/')
            val ref = drive.resolveDir(dir, create = true) ?: throw IOException("cloud folder $dir not found")
            val m = now(f)
            when (val res = drive.upload(ref, f.name, m.size, { f.inputStream() }, {}, { false }, m.mtime)) {
                is UploadResult.Done -> if (res.finalName != f.name) { Log.w(TAG, "collegata: $p arrivato come ${res.finalName}"); return }
                is UploadResult.Skipped -> {}
                is UploadResult.Failed -> throw IOException(res.message)
                UploadResult.Cancelled -> return
            }
            sentUp++
            val up = drive.info(WebDav.encPath(rp(p)).trimEnd('/')) ?: return
            base[p] = Base(m, meta(up))
        }

        fun download(p: String, replacing: Boolean = true) {
            val e = remote.getValue(p)
            val dest = lf(p)
            if (replacing && !localSame(p)) return
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, ".${dest.name}.151part")
            try {
                drive.open(e).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                if (tmp.length() != e.size) throw IOException("$p arrived incomplete")
                if (e.mtime > 0) tmp.setLastModified(e.mtime)
                if (replacing && !localSame(p)) return // cambiato mentre scaricavo: il giro dopo lo vede come conflitto
                if (dest.exists()) keepLocal(p)
                if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw IOException("could not write $p") }
                if (e.mtime > 0 && kotlin.math.abs(dest.lastModified() - e.mtime) >= 2_000) dest.setLastModified(e.mtime) // la data del cloud anche dopo la rinomina
            } finally { tmp.delete() }
            base[p] = Base(now(dest), meta(e))
        }

        /** Copia da parte del file del telefono, prima di sostituirlo o cancellarlo. */
        fun keepLocal(p: String) {
            val src = lf(p); if (!src.exists()) return
            val d = File(ctx.filesDir, "link-trash/${link.id}/$today/$p")
            val dest = if (d.exists()) File(d.parentFile, "$stamp-${d.name}") else d
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            dest.setLastModified(src.lastModified())
        }

        /** Il file del cloud si sposta (non si cancella) nella cartella nascosta delle copie, 30 giorni. */
        fun keepRemote(p: String, e: CloudEntry) {
            val sub = p.substringBeforeLast('/', "")
            val dir = listOf(link.remote, OLD_DIR, today, sub).filter { it.isNotEmpty() }.joinToString("/")
            drive.moveTo(e, dir, "$stamp-${e.name}")
        }

        /** Le cartelle rimaste vuote dopo una cancellazione se ne vanno, fino alla cartella collegata (esclusa). */
        fun prune(d: File?) {
            var c = d
            while (c != null && c != root && c.absolutePath.startsWith(root.absolutePath) && (c.list()?.isEmpty() == true)) { c.delete(); c = c.parentFile }
        }
    }

    // ------------------------------------------------------------------ avvisi

    private const val CHANNEL = "links"

    private fun notifyConflicts(ctx: Context, link: Link, n: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linked folders", NotificationManager.IMPORTANCE_LOW))
        val name = File(link.local).name
        val n2 = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(ctx.getString(R.string.link_conflict_title, name))
            .setContentText(ctx.resources.getQuantityString(R.plurals.link_conflict_text, n, n))
            .setAutoCancel(true).build()
        try { nm.notify(("link" + link.id).hashCode(), n2) } catch (_: SecurityException) {}
    }
}

/**
 * Quando gira:
 * - [ID_CHANGE]: Android sveglia Storage quando un file del telefono cambia; se è dentro una cartella collegata, giro subito
 *   (niente processo acceso, niente notifica). Si riprogramma a ogni giro: un lavoro «a contenuto» vale una volta sola.
 * - [ID_NOW]: subito, una volta (l'icona sulla schermata principale che apre l'app e intanto prende le novità: [LinkLaunch]).
 * - [ID_SAFETY]: controllo di sicurezza ogni 6 ore (il server non avvisa nessuno), sopravvive al riavvio.
 * - all'apertura di Storage ([kick]).
 */
class LinkJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread({
            try {
                // Android 16 (telefono vero, 14/11): un'app in sottofondo non ha rete («blocked=APP_BACKGROUND»), e un lavoro normale
                // aspetta la rete per sempre. Solo il lavoro «urgente» la riceve: i giri veri passano tutti da [ID_NOW].
                when (params.jobId) {
                    ID_CHANGE -> { scheduleChange(app); if (touchesLinked(app, params)) now(app) }
                    ID_SAFETY -> now(app)
                    else -> Links.runAll(app, "now")
                }
            } catch (e: Throwable) { Log.w("151Storage", "giro delle collegate", e) }
            jobFinished(params, false)
        }, "cartelle-collegate").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    /** I file cambiati stanno in una cartella collegata? (le foto della fotocamera non svegliano la rete) */
    private fun touchesLinked(ctx: Context, params: JobParameters): Boolean {
        val roots = Links.all(ctx).map { it.local.trimEnd('/') + "/" }
        if (roots.isEmpty()) return false
        val uris = params.triggeredContentUris ?: return true // troppi cambiamenti: Android non li elenca, si guarda tutto
        for (u in uris) {
            val path = try {
                ctx.contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null }
            if (path == null) return true // cancellato: non si sa dov'era, si guarda
            if (roots.any { path.startsWith(it) }) return true
        }
        return false
    }

    companion object {
        private const val ID_SAFETY = 151_015 // era il giro ogni 15 minuti: stesso numero, così il vecchio viene sostituito
        private const val ID_CHANGE = 151_016
        private const val ID_NOW = 151_017
        private const val SAFETY_EVERY = 6 * 3_600_000L

        private fun js(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

        fun schedule(ctx: Context) {
            val js = js(ctx) ?: return
            // mai un crash per il giro (14:27 13/11: permesso mancante = app chiusa)
            try {
                val p = js.getPendingJob(ID_SAFETY)
                if (p == null || p.intervalMillis != SAFETY_EVERY || (Build.VERSION.SDK_INT >= 28 && p.requiredNetwork != null)) js.schedule(JobInfo.Builder(ID_SAFETY, ComponentName(ctx, LinkJob::class.java))
                    .setPeriodic(SAFETY_EVERY).setPersisted(true).build())
            } catch (e: Exception) { Log.w("151Storage", "controllo di sicurezza non programmato: ${e.message}") }
            // anche quello di 0.10.42, che aspettava la rete (e su Android 16 non partiva mai): si sostituisce
            val c = js.getPendingJob(ID_CHANGE)
            if (c == null || (Build.VERSION.SDK_INT >= 28 && c.requiredNetwork != null)) scheduleChange(ctx)
        }

        fun scheduleChange(ctx: Context) {
            try {
                js(ctx)?.schedule(JobInfo.Builder(ID_CHANGE, ComponentName(ctx, LinkJob::class.java))
                    .addTriggerContentUri(JobInfo.TriggerContentUri(android.provider.MediaStore.Files.getContentUri("external"),
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(5_000) // un'app di appunti salva a ogni pausa: si aspetta che smetta
                    .setTriggerContentMaxDelay(60_000)
                    .build())
            } catch (e: Exception) { Log.w("151Storage", "ascolto dei cambiamenti non programmato: ${e.message}") }
        }

        /** Un giro adesso, anche ad app chiusa (l'icona che apre l'app). */
        fun now(ctx: Context) {
            try {
                val b = JobInfo.Builder(ID_NOW, ComponentName(ctx, LinkJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                if (Build.VERSION.SDK_INT >= 31) b.setExpedited(true) // urgente: ha la rete anche ad app in sottofondo
                try { js(ctx)?.schedule(b.build()) } catch (e: Exception) {
                    // urgenti finiti per oggi (quota di Android): normale, partirà appena può
                    if (Build.VERSION.SDK_INT >= 31) js(ctx)?.schedule(b.setExpedited(false).build()) else throw e
                }
            } catch (e: Exception) { Log.w("151Storage", "giro subito non programmato: ${e.message}") }
        }

        fun cancel(ctx: Context) { js(ctx)?.let { it.cancel(ID_SAFETY); it.cancel(ID_CHANGE); it.cancel(ID_NOW) } }

        private val last = java.util.concurrent.atomic.AtomicLong(0)

        /** All'apertura dell'app: un giro subito (non più di uno al minuto). */
        fun kick(ctx: Context) {
            val app = ctx.applicationContext
            if (Links.all(app).isEmpty()) return
            schedule(app)
            val now = System.currentTimeMillis()
            if (now - last.get() < 60_000) return
            last.set(now)
            Thread({ try { Links.runAll(app, "app opened") } catch (e: Throwable) { Log.w("151Storage", "giro all'apertura", e) } }, "cartelle-collegate-apertura").start()
        }
    }
}

/**
 * L'icona sulla schermata principale: logo e nome dell'app scelta; toccandola si apre subito
 * l'app e intanto Storage prende dal cloud le novità. Nessuna schermata di Storage.
 */
class LinkLaunch : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        LinkJob.now(this)
        intent.getStringExtra(EXTRA_PKG)?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                try { startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w("151Storage", "app non aperta: ${e.message}") }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
