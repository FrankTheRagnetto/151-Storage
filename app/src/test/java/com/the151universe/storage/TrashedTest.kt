package com.the151universe.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Il cestino di Android nella lista del Phone. Alberi veri su disco, come li lascia Android 14
 * sul telefono di prova: «.trashed-<scadenza>-<nome>» nella cartella del file. Con la logica di prima (cestino guardato al più
 * 2 livelli sotto, conto = nomi senza punto) le prove segnate «prima:» falliscono.
 */
class TrashedTest {
    private val top: File = Files.createTempDirectory("trashed-test").toFile()

    @After fun clean() { top.deleteRecursively() }

    private fun file(rel: String) = File(top, rel).apply { parentFile!!.mkdirs(); writeText("x") }
    private fun dir(rel: String) = File(top, rel).apply { mkdirs() }

    @Test fun iFileNelCestinoNonContano() {
        file("A/foto.jpg"); file("A/.trashed-1796932165-p1.jpg"); file("A/.trashed-1796932165-p2.jpg")
        val l = Trashed.look(File(top, "A"))!!
        assertEquals(1, l.visible)
        assertFalse(l.ghost)
    }

    @Test fun cartellaConSoloIlCestinoEVuota() {
        file("Due/.trashed-1796932165-p1.jpg"); file("Due/.trashed-1796932165-p2.jpg")
        assertTrue(Trashed.ghost(File(top, "Due")))
        assertEquals(0, Trashed.look(File(top, "Due"))!!.visible)
    }

    /** Prima: «2 items» (le due sottocartelle col solo cestino contavano) e dentro niente — il caso dell'utente in Download. */
    @Test fun dueSottocartelleFantasmaFannoZeroItems() {
        file("prova/Due/.trashed-1-p1.jpg"); file("prova/Due/.trashed-1-p2.jpg")
        file("prova/Fondo/a/b/c/.trashed-1-p3.jpg")
        val l = Trashed.look(File(top, "prova"))!!
        assertEquals(0, l.visible)
        assertTrue(l.ghost)
    }

    /** Prima: oltre 2 livelli la cartella non era «solo cestino», restava visibile e non si cancellava. */
    @Test fun ilCestinoContaAQualunqueProfondita() {
        file("Fondo/a/b/c/d/e/.trashed-1-p3.jpg")
        assertTrue(Trashed.ghost(File(top, "Fondo")))
    }

    @Test fun unFileVeroInFondoLaTieneViva() {
        file("Mista/a/.trashed-1-x.jpg"); file("Mista/b/c/vero.jpg")
        assertFalse(Trashed.ghost(File(top, "Mista")))
        assertEquals(1, Trashed.look(File(top, "Mista"))!!.visible) // «b» conta, «a» no
    }

    @Test fun cartellaVuotaNonEFantasma() {
        dir("Vuota")
        assertFalse(Trashed.ghost(File(top, "Vuota")))
        assertEquals(0, Trashed.look(File(top, "Vuota"))!!.visible)
    }

    /** Una sottocartella vuota vera si vede: chi la contiene non è fantasma. */
    @Test fun sottocartellaVuotaSiVede() {
        file("X/.trashed-1-a.jpg"); dir("X/vuota")
        assertFalse(Trashed.ghost(File(top, "X")))
        assertEquals(1, Trashed.look(File(top, "X"))!!.visible)
    }

    @Test fun nascostiNonContanoMaSenzaCestinoNonEFantasma() {
        file("N/.nomedia"); file("N/.trashed-1-a.jpg")
        assertTrue(Trashed.ghost(File(top, "N")))
        file("S/.nomedia")
        assertFalse(Trashed.ghost(File(top, "S")))
    }

    @Test fun ilFiltroDellaListaVale() {
        file("L/libreria/x.jpg"); file("L/.trashed-1-a.jpg")
        val skip: (File) -> Boolean = { it.name.startsWith('.') || it.name == "libreria" }
        assertTrue(Trashed.ghost(File(top, "L"), Trashed.Budget(), skip))
        assertFalse(Trashed.ghost(File(top, "L")))
    }

    /** Conto finito: nel dubbio la cartella si mostra (mai nascosta per un conto a metà). */
    @Test fun budgetFinitoMostra() {
        file("B/a/b/c/d/e/f/.trashed-1-x.jpg")
        assertFalse(Trashed.ghost(File(top, "B"), Trashed.Budget(3)))
        assertTrue(Trashed.ghost(File(top, "B"), Trashed.Budget(100)))
    }

    /** Dopo 30 giorni Android toglie i «.trashed-»: restano cartelle vuote, che allora si possono togliere davvero. */
    @Test fun dopoLoSvuotamentoRestanoSoloCartelle() {
        dir("Fondo/a/b/c")
        assertTrue(Trashed.noFiles(File(top, "Fondo")))
        file("Fondo/a/.trashed-1-x.jpg")
        assertFalse(Trashed.noFiles(File(top, "Fondo")))
    }

    @Test fun nomeDelCestino() {
        assertTrue(Trashed.isTrashed(".trashed-1796932165-p1.jpg"))
        assertFalse(Trashed.isTrashed("trashed-p1.jpg"))
        assertTrue(Trashed.isTrashed(".pending-1-p1.jpg")) // dal 0.10.36: salvataggio a metà = spazzatura
        assertFalse(Trashed.isTrashed("pending-1-p1.jpg"))
    }

    // telefono vero 12/11: «Nausicaa…» cancellata ricompariva; dentro restava solo un salvataggio a metà, nascosto
    @Test fun unaCartellaConSoloUnSalvataggioAMetaEFantasma() {
        file("Download/Serie/06 capitolo/.pending-1796931520-091.jpg")
        assertTrue(Trashed.ghost(File(top, "Download/Serie")))
        assertEquals(0, Trashed.look(File(top, "Download"))!!.visible)
    }
    @Test fun ilSalvataggioAMetaNonNascondeLeFotoVere() {
        file("D/S/.pending-1796931520-091.jpg"); file("D/S/090.jpg")
        assertFalse(Trashed.ghost(File(top, "D/S")))
        assertEquals(1, Trashed.look(File(top, "D"))!!.visible)
    }
}
