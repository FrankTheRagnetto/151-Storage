package com.the151universe.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * PDF/SVG/TEXT come in v2. DOC = pagina assets/viewer/doc.html (Markdown, Office, OpenDocument, RTF,
 * EPUB, ZIP e altri archivi, email, Outlook, vCard, iCal, sottotitoli, font, TIFF, PSD, ebook…):
 * [DocInfo.fmt] dice quale modulo. HTML = pagina web vera, su un'origine a parte. MEDIA = lettore
 * nativo (solo audio). APK = scheda nativa del pacchetto (PackageManager).
 * v5: 151 Viewer NON e' un'app per foto e video (decisione dell'utente): se ne arriva una comunque,
 * NOT_DOC = schermata «non è un documento» con «Apri con un'altra app» (la Galleria).
 */
enum class Kind { PDF, SVG, TEXT, DOC, HTML, MEDIA, APK, NOT_DOC, UNSUPPORTED }

/** Cosa sappiamo del file prima di mostrarlo. [size] = -1 se ignota. */
data class DocInfo(
    val uri: Uri?,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: Kind,
    /** Solo per SEND di puro testo (nessun file). */
    val sharedText: String? = null,
    /** DOC: formato per doc.html ("md", "docx", "auto"…). TEXT: linguaggio per i colori ("yaml", "log"…). */
    val fmt: String = "",
)

/**
 * Decide COME mostrare un file. Ordine: firma dei byte (magic) > MIME dichiarato > estensione
 * > euristica "sembra testo". Le app mittenti spesso dichiarano application/octet-stream:
 * la firma dei byte e' l'unica cosa di cui fidarsi.
 */
object Resolver {

    private val TEXT_MIMES = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript",
        "application/ecmascript", "application/x-sh", "application/x-shellscript", "application/yaml",
        "application/x-yaml", "application/toml", "application/sql", "application/x-subrip",
        "application/x-httpd-php", "application/x-python", "application/x-kotlin", "application/csv",
        "application/x-ndjson", "application/x-tex", "application/rtf", "application/jsonl", "application/x-jsonlines",
        "application/x-bibtex", "application/x-latex", "application/x-perl", "application/x-ruby", "application/x-php",
        "application/x-diff", "application/x-patch", "application/sql", "application/graphql", "application/x-desktop",
    )

    private val TEXT_EXT = setOf(
        "txt", "text", "md", "markdown", "json", "jsonl", "ndjson", "geojson", "xml", "csv", "tsv", "log",
        "ini", "cfg", "conf", "config", "yaml", "yml", "toml", "properties", "env", "js", "mjs", "cjs",
        "ts", "tsx", "jsx", "kt", "kts", "java", "gradle", "py", "sh", "bash", "zsh", "fish", "c", "h",
        "cc", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "pl", "lua", "r", "swift", "dart", "scala",
        "html", "htm", "xhtml", "css", "scss", "sass", "less", "sql", "srt", "vtt", "ass", "lrc", "diff",
        "patch", "tex", "bib", "rst", "adoc", "org", "nfo", "bat", "cmd", "ps1", "gd", "tscn", "tres",
        "gitignore", "gitattributes", "editorconfig", "dockerfile", "makefile", "cmake", "m3u", "m3u8",
        "pls", "cue", "vcf", "ics", "gpx", "kml", "plist", "rtf", "ino", "vue", "svelte",
        // v5: altri linguaggi e file di configurazione comuni
        "hs", "ex", "exs", "erl", "hrl", "clj", "cljs", "edn", "fs", "fsx", "ml", "mli", "jl", "m", "f90", "f", "for",
        "pas", "dpr", "proto", "glsl", "vert", "frag", "v", "sv", "vhd", "vhdl", "asm", "s", "tcl", "awk", "elm", "cr",
        "d", "coffee", "nix", "pro", "lisp", "el", "scm", "rkt", "vbs", "vb", "applescript", "ahk", "ll", "hbs",
        "twig", "smali", "vim", "nsi", "pp", "http", "nim", "gcode", "sas", "do", "graphql", "gql", "mk", "mak",
        "lock", "sum", "mod", "csproj", "sln", "xaml", "pom", "iml", "npmrc", "prettierrc", "eslintrc", "babelrc",
        "rc", "htaccess", "service", "socket", "timer", "mount", "desktop", "reg", "inf", "man", "1", "adoc", "asciidoc",
        "textile", "wiki", "mediawiki", "tsv", "psv", "sql", "psql", "hql", "cql", "rego", "bicep", "tf", "tfvars", "hcl",
        "jsonc", "json5", "hjson", "webmanifest", "pbxproj", "strings", "po", "pot", "srt", "sbv", "ass", "ssa",
    )

    /** File di testo riconoscibili solo dal nome (niente estensione, o estensione ingannevole). */
    private val TEXT_NAMES = mapOf(
        "makefile" to "makefile", "gnumakefile" to "makefile", "dockerfile" to "dockerfile", "containerfile" to "dockerfile",
        "cmakelists.txt" to "cmake", "gemfile" to "ruby", "rakefile" to "ruby", "podfile" to "ruby", "vagrantfile" to "ruby",
        "jenkinsfile" to "groovy", "procfile" to "", "license" to "", "readme" to "", "copying" to "", "authors" to "",
        "changelog" to "", "notice" to "", "brewfile" to "ruby", "justfile" to "makefile", "pkgbuild" to "bash",
        "go.mod" to "", "cargo.lock" to "ini", "poetry.lock" to "ini", ".bashrc" to "bash", ".zshrc" to "bash",
        ".profile" to "bash", ".bash_profile" to "bash", "build.gradle" to "groovy", "settings.gradle" to "groovy",
        "nginx.conf" to "nginx", "httpd.conf" to "apache",
    )

    /** Foto RAW delle fotocamere: molte sono TIFF "travestiti", ma restano foto (Galleria, non noi). */
    private val RAW_PHOTO_EXT = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "pef", "srw", "raf", "3fr", "erf", "kdc", "mos", "mrw", "x3f")
    private val PHOTO_EXT = setOf("jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "ico", "jxl") + RAW_PHOTO_EXT
    private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "avi", "wmv", "flv", "mpg", "mpeg", "m2ts", "vob", "ogv", "lrv") // .ts/.mts: TypeScript (MPEG-TS si riconosce dai byte)
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "mid", "midi", "wma", "aiff", "aif", "3ga", "m4b")

    fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Nome e dimensione dal provider (o dal filesystem per file://). */
    fun queryMeta(ctx: Context, uri: Uri): Pair<String?, Long> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: return null to -1L)
            return f.name to (if (f.exists()) f.length() else -1L)
        }
        var name: String? = null
        var size = -1L
        try {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
            // alcuni provider non implementano query(): useremo il nome dall'URI
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return name to size
    }

    fun resolve(ctx: Context, uri: Uri, intentType: String?): DocInfo {
        val (qName, qSize) = queryMeta(ctx, uri)
        val name = qName?.takeIf { it.isNotBlank() } ?: "file"
        val ext = extOf(name)
        val providerType = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
        val extMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: EXT_MIME[ext]
        val declared = listOf(intentType, providerType, extMime)
            .firstOrNull { !it.isNullOrBlank() && it != "*/*" && !isGeneric(it) }
            ?.lowercase(Locale.ROOT)
            ?: providerType ?: intentType ?: "application/octet-stream"

        val head = readHead(ctx, uri) // SecurityException/FileNotFound risalgono al chiamante
        val sniffed = sniff(head)
        val (kind, fmt) = decide(sniffed, declared, ext, head, name.lowercase(Locale.ROOT))
        val mime = when {
            kind == Kind.APK -> "application/vnd.android.package-archive"
            sniffed != null && sniffed != "text/svg" && !sniffed.startsWith("x-") -> sniffed
            kind == Kind.SVG -> "image/svg+xml"
            isGeneric(declared) && extMime != null -> extMime
            else -> declared
        }
        return DocInfo(uri, name, mime, qSize, kind, fmt = fmt)
    }

    private fun isGeneric(m: String) = m.lowercase(Locale.ROOT) in setOf(
        "application/octet-stream", "application/binary", "application/unknown", "application/force-download",
        "application/download", "binary/octet-stream",
    )

    /** Estensioni che Android non conosce (o conosce male) -> MIME vero. */
    private val EXT_MIME = mapOf(
        "md" to "text/markdown", "markdown" to "text/markdown", "eml" to "message/rfc822", "mht" to "multipart/related",
        "mhtml" to "multipart/related", "epub" to "application/epub+zip", "srt" to "application/x-subrip", "vtt" to "text/vtt",
        "ics" to "text/calendar", "vcf" to "text/vcard", "yaml" to "application/yaml", "yml" to "application/yaml",
        "toml" to "application/toml", "log" to "text/plain", "ini" to "text/plain", "woff2" to "font/woff2", "woff" to "font/woff",
        "otf" to "font/otf", "ttf" to "font/ttf", "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet", "odp" to "application/vnd.oasis.opendocument.presentation",
        // v5
        "msg" to "application/vnd.ms-outlook", "pages" to "application/vnd.apple.pages", "numbers" to "application/vnd.apple.numbers",
        "key" to "application/vnd.apple.keynote", "xps" to "application/vnd.ms-xpsdocument", "oxps" to "application/oxps",
        "cbz" to "application/vnd.comicbook+zip", "cbr" to "application/vnd.comicbook-rar", "cb7" to "application/x-cb7",
        "mobi" to "application/x-mobipocket-ebook", "azw3" to "application/vnd.amazon.mobi8-ebook", "azw" to "application/vnd.amazon.ebook",
        "fb2" to "application/x-fictionbook+xml", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip", "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz", "zst" to "application/zstd", "ipynb" to "application/x-ipynb+json", "geojson" to "application/geo+json",
        "gpx" to "application/gpx+xml", "kml" to "application/vnd.google-earth.kml+xml", "kmz" to "application/vnd.google-earth.kmz",
        "drawio" to "application/vnd.jgraph.mxfile", "dio" to "application/vnd.jgraph.mxfile", "excalidraw" to "application/vnd.excalidraw+json",
        "torrent" to "application/x-bittorrent", "apk" to "application/vnd.android.package-archive", "pem" to "application/x-pem-file",
        "crt" to "application/x-x509-ca-cert", "cer" to "application/pkix-cert", "der" to "application/x-x509-ca-cert",
        "p7m" to "application/pkcs7-mime", "p7s" to "application/pkcs7-signature", "p7b" to "application/x-pkcs7-certificates",
        "p7c" to "application/pkcs7-mime", "tif" to "image/tiff", "tiff" to "image/tiff", "psd" to "image/vnd.adobe.photoshop",
        "eps" to "application/postscript", "ps" to "application/postscript", "ai" to "application/illustrator",
        "url" to "application/x-mswinurl", "webloc" to "application/x-webloc", "desktop" to "application/x-desktop",
        "jsonl" to "application/jsonl", "ndjson" to "application/x-ndjson", "sql" to "application/sql", "diff" to "text/x-diff",
        "patch" to "text/x-diff", "tex" to "application/x-tex", "bib" to "application/x-bibtex",
    )

    /** Formato per doc.html dal MIME dichiarato. */
    private val MIME_FMT = mapOf(
        "text/markdown" to "md", "text/x-markdown" to "md", "text/x-web-markdown" to "md",
        "message/rfc822" to "eml", "multipart/related" to "mhtml", "application/x-mimearchive" to "mhtml", "message/x-mht" to "mhtml",
        "application/epub+zip" to "epub", "application/zip" to "auto", "application/x-zip-compressed" to "auto", "application/x-zip" to "auto",
        "application/rtf" to "rtf", "text/rtf" to "rtf", "application/x-rtf" to "rtf", "text/richtext" to "rtf",
        "text/calendar" to "ics", "text/x-vcalendar" to "ics", "text/vcard" to "vcf", "text/x-vcard" to "vcf", "text/directory" to "vcf",
        "application/x-subrip" to "srt", "text/x-subrip" to "srt", "text/vtt" to "vtt",
        "text/csv" to "csv", "text/comma-separated-values" to "csv", "application/csv" to "csv", "text/tab-separated-values" to "tsv",
        "application/msword" to "doc", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-word.document.macroenabled.12" to "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to "docx",
        "application/vnd.ms-excel" to "xls", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-excel.sheet.macroenabled.12" to "xlsx", "application/vnd.ms-excel.sheet.binary.macroenabled.12" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt", "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt", "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        // v5
        "application/vnd.ms-outlook" to "msg", "application/x-msg" to "msg", "application/x-ole-storage" to "auto",
        "application/vnd.apple.pages" to "iwork", "application/vnd.apple.numbers" to "iwork", "application/vnd.apple.keynote" to "iwork",
        "application/x-iwork-pages-sffpages" to "iwork", "application/x-iwork-numbers-sffnumbers" to "iwork", "application/x-iwork-keynote-sffkey" to "iwork",
        "application/vnd.ms-xpsdocument" to "xps", "application/oxps" to "xps",
        "application/vnd.comicbook+zip" to "comic", "application/x-cbz" to "comic", "application/vnd.comicbook-rar" to "comic",
        "application/x-cbr" to "comic", "application/x-cb7" to "comic",
        "application/x-mobipocket-ebook" to "mobi", "application/vnd.amazon.ebook" to "mobi", "application/vnd.amazon.mobi8-ebook" to "mobi",
        "application/x-mobi8-ebook" to "mobi", "application/x-fictionbook+xml" to "fb2", "application/x-fictionbook" to "fb2",
        "application/x-7z-compressed" to "archive", "application/x-tar" to "archive", "application/gzip" to "archive", "application/x-gzip" to "archive",
        "application/x-compressed-tar" to "archive", "application/x-rar-compressed" to "archive", "application/vnd.rar" to "archive",
        "application/x-rar" to "archive", "application/x-bzip2" to "archive", "application/x-bzip" to "archive", "application/x-xz" to "archive",
        "application/zstd" to "archive", "application/x-zstd" to "archive", "application/x-lzma" to "archive",
        "application/x-ipynb+json" to "ipynb", "application/geo+json" to "geo", "application/vnd.geo+json" to "geo", "application/gpx+xml" to "geo",
        "application/vnd.google-earth.kml+xml" to "geo", "application/vnd.google-earth.kmz" to "geo",
        "application/vnd.jgraph.mxfile" to "drawio", "application/vnd.excalidraw+json" to "excalidraw",
        "application/x-bittorrent" to "torrent", "application/x-pem-file" to "cert", "application/x-x509-ca-cert" to "cert",
        "application/x-x509-user-cert" to "cert", "application/pkix-cert" to "cert", "application/pkcs7-mime" to "p7m",
        "application/pkcs7-signature" to "p7m", "application/x-pkcs7-certificates" to "p7m", "application/x-pkcs7-mime" to "p7m",
        "application/x-pkcs7-signature" to "p7m", "image/tiff" to "tiff", "image/tiff-fx" to "tiff", "image/x-tiff" to "tiff",
        "image/vnd.adobe.photoshop" to "psd", "image/x-photoshop" to "psd", "image/psd" to "psd", "application/photoshop" to "psd",
        "application/x-photoshop" to "psd", "application/psd" to "psd", "application/postscript" to "eps", "application/eps" to "eps",
        "application/x-eps" to "eps", "image/eps" to "eps", "image/x-eps" to "eps", "application/illustrator" to "eps",
        "application/vnd.adobe.illustrator" to "eps", "application/x-mswinurl" to "link", "application/internet-shortcut" to "link",
        "application/x-url" to "link", "application/x-webloc" to "link", "application/x-desktop" to "link",
    )

    /** Formato per doc.html dall'estensione. */
    private val EXT_FMT = mapOf(
        "md" to "md", "markdown" to "md", "mdown" to "md", "mkd" to "md", "eml" to "eml", "mht" to "mhtml", "mhtml" to "mhtml",
        "epub" to "epub", "zip" to "auto", "rtf" to "rtf", "ics" to "ics", "ical" to "ics", "vcf" to "vcf", "vcard" to "vcf",
        "srt" to "srt", "vtt" to "vtt", "csv" to "csv", "tsv" to "tsv", "doc" to "doc", "dot" to "doc", "docx" to "docx", "docm" to "docx",
        "dotx" to "docx", "xls" to "xls", "xlsx" to "xlsx", "xlsm" to "xlsx", "xlsb" to "xlsx", "xltx" to "xlsx", "ppt" to "ppt", "pps" to "ppt",
        "pptx" to "pptx", "pptm" to "pptx", "ppsx" to "pptx", "potx" to "pptx", "odt" to "odt", "ott" to "odt", "ods" to "ods", "fods" to "ods",
        "odp" to "odp", "ttf" to "font", "otf" to "font", "woff" to "font", "woff2" to "font", "ttc" to "font",
        // v5
        "msg" to "msg", "pages" to "iwork", "numbers" to "iwork", "key" to "iwork", "xps" to "xps", "oxps" to "xps",
        "cbz" to "comic", "cbr" to "comic", "cb7" to "comic", "cbt" to "comic", "mobi" to "mobi", "azw" to "mobi", "azw3" to "mobi",
        "prc" to "mobi", "fb2" to "fb2", "7z" to "archive", "rar" to "archive", "tar" to "archive", "gz" to "archive", "tgz" to "archive",
        "bz2" to "archive", "tbz2" to "archive", "tbz" to "archive", "xz" to "archive", "txz" to "archive", "zst" to "archive",
        "tzst" to "archive", "lzma" to "archive", "ipynb" to "ipynb", "geojson" to "geo", "gpx" to "geo", "kml" to "geo", "kmz" to "geo",
        "drawio" to "drawio", "dio" to "drawio", "excalidraw" to "excalidraw", "url" to "link", "webloc" to "link", "desktop" to "link",
        "torrent" to "torrent", "pem" to "cert", "crt" to "cert", "cer" to "cert", "der" to "cert", "p7m" to "p7m", "p7s" to "p7m",
        "p7b" to "p7m", "p7c" to "p7m", "tif" to "tiff", "tiff" to "tiff", "psd" to "psd", "psb" to "psd", "eps" to "eps", "epsf" to "eps",
        "ps" to "eps", "ai" to "eps",
    )

    /** Formati di doc.html che sono TESTO: si mostrano solo se i byte sembrano davvero testo. */
    private val TEXTUAL_FMT = setOf("md", "eml", "mhtml", "ics", "vcf", "srt", "vtt", "csv", "tsv", "ipynb", "fb2", "drawio", "excalidraw", "fattura")

    /** Colori di sintassi nella vista testo (nomi highlight.js; "log" = righe colorate per livello). */
    fun langFor(ext: String, mime: String): String = when (ext) {
        "yaml", "yml" -> "yaml"
        "toml", "ini", "cfg", "conf", "config", "properties", "env", "editorconfig", "gitattributes", "desktop", "service" -> "ini"
        "log" -> "log"
        "json", "jsonl", "ndjson", "geojson" -> "json"
        "xml", "plist", "gpx", "kml", "xhtml", "html", "htm", "svg", "tscn", "tres" -> "xml"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "gradle", "groovy" -> "groovy"
        "py" -> "python"
        "sh", "bash", "zsh", "fish" -> "bash"
        "c", "h" -> "c"
        "cc", "cpp", "hpp", "ino" -> "cpp"
        "cs" -> "csharp"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "php" -> "php"
        "pl" -> "perl"
        "lua" -> "lua"
        "r" -> "r"
        "swift" -> "swift"
        "dart" -> "dart"
        "scala" -> "scala"
        "css" -> "css"
        "scss", "sass" -> "scss"
        "less" -> "less"
        "sql" -> "sql"
        "diff", "patch" -> "diff"
        "tex", "bib" -> "latex"
        "md", "markdown" -> "markdown"
        "bat", "cmd" -> "dos"
        "ps1" -> "powershell"
        "dockerfile" -> "dockerfile"
        "makefile" -> "makefile"
        "cmake" -> "cmake"
        "gd" -> "python"
        // v5
        "hs" -> "haskell"; "ex", "exs" -> "elixir"; "erl", "hrl" -> "erlang"; "clj", "cljs", "edn" -> "clojure"
        "fs", "fsx" -> "fsharp"; "ml", "mli" -> "ocaml"; "jl" -> "julia"; "m" -> "matlab"; "f90", "f", "for" -> "fortran"
        "pas", "dpr" -> "delphi"; "proto" -> "protobuf"; "glsl", "vert", "frag" -> "glsl"; "v", "sv" -> "verilog"
        "vhd", "vhdl" -> "vhdl"; "asm", "s" -> "x86asm"; "tcl" -> "tcl"; "awk" -> "awk"; "elm" -> "elm"; "cr" -> "crystal"
        "d" -> "d"; "coffee" -> "coffeescript"; "nix" -> "nix"; "pro" -> "prolog"; "lisp", "el" -> "lisp"
        "scm", "rkt" -> "scheme"; "vbs" -> "vbscript"; "vb" -> "vbnet"; "applescript" -> "applescript"; "ahk" -> "autohotkey"
        "ll" -> "llvm"; "hbs" -> "handlebars"; "twig" -> "twig"; "smali" -> "smali"; "vim" -> "vim"; "nsi" -> "nsis"
        "pp" -> "puppet"; "http" -> "http"; "nim" -> "nim"; "gcode" -> "gcode"; "sas" -> "sas"; "do" -> "stata"
        "graphql", "gql" -> "graphql"; "mk", "mak" -> "makefile"; "adoc", "asciidoc" -> "asciidoc"; "psql" -> "pgsql"
        "hql", "cql" -> "sql"; "jsonc", "json5", "hjson", "webmanifest" -> "json"; "csproj", "xaml", "pom", "iml" -> "xml"
        "socket", "timer", "mount" -> "ini"; "reg", "inf", "npmrc", "tfvars" -> "ini"
        "lock" -> "ini"; "vue", "svelte" -> "xml"; "mts", "cts" -> "typescript"
        else -> when {
            mime.contains("json") -> "json"
            mime.contains("yaml") -> "yaml"
            mime == "application/toml" -> "ini"
            mime.endsWith("xml") -> "xml"
            mime.contains("diff") || mime.contains("patch") -> "diff"
            mime.contains("tex") -> "latex"
            else -> ""
        }
    }

    /** Linguaggio dal NOME del file (Makefile, Dockerfile…), o null. */
    fun langForName(lowerName: String): String? = TEXT_NAMES[lowerName]
        ?: when {
            lowerName.startsWith("dockerfile.") || lowerName.endsWith(".dockerfile") -> "dockerfile"
            lowerName.startsWith("makefile.") -> "makefile"
            lowerName == ".env" || lowerName.startsWith(".env.") -> "bash"
            else -> null
        }
    private fun readHead(ctx: Context, uri: Uri): ByteArray {
        ctx.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw java.io.FileNotFoundException("stream nullo")
            val buf = ByteArray(8192)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return buf.copyOf(n)
        }
    }

    private fun startsWith(b: ByteArray, vararg sig: Int, at: Int = 0): Boolean {
        if (b.size < at + sig.size) return false
        for (i in sig.indices) if ((b[at + i].toInt() and 0xFF) != sig[i]) return false
        return true
    }

    private fun ascii(b: ByteArray, from: Int, len: Int): String =
        if (b.size < from + len) "" else String(b, from, len, Charsets.ISO_8859_1)

    /** MIME dedotto dai primi byte, o null. I nomi "x-…" sono famiglie interne (li decide doc.html). */
    fun sniff(b: ByteArray): String? {
        // %PDF- puo' non stare al byte 0 (spazzatura prima dell'header e' tollerata dai lettori).
        // Anche i file Illustrator moderni (.ai) sono PDF: si aprono come PDF.
        // firma digitale CAdES / PKCS#7 in DER (…p7m): SEQUENCE { OID 1.2.840.113549.1.7.2 signedData … }.
        // PRIMA del PDF: un .pdf.p7m contiene il PDF gia' nei primi byte, e "%PDF-" lo farebbe aprire senza firma.
        if (b.size > 20 && (b[0].toInt() and 0xFF) == 0x30 && indexOf(b, P7_SIGNED, 0, 24) >= 0) return "x-p7m"
        val headAscii = String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1)
        if (headAscii.contains("%PDF-")) return "application/pdf"
        // v5: "documenti-immagine" che NON sono foto: TIFF (scansioni, fax), PSD, EPS/PostScript
        if (startsWith(b, 0x49, 0x49, 0x2A, 0x00) || startsWith(b, 0x4D, 0x4D, 0x00, 0x2A) ||
            startsWith(b, 0x49, 0x49, 0x2B, 0x00) || startsWith(b, 0x4D, 0x4D, 0x00, 0x2B)) return "image/tiff"
        if (ascii(b, 0, 4) == "8BPS") return "image/vnd.adobe.photoshop"
        if (startsWith(b, 0xC5, 0xD0, 0xD3, 0xC6) || ascii(b, 0, 4) == "%!PS") return "application/postscript"
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47)) return "image/png"
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (ascii(b, 0, 4) == "GIF8") return "image/gif"
        if (ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP") return "image/webp"
        if (ascii(b, 4, 4) == "ftyp") {
            when (ascii(b, 8, 4)) {
                "avif", "avis" -> return "image/avif"
                "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> return "image/heif"
                "crx " -> return "image/x-canon-cr3"
            }
        }
        if (startsWith(b, 0x42, 0x4D) && b.size > 26 && b[14].toInt() in listOf(12, 40, 52, 56, 108, 124)) return "image/bmp"
        if (startsWith(b, 0x00, 0x00, 0x01, 0x00) && b.size > 6 && b[4].toInt() != 0 && b[5].toInt() == 0) return "image/x-icon"
        if (startsWith(b, 0xFF, 0x0A) || startsWith(b, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20)) return "image/jxl"
        if (ascii(b, 0, 8) == "AT&TFORM") return "image/vnd.djvu"
        // contenitori: il formato vero lo decide doc.html (legge l'indice dello ZIP / i flussi CFB)
        if (startsWith(b, 0x50, 0x4B, 0x03, 0x04) || startsWith(b, 0x50, 0x4B, 0x05, 0x06)) {
            if (ascii(b, 30, 8) == "mimetype") {
                val mt = ascii(b, 38, 60)
                when {
                    mt.startsWith("application/epub+zip") -> return "application/epub+zip"
                    mt.startsWith("application/vnd.oasis.opendocument.text") -> return "application/vnd.oasis.opendocument.text"
                    mt.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> return "application/vnd.oasis.opendocument.spreadsheet"
                    mt.startsWith("application/vnd.oasis.opendocument.presentation") -> return "application/vnd.oasis.opendocument.presentation"
                }
            }
            return "x-zip"
        }
        if (startsWith(b, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) return "x-cfb"
        if (ascii(b, 0, 5) == "{\\rtf") return "application/rtf"
        // v5: archivi (7z, RAR, gzip, bzip2, xz, zstd, tar)
        if (startsWith(b, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return "x-archive"
        if (ascii(b, 0, 6) == "Rar!\u001A\u0007") return "x-archive"
        if (startsWith(b, 0x1F, 0x8B, 0x08)) return "x-archive"
        if (ascii(b, 0, 3) == "BZh" && ascii(b, 4, 6) == "1AY&SY") return "x-archive"
        if (startsWith(b, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return "x-archive"
        if (startsWith(b, 0x28, 0xB5, 0x2F, 0xFD)) return "x-archive"
        if (ascii(b, 257, 5) == "ustar") return "x-archive"
        // v5: ebook Mobipocket / Kindle (database Palm: il tipo sta al byte 60)
        if (ascii(b, 60, 8) == "BOOKMOBI" || ascii(b, 60, 8) == "TEXtREAd") return "application/x-mobipocket-ebook"
        // v5: firma digitale CAdES / PKCS#7 in DER (…p7m): SEQUENCE { OID 1.2.840.113549.1.7.2 signedData … }
        if (ascii(b, 0, 11) == "d8:announce" || ascii(b, 0, 7) == "d4:info" || ascii(b, 0, 17) == "d13:announce-list") return "application/x-bittorrent"
        if (ascii(b, 0, 8) == "bplist00") return "x-bplist"
        when (ascii(b, 0, 4)) {
            "wOFF" -> return "font/woff"
            "wOF2" -> return "font/woff2"
            "OTTO" -> return "font/otf"
            "ttcf" -> return "font/collection"
            "true" -> return "font/ttf"
            "fLaC" -> return "audio/flac"
            "OggS" -> return if (headAscii.contains("theora") || headAscii.contains("\u0001video")) "video/ogg" else "audio/ogg"
            "MThd" -> return "audio/midi"
        }
        if (startsWith(b, 0x00, 0x01, 0x00, 0x00, 0x00) && b.size > 12) return "font/ttf"
        if (ascii(b, 0, 3) == "ID3") return "audio/mpeg"
        if (ascii(b, 0, 5) == "#!AMR") return "audio/amr"
        if (ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WAVE") return "audio/wav"
        if (ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "AVI ") return "video/x-msvideo"
        if (startsWith(b, 0x1A, 0x45, 0xDF, 0xA3)) return if (headAscii.contains("webm")) "video/webm" else "video/x-matroska"
        if (ascii(b, 4, 4) == "ftyp") {
            val brand = ascii(b, 8, 4)
            return when {
                brand.startsWith("M4A") || brand.startsWith("M4B") -> "audio/mp4"
                brand.startsWith("3g") -> "video/3gpp"
                brand == "qt  " -> "video/quicktime"
                else -> "video/mp4"
            }
        }
        // MPEG-TS (.ts/.mts dei video): un byte 0x47 ogni 188
        if (b.size > 376 && b[0].toInt() == 0x47 && b[188].toInt() == 0x47 && b[376].toInt() == 0x47) return "video/mp2t"
        val lower = headAscii.lowercase(Locale.ROOT)
        if (lower.contains("<svg")) return "text/svg"
        return null
    }

    /** 1.2.840.113549.1.7.2 (PKCS#7 signedData), come OID codificato in DER */
    private val P7_SIGNED = intArrayOf(0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x02)

    private fun indexOf(b: ByteArray, pat: IntArray, from: Int, to: Int): Int {
        val end = minOf(to, b.size - pat.size)
        var i = from
        while (i <= end) {
            var ok = true
            for (k in pat.indices) if ((b[i + k].toInt() and 0xFF) != pat[k]) { ok = false; break }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun isTextMime(m: String): Boolean =
        m.startsWith("text/") || m in TEXT_MIMES || m.endsWith("+json") ||
            (m.endsWith("+xml") && m != "image/svg+xml")

    private fun isPhotoMime(m: String): Boolean =
        m.startsWith("image/") && MIME_FMT[m] == null && m != "image/svg+xml" && m != "image/vnd.djvu" && m != "image/x-djvu"

    private fun decide(sniffed: String?, declared: String, ext: String, head: ByteArray, lowerName: String): Pair<Kind, String> {
        // APK prima di tutto: dentro e' uno ZIP, ma ha una scheda sua (PackageManager)
        if (ext == "apk" || declared == "application/vnd.android.package-archive") {
            if (sniffed == "x-zip" || sniffed == null) return Kind.APK to ""
        }
        if (sniffed == "application/pdf") return Kind.PDF to ""
        if (sniffed == "text/svg") {
            // un .html/.xml che contiene <svg> resta testo; tutto il resto con <svg> e' un'immagine SVG
            if (ext == "html" || ext == "htm" || declared == "text/html" || declared == "application/xhtml+xml") return Kind.HTML to ""
            if (ext == "md" || ext == "markdown" || declared.contains("markdown")) return Kind.DOC to "md"
            if (ext == "drawio" || ext == "dio" || ext == "excalidraw" || ext == "ipynb") return Kind.DOC to EXT_FMT.getValue(ext)
            return if (ext != "svg" && declared != "image/svg+xml" && ext in TEXT_EXT) Kind.TEXT to langFor(ext, declared) else Kind.SVG to ""
        }
        // TIFF: scansioni e fax si', ma le foto RAW delle fotocamere (DNG, NEF, CR2…) sono TIFF anche loro
        if (sniffed == "image/tiff") return if (ext in RAW_PHOTO_EXT) Kind.NOT_DOC to "" else Kind.DOC to "tiff"
        if (sniffed == "image/vnd.adobe.photoshop") return Kind.DOC to "psd"
        if (sniffed == "application/postscript") return Kind.DOC to "eps"
        if (sniffed == "image/vnd.djvu") return Kind.UNSUPPORTED to "djvu"
        if (sniffed != null && sniffed.startsWith("image/")) return Kind.NOT_DOC to ""
        if (sniffed == "x-zip" || sniffed == "x-cfb") return Kind.DOC to (EXT_FMT[ext] ?: MIME_FMT[declared] ?: "auto")
        if (sniffed == "x-archive") return Kind.DOC to (EXT_FMT[ext]?.takeIf { it == "comic" } ?: "archive")
        if (sniffed == "x-p7m") return Kind.DOC to "p7m"
        if (sniffed == "x-bplist") return if (ext == "webloc") Kind.DOC to "link" else Kind.UNSUPPORTED to ""
        if (sniffed == "application/x-mobipocket-ebook") return Kind.DOC to "mobi"
        if (sniffed == "application/x-bittorrent") return Kind.DOC to "torrent"
        if (sniffed != null && sniffed.startsWith("video/")) return Kind.NOT_DOC to ""
        if (sniffed != null && sniffed.startsWith("audio/")) return Kind.MEDIA to ""
        if (sniffed != null && sniffed.startsWith("font/")) return Kind.DOC to "font"
        if (sniffed != null) MIME_FMT[sniffed]?.let { return Kind.DOC to it } // rtf, epub, odt…
        if (declared == "image/svg+xml" || ext == "svg") return Kind.SVG to ""
        if (declared == "application/pdf" || ext == "pdf") return Kind.PDF to "" // lascio che pdf.js dica se e' rotto
        if (ext in PHOTO_EXT || isPhotoMime(declared)) return Kind.NOT_DOC to ""
        if (ext in VIDEO_EXT || declared.startsWith("video/")) return Kind.NOT_DOC to ""
        if (declared.startsWith("audio/") || ext in AUDIO_EXT) return Kind.MEDIA to ""
        if (declared.startsWith("font/") || declared.contains("font-") || declared.contains("x-font")) return Kind.DOC to "font"
        val text = looksLikeText(head)
        // contenuto testuale riconoscibile a colpo d'occhio (anche con MIME generico o sbagliato)
        if (text) {
            val t = String(head, 0, minOf(head.size, 2048), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            val tl = t.lowercase(Locale.ROOT)
            when {
                tl.startsWith("begin:vcard") -> return Kind.DOC to "vcf"
                tl.startsWith("begin:vcalendar") -> return Kind.DOC to "ics"
                tl.startsWith("<!doctype html") || tl.startsWith("<html") -> if (ext !in TEXT_EXT || ext == "html" || ext == "htm" || ext == "xhtml") return Kind.HTML to ""
            }
            // v5: formati XML/JSON/PEM riconosciuti dal contenuto (arrivano spesso come octet-stream o text/xml)
            val t4 = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1)
            when {
                t4.contains("<FictionBook") -> return Kind.DOC to "fb2"
                Regex("<(?:[\\w-]+:)?FatturaElettronica[\\s>]").containsMatchIn(t4) -> return Kind.DOC to "fattura"
                t4.contains("<gpx") && (ext == "gpx" || ext !in TEXT_EXT) -> return Kind.DOC to "geo"
                t4.contains("<kml") && (ext == "kml" || ext !in TEXT_EXT) -> return Kind.DOC to "geo"
                t4.contains("<mxfile") || t4.trimStart().startsWith("<mxGraphModel") -> return Kind.DOC to "drawio"
                Regex("\"type\"\\s*:\\s*\"excalidraw\"").containsMatchIn(t4) -> return Kind.DOC to "excalidraw"
                ext == "json" && t4.contains("\"nbformat\"") -> return Kind.DOC to "ipynb"
                t4.trimStart().startsWith("-----BEGIN ") && (ext !in TEXT_EXT || ext in setOf("pem", "crt", "cer", "txt")) -> {
                    return Kind.DOC to (if (t4.contains("-----BEGIN PKCS7") || t4.contains("-----BEGIN CMS")) "p7m" else "cert")
                }
            }
        }
        if (declared == "text/html" || declared == "application/xhtml+xml" || ext == "html" || ext == "htm" || ext == "xhtml") return Kind.HTML to ""
        (EXT_FMT[ext] ?: MIME_FMT[declared])?.let { f ->
            // formati di testo: solo se sembrano davvero testo (un .md binario non e' Markdown)
            if (f == "geo" && ext == "kmz") return Kind.UNSUPPORTED to "" // un KMZ vero e' uno ZIP: gia' preso sopra
            return if (f !in TEXTUAL_FMT || text) Kind.DOC to f else Kind.UNSUPPORTED to ""
        }
        langForName(lowerName)?.let { l -> if (text) return Kind.TEXT to l }
        if (isTextMime(declared) || ext in TEXT_EXT) return if (text) Kind.TEXT to langFor(ext, declared) else Kind.UNSUPPORTED to ""
        if (isGeneric(declared) || declared.isBlank()) {
            if (head.isNotEmpty() && text) {
                val t = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1)
                if (Regex("^(?:[\\w-]+:.*\\r?\\n)+", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
                    Regex("^(mime-version|content-type|received|from|subject):", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).findAll(t).count() >= 2
                ) return Kind.DOC to "eml"
                if (Regex("^\\d+\\r?\\n\\d{1,2}:\\d\\d:\\d\\d[,.]\\d+ -->").containsMatchIn(t.trimStart())) return Kind.DOC to "srt"
                return Kind.TEXT to ""
            }
        }
        return Kind.UNSUPPORTED to ""
    }

    /** Niente NUL (salvo UTF-16 con BOM) e pochissimi caratteri di controllo. */
    fun looksLikeText(b: ByteArray): Boolean {
        if (b.isEmpty()) return true
        if (startsWith(b, 0xFF, 0xFE) || startsWith(b, 0xFE, 0xFF)) return true
        var ctrl = 0
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x09 || (c in 0x0E..0x1F && c != 0x1B)) ctrl++
        }
        return ctrl * 100 < b.size
    }
}
