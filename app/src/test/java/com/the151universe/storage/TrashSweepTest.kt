package com.the151universe.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15/11: il giro del cestino apre solo le voci che possono essere scadute (5744 voci, 2 scadute, sul telefono di prova). */
class TrashSweepTest {
    private val now = 1_794_744_000_000L // 15/11/2026
    private fun id(msFa: Long) = (now - msFa).toString(36) + "-abc123"
    private val giorno = 86_400_000L

    @Test fun ilNomeDiceQuando() {
        assertEquals(now - 5 * giorno, Trashes.idTime(id(5 * giorno), now))
        assertEquals(0L, Trashes.idTime("cartella-strana", now))
        assertEquals(0L, Trashes.idTime("..", now))
        assertEquals(0L, Trashes.idTime("abc", now))            // niente «-»: non è un nostro nome
        assertEquals(0L, Trashes.idTime("zzzzzzzzzzzz-x", now)) // fuori dagli anni possibili
    }

    @Test fun siApronoSoloLeVociCheForseSonoScadute() {
        assertFalse(Trashes.daAprire(id(1 * giorno), now))
        assertFalse(Trashes.daAprire(id(28 * giorno), now))
        assertTrue(Trashes.daAprire(id(29 * giorno + 1), now))   // un giorno di margine
        assertTrue(Trashes.daAprire(id(40 * giorno), now))
        assertTrue(Trashes.daAprire("nome-senza-data", now))     // non si sa: si apre come prima
    }
}
