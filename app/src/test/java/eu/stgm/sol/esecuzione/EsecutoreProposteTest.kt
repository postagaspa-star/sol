package eu.stgm.sol.esecuzione

import eu.stgm.sol.cervello.Proposta
import eu.stgm.sol.conferme.Esito
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

// L'esecutore è il punto dove le proposte diventano azioni: qui si prova che
// il rosso non passa senza la frase giusta, che il giallo resta annullabile
// e che il timeout ANNULLA invece di eseguire.
class EsecutoreProposteTest {

    private class FakeRepo : PromemoriaRepo {
        val elenco = mutableListOf<Promemoria>()
        private var prossimo = 1

        override suspend fun crea(titolo: String, scadenzaIso: String?) =
            Promemoria(prossimo++, titolo, scadenzaIso).also { elenco.add(it) }

        override suspend fun lista() = elenco.filter { !it.completato }.sortedBy { it.id }

        override suspend fun perId(id: Int) = elenco.firstOrNull { it.id == id }

        override suspend fun completa(id: Int): Promemoria? {
            val i = elenco.indexOfFirst { it.id == id }
            if (i < 0) return null
            elenco[i] = elenco[i].copy(completato = true)
            return elenco[i]
        }

        override suspend fun sposta(id: Int, nuovaScadenzaIso: String): Promemoria? {
            val i = elenco.indexOfFirst { it.id == id }
            if (i < 0) return null
            elenco[i] = elenco[i].copy(scadenzaIso = nuovaScadenzaIso)
            return elenco[i]
        }

        override suspend fun elimina(id: Int): Promemoria? {
            val trovato = perId(id)
            elenco.removeAll { it.id == id }
            return trovato
        }

        override suspend fun ripristina(promemoria: Promemoria) {
            elenco.removeAll { it.id == promemoria.id }
            elenco.add(promemoria)
        }
    }

    private class FakePianificatore : Pianificatore {
        val pianificati = mutableListOf<Int>()
        val cancellati = mutableListOf<Int>()
        override fun pianifica(promemoria: Promemoria) { pianificati.add(promemoria.id) }
        override fun cancella(id: Int) { cancellati.add(id) }
    }

    private class FakeVoce(var daAscoltare: String? = null) : InterazioneVocale {
        val frasiLette = mutableListOf<String>()
        override suspend fun leggi(frase: String) { frasiLette.add(frase) }
        override suspend fun ascoltaUnaFrase(): String? = daAscoltare
    }

    private class Banco(val risposta: String? = null) {
        val repo = FakeRepo()
        val pianificatore = FakePianificatore()
        val voce = FakeVoce(risposta)
        var millis = 0L
        val esecutore = EsecutoreProposte(
            repo = repo,
            pianificatore = pianificatore,
            voce = voce,
            registro = { _, _ -> },
            adesso = { LocalDateTime.of(2026, 8, 3, 10, 0) },
            orologioMillis = { millis },
        )
    }

    @Test
    fun `il giallo esegue subito e resta annullabile`() = runBlocking {
        val banco = Banco()
        val r = banco.esecutore.esegui(
            Proposta("crea_promemoria", mapOf("titolo" to "bolletta", "scadenza" to "2026-08-04T09:00"))
        )
        assertTrue(r.contenutoPerClaude.startsWith("Creato il promemoria id 1"))
        assertFalse(r.turnoInterrotto)
        assertEquals(listOf(1), banco.pianificatore.pianificati)
        assertTrue(banco.esecutore.annullabile())
        val frase = banco.esecutore.annullaUltima()
        assertEquals("Annullato: ho tolto il promemoria bolletta.", frase)
        assertTrue(banco.repo.elenco.isEmpty())
        assertFalse(banco.esecutore.annullabile())
    }

    @Test
    fun `la finestra di annullo scade dopo trenta secondi`() = runBlocking {
        val banco = Banco()
        banco.esecutore.esegui(Proposta("crea_promemoria", mapOf("titolo" to "bolletta")))
        banco.millis = 31_000L
        assertFalse(banco.esecutore.annullabile())
        assertEquals("Non c'era più nulla da annullare.", banco.esecutore.annullaUltima())
        assertEquals(1, banco.repo.elenco.size) // niente è stato toccato
    }

    @Test
    fun `il rosso con la frase giusta elimina davvero`() = runBlocking {
        val banco = Banco(risposta = "timbro e firma")
        banco.repo.crea("vecchio", null)
        val r = banco.esecutore.esegui(Proposta("elimina_promemoria", mapOf("id" to "1")))
        assertEquals("Eliminato definitivamente: vecchio.", r.contenutoPerClaude)
        assertFalse(r.turnoInterrotto)
        assertTrue(banco.repo.elenco.isEmpty())
        // il riepilogo è stato letto prima di ascoltare
        assertTrue(banco.voce.frasiLette.first().startsWith("Sto per eliminare definitivamente"))
        // dopo un rosso non c'è finestra di annullo
        assertFalse(banco.esecutore.annullabile())
    }

    @Test
    fun `il rosso senza risposta annulla e lo dice`() = runBlocking {
        val banco = Banco(risposta = null) // non ha sentito nulla
        banco.repo.crea("vecchio", null)
        val r = banco.esecutore.esegui(Proposta("elimina_promemoria", mapOf("id" to "1")))
        assertTrue(r.turnoInterrotto)
        assertEquals(Esito.AnnullatoTimeout.frase, r.fraseImmediata)
        assertEquals(1, banco.repo.elenco.size) // NON eliminato
    }

    @Test
    fun `il rosso con una frase qualunque annulla`() = runBlocking {
        val banco = Banco(risposta = "va bene dai procedi")
        banco.repo.crea("vecchio", null)
        val r = banco.esecutore.esegui(Proposta("elimina_promemoria", mapOf("id" to "1")))
        assertTrue(r.turnoInterrotto)
        assertEquals(Esito.AnnullatoFraseDiversa.frase, r.fraseImmediata)
        assertEquals(1, banco.repo.elenco.size)
    }

    @Test
    fun `il rosso con annulla esplicito annulla con la frase gentile`() = runBlocking {
        val banco = Banco(risposta = "annulla")
        banco.repo.crea("vecchio", null)
        val r = banco.esecutore.esegui(Proposta("elimina_promemoria", mapOf("id" to "1")))
        assertTrue(r.turnoInterrotto)
        assertEquals(Esito.AnnullatoDallUtente.frase, r.fraseImmediata)
    }

    @Test
    fun `id inesistente sul rosso non apre nemmeno il cancello`() = runBlocking {
        val banco = Banco(risposta = "timbro e firma")
        val r = banco.esecutore.esegui(Proposta("elimina_promemoria", mapOf("id" to "99")))
        assertEquals("Nessun promemoria con quell'id.", r.contenutoPerClaude)
        assertFalse(r.turnoInterrotto)
        assertTrue(banco.voce.frasiLette.isEmpty()) // nessun riepilogo letto
    }

    @Test
    fun `completare e annullare rimette il promemoria attivo con la sua sveglia`() = runBlocking {
        val banco = Banco()
        banco.repo.crea("bolletta", "2026-08-04T09:00")
        banco.esecutore.esegui(Proposta("completa_promemoria", mapOf("id" to "1")))
        assertTrue(banco.repo.lista().isEmpty())
        val frase = banco.esecutore.annullaUltima()
        assertEquals("Annullato: bolletta è di nuovo attivo.", frase)
        assertEquals(1, banco.repo.lista().size)
        assertEquals(listOf(1), banco.pianificatore.pianificati) // sveglia rimessa
    }

    @Test
    fun `annulla ultima azione come tool verde`() = runBlocking {
        val banco = Banco()
        banco.esecutore.esegui(Proposta("crea_promemoria", mapOf("titolo" to "bolletta")))
        val r = banco.esecutore.esegui(Proposta("annulla_ultima_azione"))
        assertEquals("Annullato: ho tolto il promemoria bolletta.", r.contenutoPerClaude)
        assertTrue(banco.repo.elenco.isEmpty())
    }

    @Test
    fun `scadenza non valida non crea nulla`() = runBlocking {
        val banco = Banco()
        val r = banco.esecutore.esegui(
            Proposta("crea_promemoria", mapOf("titolo" to "x", "scadenza" to "domani"))
        )
        assertTrue(r.contenutoPerClaude.startsWith("Errore: scadenza non valida"))
        assertTrue(banco.repo.elenco.isEmpty())
        assertNull(r.fraseImmediata)
    }
}
