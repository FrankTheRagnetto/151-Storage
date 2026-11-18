package com.the151universe.storage

/**
 * La notifica «in corso» di un lavoro in primo piano (chiusa l'app, «Making cloud previews» restava
 * ferma anche senza più Storage fra le app attive). Il difetto: l'avanzamento si mandava con `notify` da un filo che
 * poteva arrivare DOPO la fine del primo piano (tempo di Android finito, servizio distrutto, app chiusa): quella notifica
 * non era più del servizio, e restava orfana per sempre (in corso = non si scorre via). Qui: gli aggiornamenti passano
 * solo finché il lavoro tiene il primo piano; [end] li chiude una volta per tutte, toglie quella in corso e, se c'è,
 * mette la parola finale (che si scorre via). Puro Kotlin: provato sul PC (`ForegroundNoteTest`).
 */
class ForegroundNote<T : Any>(private val post: (T) -> Unit, private val remove: () -> Unit) {
    private var live = true

    val isLive: Boolean @Synchronized get() = live

    /** Avanzamento: mandato solo se il lavoro è ancora in primo piano. false = scartato (arrivato tardi). */
    @Synchronized fun update(n: T): Boolean {
        if (!live) return false
        post(n)
        return true
    }

    /** Fine del lavoro (finito, fermato, Android, app chiusa): via la notifica in corso, poi [final] se c'è. Conta solo la prima. */
    @Synchronized fun end(final: T? = null): Boolean {
        if (!live) return false
        live = false
        try { remove() } finally { final?.let(post) }
        return true
    }
}
