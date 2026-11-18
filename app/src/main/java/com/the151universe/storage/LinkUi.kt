package com.the151universe.storage

import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Cartelle collegate, la parte che si vede: dal Phone «Link to cloud» → la gemella nel cloud → il confronto
 * (cosa c'è solo qui, solo lassù, diverso) PRIMA di toccare qualcosa → «Link». In Settings l'elenco, con stato e gesti.
 */
object LinkUi {
    fun start(act: AppCompatActivity, folder: File) {
        val drive = Clouds.drive(act) ?: run { Ask.toast(act, act.getString(R.string.link_no_cloud)); return }
        FolderPicker.show(act, drive, "", R.string.link_here) { remote ->
            if (remote.isEmpty()) { Ask.toast(act, act.getString(R.string.link_pick_folder)); return@show }
            Toast.makeText(act, R.string.link_comparing, Toast.LENGTH_LONG).show()
            Thread({
                val pv = try { Links.preview(drive, folder, remote) } catch (e: Exception) {
                    act.runOnUiThread { Ask.toast(act, act.getString(R.string.link_failed, e.message ?: "error")) }; return@Thread
                }
                act.runOnUiThread { if (!act.isDestroyed) confirm(act, folder, remote, pv) }
            }, "collega-confronto").start()
        }
    }

    private fun confirm(act: AppCompatActivity, folder: File, remote: String, pv: Links.Preview) {
        MaterialAlertDialogBuilder(act)
            .setTitle(act.getString(R.string.link_title, folder.name))
            .setMessage(act.getString(R.string.link_summary, pv.same, pv.onlyPhone.size, pv.onlyCloud.size, pv.different.size) +
                "\n\n${folder.absolutePath.removePrefix("/storage/emulated/0/")}  ⇄  ${remote.ifEmpty { "Cloud" }}")
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.link_see_files) { _, _ -> files(act, folder, remote, pv) }
            .setPositiveButton(R.string.link_go) { _, _ ->
                Links.add(act, folder.absolutePath, remote)
                Ask.toast(act, act.getString(R.string.link_done))
                val app = act.applicationContext
                Thread({ try { Links.runAll(app, "just linked") } catch (e: Throwable) { android.util.Log.w("151Storage", "primo giro", e) } }, "collega-primo").start()
            }
            .show()
    }

    /** L'elenco dei file che il primo giro tocca: si guarda prima di dire sì. */
    private fun files(act: AppCompatActivity, folder: File, remote: String, pv: Links.Preview) {
        fun part(t: Int, l: List<String>) = if (l.isEmpty()) "" else "${act.getString(t)} (${l.size})\n" + l.take(300).joinToString("\n") { "· $it" } + (if (l.size > 300) "\n…" else "") + "\n\n"
        val body = part(R.string.link_list_diff, pv.different) + part(R.string.link_list_phone, pv.onlyPhone) + part(R.string.link_list_cloud, pv.onlyCloud)
        MaterialAlertDialogBuilder(act).setTitle(R.string.link_files_title).setMessage(body.ifEmpty { "—" })
            .setPositiveButton(R.string.back) { _, _ -> confirm(act, folder, remote, pv) }.show()
    }

    /** La riga di stato di un collegamento, in parole. */
    fun statusLine(act: AppCompatActivity, l: Link): String {
        val s = Links.status(act, l.id)
        return when {
            s.heldDeletes > 0 -> act.getString(R.string.link_held, s.heldDeletes)
            s.message != null -> act.getString(R.string.link_error, s.message)
            s.lastOk > 0 -> act.getString(R.string.link_in_sync, DateUtils.getRelativeTimeSpanString(s.lastOk, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
            else -> act.getString(R.string.link_never)
        }
    }

    /** Tocco su un collegamento in Settings: sincronizza ora, decidi le cancellazioni ferme, scollega. */
    fun actions(act: AppCompatActivity, l: Link, refresh: () -> Unit) {
        val s = Links.status(act, l.id)
        val items = ArrayList<Pair<Int, () -> Unit>>()
        fun runIt(h: Links.Held) {
            Ask.toast(act, act.getString(R.string.link_syncing))
            val app = act.applicationContext
            Thread({ try { Links.runOne(app, l.id, h) } catch (_: Throwable) {}; act.runOnUiThread { if (!act.isDestroyed) refresh() } }, "collega-ora").start()
        }
        items += R.string.link_sync_now to { runIt(Links.Held.ASK) }
        items += R.string.link_app_icon to { pickApp(act) }
        if (s.heldDeletes > 0) {
            items += R.string.link_bring_back to { runIt(Links.Held.RESTORE) }
            items += R.string.link_delete_too to { runIt(Links.Held.DELETE) }
        }
        items += R.string.link_unlink to {
            MaterialAlertDialogBuilder(act).setMessage(act.getString(R.string.link_unlink_ask, File(l.local).name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.link_unlink) { _, _ -> Links.remove(act, l.id); refresh() }.show()
        }
        MaterialAlertDialogBuilder(act).setTitle(File(l.local).name)
            .setItems(items.map { act.getString(it.first) }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Le app del telefono (quelle con un'icona), per scegliere quale aprire dall'icona nuova. */
    private fun pickApp(act: AppCompatActivity) {
        val pm = act.packageManager
        val apps = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != act.packageName }.distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        MaterialAlertDialogBuilder(act).setTitle(R.string.link_app_pick)
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> pin(act, apps[i].first, apps[i].second) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** L'icona sulla schermata principale: logo e nome dell'app, tocco = l'app si apre e Storage prende le novità. */
    private fun pin(act: AppCompatActivity, pkg: String, label: String) {
        val sm = act.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) { Ask.toast(act, act.getString(R.string.link_app_no_pin)); return }
        val d = try { act.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { return }
        val icon = if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            // i due strati interi (108dp, il ritaglio lo fa la schermata principale): uguale all'icona vera
            val n = 432; val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            d.background?.let { it.setBounds(0, 0, n, n); it.draw(c) }; d.foreground?.let { it.setBounds(0, 0, n, n); it.draw(c) }
            android.graphics.drawable.Icon.createWithAdaptiveBitmap(b)
        } else {
            val n = 192; val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            d.setBounds(0, 0, n, n); d.draw(c)
            android.graphics.drawable.Icon.createWithBitmap(b)
        }
        val intent = android.content.Intent(act, LinkLaunch::class.java).setAction(android.content.Intent.ACTION_VIEW).putExtra(LinkLaunch.EXTRA_PKG, pkg)
        val info = android.content.pm.ShortcutInfo.Builder(act, "open-$pkg").setShortLabel(label).setIcon(icon).setIntent(intent).build()
        try { sm.requestPinShortcut(info, null) } catch (e: Exception) { Ask.toast(act, e.message ?: "error") }
    }
}
