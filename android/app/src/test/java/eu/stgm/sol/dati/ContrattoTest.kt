package eu.stgm.sol.dati

import eu.stgm.sol.Esempi
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il JSON del contratto si legge tutto, e un campo in più o in meno non rompe l'app. */
class ContrattoTest {

    @Test
    fun `la mail del contratto si legge campo per campo`() {
        val m = JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL)
        assertEquals(812L, m.id)
        assertEquals("Mario Rossi", m.mittente.nome)
        assertEquals("rossi@esempio.it", m.mittente.indirizzo)
        assertEquals("Offerta capannone Bassano", m.oggetto)
        assertEquals("2026-10-10T08:12:00+02:00", m.ricevutaIl)
        assertTrue(m.daRispondere)
        assertTrue(m.urgente)
        assertTrue(m.bozzaPronta)
        assertFalse(m.daControllare)
        assertFalse(m.applicataInCasella)
        assertEquals(0.93, m.sicurezza!!, 0.0001)
        assertEquals("decisore", m.origineSmistamento)
        assertEquals(RifBozza(55, "pronta"), m.bozza)
        assertEquals(listOf(Allegato("pianta.pdf", 482113)), m.allegati)
    }

    @Test
    fun `pagina di mail con altre e la mail da controllare senza bozza`() {
        val p = JsonSol.decodeFromString(PaginaMail.serializer(), Esempi.PAGINA_MAIL)
        assertEquals(2, p.mail.size)
        assertTrue(p.altre)
        val bianchi = p.mail[1]
        assertTrue(bianchi.daControllare)
        assertNull(bianchi.bozza)
        assertFalse(bianchi.bozzaPronta)
    }

    @Test
    fun `nel dettaglio i destinatari possono essere stringhe o oggetti`() {
        val m = JsonSol.decodeFromString(Mail.serializer(), Esempi.DETTAGLIO)
        assertEquals(listOf(Indirizzo(null, "studio@esempio.it")), m.destinatari)
        assertEquals("Anna Neri", m.cc.single().etichetta)
        assertTrue(m.corpo!!.startsWith("Buongiorno,\nci servirebbe"))
        assertTrue(m.probabilita!!.containsKey("gruppo"))
    }

    @Test
    fun `campi sconosciuti ignorati, mancanti col valore di base`() {
        val m = JsonSol.decodeFromString(Mail.serializer(), """{"id": 1, "campo_del_futuro": {"x": 1}, "mittente": null}""")
        assertEquals(1L, m.id)
        assertEquals("", m.mittente.indirizzo)
        assertEquals("normale", m.urgenza)
        assertFalse(m.urgente)
        assertTrue(m.allegati.isEmpty())
    }

    @Test
    fun `bozza, stati e inviabilita`() {
        val b = JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza())
        assertEquals(listOf("rossi@esempio.it"), b.a)
        assertEquals("Re: Offerta capannone Bassano", b.oggetto)
        assertTrue(b.testo.contains("\n"))
        assertTrue(b.inviabile)
        assertFalse(JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza("in_preparazione")).inviabile)
        assertTrue(JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza("errore")).inviabile)
        assertFalse(JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza("inviata")).inviabile)
        assertTrue(JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza("scartata")).chiusa)
    }

    @Test
    fun `riassunto parziale, finale e di oggi`() {
        val parziale = JsonSol.decodeFromString(
            Riassunto.serializer(),
            """{"id": 9, "richiesta": "le mail di Rossi", "stato": "in_corso", "testo_parziale": "Rossi ha scritto", "testo": null, "mail_citate": [], "errore": null, "creato_il": "2026-10-10T09:00:00+02:00"}""",
        )
        assertTrue(parziale.inCorso)
        assertEquals("Rossi ha scritto", parziale.testoVisibile)
        val oggi = JsonSol.decodeFromString(
            Riassunto.serializer(),
            """{"testo": "Oggi 14 mail, 3 importanti: Rossi [#812]", "aggiornato_il": "2026-10-10T09:00:00+02:00", "mail_citate": [812, 815], "stato": "pronto"}""",
        )
        assertFalse(oggi.inCorso)
        assertEquals(listOf(812L, 815L), oggi.mailCitate)
        assertEquals("Oggi 14 mail, 3 importanti: Rossi [#812]", oggi.testoVisibile)
    }

    @Test
    fun `scadenza, niente resta indietro, impostazioni, versioni, casella, gruppi`() {
        val s = JsonSol.decodeFromString(Scadenza.serializer(), Esempi.SCADENZA)
        assertEquals("2026-10-16", s.quando)
        assertNull(s.ora)

        val i = JsonSol.decodeFromString(Indietro.serializer(), Esempi.INDIETRO)
        assertEquals(3, i.aspettanoTe.single().giorni)
        assertEquals(77L, i.aspettiTu.single().inviata.id)
        assertEquals(listOf("neri@esempio.it"), i.aspettiTu.single().inviata.a)
        assertNull(i.aspettiTu.single().bozzaSollecito)

        val imp = JsonSol.decodeFromString(ImpostazioniSol.serializer(), Esempi.impostazioni())
        assertTrue(imp.modalitaProva)
        assertEquals(0.85, imp.sogliaSicurezza, 0.0001)
        assertEquals("Cordiali saluti\nManuel", imp.firma)

        val v = JsonSol.decodeFromString(Versioni.serializer(), Esempi.VERSIONE)
        assertEquals(VersioneApp(1, "1.0.0"), v.android)

        val c = JsonSol.decodeFromString(Casella.serializer(), Esempi.CASELLA)
        assertEquals("collegata", c.stato)
        assertEquals("2026-10-10T08:00:00+02:00", c.attivaDal)

        val g = JsonSol.decodeFromString(ListSerializer(Gruppo.serializer()), Esempi.GRUPPI)
        assertEquals(4, g.first().daLeggere)
    }

    @Test
    fun `collegamento outlook e novita`() {
        val o = JsonSol.decodeFromString(
            CollegamentoOutlook.serializer(),
            """{"casella": {"id": 2, "tipo": "outlook", "indirizzo": null, "nome": "Studio", "stato": "in_collegamento"},
               "codice_utente": "ABCD-EFGH", "url_verifica": "https://microsoft.com/devicelogin", "scade_il": "2026-10-10T10:00:00+02:00"}""",
        )
        assertEquals("ABCD-EFGH", o.codiceUtente)
        assertEquals("in_collegamento", o.casella!!.stato)

        val n = JsonSol.decodeFromString(
            Novita.serializer(),
            """{"cursore": "8131", "urgenti": [${Esempi.MAIL}], "da_rispondere_nuove": 2, "bozze_pronte": 1, "scadenze_domani": [${Esempi.SCADENZA}]}""",
        )
        assertEquals("8131", n.cursore)
        assertEquals(812L, n.urgenti.single().id)
        assertEquals(1, n.bozzePronte)
        assertEquals(4L, n.scadenzeDomani.single().id)
        assertEquals("8131", JsonSol.decodeFromString(Novita.serializer(), """{"cursore": "8131"}""").cursore)
    }

    @Test
    fun `il sollecito di una mail inviata come oggetto, come bozza intera o come solo id`() {
        fun voce(bozza: String) = JsonSol.decodeFromString(
            AspettiTu.serializer(),
            """{"inviata": {"id": 77, "a": ["neri@esempio.it"], "oggetto": "Offerta compressore"}, "giorni": 8, "bozza_sollecito": $bozza}""",
        ).bozzaSollecito
        assertEquals(RifBozza(90, "pronta"), voce("""{"id": 90, "stato": "pronta"}"""))
        assertEquals(RifBozza(55, "pronta"), voce(Esempi.bozza()))
        assertEquals(RifBozza(91, ""), voce("91"))
        assertNull(voce("null"))
    }

    @Test
    fun `un cursore numerico si legge come testo`() {
        assertEquals("8131", JsonSol.decodeFromString(Novita.serializer(), """{"cursore": 8131}""").cursore)
    }

    @Test
    fun `v1_1 - versione e prima riga della bozza, testo vuoto non inviabile`() {
        val b = JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza(versione = 7))
        assertEquals(7, b.versione)
        assertEquals("Buongiorno Mario,", b.primaRiga)
        val vuota = JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza(testo = " \n  "))
        assertTrue(vuota.senzaTesto)
        assertFalse(vuota.inviabile)
    }

    @Test
    fun `v1_1 - PEC, bozza pronta solo se non hai gia risposto`() {
        val pec = JsonSol.decodeFromString(
            Mail.serializer(),
            Esempi.MAIL.trimEnd().removeSuffix("}") +
                """, "pec": {"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}}""",
        )
        assertEquals("posta-certificata@pec.esempio.it", pec.pec!!.mittenteBusta)
        assertEquals("pec.esempio.it", pec.pec!!.gestore)
        assertNull(JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL).pec)

        val risposta = JsonSol.decodeFromString(Mail.serializer(), Esempi.DETTAGLIO_GIA_RISPOSTA)
        assertTrue(risposta.rispostaData)
        assertFalse("con risposta data niente segno «Bozza pronta»", risposta.bozzaPronta)
    }

    @Test
    fun `v1_1 - novita con bozze nuove, conteggi, arretrato, salute, versioni con impronta, gruppi con descrizione`() {
        val n = JsonSol.decodeFromString(
            Novita.serializer(),
            """{"cursore": "9", "bozze_pronte": 4, "bozze_nuove": [{"id": 55, "mail_id": 812, "oggetto": "Re: Offerta capannone Bassano"}]}""",
        )
        assertEquals(listOf(BozzaNuova(55, 812, "Re: Offerta capannone Bassano")), n.bozzeNuove)

        val c = JsonSol.decodeFromString(
            Conteggi.serializer(),
            """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "da_leggere": 12, "bozze_pronte": 4,
               "gruppi": {"1": {"da_leggere": 4, "totale": 120}}}""",
        )
        assertEquals(5, c.daRispondere)
        assertEquals(ConteggioGruppo(4, 120), c.gruppi["1"])

        assertEquals(37, JsonSol.decodeFromString(Arretrato.serializer(), """{"da_applicare": 37}""").daApplicare)
        assertEquals(false, JsonSol.decodeFromString(Salute.serializer(), """{"ok": true, "versione": "1.0.0", "autenticato": false}""").autenticato)

        val v = JsonSol.decodeFromString(
            Versioni.serializer(),
            """{"server": "1.1.0", "android": {"codice": 2, "nome": "1.1.0", "sha256": "ab12", "byte": 12500244}}""",
        )
        assertEquals(VersioneApp(2, "1.1.0", "ab12", 12500244), v.android)

        val g = JsonSol.decodeFromString(Gruppo.serializer(), """{"id": 1, "nome": "Clienti", "descrizione": "un cliente chiede o conferma un lavoro"}""")
        assertEquals("un cliente chiede o conferma un lavoro", g.descrizione)
    }

    @Test
    fun `v1_2 - possibile truffa, probabilita, conteggio e soglia`() {
        val m = JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL_SOSPETTA)
        assertTrue(m.sospetta)
        assertFalse("di base una mail non è sospetta", JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL).sospetta)
        val d = JsonSol.decodeFromString(Mail.serializer(), Esempi.DETTAGLIO_SOSPETTO)
        assertEquals(0.82, d.probabilitaTruffa!!, 0.0001)
        assertNull(JsonSol.decodeFromString(Mail.serializer(), Esempi.DETTAGLIO).probabilitaTruffa)

        assertEquals(2, JsonSol.decodeFromString(Conteggi.serializer(), """{"sospette": 2}""").sospette)
        assertEquals(0, JsonSol.decodeFromString(Conteggi.serializer(), """{"urgenti": 1}""").sospette)

        assertEquals(0.5, JsonSol.decodeFromString(ImpostazioniSol.serializer(), Esempi.impostazioni()).sogliaTruffa, 0.0001)
        val imp = JsonSol.decodeFromString(
            ImpostazioniSol.serializer(),
            Esempi.impostazioni().trimEnd().removeSuffix("}") + """, "soglia_truffa": 0.35}""",
        )
        assertEquals(0.35, imp.sogliaTruffa, 0.0001)
    }

    @Test
    fun `una risposta qualunque non passa per impostazioni con la modalita prova accesa`() {
        val esito = runCatching { JsonSol.decodeFromString(ImpostazioniSol.serializer(), """{"ok": true}""") }
        assertTrue(esito.isFailure)
    }

    @Test
    fun `i corpi delle richieste usano i nomi del contratto`() {
        assertEquals("""{"gruppo_id":2,"impara":true}""", JsonSol.encodeToString(CorpoSposta.serializer(), CorpoSposta(2, true)))
        assertEquals(
            """{"codice":"48213377","nome":"Telefono di Manuel","tipo":"android"}""",
            JsonSol.encodeToString(CorpoAbbina.serializer(), CorpoAbbina("48213377", "Telefono di Manuel")),
        )
        assertEquals(
            """{"descrizione":"Sopralluogo Bianchi","quando":"2026-10-20"}""",
            JsonSol.encodeToString(NuovaScadenza.serializer(), NuovaScadenza("Sopralluogo Bianchi", "2026-10-20")),
        )
    }

    @Test
    fun `l'errore del server si legge in cima o dentro detail`() {
        val inCima = leggiErroreServer("""{"errore": "Google ha rifiutato la password per le app.", "codice": "non_valido"}""")!!
        assertEquals("Google ha rifiutato la password per le app." to "non_valido", inCima.errore to inCima.codice)
        val dentro = leggiErroreServer("""{"detail": {"errore": "Bozza già inviata.", "codice": "conflitto"}}""")!!
        assertEquals("Bozza già inviata." to "conflitto", dentro.errore to dentro.codice)
        assertNull(leggiErroreServer(null))
        assertNull(leggiErroreServer("Bad Gateway"))
        assertNull(leggiErroreServer("""{"detail": "Not Found"}"""))
        assertNull(leggiErroreServer("""[1, 2]"""))
        assertEquals("giorni_risposta", leggiErroreServer("""{"errore": "x", "codice": "non_valido", "campo": "giorni_risposta"}""")!!.campo)
    }
}
