package com.the151universe.storage

import android.net.Uri
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per i cloud che non parlano http (SFTP, un cloud vero con la chiave): il lettore video di Android vuole un indirizzo.
 * Un server piccolissimo SOLO su 127.0.0.1 (nessun'altra app di rete, nessun altro telefono lo raggiunge), con un
 * nome a caso per ogni video, che risponde alle richieste «dammi da qui a qui» leggendo quel pezzo dal cloud.
 */
object LocalStream {
    private data class Item(val drive: CloudDrive, val entry: CloudEntry)
    private val items = ConcurrentHashMap<String, Item>()
    @Volatile private var server: ServerSocket? = null

    fun uriFor(drive: CloudDrive, e: CloudEntry): Uri {
        val s = start()
        val id = UUID.randomUUID().toString()
        items[id] = Item(drive, e)
        return Uri.parse("http://127.0.0.1:${s.localPort}/$id/${Uri.encode(e.name)}")
    }

    @Synchronized private fun start(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread({ serve(c) }, "151-video").start()
            }
        }, "151-video-server").apply { isDaemon = true }.start()
        return s
    }

    private fun serve(c: Socket) = c.use { sock ->
        try {
            val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = inp.readLine() ?: return
            var range: String? = null
            while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
            val id = first.split(' ').getOrNull(1)?.trim('/')?.substringBefore('/') ?: return
            val it = items[id]
            val out = sock.getOutputStream()
            if (it == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
            val size = it.entry.size.takeIf { s -> s > 0 } ?: it.drive.info(it.entry.ref)?.size ?: -1L
            var start = 0L; var end = size - 1
            val m = range?.let { r -> Regex("bytes=(\\d*)-(\\d*)").find(r) }
            if (m != null && size > 0) {
                val a = m.groupValues[1]; val b = m.groupValues[2]
                if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
                else if (b.isNotEmpty()) start = maxOf(0, size - b.toLong())
            }
            val len = end - start + 1
            val head = StringBuilder()
            head.append(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ").append(Thumbs.mimeOf(it.entry.name)).append("\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (size > 0) head.append("Content-Length: ").append(len).append("\r\n")
            if (m != null && size > 0) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (first.startsWith("HEAD")) return
            it.drive.open(it.entry, start, if (size > 0) end else -1).use { src ->
                val buf = ByteArray(128 * 1024)
                while (true) { val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            out.flush()
        } catch (e: Exception) {
            // il lettore chiude spesso a metà (salta avanti): non è un errore
            if (e !is java.net.SocketException) Log.w("151Storage", "video locale: ${e.message}")
        }
    }
}
