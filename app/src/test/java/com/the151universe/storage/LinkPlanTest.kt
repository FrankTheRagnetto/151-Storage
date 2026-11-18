package com.the151universe.storage

import com.the151universe.storage.LinkPlan.Act
import com.the151universe.storage.LinkPlan.Base
import com.the151universe.storage.LinkPlan.Meta
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cartelle collegate: la tabella delle decisioni. Nessuna di queste deve mai perdere un file. */
class LinkPlanTest {
    private val a = Meta(10, 1_000_000)
    private val a2 = Meta(12, 2_000_000)
    private val aSec = Meta(10, 1_000_900) // stessa data, il cloud arrotonda ai secondi

    private fun acts(l: Map<String, Meta>, r: Map<String, Meta>, b: Map<String, Base>?) =
        LinkPlan.plan(l, r, b).associate { it.path to it.act }

    @Test fun primaVoltaNonCancellaMai() {
        val m = acts(mapOf("solo-tel.md" to a), mapOf("solo-box.md" to a), null)
        assertEquals(mapOf("solo-tel.md" to Act.UPLOAD, "solo-box.md" to Act.DOWNLOAD), m)
    }

    @Test fun primaVoltaUgualiSiAdottano() {
        assertEquals(mapOf("x.md" to Act.ADOPT), acts(mapOf("x.md" to a), mapOf("x.md" to aSec), null))
    }

    @Test fun primaVoltaDiversiSiTengonoTutti() {
        assertEquals(mapOf("x.md" to Act.KEEP_BOTH), acts(mapOf("x.md" to a), mapOf("x.md" to a2), null))
        // stessa dimensione, data diversa: si guarda il contenuto
        assertEquals(mapOf("x.md" to Act.SAME_CHECK), acts(mapOf("x.md" to a), mapOf("x.md" to Meta(10, 9_000_000)), null))
    }

    @Test fun cambiatoDaUnaParte() {
        val b = mapOf("x.md" to Base(a, a))
        assertEquals(mapOf("x.md" to Act.UPLOAD), acts(mapOf("x.md" to a2), mapOf("x.md" to a), b))
        assertEquals(mapOf("x.md" to Act.DOWNLOAD), acts(mapOf("x.md" to a), mapOf("x.md" to a2), b))
        assertEquals(emptyMap<String, Act>(), acts(mapOf("x.md" to a), mapOf("x.md" to a), b))
    }

    @Test fun cambiatoDaTutteEDue() {
        val b = mapOf("x.md" to Base(a, a))
        assertEquals(mapOf("x.md" to Act.KEEP_BOTH), acts(mapOf("x.md" to a2), mapOf("x.md" to Meta(30, 3_000_000)), b))
        // [ ] → [x] da tutte e due le parti: stessa dimensione, si guarda il contenuto
        assertEquals(mapOf("x.md" to Act.SAME_CHECK), acts(mapOf("x.md" to Meta(10, 5_000_000)), mapOf("x.md" to Meta(10, 6_000_000)), b))
    }

    @Test fun cancellatoDaUnaParte() {
        val b = mapOf("x.md" to Base(a, a))
        assertEquals(mapOf("x.md" to Act.DEL_REMOTE), acts(emptyMap(), mapOf("x.md" to a), b))
        assertEquals(mapOf("x.md" to Act.DEL_LOCAL), acts(mapOf("x.md" to a), emptyMap(), b))
    }

    @Test fun cancellatoMaCambiatoDallAltraTorna() {
        val b = mapOf("x.md" to Base(a, a))
        assertEquals(mapOf("x.md" to Act.DOWNLOAD), acts(emptyMap(), mapOf("x.md" to a2), b))
        assertEquals(mapOf("x.md" to Act.UPLOAD), acts(mapOf("x.md" to a2), emptyMap(), b))
    }

    @Test fun sparitoDaTutteEDueSiDimentica() {
        assertEquals(mapOf("x.md" to Act.FORGET), acts(emptyMap(), emptyMap(), mapOf("x.md" to Base(a, a))))
    }

    @Test fun laSicuraContaLeCancellazioni() {
        val b = (1..25).associate { "n$it.md" to Base(a, a) }
        val steps = LinkPlan.plan(emptyMap(), b.mapValues { a }, b)
        assertEquals(25, LinkPlan.deletions(steps))
    }

    @Test fun nomeDellaCopia() {
        assertEquals("Logs/x (conflict phone 2026-11-13).md", LinkPlan.conflictName("Logs/x.md", "phone", "2026-11-13"))
        assertEquals("noext (conflict cloud 2026-11-13)", LinkPlan.conflictName("noext", "cloud", "2026-11-13"))
        assertEquals(".x (conflict phone 2026-11-13)", LinkPlan.conflictName(".x", "phone", "2026-11-13"))
    }
}
