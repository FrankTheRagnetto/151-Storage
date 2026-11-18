package com.the151universe.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Chiusa l'app, «Making cloud previews» restava ferma. Un avanzamento mandato DOPO la fine del primo
 * piano (tempo di Android finito, app scorsa via, servizio distrutto) diventava una notifica orfana in corso. Come si
 * mandava prima (`show` senza guardia), «avanzamentoInRitardo» e «filiInsieme» falliscono.
 */
class ForegroundNoteTest {
    private val log = Collections.synchronizedList(ArrayList<String>())
    private val note = ForegroundNote<String>({ log += "post:$it" }, { log += "remove" })

    @Test fun avanzamentoInRitardoNonRimetteLaNotifica() {
        assertTrue(note.update("10 previews"))
        assertTrue(note.end())
        assertFalse("arrivato dopo la fine: scartato", note.update("12 previews"))
        assertEquals(listOf("post:10 previews", "remove"), log)
        assertFalse(note.isLive)
    }

    @Test fun parolaFinaleDopoAverTolto() {
        note.update("1 of 3")
        note.end("All cloud previews are on the phone")
        assertEquals(listOf("post:1 of 3", "remove", "post:All cloud previews are on the phone"), log)
    }

    @Test fun laFineContaUnaVolta() {
        assertTrue(note.end("finale"))
        assertFalse(note.end("di nuovo")) // onDestroy dopo la fine del lavoro: niente
        assertEquals(listOf("remove", "post:finale"), log)
    }

    /** Quattro fili che mandano avanzamenti mentre il servizio finisce: dopo «remove» non passa più niente. */
    @Test fun filiInsiemeDopoLaFineNienteNotifiche() {
        val go = java.util.concurrent.CountDownLatch(1)
        val threads = (1..4).map { t -> Thread { go.await(); repeat(20_000) { note.update("t$t-$it") } }.also { it.start() } }
        go.countDown()
        Thread.sleep(5)
        note.end()
        threads.forEach { it.join() }
        val i = log.indexOf("remove")
        assertTrue(i >= 0)
        assertEquals("niente dopo la fine", emptyList<String>(), log.subList(i + 1, log.size).toList())
    }
}
