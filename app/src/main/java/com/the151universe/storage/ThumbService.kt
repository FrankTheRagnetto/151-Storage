package com.the151universe.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Il giro delle anteprime di TUTTO il cloud («apro l'app e TUTTE le anteprime vengono fatte, di fila,
 * finché non ne manca più nessuna»). Parte all'apertura dell'app, lavora in primo piano con la sua notifica (anche ad
 * app chiusa o telefono in tasca), 4 anteprime alla volta, cartelle più recenti per prime («2026» prima di «2025»).
 * Si ferma solo quando: ha passato tutto il cloud; il cloud viene scollegato o cambiato; il telefono ha meno di 500 MB
 * liberi; la rete manca (30 errori di fila); Android chiude il lavoro (dalla 15: 6 ore al giorno). Alla prossima
 * apertura riparte e salta in un attimo quello già fatto. Finito un giro intero, butta le anteprime dei file spariti.
 * Le cartelle che l'utente apre a mano hanno le loro anteprime subito: lo schermo non aspetta il giro.
 */
class ThumbService : Service() {
    private var worker: Thread? = null
    @Volatile private var stop = false
    /** Le anteprime in corso: [onTimeout] le interrompe subito. */
    @Volatile private var pool: java.util.concurrent.ExecutorService? = null
    /** La notifica del giro di adesso: gli avanzamenti arrivati dopo la fine si scartano, mai una notifica orfana. */
    @Volatile private var fg: ForegroundNote<Notification>? = null
    private fun newNote() = ForegroundNote<Notification>({ show(it) }, {
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(NOTE_ID) } catch (_: Exception) {}
    })

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note("Checking the cloud…", 0, 0), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // senza primo piano Android chiude l'app dopo 10 s (crash 08/11 09:07): si rinuncia, si riprova alla prossima apertura
            Log.w(TAG, "anteprime: primo piano rifiutato", e)
            if (worker?.isAlive != true) { running = false; stopSelf(); return START_NOT_STICKY }
        }
        if (worker?.isAlive != true) { stop = false; val n = newNote(); fg = n; worker = Thread({ work(n) }, "151-cloud-thumbs").also { it.start() } }
        return START_NOT_STICKY
    }

    /**
     * Android 15: un lavoro «sincronizzazione» ha 6 ore al giorno; finite, ci si ferma SUBITO (prima solo un
     * segno, e lo stopSelf arrivava dopo attese fino a 30 minuti → «did not stop in time», l'app chiusa da Android).
     */
    override fun onTimeout(startId: Int) = timedOut()
    override fun onTimeout(startId: Int, fgsType: Int) = timedOut()
    private fun timedOut() {
        stop = true
        pool?.shutdownNow()
        fg?.end()
        stopSelf()
    }

    /**
     * L'app scorsa via dalle Recenti: il giro si ferma come per Android, e la sua notifica sparisce subito (prima
     * restava ferma, orfana, anche a processo morto). Riprende a un'apertura futura, coi freni di [Freni.sweepDue].
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        timedOut()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() { stop = true; fg?.end(); super.onDestroy() }

    private fun now() = System.currentTimeMillis()

    /** [fg] = la notifica di QUESTO giro (un giro nuovo ne ha una sua: la fine di uno non tocca l'altro). */
    private fun work(fg: ForegroundNote<Notification>) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        // mentre si carica il giro non comincia nemmeno: niente telefono sveglio ad aspettare
        if (Uploads.busy) { running = false; fg.end(); stopSelf(); return }
        val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "151storage:anteprime")
            .apply { setReferenceCounted(false); acquire(6 * 3_600_000L) }
        val pool = Executors.newFixedThreadPool(WORKERS).also { this.pool = it }
        val slots = Semaphore(WORKERS * 4)
        val checked = AtomicInteger(); val made = AtomicInteger(); val netErrors = AtomicInteger()
        var complete = true
        /** il giro si è fermato perché sono partiti i caricamenti: si riprende alla prossima apertura, senza pausa */
        var forUploads = false
        /** la lista intera è stata passata tutta (anteprime e durate): la sua impronta si ricorda */
        var walked = false
        var print: String? = null
        /** il giro intero è stato segnato «finito» (sweep_done) */
        var sweptAll = false
        var lastNote = 0L
        val seen = HashSet<String>()
        val drive = Clouds.drive(this)
        var dupWork: Thread? = null
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        // (guardiano 12/11: Storage 56 s di processore ogni 2 minuti, dietro, per 6 minuti): il «giro a metà» si
        // scriveva solo nel finally, e un processo chiuso da Android (o dalla chiusura forzata) non ci arriva mai → a ogni
        // apertura il giro intero ripartiva (telefono di prova: chiusa a metà e riaperta, 41 s di processore in 60 s). Si segna PRIMA
        // di cominciare, scritto subito; finito, «sweep_done» lo supera. Fermato dai caricamenti: si toglie (riprende subito).
        val triedBefore = p.getLong("sweep_tried", 0)
        if (drive != null) p.edit().putLong("sweep_tried", System.currentTimeMillis()).putString("sweep_who", who(this)).commit()
        try {
            if (drive == null) return
            // 1) cosa c'è nel cloud, tutto in una volta (la lista la fa il telefono)
            fg.update(note("Reading the cloud…", 0, 0))
            val scan = try { CloudList.scan(drive) { stopNow(drive, netErrors) } } catch (x: Exception) { Log.w(TAG, "lista del cloud: ${x.message}"); null }
            if (scan == null) { complete = false; return }
            if (!scan.complete) complete = false
            // il cloud è come l'ultima lista passata tutta: niente da rifare (prima doppioni, ~45.000 controlli e
            // durate a ogni apertura)
            if (scan.complete) {
                print = Freni.fingerprint(scan.entries)
                if (p.getString("sweep_print", null) == print && p.getString("sweep_print_who", null) == who(this)) {
                    Log.i(TAG, "giro anteprime: il cloud non è cambiato (${scan.entries.size} voci), niente da rifare")
                    p.edit().putLong("sweep_done", System.currentTimeMillis()).putString("sweep_who", who(this)).apply()
                    sweptAll = true
                    return
                }
            }
            if (scan.complete) {
                // prima di tutto: i file spostati si portano dietro anteprima e data, poi la lista nuova
                try { CloudList.carryMoved(this, drive, scan) } catch (x: Exception) { Log.w(TAG, "spostati: ${x.message}") }
                publishList(drive, scan)
                // 2) i doppioni intanto che si fanno le anteprime: l'impronta la calcola il server, il telefono aspetta e basta
                dupWork = Thread({
                    try {
                        CloudList.duplicates(this, drive, scan) { stop || Clouds.drive(this) !== drive }
                        // le quasi uguali dalle anteprime che ci sono già (zero rete); a fine giro si rifanno con le nuove
                        CloudList.similar(this, drive, scan) { stop || Clouds.drive(this) !== drive }
                        Index.load(this, drive, force = true)
                    } catch (x: Throwable) { Log.w(TAG, "doppioni: ${x.message}") } // anche OutOfMemory: mai l'app intera
                }, "151-cloud-dups").also { it.start() }
            }
            // 3) le anteprime, nell'ordine di sempre (la cartella più recente prima)
            // (file «list-only»: SOLO per la prova sul cloud vero dell'utente — la lista sì, 26.000 anteprime no)
            val listOnly = File(filesDir, "list-only").exists()
            var broke = false
            for (e in if (listOnly) emptyList() else CloudList.order(scan)) {
                    if (e.isDir || !Thumbs.isMedia(e.name)) continue
                    val f = CloudThumbs.fileFor(this, e.path)
                    seen += f.name; seen += CloudThumbs.noneFor(f).name; seen += CloudThumbs.failFor(f).name
                    checked.incrementAndGet()
                    if (CloudThumbs.done(this, e) || CloudThumbs.busy(this, e)) continue
                    if (stopNow(drive, netErrors)) { complete = false; broke = true; break }
                    // mentre si carica, le anteprime si fermano: le strade verso il server vanno ai caricamenti (
                    // «slow»). Prima aspettavano qui col telefono sveglio, un controllo ogni 2 s: ora il giro
                    // finisce e riprende alla prossima apertura dell'app
                    if (Uploads.busy) { complete = false; forUploads = true; broke = true; break }
                    slots.acquire()
                    pool.execute {
                        try {
                            if (CloudThumbs.get(this, drive, e) != null) { made.incrementAndGet(); netErrors.set(0) }
                            else if (CloudThumbs.failedNow(this, e)) netErrors.incrementAndGet()
                        } catch (x: Throwable) { Log.w(TAG, "anteprima ${e.name}", x); netErrors.incrementAndGet() } // mai l'app intera
                        finally { slots.release() }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 2000) { lastNote = now; fg.update(note("${made.get()} new previews · ${checked.get()} photos and videos checked", 0, 0)) }
            }
            // fermato a metà: le anteprime in coda non si fanno più, quelle in corso si interrompono
            if (broke) pool.shutdownNow() else pool.shutdown()
            pool.awaitTermination(30, TimeUnit.MINUTES)
            // la durata dei video che avevano già l'anteprima: solo l'intestazione, ~7 KB a video
            val lengthsDone = !listOnly && !stop && !broke && lengths(drive, scan, netErrors, fg)
            walked = scan.complete && !listOnly && !broke && !stop && lengthsDone
            VideoLength.save(this)
            dupWork?.join()
            // le date lette facendo le anteprime entrano nella lista (Oggi negli anni, Un giorno a caso)
            if (scan.complete && !stop) {
                publishList(drive, scan)
                if (made.get() > 0) try { CloudList.similar(this, drive, scan) { stop }; Index.load(this, drive, force = true) } catch (x: Exception) { Log.w(TAG, "quasi uguali: ${x.message}") }
            }
            // un'anteprima mancata (rete) lascia il giro «da riprendere», ma non quello che è già fatto
            if (netErrors.get() > 0) complete = false
            if (complete && !stop && !File(filesDir, "list-only").exists()) {
                CloudThumbs.dir(this).listFiles()?.forEach { if (it.name !in seen && !it.name.endsWith(".tmp") && it.name != "durations.tsv") it.delete() } // file spariti dal cloud
                VideoLength.prune(this, seen)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("sweep_done", System.currentTimeMillis())
                    .putString("sweep_who", who(this)).apply()
                sweptAll = true
            }
            Log.i(TAG, "giro anteprime: ${checked.get()} foto e video, ${made.get()} anteprime nuove, ${if (complete) "completo" else "da riprendere"}")
        } catch (x: Throwable) {
            Log.w(TAG, "giro anteprime interrotto", x)
            complete = false
        } finally {
            pool.shutdownNow()
            this.pool = null
            try {
                val ed = p.edit()
                // giro a metà (lista rotta, rete, Android): non si ricomincia a ogni apertura, si aspetta [Freni.RETRY_GAP]
                // Fermato dai caricamenti no: riprende alla prossima apertura.
                if (!sweptAll && !forUploads && drive != null) ed.putLong("sweep_tried", System.currentTimeMillis()).putString("sweep_who", who(this))
                if (forUploads) ed.putLong("sweep_tried", triedBefore)   // i caricamenti non sono un giro a metà: nessuna pausa
                // la lista è stata passata tutta: la stessa lista la prossima volta non si ripassa (i file falliti hanno
                // il loro conto, [CloudThumbs.failFor])
                if (walked && print != null) ed.putString("sweep_print", print).putString("sweep_print_who", who(this))
                ed.apply()
            } catch (x: Exception) { Log.w(TAG, "freno del giro non scritto", x) }
            if (wl.isHeld) wl.release()
            // via la notifica in corso; la parola finale (si scorre via) solo a giro intero con anteprime nuove
            fg.end(if (complete && made.get() > 0) done("All cloud previews are on the phone", "${checked.get()} photos and videos") else null)
            running = false
            stopSelf()
        }
    }

    /** Scrive la lista del telefono e la fa leggere. */
    private fun publishList(drive: CloudDrive, scan: CloudList.Scan) {
        try {
            val built = CloudList.publish(this, drive, scan)
            val was = Index.source
            Index.load(this, drive, force = true)
            if (was != Index.source) Index.changed()
            Log.i(TAG, "lista del telefono: ${built.media} foto e video, ${built.dirs} cartelle, ${built.files} file")
        } catch (x: Exception) { Log.w(TAG, "lista del telefono non scritta", x) }
    }

    /** Le durate mancanti, 4 alla volta, con la notifica; un errore di rete lascia il video per il prossimo giro.
     *  true = passati tutti (non fermato a metà). */
    private fun lengths(drive: CloudDrive, scan: CloudList.Scan, netErrors: AtomicInteger, fg: ForegroundNote<Notification>): Boolean {
        val todo = CloudList.order(scan).filter { !it.isDir && Thumbs.mimeOf(it.name).startsWith("video/") && !VideoLength.known(this, it.path) }
        if (todo.isEmpty()) return true
        var all = true
        val pool = Executors.newFixedThreadPool(WORKERS).also { this.pool = it }
        val done = AtomicInteger(); var lastNote = 0L
        val slots = Semaphore(WORKERS * 2)
        try {
            for (e in todo) {
                if (stopNow(drive, netErrors) || Uploads.busy) { all = false; break }
                slots.acquire()
                pool.execute {
                    try {
                        if (!stopNow(drive, netErrors)) { VideoLength.put(this, e.path, VideoLength.read(this, drive, e)); netErrors.set(0) }
                    } catch (x: Throwable) { Log.w(TAG, "durata ${e.name}: ${x.message}"); netErrors.incrementAndGet() }
                    finally { done.incrementAndGet(); slots.release() }
                }
                val now = System.currentTimeMillis()
                if (now - lastNote > 2000) { lastNote = now; fg.update(note("Video lengths: ${done.get()} of ${todo.size}", done.get(), todo.size)) }
            }
        } finally { pool.shutdown(); pool.awaitTermination(30, TimeUnit.MINUTES) }
        Log.i(TAG, "durate dei video: ${done.get()} di ${todo.size}")
        return all && !stop
    }

    private fun stopNow(drive: CloudDrive, netErrors: AtomicInteger) =
        stop || Clouds.drive(this) !== drive || CloudThumbs.lowSpace(this) || netErrors.get() >= 30

    private fun note(text: String, done: Int, total: Int): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_cloud_done)
        .setColor(ContextCompat.getColor(this, R.color.place_cloud))
        .setContentTitle("Making cloud previews")
        .setContentText(text)
        .setProgress(total, done, total == 0)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setContentIntent(openApp(this))
        .build()

    private fun done(title: String, text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_cloud_done)
        .setColor(ContextCompat.getColor(this, R.color.place_cloud))
        .setContentTitle(title).setContentText(text)
        .setAutoCancel(true).setSilent(true)
        .setContentIntent(openApp(this))
        .build()

    private fun show(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(NOTE_ID, n) } catch (_: SecurityException) {}
    }

    companion object {
        private const val TAG = "151Storage"
        private const val CHANNEL = "previews"
        private const val NOTE_ID = 152
        private const val PREFS = "cloud_thumbs"
        private const val WORKERS = 4
        @Volatile private var running = false

        private fun who(ctx: Context) = Clouds.account(ctx)?.let { it.url + "|" + it.user }

        /**
         * All'apertura dell'app e appena collegato un cloud. Non riparte se un giro intero è finito da meno di 10 minuti,
         * né per 6 ore dopo un giro rimasto a metà, né mentre si carica ([Freni.sweepDue]).
         */
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            if (Clouds.drive(app) == null || running || Uploads.busy) return
            if (java.io.File(app.filesDir, "no-sweep").exists()) return // solo per le prove sul cloud vero: niente giro
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!Freni.sweepDue(System.currentTimeMillis(), p.getString("sweep_who", null) == who(app), p.getLong("sweep_done", 0), p.getLong("sweep_tried", 0))) return
            running = true
            try { ContextCompat.startForegroundService(app, Intent(app, ThumbService::class.java)) } catch (e: Exception) {
                running = false
                Log.w(TAG, "giro delle anteprime non partito", e)
            }
        }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cloud previews", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Making the previews of the photos and videos in the cloud"
                    setSound(null, null)
                })
            }
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLACE, "cloud").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
