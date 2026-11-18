package com.the151universe.storage

import java.io.File

/**
 * Il cestino di Android visto da chi guarda.
 * Un file cancellato resta 30 giorni NELLA SUA cartella come «.trashed-<scadenza>-<nome>» (misurato sul telefono di prova,
 * Android 14): la cartella per il sistema non è vuota e non si toglie. Qui quei file non esistono: non si vedono,
 * non contano, e una cartella che tiene solo loro, a qualunque profondità, è vuota («fantasma»).
 * Solo java.io: si prova sulla JVM (TrashedTest).
 */
object Trashed {
    const val PREFIX = ".trashed-"
    /** Letture del disco (cartelle aperte + «è una cartella?») al più per una domanda: un albero enorme non ferma la lista. */
    const val BUDGET = 2000

    /** Finito il conto si risponde «c'è roba vera»: nel dubbio una cartella si mostra, mai si nasconde. */
    class Budget(var left: Int = BUDGET) { fun take(): Boolean = left-- > 0 }

    /** Il conto di una cartella: [visible] voci che si vedono, [trash] = dentro c'è almeno un file nel cestino. */
    class Look(val visible: Int, val trash: Boolean) { val ghost get() = visible == 0 && trash }

    /** Anche «.pending-<scadenza>-<nome>»: un salvataggio rimasto a metà (app chiusa mentre scriveva), invisibile a
     * ogni app e tolto da Android alla scadenza. Teneva in vita «Nausicaa…» in Download dopo che l'utente l'aveva
     * cancellata (telefono vero 12/11): per chi guarda è spazzatura come il cestino. */
    const val PENDING = ".pending-"
    fun isTrashed(name: String) = name.startsWith(PREFIX) || name.startsWith(PENDING)

    /** Nascosto per chi guarda: i nomi col punto (compresi i «.trashed-»), come la lista del Phone. */
    fun dotName(f: File) = f.name.startsWith('.')

    /** Quante voci di [dir] si vedono (le cartelle fantasma non contano) e se dentro c'è il cestino. null = illeggibile. */
    fun look(dir: File, b: Budget = Budget(), skip: (File) -> Boolean = ::dotName): Look? {
        val kids = dir.listFiles() ?: return null
        return lookKids(kids, b, skip)
    }

    fun lookKids(kids: Array<File>, b: Budget = Budget(), skip: (File) -> Boolean = ::dotName): Look {
        var n = 0
        var trash = false
        for (k in kids) {
            if (isTrashed(k.name)) { trash = true; continue }
            if (skip(k)) continue
            if (b.take() && k.isDirectory && ghost(k, b, skip)) { trash = true; continue }
            n++
        }
        return Look(n, trash)
    }

    /**
     * Dentro [dir], a ogni profondità, solo file nel cestino (almeno uno), nascosti e cartelle a loro volta fantasma.
     * Si ferma alla prima voce vera: una cartella normale costa una lettura o poche.
     */
    fun ghost(dir: File, b: Budget = Budget(), skip: (File) -> Boolean = ::dotName): Boolean {
        if (!b.take()) return false
        val kids = dir.listFiles() ?: return false
        var trash = false
        for (k in kids) {
            if (isTrashed(k.name)) { trash = true; continue }
            if (skip(k)) continue
            if (!b.take()) return false
            if (k.isDirectory && ghost(k, b, skip)) { trash = true; continue }
            return false
        }
        return trash
    }

    /** Nessun file, a nessuna profondità (solo cartelle vuote): Android ha già svuotato il cestino di questa cartella. */
    fun noFiles(dir: File, b: Budget = Budget()): Boolean {
        if (!b.take()) return false
        val kids = dir.listFiles() ?: return false
        return kids.all { it.isDirectory && noFiles(it, b) }
    }
}
