package com.the151universe.storage

import android.net.Uri
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Un cloud SFTP (SSH) con una chiave. Nessuna password. La chiave è SOLO del telefono (una riga in
 * `.ssh/authorized_keys` del server): toglierla lì = telefono fuori, gli altri apparecchi no.
 *
 * Un collegamento solo (una Session) e dentro più «canali» SFTP insieme: per un server che limita i collegamenti
 * contemporanei è UN collegamento solo. I riferimenti sono quelli di [WebDav] (`WebDav.encPath`), così tutto il resto
 * dell'app (indici, preferiti, anteprime per percorso) non cambia. [base] = cartella di partenza ("" = la radice).
 */
class SftpDrive(
    private val host: String, private val port: Int, private val user: String, private val base: String,
    private val keyPem: String, private val knownHosts: String, override val label: String,
) : CloudDrive {
    override val rootRef = "/"

    private val lock = Any()
    @Volatile private var session: Session? = null
    private val idle = LinkedBlockingQueue<ChannelSftp>()

    /** Chiude il collegamento (i collegamenti in più dei caricamenti, a fine giro). */
    fun shutdown() = synchronized(lock) {
        while (true) { val c = idle.poll() ?: break; try { c.disconnect() } catch (_: Exception) {} }
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }
    private val gate = java.util.concurrent.Semaphore(MAX_CHANNELS, true)

    private fun session(): Session = synchronized(lock) {
        session?.takeIf { it.isConnected }?.let { return it }
        // i canali della sessione caduta si chiudono davvero (non solo si dimenticano)
        while (true) { val c = idle.poll() ?: break; try { c.disconnect() } catch (_: Exception) {} }
        val jsch = JSch()
        jsch.addIdentity("the151-phone-storage", keyPem.toByteArray(), null, null)
        jsch.setKnownHosts(ByteArrayInputStream(knownHosts.toByteArray()))
        val s = jsch.getSession(user, host, port)
        s.setConfig("StrictHostKeyChecking", "yes") // solo il server vera: la sua impronta viaggia con la chiave
        s.setConfig("PreferredAuthentications", "publickey")
        // algoritmi che il Java di Android ha di sicuro, anche su Android 8 (niente curve25519/ed25519)
        s.setConfig("server_host_key", "ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
        s.setConfig("kex", "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512")
        s.setConfig("PubkeyAcceptedAlgorithms", "ecdsa-sha2-nistp256")
        s.setServerAliveInterval(20_000)
        s.connect(20_000)
        session = s
        return s
    }

    /** Un canale SFTP per [block]; torna nel mucchio se tutto è andato bene. */
    private inline fun <T> sftp(block: (ChannelSftp) -> T): T {
        gate.acquire()
        var ch: ChannelSftp? = null
        try {
            ch = borrow()
            val r = block(ch)
            idle.offer(ch); ch = null
            return r
        } catch (e: SftpException) {
            if (ch != null && ch.isConnected) { idle.offer(ch); ch = null }
            throw mapError(e)
        } catch (e: com.jcraft.jsch.JSchException) {
            throw IOException(e.message ?: "connection failed", e)
        } finally {
            ch?.let { try { it.disconnect() } catch (_: Exception) {} }
            gate.release()
        }
    }

    private fun borrow(): ChannelSftp {
        while (true) {
            val c = idle.poll() ?: break
            if (c.isConnected && c.session.isConnected) return c
        }
        val s = session()
        // un canale che non riesce a collegarsi va chiuso: se no la libreria lo tiene nella sua lista per sempre
        val ch = s.openChannel("sftp") as ChannelSftp
        try { ch.connect(15_000) } catch (e: Exception) { try { ch.disconnect() } catch (_: Exception) {}; throw e }
        return ch
    }

    private fun mapError(e: SftpException): Exception = when (e.id) {
        ChannelSftp.SSH_FX_NO_SUCH_FILE -> CloudException("Not found in the cloud", retry = false)
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> CloudException("The cloud doesn't allow this", retry = false)
        else -> IOException(e.message ?: "cloud error ${e.id}", e)
    }

    private fun rel(ref: String) = Uri.decode(ref).trim('/')
    private fun abs(relPath: String): String {
        val r = relPath.trim('/')
        return when { base.isEmpty() && r.isEmpty() -> "."; base.isEmpty() -> r; r.isEmpty() -> base; else -> "$base/$r" }
    }
    private fun entry(relPath: String, a: SftpATTRS): CloudEntry {
        val r = relPath.trim('/')
        val ref = if (r.isEmpty()) "/" else WebDav.encPath(r).let { if (a.isDir) it else it.trimEnd('/') }
        return CloudEntry(ref, r.substringAfterLast('/').ifEmpty { "/" }, a.isDir, if (a.isDir) -1 else a.size, a.mTime * 1000L, null, r)
    }
    private fun statOrNull(ch: ChannelSftp, p: String): SftpATTRS? =
        try { ch.stat(p) } catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e }

    // ------------------------------------------------------------------ elenco

    override fun list(dirRef: String, dirPath: String): List<CloudEntry> {
        val r = rel(dirRef)
        return sftp { ch ->
            @Suppress("UNCHECKED_CAST")
            (ch.ls(abs(r)) as java.util.Vector<ChannelSftp.LsEntry>)
                .filter { !it.filename.startsWith('.') }
                .map { entry(if (r.isEmpty()) it.filename else "$r/${it.filename}", it.attrs) }
        }.sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    // ------------------------------------------------------------------ comandi del server (una richiesta, niente download)

    /** Un comando del server (`ls`, `md5sum`: la Storage server li ha nella sua shell ristretta) → uscita e codice. */
    private fun exec(cmd: String, maxBytes: Int): Pair<ByteArray, Int> {
        gate.acquire()
        try {
            val ch = session().openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(cmd)
            ch.inputStream.let { input ->
                ch.connect(20_000)
                val out = ByteArrayOutputStream(1 shl 20)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) { ch.disconnect(); throw IOException("answer too big") }
                }
                while (!ch.isClosed) Thread.sleep(20)
                val code = ch.exitStatus
                ch.disconnect()
                return out.toByteArray() to code
            }
        } catch (e: com.jcraft.jsch.JSchException) {
            throw IOException(e.message ?: "connection failed", e)
        } finally { gate.release() }
    }

    /** `'…'` per la shell del server (anche «2000's» con l'apice). */
    private fun quote(p: String) = "'" + p.replace("'", "'\\''") + "'"

    /**
     * Tutto l'albero in un colpo: `ls -lRa --time-style=+%s` (un cloud vero: 84.000 righe in 1,9 s, contro 3.100
     * richieste una per cartella). Righe: permessi, link, utente, gruppo, byte, data, nome.
     */
    override fun listAll(relRoot: String): TreeListing? {
        val r = relRoot.trim('/')
        val (bytes, code) = exec("ls -lRa --time-style=+%s " + quote(abs(r)), 200 shl 20)
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) return null
        val out = ArrayList<CloudEntry>(80_000)
        val root = abs(r).trimEnd('/')
        val line = Regex("""^([dl-])\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d+)\s(.*)$""")
        var dir: String? = null
        var hiddenDir = false
        var prevBlank = true
        for (l in text.split('\n')) {
            if (l.isEmpty()) { prevBlank = true; continue }
            if (prevBlank && l.endsWith(":") && !l.startsWith("total ")) {
                // «/base/Photos/2026:» → percorso relativo alla radice del cloud dell'app
                // l'intestazione ripete quello che abbiamo chiesto ([root]) più la sottocartella
                val d = l.dropLast(1).trimEnd('/')
                val sub = when { d == root -> ""; d.startsWith("$root/") -> d.removePrefix("$root/"); else -> null }
                dir = sub?.let { listOf(r, it).filter { x -> x.isNotEmpty() }.joinToString("/") }
                hiddenDir = dir?.split('/')?.any { it.startsWith('.') } == true
                prevBlank = false
                continue
            }
            prevBlank = false
            val m = line.find(l) ?: continue
            val d = dir ?: continue
            if (hiddenDir) continue
            val (kind, size, time, name) = m.destructured
            if (kind == "l" || name == "." || name == ".." || name.startsWith('.')) continue
            val p = if (d.isEmpty()) name else "$d/$name"
            val isDir = kind == "d"
            val ref = WebDav.encPath(p).let { if (isDir) it else it.trimEnd('/') }
            out += CloudEntry(ref, name, isDir, if (isDir) -1 else size.toLong(), time.toLong() * 1000L, null, p)
        }
        return TreeListing(out, code == 0)
    }

    override fun md5(relPaths: List<String>): Map<String, String>? {
        val out = HashMap<String, String>()
        for (chunk in relPaths.filter { '\n' !in it }.chunked(40)) {
            val byAbs = chunk.associateBy { abs(it.trim('/')) }
            val (bytes, _) = exec("md5sum " + byAbs.keys.joinToString(" ") { quote(it) }, 1 shl 20)
            for (l in String(bytes, Charsets.UTF_8).split('\n')) {
                val m = Regex("""^([0-9a-f]{32})  (.*)$""").find(l) ?: continue
                byAbs[m.groupValues[2]]?.let { out[it] = m.groupValues[1] }
            }
        }
        return out
    }

    override fun info(ref: String): CloudEntry? {
        val r = rel(ref)
        return sftp { ch -> statOrNull(ch, abs(r))?.let { entry(r, it) } }
    }

    override fun resolveDir(relPath: String, create: Boolean): String? {
        val segs = relPath.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        return sftp { ch ->
            var cur = ""
            for (s in segs) {
                cur = if (cur.isEmpty()) s else "$cur/$s"
                val st = statOrNull(ch, abs(cur))
                if (st == null) {
                    if (!create) return@sftp null
                    // due lavoratori possono crearla insieme: se l'altro è arrivato prima, va bene così
                    try { ch.mkdir(abs(cur)) } catch (e: SftpException) { if (statOrNull(ch, abs(cur))?.isDir != true) throw e }
                }
                else if (!st.isDir) throw CloudException("«$s» in the cloud is a file, not a folder", retry = false)
            }
            if (cur.isEmpty()) "/" else WebDav.encPath(cur)
        }
    }

    // ------------------------------------------------------------------ caricamento (mai sopra: provvisorio, controllo, rinomina)

    override fun upload(dirRef: String, name: String, size: Long, open: () -> InputStream, progress: (Long) -> Unit, cancelled: () -> Boolean, mtime: Long): UploadResult {
        val dir = rel(dirRef)
        return try {
            val p = { n: String -> abs(if (dir.isEmpty()) n else "$dir/$n") }
            // si chiede del nome solo, non la cartella intera: rileggerla a ogni file rendeva lente le cartelle grandi
            val finalName = sftp { ch ->
                val st = statOrNull(ch, p(name))
                if (st != null && !st.isDir && st.size == size) null else freeName(name) { statOrNull(ch, p(it)) != null }
            } ?: return UploadResult.Skipped(name)
            val tmp = p(".$finalName.151part")
            var sent = 0L
            try {
                sftp { ch ->
                    ch.put(tmp, ChannelSftp.OVERWRITE).use { out ->
                        open().use { input ->
                            val buf = ByteArray(256 * 1024); var tick = 0L
                            while (true) {
                                if (cancelled()) throw IOException("annullato")
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); sent += n
                                val now = System.currentTimeMillis(); if (now - tick > 250) { progress(sent); tick = now }
                            }
                        }
                    }
                    progress(sent)
                    val st = statOrNull(ch, tmp)
                    if ((size >= 0 && sent != size) || st == null || st.size != sent) { try { ch.rm(tmp) } catch (_: Exception) {}; return@sftp false }
                    if (statOrNull(ch, p(finalName)) != null) { try { ch.rm(tmp) } catch (_: Exception) {}; throw CloudException("A file with the same name appeared in the cloud: try again") }
                    // la data del file del telefono, non «oggi»;
                    // sul provvisorio, prima della rinomina: il file col nome buono nasce già con la sua data
                    if (mtime > 0) try { ch.setMtime(tmp, (mtime / 1000).toInt()) } catch (x: SftpException) { Log.w("151Storage", "data del file non tenuta: ${x.message}") }
                    ch.rename(tmp, p(finalName)); true
                }.let { ok -> if (!ok) return UploadResult.Failed("The cloud received an incomplete file: try again", true) }
            } catch (e: IOException) {
                try { sftp { it.rm(tmp) } } catch (_: Exception) {}
                if (cancelled()) return UploadResult.Cancelled
                throw e
            }
            UploadResult.Done(finalName)
        } catch (e: CloudException) { UploadResult.Failed(e.message ?: "error", e.retry)
        } catch (e: IOException) {
            // a chi guarda la lista, parole sue e non «java.net.ConnectException: failed to connect to /192.168…» (09/11)
            val net = generateSequence(e as Throwable) { it.cause }.firstOrNull { it is java.net.ConnectException || it is java.net.UnknownHostException || it is java.net.SocketTimeoutException }
            UploadResult.Failed(WebDav.netMessage((net ?: e) as Exception), true)
        }
    }

    // ------------------------------------------------------------------ lettura

    override fun open(entry: CloudEntry, start: Long, end: Long): InputStream {
        gate.acquire()
        val ch = try { borrow() } catch (e: Exception) { gate.release(); throw IOException(e.message ?: "connection failed", e) }
        val raw = try { ch.get(abs(rel(entry.ref)), null, start) } catch (e: SftpException) {
            idle.offer(ch); gate.release(); throw mapError(e)
        }
        val limit = if (end >= 0) end - start + 1 else Long.MAX_VALUE
        return object : FilterInputStream(raw) {
            var left = limit; var closed = false
            override fun read(): Int { if (left <= 0) return -1; val b = super.read(); if (b >= 0) left--; return b }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = super.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
            }
            override fun close() {
                if (closed) return; closed = true
                // un file letto a metà lascia il canale sporco: si chiude, non torna nel mucchio
                try { super.close() } catch (_: Exception) {}
                try { ch.disconnect() } catch (_: Exception) {}
                gate.release()
            }
        }
    }

    /** I video si guardano mentre arrivano: un piccolo server sul telefono (solo 127.0.0.1) li legge a pezzi da qui. */
    override fun streamUri(entry: CloudEntry): Pair<Uri, Map<String, String>> = LocalStream.uriFor(this, entry) to emptyMap()

    override fun check(): String? = try { list(rootRef, ""); null } catch (e: Exception) { e.message ?: "connection failed" }

    override fun readSmall(relPath: String): ByteArray? = sftp { ch ->
        val p = abs(relPath)
        if (statOrNull(ch, p) == null) return@sftp null
        ByteArrayOutputStream().also { out -> ch.get(p, out) }.toByteArray()
    }

    override fun putSmall(relPath: String, bytes: ByteArray) = sftp { ch ->
        val p = abs(relPath); val parent = p.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && statOrNull(ch, parent) == null) {
            var cur = ""; for (s in parent.split('/')) { cur = if (cur.isEmpty()) s else "$cur/$s"; if (statOrNull(ch, cur) == null) ch.mkdir(cur) }
        }
        ch.put(ByteArrayInputStream(bytes), p, ChannelSftp.OVERWRITE)
    }

    // ------------------------------------------------------------------ rinomina, sposta, copia, cancella

    override fun rename(e: CloudEntry, newName: String): String {
        val clean = newName.trim().replace('/', '-')
        if (clean.isEmpty() || clean.startsWith('.')) throw CloudException("Not a valid name", retry = false)
        val r = rel(e.ref); val parent = r.substringBeforeLast('/', "")
        val dest = if (parent.isEmpty()) clean else "$parent/$clean"
        sftp { ch ->
            if (statOrNull(ch, abs(dest)) != null) throw CloudException("Something called «$clean» is already there", retry = false)
            ch.rename(abs(r), abs(dest))
        }
        return WebDav.encPath(dest).let { if (e.isDir) it else it.trimEnd('/') }
    }

    override fun transfer(e: CloudEntry, destDir: String, copy: Boolean): String {
        resolveDir(destDir, create = true) ?: throw CloudException("Destination folder not found", retry = false)
        val d = destDir.trim('/'); val src = rel(e.ref)
        if (!copy && e.isDir && (d == src || d.startsWith("$src/"))) throw CloudException("A folder can't go inside itself", retry = false)
        val taken = list(WebDav.encPath(d), d).map { it.name }.toSet()
        val name = freeName(e.name) { it in taken }
        val dest = if (d.isEmpty()) name else "$d/$name"
        if (!copy) { sftp { it.rename(abs(src), abs(dest)) } }
        else if (!e.isDir) {
            // copia: provvisorio nascosto, dimensione controllata, la data del file di partenza (mai «oggi»), rinomina
            val from = sftp { statOrNull(it, abs(src)) } ?: throw CloudException("Not found in the cloud", retry = false)
            val tmp = abs(if (d.isEmpty()) ".$name.151part" else "$d/.$name.151part")
            try {
                open(e).use { input -> sftp { it.put(input, tmp, ChannelSftp.OVERWRITE) } }
                sftp { ch ->
                    val st = statOrNull(ch, tmp)
                    if (st == null || st.size != from.size) throw CloudException("The copy arrived incomplete: try again")
                    try { ch.setMtime(tmp, from.mTime) } catch (x: SftpException) { Log.w("151Storage", "data della copia non tenuta: ${x.message}") }
                    ch.rename(tmp, abs(dest))
                }
            } catch (x: Exception) { try { sftp { it.rm(tmp) } } catch (_: Exception) {}; throw x }
        }
        else throw CloudException("Copying a whole folder isn't possible on this cloud yet", retry = false)
        return WebDav.encPath(dest).let { if (e.isDir) it else it.trimEnd('/') }
    }

    override fun moveTo(e: CloudEntry, destDir: String, name: String) {
        resolveDir(destDir, create = true) ?: throw CloudException("Destination folder not found", retry = false)
        val d = destDir.trim('/'); val dest = if (d.isEmpty()) name else "$d/$name"
        sftp { ch ->
            if (statOrNull(ch, abs(dest)) != null) throw CloudException("Something called «$name» is already there", retry = false)
            ch.rename(abs(rel(e.ref)), abs(dest))
        }
    }

    override fun deletePath(relPath: String, isDir: Boolean) {
        val p = abs(relPath)
        // una cartella intera con UN comando del server: file per file erano 2.400 andate e ritorno (~2,5 min, 08/11);
        // se il server non lo fa (o resta qualcosa), si torna al giro file per file
        val segs = relPath.trim('/').split('/')
        // muro: mai la radice, mai «.»/«..», sempre almeno cartella-del-cestino/voce
        val safe = segs.size >= 2 && segs.none { it.isEmpty() || it == "." || it == ".." } && '\n' !in p
        if (isDir && !safe) throw CloudException("This folder can't be deleted from here", retry = false)
        if (isDir) {
            try { exec("rm -rf " + quote(p), 1 shl 16) } catch (e: IOException) { Log.w("151Storage", "rm -rf non riuscito: ${e.message}") }
            if (sftp { ch -> statOrNull(ch, p) } == null) return
        }
        sftp { ch -> rmTree(ch, p, isDir) }
    }

    private fun rmTree(ch: ChannelSftp, p: String, dir: Boolean) {
        if (!dir) { try { ch.rm(p) } catch (e: SftpException) { if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e }; return }
        @Suppress("UNCHECKED_CAST")
        val kids = try { ch.ls(p) as java.util.Vector<ChannelSftp.LsEntry> } catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return else throw e }
        for (k in kids) if (k.filename != "." && k.filename != "..") rmTree(ch, "$p/${k.filename}", k.attrs.isDir)
        ch.rmdir(p)
    }

    companion object {
        /** Canali SFTP aperti insieme dentro l'unico collegamento (lo schermo, il giro, il visore). */
        const val MAX_CHANNELS = 8
        init { JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun isEnabled(level: Int) = level >= com.jcraft.jsch.Logger.WARN
            override fun log(level: Int, message: String) { Log.w("151Storage", "sftp: $message") } // mai chiavi: JSch non le scrive
        }) }
    }
}
