package eu.stgm.sol.esecuzione

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// L'archivio su file è l'unica persistenza della fase 1: si prova che
// scrive davvero su disco (nuova istanza = riletto) e che gli id non si
// riusano mai.
class PromemoriaRepoLocaleTest {

    private fun dirTemporanea(): File =
        File(System.getProperty("java.io.tmpdir"), "sol-test-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test
    fun `crea assegna id progressivi e persiste su disco`() = runBlocking {
        val dir = dirTemporanea()
        val repo = PromemoriaRepoLocale(dir)
        assertEquals(1, repo.crea("bolletta", "2026-08-04T09:00").id)
        assertEquals(2, repo.crea("chiamare il commercialista", null).id)

        val riletto = PromemoriaRepoLocale(dir) // nuova istanza = rilegge il file
        assertEquals(listOf(1, 2), riletto.lista().map { it.id })
        assertEquals("2026-08-04T09:00", riletto.perId(1)?.scadenzaIso)
        assertNull(riletto.perId(2)?.scadenzaIso)
    }

    @Test
    fun `completare toglie dalla lista ma non dall'archivio`() = runBlocking {
        val repo = PromemoriaRepoLocale(dirTemporanea())
        repo.crea("bolletta", null)
        repo.completa(1)
        assertTrue(repo.lista().isEmpty())
        assertEquals(true, repo.perId(1)?.completato)
    }

    @Test
    fun `eliminare toglie davvero e l'id non si riusa`() = runBlocking {
        val dir = dirTemporanea()
        val repo = PromemoriaRepoLocale(dir)
        repo.crea("uno", null)
        assertEquals("uno", repo.elimina(1)?.titolo)
        assertNull(repo.perId(1))
        assertEquals(2, repo.crea("due", null).id) // mai riusare il 1
        assertEquals(2, PromemoriaRepoLocale(dir).lista().single().id)
    }

    @Test
    fun `spostare cambia la scadenza e ripristina la rimette com'era`() = runBlocking {
        val repo = PromemoriaRepoLocale(dirTemporanea())
        val prima = repo.crea("riunione", "2026-08-04T09:00")
        repo.sposta(1, "2026-08-05T15:00")
        assertEquals("2026-08-05T15:00", repo.perId(1)?.scadenzaIso)
        repo.ripristina(prima)
        assertEquals("2026-08-04T09:00", repo.perId(1)?.scadenzaIso)
    }

    @Test
    fun `un file corrotto non blocca l'app`() = runBlocking {
        val dir = dirTemporanea()
        File(dir, "promemoria.json").writeText("{non è json")
        val repo = PromemoriaRepoLocale(dir)
        assertTrue(repo.lista().isEmpty())
        assertEquals(1, repo.crea("nuovo", null).id)
        assertTrue(File(dir, "promemoria.corrotto.json").exists()) // copia per il debug
    }
}
