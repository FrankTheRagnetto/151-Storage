package com.the151universe.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * Le operazioni sui file del TELEFONO (Telefono, Media, Documenti): rinomina, sposta, copia, info, apri con.
 * Mai sopra un file che c'è: il nome libero « (1)» come per il cloud. Copiare = `.part` + dimensione + rename.
 * Dopo ogni cambio si avvisa MediaStore, così la Galleria e le altre app vedono il nome e il posto nuovi.
 */
object PhoneOps {
    fun rename(ctx: Context, f: File, newName: String): File {
        val clean = newName.trim().replace('/', '-')
        if (clean.isEmpty() || clean.startsWith('.')) throw IOException("Not a valid name")
        val dest = File(f.parentFile, clean)
        if (dest.exists()) throw IOException("«$clean» is already in this folder")
        if (!f.renameTo(dest)) throw IOException("The phone won't let «${f.name}» be renamed")
        scan(ctx, f, dest)
        return dest
    }

    /** Sposta o copia [src] dentro [destDir]; torna il file arrivato. Una cartella va con tutto quello che contiene. */
    fun transfer(ctx: Context, src: File, destDir: File, copy: Boolean): File {
        if (src.isDirectory && destDir.absolutePath.startsWith(src.absolutePath + "/") || destDir == src) throw IOException("A folder can't go inside itself")
        if (!copy && src.parentFile == destDir) return src
        destDir.mkdirs()
        val dest = File(destDir, freeName(src.name) { File(destDir, it).exists() })
        if (!copy && src.renameTo(dest)) { scan(ctx, src, dest); return dest }
        // altro disco (memoria ↔ SD) o copia: si copia davvero, e per spostare si cancella l'originale solo dopo
        copyTree(src, dest)
        if (!copy) { if (!src.deleteRecursively()) throw IOException("Copied, but the original can't be deleted") }
        scan(ctx, src, dest)
        return dest
    }

    private fun copyTree(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            // i file nel cestino di Android restano dove sono: una copia non li porta con sé
            src.listFiles()?.forEach { if (!Trashed.isTrashed(it.name)) copyTree(it, File(dest, it.name)) }
            return
        }
        val part = File(dest.parentFile, dest.name + ".part")
        src.inputStream().use { i -> part.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        if (part.length() != src.length()) { part.delete(); throw IOException("Incomplete copy: ${src.name}") }
        if (!part.renameTo(dest)) { part.delete(); throw IOException("Can't be written: ${dest.name}") }
        dest.setLastModified(src.lastModified())
    }

    private fun scan(ctx: Context, vararg files: File) {
        val paths = files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.map { it.path }.toList() else listOf(f.path) }
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx.applicationContext, paths.take(500).toTypedArray(), null, null)
    }

    /** «Foto», «Video», «PDF», «Word»… come si legge nel resto dell'app (non «text/xml»). */
    fun kindLabel(name: String): String {
        val mime = Thumbs.mimeOf(name)
        return when {
            mime.startsWith("image/") -> "Photo " + Resolver.extOf(name).uppercase()
            mime.startsWith("video/") -> "Video " + Resolver.extOf(name).uppercase()
            mime.startsWith("audio/") -> "Audio " + Resolver.extOf(name).uppercase()
            else -> Sections.catOfName(name).let { c -> if (c == Cat.OTHER || c == Cat.TEXT) Resolver.extOf(name).uppercase().ifEmpty { "File" } else c.label }
        }
    }

    /** «Memoria interna › DCIM/Camera» invece di «/storage/emulated/0/DCIM/Camera». */
    fun placeLabel(path: String): String {
        val folder = Storage.folderLabel(path)
        return if (folder == "Internal storage") folder else if (folder.startsWith("SD")) folder.replace(" · ", " › ") else "Internal storage › $folder"
    }

    /** Le righe dell'«Info» di un file o di una cartella del telefono. */
    fun info(ctx: Context, f: File): String = buildList {
        if (f.isDirectory) {
            val all = f.walkTopDown().filter { it.isFile }.toList()
            add(plural(all.size, "file", "files") + " · " + Sizes.short(all.sumOf { it.length() }))
        } else {
            add(Sizes.short(f.length()) + " · " + kindLabel(f.name))
            if (Thumbs.isMedia(f.name)) add("Taken: " + Dates.withTime(ShotDate.of(f.path, f.name, Thumbs.mimeOf(f.name), 0, f.lastModified())))
        }
        add("Modified: " + Dates.withTime(f.lastModified()))
        add(placeLabel(f.absolutePath))
        if (Uploads.isUploaded(ctx, f.absolutePath, f.length())) add(ctx.getString(R.string.already_in_cloud))
        if (Favorites.has(ctx, Favorites.phone(f.absolutePath))) add("★ In favourites")
    }.joinToString("\n")
}

/** Dialoghi comuni: chiedere un nome, mostrare l'info, «apri con». */
object Ask {
    fun name(act: AppCompatActivity, title: Int, start: String, onOk: (String) -> Unit) {
        val input = EditText(act).apply {
            setSingleLine(); setText(start)
            // il nome senza estensione già scelto: si scrive subito sopra
            val dot = start.lastIndexOf('.').takeIf { it > 0 } ?: start.length
            setSelection(0, dot)
        }
        val box = FrameLayout(act).apply { setPadding(act.dp(22), act.dp(8), act.dp(22), 0); addView(input) }
        val d = MaterialAlertDialogBuilder(act).setTitle(title).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() && it != start }?.let(onOk) }
            .show()
        input.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    fun info(act: AppCompatActivity, title: String, body: String) {
        MaterialAlertDialogBuilder(act).setTitle(title).setMessage(body).setPositiveButton(R.string.ok, null).show()
    }

    /** «Apri con»: la scelta di Android, senza di noi. */
    fun openWith(act: AppCompatActivity, uri: Uri, mime: String, name: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(name, uri)
        val chooser = Intent.createChooser(i, act.getString(R.string.open_with))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(
                android.content.ComponentName(act, ViewerActivity::class.java), android.content.ComponentName(act, MediaActivity::class.java)))
        try { act.startActivity(chooser) } catch (_: Exception) { Toast.makeText(act, R.string.error_open, Toast.LENGTH_LONG).show() }
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

/**
 * Preferiti (la stella): file e cartelle del telefono («phone:/storage/…») e del cloud («cloud:Photos/…»).
 */
object Favorites {
    private const val PREFS = "favorites"
    fun phone(path: String) = "phone:$path"
    /** Le cartelle del cloud finiscono con «/», così nei Preferiti si riaprono come cartelle. */
    fun cloud(path: String, isDir: Boolean = false) = "cloud:" + path.trim('/') + if (isDir) "/" else ""

    fun all(ctx: Context): List<String> = try {
        JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("keys", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    fun has(ctx: Context, key: String) = key in all(ctx)

    /** Mette o toglie; torna true se adesso è preferito. */
    fun toggle(ctx: Context, keys: List<String>): Boolean {
        val cur = all(ctx).toMutableList()
        val add = keys.any { it !in cur }
        if (add) keys.forEach { if (it !in cur) cur += it } else cur.removeAll(keys.toSet())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("keys", JSONArray(cur).toString()).apply()
        return add
    }

    fun rename(ctx: Context, old: String, new: String) {
        val cur = all(ctx).map { if (it == old) new else it }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("keys", JSONArray(cur).toString()).apply()
    }
}


