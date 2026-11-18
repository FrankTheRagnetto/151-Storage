package com.the151universe.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**: il rapporto dice di che versione è, e mostrato una volta non si ripropone (resta in `crash-old.txt`). */
class CrashLogVersioneTest {
    private val top: File = Files.createTempDirectory("crash-test").toFile()
    private val ctx = TestCtx(File(top, "files"))
    @After fun clean() { top.deleteRecursively() }

    @Test fun versioneEOraDallaPrimaRiga() {
        val r = "151 Storage 0.10.15 (53)\n2026-10-18 14:15:02\nsamsung\n"
        assertEquals(53L, CrashLog.versionCodeOf(r))
        assertEquals("0.10.15" to "2026-10-18 14:15:02", CrashLog.headline(r))
        assertNull(CrashLog.versionCodeOf("151 Storage ?\n"))
        assertNull(CrashLog.headline("niente\n"))
    }

    @Test fun mostratoUnaVoltaNonTorna() {
        val r = "151 Storage ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n2026-11-12 10:00:00\n"
        CrashLog.file(ctx).writeText(r)
        assertEquals(r, CrashLog.pending(ctx))
        CrashLog.seen(ctx)
        assertNull(CrashLog.pending(ctx))
        assertTrue("il testimone resta per adb", File(ctx.filesDir, "crash-old.txt").readText() == r)
    }
}
