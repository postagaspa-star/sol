package eu.stgm.sol.ui

import androidx.compose.ui.graphics.Color
import eu.stgm.sol.Esempi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.ConteggioGruppo
import eu.stgm.sol.dati.Conteggi
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.Oggi
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.dati.TemaScelto
import eu.stgm.sol.dati.UltimaMail
import eu.stgm.sol.dati.VoceOggi
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.ui.dettaglio.Barra
import eu.stgm.sol.ui.dettaglio.Tono
import eu.stgm.sol.ui.dettaglio.barraPer
import eu.stgm.sol.ui.impostazioni.nomeTema
import eu.stgm.sol.ui.impostazioni.percentuale
import eu.stgm.sol.ui.oggi.AzioneVoce
import eu.stgm.sol.ui.oggi.StatoOggi
import eu.stgm.sol.ui.oggi.pillolaScadenza
import eu.stgm.sol.ui.oggi.rigaData
import eu.stgm.sol.ui.oggi.saluto
import eu.stgm.sol.ui.oggi.testoVoce
import eu.stgm.sol.ui.oggi.titoloSaluto
import eu.stgm.sol.ui.posta.RigaRaccolta
import eu.stgm.sol.ui.posta.apparteneAllaVista
import eu.stgm.sol.ui.posta.daLeggereImportanti
import eu.stgm.sol.ui.posta.righeRaccolte
import eu.stgm.sol.ui.posta.sezioniImportanti
import eu.stgm.sol.ui.scadenze.titoloGiorno
import eu.stgm.sol.ui.smista.testoSicurezza
import eu.stgm.sol.ui.tema.ColoreGruppo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Le regole di Sol 2.0 (docs/aspetto.md e contratto §18) che non dipendono dal disegno. */
class AspettoTest {

    private val rossi: Mail = JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL)
    private val bianchi: Mail = JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL_BIANCHI)
    private val bozzaPronta: Bozza = JsonSol.decodeFromString(Bozza.serializer(), Esempi.bozza())
    /** Domenica 11 ottobre 2026 (il prototipo dice sabato, il calendario no). */
    private val domenica = LocalDate.of(2026, 10, 11)

    // --- Colori dei gruppi ------------------------------------------------------------

    private fun luminanza(c: Color): Double {
        fun canale(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * canale(c.red) + 0.7152 * canale(c.green) + 0.0722 * canale(c.blue)
    }

    private fun contrasto(a: Color, b: Color): Double {
        val x = luminanza(a)
        val y = luminanza(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    @Test
    fun `colori dei gruppi - i 6 del prototipo con le tinte esatte, 12 in tutto`() {
        // nome, pieno, tenue, iniziali (docs/aspetto.md §1)
        val guida = listOf(
            listOf("blu", 0xFF2F6FDB, 0xFFEAF1FD, 0xFF1E4FA8),
            listOf("verde", 0xFF2B8A3E, 0xFFEBF7EE, 0xFF1F6A30),
            listOf("ambra", 0xFFB7791F, 0xFFFBF3E3, 0xFF8A5A12),
            listOf("viola", 0xFF7048E8, 0xFFF1ECFE, 0xFF5532C9),
            listOf("grigio", 0xFF868E96, 0xFFF1F3F5, 0xFF495057),
            listOf("ardesia", 0xFF495057, 0xFFEEF0F2, 0xFF343A40),
        )
        assertEquals(12, ColoreGruppo.entries.size)
        assertEquals(
            listOf("blu", "verde", "ambra", "viola", "grigio", "ardesia", "petrolio", "rosa", "indaco", "oliva", "marrone", "ciano"),
            ColoreGruppo.entries.map { it.nome },
        )
        guida.forEach { riga ->
            val c = ColoreGruppo.daNome(riga[0] as String)
            assertEquals("${c.nome} pieno", Color(riga[1] as Long), c.pieno)
            assertEquals("${c.nome} tenue", Color(riga[2] as Long), c.tenue)
            assertEquals("${c.nome} iniziali", Color(riga[3] as Long), c.inchiostro)
        }
    }

    @Test
    fun `colori dei gruppi - le iniziali si leggono sul cerchio, chiaro e scuro (4,5 a 1)`() {
        ColoreGruppo.entries.forEach { c ->
            assertTrue("${c.nome} chiaro", contrasto(c.inchiostro, c.tenue) >= 4.5)
            assertTrue("${c.nome} scuro", contrasto(c.inchiostroScuro, c.tenueScuro) >= 4.5)
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
    fun `colori dei gruppi - il campo colore e la raccolta del contratto arrivano nel Gruppo`() {
        val gruppi = JsonSol.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Gruppo.serializer()),
            """[{"id": 1, "nome": "Clienti", "colore": "blu"}, {"id": 9, "nome": "Newsletter", "raccolta": true}]""",
        )
        assertEquals(ColoreGruppo.BLU, ColoreGruppo.daNome(gruppi[0].colore))
        assertFalse("server vecchio: niente raccolta", gruppi[0].raccolta)
        assertTrue(gruppi[1].raccolta)
        assertEquals("server senza colore: ripiego", ColoreGruppo.ARDESIA, ColoreGruppo.daNome(gruppi[1].colore))
    }

    // --- Segno e iniziali -----------------------------------------------------------------

    @Test
    fun `al massimo un segno per riga - prima la truffa, poi la risposta pronta`() {
        assertEquals(Segno.TRUFFA, segnoPer(rossi.copy(sospetta = true)))
        assertEquals(Segno.RISPOSTA_PRONTA, segnoPer(rossi))
        assertNull("già risposta: niente risposta pronta", segnoPer(rossi.copy(rispostaData = true)))
        assertNull("urgente e da controllare non sono più segni", segnoPer(bianchi.copy(urgenza = "alta")))
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

    // --- Oggi (18.1) ----------------------------------------------------------------------

    @Test
    fun `Oggi - il saluto cambia con l'ora del giorno`() {
        assertEquals("Buongiorno", saluto(LocalTime.of(7, 30)))
        assertEquals("fino all'una è ancora mattina", "Buongiorno", saluto(LocalTime.of(12, 59)))
        assertEquals("Buon pomeriggio", saluto(LocalTime.of(13, 0)))
        assertEquals("Buon pomeriggio", saluto(LocalTime.of(17, 59)))
        assertEquals("Buonasera", saluto(LocalTime.of(18, 0)))
        assertEquals("Buonasera", saluto(LocalTime.of(23, 30)))
        assertEquals("a notte fonda", "Buonasera", saluto(LocalTime.of(2, 0)))
        assertEquals("Buongiorno", saluto(LocalTime.of(5, 0)))
        assertEquals("Buongiorno, Manuel", titoloSaluto(LocalTime.of(8, 0), "Manuel"))
        assertEquals("senza nome solo il saluto (18.3)", "Buonasera", titoloSaluto(LocalTime.of(20, 0), "  "))
    }

    @Test
    fun `Oggi - la riga della data`() {
        assertEquals("Domenica 11 ottobre · 14 mail nuove", rigaData(domenica, 14))
        assertEquals("Domenica 11 ottobre · 1 mail nuova", rigaData(domenica, 1))
        assertEquals("Domenica 11 ottobre · nessuna mail nuova", rigaData(domenica, 0))
        assertEquals("Sabato 10 ottobre · 3 mail nuove", rigaData(domenica.minusDays(1), 3))
    }

    @Test
    fun `Oggi - ogni voce di da_fare ha la sua frase e il pulsante giusto`() {
        val oggi = JsonSol.decodeFromString(Oggi.serializer(), Esempi.OGGI)
        val testi = oggi.daFare.map { testoVoce(it, domenica)!! }
        assertEquals(
            listOf(
                Triple("Possibile truffa", "Guarda", AzioneVoce.APRI_MAIL),
                Triple("Rispondi a Mario Rossi", "Rivedi", AzioneVoce.APRI_MAIL),
                Triple("Smista 3 mail", "Inizia", AzioneVoce.SMISTA),
                Triple("Luca Bianchi", "Rispondi", AzioneVoce.APRI_MAIL),
                Triple("Anna Neri", "Prepara", AzioneVoce.PREPARA_SOLLECITO),
                Triple("Offerta a Rossi", "Fatto", AzioneVoce.SCADENZA_FATTA),
            ),
            testi.map { Triple(it.titolo, it.pulsante, it.azione) },
        )
        assertEquals(StilePillola.TRUFFA, testi[0].stile)
        assertEquals("una sola azione piena: la risposta pronta", listOf(StilePillola.PIENA), testi.map { it.stile }.filter { it == StilePillola.PIENA })
        assertEquals("Offerta capannone Bassano · urgente, risposta pronta", testi[1].sotto)
        assertEquals("Venerdì 16 ottobre", testi[5].sotto)
        assertEquals("i giorni stanno sotto, il titolo resta su una riga", "Non risponde da 8 giorni · Offerta compressore", testi[4].sotto)
        val conBozza = oggi.daFare[4].copy(bozzaId = 90)
        assertEquals("sollecito già scritto: si rivede", AzioneVoce.APRI_BOZZA, testoVoce(conBozza, domenica)!!.azione)
        assertEquals("Rivedi", testoVoce(conBozza, domenica)!!.pulsante)
        assertNull("un tipo nuovo che l'app non conosce non si mostra", testoVoce(VoceOggi(tipo = "boh"), domenica))
    }

    @Test
    fun `Oggi - tutto fatto solo con la pagina letta e nessuna voce`() {
        assertFalse("ancora niente dal server", StatoOggi().tuttoFatto)
        assertTrue(StatoOggi(oggi = Oggi(daFare = emptyList())).tuttoFatto)
        assertTrue("solo voci sconosciute", StatoOggi(oggi = Oggi(daFare = listOf(VoceOggi(tipo = "boh")))).tuttoFatto)
        assertFalse(StatoOggi(oggi = Oggi(daFare = listOf(VoceOggi(tipo = VoceOggi.SMISTA, conteggio = 2)))).tuttoFatto)
    }

    @Test
    fun `Oggi - la pillola della scadenza piu vicina`() {
        val s = Scadenza(4, descrizione = "Offerta a Rossi", quando = "2026-10-16")
        assertEquals("Venerdì: offerta a Rossi", pillolaScadenza(s, domenica))
        assertEquals("Oggi: offerta a Rossi", pillolaScadenza(s.copy(quando = "2026-10-11"), domenica))
        assertEquals("Domani: offerta a Rossi", pillolaScadenza(s.copy(quando = "2026-10-12"), domenica))
        assertEquals("30 novembre: offerta a Rossi", pillolaScadenza(s.copy(quando = "2026-11-30"), domenica))
    }

    // --- Posta: Importanti, Tutte, raccolte (18.2) ---------------------------------------------

    private val gruppi = listOf(
        Gruppo(1, "Clienti", ordine = 1, colore = "blu", daLeggere = 4),
        Gruppo(2, "Fornitori", ordine = 2, colore = "verde"),
        Gruppo(3, "Amministrazione e fatture", ordine = 3, colore = "ambra", raccolta = true, daLeggere = 2, totale = 31),
        Gruppo(5, "Newsletter", ordine = 5, raccolta = true, totale = 0),
        Gruppo(6, "Altro", ordine = 6, daLeggere = 1),
    )

    @Test
    fun `Importanti - una sezione per gruppo, nell'ordine, senza le raccolte`() {
        val fornitore = rossi.copy(id = 900, gruppoId = 2, gruppo = "Fornitori")
        val senzaGruppo = rossi.copy(id = 901, gruppoId = 77, gruppo = "Sparito")
        val sezioni = sezioniImportanti(listOf(fornitore, bianchi, rossi, senzaGruppo), gruppi)
        assertEquals(listOf("Clienti", "Fornitori", null), sezioni.map { it.gruppo?.nome })
        assertEquals("la fattura sta nella sua raccolta", listOf(812L), sezioni[0].mail.map { it.id })
        assertEquals("gruppo sconosciuto: in fondo", listOf(901L), sezioni[2].mail.map { it.id })
    }

    @Test
    fun `raccolte - una riga sola chiusa, con l'ultima mail, solo se hanno mail`() {
        val conteggi = Conteggi(
            gruppi = mapOf("3" to ConteggioGruppo(daLeggere = 2, totale = 6, ultima = UltimaMail("Banca Esempio", "Estratto conto settembre", null))),
        )
        val righe = righeRaccolte(gruppi, conteggi)
        assertEquals("Newsletter vuota non si mostra", listOf("Amministrazione e fatture"), righe.map { it.gruppo.nome })
        assertEquals(RigaRaccolta(gruppi[2], 6, 2, UltimaMail("Banca Esempio", "Estratto conto settembre", null)), righe.single())
        assertEquals("6 mail · Estratto conto settembre", righe.single().sotto)
        assertEquals("senza conteggi valgono i numeri del gruppo", "31 mail", righeRaccolte(gruppi, null).single().sotto)
    }

    @Test
    fun `il pallino della Posta conta solo le importanti da leggere`() {
        assertEquals(5, daLeggereImportanti(gruppi, null))
        val conteggi = Conteggi(gruppi = mapOf("1" to ConteggioGruppo(daLeggere = 7, totale = 9), "3" to ConteggioGruppo(daLeggere = 50, totale = 50)))
        assertEquals("i conteggi vincono sui numeri del gruppo, la raccolta non conta", 8, daLeggereImportanti(gruppi, conteggi))
    }

    @Test
    fun `conteggi - ultima e scadenze_prossime del contratto`() {
        val c = JsonSol.decodeFromString(
            Conteggi.serializer(),
            """{"da_rispondere": 1, "scadenze_prossime": 4, "gruppi": {"3": {"da_leggere": 2, "totale": 6,
              "ultima": {"mittente": "Banca Esempio", "oggetto": "Estratto conto", "ricevuta_il": "2026-10-11T07:00:00+02:00"}},
              "1": {"da_leggere": 0, "totale": 3, "ultima": null}}}""",
        )
        assertEquals(4, c.scadenzeProssime)
        assertEquals("Estratto conto", c.gruppi["3"]!!.ultima!!.oggetto)
        assertNull(c.gruppi["1"]!!.ultima)
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

    // --- Smista, Scadenze, Impostazioni ---------------------------------------------------------

    @Test
    fun `Smista - quanto e sicuro Sol, in parole`() {
        assertEquals("abbastanza sicuro", testoSicurezza(0.75))
        assertEquals("poco sicuro", testoSicurezza(0.4))
        assertEquals("poco sicuro", testoSicurezza(null))
    }

    @Test
    fun `Scadenze - un titolo per giorno`() {
        assertEquals("Oggi", titoloGiorno("2026-10-11", domenica))
        assertEquals("Domani", titoloGiorno("2026-10-12", domenica))
        assertEquals("Venerdì 16", titoloGiorno("2026-10-16", domenica))
        assertEquals("Passate", titoloGiorno("2026-10-05", domenica))
        assertEquals("Lunedì 4 gennaio 2027", titoloGiorno("2027-01-04", domenica))
        assertEquals("rotta", titoloGiorno("rotta", domenica))
    }

    @Test
    fun `Impostazioni - i valori come si leggono nelle righe`() {
        assertEquals(listOf("Automatico", "Chiaro", "Scuro"), TemaScelto.entries.map(::nomeTema))
        assertEquals("85%", percentuale(0.85))
        assertEquals("50%", percentuale(0.5))
    }

    // --- La risposta di Sol: pillole di tono e barra in basso ------------------------------------

    @Test
    fun `pillole di tono - ognuna rifa la risposta con la sua istruzione`() {
        assertEquals(listOf("Più breve", "Più formale", "Chiedi più tempo"), Tono.entries.map { it.etichetta })
        assertTrue(Tono.entries.all { it.istruzione.isNotBlank() && it.istruzione.endsWith(".") })
        assertEquals(3, Tono.entries.map { it.istruzione }.toSet().size)
    }

    private fun conBozza(b: Bozza? = bozzaPronta, campi: CampiBozza? = null) = StatoBozza(bozza = b, campi = campi)

    @Test
    fun `barra - risposta pronta = Invia risposta`() {
        assertEquals(Barra.Invia(attivo = true), barraPer(rossi, conBozza()))
        val inConferma = conBozza().copy(confermaInvio = RichiestaConferma(55, 3, listOf("rossi@esempio.it"), emptyList(), "Re", "Buongiorno"))
        assertEquals("durante la conferma niente secondo tocco", Barra.Invia(attivo = false), barraPer(rossi, inConferma))
        assertEquals(Barra.Invia(attivo = false), barraPer(rossi, conBozza().copy(inCorso = true)))
        assertEquals("anche se la mail è sospetta, se la risposta è stata chiesta", Barra.Invia(true), barraPer(rossi.copy(sospetta = true), conBozza()))
        assertEquals(Barra.Invia(true), barraPer(rossi.copy(rispostaData = true), conBozza()))
    }

    @Test
    fun `barra - testo vuoto, Invia spento finche non si scrive`() {
        val vuota = bozzaPronta.copy(testo = "")
        assertEquals(Barra.Invia(attivo = false), barraPer(rossi, conBozza(vuota)))
        val scritta = CampiBozza.da(vuota).copy(testo = "Buongiorno, arriva giovedì.")
        assertEquals(Barra.Invia(attivo = true), barraPer(rossi, conBozza(vuota, scritta)))
    }

    @Test
    fun `barra - controlli in corso al posto del pulsante`() {
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
        assertEquals(Barra.Invia(true), barraPer(null, conBozza(bozzaPronta.copy(tipo = "sollecito"))))
        assertEquals(Barra.Nessuna, barraPer(null, StatoBozza()))
        assertEquals(Barra.Nessuna, barraPer(null, conBozza(bozzaPronta.copy(stato = Bozza.SCARTATA))))
    }
}
