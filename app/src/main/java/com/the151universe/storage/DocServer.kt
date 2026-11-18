package com.the151universe.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * Serve il documento corrente alla WebView, SENZA rete: le richieste a
 * https://appassets.androidplatform.net/file/<token>/... vengono intercettate e
 * soddisfatte leggendo l'URI concesso (content://).
 *
 *  - /file/<token>/doc                 -> tutto il file in streaming (testo, SVG, PDF piccoli)
 *  - /file/<token>/range?start=&end=   -> byte [start, end) letti con pread (PDF grandi)
 *  - /file/<token>/rel?p=<percorso>    -> un file ACCANTO al documento (immagini del Markdown), se raggiungibile
 *  - https://[HTML_HOST]/<token>/…     -> pagina HTML vera (origine separata) e le sue risorse relative
 *
 * Il file non passa MAI intero dall'heap Java: niente OOM anche con PDF da centinaia di MB.
 * I range viaggiano nella query string e non nell'header Range, cosi' non dipendiamo da come
 * la WebView inoltra gli header delle richieste intercettate.
 */
class DocServer(
    private val ctx: Context,
    private val uri: Uri?,
    private val mime: String,
    private val inlineBytes: ByteArray? = null,
) {
    val token: String = UUID.randomUUID().toString().replace("-", "").take(16)
    val basePath: String get() = "/file/$token/"

    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null

    /** Dimensione vera secondo il file descriptor (-1 se pipe/ignota). */
    var fdSize: Long = -1
        private set
    /** true se possiamo leggere a salti (pread). Falso per pipe/stream di provider cloud. */
    var seekable: Boolean = false
        private set

    fun open() {
        if (uri == null) return
        try {
            val p = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
            pfd = p
            fdSize = p.statSize
            if (fdSize > 0) {
                val ch = FileInputStream(p.fileDescriptor).channel
                ch.position(0) // lancia su pipe/socket
                channel = ch
                seekable = true
            }
        } catch (_: Exception) {
            seekable = false
        }
    }

    fun close() {
        try { channel?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        channel = null
        pfd = null
    }

    fun handles(path: String?): Boolean = path != null && path.startsWith(basePath)

    fun serve(url: Uri): WebResourceResponse {
        val sub = url.path!!.removePrefix(basePath)
        return try {
            when (sub) {
                "doc" -> full(url.getQueryParameter("max")?.toLongOrNull())
                "rel" -> relative(url.getQueryParameter("p") ?: "")
                "range" -> range(
                    url.getQueryParameter("start")!!.toLong(),
                    url.getQueryParameter("end")!!.toLong(),
                )
                else -> error(404, "Not Found")
            }
        } catch (e: SecurityException) {
            error(403, "Forbidden")
        } catch (e: Exception) {
            error(500, "Error")
        }
    }

    // ------------------------------------------------------------ HTML su origine separata
    /** La pagina e le sue risorse stanno su https://HTML_HOST/<token>/<nome>: 'self' della CSP e' SOLO questo. */
    fun htmlUrl(name: String): String = "https://$HTML_HOST/$token/" + Uri.encode(name.ifBlank { "pagina.html" })

    fun handlesHtml(u: Uri): Boolean = u.host == HTML_HOST && (u.path ?: "").startsWith("/$token/")

    fun serveHtml(u: Uri, docName: String, scripts: Boolean): WebResourceResponse {
        val sub = (u.path ?: "").removePrefix("/$token/")
        return try {
            if (sub == docName.ifBlank { "pagina.html" }) {
                val raw = ctx.contentResolver.openInputStream(uri!!) ?: return error(500, "Error")
                val h = headers() + ("Content-Security-Policy" to htmlCsp(scripts))
                WebResourceResponse("text/html", null, 200, "OK", h, raw)
            } else relative(sub)
        } catch (e: SecurityException) {
            error(403, "Forbidden")
        } catch (e: Exception) {
            error(404, "Not Found")
        }
    }

    // ------------------------------------------------------------ file accanto al documento
    /**
     * Prova ad aprire [rel] (percorso relativo al documento). Funziona solo se il sistema ce lo
     * lascia fare: file:// leggibile, oppure un DocumentsProvider che ci ha dato l'albero. Con il
     * permesso su un solo file (il caso normale di "Apri con") il provider dice no: 404 e pazienza.
     */
    private fun relative(relRaw: String): WebResourceResponse {
        val u = uri ?: return error(404, "Not Found")
        val rel = relRaw.substringBefore('#').substringBefore('?').replace('\\', '/').trimStart('/')
        if (rel.isBlank() || rel.contains("://")) return error(404, "Not Found")
        val segs = rel.split('/').filter { it.isNotEmpty() && it != "." }
        val mime = mimeFor(rel)
        try {
            // file:// oppure un file della libreria (nostro FileProvider): percorso vero, accanto si legge
            val own = Storage.ownPath(ctx, u)
            if (u.scheme == "file" || own != null) {
                val base = File(own ?: u.path ?: return error(404, "Not Found")).parentFile ?: return error(404, "Not Found")
                val f = File(base, segs.joinToString("/")).canonicalFile
                if (!f.isFile || !f.canRead()) return error(404, "Not Found")
                return WebResourceResponse(mime, null, 200, "OK", headers(), f.inputStream())
            }
            if (DocumentsContract.isDocumentUri(ctx, u)) {
                // es. "primary:Documenti/nota.md" o "raw:/storage/emulated/0/Download/nota.md"
                val id = DocumentsContract.getDocumentId(u)
                val colon = id.indexOf(':')
                val root = if (colon >= 0) id.substring(0, colon + 1) else ""
                val path = id.substring(root.length)
                val lead = if (path.startsWith("/")) "/" else ""
                val parts = path.split('/').filter { it.isNotEmpty() }.dropLast(1).toMutableList()
                for (s in segs) {
                    if (s == "..") { if (parts.isEmpty()) return error(404, "Not Found"); parts.removeAt(parts.size - 1) } else parts += s
                }
                val newId = root + lead + parts.joinToString("/")
                val target = if (isTreeUri(u)) DocumentsContract.buildDocumentUriUsingTree(u, newId)
                else DocumentsContract.buildDocumentUri(u.authority, newId)
                val input = ctx.contentResolver.openInputStream(target) ?: return error(404, "Not Found")
                return WebResourceResponse(mime, null, 200, "OK", headers(), input)
            }
        } catch (_: SecurityException) {
            return error(404, "Not Found")
        } catch (_: Exception) {
            return error(404, "Not Found")
        }
        return error(404, "Not Found")
    }

    private fun isTreeUri(u: Uri): Boolean = u.pathSegments.size >= 2 && u.pathSegments[0] == "tree"

    private fun headers() = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")

    private fun full(max: Long?): WebResourceResponse {
        val raw: InputStream = inlineBytes?.let { ByteArrayInputStream(it) }
            ?: ctx.contentResolver.openInputStream(uri!!)
            ?: return error(500, "Error")
        val stream = if (max != null && max > 0) LimitedStream(raw, max) else raw
        return WebResourceResponse(mime, null, 200, "OK", headers(), stream)
    }

    private fun range(start: Long, end: Long): WebResourceResponse {
        val ch = channel ?: return error(416, "Range Not Satisfiable")
        require(start >= 0 && end > start && end - start <= 16L * 1024 * 1024)
        val len = (minOf(end, fdSize) - start).toInt().coerceAtLeast(0)
        val buf = ByteBuffer.allocate(len)
        var pos = start
        while (buf.hasRemaining()) {
            val r = ch.read(buf, pos) // pread: thread-safe, non sposta la posizione
            if (r < 0) break
            pos += r
        }
        return WebResourceResponse(
            "application/octet-stream", null, 200, "OK", headers(),
            ByteArrayInputStream(buf.array(), 0, buf.position()),
        )
    }

    private class LimitedStream(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = super.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    companion object {
        /** Origine delle pagine HTML aperte: diversa da appassets, cosi' gli script della pagina non vedono le nostre. */
        const val HTML_HOST = "pagina.androidplatform.net"

        fun htmlCsp(scripts: Boolean): String {
            val js = if (scripts) "'self' 'unsafe-inline' 'unsafe-eval' blob: data:" else "'none'"
            return "default-src 'self' data: blob:; script-src $js; style-src 'self' 'unsafe-inline' data:; " +
                "img-src 'self' data: blob:; font-src 'self' data: blob:; media-src 'self' data: blob:; frame-src 'self' data: blob:; " +
                "connect-src 'self'; object-src 'none'; form-action 'none'; base-uri 'self'"
        }

        private val EXTRA_MIME = mapOf(
            "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript", "svg" to "image/svg+xml",
            "json" to "application/json", "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "html" to "text/html", "htm" to "text/html", "webp" to "image/webp", "avif" to "image/avif",
        )

        fun mimeFor(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            return EXTRA_MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun error(code: Int, reason: String) = WebResourceResponse(
            "text/plain", "utf-8", code, reason, mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}
