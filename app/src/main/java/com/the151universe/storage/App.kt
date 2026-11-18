package com.the151universe.storage

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // il registro delle modifiche al cloud vale da subito, per ogni sezione (anche Media, prima di aprire il Cloud)
        CloudState.load(this)
        try { Library.migrate(this) } catch (_: Throwable) {} // v5: via foto/video da cache e recenti
        // il sorter del telefono è uscito: via le sue scelte a metà, le impronte e le
        // ultime cartelle. Non avevano toccato niente (si applicava solo con «Apply»). Una volta, poi non c'è più niente.
        Thread {
            try { File(filesDir, "sort").deleteRecursively() } catch (_: Throwable) {}
            getSharedPreferences("settings", 0).let { p -> p.all.keys.filter { it.startsWith("sortDest_") }.takeIf { it.isNotEmpty() }
                ?.let { k -> p.edit().apply { k.forEach { remove(it) } }.apply() } }
        }.start()
        // niente Material You: i colori sono quelli del manuale (DESIGN.md), chiari o scuri come il telefono
    }
}

/**
 * Registratore di crash, solo locale (niente rete). Se l'app muore per un'eccezione non gestita,
 * lo stack trace finisce in files/crash.txt; al prossimo avvio [CrashActivity] lo mostra con
 * «Copia» e «Condividi», cosi' l'utente ce lo puo' mandare anche senza adb.
 *
 * Dopo un aggiornamento il crash di prima non torna come nuovo: si mostra solo un
 * crash di QUESTA versione (prima riga «151 Storage <nome> (<codice>)»), e una volta sola; quello di una versione di
 * prima, o già mostrato, passa in `crash-old.txt` (resta per adb, non si ripropone). La schermata dice versione e ora.
 */
object CrashLog {
    private const val FILE = "crash.txt"
    private const val OLD = "crash-old.txt"
    private val HEAD = Regex("""^151 Storage (\S+) \((\d+)\)""")

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Il codice di versione scritto nella prima riga del rapporto; null se non c'è (rapporto senza versione). */
    fun versionCodeOf(report: String): Long? = HEAD.find(report.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(2)?.toLongOrNull()

    /** «0.10.15 · 2026-10-18 14:15:02»: versione e ora del crash, dalle prime due righe; null se non si leggono. */
    fun headline(report: String): Pair<String, String>? {
        val l = report.lineSequence().take(2).toList()
        val name = HEAD.find(l.firstOrNull().orEmpty())?.groupValues?.get(1) ?: return null
        return name to l.getOrNull(1).orEmpty().trim()
    }

    /** Il rapporto è stato mostrato: non si ripropone (come si esce dalla schermata, anche col tasto indietro). */
    fun seen(ctx: Context) = retire(ctx)

    private fun retire(ctx: Context) {
        try { val f = file(ctx); if (f.exists() && !f.renameTo(File(ctx.filesDir, OLD))) f.delete() } catch (_: Exception) {}
    }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(app).writeText(report(app, thread, error))
            } catch (_: Throwable) {
                // scrivere il rapporto non deve mai impedire la chiusura
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); System.exit(10) }
        }
    }

    private fun report(ctx: Context, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        // la versione di QUESTO codice, sempre nota (prima dal PackageManager, «?» se falliva)
        val v = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        return buildString {
            append("151 Storage ").append(v).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("thread: ").append(thread.name).append("\n\n")
            append(sw.toString())
        }
    }

    /** Il testo dell'ultimo crash non ancora visto DI QUESTA VERSIONE, o null. Uno di una versione di prima si mette da parte. */
    fun pending(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() && it.length() > 0 }?.readText()?.let { t ->
            if (versionCodeOf(t) == BuildConfig.VERSION_CODE.toLong()) t else { retire(ctx); null }
        }
    } catch (_: Exception) { null }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }

    /** Se c'e' un crash da mostrare apre la schermata apposita. true = l'ha aperta. */
    fun showIfPending(ctx: Context): Boolean {
        if (pending(ctx) == null) return false
        return try {
            ctx.startActivity(android.content.Intent(ctx, CrashActivity::class.java))
            true
        } catch (_: Exception) { false }
    }
}
