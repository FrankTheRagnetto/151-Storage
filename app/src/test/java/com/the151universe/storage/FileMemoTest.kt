package com.the151universe.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**: [FileMemo] legge una volta e rilegge solo a file cambiato (data o misura). */
class FileMemoTest {
    private val top: File = Files.createTempDirectory("memo-test").toFile()
    @After fun clean() { top.deleteRecursively() }

    @Test fun unaLetturaSolaFinchéIlFileÈUguale() {
        val f = File(top, "l.tsv").apply { writeText("uno") }
        val m = FileMemo { it.readText() }
        repeat(50) { assertEquals("uno", m.get(f)) }
        assertEquals(1, m.reads)
        assertTrue(m.ready(f))
        f.writeText("due!"); f.setLastModified(f.lastModified() + 5000)
        assertFalse(m.ready(f))
        assertEquals("due!", m.get(f))
        assertEquals(2, m.reads)
    }

    @Test fun fileSparitoOrottoNull() {
        val f = File(top, "x")
        val m = FileMemo<String> { throw java.io.IOException("rotto") }
        assertNull(m.get(f))
        f.writeText("x")
        assertNull(m.get(f))
        assertFalse(m.ready(f))
    }
}
