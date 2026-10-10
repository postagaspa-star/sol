package eu.stgm.sol.ui

import eu.stgm.sol.Esempi
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.aspetta
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.ui.posta.PostaViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit

/**
 * I gesti sulle righe della Posta (docs/aspetto.md §4): a destra letta ↔ da leggere,
 * a sinistra "Sposta in" con "Annulla" per rimetterla dov'era.
 */
class GestiTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val vm = PostaViewModel(server.client(), CacheMemoria())

    @After
    fun chiudi() = server.close()

    private val inFornitori = Esempi.MAIL
        .replace("\"gruppo_id\": 1, \"gruppo\": \"Clienti\"", "\"gruppo_id\": 2, \"gruppo\": \"Fornitori\"")
        .replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\"")
    private val diNuovoInClienti = Esempi.MAIL.replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\"")

    private fun posta(gruppoId: Long? = null) {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/conteggi", """{"da_rispondere": 5, "gruppi": {"1": {"da_leggere": 4}}}""")
        server.rispondi("GET /api/mail", Esempi.PAGINA_MAIL)
        server.rispondi("GET /api/mail?gruppo_id=1&limite=50", """{"mail": [${Esempi.MAIL}], "altre": false}""")
        if (gruppoId != null) vm.scegliGruppo(gruppoId) else vm.carica()
        aspetta("posta caricata") { vm.stato.value.caricataAlmenoUnaVolta && !vm.stato.value.caricamento }
    }

    private val rossi get() = vm.stato.value.mail.first { it.id == 812L }
    private val spostamenti get() = server.richiesteA("POST /api/mail/812/sposta").map { it.body.clone().readUtf8() }

    @Test
    fun `a sinistra - spostata in un altro gruppo esce dalla vista, Annulla la rimette al suo posto`() {
        posta(gruppoId = 1)
        server.inFila("POST /api/mail/812/sposta", inFornitori, diNuovoInClienti)
        val conteggiPrima = server.richiesteA("GET /api/conteggi").size

        assertTrue(vm.sposta(rossi, gruppoId = 2, impara = true))
        aspetta("spostata") { vm.stato.value.spostamento != null }
        val s = vm.stato.value
        assertTrue("fuori dalla vista di Clienti", s.mail.none { it.id == 812L })
        assertEquals("Fornitori", s.spostamento!!.nomeNuovo)
        assertTrue(s.spostamento!!.annullabile)
        assertNull(s.inSpostamento)
        assertEquals(listOf("""{"gruppo_id":2,"impara":true}"""), spostamenti)
        aspetta("i numeri si rileggono") { server.richiesteA("GET /api/conteggi").size > conteggiPrima }

        vm.annullaSpostamento()
        aspetta("rimessa") { vm.stato.value.mail.any { it.id == 812L } && vm.stato.value.inSpostamento == null }
        assertEquals("di nuovo in Clienti, ricordando il mittente (contratto 17)", """{"gruppo_id":1,"impara":true}""", spostamenti.last())
        assertEquals("al suo posto", 0, vm.stato.value.mail.indexOfFirst { it.id == 812L })
        assertNull(vm.stato.value.spostamento)
    }

    @Test
    fun `a sinistra - in Tutte la mail resta, col gruppo nuovo`() {
        posta()
        server.rispondi("POST /api/mail/812/sposta", inFornitori)
        vm.sposta(rossi, gruppoId = 2, impara = true)
        aspetta { vm.stato.value.spostamento != null }
        assertEquals(2L, rossi.gruppoId)
        assertEquals("Fornitori", rossi.gruppo)
        assertEquals(listOf(812L, 815L), vm.stato.value.mail.map { it.id })
    }

    @Test
    fun `a sinistra - senza Ricorda questo mittente anche Annulla non impara`() {
        posta()
        server.inFila("POST /api/mail/812/sposta", inFornitori, diNuovoInClienti)
        vm.sposta(rossi, gruppoId = 2, impara = false)
        aspetta { vm.stato.value.spostamento != null }
        vm.annullaSpostamento()
        aspetta { spostamenti.size == 2 && vm.stato.value.inSpostamento == null }
        assertEquals(listOf("""{"gruppo_id":2,"impara":false}""", """{"gruppo_id":1,"impara":false}"""), spostamenti)
        assertEquals(1L, rossi.gruppoId)
    }

    @Test
    fun `a sinistra - una alla volta, e lo stesso gruppo non e uno spostamento`() {
        posta()
        assertFalse(vm.sposta(rossi, gruppoId = 1, impara = true))
        // Un server lento: il primo spostamento è ancora in viaggio quando arriva il secondo gesto.
        server.rispondi(
            "POST /api/mail/812/sposta",
            MockResponse().setBody(inFornitori).setHeader("Content-Type", "application/json").setBodyDelay(400, TimeUnit.MILLISECONDS),
        )
        assertTrue(vm.sposta(rossi, gruppoId = 2, impara = true))
        val bianchi = vm.stato.value.mail.first { it.id == 815L }
        assertFalse(vm.sposta(bianchi, gruppoId = 6, impara = true))
        aspetta { vm.stato.value.inSpostamento == null && vm.stato.value.spostamento != null }
        assertEquals(1, spostamenti.size)
        assertTrue(server.richiesteA("POST /api/mail/815/sposta").isEmpty())
    }

    @Test
    fun `a sinistra - se il server dice no la mail resta e lo si dice`() {
        posta(gruppoId = 1)
        server.rispondi(
            "POST /api/mail/812/sposta",
            """{"errore": "Outlook non risponde. Riprova tra poco.", "codice": "casella_non_raggiungibile"}""",
            502,
        )
        vm.sposta(rossi, gruppoId = 2, impara = true)
        aspetta { vm.stato.value.erroreAzione != null }
        assertEquals("Outlook non risponde. Riprova tra poco.", vm.stato.value.erroreAzione)
        assertEquals(1L, rossi.gruppoId)
        assertNull(vm.stato.value.spostamento)
        assertNull(vm.stato.value.inSpostamento)
    }

    @Test
    fun `a sinistra - il messaggio sparito non si annulla piu`() {
        posta()
        server.rispondi("POST /api/mail/812/sposta", inFornitori)
        vm.sposta(rossi, gruppoId = 2, impara = true)
        aspetta { vm.stato.value.spostamento != null }
        vm.consumaSpostamento()
        vm.annullaSpostamento()
        Thread.sleep(100)
        assertEquals(1, spostamenti.size)
    }

    @Test
    fun `a destra - letta e da leggere, subito sullo schermo`() {
        posta()
        server.rispondi("POST /api/mail/812/letta", "{}")
        assertFalse(rossi.letta)
        vm.cambiaLetta(rossi)
        assertTrue("si vede subito", rossi.letta)
        aspetta { server.richiesteA("POST /api/mail/812/letta").size == 1 }
        assertEquals("""{"letta":true}""", server.richiesteA("POST /api/mail/812/letta").single().body.readUtf8())
        vm.cambiaLetta(rossi)
        aspetta { server.richiesteA("POST /api/mail/812/letta").size == 2 }
        assertEquals("""{"letta":false}""", server.richiesteA("POST /api/mail/812/letta").last().body.readUtf8())
        assertFalse(rossi.letta)
    }

    @Test
    fun `a destra - se il server dice no la riga torna com'era`() {
        posta()
        server.rispondi("POST /api/mail/812/letta", """{"errore": "Outlook non risponde. Riprova tra poco."}""", 502)
        vm.cambiaLetta(rossi)
        aspetta { vm.stato.value.erroreAzione != null }
        assertFalse(rossi.letta)
        assertEquals("Outlook non risponde. Riprova tra poco.", vm.stato.value.erroreAzione)
    }
}
