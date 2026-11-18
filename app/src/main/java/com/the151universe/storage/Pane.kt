package com.the151universe.storage

import android.view.View

/** Un'azione della barra in basso durante la selezione (spec §5). */
class SelAction(val label: Int, val icon: Int, val primary: Boolean = true, val run: () -> Unit)

/**
 * Un posto dell'app (Foto, Cartelle, Cloud). Documenti resta il codice del 151 Viewer dentro MainActivity.
 * MainActivity chiede al posto: la tinta della lastra (per la linguetta in basso), il menu della barra
 * in alto, cosa fare con «indietro», e le azioni quando c'è una selezione.
 */
abstract class Pane(val act: MainActivity) {
    abstract val root: View
    /** Il foglio che scivola cambiando posto: solo il contenuto, le linguette sopra restano ferme. */
    open val sheet: View get() = root
    abstract fun boardColor(): Int
    open val menuRes: Int = 0
    open fun onMenu(id: Int): Boolean = false
    open fun onShow() {}
    open fun onHide() {}
    open fun onResume() {}
    /** true = gestito (es. cartella su di un livello). */
    open fun onBack(): Boolean = false
    open val selectedCount: Int get() = 0
    open fun clearSelection() {}
    open fun selectionActions(): List<SelAction> = emptyList()
    /** «Seleziona tutto» (quello che si vede). */
    open fun selectAll() {}
    /** Ricerca dalla barra in alto: "" = nessuna. */
    open fun onQuery(q: String) {}
    open val searchHint: Int = R.string.search
    open val fab: Pair<Int, Int>? get() = null // (testo, icona) o null = nessun pulsante
    open fun onFab() {}
    open fun destroy() {}
}
