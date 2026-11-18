package com.the151universe.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Un file da caricare. [src] = content:// o percorso; [dest] = cartella leggibile sul cloud. */
class UpJob(
    val id: Long,
    val src: String,
    val name: String,
    val size: Long,
    val dest: String,
    var state: String = WAIT,
    var msg: String = "",
    var sent: Long = 0,
    var attempts: Int = 0,
    var remoteName: String = "",
    var time: Long = System.currentTimeMillis(),
    /** «Delete from phone after upload»: nel cestino del telefono SOLO a caricamento confermato. */
    var thenDelete: Boolean = false,
) {
    val finished get() = state == DONE || state == SKIP
    fun json() = JSONObject().put("id", id).put("src", src).put("name", name).put("size", size).put("dest", dest)
        .put("state", state).put("msg", msg).put("attempts", attempts).put("remote", remoteName).put("time", time).put("del", thenDelete)

    companion object {
        const val WAIT = "wait"; const val RUN = "run"; const val DONE = "done"; const val SKIP = "skip"; const val FAIL = "fail"
        fun of(j: JSONObject) = UpJob(j.getLong("id"), j.getString("src"), j.getString("name"), j.getLong("size"), j.getString("dest"),
            j.optString("state", WAIT).let { if (it == RUN) WAIT else it }, j.optString("msg"), 0, j.optInt("attempts"),
            j.optString("remote"), j.optLong("time"), j.optBoolean("del"))
    }
}

/**
 * La coda dei caricamenti, scritta su disco a ogni cambio (atomica: file temporaneo col PID accanto,
 * poi rename): se il telefono spegne l'app a metà, al giro dopo si riparte da dove si era.
 * Il lavoro lo fa [UploadService], uno alla volta, in primo piano con la notifica.
 */
object Uploads {
    private const val TAG = "151Storage"
    private const val FILE = "uploads.json"
    private const val DONE_FILE = "uploaded.json"
    private val lock = Any()
    @Volatile private var jobs: MutableList<UpJob>? = null
    /** Già nel cloud (caricati o trovati uguali da qui): chiave = percorso sul telefono o URI, valore = dimensione. */
    @Volatile private var done: java.util.concurrent.ConcurrentHashMap<String, Long>? = null
    private val listeners = mutableSetOf<() -> Unit>()
    @Volatile var stopRequested = false
    /** «Stop» dell'utente (non il tempo finito di Android): i file a metà strada escono dalla coda, non ripartono all'apertura */
    @Volatile var userStopped = false
    /** sale quando un giro ha tolto file dal telefono: le liste delle cartelle si rileggono (la cartella svuotata restava lì) */
    @Volatile var phoneChanges = 0
    /** quando è cominciato il giro in corso: il conto «X of Y» è di questo giro, non dei finiti di prima (09/11: «4252 of 21600») */
    @Volatile private var roundStart = 0L
    /** true mentre si carica: il giro delle anteprime aspetta (stesse strade verso il server, stesso tempo concesso da Android). */
    @Volatile var busy = false

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    /**
     * Gli schermi si ridisegnano al più 2 volte al secondo: con 8 lavoratori arrivavano decine di avvisi al secondo,
     * ognuno col riepilogo dell'intera coda, e l'app scattava.
     */
    fun changed() {
        if (pending.getAndSet(true)) return
        val wait = (500 - (System.currentTimeMillis() - lastFired)).coerceIn(0, 500)
        main.postDelayed({ pending.set(false); lastFired = System.currentTimeMillis(); synchronized(listeners) { listeners.toList() }.forEach { it() } }, wait)
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val pending = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var lastFired = 0L

    fun all(ctx: Context): List<UpJob> = synchronized(lock) { load(ctx).toList() }

    private fun load(ctx: Context): MutableList<UpJob> {
        jobs?.let { return it }
        val l = ArrayList<UpJob>()
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.isFile) JSONArray(f.readText()).let { a -> for (i in 0 until a.length()) l += UpJob.of(a.getJSONObject(i)) }
        } catch (e: Exception) { Log.w(TAG, "coda illeggibile", e) }
        jobs = l
        return l
    }

    fun save(ctx: Context) = synchronized(lock) {
        val l = load(ctx)
        // a coda ferma si tengono 300 righe; durante un giro i finiti restano, se no il conto «X of Y» scendeva
        // invece di salire e sembrava fermo («Uploading 1 of 18135»)
        val cut = Freni.trimQueue(l, System.currentTimeMillis())
        if (cut > 0) Log.i(TAG, "coda: tagliate $cut righe, restano ${l.size}")
        val snap = ArrayList(l)
        jobsWritten = System.currentTimeMillis()
        writeLater(File(ctx.filesDir, FILE)) { JSONArray().apply { snap.forEach { put(it.json()) } }.toString() }
    }

    // la scrittura su disco (MB con migliaia di file) fuori dal lucchetto della coda: gli schermi non la aspettano.
    // Un numero d'ordine impedisce che una copia vecchia scriva sopra una più nuova.
    private val writeLock = Any()
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val written = HashMap<String, Long>()
    /** Il testo si prepara nella strada che scrive, non sotto il lucchetto della coda (18.000 righe = centinaia di ms). */
    private fun writeLater(target: File, text: () -> String) {
        val n = seq.incrementAndGet()
        Thread {
            synchronized(writeLock) {
                if ((written[target.name] ?: 0) > n) return@Thread
                try { writeAtomic(target, text()); written[target.name] = n } catch (e: Exception) { Log.w(TAG, "non scritto: ${target.name}", e) }
            }
        }.start()
    }

    fun add(ctx: Context, items: List<Triple<String, String, Long>>, thenDelete: Boolean = false, dest: (Int) -> String) {
        synchronized(lock) {
            val l = load(ctx)
            var id = (l.maxOfOrNull { it.id } ?: 0) + 1
            if (l.none { it.state == UpJob.WAIT || it.state == UpJob.RUN }) roundStart = System.currentTimeMillis()
            // già in coda: un insieme, non un giro della coda per ogni file (18.000 file = milioni di confronti)
            val queued = l.filter { !it.finished && it.state != UpJob.FAIL }.mapTo(HashSet()) { it.src }
            items.forEachIndexed { i, (src, name, size) ->
                if (queued.add(src)) l += UpJob(id++, src, name, size, dest(i), thenDelete = thenDelete)
            }
        }
        Log.i(TAG, "coda: +${items.size} richiesti, ora ${jobs?.size} righe (${Thread.currentThread().name})")
        freshSummary()
        save(ctx)
        changed()
        UploadService.start(ctx)
    }

    fun retryFailed(ctx: Context) {
        freshSummary()
        synchronized(lock) { load(ctx).filter { it.state == UpJob.FAIL }.forEach { it.state = UpJob.WAIT; it.attempts = 0; it.msg = "" } }
        save(ctx); changed(); UploadService.start(ctx)
    }

    fun clearFinished(ctx: Context) {
        freshSummary()
        // anche i falliti (11/11): prima si potevano solo riprovare, e «41 files not uploaded» restava per sempre (telefono di prova, dal 09/11)
        synchronized(lock) { load(ctx).removeAll { it.finished || it.state == UpJob.FAIL } }
        save(ctx); changed()
    }

    /** «Ferma tutto»: la coda si svuota, il file in corso si interrompe; sul cloud non resta niente di mezzo. */
    fun stopAll(ctx: Context) {
        freshSummary()
        userStopped = true
        stopRequested = true
        synchronized(lock) { load(ctx).removeAll { it.state == UpJob.WAIT || it.state == UpJob.FAIL } }
        save(ctx); changed()
    }

    /**
     * All'apertura, fuori dal filo principale: legge la coda e, se c'è da potare, la riscrive corta.
     * Poi avvisa gli schermi, che fino a qui non l'avevano chiesta.
     */
    fun tidy(ctx: Context): List<UpJob> {
        val l = synchronized(lock) {
            val l = load(ctx)
            if (Freni.trimQueue(ArrayList(l), System.currentTimeMillis()) > 0) save(ctx)
            l.toList()
        }
        freshSummary(); changed()
        return l
    }

    fun next(ctx: Context): UpJob? = synchronized(lock) { load(ctx).firstOrNull { it.state == UpJob.WAIT } }

    data class Summary(val total: Int, val finished: Int, val failed: Int, val skipped: Int, val running: UpJob?, val bytesLeft: Long)

    /** Il giro in corso: dai file in coda o in corso, più i finiti dell'ultima ora. */
    fun summary(ctx: Context): Summary {
        // ogni lavoratore lo chiede a ogni file e lo schermo 2 volte al secondo: con 18.000 file in coda erano milioni
        // di passi al secondo dentro il lucchetto (99° percentile 350 ms sul telefono di prova); un riepilogo vale 400 ms
        val now = System.currentTimeMillis()
        lastSummary?.let { if (now - lastSummaryAt < 400) return it }
        return computeSummary(ctx).also { lastSummary = it; lastSummaryAt = now }
    }
    /** Per lo schermo: null finché la coda non è in memoria (leggerla sono MB: lo fa [tidy] fuori dal filo principale). */
    fun summaryIfReady(ctx: Context): Summary? = if (jobs == null) null else summary(ctx)
    @Volatile private var lastSummary: Summary? = null
    @Volatile private var lastSummaryAt = 0L
    /** dopo un cambio vero della coda (aggiunti, fermati, puliti) il riepilogo si rifà subito */
    fun freshSummary() { lastSummaryAt = 0L }

    private fun computeSummary(ctx: Context): Summary = synchronized(lock) {
        val l = load(ctx)
        val active = l.any { it.state == UpJob.WAIT || it.state == UpJob.RUN }
        val since = if (roundStart > 0) roundStart - 1_000L
            else if (active) (l.filter { !it.finished }.minOfOrNull { it.time } ?: 0) - 3_600_000L else System.currentTimeMillis() - 30_000
        val cur = l.filter { !it.finished || it.time >= since }
        Summary(cur.size, cur.count { it.finished }, cur.count { it.state == UpJob.FAIL }, cur.count { it.state == UpJob.SKIP },
            cur.firstOrNull { it.state == UpJob.RUN }, cur.filter { !it.finished }.sumOf { (it.size - it.sent).coerceAtLeast(0) })
    }

    // ------------------------------------------------------------ «già nel cloud»

    private fun doneMap(ctx: Context): java.util.concurrent.ConcurrentHashMap<String, Long> {
        done?.let { return it }
        val m = java.util.concurrent.ConcurrentHashMap<String, Long>()
        try {
            val f = File(ctx.filesDir, DONE_FILE)
            if (f.isFile) JSONObject(f.readText()).let { o -> o.keys().forEach { k -> m[k] = o.getLong(k) } }
        } catch (e: Exception) { Log.w(TAG, "indice dei caricati illeggibile", e) }
        done = m
        return m
    }

    /** Senza lucchetto: ogni riga di ogni elenco lo chiede, e aspettava i lavoratori (schermi a scatti, 09/11). */
    fun isUploaded(ctx: Context, key: String?, size: Long): Boolean =
        key != null && (done ?: synchronized(lock) { doneMap(ctx) })[key] == size

    /**
     * L'indice dei caricati ha una riga per ogni file mai mandato (decine di migliaia): riscriverlo intero a ogni
     * file rendeva lenta una cartella grande. Si scrive al più ogni 30 s (
     * ogni 2 s erano MB riscritti di continuo per ore), e sempre alla fine con [flush]; se il telefono spegne l'app in
     * quei 30 s, il file si ritrova uguale lassù e si salta.
     */
    fun markUploaded(ctx: Context, key: String, size: Long) = synchronized(lock) {
        doneMap(ctx)[key] = size
        doneDirty = true
        if (Freni.doneWriteDue(System.currentTimeMillis(), doneWritten)) writeDone(ctx)
    }

    private var doneDirty = false
    private var doneWritten = 0L
    private var jobsWritten = 0L
    private fun writeDone(ctx: Context) {
        val m = doneMap(ctx)
        writeLater(File(ctx.filesDir, DONE_FILE)) { JSONObject(HashMap(m) as Map<*, *>).toString() }
        doneDirty = false; doneWritten = System.currentTimeMillis()
    }

    /** La coda su disco al più ogni 10 s (18.000 righe = MB da riscrivere) durante il lavoro (lo stato vero resta in memoria). */
    fun saveSoon(ctx: Context) = synchronized(lock) { if (System.currentTimeMillis() - jobsWritten >= 10_000) save(ctx) }

    /** Tutto su disco adesso: a fine giro e dopo il controllo del foglietto. */
    fun flush(ctx: Context) = synchronized(lock) { if (doneDirty) writeDone(ctx); save(ctx) }

    /** Un file interrotto dallo «Stop»: fuori dalla coda. */
    fun drop(j: UpJob) = synchronized(lock) { jobs?.remove(j) }

    /** Il prossimo in attesa, preso da UN lavoratore solo (ce ne sono più insieme). */
    fun claim(ctx: Context): UpJob? = synchronized(lock) {
        load(ctx).firstOrNull { it.state == UpJob.WAIT }?.also { it.state = UpJob.RUN; it.sent = 0; it.msg = "" }
    }

    /** Il cloud è cambiato (scollegato o un altro): il segno «nel cloud» non vale più. */
    fun forgetUploaded(ctx: Context) = synchronized(lock) {
        freshSummary()
        done = java.util.concurrent.ConcurrentHashMap()
        File(ctx.filesDir, DONE_FILE).delete()
        jobs = ArrayList()
        File(ctx.filesDir, FILE).delete()
        changed()
    }

    fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.${android.os.Process.myPid()}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}

/** Il lavoro dei caricamenti, in primo piano: notifica con l'avanzamento, «Ferma», e il telefono non dorme a metà. */
class UploadService : Service() {
    private var worker: Thread? = null
    /**
     * La notifica dei caricamenti di adesso: l'avanzamento passa solo finché il servizio è in primo piano. Prima,
     * finito il tempo di Android, il primo piano si STACCAVA lasciando «Uploading…» in corso, e le corsie continuavano a
     * mandarne: notifica orfana, ferma, anche a processo morto.
     */
    @Volatile private var fg: ForegroundNote<Notification>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Uploads.stopAll(this)
            return START_NOT_STICKY
        }
        channel(this)
        val n = progressNote(this, "Preparing uploads…", "", 0, 0, true)
        try {
            ServiceCompat.startForeground(this, NOTE_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // Android ha finito il tempo del giorno per i trasferimenti (crash 08/11 09:07): niente lavoro senza notifica,
            // la coda resta su disco e riparte alla prossima apertura dell'app
            Log.w("151Storage", "primo piano rifiutato", e)
            if (worker?.isAlive != true) { stopSelf(); return START_NOT_STICKY }
        }
        if (worker?.isAlive != true) {
            Uploads.stopRequested = false
            Uploads.userStopped = false
            val f = ForegroundNote<Notification>({ note(it) }, {
                try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                try { getSystemService(NotificationManager::class.java).cancel(NOTE_ID) } catch (_: Exception) {}
            })
            fg = f
            worker = Thread({ work(f) }, "caricamenti").also { it.start() }
        }
        // non «sticky». Se l'app cade, Android non rilancia il servizio da solo (prima: caduta, rilancio, stesso
        // file, caduta di nuovo, a telefono in tasca); la coda resta su disco e riparte alla prossima apertura
        return START_NOT_STICKY
    }

    /**
     * Android 15: finite le 6 ore del giorno ci si ferma SUBITO: il segno ai lavoratori e, senza aspettarli,
     * via il primo piano e il servizio (prima solo il segno → «did not stop within its timeout», l'app chiusa).
     */
    override fun onTimeout(startId: Int) = timedOut()
    override fun onTimeout(startId: Int, fgsType: Int) = timedOut()
    private fun timedOut() {
        Uploads.stopRequested = true
        fg?.end() // via «Uploading…» (prima DETACH: restava in corso, orfana); la parola finale la mette il lavoro finendo
        stopSelf()
    }

    override fun onDestroy() { fg?.end(); super.onDestroy() }

    private fun work(fg: ForegroundNote<Notification>) {
        Uploads.busy = true
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "151storage:caricamenti").apply { setReferenceCounted(false); acquire(6 * 3_600_000L) }
        try {
            val drive = Clouds.drive(this)
            if (drive == null) {
                for (j in Uploads.all(this)) if (j.state == UpJob.WAIT) { j.state = UpJob.FAIL; j.msg = "No cloud connected" }
                Uploads.save(this); Uploads.changed()
                return
            }
            val trashed = java.util.concurrent.atomic.AtomicInteger()
            // più file insieme sul server, e ognuno col SUO collegamento: i canali
            // dentro un collegamento solo si dividono la stessa strada, e 6 andavano come pochi. il server regge 10
            // collegamenti per account: 1 resta all'app (schermi, anteprime), gli altri ai caricamenti.
            // WebDAV resta uno alla volta: la sua rete è quella provata sul Samsung (Regole della scheda)
            val workers = if (drive is SftpDrive) PARALLEL else 1
            rate.set(0); startedAt = System.currentTimeMillis(); runDone.set(0); runSkip.set(0); runFail.set(0)
            // la cartella d'arrivo si risolve una volta per giro, ognuno col SUO collegamento e senza un lucchetto comune:
            // prima tutti aspettavano chi creava una cartella nuova (18.000 file in 300 cartelle: pause di secondi, 09/11)
            val dirs = java.util.concurrent.ConcurrentHashMap<String, String>()
            fun dirOf(d: CloudDrive, dest: String): String =
                dirs[dest] ?: (d.resolveDir(dest, create = true) ?: throw CloudException("Destination folder not found")).also { dirs[dest] = it }
            // per i file piccoli conta l'andata e ritorno (~57 ms verso il server, 6 a file), non la banda: ogni collegamento
            // porta PER_CONN file insieme su canali suoi
            val per = if (drive is SftpDrive) (try { File(filesDir, "per-conn").readText().trim().toInt() } catch (_: Exception) { PER_CONN }).coerceIn(1, 6) else 1 // file «per-conn»: solo per le misure A/B
            val crew = (1..workers).map { n -> Thread({
                // il primo usa il collegamento dell'app; gli altri se ne aprono uno: se il server lo rifiuta, quel
                // lavoratore si ritira e il lavoro lo fanno gli altri (nessun file segnato come fallito per questo)
                // ogni strada prende TUTTO (anche OutOfMemory): un errore qui chiudeva l'app intera
                var own: SftpDrive? = null
                try {
                    own = if (n == 1) null else Clouds.freshDrive(this) as? SftpDrive
                    val d: CloudDrive? = if (n == 1) drive else own?.takeIf { it.check() == null }
                    if (d != null) {
                        val lanes = (1..per).map { Thread({
                            try { uploadLoop(d, { dirOf(d, it) }, trashed) } catch (x: Throwable) { Log.w("151Storage", "corsia dei caricamenti caduta", x) }
                        }, "caricamenti-$n-$it").also { it.start() } }
                        lanes.forEach { it.join() }
                    }
                } catch (x: Throwable) { Log.w("151Storage", "lavoratore dei caricamenti caduto", x)
                } finally { try { own?.shutdown() } catch (_: Throwable) {} }
            }, "caricamenti-$n").also { it.start() } }
            crew.forEach { it.join() }
            if (trashed.get() > 0) { Uploads.phoneChanges++; Gallery.refresh(applicationContext); Library.refresh(applicationContext, force = true) }
        } catch (e: Throwable) {
            Log.w("151Storage", "caricamenti interrotti", e)
        } finally {
            Uploads.busy = false
            try { Uploads.flush(this) } catch (e: Exception) { Log.w("151Storage", "coda non scritta", e) }
            if (wl.isHeld) wl.release()
            Uploads.freshSummary()
            // l'ultima parola dopo l'ultimo avanzamento, fuori dalla finestra in cui Android scarta le notifiche troppo fitte
            try { Thread.sleep((1200 - (System.currentTimeMillis() - noteAt.get())).coerceIn(0, 1200)) } catch (_: InterruptedException) {}
            // via quella in corso, poi il riepilogo (si scorre via); se il primo piano era già finito, il riepilogo arriva lo stesso
            val last = finalNote()
            if (!fg.end(last) && last != null) note(last)
            Uploads.changed()
            stopSelf()
        }
    }

    private val noteAt = java.util.concurrent.atomic.AtomicLong()
    /** il conto di QUESTO giro per la notifica finale (il riepilogo guarda solo gli ultimi 30 s: diceva 1.377 su 1.800, 09/11) */
    private val runDone = java.util.concurrent.atomic.AtomicInteger()
    private val runSkip = java.util.concurrent.atomic.AtomicInteger()
    private val runFail = java.util.concurrent.atomic.AtomicInteger()

    /** I byte mandati da tutti i lavoratori insieme: la velocità vera nella notifica, per misurare sul telefono. */
    private val rate = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var startedAt = 0L
    private fun speed(): String {
        val secs = (System.currentTimeMillis() - startedAt) / 1000.0
        val mb = rate.get() / 1_048_576.0
        return if (secs < 1) "…" else String.format(java.util.Locale.UK, "%.1f MB/s", mb / secs)
    }

    /** Un lavoratore: prende un file alla volta dalla coda finché ce ne sono. */
    private fun uploadLoop(drive: CloudDrive, dirOf: (String) -> String, trashed: java.util.concurrent.atomic.AtomicInteger) {
        while (!Uploads.stopRequested) {
            val j = Uploads.claim(this) ?: break
            Uploads.changed()
            val s = Uploads.summary(this)
            val index = s.finished + s.failed + 1
            val r = try {
                val dir = dirOf(j.dest)
                var lastNote = 0L
                var counted = 0L
                drive.upload(dir, j.name, j.size, { openSrc(j.src) }, { sent ->
                    rate.addAndGet(sent - counted); counted = sent
                    j.sent = sent
                    val now = System.currentTimeMillis()
                    // Android scarta le notifiche di un'app che ne manda più di ~5 al secondo: una al secondo basta
                    // e gli schermi si ridisegnano una volta al secondo, non a ogni pezzo (con 3 insieme erano 12 al secondo)
                    // una notifica al secondo per TUTTI i lavoratori insieme: 24 corsie ne mandavano 24 al secondo, Android
                    // le scartava, compresa l'ultima («Uploading 17989 of 18000» restava lì a giro finito, 09/11)
                    if (now - lastNote >= 1000) { lastNote = now; val prev = noteAt.get()
                        if (now - prev >= 1000 && noteAt.compareAndSet(prev, now)) fg?.update(progressNote(this, "Uploading $index of ${s.total} · " + speed(), j.dest, sent, j.size, false))
                        Uploads.changed() }
                }, { Uploads.stopRequested }, srcMtime(j.src))
            } catch (e: CloudException) {
                UploadResult.Failed(e.message ?: "error", e.retry)
            } catch (e: java.io.IOException) {
                UploadResult.Failed(WebDav.netMessage(e), true)
            } catch (e: SecurityException) {
                UploadResult.Failed("The phone no longer lets this file be read", false)
            } catch (e: Throwable) {
                // un errore che non è di rete (anche OutOfMemory): prima chiudeva l'app, che ripartiva sullo stesso file
                // e ricadeva. Si riprova una volta; alla seconda il file è «non caricato» e la coda va avanti
                Log.w("151Storage", "caricamento caduto: ${j.name}", e)
                UploadResult.Failed("This file stopped the upload", Freni.crashRetry(j.attempts + 1))
            }
            j.time = System.currentTimeMillis()
            when (r) {
                is UploadResult.Done -> { runDone.incrementAndGet(); j.state = UpJob.DONE; j.remoteName = r.finalName; j.sent = j.size; Uploads.markUploaded(this, keyOf(j.src), j.size)
                    CloudState.revived(this, listOf(j.dest.trim('/') + "/" + r.finalName)) } // ricaricato dove era stato eliminato: di nuovo visibile
                is UploadResult.Skipped -> { runSkip.incrementAndGet(); j.state = UpJob.SKIP; j.remoteName = r.existingName; j.msg = "already in the cloud"; Uploads.markUploaded(this, keyOf(j.src), j.size) }
                is UploadResult.Failed -> {
                    j.attempts++
                    if (r.retry && j.attempts < 3) { j.state = UpJob.WAIT; j.msg = r.message; Uploads.saveSoon(this); Uploads.changed(); val until = System.currentTimeMillis() + (if (j.attempts == 1) 5_000L else 45_000L)
                        while (System.currentTimeMillis() < until && !Uploads.stopRequested) Thread.sleep(500) } // la rete che cade (Wi-Fi, ascensore) torna spesso in un minuto: 5 s, poi 45 s
                    else { runFail.incrementAndGet(); j.state = UpJob.FAIL; j.msg = r.message }
                }
                UploadResult.Cancelled -> { if (Uploads.userStopped) Uploads.drop(j) else j.state = UpJob.WAIT }
            }
            // al sicuro lassù (caricato con la dimensione controllata, o già lì uguale): ora si può togliere dal telefono
            if (j.thenDelete && j.finished) {
                val gone = try { PhoneTrash.trash(this, j.src) } catch (_: Exception) { false }
                j.thenDelete = false
                j.msg = if (gone) "moved to the phone's trash" else (if (j.state == UpJob.SKIP) "already in the cloud · " else "") + "still on the phone"
                if (gone) trashed.incrementAndGet()
            }
            Uploads.saveSoon(this)
            Uploads.changed()
            }
    }

    /** La data del file sul telefono (lassù la tiene); 0 = non si sa, e allora resta quella del server. */
    private fun srcMtime(src: String): Long = try {
        if (!src.startsWith("content://")) File(src).lastModified()
        else {
            val u = Uri.parse(src)
            fun col(c: String, scale: Long) = try { contentResolver.query(u, arrayOf(c), null, null, null)?.use { q ->
                if (q.moveToFirst() && !q.isNull(0)) q.getLong(0) * scale else 0L } ?: 0L } catch (_: Exception) { 0L }
            col(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1).takeIf { it > 0 }
                ?: col(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, 1000)
        }
    } catch (_: Exception) { 0L }

    private fun openSrc(src: String): InputStream =
        if (src.startsWith("content://")) contentResolver.openInputStream(Uri.parse(src)) ?: throw java.io.IOException("file not readable")
        else FileInputStream(File(src))

    /** Il riepilogo del giro (non «in corso»: si scorre via); null = nessun file passato, nessuna notifica. */
    private fun finalNote(): Notification? {
        val ok = runDone.get(); val skipped = runSkip.get(); val failed = runFail.get()
        if (ok + skipped + failed == 0) return null
        val text = buildList {
            if (ok > 0) add(if (ok == 1) "1 file in the cloud" else "$ok files in the cloud")
            if (skipped > 0) add("$skipped already there")
            if (failed > 0) add(if (failed == 1) "1 not uploaded" else "$failed not uploaded")
        }.joinToString(" · ").ifEmpty { "Uploads stopped" }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setColor(ContextCompat.getColor(this, R.color.place_cloud))
            .setContentTitle(if (failed > 0) "Uploads: something went wrong" else if (Uploads.stopRequested) "Uploads stopped" else "Uploads finished")
            .setContentText(text)
            .setContentIntent(openApp(this))
            .setAutoCancel(true)
            .setOngoing(false)
        return b.build()
    }

    private fun note(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(NOTE_ID, n) } catch (_: SecurityException) {}
    }

    companion object {
        /** quanti file insieme verso il server */
        const val PARALLEL = 8
        /** file insieme dentro ogni collegamento (canali SFTP separati) */
        const val PER_CONN = 3
        const val CHANNEL = "uploads"
        const val NOTE_ID = 151
        const val ACTION_STOP = "com.the151universe.storage.STOP_UPLOADS"

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, UploadService::class.java)) } catch (e: Exception) {
                Log.w("151Storage", "servizio dei caricamenti non partito", e)
            }
        }

        fun keyOf(src: String): String = src

        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of files going to the cloud"
                    setSound(null, null)
                })
            }
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLACE, "cloud").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun progressNote(ctx: Context, title: String, text: String, sent: Long, size: Long, indeterminate: Boolean): Notification {
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, UploadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val pct = if (size > 0) ((sent * 100) / size).toInt().coerceIn(0, 100) else 0
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_upload)
                .setColor(ContextCompat.getColor(ctx, R.color.place_cloud))
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, pct, indeterminate || size <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(ctx))
                .addAction(0, "Stop", stop)
                .build()
        }
    }
}
