package eu.stgm.sol.ui

import androidx.compose.ui.graphics.Color
import eu.stgm.sol.Esempi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Conteggi
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.ui.dettaglio.Barra
import eu.stgm.sol.ui.dettaglio.barraPer
import eu.stgm.sol.ui.posta.Piastrella
import eu.stgm.sol.ui.posta.StatoPosta
import eu.stgm.sol.ui.posta.VoceDaFare
import eu.stgm.sol.ui.posta.apparteneAllaVista
import eu.stgm.sol.ui.posta.piastrelleDaFare
import eu.stgm.sol.ui.tema.ColoreGruppo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le regole del nuovo aspetto (docs/aspetto.md v1.3) che non dipendono dal disegno. */
class AspettoTest {

    private val rossi: Mail = JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL)
    private val bozzaPronta: Bozza = JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza())

    // --- Colori dei gruppi ------------------------------------------------------------

    @Test
    fun `colori dei gruppi - i 12 nomi del contratto con le tinte esatte della guida`() {
        // nome, base, etichetta chiara (sfondo, testo), etichetta scura (sfondo, testo)
        val guida = listOf(
            listOf("blu", 0xFF1D4ED8, 0xFFDBEAFE, 0xFF1E40AF, 0xFF1E3A8A, 0xFFBFDBFE),
            listOf("verde", 0xFF15803D, 0xFFDCFCE7, 0xFF166534, 0xFF14532D, 0xFFBBF7D0),
            listOf("ambra", 0xFFB45309, 0xFFFEF3C7, 0xFF92400E, 0xFF78350F, 0xFFFDE68A),
            listOf("viola", 0xFF7E22CE, 0xFFF3E8FF, 0xFF6B21A8, 0xFF581C87, 0xFFE9D5FF),
            listOf("grigio", 0xFF4B5563, 0xFFF3F4F6, 0xFF374151, 0xFF374151, 0xFFE5E7EB),
            listOf("ardesia", 0xFF334155, 0xFFE2E8F0, 0xFF1E293B, 0xFF1E293B, 0xFFCBD5E1),
            listOf("petrolio", 0xFF0F766E, 0xFFCCFBF1, 0xFF115E59, 0xFF134E4A, 0xFF99F6E4),
            listOf("rosa", 0xFFBE185D, 0xFFFCE7F3, 0xFF9D174D, 0xFF831843, 0xFFFBCFE8),
            listOf("indaco", 0xFF4338CA, 0xFFE0E7FF, 0xFF3730A3, 0xFF312E81, 0xFFC7D2FE),
            listOf("oliva", 0xFF4D7C0F, 0xFFECFCCB, 0xFF3F6212, 0xFF365314, 0xFFD9F99D),
            listOf("marrone", 0xFF92400E, 0xFFFFEDD5, 0xFF7C2D12, 0xFF7C2D12, 0xFFFED7AA),
            listOf("ciano", 0xFF0E7490, 0xFFCFFAFE, 0xFF155E75, 0xFF164E63, 0xFFA5F3FC),
        )
        assertEquals(12, ColoreGruppo.entries.size)
        guida.forEach { riga ->
            val c = ColoreGruppo.daNome(riga[0] as String)
            assertEquals(riga[0], c.nome)
            assertEquals("${c.nome} base", Color(riga[1] as Long), c.base)
            assertEquals("${c.nome} chiaro sfondo", Color(riga[2] as Long), c.chiaroFondo)
            assertEquals("${c.nome} chiaro testo", Color(riga[3] as Long), c.chiaroTesto)
            assertEquals("${c.nome} scuro sfondo", Color(riga[4] as Long), c.scuroFondo)
            assertEquals("${c.nome} scuro testo", Color(riga[5] as Long), c.scuroTesto)
        }
    }

    @Test
    fun `colori dei gruppi - nome sconosciuto, vuoto o mancante diventa ardesia`() {
        assertEquals(ColoreGruppo.ARDESIA, ColoreGruppo.daNome(null))
        assertEquals(ColoreGruppo.ARDESIA, ColoreGruppo.daNome(""))
        assertEquals(ColoreGruppo.ARDESIA, ColoreGruppo.daNome("fucsia"))
        assertEquals(ColoreGruppo.ARDESIA, ColoreGruppo.daNome("#1D4ED8"))
        assertEquals("maiuscole e spazi non contano", ColoreGruppo.BLU, ColoreGruppo.daNome(" Blu "))
    }

    @Test
    fun `colori dei gruppi - il campo colore del contratto arriva nel Gruppo`() {
        val gruppi = JsonSol.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(eu.stgm.sol.dati.Gruppo.serializer()),
            """[{"id": 1, "nome": "Clienti", "colore": "blu"}, {"id": 9, "nome": "Vecchio server"}]""",
        )
        assertEquals(ColoreGruppo.BLU, ColoreGruppo.daNome(gruppi[0].colore))
        assertNull(gruppi[1].colore)
        assertEquals("server senza colore: ripiego", ColoreGruppo.ARDESIA, ColoreGruppo.daNome(gruppi[1].colore))
    }

    // --- Segnali e iniziali ---------------------------------------------------------------

    @Test
    fun `segnali - al massimo due, nell'ordine della guida`() {
        val tutto = rossi.copy(sospetta = true, urgenza = "alta", smistamento = Mail.SMISTAMENTO_DA_CONTROLLARE)
        assertEquals(listOf(Segnale.TRUFFA, Segnale.URGENTE), segnaliPer(tutto))
        assertEquals(
            listOf(Segnale.TRUFFA, Segnale.URGENTE, Segnale.DA_CONTROLLARE, Segnale.RISPOSTA_PRONTA),
            segnaliPer(tutto, massimo = Int.MAX_VALUE),
        )
        assertEquals(listOf(Segnale.URGENTE, Segnale.RISPOSTA_PRONTA), segnaliPer(rossi))
        assertEquals(
            listOf(Segnale.DA_CONTROLLARE, Segnale.RISPOSTA_PRONTA),
            segnaliPer(rossi.copy(urgenza = "normale", smistamento = Mail.SMISTAMENTO_DA_CONTROLLARE)),
        )
        assertEquals("già risposta: niente risposta pronta", emptyList<Segnale>(), segnaliPer(rossi.copy(urgenza = "normale", rispostaData = true)))
        assertEquals("da rispondere non è più un segnale", emptyList<Segnale>(), segnaliPer(rossi.copy(urgenza = "normale", bozza = null)))
    }

    @Test
    fun `iniziali del mittente`() {
        assertEquals("MR", iniziali("Mario Rossi", "rossi@esempio.it"))
        assertEquals("CG", iniziali("Comune di Bassano del Grappa", "pec@esempio.it"))
        assertEquals("R", iniziali("", "rossi@esempio.it"))
        assertEquals("MR", iniziali(null, "mario.rossi@esempio.it"))
        assertEquals("un nome che è un indirizzo vale come indirizzo", "I", iniziali("info@esempio.it", "info@esempio.it"))
        assertEquals("?", iniziali(null, ""))
    }

    // --- Riquadro "Da fare" -----------------------------------------------------------------

    @Test
    fun `Da fare - nascosto se tutto a zero o senza conteggi, solo le voci con qualcosa`() {
        assertTrue(piastrelleDaFare(null).isEmpty())
        assertTrue(piastrelleDaFare(Conteggi()).isEmpty())
        assertTrue("urgenti e da leggere non sono piastrelle", piastrelleDaFare(Conteggi(urgenti = 3, daLeggere = 9)).isEmpty())
        assertEquals(
            listOf(
                Piastrella(VoceDaFare.DA_RISPONDERE, 5),
                Piastrella(VoceDaFare.TRUFFE, 1),
                Piastrella(VoceDaFare.DA_CONTROLLARE, 3),
                Piastrella(VoceDaFare.SCADENZE, 2),
            ),
            piastrelleDaFare(Conteggi(daRispondere = 5, sospette = 1, daControllare = 3, scadenzeProssime = 2)),
        )
        assertEquals(listOf(VoceDaFare.SCADENZE), piastrelleDaFare(Conteggi(scadenzeProssime = 1)).map { it.voce })
    }

    @Test
    fun `Da fare - solo in Tutte, e ogni piastrella sa cosa apre`() {
        val conteggi = Conteggi(daRispondere = 5)
        assertEquals(1, StatoPosta(conteggi = conteggi).piastrelle.size)
        assertTrue("in un gruppo non c'è", StatoPosta(gruppoId = 1, conteggi = conteggi).piastrelle.isEmpty())
        assertEquals(FiltroRapido.DA_RISPONDERE, VoceDaFare.DA_RISPONDERE.filtro)
        assertEquals(FiltroRapido.SOSPETTE, VoceDaFare.TRUFFE.filtro)
        assertEquals(FiltroRapido.DA_CONTROLLARE, VoceDaFare.DA_CONTROLLARE.filtro)
        assertNull("le scadenze aprono la loro scheda", VoceDaFare.SCADENZE.filtro)
        assertEquals("possibile truffa", Piastrella(VoceDaFare.TRUFFE, 1).etichetta)
        assertEquals("scadenze entro 7 giorni", Piastrella(VoceDaFare.SCADENZE, 2).etichetta)
    }

    @Test
    fun `Da fare - sotto il numero un'etichetta corta, sempre su due righe`() {
        val corte = listOf(
            Piastrella(VoceDaFare.DA_RISPONDERE, 5),
            Piastrella(VoceDaFare.TRUFFE, 2),
            Piastrella(VoceDaFare.TRUFFE, 1),
            Piastrella(VoceDaFare.DA_CONTROLLARE, 3),
            Piastrella(VoceDaFare.SCADENZE, 2),
            Piastrella(VoceDaFare.SCADENZE, 1),
        ).map { it.etichettaCorta }
        assertEquals(
            listOf("da\nrispondere", "possibili\ntruffe", "possibile\ntruffa", "da\ncontrollare", "scadenze\n7 giorni", "scadenza\n7 giorni"),
            corte,
        )
        corte.forEach { e ->
            val righe = e.lines()
            assertEquals(e, 2, righe.size)
            // Larghe al massimo 11 lettere: in una piastrella da un quarto di 360 dp ci stanno.
            assertTrue(e, righe.all { it.length <= 11 })
        }
    }

    @Test
    fun `conteggi - scadenze_prossime del contratto 17`() {
        val c = JsonSol.decodeFromString(Conteggi.serializer(), """{"da_rispondere": 1, "scadenze_prossime": 4}""")
        assertEquals(4, c.scadenzeProssime)
        assertEquals("server vecchio: zero", 0, JsonSol.decodeFromString(Conteggi.serializer(), "{}").scadenzeProssime)
    }

    @Test
    fun `dopo un gesto la mail resta solo se e ancora della vista`() {
        val inFornitori = rossi.copy(gruppoId = 2, smistamento = "corretta")
        assertTrue(apparteneAllaVista(inFornitori, null, FiltroRapido.TUTTE))
        assertFalse(apparteneAllaVista(inFornitori, 1, FiltroRapido.TUTTE))
        assertTrue(apparteneAllaVista(inFornitori, 2, FiltroRapido.TUTTE))
        assertFalse("spostata a mano non è più da controllare", apparteneAllaVista(inFornitori, null, FiltroRapido.DA_CONTROLLARE))
        assertTrue(apparteneAllaVista(rossi, null, FiltroRapido.DA_RISPONDERE))
        assertFalse(apparteneAllaVista(rossi, null, FiltroRapido.SOSPETTE))
    }

    // --- Barra in basso della mail aperta ----------------------------------------------------

    private fun conBozza(b: Bozza? = bozzaPronta, campi: CampiBozza? = null) = StatoBozza(bozza = b, campi = campi)

    @Test
    fun `barra - risposta pronta = Rifai e Invia`() {
        assertEquals(Barra.RifaiInvia(invioAttivo = true, rifaiAttivo = true), barraPer(rossi, conBozza()))
        val inConferma = conBozza().copy(confermaInvio = RichiestaConferma(55, 3, listOf("rossi@esempio.it"), emptyList(), "Re", "Buongiorno"))
        assertEquals("durante la conferma niente secondo tocco", Barra.RifaiInvia(invioAttivo = false, rifaiAttivo = false), barraPer(rossi, inConferma))
        assertEquals(Barra.RifaiInvia(invioAttivo = false, rifaiAttivo = false), barraPer(rossi, conBozza().copy(inCorso = true)))
        assertEquals("anche se la mail è sospetta, se la risposta è stata chiesta", Barra.RifaiInvia(true, true), barraPer(rossi.copy(sospetta = true), conBozza()))
        assertEquals(Barra.RifaiInvia(true, true), barraPer(rossi.copy(rispostaData = true), conBozza()))
    }

    @Test
    fun `barra - testo vuoto, Invia spento finche non si scrive`() {
        val vuota = bozzaPronta.copy(testo = "")
        assertEquals(Barra.RifaiInvia(invioAttivo = false, rifaiAttivo = true), barraPer(rossi, conBozza(vuota)))
        val scritta = CampiBozza.da(vuota).copy(testo = "Buongiorno, arriva giovedì.")
        assertEquals(Barra.RifaiInvia(invioAttivo = true, rifaiAttivo = true), barraPer(rossi, conBozza(vuota, scritta)))
    }

    @Test
    fun `barra - controlli in corso al posto dei pulsanti`() {
        assertEquals(Barra.Attesa("Controllo se è partita..."), barraPer(rossi, conBozza().copy(verificaInvio = true)))
        assertEquals(Barra.Attesa("Rileggo la risposta..."), barraPer(rossi, conBozza().copy(preparazioneConferma = true)))
    }

    @Test
    fun `barra - mentre Sol scrive`() {
        assertEquals(Barra.Scrivendo, barraPer(rossi, conBozza(bozzaPronta.copy(stato = Bozza.IN_PREPARAZIONE))))
        assertEquals(Barra.Scrivendo, barraPer(rossi, conBozza().copy(richiestaRisposta = true)))
        assertEquals(Barra.Scrivendo, barraPer(rossi.copy(bozza = null), StatoBozza(richiestaRisposta = true)))
        assertEquals(Barra.Scrivendo, barraPer(rossi, conBozza().copy(inAttesa = true)))
    }

    @Test
    fun `barra - senza risposta`() {
        val senza = rossi.copy(bozza = null)
        assertEquals(Barra.Prepara, barraPer(senza, StatoBozza()))
        assertEquals("sospetta senza risposta chiesta: nessuna barra", Barra.Nessuna, barraPer(senza.copy(sospetta = true), StatoBozza()))
        assertEquals(Barra.Nessuna, barraPer(senza.copy(daRispondere = false), StatoBozza()))
        assertEquals(Barra.Nessuna, barraPer(senza.copy(rispostaData = true), StatoBozza()))
        assertEquals("scartata ma da rispondere", Barra.Prepara, barraPer(rossi, conBozza(bozzaPronta.copy(stato = Bozza.SCARTATA))))
        assertEquals("inviata", Barra.Nessuna, barraPer(rossi, conBozza(bozzaPronta.copy(stato = Bozza.INVIATA))))
        assertEquals("mentre la si legge", Barra.Nessuna, barraPer(rossi, StatoBozza(caricamento = true)))
    }

    @Test
    fun `barra - sollecito senza mail`() {
        assertEquals(Barra.RifaiInvia(true, true), barraPer(null, conBozza(bozzaPronta.copy(tipo = "sollecito"))))
        assertEquals(Barra.Nessuna, barraPer(null, StatoBozza()))
        assertEquals(Barra.Nessuna, barraPer(null, conBozza(bozzaPronta.copy(stato = Bozza.SCARTATA))))
    }
}
