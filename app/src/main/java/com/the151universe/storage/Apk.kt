package com.the151universe.storage

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * v5: scheda di un pacchetto Android (.apk) SENZA installarlo: nome, icona, pacchetto, versione,
 * SDK minimo/target, permessi richiesti (con il nome leggibile che dà Android), impronta del
 * certificato di firma. Tutto da [PackageManager.getPackageArchiveInfo], che vuole un percorso:
 * se l'URI non ne ha uno leggibile il file si copia nella cache dell'app (e si cancella alla chiusura).
 */
object ApkInspector {

    class Result(
        val pkg: String?, val label: String, val icon: Drawable?, val versionName: String?, val versionCode: Long,
        val minSdk: Int, val targetSdk: Int, val permissions: List<Pair<String, String?>>, val dangerous: Set<String>,
        val signer: String?, val signerSha256: String?, val installed: String?, val size: Long, val temp: File?,
    )

    private const val MAX_COPY = 2L * 1024 * 1024 * 1024

    fun inspect(ctx: Context, uri: Uri, knownPath: String?): Result {
        var temp: File? = null
        val direct = listOfNotNull(knownPath, Storage.ownPath(ctx, uri), if (uri.scheme == "file") uri.path else null)
            .map(::File).firstOrNull { it.isFile && it.canRead() }
        val file = direct ?: run {
            val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // copie lasciate da un'apertura precedente
            val f = File(dir, "ispezione-${System.nanoTime()}.apk")
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "stream nullo" }
                f.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_COPY) throw IllegalStateException("APK troppo grande")
                        out.write(buf, 0, n)
                    }
                }
            }
            temp = f
            f
        }
        val pm = ctx.packageManager
        var flags = PackageManager.GET_PERMISSIONS.toLong()
        flags = flags or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES.toLong() else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES.toLong())
        val pi: PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.absolutePath, flags.toInt())
        }
        if (pi == null) return Result(null, file.name, null, null, 0, 0, 0, emptyList(), emptySet(), null, null, null, file.length(), temp)
        val ai = pi.applicationInfo
        ai?.sourceDir = file.absolutePath
        ai?.publicSourceDir = file.absolutePath
        val label = try { ai?.let { pm.getApplicationLabel(it).toString() } } catch (_: Exception) { null } ?: pi.packageName
        val icon = try { ai?.let { pm.getApplicationIcon(it) } } catch (_: Exception) { null }
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val minSdk = if (Build.VERSION.SDK_INT >= 24) ai?.minSdkVersion ?: 0 else 0
        val perms = (pi.requestedPermissions ?: emptyArray()).map { p ->
            val lbl = try {
                val info = pm.getPermissionInfo(p, 0)
                info.loadLabel(pm).toString().takeIf { it != p }
            } catch (_: Exception) { null }
            p to lbl
        }.sortedWith(compareBy({ it.second == null }, { (it.second ?: it.first).lowercase(Locale.ROOT) }))
        val dangerous = perms.mapNotNull { (p, _) ->
            try {
                val info = pm.getPermissionInfo(p, 0)
                val prot = if (Build.VERSION.SDK_INT >= 28) info.protection else @Suppress("DEPRECATION") (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
                if (prot == PermissionInfo.PROTECTION_DANGEROUS) p else null
            } catch (_: Exception) { null }
        }.toSet()
        val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            pi.signingInfo?.let { si -> (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.firstOrNull()?.toByteArray() }
        } else {
            @Suppress("DEPRECATION") pi.signatures?.firstOrNull()?.toByteArray()
        }
        var signer: String? = null
        var sha: String? = null
        if (certBytes != null) {
            sha = MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString(":") { "%02X".format(it) }
            signer = try {
                (CertificateFactory.getInstance("X.509").generateCertificate(certBytes.inputStream()) as X509Certificate).subjectX500Principal.name
            } catch (_: Exception) { null }
        }
        // installata? Da Android 11 si vedono solo alcune app: se non la troviamo NON diciamo "non installata"
        val installed = try {
            val ip = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pi.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageInfo(pi.packageName, 0)
            val c = if (Build.VERSION.SDK_INT >= 28) ip.longVersionCode else @Suppress("DEPRECATION") ip.versionCode.toLong()
            "${ip.versionName ?: "?"} ($c)"
        } catch (_: Exception) { null }
        return Result(pi.packageName, label, icon, pi.versionName, code, minSdk, ai?.targetSdkVersion ?: 0, perms, dangerous,
            signer, sha, installed, file.length(), temp)
    }

    private fun androidName(api: Int): String = when {
        api <= 0 -> "?"
        else -> {
            val v = mapOf(21 to "5.0", 22 to "5.1", 23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10",
                30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16")[api]
            if (v != null) "Android $v (API $api)" else "API $api"
        }
    }

    fun view(ctx: Context, r: Result): View {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(48))
        }
        scroll.addView(col)
        val muted = MaterialColors.getColor(col, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val strong = MaterialColors.getColor(col, com.google.android.material.R.attr.colorOnSurface)
        val warn = MaterialColors.getColor(col, androidx.appcompat.R.attr.colorError)
        // stessa grammatica del resto dell'app: Archivo dice, Mono misura, Garamond spiega
        val title = R.style.TextAppearance_Viewer_Headline
        val head = R.style.TextAppearance_Viewer_Title
        val body = R.style.TextAppearance_Viewer_Body
        val meta = R.style.TextAppearance_Viewer_Meta
        val prose = R.style.TextAppearance_Viewer_Prose
        fun text(t: CharSequence, ap: Int, color: Int = strong, top: Int = 0, center: Boolean = false, selectable: Boolean = false) =
            TextView(ctx).apply {
                setTextAppearance(ap)
                text = t
                setTextColor(color)
                if (center) gravity = Gravity.CENTER_HORIZONTAL
                setTextIsSelectable(selectable)
                setPadding(0, px(top), 0, 0)
                col.addView(this)
            }
        ImageView(ctx).apply {
            if (r.icon != null) setImageDrawable(r.icon) else setImageResource(R.drawable.ic_doc_generic)
            col.addView(this, LinearLayout.LayoutParams(px(72), px(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        text(r.label, title, top = 16, center = true, selectable = true)
        text(r.pkg ?: "", meta, muted, top = 4, center = true, selectable = true)
        fun row(k: String, v: String?) {
            if (v.isNullOrBlank()) return
            text(k, meta, muted, top = 16)
            text(v, body, top = 2, selectable = true)
        }
        row("Version", listOfNotNull(r.versionName, "code ${r.versionCode}").joinToString(" · "))
        row("Runs on", androidName(r.minSdk))
        row("Built for", androidName(r.targetSdk))
        row("Size", Sizes.short(r.size))
        row("Already installed", r.installed?.let { "yes, version $it" })
        row("Signed by", r.signer)
        row("Certificate SHA-256 fingerprint", r.signerSha256)
        text("Requested permissions (${r.permissions.size})", head, top = 30)
        if (r.dangerous.isNotEmpty()) text("${r.dangerous.size} sensitive: they ask for your consent", prose, warn, top = 4)
        if (r.permissions.isEmpty()) text("None", prose, muted, top = 8)
        for ((p, lbl) in r.permissions) {
            val sensitive = p in r.dangerous
            text((if (sensitive) "Sensitive · " else "") + (lbl?.replaceFirstChar { it.titlecase(Locale.UK) } ?: p.substringAfterLast('.')),
                body, if (sensitive) warn else strong, top = 12)
            text(p, meta, muted, selectable = true)
        }
        text("151 Storage only shows this sheet: it doesn't install anything.", prose, muted, top = 30)
        return scroll
    }
}
