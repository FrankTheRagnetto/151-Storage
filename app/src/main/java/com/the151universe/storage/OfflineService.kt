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

/**
 * «Keep on phone» in primo piano: lo scaricamento continua anche con l'app in sottofondo. Il lavoro è [Offline.work]; questo servizio lo tiene vivo con una notifica che
 * dice «3 of 59», come quella dei caricamenti. Finito (o fermato da Android), la notifica sparisce.
 */
class OfflineService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note(Offline.status), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // senza primo piano Android chiude l'app dopo pochi secondi («did not then call startForeground»):
            // si rinuncia come fa [ThumbService]; si riallinea al prossimo giro (6 ore) o subito con «Refresh»
            Log.w(TAG, "sul telefono: primo piano rifiutato", e)
            if (worker?.isAlive != true) { Offline.abandon(); stopSelf(); return START_NOT_STICKY }
        }
        if (worker?.isAlive != true) worker = Thread({ run() }, "sempre-sul-telefono").also { it.start() }
        return START_NOT_STICKY
    }

    /** Android 15: finite le 6 ore del giorno ci si ferma SUBITO, senza aspettare il file in corso. */
    override fun onTimeout(startId: Int) = timedOut()
    override fun onTimeout(startId: Int, fgsType: Int) = timedOut()
    private fun timedOut() {
        Offline.stop = true
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private val listener: () -> Unit = { show(note(Offline.status)) }

    private fun run() {
        val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "151storage:sultelefono")
            .apply { setReferenceCounted(false); acquire(6 * 3_600_000L) }
        Offline.addListener(listener)
        try {
            val drive = Clouds.drive(this)
            if (drive != null) Offline.work(applicationContext, drive) else Offline.abandon()
        } catch (x: Throwable) {
            Log.w(TAG, "sul telefono interrotto", x)
        } finally {
            Offline.removeListener(listener)
            if (wl.isHeld) wl.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var lastShown = 0L
    private fun show(n: Notification) {
        val now = System.currentTimeMillis()
        if (now - lastShown < 700) return // non più di poco più di un aggiornamento al secondo
        lastShown = now
        try { getSystemService(NotificationManager::class.java).notify(NOTE_ID, n) } catch (_: SecurityException) {}
    }

    private fun note(text: String): Notification {
        // «Downloading 3 of 59 · nome» → barra 3/59
        val m = Regex("""(\d+) of (\d+)""").find(text)
        val done = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_done)
            .setColor(ContextCompat.getColor(this, R.color.place_cloud))
            .setContentTitle("Keeping folders on this phone")
            .setContentText(text.ifEmpty { "Checking the folders kept on this phone…" })
            .setProgress(total, done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 3,
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLACE, "cloud").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    companion object {
        private const val TAG = "151Storage"
        private const val CHANNEL = "offline"
        private const val NOTE_ID = 153

        /** true = partito (il lavoro lo fa lui); false = Android non lo lascia partire, lo fa chi chiama. */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OfflineService::class.java)); true
        } catch (e: Exception) { Log.w(TAG, "sul telefono: servizio non partito", e); false }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kept on this phone", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Downloading the cloud folders kept on this phone"
                    setSound(null, null)
                })
        }
    }
}
