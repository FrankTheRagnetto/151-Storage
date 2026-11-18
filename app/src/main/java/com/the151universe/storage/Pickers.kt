package com.the151universe.storage

import android.os.Environment
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Scegliere una cartella del TELEFONO (sposta, copia, scarica qui): si entra con un tocco, ⟵ risale. */
object PhonePicker {
    fun show(act: AppCompatActivity, title: Int, okLabel: Int, start: File?, onPick: (File) -> Unit) {
        val roots = Storage.roots(act)
        @Suppress("DEPRECATION") var cur: File? = start?.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()
        lateinit var d: AlertDialog
        fun open(dir: File?) {
            cur = dir
            val kids = if (dir == null) roots else (dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') && !(it.name == "Android" && roots.any { r -> r == dir }) && !Trashed.ghost(it) }
                ?.sortedBy { it.name.lowercase() } ?: emptyList())
            val up = if (dir == null) null else if (roots.any { it == dir }) act.getString(R.string.places_title) else dir.parentFile?.let { p ->
                if (roots.any { it == p }) act.getString(R.string.internal_storage) else p.name }
            val labels = (if (up != null) listOf("⟵ $up") else emptyList()) + kids.mapIndexed { i, f ->
                if (dir == null) (if (i == 0) act.getString(R.string.internal_storage) else act.getString(R.string.sd_card)) else f.name } +
                (if (dir != null && kids.isEmpty()) listOf(act.getString(R.string.no_subfolders)) else emptyList())
            d.listView.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, labels)
            d.listView.setOnItemClickListener { _, _, pos, _ ->
                val k = pos - if (dir != null) 1 else 0
                if (dir != null && pos == 0) open(if (roots.any { it == dir }) null else dir.parentFile) else if (k in kids.indices) open(kids[k])
            }
            d.setTitle(dir?.let { if (roots.any { r -> r == it }) act.getString(R.string.internal_storage) else it.name } ?: act.getString(R.string.places_title))
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = dir != null
        }
        d = MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Viewer_Dialog_List).setTitle(title).setItems(arrayOf(" "), null)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(okLabel) { _, _ -> cur?.let(onPick) }.show()
        open(cur)
    }
}
