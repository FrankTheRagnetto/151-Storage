package com.the151universe.storage

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Il cloud, intercambiabile: un server WebDAV (Nextcloud e ogni servizio WebDAV), un server SFTP, oppure
 * Proton Drive, che non ha un'API pubblica e passa dall'app ufficiale (vedi [Clouds.KIND_PROTON]).
 * Il resto dell'app parla SOLO con [CloudDrive]: chi sta dietro non conta.
 *
 * Riferimenti: una cartella o un file si indicano con un [CloudEntry.ref] opaco (per WebDAV il percorso). I percorsi «leggibili»
 * ("Photos/2026/10") servono solo per scegliere dove caricare.
 */
data class CloudEntry(
    val ref: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val mime: String?,
    /** Percorso leggibile dalla radice ("Documents/x.pdf"), per i messaggi e i segni «già caricato». */
    val path: String,
)

sealed class UploadResult {
    /** Arrivato e controllato (la dimensione sul cloud è quella del telefono). */
    data class Done(val finalName: String) : UploadResult()
    /** C'era già un file con lo stesso nome e la stessa dimensione: non si ricarica. */
    data class Skipped(val existingName: String) : UploadResult()
    data class Failed(val message: String, val retry: Boolean) : UploadResult()
    object Cancelled : UploadResult()
}

class CloudException(message: String, val retry: Boolean = true) : Exception(message)

interface CloudDrive {
    /** Nome da mostrare: «dav.example.com · username», «Proton Drive». */
    val label: String
    val rootRef: String
    /** Elenca una cartella (cartelle prima, poi per nome). Lancia [CloudException] con un messaggio da mostrare. */
    fun list(dirRef: String, dirPath: String): List<CloudEntry>
    /** La cartella al percorso leggibile, creandola se [create]; null se non c'è (e non la si crea). */
    fun resolveDir(relPath: String, create: Boolean): String?
    /**
     * Carica un file: prima con un nome provvisorio nascosto, poi lo rinomina; mai sopra un file che
     * c'è già (stesso nome e stessa dimensione = già caricato; stessa dimensione no = « (1)»).
     * [mtime] = la data del file sul telefono: lassù il file la tiene, mai «oggi». 0 = non si sa.
     */
    fun upload(dirRef: String, name: String, size: Long, open: () -> InputStream, progress: (Long) -> Unit, cancelled: () -> Boolean, mtime: Long = 0): UploadResult
    /** Legge un file (tutto o un pezzo: [end] incluso, -1 = fino alla fine). */
    fun open(entry: CloudEntry, start: Long = 0, end: Long = -1): InputStream
    /** Per i video: un indirizzo da dare al lettore, con le intestazioni (null = prima si scarica). */
    fun streamUri(entry: CloudEntry): Pair<Uri, Map<String, String>>?
    /** Prova la connessione: null se va, altrimenti il messaggio da mostrare. */
    fun check(): String?
    /** Rinomina dentro la stessa cartella; mai sopra un nome che c'è (CloudException). Torna il ref nuovo. */
    fun rename(e: CloudEntry, newName: String): String
    /** Sposta o copia dentro il cloud, nella cartella [destDir] (percorso leggibile); nome libero se c'è già. */
    fun transfer(e: CloudEntry, destDir: String, copy: Boolean): String
    /** Un file piccolo letto intero; null se non c'è. */
    fun readSmall(relPath: String): ByteArray?
    /** Scrive un file piccolo (la scheda di un elemento nel cestino dell'app). */
    fun putSmall(relPath: String, bytes: ByteArray)
    /** Sposta [e] nella cartella [destDir] col nome [name] (mai sopra: CloudException se c'è già). */
    fun moveTo(e: CloudEntry, destDir: String, name: String)
    /** Cancella davvero un percorso (solo per svuotare il cestino dell'app). */
    fun deletePath(relPath: String, isDir: Boolean)
    /** Dimensione e data vere di un file (le voci degli indici non le hanno); null se non c'è. */
    fun info(ref: String): CloudEntry? = null
    /**
     * Tutto l'albero sotto [relRoot] in UNA richiesta (cartelle e file, nascosti esclusi), o null se questo cloud non
     * lo sa fare: allora si cammina cartella per cartella con [list]. [complete] = false se il server ha saltato qualcosa.
     */
    fun listAll(relRoot: String): TreeListing? = null
    /** L'impronta md5 calcolata DAL SERVER (niente download), percorso → md5; null se questo cloud non la dà. */
    fun md5(relPaths: List<String>): Map<String, String>? = null
}

class TreeListing(val entries: List<CloudEntry>, val complete: Boolean)

/** Il cloud collegato: tipo, indirizzo, utente (in chiaro) e password (cifrata col portachiavi di Android). */
object Clouds {
    private const val TAG = "151Storage"
    private const val PREFS = "cloud"
    const val KIND_WEBDAV = "webdav"
    /** Proton Drive: niente API pubblica (ricerca del 07/10/2026). Si passa dall'app ufficiale: apri e carica. */
    const val KIND_PROTON = "proton"
    const val PROTON_PACKAGE = "me.proton.android.drive"
    /** Un server SFTP con una chiave SSH. L'indirizzo è «sftp://host:porta/base». */
    const val KIND_SFTP = "sftp"

    data class Account(val kind: String, val url: String, val user: String, val label: String)

    @Volatile private var cached: Pair<Account, CloudDrive>? = null

    fun account(ctx: Context): Account? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = p.getString("kind", null) ?: return null
        return Account(kind, p.getString("url", "")!!, p.getString("user", "")!!, p.getString("label", "")!!)
    }

    fun drive(ctx: Context): CloudDrive? {
        val a = account(ctx) ?: return null
        cached?.let { if (it.first == a) return it.second }
        val d = build(ctx, a) ?: return null
        cached = a to d
        return d
    }

    /** Un collegamento tutto suo, fuori dal mucchio comune: i caricamenti ne aprono più d'uno insieme. */
    fun freshDrive(ctx: Context): CloudDrive? = account(ctx)?.let { build(ctx, it) }

    private fun build(ctx: Context, a: Account): CloudDrive? {
        return when (a.kind) {
            KIND_WEBDAV -> WebDav(a.url, a.user, Secret.get(ctx) ?: "", a.label)
            KIND_SFTP -> {
                val j = try { org.json.JSONObject(Secret.get(ctx) ?: return null) } catch (_: Exception) { return null }
                val u = Uri.parse(a.url)
                SftpDrive(u.host ?: return null, if (u.port > 0) u.port else 22, a.user, (u.path ?: "").trim('/'),
                    j.getString("key"), j.getString("known"), a.label)
            }
            else -> null // Proton: nessun «drive» da sfogliare, solo l'app ufficiale
        }
    }

    fun saveWebDav(ctx: Context, url: String, user: String, password: String) {
        Secret.put(ctx, password)
        val host = try { Uri.parse(url).host.orEmpty() } catch (_: Exception) { "" }
        val label = "$host · $user"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("kind", KIND_WEBDAV).putString("url", url).putString("user", user).putString("label", label).apply()
        cached = null
        ThumbService.start(ctx) // cloud appena collegato: si comincia subito
    }

    /** La chiave e l'impronta del server si salvano cifrate (portachiavi di Android), come la password del WebDAV. */
    fun saveSftp(ctx: Context, host: String, port: Int, user: String, base: String, key: String, known: String, label: String) {
        Secret.put(ctx, org.json.JSONObject().put("key", key).put("known", known).toString())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("kind", KIND_SFTP).putString("url", "sftp://$host:$port/${base.trim('/')}").putString("user", user)
            .putString("label", label).apply()
        cached = null
        Log.i(TAG, "cloud collegato con la chiave")
        ThumbService.start(ctx)
    }

    fun saveProton(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("kind", KIND_PROTON).putString("label", "Proton Drive").apply()
        Secret.clear(ctx)
        cached = null
    }

    /** Scollega: via indirizzo e password dal telefono. I file sul cloud non si toccano. */
    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        Secret.clear(ctx)
        cached = null
        File(ctx.cacheDir, "cthumb").deleteRecursively() // anteprime vecchie (fino alla 0.6.0)
        CloudThumbs.clear(ctx)
        File(ctx.cacheDir, "cloud").deleteRecursively()
        Log.i(TAG, "cloud scollegato")
    }

    /** Cartelle d'arrivo predefinite (cambiabili nel foglietto «Carica»). */
    fun destinations(ctx: Context): JSONObject = JSONObject(
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dest", null) ?: "{}"
    )

    fun setDestination(ctx: Context, key: String, value: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val j = destinations(ctx).put(key, value)
        p.edit().putString("dest", j.toString()).apply()
    }
}

/**
 * La password del cloud, cifrata con una chiave AES che non esce mai dal portachiavi di Android
 * (AndroidKeyStore). Nel file delle preferenze c'è solo il testo cifrato: un backup o una copia
 * dei dati dell'app non la rivela. Il backup dell'app è comunque spento (manifest).
 */
object Secret {
    private const val ALIAS = "cloud-secret"
    private const val PREFS = "cloud-secret"

    private fun key(): SecretKey = keyFor(ALIAS)

    /** Una chiave AES del portachiavi di Android, creata la prima volta (per la password del cloud). */
    fun keyFor(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("v", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context): String? = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(p.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val v = Base64.decode(p.getString("v", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(v), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w("151Storage", "password del cloud illeggibile", e)
        null
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
    }
}

/** Un nome libero accanto a quelli che ci sono: «foto.jpg» → «foto (1).jpg». */
fun freeName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val ext = name.substring(dot)
    var i = 1
    while (true) {
        val n = "$base ($i)$ext"
        if (!taken(n)) return n
        i++
    }
}

/**
 * I file del cloud portati sul telefono per aprirli: una copia in cache (`cache/cloud/<impronta>/<nome>`), o quella
 * «Sul telefono» se c'è. Un download si guadagna il nome: `.part`, dimensione controllata, poi rename.
 * Usato da Cloud (elenco e griglia) e dal visore (foto del cloud che si sfogliano).
 */
object CloudFiles {
    fun localFor(ctx: Context, e: CloudEntry): File {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest((e.path + e.size + e.mtime).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(File(ctx.cacheDir, "cloud/$h"), e.name)
    }

    /** Il file già pronto sul telefono, se c'è (copia «Sul telefono» o cache intera). */
    fun ready(ctx: Context, e: CloudEntry): File? =
        Offline.copyOf(ctx, e) ?: localFor(ctx, e).takeIf { it.isFile && (e.size < 0 || it.length() == e.size) }

    /** Scarica (se serve) e torna il file, o lancia CloudException/IOException con un messaggio da mostrare. */
    fun fetch(ctx: Context, drive: CloudDrive, e: CloudEntry, progress: (Int) -> Unit = {}): File {
        ready(ctx, e)?.let { return it }
        val f = localFor(ctx, e)
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        try {
            var got = 0L
            var last = -1
            drive.open(e).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        val pct = if (e.size > 0) (got * 100 / e.size).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            if (e.size >= 0 && got != e.size) throw java.io.IOException("arrived incomplete")
            if (e.mtime > 0) part.setLastModified(e.mtime) // la data del file del cloud, mai «oggi»
            if (!part.renameTo(f)) throw java.io.IOException("can't be written")
            return f
        } finally { part.delete() }
    }
}
