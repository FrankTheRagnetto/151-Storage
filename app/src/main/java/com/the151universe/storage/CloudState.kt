package com.the151universe.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * La verità del cloud dopo le modifiche fatte DA QUESTO TELEFONO («ogni sezione mostra sempre lo stato vero:
 * quello che è stato eliminato, spostato o rinominato sparisce o si aggiorna dappertutto, subito, anche tornando indietro»).
 *
 * Il difetto stava nelle copie: la lista del telefono (Doppioni, Quasi uguali, Random, Oggi, Preferiti,
 * ricerca), le cartelle già lette del Cloud, le copie «Sul telefono» in Media. Ognuna veniva corretta (o no) a mano dal
 * posto che cancellava. Adesso c'è UN registro: chi elimina, sposta o rinomina lo scrive qui ([deleted], [moved]); chi
 * mostra legge sempre attraverso [current] — mai la sua copia così com'è. Chi guarda si fa avvisare ([addListener]).
 *
 * Una voce resta finché tutte le liste che potrebbero ancora contenere la cosa vecchia sono state rifatte DOPO di lei
 * ([prune], con l'ora d'inizio della lista del telefono); al più 30 giorni. Ripristinare dal cestino la toglie ([revived]).
 * Percorsi pieni dalla radice del cloud dell'app («Photos/2026/…»). Scritto atomico in `files/index/cloud-state.json`.
 */
object CloudState {
    private const val TAG = "151Storage"
    private const val MAX_AGE = 30L * 86_400_000L
    private val gone = ConcurrentHashMap<String, Long>()          // percorso → quando
    private val moved = ConcurrentHashMap<String, Pair<String, Long>>() // vecchio → (nuovo, quando)
    @Volatile private var loaded = false
    /** Cresce a ogni modifica: chi mostra una copia sa se deve rileggerla. */
    @Volatile var version = 0L
        private set
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    private fun key(p: String) = p.trim('/')
    private fun file(ctx: Context) = File(File(ctx.filesDir, "index"), "cloud-state.json")

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val o = JSONObject(file(ctx).readText())
                o.optJSONObject("gone")?.let { g -> for (k in g.keys()) gone[k] = g.getLong(k) }
                o.optJSONObject("moved")?.let { m -> for (k in m.keys()) m.getJSONArray(k).let { a -> moved[k] = a.getString(0) to a.getLong(1) } }
            } catch (_: Exception) {}
            // il registro di prima (0.9.4–0.9.10: solo i cancellati) non serve più: lo copre la lista nuova
            loaded = true
        }
    }

    private fun save(ctx: Context) {
        try {
            val g = JSONObject(); for ((k, v) in gone) g.put(k, v)
            val m = JSONObject(); for ((k, v) in moved) m.put(k, JSONArray().put(v.first).put(v.second))
            CloudList.writeAtomic(file(ctx), JSONObject().put("gone", g).put("moved", m).toString())
        } catch (e: Exception) { Log.w(TAG, "registro del cloud non scritto", e) }
    }

    private fun changed(ctx: Context) {
        version++
        save(ctx)
        synchronized(listeners) { listeners.toList() }.forEach { try { it() } catch (_: Exception) {} }
    }

    /** Eliminati (nel cestino del cloud o per sempre): file o cartelle, con tutto quello che c'è dentro. */
    fun deleted(ctx: Context, paths: Collection<String>) {
        load(ctx)
        val now = System.currentTimeMillis()
        for (p in paths) { gone[key(p)] = now; moved.remove(key(p)) }
        changed(ctx)
    }

    /** Spostato o rinominato: chi mostrava [from] mostra [to]. */
    fun moved(ctx: Context, from: String, to: String) {
        load(ctx)
        val f = key(from); val t = key(to)
        if (f == t) return
        val now = System.currentTimeMillis()
        // una catena (A→B, poi B→C) diventa A→C; e se torna dove era, non è più spostato
        for ((k, v) in moved.entries.toList()) if (v.first == f) { if (k == t) moved.remove(k) else moved[k] = t to now }
        moved[f] = t to now
        gone.remove(t)
        Marks.moved(ctx, f, t) // il segno della cartella la segue
        changed(ctx)
    }

    /** Tornati (ripristinati dal cestino, ricaricati): di nuovo visibili. */
    fun revived(ctx: Context, paths: Collection<String>) {
        load(ctx)
        var any = false
        for (p in paths) { if (gone.remove(key(p)) != null) any = true }
        if (any) changed(ctx)
    }

    /**
     * Una cartella letta DAL VIVO, con la lettura cominciata a [listedFrom], contiene [paths]: quelli eliminati o spostati via
     * PRIMA di quell'ora ci sono di nuovo (rimessi dal PC, da un'altra app, ricopiati con lo stesso nome) e tornano visibili.
     * 15/11: un file eliminato dal telefono e rimesso sul server con lo stesso nome restava nascosto in ogni
     * cartella e nella ricerca finché la lista del telefono non veniva rifatta tutta (fino a 30 giorni, [prune]); il
     * video di prova del guardiano non si vedeva più. Le eliminazioni si scrivono qui DOPO che il cloud le ha fatte,
     * quindi una lettura cominciata dopo non può vedere il file vecchio.
     */
    fun seenLive(ctx: Context, paths: Collection<String>, listedFrom: Long) {
        load(ctx)
        if (gone.isEmpty() && moved.isEmpty()) return
        var any = false
        for (p in paths) {
            val k = key(p)
            gone[k]?.let { at -> if (at < listedFrom) { gone.remove(k); any = true } }
            // anche spostato/rinominato da qui: chi è a [k] adesso è un altro file, non va mostrato al posto nuovo
            moved[k]?.let { v -> if (v.second < listedFrom) { moved.remove(k); any = true } }
        }
        if (any) changed(ctx)
    }

    /**
     * Dove sta adesso quello che una copia chiama [path]: lo stesso percorso, quello nuovo se è stato spostato o
     * rinominato (anche una cartella sopra di lui), o null se è stato eliminato (lui o una cartella sopra di lui).
     */
    fun current(path: String): String? {
        if (gone.isEmpty() && moved.isEmpty()) return path.trim('/')
        var p = key(path)
        // prima gli spostamenti (il più lungo prefisso vince), poi i cancellati sul percorso risultante
        var guard = 0
        while (guard++ < 8) {
            var hit: Pair<String, String>? = null
            var cur = p
            while (true) {
                moved[cur]?.let { hit = cur to it.first }
                if (hit != null) break
                val i = cur.lastIndexOf('/'); if (i < 0) break
                cur = cur.substring(0, i)
            }
            val h = hit ?: break
            p = h.second + p.substring(h.first.length)
        }
        var cur = p
        while (true) {
            if (gone.containsKey(cur)) return null
            val i = cur.lastIndexOf('/'); if (i < 0) break
            cur = cur.substring(0, i)
        }
        return p
    }

    /** Le cartelle (genitori) toccate dopo [since]: le loro copie vanno rilette. */
    fun touchedDirs(since: Long): Set<String> {
        val out = HashSet<String>()
        for ((k, at) in gone) if (at > since) out += k.substringBeforeLast('/', "")
        for ((k, v) in moved) if (v.second > since) { out += k.substringBeforeLast('/', ""); out += v.first.substringBeforeLast('/', "") }
        return out
    }

    /**
     * Le voci più vecchie di [listFrom] (l'ora in cui è COMINCIATA la lista più vecchia ancora in uso) le sa già la lista:
     * non servono più. Mai oltre 30 giorni.
     */
    fun prune(ctx: Context, listFrom: Long) {
        load(ctx)
        val limit = maxOf(listFrom, System.currentTimeMillis() - MAX_AGE)
        var any = false
        for ((k, at) in gone.entries.toList()) if (at < limit) { gone.remove(k); any = true }
        for ((k, v) in moved.entries.toList()) if (v.second < limit) { moved.remove(k); any = true }
        if (any) save(ctx)
    }

    fun forget(ctx: Context) { gone.clear(); moved.clear(); file(ctx).delete(); version++ }
}
