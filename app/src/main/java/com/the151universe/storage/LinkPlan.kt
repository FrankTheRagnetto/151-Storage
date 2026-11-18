package com.the151universe.storage

/**
 * Cartelle collegate: una cartella del telefono e la sua gemella nel cloud restano uguali nei due sensi.
 * Qui SOLO la decisione, senza rete né disco (si prova sul PC, LinkPlanTest): chi è cambiato rispetto all'ultima volta
 * che erano uguali ([base]), e quindi cosa si fa.
 *
 * Le leggi:
 * - La prima volta (base assente) non si cancella MAI: si unisce. Un file diverso nei due posti = si tengono tutti e due.
 * - Cambiato da una parte sola → va dall'altra. Cambiato da tutte e due → si tengono tutti e due ([Act.KEEP_BOTH]).
 * - Cancellato da una parte e non toccato dall'altra → si cancella anche lì (nel cestino, mai per sempre: lo fa chi esegue).
 * - Cancellato da una parte e cambiato dall'altra → vince chi è cambiato: il file torna.
 * - Stessa dimensione ma date diverse senza base, o cambiati tutti e due con la stessa dimensione → [Act.SAME_CHECK]:
 *   chi esegue confronta il contenuto e decide fra «uguali» ([Act.ADOPT]) e [Act.KEEP_BOTH].
 */
object LinkPlan {
    /** Dimensione e data (ms) come le ha viste l'elenco. */
    data class Meta(val size: Long, val mtime: Long)
    /** Com'erano i due lati l'ultima volta che sono stati uguali. */
    data class Base(val local: Meta, val remote: Meta)

    enum class Act { UPLOAD, DOWNLOAD, DEL_REMOTE, DEL_LOCAL, KEEP_BOTH, SAME_CHECK, ADOPT, FORGET }
    data class Step(val path: String, val act: Act)

    /** Le date del telefono sono in millisecondi, quelle del cloud spesso in secondi: sotto i 2 s sono la stessa data. */
    private fun sameTime(a: Long, b: Long) = kotlin.math.abs(a - b) < 2_000

    /**
     * [local] e [remote]: percorso relativo alla cartella collegata («Personal/Logs/x.md») → com'è adesso.
     * [base] null = prima volta (si unisce soltanto). Torna i passi da fare, in ordine di percorso.
     */
    fun plan(local: Map<String, Meta>, remote: Map<String, Meta>, base: Map<String, Base>?): List<Step> {
        val out = ArrayList<Step>()
        val all = java.util.TreeSet<String>().apply { addAll(local.keys); addAll(remote.keys); base?.let { addAll(it.keys) } }
        for (p in all) {
            val l = local[p]; val r = remote[p]; val b = base?.get(p)
            val act: Act? = when {
                l != null && r != null -> when {
                    b == null -> when {
                        l.size == r.size && sameTime(l.mtime, r.mtime) -> Act.ADOPT
                        l.size == r.size -> Act.SAME_CHECK
                        else -> Act.KEEP_BOTH
                    }
                    else -> {
                        val lc = l != b.local; val rc = r != b.remote
                        when {
                            !lc && !rc -> null
                            lc && !rc -> Act.UPLOAD
                            !lc && rc -> Act.DOWNLOAD
                            l.size == r.size -> Act.SAME_CHECK
                            else -> Act.KEEP_BOTH
                        }
                    }
                }
                l != null -> when {
                    b == null -> Act.UPLOAD
                    l != b.local -> Act.UPLOAD // cancellato lassù ma cambiato qui: torna
                    else -> Act.DEL_LOCAL
                }
                r != null -> when {
                    b == null -> Act.DOWNLOAD
                    r != b.remote -> Act.DOWNLOAD // cancellato qui ma cambiato lassù: torna
                    else -> Act.DEL_REMOTE
                }
                else -> Act.FORGET // sparito da tutte e due: solo la memoria
            }
            if (act != null) out += Step(p, act)
        }
        return out
    }

    /** Quante cancellazioni in un giro fanno tirare la sicura (20). */
    const val MAX_DELETE = 20

    fun deletions(steps: List<Step>) = steps.count { it.act == Act.DEL_LOCAL || it.act == Act.DEL_REMOTE }

    /** Il nome della copia che si tiene accanto: «nota (conflict phone 2026-11-13).md». */
    fun conflictName(path: String, side: String, day: String): String {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val n = "${name.substring(0, dot)} (conflict $side $day)${name.substring(dot)}"
        return if (dir.isEmpty()) n else "$dir/$n"
    }
}
