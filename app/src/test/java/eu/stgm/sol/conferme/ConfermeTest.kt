package eu.stgm.sol.conferme

import eu.stgm.sol.cervello.DefinizioniTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Il cancello è l'unico punto dove un bug diventa un danno dal vivo:
// la sua logica pura si prova su JVM, senza telefono.
class ConfermeTest {

    @Test
    fun `la frase rossa si riconosce anche sporca`() {
        assertEquals(
            CancelloConferme.FRASE_ROSSA,
            Normalizzatore.normalizza("  Timbro, e FIRMA! "),
        )
    }

    @Test
    fun `un si generico non apre il cancello rosso`() {
        listOf("sì", "va bene", "confermo", "ok", "certo").forEach {
            assertTrue(
                "\"$it\" non deve valere come frase rossa",
                Normalizzatore.normalizza(it) != CancelloConferme.FRASE_ROSSA,
            )
        }
    }

    @Test
    fun `le frasi di annullamento si riconoscono normalizzate`() {
        assertTrue(Normalizzatore.normalizza("Annulla!") in CancelloConferme.FRASI_ANNULLA)
        assertTrue(Normalizzatore.normalizza("lascia perdere") in CancelloConferme.FRASI_ANNULLA)
    }

    // I tool dichiarati a Claude e le regole di rischio devono restare la
    // stessa lista: un tool senza classe di rischio finirebbe ROSSO per il
    // ripiego prudente, uno con rischio ma senza definizione non esisterebbe.
    @Test
    fun `tool dichiarati a claude e regole di rischio sono allineati`() {
        assertEquals(DefinizioniTool.NOMI.toSet(), RegoleDiRischio.perTool.keys)
    }

    @Test
    fun `tool sconosciuto vuol dire rosso`() {
        assertEquals(Rischio.ROSSO, RegoleDiRischio.di("tool_mai_visto"))
    }

    @Test
    fun `ogni esito ha una frase da dire, il silenzio non esiste`() {
        val esiti = listOf(
            Esito.AnnullatoTimeout,
            Esito.AnnullatoFraseDiversa,
            Esito.AnnullatoDallUtente,
            Esito.Confermato("Confermato: eseguo."),
            Esito.Eseguito("Fatto."),
            Esito.Fallito("Non ci sono riuscito."),
        )
        esiti.forEach { assertTrue(it.frase.isNotBlank()) }
    }
}
