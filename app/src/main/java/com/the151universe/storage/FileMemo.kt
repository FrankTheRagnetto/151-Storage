package com.the151universe.storage

import java.io.File

/**
 * Un file letto UNA volta per processo e tenuto in memoria; si rilegge solo se è cambiato (data + misura).
 * Prima `Index.load` rileggeva la lista del telefono (indice delle foto tre volte, doppioni, quasi uguali) a ogni
 * apertura del Cloud e a ogni passaggio del giro delle anteprime, anche con i file identici: ~18 s di processore
 * sul telefono di prova (pool-9-thread-2, 12/11) anche uscendo subito. Puro Kotlin: provato sul PC (`IndexTest`).
 *
 * [reads] conta le letture vere (per le prove).
 */
class FileMemo<T : Any>(private val parse: (File) -> T) {
    private var key = ""
    private var value: T? = null
    @Volatile var reads = 0
        private set

    /** Il contenuto di [f]: dalla memoria se il file è lo stesso dell'ultima lettura; null se il file non c'è o non si legge. */
    @Synchronized fun get(f: File): T? {
        if (!f.isFile) { value = null; key = ""; return null }
        val k = "${f.absolutePath}|${f.lastModified()}|${f.length()}"
        value?.let { if (k == key) return it }
        value = null // la vecchia non resta in memoria insieme alla nuova
        reads++
        return try { parse(f).also { value = it; key = k } } catch (_: Exception) { key = ""; null }
    }

    /** Già in memoria per [f] così com'è adesso (nessuna lettura). */
    @Synchronized fun ready(f: File): Boolean = value != null && f.isFile && key == "${f.absolutePath}|${f.lastModified()}|${f.length()}"

    @Synchronized fun forget() { value = null; key = "" }
}
