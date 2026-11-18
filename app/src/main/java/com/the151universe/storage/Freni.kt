package com.the151universe.storage

/**
 * I freni della batteria (10/11: su un telefono vero 35% al giorno con 52 min di schermo, e chiusure ripetute). Solo conti, niente Android: così le prove girano sul PC (`app/src/test/.../FreniTest.kt`).
 *
 * - Giro delle anteprime: dopo un giro intero si riparte al più ogni 10 minuti (come prima); dopo un giro NON intero
 *   (lista del cloud rotta, rete, Android che lo ferma) non si riparte per 6 ore: prima ripartiva a ogni apertura,
 *   rileggendo tutto il cloud con il telefono sveglio.
 * - Impronta della lista: se il cloud è uguale all'ultima lista passata tutta, niente da rifare.
 * - Un file la cui anteprima fallisce 2 volte non si riprova per 7 giorni.
 * - Media e Docs rileggono MediaStore solo se è cambiato; il giro del disco al più ogni 6 ore.
 * - L'indice dei file caricati si scrive al più ogni 30 s (e sempre a fine coda).
 */
object Freni {
    /** Dopo un giro intero (regola di «poi solo le nuove»). */
    const val SWEEP_GAP = 10 * 60 * 1000L
    /** Dopo un giro rimasto a metà. */
    const val RETRY_GAP = 6 * 3_600_000L
    /** Fallimenti di fila dello stesso file prima del segno «non riprovare». */
    const val FAIL_MAX = 2
    /** Quanto dura il segno «non riprovare»: poi il file si riprova da capo. */
    const val FAIL_PAUSE = 7 * 86_400_000L
    /** Il giro di tutto il disco del telefono (Media, Docs). */
    const val WALK_GAP = 6 * 3_600_000L
    /** L'indice dei caricati su disco. */
    const val DONE_WRITE_GAP = 30_000L
    /** Un caricamento che fa cadere il suo lavoratore (errore non di rete) per la seconda volta: fallito. */
    const val UPLOAD_CRASH_MAX = 2

    /**
     * Si comincia un giro delle anteprime? [sameWho] = stesso cloud dell'ultimo giro; [doneAt] = fine dell'ultimo giro
     * intero; [triedAt] = fine dell'ultimo giro rimasto a metà (0 = mai).
     */
    fun sweepDue(now: Long, sameWho: Boolean, doneAt: Long, triedAt: Long): Boolean {
        if (!sameWho) return true
        if (now - doneAt < SWEEP_GAP) return false
        if (triedAt > doneAt && now - triedAt < RETRY_GAP) return false
        return true
    }

    /**
     * L'impronta della lista del cloud: quante voci, byte in tutto, data più recente (anche delle cartelle: cambia
     * quando dentro si aggiunge, toglie o rinomina) e una somma dei percorsi (un file spostato cambia percorso).
     */
    fun fingerprint(entries: List<CloudEntry>): String {
        var n = 0L; var bytes = 0L; var newest = 0L; var paths = 0L
        for (e in entries) {
            n++
            if (!e.isDir && e.size > 0) bytes += e.size
            if (e.mtime > newest) newest = e.mtime
            paths += e.path.trim('/').hashCode().toLong() and 0xffffffffL
        }
        return "$n:$bytes:$newest:$paths"
    }

    /** Il prossimo conto dei fallimenti di un file: un segno vecchio di [FAIL_PAUSE] riparte da 1. */
    fun nextFailCount(prev: Int, ageMs: Long): Int = if (prev <= 0 || ageMs >= FAIL_PAUSE || ageMs < 0) 1 else prev + 1

    /** Il giro salta il file? Sì se ha fallito [FAIL_MAX] volte e il segno non è scaduto. */
    fun failBlocks(count: Int, ageMs: Long): Boolean = count >= FAIL_MAX && ageMs in 0 until FAIL_PAUSE

    /** MediaStore va riletto? [token] null = Android senza «generazione» (prima della 11): si rilegge come prima. */
    fun mediaChanged(token: String?, saved: String?): Boolean = token == null || token != saved

    /**
     * Roba cancellata che ricompare: una scansione partita PRIMA di una cancellazione ha visto il
     * file ancora lì, e finendo lo rimetteva nell'elenco (e nella cache). Il suo risultato rispetta quello che l'app ha
     * fatto mentre girava: [removed] = cancellati nel frattempo (fuori, se il disco non li ha più), [restored] = rimessi
     * con «Annulla» nel frattempo (dentro, se il disco li ha).
     */
    fun <T> afterScan(result: List<T>, path: (T) -> String, removed: Set<String>, restored: List<T>, exists: (String) -> Boolean): List<T> {
        if (removed.isEmpty() && restored.isEmpty()) return result
        val kept = result.filter { val p = path(it); p !in removed || exists(p) }
        val have = kept.mapTo(HashSet()) { path(it) }
        return kept + restored.filter { val p = path(it); p !in have && exists(p) }
    }

    /** Il giro del disco del telefono è dovuto? */
    fun walkDue(now: Long, walkAt: Long): Boolean = now - walkAt >= WALK_GAP || walkAt > now

    /** L'indice dei caricati va scritto adesso? */
    fun doneWriteDue(now: Long, writtenAt: Long): Boolean = now - writtenAt >= DONE_WRITE_GAP

    /**
     * La coda dei caricamenti si pota (11/11): i finiti di più di una settimana escono; a coda ferma restano
     * 300 righe, ma i finiti degli ultimi 10 minuti restano (il «finito, 1.800 file nel cloud» li conta, 09/11).
     * Ritorna quante righe ha tolto. Prima si potava solo scrivendo: un giro finito alle 01:04 lasciava 8.164 righe
     * (2,1 MB) che ogni apertura rileggeva, perché a coda ferma nessuno scriveva più.
     */
    fun trimQueue(l: MutableList<UpJob>, now: Long): Int {
        val before = l.size
        val week = now - 7 * 86_400_000L
        l.removeAll { it.finished && it.time < week }
        if (l.none { it.state == UpJob.WAIT || it.state == UpJob.RUN }) {
            val recent = now - 600_000L
            val drop = l.count { it.finished } - maxOf(0, 300 - l.count { !it.finished })
            if (drop > 0) { var n = drop; l.removeAll { it.finished && it.time < recent && n-- > 0 } }
        }
        return before - l.size
    }

    /** Dopo un errore non di rete ([attemptsAfter] = tentativi contando questo): si riprova o è fallito? */
    fun crashRetry(attemptsAfter: Int): Boolean = attemptsAfter < UPLOAD_CRASH_MAX
}

/**
 * Una lettura alla volta, ma una richiesta arrivata DURANTE la lettura non si perde: si rifà alla fine (l'utente
 * 11/11 «roba cancellata che ricompare»). Prima `if (loading) return`: cancellavi in Media mentre la galleria si
 * leggeva, la rilettura chiesta dopo la cancellazione veniva buttata e restava l'elenco letto prima, col file dentro.
 */
class Rilettura {
    private var running = false
    private var pending = false

    /** Parto adesso? Se una lettura gira già: no, ma alla sua fine se ne fa un'altra. */
    @Synchronized fun start(): Boolean {
        if (running) { pending = true; return false }
        running = true
        return true
    }

    /** Qualcosa è cambiato (una cancellazione): se una lettura gira, quella che finisce non basta più. */
    @Synchronized fun dirty() { if (running) pending = true }

    /** Lettura finita. Vero = ne serve un'altra subito (si resta «in lettura»); falso = fermi. */
    @Synchronized fun finish(): Boolean {
        if (pending) { pending = false; return true }
        running = false
        return false
    }

    val busy: Boolean @Synchronized get() = running
}
