package eu.stgm.sol.notifiche

import eu.stgm.sol.Esempi
import eu.stgm.sol.MemoriaNovitaFinta
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.BozzaNuova
import eu.stgm.sol.dati.Indirizzo
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.Novita
import eu.stgm.sol.dati.Scadenza
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cosa merita di interrompere Manuel, e cosa no. */
class AvvisiTest {
    private val rossi = JsonSol.decodeFromString(Mail.serializer(), Esempi.MAIL)
    private val scadenza = JsonSol.decodeFromString(Scadenza.serializer(), Esempi.SCADENZA)

    private fun urgente(id: Long, nome: String = "Mario Rossi", letta: Boolean = false) =
        rossi.copy(id = id, mittente = Indirizzo(nome, "x@esempio.it"), letta = letta)

    private val bozzaNuova = BozzaNuova(id = 55, mailId = 812, oggetto = "Re: Offerta capannone Bassano")

    @Test
    fun `primo giro senza cursore, niente notifiche`() {
        val n = Novita(cursore = "1", urgenti = listOf(rossi), bozzeNuove = listOf(bozzaNuova), scadenzeDomani = listOf(scadenza))
        assertTrue(avvisiDaMostrare(n, emptySet(), primoGiro = true).isEmpty())
    }

    @Test
    fun `mail urgente non letta, con mittente e riassunto, apre quella mail`() {
        val a = avvisiDaMostrare(Novita(cursore = "2", urgenti = listOf(rossi)), emptySet(), primoGiro = false).single()
        assertEquals("Mail urgente da Mario Rossi", a.titolo)
        assertEquals("Rossi chiede l'offerta per il capannone di Bassano entro venerdì.", a.testo)
        assertEquals(812L, a.mailId)
        assertTrue(a.urgente)
    }

    @Test
    fun `una possibile truffa non si notifica come urgente`() {
        val n = Novita(cursore = "2", urgenti = listOf(rossi.copy(id = 820, sospetta = true)))
        assertTrue(avvisiDaMostrare(n, emptySet(), primoGiro = false).isEmpty())
    }

    @Test
    fun `urgente gia letta o gia avvisata no`() {
        val n = Novita(cursore = "2", urgenti = listOf(urgente(1, letta = true), urgente(2)))
        assertTrue(avvisiDaMostrare(n, setOf("urgente:2"), primoGiro = false).isEmpty())
    }

    @Test
    fun `solo mail nuove da rispondere, nessuna notifica`() {
        assertTrue(avvisiDaMostrare(Novita(cursore = "3", daRispondereNuove = 5), emptySet(), primoGiro = false).isEmpty())
    }

    @Test
    fun `bozze nuove - una notifica per bozza, che apre quella giusta`() {
        val sollecito = BozzaNuova(id = 90, mailId = null, oggetto = "Sollecito: offerta compressore")
        val avvisi = avvisiDaMostrare(Novita(cursore = "4", bozzeNuove = listOf(bozzaNuova, sollecito)), emptySet(), primoGiro = false)
        assertEquals(2, avvisi.size)
        assertEquals("Risposta pronta: Re: Offerta capannone Bassano", avvisi[0].titolo)
        assertEquals(812L, avvisi[0].mailId)
        assertEquals(55L, avvisi[0].bozzaId)
        assertNull("un sollecito non ha una mail ricevuta: si apre la bozza", avvisi[1].mailId)
        assertEquals(90L, avvisi[1].bozzaId)
    }

    @Test
    fun `il totale delle bozze pronte da solo non notifica mai`() {
        assertTrue(avvisiDaMostrare(Novita(cursore = "5", bozzePronte = 3), emptySet(), primoGiro = false).isEmpty())
    }

    @Test
    fun `scadenza di domani una volta sola anche se torna a ogni giro`() {
        val n = Novita(cursore = "6", scadenzeDomani = listOf(scadenza.copy(ora = "10:00")))
        val primo = avvisiDaMostrare(n, emptySet(), primoGiro = false).single()
        assertEquals("Domani: Offerta capannone Bassano a Rossi", primo.titolo)
        assertEquals("Scadenza di domani alle 10:00", primo.testo)
        assertEquals(812L, primo.mailId)
        val ricordati = avvisatiDaRicordare(n, emptySet(), listOf(primo))
        assertTrue(avvisiDaMostrare(n.copy(cursore = "7"), ricordati, primoGiro = false).isEmpty())
    }

    @Test
    fun `tante urgenti insieme, alcune singole e un riepilogo`() {
        val n = Novita(cursore = "8", urgenti = (1L..7L).map { urgente(it, "Mittente $it") })
        val avvisi = avvisiDaMostrare(n, emptySet(), primoGiro = false)
        assertEquals(MASSIMO_URGENTI_SINGOLE + 1, avvisi.size)
        val riepilogo = avvisi.last()
        assertEquals("Altre 3 mail urgenti", riepilogo.titolo)
        assertEquals("Mittente 5, Mittente 6, Mittente 7", riepilogo.testo)
    }

    @Test
    fun `la memoria tiene solo quello che puo tornare`() {
        val n = Novita(cursore = "9", urgenti = listOf(urgente(2)), scadenzeDomani = listOf(scadenza))
        val ricordati = avvisatiDaRicordare(n, setOf("urgente:1", "urgente:2", "scadenza:99:2026-01-01"), emptyList())
        assertEquals(setOf("urgente:2"), ricordati)
    }

    // --- Il giro col server ------------------------------------------------------------

    private val server = ServerFinto()

    @After
    fun chiudi() = server.close()

    @Test
    fun `il primo giro prende solo il cursore, il secondo notifica`() = runBlocking {
        val memoria = MemoriaNovitaFinta()
        val mostrati = mutableListOf<Avviso>()
        val giro = GiroNovita(server.client(), memoria) { mostrati += it }

        server.rispondi("GET /api/novita", """{"cursore": "100", "urgenti": [${Esempi.MAIL}]}""")
        assertEquals(EsitoGiro.FATTO, giro.giro())
        assertTrue(mostrati.isEmpty())
        assertEquals("100", memoria.cursore)

        server.rispondi(
            "GET /api/novita?dal=100",
            """{"cursore": "101", "urgenti": [${Esempi.MAIL}], "bozze_pronte": 4,
               "bozze_nuove": [{"id": 55, "mail_id": 812, "oggetto": "Re: Offerta capannone Bassano"}]}""",
        )
        assertEquals(EsitoGiro.FATTO, giro.giro())
        assertEquals(listOf("urgente:812", "bozza:55"), mostrati.map { it.chiave })
        assertEquals("101", memoria.cursore)
        assertTrue("urgente:812" in memoria.avvisati)
    }

    @Test
    fun `telefono revocato - una notifica, una volta sola`() = runBlocking {
        val memoria = MemoriaNovitaFinta().apply { cursore = "100" }
        val mostrati = mutableListOf<Avviso>()
        server.rispondi("GET /api/novita?dal=100", """{"errore": "Questo dispositivo non è abbinato o il suo accesso è stato revocato.", "codice": "non_autorizzato"}""", 401)
        val giro = GiroNovita(server.client(), memoria) { mostrati += it }
        assertEquals(EsitoGiro.NON_AUTORIZZATO, giro.giro())
        assertEquals(EsitoGiro.NON_AUTORIZZATO, giro.giro())
        assertEquals(listOf(AVVISO_SCOLLEGATO), mostrati)
        assertEquals("Sol non è più collegato", mostrati.single().titolo)
    }

    @Test
    fun `server spento, il giro si riprova e non tocca il cursore`() = runBlocking {
        val memoria = MemoriaNovitaFinta().apply { cursore = "50" }
        server.spegni()
        assertEquals(EsitoGiro.SENZA_RETE, GiroNovita(server.client(), memoria) {}.giro())
        assertEquals("50", memoria.cursore)
    }
}
