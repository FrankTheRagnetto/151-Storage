package com.the151universe.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Dopo l'aggiornamento l'app mostrava all'apertura il crash della 0.10.15 come se fosse nuovo.
 * Rapporti finti, scritti come li scrive [CrashLog]. Usa solo ciò che c'era anche prima: sul codice di prima
 * «crashDiUnaVersioneDiPrima» fallisce.
 */
class CrashLogTest {
    private val top: File = Files.createTempDirectory("crash-test").toFile()
    private val ctx = TestCtx(File(top, "files"))
    @After fun clean() { top.deleteRecursively() }

    private fun report(name: String, code: Long) =
        "151 Storage $name ($code)\n2026-10-18 14:15:02\nsamsung SM-A528B · Android 14 (API 34)\nthread: main\n\njava.lang.IllegalStateException: prova\n"

    @Test fun crashDiUnaVersioneDiPrimaNonSiMostra() {
        CrashLog.file(ctx).writeText(report("0.10.15", BuildConfig.VERSION_CODE - 21L))
        assertNull(CrashLog.pending(ctx))
        assertNull("non torna alla prossima apertura", CrashLog.pending(ctx))
    }

    @Test fun crashDiQuestaVersioneSiMostra() {
        val r = report(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        CrashLog.file(ctx).writeText(r)
        assertEquals(r, CrashLog.pending(ctx))
    }
}
