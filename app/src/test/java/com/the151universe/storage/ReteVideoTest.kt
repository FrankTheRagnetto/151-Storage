package com.the151universe.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I video del Cloud passano da LocalStream (http://127.0.0.1): la configurazione di rete delle CONSEGNE deve
 * permetterlo, se no ogni video aspetta 30 s di tentativi (telefono di prova 15/11). Si legge il file vero del progetto.
 */
class ReteVideoTest {
    private fun xml(nome: String): String {
        val f = listOf("src/main/res/xml/$nome", "app/src/main/res/xml/$nome").map(::File).first { it.exists() }
        return f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test fun laConsegnaLasciaParlareIlServerVideoLocale() {
        val x = xml("network_security_config.xml")
        val chiaro = Regex("""<domain-config cleartextTrafficPermitted="true">(.*?)</domain-config>""", RegexOption.DOT_MATCHES_ALL).find(x)
        assertTrue("manca il blocco in chiaro", chiaro != null)
        assertTrue("127.0.0.1 non permesso: i video del Cloud aspettano 30 s",
            Regex("""<domain includeSubdomains="false">127\.0\.0\.1</domain>""").containsMatchIn(chiaro!!.groupValues[1]))
        // e il resto resta chiuso
        assertTrue(x.contains("""<base-config cleartextTrafficPermitted="false" />"""))
    }
}
