package com.the151universe.storage

import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Quando è stata scattata una foto o girato un video: una regola semplice.
 * Si raccolgono tutte le date (EXIF, video, nome del file, data di MediaStore), si buttano quelle
 * prima del 1995 e quelle oltre domani; vince il GIORNO più vecchio, e dentro quel giorno la prima
 * data con un'ora vera (un nome «IMG-20260412-WA0003» porta solo il giorno, a mezzanotte finta).
 * Un nome «-WA» è il giorno in cui il file è ARRIVATO: per questo vince il più vecchio.
 * Niente fusi: l'ora locale del telefono, mai UTC (il giorno cambierebbe a mezzanotte).
 */
object ShotDate {
    private val MIN = Calendar.getInstance().apply { set(1995, 0, 1, 0, 0, 0) }.timeInMillis

    /** I mesi delle cartelle sul server sono in inglese, scritti a mano, mai la lingua del telefono. */
    val MONTHS_EN = arrayOf("January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December")

    private data class Cand(val t: Long, val hasTime: Boolean)

    fun of(path: String?, name: String, mime: String?, mediaStoreTaken: Long, mtime: Long): Long {
        val c = ArrayList<Cand>()
        if (path != null) {
            if (mime?.startsWith("video/") == true) video(path)?.let { c += Cand(it, true) }
            else exif(path)?.let { c += Cand(it, true) }
        }
        fromName(name)?.let { c += it }
        if (mediaStoreTaken > 0) c += Cand(mediaStoreTaken, true)
        val max = System.currentTimeMillis() + 86_400_000L
        val ok = c.filter { it.t in MIN..max }
        if (ok.isEmpty()) return if (mtime in MIN..max) mtime else System.currentTimeMillis()
        val day = ok.minOf { dayKey(it.t) }
        val sameDay = ok.filter { dayKey(it.t) == day }
        return (sameDay.firstOrNull { it.hasTime } ?: sameDay.first()).t
    }

    /** "Photos/{YYYY}/{MM} {YYYY}" → "Photos/2026/10 2026" ({Month} = "October"). */
    fun fill(template: String, t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        return template.replace("{YYYY}", y.toString())
            .replace("{MM}", String.format(Locale.ROOT, "%02d", m + 1))
            .replace("{Month}", MONTHS_EN[m])
    }

    private fun dayKey(t: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }

    private fun exif(path: String): Long? = try {
        val e = ExifInterface(path)
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
        listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME)
            .mapNotNull { tag -> e.getAttribute(tag)?.takeIf { it.length >= 19 && !it.startsWith("0000") }?.let { fmt.parse(it.take(19))?.time } }
            .minOrNull()
    } catch (_: Exception) { null }

    /**
     * Il video: prima la data che l'iPhone scrive COL SUO FUSO («2024-05-31T18:58:29-0400»): l'ora
     * dell'orologio di dove si girava, presa come ora del telefono (come l'EXIF delle foto), così un video del 31/05
     * negli Stati Uniti va nel maggio e non nel giugno italiano. Se manca: la data in UTC portata all'ora locale.
     */
    private fun video(path: String): Long? = qtWall(path) ?: videoUtc(path)

    private val QT = Regex("""(20\d{2}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})[+-]\d{2}:?\d{2}""")
    private fun qtWall(path: String): Long? = try {
        java.io.RandomAccessFile(path, "r").use { r ->
            val n = r.length()
            fun part(from: Long, size: Int): String { val b = ByteArray(minOf(size.toLong(), n - from).toInt().coerceAtLeast(0)); r.seek(from); r.readFully(b); return String(b, Charsets.ISO_8859_1) }
            val m = QT.find(part(0, 1 shl 20)) ?: if (n > (1 shl 20)) QT.find(part(maxOf(1L shl 20, n - (4 shl 20)), 4 shl 20)) else null
            m?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(it.groupValues[1] + " " + it.groupValues[2])?.time }
        }
    } catch (_: Exception) { null }

    /** Il video dice la data in UTC ("20261007T101010.000Z"): si porta all'ora locale. */
    private fun videoUtc(path: String): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            fmt.parse(s.take(15))?.time?.takeIf { it > 0 }
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    // IMG_20261007_101010, PXL_20261007_101010123, VID-20261007-WA0001, Screenshot_20261007-101010,
    // "2026-10-07 10.10.10", signal-2026-10-07-101010
    private val NAME = Regex("""(?<![0-9])((?:19[89]|20[0-4])[0-9])[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12][0-9]|3[01])(?:[-_ T.]?([01][0-9]|2[0-3])[-_.:]?([0-5][0-9])[-_.:]?([0-5][0-9]))?""")

    private fun fromName(name: String): Cand? {
        // «20240531_225829000_iOS.MOV»: nome in ora inglese, all'ora del telefono
        CloudList.iosNameTime(name)?.let { return Cand(it, true) }
        val m = NAME.find(name) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance()
        cal.clear()
        val hasTime = g[4].isNotEmpty()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
            if (hasTime) g[4].toInt() else 0, if (hasTime) g[5].toInt() else 0, if (hasTime) g[6].toInt() else 0)
        return Cand(cal.timeInMillis, hasTime)
    }
}
