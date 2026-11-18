package com.the151universe.storage

import android.net.Uri
import android.util.Log
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Cloud WebDAV (Nextcloud, ogni server WebDAV, …) con OkHttp (Apache-2.0):
 * HttpURLConnection di Android rifiuta PROPFIND/MKCOL/MOVE. Radice = la cartella base dell'utente.
 *
 * Regole: mai sopra un file che c'è; caricamento su un nome provvisorio nascosto
 * (`.nome.151part`, nascosto come ogni nome col punto), controllo della
 * dimensione, poi MOVE con `Overwrite: F`.
 */
class WebDav(baseUrl: String, private val user: String, private val password: String, override val label: String) : CloudDrive {

    private val base: String = baseUrl.trimEnd('/')
    private val basePath: String = (Uri.parse(base).path ?: "").trimEnd('/')
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val knownDirs = HashSet<String>()

    override val rootRef = "/"

    // ------------------------------------------------------------------ elenco

    override fun list(dirRef: String, dirPath: String): List<CloudEntry> {
        val dir = norm(dirRef, dir = true)
        val self = Uri.decode(dir).trim('/')
        val out = propfind(dir, "1")?.filter { it.path.trim('/') != self } ?: throw fail(404)
        knownDirs += dir
        return out.filterNot { it.name.startsWith('.') }
            .sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    private fun stat(ref: String): CloudEntry? = propfind(ref, "0")?.firstOrNull()
    override fun info(ref: String): CloudEntry? = stat(ref)

    /** null = 404. */
    private fun propfind(ref: String, depth: String): List<CloudEntry>? {
        val req = req(ref).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", depth).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (r.code != 207) throw fail(r.code)
            return r.body!!.byteStream().use { parse(it) }
        }
    }

    private fun simple(ref: String, method: String, extra: Map<String, String> = emptyMap()): Int {
        val b = req(ref).method(method, null)
        for ((k, v) in extra) b.header(k, v)
        http.newCall(b.build()).execute().use { return it.code }
    }

    override fun resolveDir(relPath: String, create: Boolean): String? {
        val segs = relPath.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        var cur = "/"
        for (s in segs) {
            val next = "$cur$s/"
            if (next !in knownDirs) {
                val st = stat(next)
                if (st == null) {
                    if (!create) return null
                    val code = simple(next, "MKCOL")
                    if (code != 201 && code != 405) throw fail(code) // 405 = c'è già
                } else if (!st.isDir) throw CloudException("«$s» in the cloud is a file, not a folder", retry = false)
                knownDirs += next
            }
            cur = next
        }
        return cur
    }

    // ------------------------------------------------------------------ caricamento

    // [mtime] non arriva lassù: il WebDAV qui non ha un modo standard per darla (la rete WebDAV non si cambia senza
    // una prova sul Samsung, scheda). La server dell'utente passa da SFTP, che la tiene ([SftpDrive.upload]).
    override fun upload(dirRef: String, name: String, size: Long, open: () -> InputStream, progress: (Long) -> Unit, cancelled: () -> Boolean, mtime: Long): UploadResult {
        val dir = norm(dirRef, dir = true)
        val existing = try { list(dir, "").filter { !it.isDir }.associateBy { it.name } } catch (e: CloudException) {
            return UploadResult.Failed(e.message ?: "error", e.retry)
        } catch (e: IOException) { return UploadResult.Failed(netMessage(e), true) }
        existing[name]?.let { if (it.size == size) return UploadResult.Skipped(name) }
        val finalName = freeName(name) { existing.containsKey(it) }
        val tmp = dir + enc(".$finalName.151part")
        var call: Call? = null
        try {
            var sent = 0L
            val stream = object : RequestBody() {
                override fun contentType() = OCTET
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    open().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastTick = 0L
                        while (true) {
                            if (cancelled()) throw IOException("annullato")
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            sent += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 250) { progress(sent); lastTick = now }
                        }
                    }
                }
            }
            call = http.newCall(req(tmp).put(stream).build())
            val code = call.execute().use { it.code }
            progress(sent)
            if (code !in 200..299) { deleteQuiet(tmp); throw fail(code) }
            if (size >= 0 && sent != size) { deleteQuiet(tmp); return UploadResult.Failed("The file changed while uploading: try again", true) }
            // il nome si guadagna: prima si controlla che il provvisorio sia intero, poi si rinomina
            val st = stat(tmp)
            if (st == null || st.size != sent) { deleteQuiet(tmp); return UploadResult.Failed("The cloud received an incomplete file: try again", true) }
            val mc = simple(tmp, "MOVE", mapOf("Destination" to base + dir + enc(finalName), "Overwrite" to "F"))
            if (mc == 412) { deleteQuiet(tmp); return UploadResult.Failed("A file with the same name appeared in the cloud: try again", true) }
            if (mc !in 200..299) { deleteQuiet(tmp); throw fail(mc) }
            return UploadResult.Done(finalName)
        } catch (e: CloudException) {
            return UploadResult.Failed(e.message ?: "error", e.retry)
        } catch (e: IOException) {
            deleteQuiet(tmp)
            if (cancelled()) return UploadResult.Cancelled
            return UploadResult.Failed(netMessage(e), true)
        }
    }

    private fun deleteQuiet(ref: String) {
        try { simple(ref, "DELETE") } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ lettura

    override fun open(entry: CloudEntry, start: Long, end: Long): InputStream {
        val b = req(entry.ref).get()
        if (start > 0 || end >= 0) b.header("Range", "bytes=$start-" + (if (end >= 0) end.toString() else ""))
        val r: Response = http.newCall(b.build()).execute()
        if (r.code != 200 && r.code != 206) { r.close(); throw fail(r.code) }
        val input = r.body!!.byteStream()
        // server che ignora il Range (200 invece di 206): si salta fino a [start], o arriverebbe l'inizio del file
        if (start > 0 && r.code == 200) {
            var left = start
            try {
                while (left > 0) { val n = input.skip(left); if (n <= 0) { if (input.read() < 0) break; left-- } else left -= n }
            } catch (x: IOException) { r.close(); throw x }
            if (left > 0) { r.close(); throw IOException("file shorter than expected") }
        }
        return object : FilterInputStream(input) {
            override fun close() { try { super.close() } finally { r.close() } }
        }
    }

    override fun rename(e: CloudEntry, newName: String): String {
        val clean = newName.trim().replace('/', '-')
        if (clean.isEmpty() || clean.startsWith('.')) throw CloudException("Not a valid name", retry = false)
        val parent = e.ref.trimEnd('/').substringBeforeLast('/') + "/"
        val dest = parent + enc(clean) + if (e.isDir) "/" else ""
        val code = simple(e.ref, "MOVE", mapOf("Destination" to base + dest, "Overwrite" to "F"))
        if (code == 412) throw CloudException("Something called «$clean» is already there", retry = false)
        if (code !in 200..299) throw fail(code)
        knownDirs.clear()
        return dest
    }

    override fun transfer(e: CloudEntry, destDir: String, copy: Boolean): String {
        val dir = resolveDir(destDir, create = true) ?: throw CloudException("Destination folder not found", retry = false)
        if (!copy && e.isDir && (dir.startsWith(e.ref))) throw CloudException("A folder can't go inside itself", retry = false)
        val taken = list(dir, destDir).map { it.name }.toSet()
        val name = freeName(e.name) { it in taken }
        val dest = dir + enc(name) + if (e.isDir) "/" else ""
        val code = simple(e.ref, if (copy) "COPY" else "MOVE", mapOf("Destination" to base + dest, "Overwrite" to "F", "Depth" to "infinity"))
        if (code !in 200..299) throw fail(code)
        knownDirs.clear()
        return dest
    }

    override fun readSmall(relPath: String): ByteArray? {
        val r = http.newCall(req(encPath(relPath).trimEnd('/')).get().build()).execute()
        r.use {
            if (it.code == 404) return null
            if (it.code != 200) throw fail(it.code)
            return it.body!!.bytes()
        }
    }

    override fun putSmall(relPath: String, bytes: ByteArray) {
        val r = http.newCall(req(encPath(relPath).trimEnd('/')).put(bytes.toRequestBody(OCTET)).build()).execute()
        r.use { if (it.code !in 200..299) throw fail(it.code) }
    }

    override fun moveTo(e: CloudEntry, destDir: String, name: String) {
        val dir = resolveDir(destDir, create = true) ?: throw CloudException("Destination folder not found", retry = false)
        val dest = dir + enc(name) + if (e.isDir) "/" else ""
        val code = simple(e.ref, "MOVE", mapOf("Destination" to base + dest, "Overwrite" to "F"))
        if (code == 412) throw CloudException("Something called «$name» is already there", retry = false)
        if (code !in 200..299) throw fail(code)
        knownDirs.clear()
    }

    override fun deletePath(relPath: String, isDir: Boolean) {
        val code = simple(encPath(relPath).let { if (isDir) it else it.trimEnd('/') }, "DELETE")
        if (code !in 200..299 && code != 404) throw fail(code)
        knownDirs.clear()
    }

    override fun streamUri(entry: CloudEntry): Pair<Uri, Map<String, String>> =
        Uri.parse(base + entry.ref) to mapOf("Authorization" to auth)

    override fun check(): String? = try {
        list(rootRef, "")
        null
    } catch (e: CloudException) {
        e.message
    } catch (e: IOException) {
        netMessage(e)
    }

    // ------------------------------------------------------------------ attrezzi

    private fun req(ref: String): Request.Builder = Request.Builder().url(base + ref)
        .header("Authorization", auth).header("User-Agent", "The151Storage/1")

    /** I percorsi del server sono "/Photos/2026/" (codificati, relativi alla radice dell'utente). */
    private fun norm(ref: String, dir: Boolean): String {
        var r = if (ref.startsWith("/")) ref else "/$ref"
        if (dir && !r.endsWith("/")) r += "/"
        return r
    }

    private fun parse(input: InputStream): List<CloudEntry> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(input, null)
        val out = ArrayList<CloudEntry>()
        var href: String? = null
        var isDir = false
        var size = -1L
        var mtime = 0L
        var mime: String? = null
        var okProps = true
        var text = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = null; isDir = false; size = -1; mtime = 0; mime = null }
                        "collection" -> if (okProps) isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name) {
                        "href" -> href = t
                        "getcontentlength" -> size = t.toLongOrNull() ?: -1
                        "getlastmodified" -> mtime = parseDate(t)
                        "getcontenttype" -> mime = t.ifEmpty { null }
                        "response" -> href?.let { h -> toEntry(h, isDir, size, mtime, mime)?.let { out += it } }
                    }
                    text = StringBuilder()
                }
            }
        }
        return out
    }

    private fun toEntry(href: String, isDir: Boolean, size: Long, mtime: Long, mime: String?): CloudEntry? {
        // href: "/Photos/2026/x%20y.jpg" o "https://host/Photos/…"; si tiene il percorso codificato, si toglie la base
        var raw = if (href.startsWith("http://") || href.startsWith("https://")) Uri.parse(href).encodedPath ?: return null else href
        if (basePath.isNotEmpty() && raw.startsWith(basePath)) raw = raw.removePrefix(basePath)
        if (!raw.startsWith("/")) raw = "/$raw"
        // il riferimento si riscrive SEMPRE nello stesso modo (Apache scrive %e2 e lascia le virgole, noi %E2 e %2C):
        // così una cartella aperta dai recenti, dall'indice o da un href è la stessa cartella
        val decoded = Uri.decode(raw.trimEnd('/'))
        val ref = if (decoded.isEmpty()) "/" else encPath(decoded).let { if (isDir) it else it.trimEnd('/') }
        val name = decoded.substringAfterLast('/')
        return CloudEntry(ref, name.ifEmpty { "/" }, isDir, if (isDir) -1 else size, mtime, mime, decoded.trimStart('/'))
    }

    private fun fail(code: Int): CloudException {
        Log.w("151Storage", "WebDAV $code")
        return when (code) {
            401 -> CloudException("Wrong username or password", retry = false)
            403 -> CloudException("The cloud doesn't allow this (403)", retry = false)
            404 -> CloudException("It's no longer in the cloud (404)", retry = false)
            405 -> CloudException("The server doesn't speak WebDAV: is it turned on in the cloud's settings? (405)", retry = false)
            423 -> CloudException("The file in the cloud is locked by another program (423)")
            507 -> CloudException("The cloud is full (507)", retry = false)
            in 500..599 -> CloudException("The cloud has a problem right now ($code)")
            else -> CloudException("The cloud answered $code")
        }
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        /** Un client solo per tutta l'app (pool di connessioni condiviso), niente redirect verso altri siti. */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        private val PROPFIND_BODY = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/></d:prop></d:propfind>").toByteArray()

        /** Un pezzo di percorso, codificato per l'URL (spazi, accenti, «☉»…). */
        fun enc(segment: String): String = Uri.encode(segment)

        /** Un percorso leggibile "Photos/2026/10 - October" → "/Photos/2026/10%20-%20October/". */
        fun encPath(rel: String): String {
            val segs = rel.split('/').filter { it.isNotEmpty() }
            return if (segs.isEmpty()) "/" else "/" + segs.joinToString("/") { enc(it) } + "/"
        }

        fun netMessage(e: Exception): String = when (e) {
            is UnknownHostException -> "No connection, or wrong cloud address."
            is SocketTimeoutException -> "The cloud isn't answering: slow connection?"
            is SSLException -> "Insecure connection: check the address."
            is java.net.ConnectException -> "The cloud isn't answering: check the address and the connection."
            else -> "Connection interrupted: try again."
        }

        private fun parseDate(s: String): Long = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)?.time ?: 0
        } catch (_: Exception) { 0 }
    }
}
