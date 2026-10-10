package eu.stgm.sol.revisione

import eu.stgm.sol.DepositoMemoria
import eu.stgm.sol.Esempi
import eu.stgm.sol.MemoriaNovitaFinta
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.aspetta
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.notifiche.AVVISO_SCOLLEGATO
import eu.stgm.sol.notifiche.Avviso
import eu.stgm.sol.notifiche.EsitoGiro
import eu.stgm.sol.notifiche.GiroNovita
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.ui.GestoreBozza
import eu.stgm.sol.ui.dettaglio.DettaglioViewModel
import eu.stgm.sol.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.sol.ui.posta.PostaViewModel
import eu.stgm.sol.ui.riassunti.RiassuntiViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Le dimostrazioni della revisione avversaria, ROVESCIATE: ognuna descriveva un
 * difetto (e passava finché c'era). Qui dicono il comportamento giusto: falliscono
 * sul codice di prima, passano su quello corretto.
 */
class RevisioneTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val client = server.client()
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun chiudi() {
        ambito.cancel()
        server.close()
    }

    private fun gestoreConBozzaPronta(verificaPerMs: Long = 2_000): GestoreBozza {
        server.rispondi("GET /api/bozze/55", Esempi.bozza())
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO)
        val g = GestoreBozza(client, ambito, intervalloMs = 20, verificaOgniMs = 20, verificaPerMs = verificaPerMs)
        g.imposta(55)
        aspetta("bozza pronta") { g.stato.value.bozza?.stato == "pronta" }
        return g
    }

    private fun apriConferma(g: GestoreBozza) {
        g.chiediInvio()
        aspetta("conferma") { g.stato.value.confermaInvio != null }
    }

    // R1 ------------------------------------------------------------------------------------
    @Test
    fun `R1 un 500 durante l'invio non diventa 'NON e partita' - si ricontrolla e la bozza dice che e partita`() {
        val g = gestoreConBozzaPronta()
        apriConferma(g)
        // Il 500 come lo manda il server dopo che la casella ha accettato la mail.
        server.rispondi(
            "POST /api/bozze/55/invia",
            """{"errore": "Sol ha avuto un problema interno. Riprova tra poco.", "codice": "errore_interno"}""",
            500,
        )
        server.rispondi("GET /api/bozze/55", Esempi.bozza("inviata"))
        g.confermaInvio()
        aspetta("ricontrollo") { g.stato.value.bozza?.stato == "inviata" && !g.stato.value.verificaInvio }
        assertFalse(g.stato.value.errore.orEmpty().contains("NON è partita"))
        assertNull(g.stato.value.errore)
        assertEquals(GestoreBozza.VERIFICATA_PARTITA, g.stato.value.messaggio)
    }

    // R1b -----------------------------------------------------------------------------------
    @Test
    fun `R1b un 502 ambiguo mostra la frase del server senza prefisso e tiene Invia spento`() {
        val g = gestoreConBozzaPronta(verificaPerMs = 5_000)
        apriConferma(g)
        val frase = "Non riesco a capire se la mail è già partita. Guarda in Posta inviata prima di riprovare."
        server.rispondi("POST /api/bozze/55/invia", """{"errore": "$frase", "codice": "casella_non_raggiungibile"}""", 502)
        server.rispondi("GET /api/bozze/55", Esempi.bozza("errore"))
        g.confermaInvio()
        aspetta("stato errore") { g.stato.value.bozza?.stato == "errore" && !g.stato.value.inCorso }
        assertEquals(frase, g.stato.value.errore)
        assertTrue("Invia resta spento mentre si ricontrolla", g.stato.value.verificaInvio)
        assertFalse(g.stato.value.invioPossibile)
    }

    // R2 ------------------------------------------------------------------------------------
    @Test
    fun `R2 la conferma rilegge la bozza - mostra quella cambiata dal PC, e invia quella`() {
        val g = gestoreConBozzaPronta()
        // Dal PC la bozza cambia: aggiunto un Cc e riscritto il testo (versione nuova).
        val dalPc = Esempi.bozza(testo = "Testo riscritto dal PC", versione = 4, cc = "\"bianchi@esempio.it\"")
        server.rispondi("GET /api/bozze/55", dalPc)
        server.rispondi("POST /api/bozze/55/invia", dalPc.replace("\"pronta\"", "\"inviata\""))
        val letturePrima = server.richiesteA("GET /api/bozze/55").size

        apriConferma(g)
        val finestra = g.stato.value.confermaInvio!!
        g.confermaInvio()
        aspetta("inviata") { g.stato.value.bozza?.stato == "inviata" }

        assertEquals(listOf("bianchi@esempio.it"), finestra.cc)
        assertEquals("Testo riscritto dal PC", finestra.primaRiga)
        assertTrue("riletta tra il tocco e la conferma", server.richiesteA("GET /api/bozze/55").size > letturePrima)
        assertEquals("""{"conferma":true,"versione":4}""", server.richiesteA("POST /api/bozze/55/invia").single().body.readUtf8())
        assertEquals(finestra.cc, g.stato.value.bozza!!.cc)
    }

    // R3 ------------------------------------------------------------------------------------
    @Test
    fun `R3 il testo della bozza in modifica resta aprendo un'altra mail da una notifica`() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO)
        server.rispondi("GET /api/mail/815", Esempi.MAIL_BIANCHI)
        server.rispondi("GET /api/bozze/55", Esempi.bozza())
        val vm = DettaglioViewModel(client, CacheMemoria(), intervalloBozzaMs = 20)
        vm.apri(812)
        aspetta("bozza") { vm.bozza.stato.value.bozza != null }
        vm.bozza.avviaModifica()
        vm.bozza.cambiaCampi(vm.bozza.stato.value.campi!!.copy(testo = "Dieci minuti di testo scritto in cantiere"))
        assertTrue("prima di cambiare mail l'app sa di dover chiedere", vm.haModificheNonSalvate())

        vm.apri(815) // tocco sulla notifica di una mail urgente
        aspetta("altra mail") { vm.stato.value.mail?.id == 815L && !vm.stato.value.caricamento }
        vm.apri(812) // torna alla mail di Rossi
        aspetta("bozza riletta") { vm.bozza.stato.value.bozza != null && !vm.bozza.stato.value.caricamento }

        assertEquals("Dieci minuti di testo scritto in cantiere", vm.bozza.stato.value.campi?.testo)
    }

    // R4 ------------------------------------------------------------------------------------
    @Test
    fun `R4 senza server il riassunto smette di ricontrollare e lo dice, e fuori schermata si ferma`() {
        server.rispondi("POST /api/riassunti", """{"id": 9, "stato": "in_coda"}""", 202)
        server.rispondi("GET /api/riassunti/9", MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        val vm = RiassuntiViewModel(client, CacheMemoria(), intervalloMs = 10)
        vm.cambiaRichiesta("le mail di Rossi")
        vm.chiedi()
        Thread.sleep(1_500)
        val giri = server.richiesteA("GET /api/riassunti/9").size
        assertTrue("richieste in 1,5 s: $giri", giri <= 3)
        assertEquals(RiassuntiViewModel.SENZA_SERVER, vm.stato.value.erroreAzione)
        assertFalse(vm.stato.value.inCorso)

        // Server tornato, riassunto ancora in corso: lo si segue solo con la schermata aperta.
        server.rispondi("GET /api/riassunti/9", """{"id": 9, "stato": "in_corso", "testo_parziale": "Rossi ha"}""")
        vm.riprendi()
        aspetta { server.richiesteA("GET /api/riassunti/9").size > giri }
        vm.sospendi()
        Thread.sleep(100)
        val dopoUscita = server.richiesteA("GET /api/riassunti/9").size
        Thread.sleep(300)
        assertEquals("fuori schermata nessun controllo", dopoUscita, server.richiesteA("GET /api/riassunti/9").size)
    }

    // R5 ------------------------------------------------------------------------------------
    @Test
    fun `R5 dopo un 401 la posta salvata sparisce, il telefono si scollega e la frase dice cosa fare`() {
        val cache = CacheMemoria()
        var dimenticato = false
        val revocabile = server.client(alNonAutorizzato = {
            cache.svuota()
            dimenticato = true
        })
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        server.rispondi("GET /api/mail", Esempi.PAGINA_MAIL)
        val vm = PostaViewModel(revocabile, cache)
        vm.carica()
        aspetta("posta") { vm.stato.value.mail.size == 2 && !vm.stato.value.caricamento }

        val revocato = """{"errore": "Questo dispositivo non è abbinato o il suo accesso è stato revocato.", "codice": "non_autorizzato"}"""
        listOf("GET /api/mail", "GET /api/gruppi", "GET /api/impostazioni").forEach { server.rispondi(it, revocato, 401) }
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.0.0", "autenticato": false}""")
        vm.carica()
        aspetta("401") { vm.stato.value.codiceErrore == "non_autorizzato" && !vm.stato.value.caricamento }

        assertTrue(vm.stato.value.mail.isEmpty())
        assertEquals(Testi.NON_AUTORIZZATO, vm.stato.value.errore)
        assertTrue(vm.stato.value.errore!!.contains("abbinamento"))
        assertTrue(dimenticato)
        assertNull(cache.leggi("mail-tutte-TUTTE"))
    }

    // R6 ------------------------------------------------------------------------------------
    @Test
    fun `R6 telefono revocato - il controllo novita lo dice con una notifica`() = runBlocking {
        val memoria = MemoriaNovitaFinta().apply { cursore = "100" }
        val mostrati = mutableListOf<Avviso>()
        server.rispondi(
            "GET /api/novita?dal=100",
            """{"errore": "Questo dispositivo non è abbinato o il suo accesso è stato revocato.", "codice": "non_autorizzato"}""",
            401,
        )
        val esito = GiroNovita(client, memoria) { mostrati += it }.giro()
        assertEquals(EsitoGiro.NON_AUTORIZZATO, esito)
        assertEquals(listOf(AVVISO_SCOLLEGATO), mostrati)
    }

    // R7 ------------------------------------------------------------------------------------
    @Test
    fun `R7 bozze_pronte e il totale - da solo non notifica, la stessa notifica non torna a ogni giro`() = runBlocking {
        val memoria = MemoriaNovitaFinta().apply { cursore = "100" }
        val mostrati = mutableListOf<Avviso>()
        val giro = GiroNovita(client, memoria) { mostrati += it }
        server.rispondi("GET /api/novita?dal=100", """{"cursore": "101", "bozze_pronte": 3}""")
        server.rispondi("GET /api/novita?dal=101", """{"cursore": "102", "bozze_pronte": 3}""")
        server.rispondi("GET /api/novita?dal=102", """{"cursore": "103", "bozze_pronte": 3}""")
        repeat(3) { giro.giro() }
        assertTrue(mostrati.isEmpty())
    }

    // R8 ------------------------------------------------------------------------------------
    @Test
    fun `R8 la pagina 'piu vecchie' ancora in arrivo non finisce dentro il filtro Urgenti`() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        server.rispondi("GET /api/mail?limite=50", Esempi.PAGINA_MAIL)
        val vecchia = Esempi.MAIL_BIANCHI.replace("\"id\": 815", "\"id\": 700") // urgenza normale
        server.rispondi(
            "GET /api/mail?limite=50&prima_di=815",
            MockResponse().setBody("""{"mail": [$vecchia], "altre": false}""")
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )
        server.rispondi("GET /api/mail?urgenza=alta&limite=50", """{"mail": [${Esempi.MAIL}], "altre": false}""")
        val vm = PostaViewModel(client, CacheMemoria())
        vm.carica()
        aspetta("prima pagina") { vm.stato.value.mail.size == 2 && !vm.stato.value.caricamento }

        vm.caricaAltre() // "Mostra mail più vecchie"
        vm.scegliFiltro(FiltroRapido.URGENTI) // subito dopo, il filtro Urgenti
        aspetta("tutto arrivato") { !vm.stato.value.caricamentoAltre && !vm.stato.value.caricamento }
        Thread.sleep(600) // anche quando la pagina lenta sarebbe arrivata

        val s = vm.stato.value
        assertEquals(FiltroRapido.URGENTI, s.filtro)
        assertFalse(s.mail.any { it.id == 700L })
        assertTrue(s.mail.all { it.urgente })
    }

    // R9 ------------------------------------------------------------------------------------
    @Test
    fun `R9 'Scollega questo telefono' revoca il token sul server prima di dimenticarlo`() {
        val deposito = DepositoMemoria(Configurazione(server.url, "x", 3))
        server.rispondi("DELETE /api/dispositivi/3", "", 500)
        val vm = ImpostazioniViewModel(client, CacheMemoria(), deposito)
        vm.scollegaQuesto()
        aspetta("errore") { vm.stato.value.erroreAzione != null }
        assertFalse("il server non ha revocato: niente scollegamento a metà", vm.stato.value.scollegato)
        assertTrue(vm.stato.value.erroreAzione!!.contains("Non sono riuscito a scollegare"))
        assertTrue(runBlocking { deposito.leggi() } != null)

        server.rispondi("DELETE /api/dispositivi/3", """{"ok": true}""")
        vm.scollegaQuesto()
        aspetta("scollegato") { vm.stato.value.scollegato }
        assertEquals(2, server.richiesteA("DELETE /api/dispositivi/3").size)
        assertNull(runBlocking { deposito.leggi() })
    }
}
