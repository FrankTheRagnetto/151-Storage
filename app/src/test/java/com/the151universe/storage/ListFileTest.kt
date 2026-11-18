package com.the151universe.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

/**
 * La lista di tutto il cloud letta una riga alla volta. Prima: `list-cache.json` letto intero in testo + albero
 * JSON, e Storage chiuso per memoria finita su un telefono vero (11/11 11:03). L'ultima prova lo misura.
 */
class ListFileTest {
    private fun roundTrip(write: (StringWriter) -> Unit) = StringWriter().also(write).toString().let { ListFile.read(StringReader(it).buffered()) }

    @Test fun cartelleEFigli() {
        val m = roundTrip { w ->
            ListFile.row(w, "", "Photos", true, 0, 10)
            ListFile.row(w, "Photos", "a.jpg", false, 123, 20)
            ListFile.row(w, "Photos", "Vuota", true, 0, 30)
        }
        assertEquals(listOf("Photos"), m[""]!!.map { it.name })
        assertTrue(m[""]!![0].isDir)
        assertEquals(-1L, m[""]!![0].size)
        assertEquals(123L, m["Photos"]!!.first { it.name == "a.jpg" }.size)
        assertEquals(20L, m["Photos"]!!.first { it.name == "a.jpg" }.mtime)
        assertEquals(emptyList<ListFile.Kid>(), m["Photos/Vuota"]) // cartella vuota: c'è, senza figli
    }

    @Test fun radiceSempre() { assertEquals(emptyList<ListFile.Kid>(), roundTrip { }[""]) }

    @Test fun nomiStrani() {
        val brutto = "a\tb\\c\nd\re é 😀"
        val m = roundTrip { w -> ListFile.row(w, "x\ty", brutto, false, 1, 2) }
        assertEquals(brutto, m["x\ty"]!!.single().name)
        assertEquals(brutto, ListFile.unesc(ListFile.esc(brutto)))
    }

    @Test fun righeRotteSaltate() {
        val m = ListFile.read(StringReader("rotta\n\tsolo\tdue\nA\tb.jpg\tf\tnon-numero\t5\n").buffered())
        assertEquals(-1L, m["A"]!!.single().size)
        assertEquals(setOf("", "A"), m.keys)
    }

    /**
     * 200.000 voci (più di un cloud vero) lette con un tetto di memoria: la lista in memoria resta sotto i 64 MB.
     * La strada di prima (testo intero + albero JSON) per la stessa lista ne chiedeva diverse volte tanto.
     */
    @Test fun grandeListaMemoriaContenuta() {
        val sw = StringWriter(32 shl 20)
        for (d in 0 until 2_000) {
            ListFile.row(sw, "Photos/2026", "cartella-$d", true, 0, d.toLong())
            for (f in 0 until 100) ListFile.row(sw, "Photos/2026/cartella-$d", "IMG_2026${d}_$f.jpg", false, 3_000_000L + f, 1_700_000_000_000L + f)
        }
        val text = sw.toString()
        System.gc(); Thread.sleep(50)
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        val m = ListFile.read(StringReader(text).buffered())
        System.gc(); Thread.sleep(50)
        val used = rt.totalMemory() - rt.freeMemory() - before
        assertEquals(2_002, m.size)
        assertEquals(100, m["Photos/2026/cartella-7"]!!.size)
        assertTrue("lista in memoria: ${used shr 20} MB", used < (64L shl 20))
    }
}
