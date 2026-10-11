package eu.stgm.sol.ui

import eu.stgm.sol.DepositoMemoria
import eu.stgm.sol.Esempi
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.aspetta
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.dati.Riassunto
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.ui.abbinamento.AbbinamentoViewModel
import eu.stgm.sol.ui.dettaglio.DettaglioViewModel
import eu.stgm.sol.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.sol.ui.indietro.IndietroViewModel
import eu.stgm.sol.ui.posta.PostaViewModel
import eu.stgm.sol.ui.riassunti.RiassuntiViewModel
import eu.stgm.sol.ui.scadenze.ScadenzeViewModel
import eu.stgm.sol.ui.scadenze.leggiOra
import eu.stgm.sol.ui.scadenze.ordinaScadenze
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ViewModelTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val client = server.client()
    private val cache = CacheMemoria()

    @After
    fun chiudi() = server.close()

    private fun serverBase(prova: Boolean = true) {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova))
        server.rispondi("GET /api/mail", Esempi.PAGINA_MAIL)
    }

    // --- Posta -------------------------------------------------------------------------

    @Test
    fun `posta - carica mail, gruppi e modalita prova`() {
        serverBase(prova = true)
        val vm = PostaViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.caricataAlmenoUnaVolta }
        val s = vm.stato.value
        assertEquals(listOf(812L, 815L), s.mail.map { it.id })
        assertEquals(listOf("Clienti", "Fornitori", "Altro"), s.gruppi.map { it.nome })
        assertTrue(s.modalitaProva)
        assertTrue(s.altre)
        assertNull(s.errore)
    }

    @Test
    fun `posta - gruppo e filtro diventano la richiesta giusta, ritoccare il filtro lo toglie`() {
        serverBase()
        val vm = PostaViewModel(client, cache)
        vm.scegliGruppo(1)
        vm.scegliFiltro(FiltroRapido.DA_CONTROLLARE)
        aspetta { server.richiesteA("GET /api/mail").any { it.path!!.contains("smistamento=da_controllare") } }
        assertTrue(server.richiesteA("GET /api/mail").any { it.path == "/api/mail?gruppo_id=1&smistamento=da_controllare&limite=50" })
        vm.scegliFiltro(FiltroRapido.DA_CONTROLLARE)
        assertEquals(FiltroRapido.TUTTE, vm.stato.value.filtro)
    }

    @Test
    fun `posta - senza server restano gli ultimi dati visti, con l'avviso`() {
        serverBase()
        val vm = PostaViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.mail.isNotEmpty() && !vm.stato.value.caricamento }
        server.spegni()
        vm.carica()
        aspetta { vm.stato.value.errore != null && !vm.stato.value.caricamento }
        val s = vm.stato.value
        assertEquals(Testi.SERVER_IRRAGGIUNGIBILE, s.errore)
        assertNotNull(s.daCacheDel)
        assertEquals(2, s.mail.size)
        assertTrue(s.modalitaProva)
    }

    @Test
    fun `posta - i numeri dei gruppi e il pallino della barra vengono da conteggi`() {
        serverBase()
        server.rispondi(
            "GET /api/conteggi",
            """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "da_leggere": 12, "bozze_pronte": 4,
               "scadenze_prossime": 2, "gruppi": {"1": {"da_leggere": 9, "totale": 120}}}""",
        )
        val vm = PostaViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.conteggi != null }
        val s = vm.stato.value
        assertEquals(9, s.daLeggere(s.gruppi.first { it.id == 1L }))
        assertEquals("senza conteggio vale l'elenco dei gruppi", 1, s.daLeggere(s.gruppi.first { it.id == 6L }))
        assertEquals("il pallino: le importanti da leggere", 10, s.daLeggere)
    }

    @Test
    fun `posta - le mail da sistemare sono le da controllare non sospette, solo nella Posta`() {
        serverBase()
        server.rispondi(
            "GET /api/mail?smistamento=da_controllare&limite=50",
            """{"mail": [${Esempi.MAIL_BIANCHI}, ${Esempi.MAIL_SOSPETTA}], "altre": false}""",
        )
        val vm = PostaViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.caricataAlmenoUnaVolta }
        assertEquals("la possibile truffa non si smista", 1, vm.stato.value.daSistemare)

        val raccolta = PostaViewModel(client, cache)
        raccolta.scegliGruppo(3)
        aspetta { raccolta.stato.value.caricataAlmenoUnaVolta }
        assertEquals(
            "nelle raccolte non si chiede",
            1,
            server.richiesteA("GET /api/mail").count { it.path == "/api/mail?smistamento=da_controllare&limite=50" },
        )
    }

    @Test
    fun `posta - la ricerca chiede cerca, vuota non chiede niente`() {
        serverBase()
        server.rispondi("GET /api/mail?cerca=capannone+Bassano&limite=50", """{"mail": [${Esempi.MAIL}], "altre": false}""")
        val vm = PostaViewModel(client, cache)
        vm.cerca("")
        assertTrue(vm.stato.value.mail.isEmpty())
        assertTrue(server.richieste.isEmpty())
        vm.cerca("capannone Bassano")
        aspetta { vm.stato.value.mail.map { it.id } == listOf(812L) }
        assertTrue(server.richiesteA("GET /api/mail").none { it.path!!.contains("smistamento") })
    }

    @Test
    fun `dettaglio - non e una truffa riporta la mail normale, segnala la rimette sospetta`() {
        serverBase()
        server.rispondi("GET /api/mail/820", Esempi.DETTAGLIO_SOSPETTO)
        val vm = DettaglioViewModel(client, cache, intervalloBozzaMs = 20)
        vm.apri(820)
        aspetta { vm.stato.value.mail != null }
        assertTrue(vm.stato.value.mail!!.sospetta)

        server.rispondi(
            "POST /api/mail/820/sospetta",
            Esempi.MAIL_SOSPETTA.replace("\"sospetta\": true", "\"sospetta\": false").replace("\"da_controllare\"", "\"smistata\""),
        )
        vm.nonETruffa()
        aspetta { vm.stato.value.messaggio != null }
        assertEquals("""{"sospetta":false}""", server.richiesteA("POST /api/mail/820/sospetta").single().body.readUtf8())
        assertFalse(vm.stato.value.mail!!.sospetta)
        assertEquals("smistata", vm.stato.value.mail!!.smistamento)
        assertTrue("il testo della mail non si perde", vm.stato.value.mail!!.corpo!!.contains("IBAN"))
        assertEquals("Va bene: Sol la tratta come una mail normale.", vm.stato.value.messaggio)

        vm.consumaMessaggio()
        server.rispondi("POST /api/mail/820/sospetta", Esempi.MAIL_SOSPETTA)
        vm.segnalaTruffa()
        aspetta { vm.stato.value.messaggio != null }
        assertTrue(vm.stato.value.mail!!.sospetta)
        assertEquals("""{"sospetta":true}""", server.richiesteA("POST /api/mail/820/sospetta").last().body.readUtf8())
    }

    @Test
    fun `posta - mostra le piu vecchie partendo dall'ultima`() {
        serverBase()
        val vm = PostaViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.mail.size == 2 }
        server.rispondi("GET /api/mail?limite=50&prima_di=815", """{"mail": [${Esempi.MAIL.replace("\"id\": 812", "\"id\": 700")}], "altre": false}""")
        vm.caricaAltre()
        aspetta { vm.stato.value.mail.size == 3 }
        assertFalse(vm.stato.value.altre)
    }

    // --- Dettaglio -----------------------------------------------------------------------

    private fun dettaglio(prova: Boolean = true): DettaglioViewModel {
        serverBase(prova)
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO)
        server.rispondi("GET /api/bozze/55", Esempi.bozza())
        val vm = DettaglioViewModel(client, cache, intervalloBozzaMs = 20)
        vm.apri(812)
        aspetta("mail e bozza") { vm.stato.value.mail != null && vm.bozza.stato.value.bozza != null }
        return vm
    }

    @Test
    fun `dettaglio - mail, gruppi e bozza della mail`() {
        val vm = dettaglio()
        assertEquals("Offerta capannone Bassano", vm.stato.value.mail!!.oggetto)
        assertTrue(vm.stato.value.mail!!.corpo!!.contains("venerdì"))
        assertEquals(55L, vm.bozza.stato.value.bozza!!.id)
    }

    @Test
    fun `dettaglio - sposta con ricordati il mittente, in modalita prova lo dice`() {
        val vm = dettaglio(prova = true)
        server.rispondi(
            "POST /api/mail/812/sposta",
            Esempi.MAIL.replace("\"gruppo_id\": 1, \"gruppo\": \"Clienti\"", "\"gruppo_id\": 2, \"gruppo\": \"Fornitori\"")
                .replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\""),
        )
        vm.sposta(2, impara = true)
        aspetta { vm.stato.value.messaggio != null }
        assertEquals("""{"gruppo_id":2,"impara":true}""", server.richiesteA("POST /api/mail/812/sposta").single().body.readUtf8())
        val s = vm.stato.value
        assertEquals("Fornitori", s.mail!!.gruppo)
        assertEquals("corretta", s.mail!!.smistamento)
        assertTrue(s.mail!!.corpo!!.isNotEmpty()) // il dettaglio non si perde
        assertTrue(s.messaggio!!, s.messaggio!!.contains("Fornitori"))
        assertTrue(s.messaggio!!, s.messaggio!!.contains("ricorderà"))
        assertTrue(s.messaggio!!, s.messaggio!!.contains("Modalità prova"))
    }

    @Test
    fun `dettaglio - un errore del server si mostra cosi com'e`() {
        val vm = dettaglio()
        server.rispondi("POST /api/mail/812/letta", """{"errore": "Outlook non risponde. Riprova tra poco.", "codice": "casella_non_raggiungibile"}""", 502)
        vm.cambiaLetta()
        aspetta { !vm.stato.value.inCorso && vm.stato.value.erroreAzione != null }
        assertEquals("Outlook non risponde. Riprova tra poco.", vm.stato.value.erroreAzione)
        assertNull("un gesto fallito non è un problema di lettura", vm.stato.value.errore)
        assertFalse(vm.stato.value.mail!!.letta)
    }

    @Test
    fun `dettaglio - senza rete la mail vecchia si legge ma la bozza no`() {
        val vm = dettaglio()
        server.spegni()
        val altro = DettaglioViewModel(client, cache)
        altro.apri(812)
        aspetta { altro.stato.value.mail != null && !altro.stato.value.caricamento }
        assertNotNull(altro.stato.value.daCacheDel)
        assertNull(altro.bozza.stato.value.bozza)
        assertNotNull(vm.stato.value.mail)
    }

    @Test
    fun `dettaglio - l'invio aggiorna la mail come risposta data`() {
        val vm = dettaglio()
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata"))
        vm.bozza.chiediInvio()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        vm.bozza.confermaInvio()
        aspetta { vm.stato.value.mail!!.rispostaData }
        assertEquals(1, server.richiesteA("POST /api/bozze/55/invia").size)
    }

    // --- Riassunti -----------------------------------------------------------------------

    @Test
    fun `riassunti - il testo cresce mentre Sol scrive, poi si ferma`() {
        server.rispondi("GET /api/riassunti/oggi", """{"testo": "Oggi 3 mail [#812]", "aggiornato_il": "2026-10-10T09:00:00+02:00", "mail_citate": [812], "stato": "pronto"}""")
        server.rispondi("GET /api/riassunti", "[]")
        server.rispondi("POST /api/riassunti", """{"id": 9, "stato": "in_coda"}""", 202)
        server.inFila(
            "GET /api/riassunti/9",
            """{"id": 9, "richiesta": "le mail di Rossi", "stato": "in_corso", "testo_parziale": "Rossi ha", "mail_citate": []}""",
            """{"id": 9, "richiesta": "le mail di Rossi", "stato": "in_corso", "testo_parziale": "Rossi ha chiesto l'offerta", "mail_citate": []}""",
            """{"id": 9, "richiesta": "le mail di Rossi", "stato": "pronto", "testo": "Rossi ha chiesto l'offerta [#812].", "mail_citate": [812]}""",
        )
        val vm = RiassuntiViewModel(client, cache, intervalloMs = 20)
        vm.carica()
        aspetta { vm.stato.value.oggi != null }
        assertEquals("Oggi 3 mail [#812]", vm.stato.value.oggi!!.testoVisibile)

        val visti = mutableListOf<String>()
        vm.cambiaRichiesta("le mail di Rossi")
        vm.chiedi()
        aspetta("riassunto finito") {
            vm.stato.value.corrente?.testoVisibile?.let { if (visti.lastOrNull() != it) visti += it }
            vm.stato.value.corrente?.stato == Riassunto.PRONTO
        }
        assertEquals("Rossi ha chiesto l'offerta [#812].", vm.stato.value.corrente!!.testoVisibile)
        assertTrue(visti.toString(), visti.size >= 2)
        assertFalse(vm.stato.value.inCorso)
        assertEquals("", vm.stato.value.richiesta)
        assertEquals("""{"richiesta":"le mail di Rossi"}""", server.richiesteA("POST /api/riassunti").single().body.readUtf8())
        val giri = server.richiesteA("GET /api/riassunti/9").size
        Thread.sleep(100)
        assertEquals("niente ricariche dopo la fine", giri, server.richiesteA("GET /api/riassunti/9").size)
    }

    @Test
    fun `riassunti - richiesta vuota non parte`() {
        val vm = RiassuntiViewModel(client, cache)
        vm.chiedi()
        assertNotNull(vm.stato.value.erroreAzione)
        assertTrue(server.richiesteA("POST /api/riassunti").isEmpty())
    }

    // --- Niente resta indietro -----------------------------------------------------------

    @Test
    fun `indietro - sollecito da aprire e ignora che toglie dalla lista`() {
        server.rispondi("GET /api/indietro", Esempi.INDIETRO)
        server.rispondi("POST /api/indietro/77/sollecito", Esempi.bozza("in_preparazione", id = 90).replace("\"risposta\"", "\"sollecito\""), 202)
        server.rispondi("POST /api/indietro/77/ignora", "{}")
        val vm = IndietroViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.dati != null }
        assertEquals(1, vm.stato.value.dati!!.aspettiTu.size)

        vm.preparaSollecito(77)
        aspetta { vm.stato.value.sollecitoDaAprire != null }
        assertEquals(90L, vm.stato.value.sollecitoDaAprire!!.id)
        assertEquals("sollecito", vm.stato.value.sollecitoDaAprire!!.tipo)

        vm.ignora(77)
        aspetta { vm.stato.value.dati!!.aspettiTu.isEmpty() }
        assertEquals("Tolta dalla lista.", vm.stato.value.messaggio)
    }

    // --- Scadenze ------------------------------------------------------------------------

    @Test
    fun `scadenze - ordine per data e ora, solo aperte`() {
        val s = listOf(
            Scadenza(1, descrizione = "C", quando = "2026-10-20"),
            Scadenza(2, descrizione = "A", quando = "2026-10-12", ora = "15:00"),
            Scadenza(3, descrizione = "B", quando = "2026-10-12"),
            Scadenza(4, descrizione = "fatta", quando = "2026-10-01", stato = Scadenza.FATTA),
        )
        assertEquals(listOf("B", "A", "C"), ordinaScadenze(s).map { it.descrizione })
    }

    @Test
    fun `scadenze - ora scritta come viene, controllata prima di mandare`() {
        assertEquals("09:30", leggiOra("9.30").getOrNull())
        assertEquals("14:05", leggiOra(" 14:05 ").getOrNull())
        assertNull(leggiOra("").getOrNull())
        assertTrue(leggiOra("25:00").isFailure)

        val vm = ScadenzeViewModel(client, cache)
        vm.aggiungi("Sopralluogo Bianchi", null, "")
        assertEquals("Scegli il giorno.", vm.stato.value.erroreAzione)
        vm.aggiungi("Sopralluogo Bianchi", LocalDate.of(2026, 10, 20), "ore nove")
        assertTrue(vm.stato.value.erroreAzione!!.contains("9:30"))
        assertTrue(server.richiesteA("POST /api/scadenze").isEmpty())

        server.rispondi("POST /api/scadenze", Esempi.SCADENZA, 201)
        server.rispondi("GET /api/scadenze?stato=aperta", "[${Esempi.SCADENZA}]")
        vm.aggiungi("Sopralluogo Bianchi", LocalDate.of(2026, 10, 20), "9:30")
        aspetta { vm.stato.value.aggiunta }
        assertEquals(
            """{"descrizione":"Sopralluogo Bianchi","quando":"2026-10-20","ora":"09:30"}""",
            server.richiesteA("POST /api/scadenze").single().body.readUtf8(),
        )
    }

    @Test
    fun `scadenze - fatta toglie dalla lista`() {
        server.rispondi("GET /api/scadenze?stato=aperta", "[${Esempi.SCADENZA}]")
        server.rispondi("PATCH /api/scadenze/4", Esempi.SCADENZA.replace("aperta", "fatta"))
        val vm = ScadenzeViewModel(client, cache)
        vm.carica()
        aspetta { vm.stato.value.scadenze.isNotEmpty() }
        vm.segnaFatta(4)
        aspetta { vm.stato.value.scadenze.isEmpty() }
        assertEquals("""{"stato":"fatta"}""", server.richiesteA("PATCH /api/scadenze/4").single().body.readUtf8())
    }

    // --- Abbinamento ---------------------------------------------------------------------

    @Test
    fun `abbinamento - controlla codice e indirizzo, poi salva il token`() = runBlocking {
        val deposito = DepositoMemoria()
        var chiamate = 0
        var svuotato = false
        val vm = AbbinamentoViewModel(deposito, prima = { svuotato = true }, abbina = { server, codice, nome ->
            chiamate++
            assertEquals("https://sol.esempio.ts.net", server)
            assertEquals("48213377", codice)
            assertEquals("Telefono di Manuel", nome)
            Esito.Ok(eu.stgm.sol.dati.Abbinamento("t".repeat(64), 3))
        })
        vm.cambiaIndirizzo("http://sol.esempio.ts.net")
        vm.cambiaCodice("4821 3377")
        vm.abbina()
        assertTrue(vm.stato.value.errore!!.contains("https://"))

        vm.cambiaIndirizzo("sol.esempio.ts.net")
        vm.cambiaCodice("123")
        vm.abbina()
        assertTrue(vm.stato.value.errore!!.contains("8 cifre"))
        assertEquals(0, chiamate)

        vm.cambiaCodice("4821-3377")
        assertEquals("48213377", vm.stato.value.codice)
        vm.abbina()
        aspetta { vm.stato.value.fatto }
        assertEquals(Configurazione("https://sol.esempio.ts.net", "t".repeat(64), 3), deposito.leggi())
        assertTrue("i dati del server di prima vanno via", svuotato)
    }

    @Test
    fun `abbinamento - server irraggiungibile spiegato semplice`() {
        val vm = AbbinamentoViewModel(DepositoMemoria(), abbina = { _, _, _ -> Esito.Irraggiungibile })
        vm.cambiaIndirizzo("http://192.168.1.51:8200")
        vm.cambiaCodice("48213377")
        vm.abbina()
        aspetta { !vm.stato.value.inCorso }
        assertTrue(vm.stato.value.errore!!.startsWith("Non trovo il server"))
        assertFalse(vm.stato.value.fatto)
    }

    // --- Impostazioni --------------------------------------------------------------------

    private fun impostazioni(): ImpostazioniViewModel {
        server.rispondi("GET /api/caselle", "[${Esempi.CASELLA}]")
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/regole", """[{"id": 1, "tipo": "mittente", "valore": "bianchi@esempio.it", "gruppo_id": 1, "origine": "imparata"}]""")
        server.rispondi("GET /api/dispositivi", """[{"id": 3, "nome": "Telefono di Manuel", "tipo": "android"}]""")
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione(server.url, "x", 3)), intervalloOutlookMs = 20)
        vm.carica()
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        return vm
    }

    @Test
    fun `impostazioni - outlook mostra il codice e ricarica finche e collegata`() {
        val vm = impostazioni()
        server.rispondi(
            "POST /api/caselle",
            """{"casella": {"id": 2, "tipo": "outlook", "nome": "Studio", "stato": "in_collegamento"},
               "codice_utente": "ABCD-EFGH", "url_verifica": "https://microsoft.com/devicelogin"}""",
            202,
        )
        server.inFila(
            "GET /api/caselle/2",
            """{"id": 2, "tipo": "outlook", "nome": "Studio", "stato": "in_collegamento"}""",
            """{"id": 2, "tipo": "outlook", "indirizzo": "studio@esempio.it", "nome": "Studio", "stato": "collegata"}""",
        )
        vm.aggiungiOutlook("Studio")
        aspetta { vm.stato.value.outlook != null }
        assertEquals("ABCD-EFGH", vm.stato.value.outlook!!.codice)
        assertEquals("https://microsoft.com/devicelogin", vm.stato.value.outlook!!.url)
        aspetta("collegata") { vm.stato.value.outlook?.stato == "collegata" }
        assertTrue(server.richiesteA("GET /api/caselle/2").size >= 2)
        assertEquals("Casella Outlook collegata.", vm.stato.value.messaggio)
    }

    @Test
    fun `impostazioni - gmail, la password per le app controllata e il rifiuto di Google mostrato`() {
        val vm = impostazioni()
        vm.aggiungiGmail("rossi.studio@gmail.com", "", "abcd efgh")
        assertTrue(vm.stato.value.erroreAzione!!.contains("16 lettere"))
        assertTrue(server.richiesteA("POST /api/caselle").isEmpty())

        server.rispondi("POST /api/caselle", """{"errore": "Google ha rifiutato la password per le app. Controlla di averla copiata bene.", "codice": "non_valido"}""", 422)
        vm.aggiungiGmail("rossi.studio@gmail.com", "", "abcd efgh ijkl mnop")
        aspetta { !vm.stato.value.inCorso && vm.stato.value.erroreAzione != null }
        assertTrue(vm.stato.value.erroreAzione!!.startsWith("Google ha rifiutato"))
        assertTrue(server.richiesteA("POST /api/caselle").single().body.readUtf8().contains("\"password_app\":\"abcd efgh ijkl mnop\""))
    }

    @Test
    fun `impostazioni - niente password Gmail su http, nemmeno in casa`() {
        server.rispondi("GET /api/caselle", "[${Esempi.CASELLA}]")
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione("http://192.168.1.51:8200", "x", 3)))
        vm.carica()
        aspetta { !vm.stato.value.caricamento }
        assertFalse(vm.stato.value.passwordConsentita)
        assertTrue(vm.stato.value.collegamentoNonProtetto)
        vm.aggiungiGmail("rossi.studio@gmail.com", "", "abcd efgh ijkl mnop")
        aspetta { vm.stato.value.erroreAzione != null }
        assertEquals(Testi.PASSWORD_SU_HTTP, vm.stato.value.erroreAzione)
        vm.ricollegaGmail(2, "abcd efgh ijkl mnop")
        Thread.sleep(50)
        assertTrue(server.richiesteA("POST /api/caselle").isEmpty())
        assertTrue(server.richiesteA("POST /api/caselle/2/ricollega").isEmpty())
    }

    @Test
    fun `impostazioni - spenta la modalita prova si chiede dell'arretrato, e solo col si si sposta`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/arretrato", """{"da_applicare": 37}""")
        server.rispondi("POST /api/arretrato/applica", """{"da_applicare": 37}""", 202)
        vm.cambiaModalitaProva(false)
        aspetta { vm.stato.value.arretratoDaApplicare == 37 }
        assertTrue(vm.stato.value.messaggio!!.contains("mail nuove"))
        assertTrue(server.richiesteA("POST /api/arretrato/applica").isEmpty())
        vm.applicaArretrato()
        aspetta { server.richiesteA("POST /api/arretrato/applica").isNotEmpty() && !vm.stato.value.inCorso }
        assertEquals("""{"conferma":true}""", server.richiesteA("POST /api/arretrato/applica").single().body.readUtf8())
        assertNull(vm.stato.value.arretratoDaApplicare)
    }

    @Test
    fun `impostazioni - ricollegare una Gmail e un Outlook in errore`() {
        val vm = impostazioni()
        server.rispondi("POST /api/caselle/2/ricollega", """{"id": 2, "tipo": "gmail", "stato": "collegata"}""")
        vm.ricollegaGmail(2, "abcd efgh ijkl mnop")
        aspetta { vm.stato.value.casellaAggiunta }
        assertEquals("""{"password_app":"abcd efgh ijkl mnop"}""", server.richiesteA("POST /api/caselle/2/ricollega").single().body.readUtf8())

        server.rispondi(
            "POST /api/caselle/3/ricollega",
            """{"codice_utente": "WXYZ-1234", "url_verifica": "https://microsoft.com/devicelogin"}""",
            202,
        )
        server.rispondi("GET /api/caselle/3", """{"id": 3, "tipo": "outlook", "stato": "collegata"}""")
        vm.ricollegaOutlook(3)
        aspetta { vm.stato.value.outlook?.codice == "WXYZ-1234" }
        assertTrue(vm.stato.value.outlook!!.ricollegamento)
        aspetta { vm.stato.value.outlook?.stato == "collegata" }
    }

    @Test
    fun `impostazioni - gruppo nuovo con la frase per Sol, e al massimo 16`() {
        val vm = impostazioni()
        server.rispondi("POST /api/gruppi", """{"id": 9, "nome": "Cantieri"}""", 201)
        vm.aggiungiGruppo("Cantieri", "mail dei cantieri aperti e dei sopralluoghi")
        aspetta { server.richiesteA("POST /api/gruppi").isNotEmpty() && !vm.stato.value.inCorso }
        assertEquals(
            """{"nome":"Cantieri","descrizione":"mail dei cantieri aperti e dei sopralluoghi"}""",
            server.richiesteA("POST /api/gruppi").single().body.readUtf8(),
        )

        server.rispondi("PATCH /api/gruppi/1", """{"id": 1, "nome": "Clienti"}""")
        vm.cambiaDescrizioneGruppo(1, "un cliente chiede un preventivo o conferma un lavoro")
        aspetta { vm.stato.value.gruppi.first { it.id == 1L }.descrizione?.startsWith("un cliente") == true }
        assertEquals(
            """{"descrizione":"un cliente chiede un preventivo o conferma un lavoro"}""",
            server.richiesteA("PATCH /api/gruppi/1").single().body.readUtf8(),
        )

        server.rispondi("GET /api/gruppi", "[" + (1..16).joinToString(",") { """{"id": $it, "nome": "G$it"}""" } + "]")
        val pieno = ImpostazioniViewModel(client, CacheMemoria(), DepositoMemoria(Configurazione(server.url, "x", 3)))
        pieno.carica()
        aspetta { pieno.stato.value.gruppi.size == 16 }
        assertFalse(pieno.stato.value.puoAggiungereGruppo)
        pieno.aggiungiGruppo("Diciassettesimo", "")
        assertEquals(ImpostazioniViewModel.TROPPI_GRUPPI, pieno.stato.value.erroreAzione)
    }

    @Test
    fun `impostazioni - colore del gruppo, solo il campo colore (contratto 17)`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/gruppi/2", """{"id": 2, "nome": "Fornitori", "colore": "petrolio"}""")
        vm.cambiaColoreGruppo(2, "petrolio")
        aspetta { vm.stato.value.gruppi.first { it.id == 2L }.colore == "petrolio" && !vm.stato.value.inCorso }
        assertEquals("""{"colore":"petrolio"}""", server.richiesteA("PATCH /api/gruppi/2").single().body.readUtf8())
        assertEquals("Colore cambiato.", vm.stato.value.messaggio)

        // Un nome fuori elenco: il server risponde 422 col campo "colore" e la frase si mostra.
        server.rispondi(
            "PATCH /api/gruppi/2",
            """{"errore": "Questo colore non c'è. Scegli tra blu, verde, ambra, viola, grigio, ardesia, petrolio, rosa, indaco, oliva, marrone e ciano.", "codice": "dati_non_validi", "campo": "colore"}""",
            422,
        )
        vm.cambiaColoreGruppo(2, "fucsia")
        aspetta { vm.stato.value.erroreAzione != null }
        assertTrue(vm.stato.value.erroreAzione!!.startsWith("Questo colore non c'è"))
        assertEquals("petrolio", vm.stato.value.gruppi.first { it.id == 2L }.colore)
    }

    @Test
    fun `impostazioni - nome per il saluto, al massimo 40 caratteri (contratto 18_3)`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni().replace("\"conserva_giorni\": 180", "\"conserva_giorni\": 180, \"nome\": \"Manuel\""))
        vm.cambiaNome("  Manuel  ")
        aspetta { vm.stato.value.impostazioni?.nome == "Manuel" && !vm.stato.value.inCorso }
        assertEquals("""{"nome":"Manuel"}""", server.richiesteA("PATCH /api/impostazioni").single().body.readUtf8())
        vm.cambiaNome("x".repeat(50))
        aspetta { server.richiesteA("PATCH /api/impostazioni").size == 2 && !vm.stato.value.inCorso }
        assertEquals("""{"nome":"${"x".repeat(40)}"}""", server.richiesteA("PATCH /api/impostazioni").last().body.readUtf8())
    }

    @Test
    fun `impostazioni - raccolta e posizione dei gruppi (contratto 18_2)`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/gruppi/2", "{}")
        server.rispondi("PATCH /api/gruppi/6", "{}")
        vm.cambiaRaccoltaGruppo(2, true)
        aspetta { vm.stato.value.gruppi.first { it.id == 2L }.raccolta && !vm.stato.value.inCorso }
        assertEquals("""{"raccolta":true}""", server.richiesteA("PATCH /api/gruppi/2").single().body.readUtf8())

        // Altro sale di un posto: si rinumerano solo i gruppi che cambiano posto.
        vm.spostaGruppo(6, su = true)
        aspetta { vm.stato.value.gruppi.map { it.id } == listOf(1L, 6L, 2L) && !vm.stato.value.inCorso }
        assertEquals(listOf(1, 2, 3), vm.stato.value.gruppi.map { it.ordine })
        assertEquals("""{"ordine":2}""", server.richiesteA("PATCH /api/gruppi/6").single().body.readUtf8())
        assertEquals("""{"ordine":3}""", server.richiesteA("PATCH /api/gruppi/2").last().body.readUtf8())
        assertTrue("il primo non cambia posto", server.richiesteA("PATCH /api/gruppi/1").isEmpty())

        // Il primo non sale più su: nessuna richiesta.
        val prima = server.richieste.size
        vm.spostaGruppo(1, su = true)
        assertEquals(prima, server.richieste.size)
    }

    @Test
    fun `impostazioni - modalita prova e soglia mandano solo il campo cambiato`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni(prova = false))
        vm.cambiaModalitaProva(false)
        aspetta { vm.stato.value.impostazioni?.modalitaProva == false }
        assertEquals("""{"modalita_prova":false}""", server.richiesteA("PATCH /api/impostazioni").single().body.readUtf8())
        vm.cambiaSoglia(0.9012)
        aspetta { server.richiesteA("PATCH /api/impostazioni").size == 2 }
        assertEquals("""{"soglia_sicurezza":0.9}""", server.richiesteA("PATCH /api/impostazioni").last().body.readUtf8())
    }

    @Test
    fun `impostazioni - soglia truffa tra 0,2 e 0,9`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni())
        vm.cambiaSogliaTruffa(0.349)
        aspetta { server.richiesteA("PATCH /api/impostazioni").size == 1 && !vm.stato.value.inCorso }
        assertEquals("""{"soglia_truffa":0.34}""", server.richiesteA("PATCH /api/impostazioni").last().body.readUtf8())
        vm.cambiaSogliaTruffa(0.05)
        aspetta { server.richiesteA("PATCH /api/impostazioni").size == 2 && !vm.stato.value.inCorso }
        assertEquals("""{"soglia_truffa":0.2}""", server.richiesteA("PATCH /api/impostazioni").last().body.readUtf8())
        vm.cambiaSogliaTruffa(1.0)
        aspetta { server.richiesteA("PATCH /api/impostazioni").size == 3 }
        assertEquals("""{"soglia_truffa":0.9}""", server.richiesteA("PATCH /api/impostazioni").last().body.readUtf8())
    }

    @Test
    fun `impostazioni - revocare questo telefono lo scollega`() {
        val vm = impostazioni()
        server.rispondi("DELETE /api/dispositivi/3", "", 204)
        vm.revocaDispositivo(3)
        aspetta { vm.stato.value.scollegato }
    }
}
