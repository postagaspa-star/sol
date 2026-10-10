package eu.stgm.sol.rete

import eu.stgm.sol.rete.IndirizzoServer.Risultato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndirizzoServerTest {

    private fun valido(grezzo: String) = (IndirizzoServer.normalizza(grezzo) as Risultato.Valido).url

    @Test
    fun `gli esempi del contratto passano come sono`() {
        assertEquals("https://sol.esempio.ts.net", valido("https://sol.esempio.ts.net"))
        assertEquals("http://192.168.1.51:8200", valido("http://192.168.1.51:8200"))
    }

    @Test
    fun `senza schema, https fuori casa e http in casa`() {
        assertEquals("https://sol.esempio.ts.net", valido("sol.esempio.ts.net/"))
        assertEquals("http://192.168.1.51:8200", valido(" 192.168.1.51:8200 "))
        // Un nome non è un indirizzo di casa: senza schema diventa https.
        assertEquals("https://nas.local:8200", valido("nas.local:8200"))
    }

    @Test
    fun `query, frammenti e credenziali incollati per sbaglio spariscono`() {
        assertEquals("https://sol.esempio.ts.net", valido("https://utente:segreto@sol.esempio.ts.net/?x=1#y"))
        assertEquals("https://sol.esempio.ts.net/sol", valido("https://sol.esempio.ts.net/sol/"))
    }

    @Test
    fun `http fuori dalla rete privata di casa no, il token viaggerebbe in chiaro`() {
        listOf(
            "http://sol.esempio.it",
            "http://nas.local:8200",
            "http://nas.lan:8200",
            "http://100.101.102.103:8200",
            "http://8.8.8.8",
        ).forEach { assertTrue(it, IndirizzoServer.normalizza(it) is Risultato.NonValido) }
        assertTrue(IndirizzoServer.normalizza("") is Risultato.NonValido)
        assertTrue(IndirizzoServer.normalizza("ftp://sol.esempio.it") is Risultato.NonValido)
    }

    @Test
    fun `rete privata di casa secondo il contratto 15_13`() {
        listOf("192.168.1.51", "10.0.0.2", "172.16.0.1", "172.31.255.1").forEach {
            assertTrue(it, IndirizzoServer.diCasa(it))
        }
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.101.102.103", "localhost", "nas.local", "nas.lan", "192.168.1")
            .forEach { assertFalse(it, IndirizzoServer.diCasa(it)) }
    }

    @Test
    fun `password solo sul collegamento protetto`() {
        assertTrue(IndirizzoServer.passwordConsentita("https://sol.esempio.ts.net"))
        assertFalse(IndirizzoServer.passwordConsentita("http://192.168.1.51:8200"))
        assertFalse(IndirizzoServer.passwordConsentita(null))
        assertTrue(IndirizzoServer.nonProtetto("http://192.168.1.51:8200"))
        assertFalse(IndirizzoServer.nonProtetto("https://sol.esempio.ts.net"))
    }
}
