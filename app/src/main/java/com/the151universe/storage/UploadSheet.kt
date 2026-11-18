package com.the151universe.storage

import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Un file del telefono da caricare. [src] = percorso (o content://), [taken] = data di scatto se la sa MediaStore. */
/** [rel] = la cartella dentro quella scelta («Roma/giorno 1»): la cartella arriva nel cloud così com'è. */
class UpSource(val src: String, val name: String, val size: Long, val mime: String, val taken: Long, val mtime: Long, val path: String?, val rel: String? = null) {
    val isMedia get() = mime.startsWith("image/") || mime.startsWith("video/")
}

/**
 * Il foglietto «Carica nel cloud». Propone la cartella d'arrivo: foto e video nel mese di
 * scatto ([ShotDate]), gli altri file in «Documents». L'ultima scelta a mano vince sempre.
 * Prima di caricare guarda cosa c'è già (nome + dimensione) e lo dice. Il lavoro vero è di [UploadService].
 */
object UploadSheet {
    /** I predefiniti: foto e video nel mese di scatto, gli altri file in «Documents». */
    const val GENERIC_MEDIA = "Photos and videos/{YYYY}/{MM}"
    const val GENERIC_DOCS = "Documents"
    /** la chiave dell'ultima cartella scelta per le cartelle intere */
    private const val FOLDERS = "folders"
    fun join(base: String, rel: String?): String = listOf(base.trim('/'), rel.orEmpty().trim('/')).filter { it.isNotEmpty() }.joinToString("/")
    private val io = Executors.newSingleThreadExecutor()
    /** la coda si riempie su una strada sua: dietro al controllo dei doppioni (una domanda al cloud per cartella) aspettava */
    private val queueIo = Executors.newSingleThreadExecutor()

    fun show(act: AppCompatActivity, items: List<UpSource>, fixedDest: String?, emptyDirs: List<String> = emptyList(), onQueued: () -> Unit) {
        if (items.isEmpty() && emptyDirs.isEmpty()) return
        // cartelle scelte: arrivano intere, con le loro sottocartelle, dove si sceglie (niente mesi)
        val asFolders = fixedDest == null && (emptyDirs.isNotEmpty() || items.any { it.rel != null })
        val acc = Clouds.account(act)
        if (acc == null) { Toast.makeText(act, R.string.up_no_cloud, Toast.LENGTH_LONG).show(); return }
        if (acc.kind == Clouds.KIND_PROTON) { Proton.send(act, items); onQueued(); return }
        val drive = Clouds.drive(act) ?: return

        val dp = act.resources.displayMetrics.density
        val sheet = BottomSheetDialog(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (24 * dp).toInt(), (22 * dp).toInt(), (16 * dp).toInt())
            setBackgroundColor(ContextCompat.getColor(act, R.color.acetate))
        }
        fun text(style: Int, color: Int = R.color.ink, top: Int = 0) = TextView(act).apply {
            setTextAppearance(style)
            setTextColor(ContextCompat.getColor(act, color))
            (layoutParams as? LinearLayout.LayoutParams)
            setPadding(0, (top * dp).toInt(), 0, 0)
        }
        box.addView(text(R.style.TextAppearance_Viewer_Headline).apply { setText(R.string.up_title) })
        val summary = text(R.style.TextAppearance_Viewer_Meta, R.color.ink_muted, 6)
        box.addView(summary)

        val media = items.filter { it.isMedia }
        val docs = items.filterNot { it.isMedia }
        val dests = Clouds.destinations(act)

        class Group(val items: List<UpSource>, var template: String, val perMonth: Boolean, val key: String) {
            lateinit var field: TextInputEditText
            lateinit var note: TextView
            var dates: List<Long> = emptyList()
            var edited = false
            /** true mentre è l'app a scrivere nel campo: solo una mano vera lo rende «scelto dall'utente». */
            var auto = false
            fun put(text: String) { auto = true; field.setText(text); auto = false }
            fun base(): String = field.text.toString().trim().trim('/')
            fun dest(i: Int): String = if (perMonth && !edited) ShotDate.fill(template, dates.getOrElse(i) { System.currentTimeMillis() })
                else join(base(), items[i].rel)
        }
        val groups = ArrayList<Group>()
        if (fixedDest != null) groups += Group(items, fixedDest, false, "")
        else if (asFolders) groups += Group(items, dests.optString(FOLDERS, ""), false, FOLDERS)
        else {
            if (media.isNotEmpty()) groups += Group(media, dests.optString("media", ""), true, "media")
            if (docs.isNotEmpty()) groups += Group(docs, dests.optString("docs", ""), false, "docs")
        }
        for (g in groups) {
            box.addView(text(R.style.TextAppearance_Viewer_Meta, R.color.ink_muted, 18).apply {
                text = act.getString(R.string.up_dest) + if (groups.size > 1) (if (g.items === media) " · photos and videos" else " · other files") else ""
            })
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * dp).toInt(), 0, 0) }
            val til = TextInputLayout(act, null, com.google.android.material.R.attr.textInputStyle).apply { isHintEnabled = false }
            g.field = TextInputEditText(til.context).apply {
                setTextAppearance(R.style.TextAppearance_Viewer_Meta)
                textSize = 14f
                setTextColor(ContextCompat.getColor(act, R.color.ink))
                minHeight = (48 * dp).toInt()
                setSingleLine()
            }
            if (g.key == FOLDERS) g.field.hint = act.getString(R.string.up_top_level)
            g.put(if (g.key == FOLDERS) g.template else if (g.template.isEmpty()) "…" else if (g.perMonth) ShotDate.fill(g.template, System.currentTimeMillis()) else g.template)
            til.addView(g.field)
            row.addView(til, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(com.google.android.material.button.MaterialButton(act, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.up_browse)
                setOnClickListener { FolderPicker.show(act, drive, g.field.text.toString()) { p -> g.put(p); g.edited = true; g.note.isVisible = false } }
            })
            box.addView(row)
            g.note = text(R.style.TextAppearance_Viewer_Meta, R.color.ink_muted, 4).apply { isVisible = false }
            box.addView(g.note)
            if (g.key == FOLDERS) {
                val tops = (items.mapNotNull { it.rel?.substringBefore('/') } + emptyDirs.map { it.substringBefore('/') }).filter { it.isNotEmpty() }.distinct()
                // una riga sola anche con 23 cartelle
                fun folderNote() {
                    g.note.isVisible = tops.isNotEmpty()
                    val where = ("Cloud/" + g.base()).trimEnd('/').replace("/", " › ")
                    val names = if (tops.size <= 3) tops.joinToString(", ") else tops.take(2).joinToString(", ") + " and ${tops.size - 2} more"
                    g.note.text = act.getString(R.string.up_folders_note, where, names)
                }
                folderNote()
                g.field.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { g.note.post { folderNote() } }
                })
            }
            g.field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if (!g.auto) { g.edited = true; g.note.isVisible = false } }
            })
        }
        val dup = text(R.style.TextAppearance_Viewer_Prose, R.color.ink, 14).apply { setText(R.string.up_checking) }
        box.addView(dup)
        // «Delete from phone after upload», da ovunque si carichi: SOLO a caricamento confermato, nel cestino
        // del telefono; la scelta resta per la volta dopo
        val prefs = act.getSharedPreferences("settings", 0)
        val delAfter = com.google.android.material.materialswitch.MaterialSwitch(act).apply {
            setText(R.string.up_delete_after)
            setTextAppearance(R.style.TextAppearance_Viewer_Body)
            setTextColor(ContextCompat.getColor(act, R.color.ink))
            minHeight = (48 * dp).toInt()
            isChecked = prefs.getBoolean("upDeleteAfter", false) && android.os.Build.VERSION.SDK_INT >= 30
            isEnabled = android.os.Build.VERSION.SDK_INT >= 30
        }
        box.addView(delAfter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() })
        box.addView(text(R.style.TextAppearance_Viewer_Meta, R.color.ink_muted, 0).apply {
            setText(if (android.os.Build.VERSION.SDK_INT >= 30) R.string.up_delete_after_note else R.string.up_delete_after_old)
        })
        val go = com.google.android.material.button.MaterialButton(act).apply { isEnabled = false }
        box.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt() })
        box.addView(com.google.android.material.button.MaterialButton(act, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { setText(R.string.cancel); setOnClickListener { sheet.dismiss() } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // riempito dal controllo in sottofondo mentre si può già premere il tasto: insieme sicuro fra i due
        val skip: MutableSet<UpSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun refreshSummary() {
            // da cancellare dopo: anche quelli già lassù (uguali) entrano in coda, così si tolgono dal telefono
            val left = if (delAfter.isChecked) items else items.filter { it !in skip }
            summary.text = String.format(Locale.UK, "%,d files · %s", left.size, Sizes.short(left.sumOf { it.size.coerceAtLeast(0) }))
            go.text = if (left.size == 1) act.getString(R.string.up_go_one) else act.getString(R.string.up_go, left.size)
            if (delAfter.isChecked) go.text = act.resources.getQuantityString(R.plurals.up_go_delete, left.size, left.size)
            go.isEnabled = left.isNotEmpty() || emptyDirs.isNotEmpty()
            dup.isVisible = skip.isNotEmpty() || left.isEmpty()
            dup.text = if (left.isEmpty()) act.getString(R.string.up_all_there) else act.resources.getQuantityString(R.plurals.up_some_there, skip.size, skip.size)
        }
        summary.text = String.format(Locale.UK, "%,d files · %s", items.size, Sizes.short(items.sumOf { it.size.coerceAtLeast(0) }))
        delAfter.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("upDeleteAfter", on).apply(); if (!dup.text.contentEquals(act.getString(R.string.up_checking))) refreshSummary() }
        // si parte subito, senza aspettare il controllo:
        // i doppioni li salta comunque il caricamento, file per file; il controllo serve solo a dirlo prima
        refreshSummary(); dup.setText(R.string.up_checking); dup.isVisible = true

        go.setOnClickListener {
            val jobs = ArrayList<Triple<String, String, Long>>()
            val destOf = ArrayList<String>()
            for (g in groups) g.items.forEachIndexed { i, s ->
                if (s in skip && !g.edited && !delAfter.isChecked) return@forEachIndexed
                jobs += Triple(s.src, s.name, s.size)
                destOf += g.dest(i)
            }
            if (!asFolders && destOf.any { it.isEmpty() }) return@setOnClickListener
            for (g in groups) if (!g.perMonth && fixedDest == null) Clouds.setDestination(act, g.key, g.base())
            if (emptyDirs.isNotEmpty()) groups.firstOrNull()?.base()?.let { b ->
                // anche le cartelle vuote: la cartella arriva com'è
                io.execute { for (d in emptyDirs) try { drive.resolveDir(join(b, d), create = true) } catch (_: Exception) {} }
            }
            // la coda si riempie dietro: con migliaia di file sullo schermo bloccava l'app
            if (jobs.isNotEmpty()) { val del = delAfter.isChecked; val app = act.applicationContext; queueIo.execute { Uploads.add(app, jobs, thenDelete = del) { destOf[it] } } }
            (act as? MainActivity)?.askNotifications()
            if (jobs.isNotEmpty()) Toast.makeText(act, act.getString(R.string.up_queued, jobs.size), Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            onQueued()
        }

        sheet.setContentView(androidx.core.widget.NestedScrollView(act).apply { addView(box) })
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()

        // in background: date di scatto, cartelle proposte, e cosa c'è già lassù
        io.execute {
            for (g in groups) if (g.template.isEmpty() && g.key != FOLDERS) g.template = if (g.key == "media") GENERIC_MEDIA else GENERIC_DOCS
            for (g in groups) if (g.perMonth) g.dates = g.items.map { ShotDate.of(it.path, it.name, it.mime, it.taken, it.mtime) }
            act.runOnUiThread {
                for (g in groups) if (!g.perMonth && g.field.text.toString() == "…") g.put(g.template)
                for (g in groups) if (g.perMonth) {
                    val folders = g.items.indices.map { g.dest(it) }.distinct()
                    g.put(folders.first())
                    if (folders.size > 1) {
                        g.note.isVisible = true
                        g.note.text = "→ ${folders.size} folders, " + act.getString(R.string.up_per_month) + ": " + folders.joinToString(" · ") { it.substringAfterLast('/') }
                    }
                }
            }
            try {
                for (g in groups) {
                    val byDir = g.items.indices.groupBy { g.dest(it) }
                    for ((dir, idx) in byDir) {
                        val ref = drive.resolveDir(dir, create = false) ?: continue
                        val there = drive.list(ref, dir).filter { !it.isDir }.associate { it.name to it.size }
                        for (i in idx) { val s = g.items[i]; if (there[s.name] == s.size) { skip += s; Uploads.markUploaded(act, s.src, s.size) } }
                    }
                }
            } catch (_: Exception) { /* senza rete il controllo si salta: lo rifà comunque il caricamento */ }
            if (skip.isNotEmpty()) Uploads.flush(act)
            act.runOnUiThread { if (sheet.isShowing) refreshSummary(); if (skip.isNotEmpty()) Uploads.changed() }
        }
    }
}

/** «Sfoglia» del foglietto: le cartelle del cloud, si entra con un tocco, «Carica qui» sceglie. */
object FolderPicker {
    fun show(act: AppCompatActivity, drive: CloudDrive, start: String, okLabel: Int = R.string.up_here, onPick: (String) -> Unit) {
        var path = ""
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        fun open(p: String) {
            path = p
            Executors.newSingleThreadExecutor().execute {
                val names = try {
                    val ref = drive.resolveDir(p, create = false)
                    if (ref == null) emptyList() else drive.list(ref, p).filter { it.isDir }.map { it.name }
                } catch (e: Exception) { emptyList() }
                act.runOnUiThread {
                    val up = if (p.isEmpty()) null else p.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { "Cloud" }
                    val items = (if (up != null) listOf("⟵ $up") else emptyList()) + names +
                        (if (names.isEmpty()) listOf(act.getString(R.string.no_subfolders)) else emptyList())
                    val lv = dialog.listView
                    lv.adapter = android.widget.ArrayAdapter(act, android.R.layout.simple_list_item_1, items)
                    lv.setOnItemClickListener { _, _, pos, _ ->
                        val k = pos - if (p.isNotEmpty()) 1 else 0
                        if (p.isNotEmpty() && pos == 0) open(p.substringBeforeLast('/', "")) else if (k in names.indices) open((if (p.isEmpty()) "" else "$p/") + names[k])
                    }
                    dialog.setTitle(if (p.isEmpty()) "Cloud" else p.replace("/", " › "))
                }
            }
        }
        dialog = MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Viewer_Dialog_List)
            .setTitle(R.string.up_choose_title)
            .setItems(arrayOf(" "), null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(okLabel) { _, _ -> onPick(path) }
            .show()
        // si parte dalla cartella più profonda che esiste già sul percorso proposto
        Executors.newSingleThreadExecutor().execute {
            var p = start.trim('/')
            while (p.isNotEmpty() && (try { drive.resolveDir(p, false) } catch (_: Exception) { null }) == null) p = p.substringBeforeLast('/', "")
            act.runOnUiThread { open(p) }
        }
    }
}

/** Proton Drive: i file vanno all'app ufficiale (Condividi mirato), che chiede la cartella e carica. */
object Proton {
    fun send(act: AppCompatActivity, items: List<UpSource>) {
        val uris = ArrayList(items.mapNotNull { s ->
            try { if (s.src.startsWith("content://")) android.net.Uri.parse(s.src) else Storage.uriFor(act, s.src) } catch (_: Exception) { null }
        })
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
        else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
        i.type = if (items.all { it.mime.startsWith("image/") }) "image/*" else "*/*"
        i.setPackage(Clouds.PROTON_PACKAGE)
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        try {
            act.startActivity(i)
            Toast.makeText(act, R.string.proton_sent, Toast.LENGTH_LONG).show()
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(act, R.string.proton_missing, Toast.LENGTH_LONG).show()
        }
    }
}

/** Il foglio dei caricamenti (spec §11): ogni file col suo stato; Ferma tutto, Pulisci i finiti, Riprova. */
object JobsSheet {
    fun show(act: AppCompatActivity) {
        val jobs = Uploads.all(act).sortedByDescending { it.time }
        val lines = jobs.take(200).map { j ->
            val st = when (j.state) {
                UpJob.WAIT -> "queued"
                UpJob.RUN -> if (j.size > 0) "${j.sent * 100 / j.size} % · ${Sizes.short(j.sent)} of ${Sizes.short(j.size)}" else "uploading"
                UpJob.DONE -> "done → " + j.dest + "/" + j.remoteName
                UpJob.SKIP -> "already in the cloud, skipped"
                else -> "error: " + j.msg
            }
            "${j.name}\n$st"
        }
        val b = MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Viewer_Dialog_List).setTitle(R.string.jobs_title)
        if (lines.isEmpty()) b.setMessage("—") else b.setItems(lines.toTypedArray(), null)
        if (jobs.any { it.state == UpJob.FAIL }) b.setPositiveButton(R.string.jobs_retry) { _, _ -> Uploads.retryFailed(act) }
        else b.setPositiveButton(R.string.ok, null)
        if (jobs.any { it.state == UpJob.WAIT || it.state == UpJob.RUN }) b.setNegativeButton(R.string.jobs_stop) { _, _ ->
            act.startService(android.content.Intent(act, UploadService::class.java).setAction(UploadService.ACTION_STOP))
            Uploads.stopAll(act)
        }
        if (jobs.any { it.finished }) b.setNeutralButton(R.string.jobs_clear) { _, _ -> Uploads.clearFinished(act) }
        b.show()
    }
}
