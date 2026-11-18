package com.the151universe.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Foto o video dai primi byte (prova sul telefono 12/11): sul cloud c'erano video chiamati «.HEIC» e foto chiamate
 * «.MOV»/«.MP4», con la miniatura nera. Testate fatte qui, nessun file dell'utente.
 */
class SniffTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private fun box(type: String, vararg brands: String): ByteArray {
        val s = StringBuilder("\u0000\u0000\u0000 ").append(type)
        brands.forEachIndexed { i, br -> s.append(br); if (i == 0) s.append("\u0000\u0000\u0000\u0000") }
        return s.toString().toByteArray(Charsets.ISO_8859_1)
    }
    private val jpeg = b(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J'.code, 'F'.code, 'I'.code, 'F'.code)
    private val mov = box("ftyp", "qt  ", "qt  ")
    private val heic = box("ftyp", "heic", "mif1", "heic")
    private val mp4 = box("ftyp", "isom", "isom", "iso2", "avc1", "mp41")

    @Test fun unaFotoChiamataMovEUnaFoto() = assertEquals("image/jpeg", Sniff.fix("video/quicktime", jpeg))
    @Test fun unaFotoChiamataMp4EUnaFoto() = assertEquals("image/jpeg", Sniff.fix("video/mp4", jpeg))
    @Test fun unVideoChiamatoHeicEUnVideo() = assertEquals("video/quicktime", Sniff.fix("image/heic", mov))
    @Test fun unVideoMp4ChiamatoJpgEUnVideo() = assertEquals("video/mp4", Sniff.fix("image/jpeg", mp4))

    // chi ha già il nome giusto non cambia
    @Test fun heicVeraRestaHeic() = assertEquals("image/heic", Sniff.fix("image/heic", heic))
    @Test fun movVeroResta() = assertEquals("video/quicktime", Sniff.fix("video/quicktime", mov))
    @Test fun jpgVeraResta() = assertEquals("image/jpeg", Sniff.fix("image/jpeg", jpeg))
    @Test fun pngChiamataJpgResta() = assertEquals("image/jpeg", Sniff.fix("image/jpeg", b(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10)))
    @Test fun heifConMif1Principale() = assertEquals("image/heic", Sniff.kind(box("ftyp", "mif1", "mif1", "heic")))
    @Test fun quickTimeVecchioSenzaFtyp() = assertEquals("video/quicktime", Sniff.kind(box("moov")))

    // byte che non si riconoscono, o file vuoto: vale il nome
    @Test fun sconosciutoValeIlNome() { assertNull(Sniff.kind(b(1, 2, 3, 4, 5, 6, 7, 8))); assertEquals("video/mp4", Sniff.fix("video/mp4", ByteArray(0))) }
    @Test fun documentoNonDiventaFoto() = assertEquals("application/pdf", Sniff.fix("application/pdf", jpeg))
}
