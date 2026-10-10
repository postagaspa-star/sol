package eu.stgm.sol.ui

import eu.stgm.sol.Esempi
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.aspetta
import eu.stgm.sol.dati.CacheMemoria
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regola più importante di Sol: nessuna mail parte senza un sì esplicito, e
 * parte solo la versione che Manuel ha visto (15.11). E un esito incerto non si
 * spaccia mai per "non è partita".
 */
class GestoreBozzaTest {
    private val server = ServerFinto()
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val inviate = mutableListOf<Long>()
    private val memoria = CacheMemoria()
    private val gestore = nuovoGestore()

    private fun nuovoGestore(verificaPerMs: Long = 300) = GestoreBozza(
        server.client(),
        ambito,
        intervalloMs = 20,
        memoria = memoria,
        verificaOgniMs = 20,
        verificaPerMs = verificaPerMs,
        dopoInvio = { inviate += it.id },
    )

    private val invii get() = server.richiesteA("POST /api/bozze/55/invia")

    @After
    fun chiudi() {
        ambito.cancel()
        server.close()
    }

    private fun bozzaPronta(g: GestoreBozza = gestore, json: String = Esempi.bozza()) {
        server.rispondi("GET /api/bozze/55", json)
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO)
        g.imposta(55)
        aspetta("bozza caricata") { g.stato.value.bozza?.stato == "pronta" }
    }

    private fun apriConferma(g: GestoreBozza = gestore): RichiestaConferma {
        g.chiediInvio()
        aspetta("conferma pronta") { g.stato.value.confermaInvio != null || g.stato.value.errore != null }
        return g.stato.value.confermaInvio!!
    }

    // --- Conferma in due tempi ----------------------------------------------------------

    @Test
    fun `confermare senza aver chiesto non invia niente`() {
        bozzaPronta()
        gestore.confermaInvio()
        Thread.sleep(100)
        assertTrue(invii.isEmpty())
    }

    @Test
    fun `la conferma rilegge la bozza e mostra A, Cc, oggetto e prima riga di quella versione`() {
        bozzaPronta()
        // Intanto dal PC la bozza è cambiata: la conferma deve mostrare quella nuova.
        server.rispondi(
            "GET /api/bozze/55",
            Esempi.bozza(testo = "Buongiorno Mario,\ngiovedì va bene.", versione = 4, cc = "\"bianchi@esempio.it\""),
        )
        val letturePrima = server.richiesteA("GET /api/bozze/55").size
        val conferma = apriConferma()
        assertTrue(server.richiesteA("GET /api/bozze/55").size > letturePrima)
        assertEquals(listOf("rossi@esempio.it"), conferma.a)
        assertEquals(listOf("bianchi@esempio.it"), conferma.cc)
        assertEquals("Re: Offerta capannone Bassano", conferma.oggetto)
        assertEquals("Buongiorno Mario,", conferma.primaRiga)
        assertEquals(4, conferma.versione)
        assertTrue("chiedere la conferma non invia", invii.isEmpty())
    }

    @Test
    fun `annullare la conferma non invia`() {
        bozzaPronta()
        apriConferma()
        gestore.annullaInvio()
        gestore.confermaInvio()
        Thread.sleep(100)
        assertTrue(invii.isEmpty())
        assertNull(gestore.stato.value.confermaInvio)
    }

    @Test
    fun `dopo il si parte un solo invio, con conferma e la versione vista`() {
        bozzaPronta()
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata"))
        apriConferma()
        gestore.confermaInvio()
        gestore.confermaInvio() // un secondo tocco non rimanda
        aspetta("bozza inviata") { gestore.stato.value.bozza?.stato == "inviata" }
        assertEquals(1, invii.size)
        assertEquals("""{"conferma":true,"versione":3}""", invii.single().body.readUtf8())
        assertEquals(listOf(55L), inviate)
        assertTrue(gestore.stato.value.messaggio!!.contains("rossi@esempio.it"))
    }

    @Test
    fun `testo vuoto - niente conferma e niente invio`() {
        bozzaPronta(json = Esempi.bozza(testo = "   "))
        gestore.chiediInvio()
        aspetta { !gestore.stato.value.preparazioneConferma && gestore.stato.value.errore != null }
        assertNull(gestore.stato.value.confermaInvio)
        assertEquals(GestoreBozza.SENZA_TESTO, gestore.stato.value.errore)
        assertTrue(invii.isEmpty())
    }

    @Test
    fun `senza versione dal server non si chiede nemmeno la conferma`() {
        bozzaPronta(json = Esempi.bozza().replace("\"versione\": 3,", ""))
        gestore.chiediInvio()
        aspetta { !gestore.stato.value.preparazioneConferma && gestore.stato.value.errore != null }
        assertNull(gestore.stato.value.confermaInvio)
        assertTrue(gestore.stato.value.errore!!.contains("aggiornato"))
    }

    @Test
    fun `mail gia risposta da un altro programma - la conferma lo dice`() {
        bozzaPronta()
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO_GIA_RISPOSTA)
        assertTrue(apriConferma().giaRisposto)
    }

    @Test
    fun `durante la conferma i campi sono bloccati`() {
        bozzaPronta()
        apriConferma()
        gestore.avviaModifica()
        assertFalse(gestore.stato.value.inModifica)
        assertTrue(gestore.stato.value.campiBloccati)
        gestore.scarta()
        Thread.sleep(50)
        assertTrue(server.richiesteA("POST /api/bozze/55/scarta").isEmpty())
    }

    @Test
    fun `409 per bozza cambiata - si ripresenta la conferma con la versione nuova`() {
        bozzaPronta()
        apriConferma()
        val nuova = Esempi.bozza(testo = "Testo riscritto dal PC", versione = 5)
        server.rispondi(
            "POST /api/bozze/55/invia",
            """{"errore": "La risposta è cambiata dopo che l'hai controllata: ricontrollala prima di inviarla.",
               "codice": "conflitto", "bozza": $nuova}""",
            409,
        )
        gestore.confermaInvio()
        aspetta("conferma nuova") { gestore.stato.value.confermaInvio?.cambiata == true }
        val conferma = gestore.stato.value.confermaInvio!!
        assertEquals(5, conferma.versione)
        assertEquals("Testo riscritto dal PC", conferma.primaRiga)
        assertNull("niente è partito, nessun errore da mostrare", gestore.stato.value.errore)
        assertTrue(inviate.isEmpty())

        server.rispondi("POST /api/bozze/55/invia", nuova.replace("\"pronta\"", "\"inviata\""))
        gestore.confermaInvio()
        aspetta { gestore.stato.value.bozza?.stato == "inviata" }
        assertEquals("""{"conferma":true,"versione":5}""", invii.last().body.readUtf8())
    }

    @Test
    fun `gia_inviata - era gia partita e non e stata rimandata`() {
        bozzaPronta()
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata").trimEnd().removeSuffix("}") + """, "gia_inviata": true}""")
        apriConferma()
        gestore.confermaInvio()
        aspetta { gestore.stato.value.bozza?.stato == "inviata" }
        assertEquals(GestoreBozza.GIA_INVIATA, gestore.stato.value.messaggio)
    }

    // --- Esiti certi e incerti ----------------------------------------------------------

    @Test
    fun `422 - di sicuro non e partita e si dice`() {
        bozzaPronta()
        server.rispondi("POST /api/bozze/55/invia", """{"errore": "Manca il destinatario.", "codice": "non_valido"}""", 422)
        apriConferma()
        gestore.confermaInvio()
        aspetta { !gestore.stato.value.inCorso && gestore.stato.value.errore != null }
        assertEquals("La mail NON è partita. Manca il destinatario.", gestore.stato.value.errore)
        assertFalse(gestore.stato.value.verificaInvio)
    }

    @Test
    fun `casella che non risponde - la frase del server senza prefisso, Invia spento finche si ricontrolla`() {
        val g = nuovoGestore(verificaPerMs = 300)
        bozzaPronta(g)
        server.rispondi(
            "POST /api/bozze/55/invia",
            """{"errore": "Gmail non risponde. Riprova tra poco.", "codice": "casella_non_raggiungibile"}""",
            502,
        )
        server.rispondi("GET /api/bozze/55", Esempi.bozza("errore"))
        apriConferma(g)
        g.confermaInvio()
        aspetta { !g.stato.value.inCorso && g.stato.value.errore != null }
        assertEquals("Gmail non risponde. Riprova tra poco.", g.stato.value.errore)
        assertTrue("Invia resta spento", g.stato.value.verificaInvio)
        assertFalse(g.stato.value.invioPossibile)
        // Finita la verifica, la bozza non risulta partita: si può riprovare.
        aspetta("fine verifica") { !g.stato.value.verificaInvio }
        assertEquals(GestoreBozza.NON_RISULTA_PARTITA, g.stato.value.errore)
        assertTrue(g.stato.value.invioPossibile)
        assertTrue(inviate.isEmpty())
    }

    @Test
    fun `server che non risponde all'invio - si ricontrolla e la bozza dice che e partita`() {
        bozzaPronta()
        server.rispondi("POST /api/bozze/55/invia", "<html>Gateway Timeout</html>", 504)
        apriConferma()
        // La casella l'aveva accettata: rileggendo la bozza risulta inviata.
        server.rispondi("GET /api/bozze/55", Esempi.bozza("inviata"))
        gestore.confermaInvio()
        aspetta { gestore.stato.value.bozza?.stato == "inviata" && !gestore.stato.value.verificaInvio }
        assertNull(gestore.stato.value.errore)
        assertEquals(GestoreBozza.VERIFICATA_PARTITA, gestore.stato.value.messaggio)
        assertEquals(listOf(55L), inviate)
    }

    @Test
    fun `invio riuscito anche senza bozza nella risposta`() {
        bozzaPronta()
        apriConferma()
        server.rispondi("POST /api/bozze/55/invia", "", 200)
        server.rispondi("GET /api/bozze/55", "", 500)
        gestore.confermaInvio()
        aspetta { gestore.stato.value.bozza?.stato == "inviata" }
        assertNull(gestore.stato.value.errore)
        assertEquals(listOf(55L), inviate)
    }

    // --- Rotazione, cambio di mail, chiusura dell'app -----------------------------------

    @Test
    fun `rotazione con la conferma aperta - la finestra si ricostruisce coi dati riletti`() {
        bozzaPronta()
        apriConferma()
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = "Cambiata dal PC", versione = 4))
        gestore.ricostruisciConferma()
        aspetta { gestore.stato.value.confermaInvio?.versione == 4 }
        assertEquals("Cambiata dal PC", gestore.stato.value.confermaInvio!!.primaRiga)
        assertTrue(gestore.stato.value.confermaInvio!!.cambiata)
    }

    @Test
    fun `chiusura dell'app con la conferma aperta - al ritorno si ricostruisce`() {
        bozzaPronta()
        apriConferma()
        val dopo = nuovoGestore() // processo nuovo, stessa memoria su file
        dopo.imposta(55)
        aspetta { dopo.stato.value.confermaInvio != null }
        assertEquals(3, dopo.stato.value.confermaInvio!!.versione)
    }

    @Test
    fun `il testo in modifica resta per quella bozza, anche dopo un'altra e dopo un riavvio`() {
        bozzaPronta()
        gestore.avviaModifica()
        gestore.cambiaCampi(gestore.stato.value.campi!!.copy(testo = "Scritto in cantiere"))

        server.rispondi("GET /api/bozze/56", Esempi.bozza(id = 56))
        gestore.imposta(56)
        aspetta { gestore.stato.value.bozza?.id == 56L }
        assertNull(gestore.stato.value.campi)

        gestore.imposta(55)
        aspetta { gestore.stato.value.bozza?.id == 55L && !gestore.stato.value.caricamento }
        assertEquals("Scritto in cantiere", gestore.stato.value.campi?.testo)

        val dopo = nuovoGestore()
        dopo.imposta(55)
        aspetta { dopo.stato.value.bozza != null }
        assertEquals("Scritto in cantiere", dopo.stato.value.campi?.testo)
        assertTrue(dopo.haModificheNonSalvate())
    }

    @Test
    fun `non si chiede l'invio mentre si modifica o mentre Sol scrive`() {
        bozzaPronta()
        gestore.avviaModifica()
        gestore.chiediInvio()
        assertNull(gestore.stato.value.confermaInvio)
        assertEquals("Prima salva o annulla le modifiche.", gestore.stato.value.errore)

        server.rispondi("GET /api/bozze/56", Esempi.bozza("in_preparazione", id = 56))
        gestore.imposta(56)
        aspetta { gestore.stato.value.bozza?.id == 56L }
        gestore.chiediInvio()
        aspetta { !gestore.stato.value.preparazioneConferma }
        assertNull(gestore.stato.value.confermaInvio)
    }

    @Test
    fun `una bozza gia inviata non si reinvia`() {
        server.rispondi("GET /api/bozze/55", Esempi.bozza("inviata"))
        gestore.imposta(55)
        aspetta { gestore.stato.value.bozza != null }
        gestore.chiediInvio()
        aspetta { !gestore.stato.value.preparazioneConferma }
        assertNull(gestore.stato.value.confermaInvio)
        gestore.confermaInvio()
        Thread.sleep(50)
        assertTrue(invii.isEmpty())
    }

    // --- Rifare -----------------------------------------------------------------------

    @Test
    fun `rifare con istruzioni parte dalla bozza attuale e aspetta che sia pronta`() {
        bozzaPronta()
        server.rispondi("POST /api/bozze/55/rifai", Esempi.bozza("in_preparazione"), 202)
        server.inFila(
            "GET /api/bozze/55",
            Esempi.bozza("in_preparazione"),
            Esempi.bozza("pronta", testo = "Buongiorno Mario,\ngiovedì va bene."),
        )
        gestore.rifai("digli che giovedì va bene")
        aspetta("bozza nuova pronta") { gestore.stato.value.bozza?.testo?.contains("giovedì va bene") == true }
        assertFalse(gestore.stato.value.inAttesa)
        assertEquals(
            """{"istruzioni":"digli che giovedì va bene"}""",
            server.richiesteA("POST /api/bozze/55/rifai").single().body.readUtf8(),
        )
    }

    @Test
    fun `rifare senza istruzioni una bozza modificata - prima chiede, poi manda da_zero`() {
        bozzaPronta(json = Esempi.bozza(modificata = true))
        server.rispondi("POST /api/bozze/55/rifai", Esempi.bozza("in_preparazione"), 202)
        gestore.rifai(null)
        assertTrue(gestore.stato.value.chiediDaZero)
        assertTrue(server.richiesteA("POST /api/bozze/55/rifai").isEmpty())
        gestore.confermaDaZero()
        aspetta { server.richiesteA("POST /api/bozze/55/rifai").isNotEmpty() }
        assertEquals("""{"istruzioni":null,"da_zero":true}""", server.richiesteA("POST /api/bozze/55/rifai").single().body.readUtf8())
    }

    @Test
    fun `salvare controlla gli indirizzi prima di chiamare il server`() {
        bozzaPronta()
        gestore.avviaModifica()
        gestore.cambiaCampi(gestore.stato.value.campi!!.copy(a = "rossi@esempio.it, bianchi-senza-chiocciola"))
        gestore.salvaModifica()
        assertTrue(gestore.stato.value.errore!!.contains("bianchi-senza-chiocciola"))
        assertTrue(server.richiesteA("PUT /api/bozze/55").isEmpty())

        server.rispondi("PUT /api/bozze/55", Esempi.bozza(modificata = true))
        gestore.cambiaCampi(gestore.stato.value.campi!!.copy(a = "rossi@esempio.it; bianchi@esempio.it"))
        gestore.salvaModifica()
        aspetta { !gestore.stato.value.inModifica }
        assertTrue(server.richiesteA("PUT /api/bozze/55").single().body.readUtf8().contains("""["rossi@esempio.it","bianchi@esempio.it"]"""))
        assertFalse("salvata: niente testo in sospeso", gestore.haModificheNonSalvate())
        assertNotNull(gestore.stato.value.bozza)
    }
}
