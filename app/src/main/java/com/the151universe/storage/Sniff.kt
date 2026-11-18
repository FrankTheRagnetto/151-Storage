package com.the151universe.storage

import java.io.File

/**
 * Foto o video lo dicono i primi byte, non il nome. Sul cloud vero (prova sul telefono 12/11) quattro file su 28 di
 * una cartella avevano il nome sbagliato: due video da 155 e 234 MB chiamati «.HEIC», due foto chiamate «.MOV»/«.MP4».
 * Fidandosi del nome, le loro miniature restavano nere. Puro Kotlin: si prova sul PC (SniffTest).
 */
object Sniff {
    const val HEAD = 32

    private val IMAGE_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis")

    /** Il tipo vero dai primi byte, o null se non si riconosce (allora vale il nome). */
    fun kind(h: ByteArray): String? {
        fun at(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        if (h.size >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()) return "image/jpeg"
        if (h.size >= 4 && h[0] == 0x89.toByte() && at(1, "PNG")) return "image/png"
        if (at(0, "GIF8")) return "image/gif"
        if (at(0, "RIFF") && at(8, "WEBP")) return "image/webp"
        if (at(0, "RIFF") && at(8, "AVI ")) return "video/x-msvideo"
        if (h.size >= 4 && h[0] == 0x1A.toByte() && h[1] == 0x45.toByte() && h[2] == 0xDF.toByte() && h[3] == 0xA3.toByte()) return "video/x-matroska"
        if (at(4, "ftyp")) {
            // marca principale e compatibili (dal byte 16, a gruppi di 4): una sola marca d'immagine basta
            val brands = (8 until minOf(h.size - 3, 64) step 4).filter { it != 12 }.map { String(h, it, 4, Charsets.ISO_8859_1) }
            return when {
                brands.firstOrNull() in IMAGE_BRANDS -> if (brands.first() == "avif" || brands.first() == "avis") "image/avif" else "image/heic"
                brands.firstOrNull() == "qt  " -> "video/quicktime"
                brands.firstOrNull()?.startsWith("3g") == true -> "video/3gpp"
                brands.any { it in IMAGE_BRANDS } && brands.none { it == "qt  " || it.startsWith("mp4") || it == "isom" } -> "image/heic"
                else -> "video/mp4"
            }
        }
        if (at(4, "moov") || at(4, "mdat") || at(4, "wide") || at(4, "pnot")) return "video/quicktime"
        return null
    }

    /** [declared] (dal nome), corretto solo quando i byte dicono l'altra famiglia (foto ↔ video). */
    fun fix(declared: String, head: ByteArray): String {
        val real = kind(head) ?: return declared
        val fam = real.substringBefore('/')
        val dec = declared.substringBefore('/')
        return if ((fam == "image" || fam == "video") && (dec == "image" || dec == "video") && fam != dec) real else declared
    }

    fun head(f: File): ByteArray = try { f.inputStream().use { i -> ByteArray(HEAD).let { b -> val n = i.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } } } catch (_: Exception) { ByteArray(0) }

    fun fixFile(declared: String, f: File): String = fix(declared, head(f))
}
