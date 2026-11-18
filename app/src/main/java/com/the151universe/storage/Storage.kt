package com.the151universe.storage

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Accesso allo storage condiviso (v4): permesso «Accesso a tutti i file», radici da scansionare,
 * URI dei nostri file (FileProvider interno) e, al contrario, il percorso vero dietro un URI.
 * Tutto locale: nessuna di queste funzioni tocca la rete.
 */
object Storage {

    fun authority(ctx: Context) = ctx.packageName + ".files"

    /** true se possiamo leggere e scrivere tutto lo storage condiviso. */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * Intent per la schermata «Accesso a tutti i file» (Android 11+). Prima quella dell'app, poi
     * l'elenco generale, poi i dettagli dell'app: qualche ROM toglie una delle prime due.
     */
    fun accessIntents(ctx: Context): List<Intent> {
        val pkg = Uri.parse("package:" + ctx.packageName)
        return listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
    }

    /** Radici da scansionare: memoria interna condivisa + schede SD / USB montate. */
    fun roots(ctx: Context): List<File> {
        val out = linkedSetOf<File>()
        @Suppress("DEPRECATION")
        out += Environment.getExternalStorageDirectory()
        try {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else v.uuid?.let { File("/storage/$it") }
                if (dir != null && dir.isDirectory) out += dir
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** "Download/Lavoro" per un file in /storage/emulated/0/Download/Lavoro; "SD · Musica" per la scheda. */
    fun folderLabel(path: String): String {
        val parent = path.substringBeforeLast('/', "")
        val m = Regex("^/storage/emulated/\\d+/?(.*)$").find(parent)
        if (m != null) return m.groupValues[1].ifEmpty { "Internal storage" }
        val sd = Regex("^/storage/([^/]+)/?(.*)$").find(parent)
        if (sd != null) return "SD" + sd.groupValues[2].let { if (it.isEmpty()) "" else " · $it" }
        return parent
    }

    /** URI da passare al visualizzatore e alle altre app (niente file:// fuori dall'app). */
    fun uriFor(ctx: Context, path: String): Uri = FileProvider.getUriForFile(ctx, authority(ctx), File(path))

    /** Il percorso dietro un URI del NOSTRO FileProvider (content://…files/storage/emulated/0/…). */
    fun ownPath(ctx: Context, uri: Uri): String? {
        if (uri.authority != authority(ctx)) return null
        val s = uri.pathSegments
        if (s.size < 2 || s[0] != "storage" || s.any { it == ".." }) return null
        return "/storage/" + s.drop(1).joinToString("/")
    }

    private fun primaryRoot(): String {
        @Suppress("DEPRECATION")
        return Environment.getExternalStorageDirectory().absolutePath
    }

    /** Il file e' in una zona che non si tocca (dati privati delle app) o fuori dallo storage condiviso? */
    fun isShared(path: String): Boolean {
        if (!path.startsWith("/storage/") && !path.startsWith("/sdcard/")) return false
        val rel = path.replace(Regex("^/storage/(emulated/\\d+|[^/]+)/"), "")
        return !rel.startsWith("Android/data/") && !rel.startsWith("Android/obb/")
    }

    /**
     * Prova a trovare il file vero dietro un URI qualunque. Null se non si riesce o se non ci
     * fidiamo del risultato. Ogni tentativo e' protetto: un provider strano non deve far crashare.
     * [name]/[size] servono per l'ultimo tentativo (percorso indovinato dalla coda dell'URI):
     * il file trovato deve avere lo STESSO nome e la STESSA dimensione, altrimenti niente.
     */
    fun realPath(ctx: Context, uri: Uri, name: String?, size: Long): String? {
        val p = try { realPathRaw(ctx, uri, name, size) } catch (_: Exception) { null } ?: return null
        return p.takeIf { isShared(it) && File(it).isFile }
    }

    private fun realPathRaw(ctx: Context, uri: Uri, name: String?, size: Long): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        ownPath(ctx, uri)?.let { return it }
        val auth = uri.authority ?: return null
        if (auth == MediaStore.AUTHORITY) return dataColumn(ctx, uri)
        if (DocumentsContract.isDocumentUri(ctx, uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            when (auth) {
                "com.android.externalstorage.documents" -> {
                    val vol = id.substringBefore(':')
                    val rel = id.substringAfter(':', "")
                    return if (vol.equals("primary", true)) "${primaryRoot()}/$rel" else "/storage/$vol/$rel"
                }
                "com.android.providers.media.documents" -> {
                    val mid = id.substringAfter(':', "").toLongOrNull() ?: return null
                    return dataColumn(ctx, MediaStore.Files.getContentUri("external", mid))
                }
                "com.android.providers.downloads.documents" -> {
                    if (id.startsWith("raw:")) return id.removePrefix("raw:")
                    if (id.startsWith("msf:")) {
                        val mid = id.removePrefix("msf:").toLongOrNull() ?: return null
                        return dataColumn(ctx, MediaStore.Files.getContentUri("external", mid))
                    }
                }
            }
        }
        // provider sconosciuto: alcuni espongono ancora la colonna _data
        dataColumn(ctx, uri)?.let { if (File(it).isFile) return it }
        return guessFromTail(uri, name, size)
    }

    @Suppress("DEPRECATION")
    fun dataColumn(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(MediaStore.MediaColumns.DATA).takeIf { it >= 0 }?.let { c.getString(it) } else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Molti file manager (es. «Archivio» di Samsung) usano URI come
     * content://…FileProvider/device_storage/0/Download/x.pdf: la coda del percorso e' quella vera.
     * Proviamo le code sotto la memoria interna, e teniamo il file solo se nome E dimensione tornano.
     */
    private fun guessFromTail(uri: Uri, name: String?, size: Long): String? {
        if (name.isNullOrBlank() || size < 0) return null
        val segs = uri.pathSegments.map { Uri.decode(it) }
        if (segs.size < 2 || segs.last() != name) return null
        val root = primaryRoot()
        for (from in 1 until segs.size - 1) { // almeno una cartella: mai un file "nudo" nella radice indovinato a caso
            val f = File(root + "/" + segs.subList(from, segs.size).joinToString("/"))
            if (f.isFile && f.length() == size) return f.absolutePath
        }
        return null
    }

    /** Nome e dimensione (serve a [realPath] per il tentativo sulla coda dell'URI). */
    fun nameAndSize(ctx: Context, uri: Uri): Pair<String?, Long> = try {
        var n: String? = null
        var s = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) n = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) s = c.getLong(si)
            }
        }
        n to s
    } catch (_: Exception) {
        null to -1L
    }
}
