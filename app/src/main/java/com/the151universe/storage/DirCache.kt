package com.the151universe.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Le cartelle del Cloud si aprono SUBITO («we have all thumbnails already, why can we not have the
 * structure of the folders?»). Due fonti, nell'ordine:
 *  1. l'ultima volta che QUESTA cartella è stata letta dalla rete (`files/dir-cache/<cloud>/<cartella>.json`);
 *  2. la lista di tutto il cloud che il telefono fa all'apertura ([CloudList.kids], `index-phone/list-cache.tsv`).
 * Poi la rete rilegge la cartella e lo schermo si aggiorna solo se qualcosa è cambiato. È solo una copia per mostrare:
 * eliminati/spostati/rinominati passano comunque dal registro ([CloudState]) prima di arrivare a schermo.
 */
object DirCache {
    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    private fun root(ctx: Context) = File(ctx.filesDir, "dir-cache")
    private fun fileFor(ctx: Context, drive: CloudDrive, path: String) = File(File(root(ctx), sha(drive.label)), sha(path.trim('/')) + ".json")

    fun get(ctx: Context, drive: CloudDrive, path: String): List<CloudEntry>? = saved(ctx, drive, path) ?: fromList(ctx, drive, path)

    fun put(ctx: Context, drive: CloudDrive, path: String, items: List<CloudEntry>) {
        try {
            val a = JSONArray()
            for (e in items) a.put(JSONObject().put("r", e.ref).put("n", e.name).put("d", e.isDir).put("s", e.size).put("m", e.mtime).put("p", e.path)
                .also { o -> e.mime?.let { o.put("t", it) } })
            CloudList.writeAtomic(fileFor(ctx, drive, path), a.toString())
        } catch (_: Exception) {}
    }

    /** Scollegato o cambiato il cloud: via tutto. */
    fun forget(ctx: Context) { root(ctx).deleteRecursively() }

    private fun saved(ctx: Context, drive: CloudDrive, path: String): List<CloudEntry>? = try {
        val a = JSONArray(fileFor(ctx, drive, path).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            CloudEntry(o.getString("r"), o.getString("n"), o.getBoolean("d"), o.getLong("s"), o.getLong("m"), o.optString("t").ifEmpty { null }, o.getString("p"))
        } }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------ dalla lista di tutto il cloud

    private fun fromList(ctx: Context, drive: CloudDrive, path: String): List<CloudEntry>? {
        val meta = CloudList.meta(ctx, drive)?.takeIf { it.has("builtAt") } ?: return null
        val base = meta.optString("base")
        val p = path.trim('/')
        val rel = when {
            base.isEmpty() -> p
            p == base -> ""
            p.startsWith("$base/") -> p.removePrefix("$base/")
            else -> return null // fuori dalla lista (per esempio la radice sopra «active»): solo la copia salvata
        }
        val kids = CloudList.kids(ctx)?.get(rel) ?: return null
        return kids.map { k ->
            val full = if (p.isEmpty()) k.name else "$p/${k.name}"
            CloudEntry(WebDav.encPath(full).let { if (k.isDir) it else it.trimEnd('/') }, k.name, k.isDir,
                if (k.isDir) -1 else k.size, k.mtime, null, full)
        }
    }
}
