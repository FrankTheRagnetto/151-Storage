package com.the151universe.storage

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Il cestino del cloud, fatto dall'app da sola: una cartella sua nel cloud, `.151-storage-trash/`:
 *    `.151-storage-trash/<id>/<nome>` + `.151-storage-trash/<id>/meta.json` {id, origRel, name, type, size, deletedAt ISO},
 *    id = millisecondi in base 36 + «-» + 6 caratteri a caso, ripristino accanto con «-restored-<ms>» se il posto è preso.
 * Svuotamento da solo 30 giorni dopo `deletedAt`: a ogni apertura del Cestino e una volta al giorno all'apertura
 * dell'app. Una voce senza scheda non si tocca mai (non si sa quando è stata buttata).
 */
/** [loc] = dove sta adesso nel cloud (dentro il cestino), per l'anteprima e il ripristino. */
class TrashItem(val id: String, val name: String, val origRel: String, val isDir: Boolean, val size: Long, val deletedAt: Long, val loc: String = "")

interface CloudTrash {
    fun delete(items: List<CloudEntry>)
    fun list(): List<TrashItem>
    fun restore(item: TrashItem): String
    fun purge(item: TrashItem)
}

object Trashes {
    const val OWN_DIR = ".151-storage-trash"
    private const val DAYS = 30L

    /** Il cestino di questo cloud. Fuori dal thread principale. */
    fun forDrive(drive: CloudDrive): CloudTrash = FolderTrash(drive, OWN_DIR)

    /** Una volta al giorno, all'apertura dell'app: via le voci del cestino più vecchie di 30 giorni. */
    fun sweepIfDue(ctx: Context) {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences("trash", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("swept", 0) < 20 * 3_600_000L) return
        val drive = Clouds.drive(app) ?: return
        Thread({
            try { forDrive(drive).let { if (it is FolderTrash) it.sweep() else it.list() }; p.edit().putLong("swept", System.currentTimeMillis()).apply() }
            catch (e: Exception) { Log.w("151Storage", "cestino non svuotato: ${e.message}") }
        }, "151-trash-sweep").start()
    }

    fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(t))
    fun parseIso(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))?.time ?: 0
    } catch (_: Exception) { 0 }

    fun expired(t: Long) = t > 0 && System.currentTimeMillis() - t > DAYS * 86_400_000L

    /** Il momento scritto nel nome di una voce ([newId]: millisecondi in base 36 + «-…»); 0 = il nome non lo dice. */
    fun idTime(id: String, now: Long = System.currentTimeMillis()): Long {
        val t = id.substringBefore('-').takeIf { it.isNotEmpty() && it.length < id.length }?.toLongOrNull(36) ?: return 0
        return if (t in 1_500_000_000_000L..now + 86_400_000L) t else 0
    }

    /**
     * Va aperta (letta la sua scheda) per sapere se è scaduta? Sì se il nome non dice quando, o se dice più di 29 giorni.
     * Con migliaia di voci nel cestino e poche scadute, il giro leggeva tutte le schede a ogni apertura (e, chiuso
     * prima di finire da Android, ricominciava la volta dopo: processore e rete in sottofondo).
     */
    fun daAprire(id: String, now: Long = System.currentTimeMillis()): Boolean {
        val t = idTime(id, now)
        return t == 0L || now - t > (DAYS - 1) * 86_400_000L
    }

    /** Millisecondi in base 36 + «-» + 6 caratteri a caso. */
    fun newId(): String = System.currentTimeMillis().toString(36) + "-" + (1..6).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
}

/** Un cestino in una cartella del cloud: [dir] dalla radice. */
class FolderTrash(private val drive: CloudDrive, private val dir: String) : CloudTrash {

    override fun delete(items: List<CloudEntry>) {
        for (e in items) {
            val full = e.path.trim('/')
            val id = Trashes.newId()
            val entry = "$dir/$id"
            drive.resolveDir(entry, create = true)
            // prima il file nel cestino, poi la scheda; se la scheda non si scrive il file torna al suo posto
            drive.moveTo(e, entry, e.name)
            val meta = JSONObject().put("id", id).put("origRel", full).put("name", e.name)
                .put("type", if (e.isDir) "directory" else "file").put("size", if (e.isDir) 0 else e.size.coerceAtLeast(0))
                .put("deletedAt", Trashes.iso(System.currentTimeMillis()))
            try { drive.putSmall("$entry/meta.json", meta.toString().toByteArray()) } catch (x: Exception) {
                try { drive.putSmall("$entry/meta.json", meta.toString().toByteArray()) } catch (_: Exception) {
                    val moved = CloudEntry(WebDav.encPath("$entry/${e.name}").let { if (e.isDir) it else it.trimEnd('/') }, e.name, e.isDir, e.size, 0, null, "$entry/${e.name}")
                    try { drive.moveTo(moved, full.substringBeforeLast('/', ""), e.name); drive.deletePath(entry, isDir = true) } catch (_: Exception) {}
                    throw x
                }
            }
        }
    }

    override fun list(): List<TrashItem> {
        val root = drive.resolveDir(dir, create = false) ?: return emptyList()
        val out = ArrayList<TrashItem>()
        for (d in drive.list(root, dir).filter { it.isDir }) {
            val m = try { drive.readSmall("$dir/${d.name}/meta.json")?.let { JSONObject(String(it)) } } catch (_: Exception) { null } ?: continue
            // l'id è il nome VERO della cartella nel cestino, non quello scritto nella scheda: una scheda rovinata
            // («», «..») farebbe cancellare per sempre il posto sbagliato (09/11)
            out += TrashItem(d.name, m.optString("name"), m.optString("origRel"), m.optString("type") == "directory",
                m.optLong("size"), Trashes.parseIso(m.optString("deletedAt")), "$dir/${d.name}/${m.optString("name")}")
        }
        val (old, keep) = out.partition { Trashes.expired(it.deletedAt) }
        old.forEach { try { purge(it) } catch (_: Exception) {} }
        return keep.sortedByDescending { it.deletedAt }
    }

    override fun restore(item: TrashItem): String {
        val src = CloudEntry(WebDav.encPath("$dir/${item.id}/${item.name}").let { if (item.isDir) it else it.trimEnd('/') },
            item.name, item.isDir, item.size, 0, null, "$dir/${item.id}/${item.name}")
        if (drive.info(src.ref) == null) throw CloudException("The file isn't in the trash any more", retry = false)
        val parent = item.origRel.trim('/').substringBeforeLast('/', "")
        val there = try { drive.resolveDir(parent, create = true)?.let { r -> drive.list(r, parent).map { it.name }.toSet() } } catch (_: Exception) { null } ?: emptySet()
        // se al suo posto c'è già qualcosa, accanto con «-restored-<ms>»
        val name = if (item.name !in there) item.name else {
            val dot = item.name.lastIndexOf('.').takeIf { it > 0 && !item.isDir } ?: item.name.length
            item.name.substring(0, dot) + "-restored-" + System.currentTimeMillis() + item.name.substring(dot)
        }
        drive.moveTo(src, parent, name)
        drive.deletePath("$dir/${item.id}", isDir = true)
        return if (parent.isEmpty()) name else "$parent/$name"
    }

    override fun purge(item: TrashItem) = drive.deletePath("$dir/${item.id}", isDir = true)

    /** Il giro di pulizia: legge la scheda solo delle voci che possono essere scadute ([Trashes.daAprire]). Quante tolte. */
    fun sweep(): Int {
        val root = drive.resolveDir(dir, create = false) ?: return 0
        var tolte = 0
        for (d in drive.list(root, dir).filter { it.isDir && Trashes.daAprire(it.name) }) {
            val m = try { drive.readSmall("$dir/${d.name}/meta.json")?.let { JSONObject(String(it)) } } catch (_: Exception) { null } ?: continue
            val quando = Trashes.parseIso(m.optString("deletedAt"))
            if (!Trashes.expired(quando)) continue
            try { purge(TrashItem(d.name, m.optString("name"), m.optString("origRel"), m.optString("type") == "directory",
                m.optLong("size"), quando, "$dir/${d.name}/${m.optString("name")}")); tolte++ } catch (_: Exception) {}
        }
        return tolte
    }
}
