package com.the151universe.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * (15/11): un file eliminato dal telefono e rimesso sul server con lo stesso nome restava nascosto finché la lista
 * del telefono non veniva rifatta tutta (fino a 30 giorni). Una cartella letta dal vivo DOPO l'eliminazione dice la verità.
 */
class CloudStateTest {
    private val top: File = Files.createTempDirectory("cloudstate-test").toFile()
    private val ctx = TestCtx(top)

    @Before fun pulito() = CloudState.forget(ctx)
    @After fun clean() { CloudState.forget(ctx); top.deleteRecursively() }

    @Test fun rimessoDopoLEliminazioneSiVede() {
        CloudState.deleted(ctx, listOf("Prove/video.mp4"))
        assertNull(CloudState.current("Prove/video.mp4"))
        CloudState.seenLive(ctx, listOf("Prove/video.mp4", "Prove/altro.md"), System.currentTimeMillis() + 1)
        assertEquals("Prove/video.mp4", CloudState.current("Prove/video.mp4"))
    }

    /** Una lettura cominciata PRIMA dell'eliminazione può ancora vedere il file vecchio: resta nascosto. */
    @Test fun letturaVecchiaNonLoRimette() {
        val prima = System.currentTimeMillis() - 60_000
        CloudState.deleted(ctx, listOf("Prove/video.mp4"))
        CloudState.seenLive(ctx, listOf("Prove/video.mp4"), prima)
        assertNull(CloudState.current("Prove/video.mp4"))
    }

    /** Un file che il server non ha più resta eliminato; gli altri eliminati non si toccano. */
    @Test fun soloQuelliCheCiSono() {
        CloudState.deleted(ctx, listOf("A/uno.jpg", "A/due.jpg"))
        CloudState.seenLive(ctx, listOf("A/tre.jpg", "A/due.jpg"), System.currentTimeMillis() + 1)
        assertNull(CloudState.current("A/uno.jpg"))
        assertEquals("A/due.jpg", CloudState.current("A/due.jpg"))
    }

    /** Rinominato dal telefono (video.mp4 → vecchio.mp4), poi un file nuovo col nome di prima: si vede col suo nome. */
    @Test fun rinominatoPoiRimessoSiVede() {
        CloudState.moved(ctx, "Prove/video.mp4", "Prove/vecchio.mp4")
        assertEquals("Prove/vecchio.mp4", CloudState.current("Prove/video.mp4"))
        CloudState.seenLive(ctx, listOf("Prove/video.mp4", "Prove/vecchio.mp4"), System.currentTimeMillis() + 1)
        assertEquals("Prove/video.mp4", CloudState.current("Prove/video.mp4"))
        assertEquals("Prove/vecchio.mp4", CloudState.current("Prove/vecchio.mp4"))
    }
}
