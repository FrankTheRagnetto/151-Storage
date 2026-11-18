package com.the151universe.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * (telefono di prova 12/11: ~18 s di processore a ogni apertura, pool-9-thread-2 in `Index.load`, anche uscendo subito).
 * La lista del telefono si legge solo quando serve, una volta per processo, e di nuovo solo se il file è cambiato
 * (data + misura). Liste anonime fatte qui. Usa solo ciò che c'era anche prima: sul codice di prima le prove
 * «nonLegge» e «nonSiRilegge» falliscono.
 */
class IndexTest {
    private val top: File = Files.createTempDirectory("index-test").toFile()
    private val ctx = TestCtx(File(top, "files"))
    private val drive = testDrive()
    private val dir get() = File(ctx.filesDir, "index-phone")

    private fun media(name: String) = """{"version":1,"builtAt":1,"buckets":{"03-15":[{"path":"Photos/2020/$name","name":"$name","kind":"image","capturedDate":"2020-03-15","year":"2020"}]}}"""
    private fun dups(a: String) = """{"version":1,"builtAt":"2026-11-01T10:00:00","groups":[{"size":10,"files":[{"path":"Photos/$a"},{"path":"Copie/$a"}]}]}"""
    private fun write(name: String, text: String) = File(dir, name).apply { parentFile!!.mkdirs(); writeText(text) }

    @Before fun fixtures() {
        Index.forget(ctx)
        write("meta.json", """{"cloud":"test-cloud","builtAt":1,"scanAt":1,"base":"","dupGroups":1}""")
        write("similar.json", """{"version":1,"groups":[]}""")
    }

    @After fun clean() { Index.forget(ctx); top.deleteRecursively() }

    /** Aprire il Cloud non legge l'indice delle foto: qui è rotto apposta, e la lista resta comunque «c'è». Prima: letto, «non c'è». */
    @Test fun aprireIlCloudNonLeggeLIndice() {
        write("media-index.json", "{rotto")
        write("duplicates.json", "{rotto")
        Index.load(ctx, drive)
        assertTrue("la lista del telefono c'è anche senza averla letta", Index.available)
        assertEquals("phone", Index.source)
        assertEquals(emptyList<Index.Item>(), Index.random(16)) // letta quando serve: rotta = vuota, mai un crash
    }

    /** Il giro delle anteprime richiama load(force) più volte: con lo stesso file la lista NON si rilegge. Prima sì. */
    @Test fun stessoFileNonSiRilegge() {
        val f = write("media-index.json", media("a.jpg"))
        Index.load(ctx, drive, force = true)
        assertEquals(listOf("Photos/2020/a.jpg"), Index.random(16).map { it.path })
        // stessa misura e stessa data, dentro un altro nome: se si rileggesse, Random direbbe b.jpg
        val t = f.lastModified()
        f.writeText(media("b.jpg")); f.setLastModified(t)
        Index.load(ctx, drive, force = true)
        Index.load(ctx, drive, force = true)
        assertEquals(listOf("Photos/2020/a.jpg"), Index.random(16).map { it.path })
    }

    /** Ma se il file cambia davvero (misura o data), la lista nuova si vede. */
    @Test fun fileCambiatoSiRilegge() {
        val f = write("media-index.json", media("a.jpg"))
        Index.load(ctx, drive, force = true)
        assertEquals(listOf("Photos/2020/a.jpg"), Index.random(16).map { it.path })
        f.writeText(media("nuova.jpg")); f.setLastModified(f.lastModified() + 5000)
        Index.load(ctx, drive, force = true)
        assertEquals(listOf("Photos/2020/nuova.jpg"), Index.random(16).map { it.path })
    }

    /** Anche i doppioni: stesso file, nessuna rilettura. */
    @Test fun doppioniStessoFileNonSiRileggono() {
        write("media-index.json", media("a.jpg"))
        val f = write("duplicates.json", dups("x.jpg"))
        Index.load(ctx, drive, force = true)
        assertEquals(listOf("Photos/x.jpg", "Copie/x.jpg"), Index.duplicates().single().files)
        val t = f.lastModified()
        f.writeText(dups("y.jpg")); f.setLastModified(t)
        Index.load(ctx, drive, force = true)
        assertEquals(listOf("Photos/x.jpg", "Copie/x.jpg"), Index.duplicates().single().files)
        assertEquals("2026-11-01", Index.dupsBuiltAt)
    }
}
