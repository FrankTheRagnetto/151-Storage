package com.the151universe.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Recent(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: String,
    val time: Long,
    val page: Int,
)

/**
 * Lista "recenti" semplice in SharedPreferences (solo su questo telefono: il backup e' spento).
 * Per poter riaprire un file dopo il riavvio serve un permesso PERSISTENTE sull'URI: lo prendiamo
 * quando il mittente lo consente (sempre col selettore «Apri un file», quasi mai da "Apri con").
 */
object Recents {
    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 40

    fun list(ctx: Context): List<Recent> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Recent(
                    o.getString("uri"), o.optString("name", "file"), o.optString("mime", ""),
                    o.optLong("size", -1), o.optString("kind", Kind.UNSUPPORTED.name),
                    o.optLong("time", 0), o.optInt("page", 1),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, items: List<Recent>) {
        val a = JSONArray()
        items.forEach {
            a.put(
                JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime)
                    .put("size", it.size).put("kind", it.kind).put("time", it.time).put("page", it.page)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun find(ctx: Context, uri: Uri): Recent? = list(ctx).firstOrNull { it.uri == uri.toString() }

    /** Prova a rendere persistente il permesso di lettura. Non fallisce mai. */
    fun tryPersist(ctx: Context, uri: Uri) {
        if (uri.authority == Storage.authority(ctx)) return // file nostri (libreria): nessun permesso da prendere
        // v4: anche la scrittura, se concessa (serve a «Elimina» su un file preso con «Apri un file»)
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            return
        } catch (_: Exception) {
        }
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // il mittente non l'ha concesso: il file restera' nei recenti finche' il permesso vive
        }
    }

    /** true se possiamo ancora leggere l'URI: permesso persistente, file:// o permesso vivo del processo. */
    fun canRead(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return true
        Storage.ownPath(ctx, uri)?.let { return Storage.hasAccess(ctx) && java.io.File(it).canRead() }
        if (ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) return true
        return ctx.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Toglie dai recenti le voci che non si possono piu' riaprire. */
    fun prune(ctx: Context) {
        val items = list(ctx)
        val keep = items.filter { r -> try { canRead(ctx, Uri.parse(r.uri)) } catch (_: Exception) { false } }
        if (keep.size != items.size) save(ctx, keep)
    }

    /**
     * Aggiunge ai recenti SOLO se il file si potra' riaprire dopo (permesso persistente o file://).
     * Un file arrivato da "Apri con" con permesso temporaneo non si riapre piu' quando il
     * visualizzatore si chiude: metterlo in lista vorrebbe dire una voce che non funziona.
     */
    fun add(ctx: Context, info: DocInfo) {
        val uri = info.uri ?: return
        if (info.kind == Kind.NOT_DOC) return // v5: una foto aperta per sbaglio non diventa un "recente"
        val persisted = uri.scheme == "file" || uri.authority == Storage.authority(ctx) ||
            ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!persisted) return
        val key = uri.toString()
        val old = list(ctx)
        val prev = old.firstOrNull { it.uri == key }
        val entry = Recent(key, info.name, info.mime, info.size, info.kind.name, System.currentTimeMillis(), prev?.page ?: 1)
        val next = (listOf(entry) + old.filter { it.uri != key })
        save(ctx, next.take(MAX))
        next.drop(MAX).forEach { release(ctx, it.uri) }
    }

    fun setPage(ctx: Context, uri: Uri, page: Int) {
        val key = uri.toString()
        val items = list(ctx)
        if (items.none { it.uri == key && it.page != page }) return
        save(ctx, items.map { if (it.uri == key) it.copy(page = page) else it })
    }

    fun remove(ctx: Context, uri: String) {
        save(ctx, list(ctx).filter { it.uri != uri })
        release(ctx, uri)
    }

    /** Dopo una cancellazione: via la voce dell'URI e ogni voce che punta allo stesso file. */
    fun forget(ctx: Context, uri: Uri?, path: String?) {
        val items = list(ctx)
        val gone = items.filter { r ->
            r.uri == uri?.toString() || (path != null && try {
                val u = Uri.parse(r.uri)
                (u.scheme == "file" && u.path == path) || Storage.ownPath(ctx, u) == path
            } catch (_: Exception) { false })
        }
        if (gone.isEmpty()) return
        save(ctx, items - gone.toSet())
        gone.forEach { release(ctx, it.uri) }
    }

    /** v5: via foto e video dai recenti (SVG, TIFF, PSD restano: sono documenti). */
    fun dropNonDocuments(ctx: Context) {
        val docImages = setOf("image/svg+xml", "image/tiff", "image/vnd.adobe.photoshop")
        val items = list(ctx)
        val gone = items.filter { r ->
            r.kind == "IMAGE" || r.kind == Kind.NOT_DOC.name || r.mime.startsWith("video/") ||
                (r.mime.startsWith("image/") && r.mime !in docImages)
        }
        if (gone.isEmpty()) return
        save(ctx, items - gone.toSet())
        gone.forEach { release(ctx, it.uri) }
    }

    fun clear(ctx: Context) {
        list(ctx).forEach { release(ctx, it.uri) }
        save(ctx, emptyList())
    }

    private fun release(ctx: Context, uri: String) {
        try {
            val u = Uri.parse(uri)
            if (ctx.contentResolver.persistedUriPermissions.any { it.uri == u }) {
                val p = ctx.contentResolver.persistedUriPermissions.first { it.uri == u }
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                ctx.contentResolver.releasePersistableUriPermission(u, flags)
            }
        } catch (_: Exception) {
        }
    }
}
