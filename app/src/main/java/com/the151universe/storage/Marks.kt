package com.the151universe.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Un segno da mettere sulle cartelle del cloud: nome e colore scelti dall'utente (Settings › Folder marks). */
data class Mark(val id: String, val name: String, val color: Int)

/**
 * I segni sulle cartelle del cloud: tieni premuta una cartella →
 * «Mark as…». I segni li inventa l'utente in Settings. Stanno sul telefono (`files/marks.json`, scritto atomico) E nel cloud
 * (`.151-storage-marks.json` alla radice, nascosto): un altro telefono collegato allo stesso cloud li vede; fra i due
 * vince il più recente, voce per voce. Cartella spostata o rinominata dall'app: il segno la segue.
 */
object Marks {
    private const val FILE = "marks.json"
    const val REMOTE = ".151-storage-marks.json"
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private var defs: MutableList<Mark>? = null
    private var defsAt = 0L
    /** percorso della cartella → (id del segno, "" = tolto; quando) */
    private val marks = HashMap<String, Pair<String, Long>>()

    /** I colori fra cui scegliere: le tinte che l'app usa già (DESIGN.md), niente colori nuovi. */
    val PALETTE = listOf(R.color.hue_excel, R.color.hue_text, R.color.hue_word, R.color.place_cloud, R.color.hue_pdf,
        R.color.hue_ppt, R.color.hue_archive, R.color.hue_audio, R.color.hue_other)

    private fun defaults(ctx: Context) = mutableListOf(
        Mark("sorted", "Sorted", ctx.getColor(R.color.hue_excel)),
        Mark("downloaded", "Downloaded", ctx.getColor(R.color.hue_text)),
    )

    private fun key(path: String) = path.trim('/')

    private fun load(ctx: Context) {
        if (defs != null) return
        try { read(JSONObject(File(ctx.filesDir, FILE).readText())) } catch (_: Exception) {}
        if (defs == null) defs = defaults(ctx)
    }

    private fun read(o: JSONObject) {
        o.optJSONArray("defs")?.let { a -> defs = (0 until a.length()).map { a.getJSONObject(it).let { d -> Mark(d.getString("id"), d.getString("name"), d.getInt("color")) } }.toMutableList() }
        defsAt = o.optLong("defsAt")
        o.optJSONObject("marks")?.let { m -> for (k in m.keys()) m.getJSONObject(k).let { v -> marks[k] = v.optString("m") to v.optLong("at") } }
    }

    private fun json(): JSONObject {
        val m = JSONObject()
        for ((k, v) in marks) m.put(k, JSONObject().put("m", v.first).put("at", v.second))
        return JSONObject().put("defs", JSONArray().apply { defs!!.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } })
            .put("defsAt", defsAt).put("marks", m)
    }

    private fun save(ctx: Context, push: Boolean) {
        val text = json().toString()
        Uploads.writeAtomic(File(ctx.filesDir, FILE), text)
        if (push) Clouds.drive(ctx)?.let { d -> io.execute { try { d.putSmall(REMOTE, text.toByteArray()) } catch (e: Exception) { Log.w("151Storage", "segni: cloud non scritto", e) } } }
    }

    fun all(ctx: Context): List<Mark> = synchronized(lock) { load(ctx); defs!!.toList() }

    fun of(ctx: Context, path: String): Mark? = synchronized(lock) {
        load(ctx)
        val id = marks[key(path)]?.first?.takeIf { it.isNotEmpty() } ?: return null
        defs!!.firstOrNull { it.id == id }
    }

    /** [id] null = togli il segno. */
    fun set(ctx: Context, paths: Collection<String>, id: String?) = synchronized(lock) {
        load(ctx)
        val now = System.currentTimeMillis()
        for (p in paths) marks[key(p)] = (id ?: "") to now
        save(ctx, push = true)
    }

    fun saveDefs(ctx: Context, list: List<Mark>) = synchronized(lock) {
        load(ctx)
        defs = list.toMutableList(); defsAt = System.currentTimeMillis()
        save(ctx, push = true)
    }

    /** Spostata o rinominata dall'app: il segno (anche delle sottocartelle) va dietro. */
    fun moved(ctx: Context, from: String, to: String) = synchronized(lock) {
        load(ctx)
        val f = key(from); val t = key(to)
        val now = System.currentTimeMillis()
        var changed = false
        for ((k, v) in marks.entries.toList()) {
            if (v.first.isEmpty()) continue
            val nk = when { k == f -> t; k.startsWith("$f/") -> t + k.removePrefix(f); else -> continue }
            marks[nk] = v.first to now; marks[k] = "" to now; changed = true
        }
        if (changed) save(ctx, push = true)
    }

    /** Allinea col cloud (fuori dal thread principale): voce per voce vince la più recente. true = qualcosa è cambiato qui. */
    fun sync(ctx: Context, drive: CloudDrive): Boolean {
        val remote = try { drive.readSmall(REMOTE)?.let { JSONObject(String(it)) } } catch (_: Exception) { return false }
        synchronized(lock) {
            load(ctx)
            var here = false; var there = remote == null
            if (remote != null) {
                if (remote.optLong("defsAt") > defsAt) { remote.optJSONArray("defs")?.let { a ->
                    defs = (0 until a.length()).map { a.getJSONObject(it).let { d -> Mark(d.getString("id"), d.getString("name"), d.getInt("color")) } }.toMutableList()
                    defsAt = remote.optLong("defsAt"); here = true } }
                else if (remote.optLong("defsAt") < defsAt) there = true
                val rm = remote.optJSONObject("marks") ?: JSONObject()
                for (k in rm.keys()) {
                    val v = rm.getJSONObject(k); val at = v.optLong("at")
                    val mine = marks[k]
                    if (mine == null || at > mine.second) { marks[k] = v.optString("m") to at; here = true }
                    else if (at < mine.second) there = true
                }
                if (marks.keys.any { !rm.has(it) }) there = true
            }
            if (here || there) save(ctx, push = false)
            if (there && (marks.isNotEmpty() || remote != null)) try { drive.putSmall(REMOTE, json().toString().toByteArray()) } catch (_: Exception) {}
            return here
        }
    }
}
