package com.the151universe.storage

import java.io.BufferedReader
import java.io.Writer

/**
 * La lista di tutto il cloud sul telefono (`index-phone/list-cache.tsv`), una riga per voce:
 * `cartella \t nome \t d|f \t dimensione \t data`. Si scrive e si legge UNA riga alla volta.
 *
 * Prima era `list-cache.json` (16 MB su un cloud vero) letto intero in un testo e poi in un albero JSON, in tre posti
 * diversi (ricerca, cartelle istantanee, anteprime spostate): 256 MB di memoria finiti e Storage chiuso da solo sul
 * un telefono vero (11/11 11:03, `Index.kids`). Qui in memoria resta solo cartella → figli, una volta per tutti.
 * Puro Kotlin: provato sul PC (`ListFileTest`).
 */
object ListFile {
    class Kid(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    /** Tab, a capo e «\» dentro un nome non rompono la riga. */
    fun esc(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) { '\\' -> b.append("\\\\"); '\t' -> b.append("\\t"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); else -> b.append(c) }
        return b.toString()
    }

    fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> b.append('\t'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }

    fun row(w: Writer, dir: String, name: String, isDir: Boolean, size: Long, mtime: Long) {
        w.write(esc(dir)); w.write('\t'.code); w.write(esc(name)); w.write('\t'.code)
        w.write(if (isDir) "d" else "f"); w.write('\t'.code); w.write(size.toString()); w.write('\t'.code)
        w.write(mtime.toString()); w.write('\n'.code)
    }

    /**
     * Cartella (relativa alla base, "" = la base) → figli. Ogni cartella elencata ha la sua chiave anche vuota, come la
     * lista di prima. Le righe rotte si saltano.
     */
    fun read(r: BufferedReader): Map<String, List<Kid>> {
        val m = HashMap<String, ArrayList<Kid>>(4096)
        m[""] = ArrayList()
        while (true) {
            val l = r.readLine() ?: break
            val t1 = l.indexOf('\t'); if (t1 < 0) continue
            val t2 = l.indexOf('\t', t1 + 1); if (t2 < 0) continue
            val t3 = l.indexOf('\t', t2 + 1); if (t3 < 0) continue
            val t4 = l.indexOf('\t', t3 + 1); if (t4 < 0) continue
            val dir = unesc(l.substring(0, t1))
            val name = unesc(l.substring(t1 + 1, t2))
            val isDir = l.substring(t2 + 1, t3) == "d"
            val size = l.substring(t3 + 1, t4).toLongOrNull() ?: -1
            val mtime = l.substring(t4 + 1).toLongOrNull() ?: 0
            m.getOrPut(dir) { ArrayList() }.add(Kid(name, isDir, if (isDir) -1 else size, mtime))
            if (isDir) m.getOrPut(if (dir.isEmpty()) name else "$dir/$name") { ArrayList() }
        }
        for (v in m.values) v.trimToSize()
        return m
    }
}
