package com.the151universe.storage

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Un Context finto per le prove sul PC: `filesDir` = una cartella temporanea, nient'altro (dati anonimi fatti al momento). */
class TestCtx(private val files: File) : ContextWrapper(null) {
    override fun getFilesDir(): File = files.apply { mkdirs() }
    override fun getApplicationContext(): Context = this
}

/** Un cloud finto: ha solo un nome. Ogni altra domanda risponde null (le prove non vanno mai in rete). */
fun testDrive(label: String = "test-cloud"): CloudDrive =
    java.lang.reflect.Proxy.newProxyInstance(CloudDrive::class.java.classLoader, arrayOf(CloudDrive::class.java)) { p, m, a ->
        when (m.name) { "getLabel" -> label; "toString" -> "testDrive($label)"; "hashCode" -> label.hashCode(); "equals" -> p === a?.get(0); else -> null }
    } as CloudDrive
