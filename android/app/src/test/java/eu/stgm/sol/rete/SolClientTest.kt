package eu.stgm.sol.rete

import eu.stgm.sol.Esempi
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.ModificaBozza
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolClientTest {
    private val server = ServerFinto()
    private val client = server.client()

    @After
    fun chiudi() = server.close()

    @Test
    fun `ogni richiesta porta il token`() = runBlocking {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        val esito = client.gruppi()
        assertEquals(3, (esito as Esito.Ok).dato.size)
        assertEquals("Bearer token-finto-di-prova", server.richieste.single().getHeader("Authorization"))
    }

    @Test
    fun `i filtri della posta diventano i parametri del contratto`() = runBlocking {
        server.rispondi("GET /api/mail", Esempi.PAGINA_MAIL)
        val esito = client.mail(FiltroMail(gruppoId = 1, filtro = FiltroRapido.URGENTI))
        assertEquals(2, (esito as Esito.Ok).dato.mail.size)
        assertEquals("/api/mail?gruppo_id=1&urgenza=alta&limite=50", server.richieste.single().path)
    }

    @Test
    fun `percorsi dei filtri rapidi e della paginazione`() {
        assertEquals("/api/mail?limite=50", FiltroMail().percorso())
        assertEquals("/api/mail?da_rispondere=true&limite=50", FiltroMail(filtro = FiltroRapido.DA_RISPONDERE).percorso())
        assertEquals(
            "/api/mail?smistamento=da_controllare&limite=50&prima_di=812",
            FiltroMail(filtro = FiltroRapido.DA_CONTROLLARE, primaDi = 812).percorso(),
        )
    }

    @Test
    fun `invia manda sempre conferma true e la versione vista, con POST, alla bozza giusta`() = runBlocking {
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata"))
        val esito = client.inviaBozza(55, versione = 3)
        assertEquals("inviata", (esito as Esito.Ok).dato.bozza!!.stato)
        assertEquals(false, esito.dato.giaInviata)
        val r = server.richiesteA("POST /api/bozze/55/invia").single()
        val corpo = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("true", corpo["conferma"]!!.jsonPrimitive.content)
        assertEquals("3", corpo["versione"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gia_inviata arriva insieme alla bozza`() = runBlocking {
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata").trimEnd().removeSuffix("}") + """, "gia_inviata": true}""")
        val esito = client.inviaBozza(55, versione = 3) as Esito.Ok
        assertTrue(esito.dato.giaInviata)
        assertEquals("inviata", esito.dato.bozza!!.stato)
    }

    @Test
    fun `409 di versione cambiata - la bozza nuova arriva nei dettagli dell'errore`() = runBlocking {
        server.rispondi(
            "POST /api/bozze/55/invia",
            """{"errore": "La risposta è cambiata dopo che l'hai controllata: ricontrollala prima di inviarla.",
               "codice": "conflitto", "bozza": ${Esempi.bozza(versione = 4)}}""",
            409,
        )
        val e = client.inviaBozza(55, versione = 3) as Esito.Errore
        assertEquals(409, e.stato)
        assertEquals("conflitto", e.codice)
        assertTrue(e.dettagli!!.containsKey("bozza"))
    }

    @Test
    fun `invio riuscito senza una bozza nella risposta resta un invio riuscito`() = runBlocking {
        server.rispondi("POST /api/bozze/55/invia", "", 200)
        server.rispondi("GET /api/bozze/55", Esempi.bozza("inviata"))
        assertEquals("inviata", (client.inviaBozza(55, 3) as Esito.Ok).dato.bozza!!.stato)

        // Anche se poi la bozza non si rilegge: il server ha detto sì.
        server.rispondi("GET /api/bozze/55", "", 500)
        assertEquals(Esito.Ok(RisultatoInvio(null)), client.inviaBozza(55, 3))
    }

    @Test
    fun `salvare bozza e impostazioni con una risposta vuota rilegge il dato`() = runBlocking {
        server.rispondi("PUT /api/bozze/55", "", 204)
        server.rispondi("GET /api/bozze/55", Esempi.bozza().replace("\"modificata\": false", "\"modificata\": true"))
        val b = client.salvaBozza(55, ModificaBozza(listOf("rossi@esempio.it"), emptyList(), "Re", "Va bene.")) as Esito.Ok
        assertTrue(b.dato.modificata)

        server.rispondi("PATCH /api/impostazioni", """{"ok": true}""")
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        val i = client.modificaImpostazioni(kotlinx.serialization.json.buildJsonObject { put("modalita_prova", false) })
        assertEquals(false, (i as Esito.Ok).dato.modalitaProva)
    }

    @Test
    fun `l'errore di invio del server arriva con la sua frase`() = runBlocking {
        server.rispondi(
            "POST /api/bozze/55/invia",
            """{"errore": "Outlook non ha accettato l'invio: casella piena.", "codice": "casella_non_raggiungibile"}""",
            502,
        )
        val esito = client.inviaBozza(55, 3) as Esito.Errore
        assertEquals("Outlook non ha accettato l'invio: casella piena.", esito.messaggio)
        assertEquals("casella_non_raggiungibile", esito.codice)
        assertEquals(502, esito.stato)
    }

    @Test
    fun `un 502 senza il JSON di Sol vuol dire server non raggiungibile`() = runBlocking {
        server.rispondi("GET /api/indietro", "<html>Bad Gateway</html>", 502)
        assertEquals(Esito.Irraggiungibile, client.indietro())
        assertEquals(Testi.SERVER_IRRAGGIUNGIBILE, client.indietro().messaggio)
    }

    @Test
    fun `senza corpo, frasi nostre per stato`() = runBlocking {
        server.rispondi("GET /api/caselle", "", 401)
        val e = client.caselle() as Esito.Errore
        assertEquals(Testi.NON_AUTORIZZATO, e.messaggio)
        assertEquals(Testi.CODICE_NON_AUTORIZZATO, e.codice)

        server.rispondi("PUT /api/bozze/55", """{"detail": {"errore": "La bozza è già partita.", "codice": "conflitto"}}""", 409)
        val c = client.salvaBozza(55, ModificaBozza(listOf("rossi@esempio.it"), emptyList(), "Re", "Testo")) as Esito.Errore
        assertEquals("La bozza è già partita.", c.messaggio)

        server.rispondi("GET /api/regole", "boom", 500)
        assertEquals(Testi.PROBLEMA_SERVER, (client.regole() as Esito.Errore).messaggio)
    }

    @Test
    fun `rete caduta = non raggiungibile, mai un'eccezione`() = runBlocking {
        server.rispondi("GET /api/scadenze", MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(Esito.Irraggiungibile, client.scadenzeAperte())
        server.spegni()
        assertEquals(Esito.Irraggiungibile, client.gruppi())
    }

    @Test
    fun `JSON che non si capisce = errore chiaro`() = runBlocking {
        server.rispondi("GET /api/impostazioni", "[1,2,3]")
        assertEquals(Testi.RISPOSTA_STRANA, (client.impostazioni() as Esito.Errore).messaggio)
    }

    @Test
    fun `senza abbinamento non parte nessuna richiesta`() = runBlocking {
        val senza = SolClient({ null })
        val e = senza.inviaBozza(55, 3) as Esito.Errore
        assertEquals(Testi.CODICE_NON_ABBINATO, e.codice)
        assertTrue(server.richieste.isEmpty())
    }

    @Test
    fun `sposta, letta, rifai e salva mandano i corpi del contratto`() = runBlocking {
        server.rispondi("POST /api/mail/812/sposta", Esempi.MAIL)
        server.rispondi("POST /api/mail/812/letta", "{}")
        server.rispondi("POST /api/mail/812/bozza", Esempi.bozza("in_preparazione"), 202)
        server.rispondi("PUT /api/bozze/55", Esempi.bozza())
        client.sposta(812, 2, impara = true)
        client.segnaLetta(812, true)
        val rifatta = client.preparaRisposta(812, "digli che giovedì va bene")
        client.salvaBozza(55, ModificaBozza(listOf("rossi@esempio.it"), listOf("neri@esempio.it"), "Re: Offerta", "Va bene."))

        assertEquals("in_preparazione", (rifatta as Esito.Ok).dato.stato)
        assertEquals("""{"gruppo_id":2,"impara":true}""", server.richiesteA("POST /api/mail/812/sposta").single().body.readUtf8())
        assertEquals("""{"letta":true}""", server.richiesteA("POST /api/mail/812/letta").single().body.readUtf8())
        assertEquals(
            """{"istruzioni":"digli che giovedì va bene"}""",
            server.richiesteA("POST /api/mail/812/bozza").single().body.readUtf8(),
        )
        assertEquals(
            """{"a":["rossi@esempio.it"],"cc":["neri@esempio.it"],"oggetto":"Re: Offerta","testo":"Va bene."}""",
            server.richiesteA("PUT /api/bozze/55").single().body.readUtf8(),
        )
    }

    @Test
    fun `rifai, conteggi, arretrato e ricollega con i nomi della v1_1`() = runBlocking {
        server.rispondi("POST /api/bozze/55/rifai", Esempi.bozza("in_preparazione"), 202)
        server.rispondi("GET /api/conteggi", """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "gruppi": {"1": {"da_leggere": 4, "totale": 120}}}""")
        server.rispondi("GET /api/arretrato", """{"da_applicare": 37}""")
        server.rispondi("POST /api/arretrato/applica", """{"da_applicare": 37}""", 202)
        server.rispondi("POST /api/caselle/2/ricollega", """{"casella": null, "codice_utente": "ABCD-EFGH", "url_verifica": "https://microsoft.com/devicelogin"}""", 202)

        client.rifaiBozza(55, "più breve")
        client.rifaiBozza(55, null, daZero = true)
        val c = (client.conteggi() as Esito.Ok).dato
        assertEquals(37, (client.arretrato() as Esito.Ok).dato.daApplicare)
        client.applicaArretrato()
        val o = (client.ricollegaOutlook(2) as Esito.Ok).dato

        assertEquals(listOf("""{"istruzioni":"più breve"}""", """{"istruzioni":null,"da_zero":true}"""),
            server.richiesteA("POST /api/bozze/55/rifai").map { it.body.readUtf8() })
        assertEquals(2, c.urgenti)
        assertEquals(4, c.gruppi["1"]!!.daLeggere)
        assertEquals("""{"conferma":true}""", server.richiesteA("POST /api/arretrato/applica").single().body.readUtf8())
        assertEquals("ABCD-EFGH", o.codiceUtente)
        assertEquals("{}", server.richiesteA("POST /api/caselle/2/ricollega").single().body.readUtf8())
    }

    @Test
    fun `un 422 porta il campo sbagliato e la frase semplice`() = runBlocking {
        server.rispondi(
            "PATCH /api/impostazioni",
            """{"errore": "I giorni per rispondere devono essere un numero intero tra 1 e 60.", "codice": "non_valido", "campo": "giorni_risposta"}""",
            422,
        )
        val e = client.modificaImpostazioni(kotlinx.serialization.json.buildJsonObject { put("giorni_risposta", 99) }) as Esito.Errore
        assertEquals("giorni_risposta", e.campo)
        assertEquals("I giorni per rispondere devono essere un numero intero tra 1 e 60.", e.messaggio)
    }

    // --- 401, http e password (15.12, 15.13) ---------------------------------------------

    @Test
    fun `un 401 confermato da salute fa dimenticare il telefono, una volta sola`() = runBlocking {
        var dimenticato = 0
        val c = server.client(alNonAutorizzato = { dimenticato++ })
        val revocato = """{"errore": "Questo dispositivo non è abbinato o il suo accesso è stato revocato.", "codice": "non_autorizzato"}"""
        server.rispondi("GET /api/gruppi", revocato, 401)
        server.rispondi("GET /api/caselle", revocato, 401)
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.0.0", "autenticato": false}""")
        val e = c.gruppi() as Esito.Errore
        c.caselle()
        assertEquals(Testi.NON_AUTORIZZATO, e.messaggio)
        assertEquals(Testi.CODICE_NON_AUTORIZZATO, e.codice)
        assertEquals(1, dimenticato)
    }

    @Test
    fun `se salute dice autenticato, un 401 isolato non fa dimenticare niente`() = runBlocking {
        var dimenticato = 0
        val c = server.client(alNonAutorizzato = { dimenticato++ })
        server.rispondi("GET /api/gruppi", "", 401)
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.0.0", "autenticato": true}""")
        c.gruppi()
        assertEquals(0, dimenticato)
    }

    @Test
    fun `in chiaro solo verso la rete di casa - altrimenti nessuna richiesta`() = runBlocking {
        val fuori = SolClient({ Configurazione("http://8.8.8.8:8200", "t") })
        val e = fuori.gruppi() as Esito.Errore
        assertEquals(SolClient.CODICE_INDIRIZZO_NON_PROTETTO, e.codice)
        assertEquals(Testi.INDIRIZZO_NON_PROTETTO, e.messaggio)
    }

    @Test
    fun `la password Gmail non parte mai in chiaro, nemmeno in casa`() = runBlocking {
        val casa = SolClient({ Configurazione("http://192.168.1.51:8200", "t") })
        val e = casa.aggiungiGmail("rossi.studio@gmail.com", "Personale", "abcd efgh ijkl mnop") as Esito.Errore
        assertEquals(Testi.PASSWORD_SU_HTTP, e.messaggio)
        val r = casa.ricollegaGmail(2, "abcd efgh ijkl mnop") as Esito.Errore
        assertEquals(Testi.PASSWORD_SU_HTTP, r.messaggio)
    }

    @Test
    fun `novita col cursore e prima volta senza`() = runBlocking {
        server.rispondi("GET /api/novita", """{"cursore": "8131"}""")
        assertEquals("8131", (client.novita(null) as Esito.Ok).dato.cursore)
        client.novita("8131")
        assertEquals(listOf("/api/novita", "/api/novita?dal=8131"), server.richieste.map { it.path })
    }

    @Test
    fun `abbinamento senza token, col tipo android`() = runBlocking {
        server.rispondi("POST /api/abbina", """{"token": "${"a".repeat(64)}", "dispositivo_id": 3}""")
        val esito = SolClient.abbina(server.url, "48213377", "Telefono di Manuel") as Esito.Ok
        assertEquals(3L, esito.dato.dispositivoId)
        val r = server.richieste.single()
        assertNull(r.getHeader("Authorization"))
        assertEquals("""{"codice":"48213377","nome":"Telefono di Manuel","tipo":"android"}""", r.body.readUtf8())
    }

    @Test
    fun `abbinamento rifiutato o bloccato si spiega`() = runBlocking {
        server.rispondi("POST /api/abbina", "", 401)
        val e = SolClient.abbina(server.url, "11111111", "Telefono") as Esito.Errore
        assertTrue(e.messaggio.contains("sbagliato o scaduto"))
        server.rispondi("POST /api/abbina", "", 429)
        assertTrue((SolClient.abbina(server.url, "11111111", "Telefono") as Esito.Errore).messaggio.contains("15 minuti"))
    }
}
