package com.the151universe.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** I freni della batteria. Ogni prova fallisce con il comportamento di prima (scritto accanto). */
class FreniTest {
    private val h = 3_600_000L
    private val min = 60_000L
    private val day = 86_400_000L
    private val now = 1_000 * day

    // ---- giro delle anteprime

    @Test fun dopoGiroInteroAspetta10Minuti() {
        assertFalse(Freni.sweepDue(now, true, doneAt = now - 5 * min, triedAt = 0))
        assertTrue(Freni.sweepDue(now, true, doneAt = now - 11 * min, triedAt = 0))
    }

    /** Prima: un giro a metà non scriveva niente e il giro ripartiva a OGNI apertura. */
    @Test fun dopoGiroAMetaAspetta6Ore() {
        val doneAt = now - 3 * day
        assertFalse(Freni.sweepDue(now, true, doneAt, triedAt = now - 1 * min))
        assertFalse(Freni.sweepDue(now, true, doneAt, triedAt = now - 5 * h))
        assertTrue(Freni.sweepDue(now, true, doneAt, triedAt = now - 6 * h - 1))
    }

    @Test fun giroAMetaPiuVecchioDelGiroInteroNonConta() {
        assertTrue(Freni.sweepDue(now, true, doneAt = now - 20 * min, triedAt = now - 2 * h))
    }

    @Test fun cloudNuovoParteSubito() {
        assertTrue(Freni.sweepDue(now, false, doneAt = now - 1 * min, triedAt = now - 1 * min))
    }

    // ---- impronta della lista

    private fun e(path: String, size: Long, mtime: Long, dir: Boolean = false) = CloudEntry(path, path.substringAfterLast('/'), dir, size, mtime, null, path)
    private val base = listOf(e("Photos", -1, 100, dir = true), e("Photos/a.jpg", 1000, 50), e("Photos/b.mp4", 5000, 80))

    @Test fun stessaListaStessaImpronta() {
        assertEquals(Freni.fingerprint(base), Freni.fingerprint(base.reversed()))
    }

    @Test fun fileAggiuntoCambiaImpronta() {
        assertNotEquals(Freni.fingerprint(base), Freni.fingerprint(base + e("Photos/c.jpg", 10, 10)))
    }

    @Test fun fileCambiatoCambiaImpronta() {
        assertNotEquals(Freni.fingerprint(base), Freni.fingerprint(listOf(base[0], e("Photos/a.jpg", 1001, 50), base[2])))
        assertNotEquals(Freni.fingerprint(base), Freni.fingerprint(listOf(base[0], e("Photos/a.jpg", 1000, 150), base[2])))
    }

    /** Spostato: stessi numero, byte e date; cambia solo il percorso. */
    @Test fun fileSpostatoCambiaImpronta() {
        assertNotEquals(Freni.fingerprint(base), Freni.fingerprint(listOf(base[0], e("Photos/z.jpg", 1000, 50), base[2])))
    }

    // ---- file che fallisce

    /** Prima: il fallimento era solo in memoria; a ogni apertura il file si riscaricava (fino a 60 MB). */
    @Test fun dueFallimentiFermanoIlFilePerGiorni() {
        val first = Freni.nextFailCount(0, 0)
        assertEquals(1, first)
        assertFalse(Freni.failBlocks(first, 1 * min))
        val second = Freni.nextFailCount(first, 2 * h)
        assertEquals(2, second)
        assertTrue(Freni.failBlocks(second, 1 * min))
        assertTrue(Freni.failBlocks(second, 6 * day))
    }

    @Test fun dopo7GiorniSiRiprova() {
        assertFalse(Freni.failBlocks(2, 7 * day))
        assertEquals(1, Freni.nextFailCount(2, 8 * day))
    }

    // ---- MediaStore e disco

    /** Prima: MediaStore intero e giro del disco a ogni ritorno nell'app (30 s di tregua). */
    @Test fun mediaStoreUgualeNonSiRilegge() {
        assertFalse(Freni.mediaChanged("1|external_primary=42", "1|external_primary=42"))
        assertTrue(Freni.mediaChanged("1|external_primary=43", "1|external_primary=42"))
        assertTrue(Freni.mediaChanged(null, "x")) // Android 10 e prima: come prima
    }

    @Test fun giroDelDiscoAlPiuOgni6Ore() {
        assertFalse(Freni.walkDue(now, now - 1 * min))
        assertFalse(Freni.walkDue(now, now - 5 * h))
        assertTrue(Freni.walkDue(now, now - 6 * h))
        assertTrue(Freni.walkDue(now, 0))
        assertTrue(Freni.walkDue(now, now + 1 * h)) // orologio tornato indietro
    }

    // ---- caricamenti

    /** Prima: ogni 2 s. */
    @Test fun indiceCaricatiAlPiuOgni30Secondi() {
        assertFalse(Freni.doneWriteDue(now, now - 2_000))
        assertFalse(Freni.doneWriteDue(now, now - 29_000))
        assertTrue(Freni.doneWriteDue(now, now - 30_000))
    }

    /** Prima: l'errore chiudeva l'app, il servizio «sticky» ripartiva sullo stesso file, all'infinito. */
    @Test fun caricamentoCadutoDueVolteEFallito() {
        assertTrue(Freni.crashRetry(1))
        assertFalse(Freni.crashRetry(2))
    }

    // ---- coda dei caricamenti

    private fun job(i: Int, state: String, time: Long) = UpJob(i.toLong(), "/sdcard/$i.jpg", "$i.jpg", 1, "/Foto", state, time = time)

    /** L'telefono di prova l'11/11: 8.119 finiti + 4 saltati + 41 falliti di due giorni prima, coda ferma. Prima nessuno potava: 8.164 righe a ogni apertura. */
    @Test fun codaFermaVecchiaSiPotaA300() {
        val t = now - 2 * day
        val l = ArrayList<UpJob>()
        repeat(8119) { l += job(it, UpJob.DONE, t) }
        repeat(4) { l += job(9000 + it, UpJob.SKIP, t) }
        repeat(41) { l += job(10000 + it, UpJob.FAIL, t) }
        assertEquals(7864, Freni.trimQueue(l, now))
        assertEquals(300, l.size)
        assertEquals(41, l.count { it.state == UpJob.FAIL })
    }

    /** Durante un giro i finiti restano (il conto «X of Y» non deve scendere). */
    @Test fun giroInCorsoNonSiPota() {
        val l = ArrayList<UpJob>()
        repeat(5000) { l += job(it, UpJob.DONE, now - h) }
        l += job(99999, UpJob.WAIT, now)
        assertEquals(0, Freni.trimQueue(l, now))
    }

    /** I finiti degli ultimi 10 minuti restano anche a coda ferma; quelli di più di una settimana escono sempre. */
    @Test fun recentiRestanoVecchiDiSettimanaEscono() {
        val l = ArrayList<UpJob>()
        repeat(1000) { l += job(it, UpJob.DONE, now - 5 * min) }
        l += job(5000, UpJob.DONE, now - 8 * day)
        l += job(5001, UpJob.WAIT, now)
        assertEquals(1, Freni.trimQueue(l, now))
        assertEquals(1001, l.size)
    }

    // ---- «roba cancellata che ricompare»

    private data class F(val p: String)

    @Test fun scansioneVecchiaNonRimetteIlCancellato() {
        // la scansione è partita prima: ha visto a, b, c. Durante, l'app ha cancellato b (il disco non l'ha più)
        val r = Freni.afterScan(listOf(F("a"), F("b"), F("c")), { it.p }, setOf("b"), emptyList()) { it != "b" }
        assertEquals(listOf(F("a"), F("c")), r) // prima: [a, b, c], b tornava
    }

    @Test fun cancellazioneFallitaResta() {
        // l'app l'aveva tolto dallo schermo ma il file è ancora sul disco: la scansione ha ragione
        val r = Freni.afterScan(listOf(F("a"), F("b")), { it.p }, setOf("b"), emptyList()) { true }
        assertEquals(listOf(F("a"), F("b")), r)
    }

    @Test fun annullaDuranteLaScansioneRimette() {
        // b cancellato prima della scansione (lei non lo vede), «Annulla» mentre gira: b torna
        val r = Freni.afterScan(listOf(F("a")), { it.p }, emptySet(), listOf(F("b"))) { true }
        assertEquals(listOf(F("a"), F("b")), r) // prima: [a], b spariva fino alla scansione dopo
    }

    @Test fun senzaNovitaIlRisultatoEQuello() {
        val l = listOf(F("a"))
        assertTrue(Freni.afterScan(l, { it.p }, emptySet(), emptyList<F>()) { false } === l)
    }

    @Test fun richiestaDuranteLaLetturaNonSiPerde() {
        val g = Rilettura()
        assertTrue(g.start())           // la lettura parte
        assertFalse(g.start())          // cancellazione + rilettura chiesta mentre gira: non parte ora…
        assertTrue(g.finish())          // …ma alla fine se ne rifà una (prima: buttata, restava l'elenco vecchio)
        assertTrue(g.busy)
        assertFalse(g.finish())         // la seconda finisce e basta
        assertFalse(g.busy)
        assertTrue(g.start())
    }

    @Test fun toltoDuranteLaLetturaRilegge() {
        val g = Rilettura()
        g.dirty()                       // a riposo non conta
        assertTrue(g.start())
        g.dirty()                       // Gallery.remove mentre MediaStore si legge
        assertTrue(g.finish())
        assertFalse(g.finish())
    }
}

