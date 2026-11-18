package com.the151universe.storage

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Esito di una cancellazione riuscita. [trashUri] != null = e' nel cestino di sistema e si puo' annullare. */
data class Deleted(val name: String, val path: String?, val sourceUri: Uri?, val trashUri: Uri?) {
    val inTrash get() = trashUri != null
}

/** Esito di un'eliminazione multipla dalla libreria: [trashed] si possono annullare, [gone] sono definitivi. */
data class DeletedMany(val trashed: List<Deleted>, val gone: Int, val failed: Int)

/** Passaggio di consegne visualizzatore -> schermata principale: la Snackbar con «Annulla» la mostra lei. */
object TrashHandoff {
    @Volatile var pending: Deleted? = null
}

/**
 * «Elimina» del visualizzatore e della libreria. Strategia, nell'ordine:
 *  a) percorso vero sullo storage condiviso + «Accesso a tutti i file" -> cestino di sistema
 *     (MediaStore IS_TRASHED, Android 11+: recuperabile ~30 giorni), altrimenti eliminazione definitiva
 *     dopo una conferma che lo dice;
 *  b) URI di MediaStore -> MediaStore.createTrashRequest (dialogo di sistema);
 *  c) DocumentsProvider con FLAG_SUPPORTS_DELETE -> DocumentsContract.deleteDocument (definitivo);
 *  d) altrimenti messaggio: il file e' di un'altra app (WhatsApp, Gmail…) e da qui non si cancella.
 * Va creato PRIMA di onStart dell'activity (registra un launcher).
 */
class Deleter(
    private val act: AppCompatActivity,
    private val onDeleted: (Deleted) -> Unit,
    private val onRestored: (Deleted) -> Unit = {},
    private val onDeletedMany: (DeletedMany) -> Unit = {},
) {
    private val ctx: Context get() = act
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var afterRequest: ((Boolean) -> Unit)? = null
    private val requestLauncher = act.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val cb = afterRequest
        afterRequest = null
        cb?.invoke(r.resultCode == Activity.RESULT_OK)
    }

    private sealed interface Plan
    private class ByPath(val path: String) : Plan
    private class ByMedia(val mediaUri: Uri, val path: String?) : Plan
    private class ByDocument(val uri: Uri, val path: String?) : Plan
    private class NeedAccess(val path: String) : Plan
    private class Foreign(val app: String?) : Plan
    private object Missing : Plan

    fun shutdown() = io.shutdownNow()

    /** Punto d'ingresso. [path] se lo sappiamo gia' (libreria), [referrer] = pacchetto di chi ci ha aperto. */
    fun start(uri: Uri?, path: String?, name: String, referrer: String? = null) {
        if (io.isShutdown) return
        io.execute {
            val plan = try { plan(uri, path, name, referrer) } catch (e: Exception) {
                Log.w(TAG, "piano di cancellazione fallito", e); Foreign(null)
            }
            ui { confirm(plan, uri, name) }
        }
    }

    private fun plan(uri: Uri?, knownPath: String?, name: String, referrer: String?): Plan {
        val access = Storage.hasAccess(ctx)
        var path = knownPath
        if (path == null && uri != null) {
            val (n, s) = Storage.nameAndSize(ctx, uri)
            path = Storage.realPath(ctx, uri, n ?: name, s)
        }
        if (path != null && !File(path).exists()) return Missing
        if (path != null && access) return ByPath(path)
        if (uri != null && Build.VERSION.SDK_INT >= 30) mediaUriOf(uri)?.let { return ByMedia(it, path) }
        if (uri != null && DocumentsContract.isDocumentUri(ctx, uri) && supportsDelete(uri)) return ByDocument(uri, path)
        if (path != null) return NeedAccess(path)
        return Foreign(appName(uri, referrer))
    }

    /** URI di MediaStore dietro l'URI ricevuto (direttamente, o via il provider «Documenti» dei media / Download). */
    private fun mediaUriOf(uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        if (!DocumentsContract.isDocumentUri(ctx, uri)) return null
        val id = DocumentsContract.getDocumentId(uri)
        val mid = when (uri.authority) {
            "com.android.providers.media.documents" -> id.substringAfter(':', "").toLongOrNull()
            "com.android.providers.downloads.documents" -> if (id.startsWith("msf:")) id.removePrefix("msf:").toLongOrNull() else null
            else -> null
        } ?: return null
        return MediaStore.Files.getContentUri("external", mid)
    }

    private fun supportsDelete(uri: Uri): Boolean = try {
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
        } ?: false
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ più file insieme (libreria)

    /**
     * Elimina più file della libreria in un colpo solo. Sono percorsi veri e la libreria esiste solo con
     * «Accesso a tutti i file», quindi la strada è quella a): cestino di sistema dove si può (Android 11+),
     * altrimenti definitivo. Una conferma sola, che dice quanti vanno nel cestino e quanti spariscono per sempre.
     */
    /** [folders] = i nomi delle cartelle scelte (se ce ne sono): la conferma parla di loro, non dei file dentro. */
    private var folderNames: List<String> = emptyList()

    /** [ask] = false solo dopo una domanda già fatta (riepilogo del Sort): nel cestino senza chiedere di nuovo; il definitivo chiede sempre. */
    fun startMany(files: List<LibFile>, folders: List<String> = emptyList(), ask: Boolean = true) {
        folderNames = folders
        if (io.isShutdown || files.isEmpty()) return
        io.execute {
            try {
                val trash = ArrayList<Pair<LibFile, Uri>>()
                val perm = ArrayList<LibFile>()
                val t0 = android.os.SystemClock.elapsedRealtime()
                val ids = PhoneTrash.idsFor(ctx, files.map { it.path })
                Log.i(TAG, "cestino: ${files.size} file, ${ids.size} id in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                for (f in files) {
                    if (!File(f.path).exists()) continue
                    val mu = if (Build.VERSION.SDK_INT < 30) null
                        else ids[f.path]?.let { MediaStore.Files.getContentUri("external", it) } ?: mediaUriForPath(f.path)
                    if (mu != null) trash += f to mu else perm += f
                }
                Log.i(TAG, "cestino: ${trash.size} nel cestino, ${perm.size} per sempre, in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                ui { if (ask || perm.isNotEmpty()) confirmMany(trash, perm) else bg { runMany(trash, perm) } }
            } catch (e: Exception) {
                Log.w(TAG, "piano di cancellazione multipla fallito", e); fail(R.string.del_failed)
            }
        }
    }

    private fun confirmMany(trash: List<Pair<LibFile, Uri>>, perm: List<LibFile>) {
        if (act.isFinishing || act.isDestroyed) return
        val n = trash.size + perm.size
        if (n == 0) { fail(R.string.del_missing_many); return }
        val names = if (folderNames.isNotEmpty()) folderNames.map { "$it  ($n files inside)" }.take(1) + folderNames.drop(1)
            else (trash.map { it.first } + perm).map { it.name }
        val list = names.take(4).joinToString("\n") { "«$it»" } +
            if (names.size > 4) "\n" + act.getString(R.string.del_many_more, names.size - 4) else ""
        val body = StringBuilder(list).append("\n\n")
        when {
            perm.isEmpty() -> body.append(act.getString(R.string.del_many_trash_note))
            trash.isEmpty() -> body.append(act.getString(R.string.del_many_perm_note))
            else -> body.append(act.getString(R.string.del_many_mixed_note, trash.size, perm.size))
        }
        val d = MaterialAlertDialogBuilder(act)
            .setTitle(act.resources.getQuantityString(if (perm.isEmpty()) R.plurals.del_many_trash_title else R.plurals.del_many_perm_title, n, n))
            .setMessage(body)
            // annullato: chi aveva già tolto le righe dallo schermo (Phone) le rimette
            .setNegativeButton(R.string.cancel) { _, _ -> onDeletedMany(DeletedMany(emptyList(), 0, 0)) }
            .setPositiveButton(if (perm.isEmpty()) R.string.del_trash_ok else R.string.delete) { _, _ -> bg { runMany(trash, perm) } }
            .show()
        if (perm.isNotEmpty()) markIrreversible(d)
    }

    private fun runMany(trash: List<Pair<LibFile, Uri>>, perm: List<LibFile>) {
        val done = ArrayList<Deleted>()
        var gone = 0
        var failed = 0
        val moved = PhoneTrash.setTrashedMany(ctx, trash.map { it.second }, true)
        for ((f, mu) in trash) {
            if (mu in moved && !File(f.path).exists()) done += Deleted(f.name, f.path, null, mu) else failed++
        }
        for (f in perm) {
            val file = File(f.path)
            if (file.delete() || !file.exists()) { scan(f.path); gone++; ui { Library.remove(act, f.path) } } else failed++
        }
        ui {
            Library.removeMany(act, done.mapNotNullTo(HashSet()) { it.path })
            if (done.size <= 200) for (d in done) Recents.forget(act, null, d.path)
            for (f in perm) Recents.forget(act, null, f.path)
            onDeletedMany(DeletedMany(done, gone, failed))
        }
    }

    /** «Annulla» dopo un'eliminazione multipla: tutti fuori dal cestino. */
    fun undoMany(m: DeletedMany) {
        if (m.trashed.isEmpty()) return
        bg {
            var ok = 0
            val back = PhoneTrash.setTrashedMany(ctx, m.trashed.mapNotNull { it.trashUri }, false)
            for (d in m.trashed) {
                val mu = d.trashUri ?: continue
                if (mu in back && (d.path == null || File(d.path).exists())) {
                    ok++
                    d.path?.let { p -> ui { Library.restore(act, p) } }
                }
            }
            ui {
                toast(if (ok == m.trashed.size) R.string.restored else R.string.undo_failed)
                onRestored(Deleted("", null, null, null))
            }
        }
    }

    // ------------------------------------------------------------ conferme

    private fun confirm(plan: Plan, uri: Uri?, name: String) {
        if (act.isFinishing || act.isDestroyed) return
        val d = MaterialAlertDialogBuilder(act)
        when (plan) {
            is ByPath -> {
                val trash = Build.VERSION.SDK_INT >= 30
                d.setTitle(if (trash) R.string.del_trash_title else R.string.del_perm_title)
                    .setMessage(act.getString(if (trash) R.string.del_trash_body else R.string.del_perm_body_notrash, name, Storage.folderLabel(plan.path)))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(if (trash) R.string.del_trash_ok else R.string.del_perm_ok) { _, _ -> bg { byPath(plan.path, uri, name) } }
            }
            is ByMedia -> {
                // il dialogo di sistema di createTrashRequest E' la conferma: non ne mettiamo due
                trashRequest(plan.mediaUri, uri, plan.path, name); return
            }
            is ByDocument -> {
                d.setTitle(R.string.del_perm_title)
                    .setMessage(act.getString(R.string.del_perm_body_provider, name))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.del_perm_ok) { _, _ -> bg { byDocument(plan.uri, plan.path, name) } }
                // con «Accesso a tutti i file» lo stesso file andrebbe nel cestino: lo diciamo
                if (!Storage.hasAccess(ctx) && Build.VERSION.SDK_INT >= 30) {
                    d.setMessage(act.getString(R.string.del_perm_body_provider, name) + "\n\n" + act.getString(R.string.del_hint_access))
                    d.setNeutralButton(R.string.grant_access) { _, _ -> openAccessSettings(act) }
                }
            }
            is NeedAccess -> d.setTitle(R.string.del_need_access_title)
                .setMessage(R.string.del_need_access_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.grant_access) { _, _ -> openAccessSettings(act) }
            is Foreign -> d.setTitle(R.string.del_foreign_title)
                .setMessage(
                    if (plan.app != null) act.getString(R.string.del_foreign_body, plan.app)
                    else act.getString(R.string.del_foreign_body_unknown)
                )
                .setPositiveButton(R.string.ok, null)
            Missing -> d.setTitle(R.string.del_missing).setPositiveButton(R.string.ok, null)
        }
        val permanent = plan is ByDocument || (plan is ByPath && Build.VERSION.SDK_INT < 30)
        val shown = d.show()
        if (permanent) markIrreversible(shown)
    }

    /** «Elimina definitivamente» è in vermiglio: è l'unico gesto che non si può annullare. */
    private fun markIrreversible(d: androidx.appcompat.app.AlertDialog) {
        try {
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(androidx.core.content.ContextCompat.getColor(act, R.color.vermilion))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ a) percorso vero

    private fun byPath(path: String, uri: Uri?, name: String) {
        if (Build.VERSION.SDK_INT < 30) { deleteFile(path, null, uri, name); return }
        val mu = mediaUriForPath(path)
        if (mu != null && setTrashed(mu, true) && !File(path).exists()) {
            done(Deleted(name, path, uri, mu)); return
        }
        if (mu != null) {
            ui { trashRequest(mu, uri, path, name, permanentFallback = true) }
            return
        }
        ui { confirmPermanent(path, null, uri, name) }
    }

    private fun confirmPermanent(path: String, mu: Uri?, uri: Uri?, name: String) {
        if (act.isFinishing || act.isDestroyed) return
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.del_perm_title)
            .setMessage(act.getString(R.string.del_perm_body_notrash, name, Storage.folderLabel(path)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.del_perm_ok) { _, _ -> bg { deleteFile(path, mu, uri, name) } }
            .show().also { markIrreversible(it) }
    }

    private fun deleteFile(path: String, mu: Uri?, uri: Uri?, name: String) {
        val f = File(path)
        val ok = f.delete() || !f.exists()
        if (!ok) { fail(R.string.del_failed); return }
        // via anche dall'indice di MediaStore, cosi' Galleria & co. non mostrano un fantasma
        try { if (mu != null) ctx.contentResolver.delete(mu, null, null) } catch (_: Exception) {}
        scan(path)
        done(Deleted(name, path, uri, null))
    }

    // ------------------------------------------------------------ b) MediaStore

    /**
     * createTrashRequest accetta solo URI delle collezioni Images/Video/Audio (per id), NON quelli generici
     * di MediaStore.Files («All requested items must be Media items») e nemmeno Downloads («must be
     * referenced by specific ID»): visto sull'emulatore Android 15. Quindi si traduce l'URI nella
     * collezione giusta; un documento (PDF, Office…) non ci sta: senza «Accesso a tutti i file» non si puo'.
     */
    private fun trashRequest(mu: Uri, uri: Uri?, path: String?, name: String, permanentFallback: Boolean = false) {
        if (Build.VERSION.SDK_INT < 30) { fail(R.string.del_failed); return }
        bg {
            val candidates = collectionUris(mu)
            val docDeletable = uri != null && DocumentsContract.isDocumentUri(ctx, uri) && supportsDelete(uri)
            ui {
                for (c in candidates) {
                    try {
                        val pi = MediaStore.createTrashRequest(act.contentResolver, listOf(c), true)
                        afterRequest = { ok -> if (ok) bg { done(Deleted(name, path, uri, c)) } }
                        requestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        return@ui
                    } catch (e: Exception) {
                        Log.w(TAG, "createTrashRequest rifiutato per $c: ${e.message}")
                    }
                }
                when {
                    permanentFallback && path != null -> confirmPermanent(path, mu, uri, name)
                    docDeletable -> confirm(ByDocument(uri!!, path), uri, name) // c) definitivo, dopo conferma
                    path != null || !Storage.hasAccess(ctx) -> confirm(NeedAccess(path ?: ""), uri, name)
                    else -> fail(R.string.del_failed)
                }
            }
        }
    }

    /** L'URI tradotto nelle collezioni che createTrashRequest accetta, nell'ordine in cui provarle. */
    private fun collectionUris(mu: Uri): List<Uri> {
        if (Build.VERSION.SDK_INT < 30) return listOf(mu)
        val id = try { android.content.ContentUris.parseId(mu) } catch (_: Exception) { -1L }
        if (id < 0) return listOf(mu)
        val seg = mu.pathSegments
        if (seg.size >= 2 && seg[1] in setOf("images", "video", "audio")) return listOf(mu) // gia' tipizzato
        var type = -1
        try {
            ctx.contentResolver.query(mu, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), null, null, null)?.use { c ->
                if (c.moveToFirst()) type = c.getInt(0)
            }
        } catch (_: Exception) {}
        val vol = seg.firstOrNull() ?: "external"
        val out = mutableListOf<Uri>()
        when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> out += MediaStore.Images.Media.getContentUri(vol, id)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> out += MediaStore.Video.Media.getContentUri(vol, id)
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> out += MediaStore.Audio.Media.getContentUri(vol, id)
        }
        return out
    }

    private fun setTrashed(mu: Uri, value: Boolean): Boolean = PhoneTrash.setTrashed(ctx, mu, value)

    /** L'id MediaStore del file; se MediaStore non lo conosce ancora lo facciamo indicizzare. */
    @Suppress("DEPRECATION")
    private fun mediaUriForPath(path: String): Uri? {
        val files = MediaStore.Files.getContentUri("external")
        try {
            ctx.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null)?.use { c ->
                if (c.moveToFirst()) return MediaStore.Files.getContentUri("external", c.getLong(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "query MediaStore per $path fallita", e)
        }
        return scan(path)
    }

    private fun scan(path: String): Uri? {
        val latch = CountDownLatch(1)
        var out: Uri? = null
        MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(path), null) { _, u -> out = u; latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        return out
    }

    // ------------------------------------------------------------ c) DocumentsProvider

    private fun byDocument(uri: Uri, path: String?, name: String) {
        try {
            if (DocumentsContract.deleteDocument(ctx.contentResolver, uri)) done(Deleted(name, path, uri, null))
            else fail(R.string.del_failed)
        } catch (e: SecurityException) {
            Log.w(TAG, "deleteDocument negato", e); fail(R.string.del_no_write)
        } catch (e: Exception) {
            Log.w(TAG, "deleteDocument fallito", e); fail(R.string.del_failed)
        }
    }

    // ------------------------------------------------------------ d) nome dell'app proprietaria

    private fun appName(uri: Uri?, referrer: String?): String? {
        val pm = ctx.packageManager
        val auth = uri?.authority
        if (auth != null) {
            try {
                pm.resolveContentProvider(auth, 0)?.applicationInfo?.let { ai ->
                    if (ai.packageName != ctx.packageName) return ai.loadLabel(pm).toString()
                }
            } catch (_: Exception) {}
            KNOWN.entries.firstOrNull { auth == it.key || auth.startsWith(it.key + ".") }?.let { return it.value }
        }
        if (referrer != null && referrer != ctx.packageName) {
            try { return pm.getApplicationInfo(referrer, 0).loadLabel(pm).toString() } catch (_: Exception) {}
            KNOWN[referrer]?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ Annulla

    fun undo(d: Deleted) {
        val mu = d.trashUri ?: return
        bg {
            if (setTrashed(mu, false) && (d.path == null || File(d.path).exists())) {
                ui { restored(d) }
            } else ui {
                if (Build.VERSION.SDK_INT < 30) return@ui
                try {
                    val pi = MediaStore.createTrashRequest(act.contentResolver, listOf(mu), false)
                    afterRequest = { ok -> if (ok) restored(d) }
                    requestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } catch (e: Exception) {
                    Log.w(TAG, "ripristino rifiutato", e); toast(R.string.undo_failed)
                }
            }
        }
    }

    /**
     * Il cestino di MediaStore visto da qui. Su One UI i documenti cestinati non compaiono in nessuna
     * app di sistema (la Galleria mostra solo foto e video): senza questa lista, dopo la Snackbar
     * non ci sarebbe piu' modo di recuperarli.
     */
    fun showTrash() {
        if (Build.VERSION.SDK_INT < 30 || !Storage.hasAccess(ctx)) {
            MaterialAlertDialogBuilder(act).setTitle(R.string.trash_title).setMessage(R.string.trash_need_access)
                .setPositiveButton(R.string.ok, null).show()
            return
        }
        bg {
            val items = mutableListOf<Pair<Uri, CharSequence>>()
            val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
            val q = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_EXPIRES))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_EXPIRES), q, null,
            )?.use { c ->
                while (c.moveToNext() && items.size < 500) {
                    val n = (c.getString(1) ?: "file").replace(Regex("^\\.trashed-\\d+-"), "")
                    val exp = if (c.isNull(2)) null else "recoverable until " + fmt.format(java.util.Date(c.getLong(2) * 1000))
                    items += MediaStore.Files.getContentUri("external", c.getLong(0)) to trashLine(n, exp)
                }
            }
            ui {
                if (act.isFinishing || act.isDestroyed) return@ui
                if (items.isEmpty()) {
                    MaterialAlertDialogBuilder(act).setTitle(R.string.trash_title).setMessage(R.string.trash_empty)
                        .setPositiveButton(R.string.ok, null).show()
                    return@ui
                }
                val checked = BooleanArray(items.size)
                MaterialAlertDialogBuilder(act).setTitle(R.string.trash_title)
                    .setMultiChoiceItems(items.map { it.second }.toTypedArray(), checked) { _, w, on -> checked[w] = on }
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.trash_restore) { _, _ ->
                        val pick = items.filterIndexed { i, _ -> checked[i] }.map { it.first }
                        if (pick.isEmpty()) return@setPositiveButton
                        bg {
                            var ok = 0
                            for (u in pick) if (setTrashed(u, false)) {
                                ok++
                                Storage.dataColumn(ctx, u)?.let { p -> ui { Library.restore(act, p) } }
                            }
                            ui {
                                toast(if (ok == pick.size) R.string.restored else R.string.undo_failed)
                                onRestored(Deleted("", null, null, null))
                            }
                        }
                    }
                    .show()
            }
        }
    }

    /** Voce del cestino: il nome, e sotto in mono fino a quando si recupera. */
    private fun trashLine(name: String, until: String?): CharSequence {
        if (until == null) return name
        val s = android.text.SpannableString("$name\n$until")
        val from = name.length + 1
        val mono = try { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.mono_regular) } catch (_: Exception) { null }
        if (mono != null && Build.VERSION.SDK_INT >= 28) s.setSpan(android.text.style.TypefaceSpan(mono), from, s.length, 0)
        s.setSpan(android.text.style.RelativeSizeSpan(0.78f), from, s.length, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(ctx, R.color.ink_muted)), from, s.length, 0)
        return s
    }

    private fun restored(d: Deleted) {
        d.path?.let { Library.restore(act, it) }
        toast(R.string.restored)
        onRestored(d)
    }

    // ------------------------------------------------------------ utilita'

    private fun done(d: Deleted) = ui {
        Recents.forget(act, d.sourceUri, d.path)
        d.path?.let { Library.remove(act, it) }
        onDeleted(d)
    }

    private fun fail(msg: Int) = ui {
        if (act.isFinishing || act.isDestroyed) return@ui
        MaterialAlertDialogBuilder(act).setMessage(msg).setPositiveButton(R.string.ok, null).show()
    }

    private fun toast(msg: Int) = Toast.makeText(act.applicationContext, msg, Toast.LENGTH_LONG).show()
    private fun ui(f: () -> Unit) { main.post { try { f() } catch (e: Exception) { Log.w(TAG, "cancellazione (ui)", e) } } }
    private fun bg(f: () -> Unit) {
        if (io.isShutdown) return
        io.execute { try { f() } catch (e: Throwable) { Log.w(TAG, "cancellazione fallita", e); fail(R.string.del_failed) } }
    }

    companion object {
        private const val TAG = "151Viewer"

        /** Authority / pacchetti noti, per quando il sistema non ci lascia leggere il nome dell'app. */
        private val KNOWN = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "com.google.android.gm" to "Gmail", "org.telegram.messenger" to "Telegram", "org.telegram" to "Telegram",
            "com.google.android.apps.docs" to "Google Drive", "com.microsoft.office.outlook" to "Outlook",
            "com.samsung.android.email.provider" to "Samsung Email", "com.samsung.android.email" to "Samsung Email",
            "com.facebook.orca" to "Messenger", "com.instagram.android" to "Instagram", "com.android.chrome" to "Chrome",
            "com.google.android.apps.messaging" to "Messages", "com.samsung.android.messaging" to "Samsung Messages",
            "com.google.android.apps.photos" to "Google Photos", "com.dropbox.android" to "Dropbox",
            "com.microsoft.skydrive" to "OneDrive", "org.thoughtcrime.securesms" to "Signal",
        )

        fun openAccessSettings(a: Activity) {
            for (i in Storage.accessIntents(a)) {
                try { a.startActivity(i); return } catch (_: Exception) {}
            }
            Toast.makeText(a, R.string.access_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Nel cestino di Android senza domande (solo dove una domanda c'è già stata: «Delete from phone after upload»).
 * Si ripristina per 30 giorni da Galleria/File. Sotto Android 11 il cestino non c'è: torna false e il file RESTA.
 */
object PhoneTrash {
    /**
     * Gli id MediaStore di tanti file con poche domande (400 per volta): una domanda a file rendeva lunghissima
     * l'eliminazione di una cartella grande (08/11, telefono di prova: 2.400 file = 18 s solo per prepararla).
     */
    fun idsFor(ctx: Context, paths: List<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        if (Build.VERSION.SDK_INT < 30) return out
        val files = MediaStore.Files.getContentUri("external")
        for (chunk in paths.chunked(400)) try {
            ctx.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.DATA} IN (" + chunk.joinToString(",") { "?" } + ")", chunk.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { out[it] = c.getLong(0) }
            }
        } catch (e: Exception) { Log.w("151Storage", "id dei file non letti: ${e.message}") }
        return out
    }

    /**
     * Dentro o fuori dal cestino, 4 file insieme. Android non lo fa in blocco (lo rifiuta anche per raccolta:
     * «Movement of … images/media … not allowed», telefono di prova 08/11): uno per file, ma in parallelo.
     */
    fun setTrashedMany(ctx: Context, uris: List<Uri>, value: Boolean): Set<Uri> {
        val ok = java.util.concurrent.ConcurrentHashMap.newKeySet<Uri>()
        if (Build.VERSION.SDK_INT < 30 || uris.isEmpty()) return ok
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        for (u in uris) pool.execute { if (setTrashed(ctx, u, value)) ok += u }
        pool.shutdown(); pool.awaitTermination(30, TimeUnit.MINUTES)
        return ok
    }

    fun setTrashed(ctx: Context, mu: Uri, value: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (value) 1 else 0) }
            val extras = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            ctx.contentResolver.update(mu, v, extras) > 0
        } catch (e: Exception) {
            Log.w("151Storage", "IS_TRASHED=$value rifiutato su $mu: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** [src] = percorso sul telefono o content:// di MediaStore. true solo se il file non c'è più dov'era. */
    @Suppress("DEPRECATION")
    fun trash(ctx: Context, src: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (src.startsWith("content://")) {
            val u = Uri.parse(src)
            return u.authority == MediaStore.AUTHORITY && setTrashed(ctx, u, true)
        }
        val f = File(src)
        if (!f.exists()) return false
        val files = MediaStore.Files.getContentUri("external")
        var mu: Uri? = try {
            ctx.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(src), null)?.use { c ->
                if (c.moveToFirst()) MediaStore.Files.getContentUri("external", c.getLong(0)) else null
            }
        } catch (_: Exception) { null }
        if (mu == null) {
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(src), null) { _, u -> mu = u; latch.countDown() }
            latch.await(10, TimeUnit.SECONDS)
        }
        val u = mu ?: return false
        return setTrashed(ctx, u, true) && !f.exists()
    }
}

/**
 * Il cestino del telefono come lista: i file che Android tiene
 * 30 giorni dopo un'eliminazione. Eliminare per sempre tocca SOLO file il cui nome comincia con «.trashed-».
 */
object PhoneTrashList {
    class Item(val path: String, val name: String, val meta: String, val size: Long, val mtime: Long, val special: Boolean = false)

    private fun query(ctx: Context, cols: Array<String>): android.database.Cursor? {
        if (Build.VERSION.SDK_INT < 30) return null
        val q = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_EXPIRES))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        return try { ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, q, null) } catch (e: Exception) { Log.w("151Storage", "cestino illeggibile", e); null }
    }

    /** «52,223 files · 10.6 GB» per la riga del cestino. */
    fun summary(ctx: Context): String {
        if (!Storage.hasAccess(ctx)) return ctx.getString(R.string.trash_row_meta)
        var n = 0; var bytes = 0L
        query(ctx, arrayOf(MediaStore.MediaColumns.SIZE))?.use { c -> while (c.moveToNext()) { n++; bytes += c.getLong(0) } }
        return if (n == 0) ctx.getString(R.string.trash_empty) else plural(n, "file", "files") + " · " + Sizes.short(bytes)
    }

    fun rows(ctx: Context, uris: MutableMap<String, Uri>): List<Item> {
        val out = ArrayList<Item>()
        val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.UK)
        uris.clear()
        query(ctx, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.MediaColumns.DATE_MODIFIED))?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                val name = path.substringAfterLast('/').replace(Regex("^\\.trashed-\\d+-"), "")
                val size = c.getLong(2)
                val until = if (c.isNull(3)) "" else " · until " + fmt.format(java.util.Date(c.getLong(3) * 1000))
                uris[path] = MediaStore.Files.getContentUri("external", c.getLong(0))
                out += Item(path, name, Sizes.short(size) + " · " + Storage.folderLabel(path) + until, size, c.getLong(4) * 1000)
            }
        }
        if (out.isNotEmpty()) out.add(0, Item("", "", "", 0, 0, special = true))
        return out
    }

    /** Ripristina o elimina per sempre: (riusciti, falliti). */
    fun apply(ctx: Context, paths: List<String>, uris: Map<String, Uri>, restore: Boolean): Pair<Int, Int> {
        var ok = 0; var bad = 0
        val back = if (restore) PhoneTrash.setTrashedMany(ctx, paths.mapNotNull { uris[it] }, false) else emptySet()
        for (p in paths) {
            val u = uris[p]
            val done = if (restore) u != null && u in back
            else {
                val f = java.io.File(p)
                // muro: solo file nel cestino di Android
                if (!f.name.startsWith(".trashed-")) false
                else {
                    val gone = !f.exists() || f.delete()
                    if (gone && u != null) try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) {}
                    gone
                }
            }
            if (done) ok++ else bad++
        }
        // le cartelle rimaste vuote dopo «per sempre» se ne vanno;
        // mai le cartelle principali (Download, DCIM…) né quelle che hanno ancora qualcosa dentro
        if (!restore) {
            val roots = Storage.roots(ctx).map { it.absolutePath.trimEnd('/') }
            fun deepEnough(d: java.io.File) = roots.any { r -> d.absolutePath.startsWith("$r/") && d.absolutePath.removePrefix("$r/").contains('/') }
            for (d0 in paths.mapNotNull { java.io.File(it).parentFile }.distinct().sortedByDescending { it.absolutePath.length }) {
                var d: java.io.File? = d0
                while (d != null && deepEnough(d) && d.listFiles()?.isEmpty() == true && d.delete()) d = d.parentFile
            }
        }
        return ok to bad
    }
}
