package eu.stgm.sol.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import eu.stgm.sol.AppDiProva
import eu.stgm.sol.Esempi
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.RegolaFinestre
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.ui.dettaglio.DettaglioScreen
import eu.stgm.sol.ui.dettaglio.DettaglioViewModel
import eu.stgm.sol.ui.posta.PostaScreen
import eu.stgm.sol.ui.posta.PostaViewModel
import eu.stgm.sol.ui.tema.SolTema
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Le schermate vere (ViewModel veri contro il server finto) toccate come le tocca
 * Manuel: gesti sulle righe, riquadro "Da fare", barra in basso della mail aperta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w393dp-h851dp-xxhdpi")
class InterfacciaTest {
    @get:Rule(order = 0)
    val main = RegolaMain()

    @get:Rule(order = 1)
    val finestre = RegolaFinestre()

    @get:Rule(order = 2)
    val compose = createComposeRule()

    private val server = ServerFinto()
    private val client = server.client()
    private val oggi = LocalDate.now().toString()

    @After
    fun chiudi() = server.close()

    private fun mostra(contenuto: @Composable () -> Unit) {
        val messaggi = SnackbarHostState()
        compose.setContent {
            // Senza movimenti: i gesti e i loro esiti, non le animazioni.
            SolTema(scuro = false, movimenti = false) {
                CompositionLocalProvider(LocalMessaggi provides messaggi) {
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { contenuto() }
                        SnackbarHost(messaggi, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    // Largo: sotto Robolectric, con la macchina carica, il disegno a volte arriva tardi.
    private fun aspetta(condizione: () -> Boolean) = compose.waitUntil(20_000) { condizione() }

    private fun esiste(testo: String) = compose.onAllNodes(hasText(testo)).fetchSemanticsNodes().isNotEmpty()

    private val inFornitori = Esempi.MAIL
        .replace("\"gruppo_id\": 1, \"gruppo\": \"Clienti\"", "\"gruppo_id\": 2, \"gruppo\": \"Fornitori\"")
        .replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\"")

    private fun serverPosta(conteggi: String = """{"da_rispondere": 5, "da_controllare": 3, "scadenze_prossime": 2}""") {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/conteggi", conteggi)
        server.rispondi("GET /api/mail", Esempi.PAGINA_MAIL.replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail?gruppo_id=1&limite=50", """{"mail": [${Esempi.MAIL.replace("2026-10-10", oggi)}], "altre": false}""")
    }

    private fun posta(onApriScadenze: () -> Unit = {}): PostaViewModel {
        val vm = PostaViewModel(client, CacheMemoria())
        mostra { PostaScreen(vm, onApriMail = {}, onApriScadenze = onApriScadenze) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null && !vm.stato.value.caricamento }
        // Lo stato del ViewModel arriva prima del disegno: si aspetta di vedere le righe.
        aspetta { esiste("Offerta capannone Bassano") }
        return vm
    }

    private val corpiSposta get() = server.richiesteA("POST /api/mail/812/sposta").map { it.body.clone().readUtf8() }

    // --- Gesti (docs/aspetto.md §4) ----------------------------------------------------------

    @Test
    fun `scorrere a sinistra apre Sposta in, la scelta sposta e Annulla la rimette nel gruppo di prima`() {
        serverPosta()
        server.inFila("POST /api/mail/812/sposta", inFornitori, Esempi.MAIL.replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\""))
        val vm = posta()
        // Nella scheda Clienti: spostata altrove, la riga deve uscire.
        compose.onNodeWithContentDescription("Clienti, 4 da leggere").performClick()
        aspetta { vm.stato.value.gruppoId == 1L && !vm.stato.value.caricamento && vm.stato.value.mail.size == 1 }
        aspetta { esiste("Offerta capannone Bassano") && !esiste("Fattura ottobre") }

        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Ricorda questo mittente").assertExists()
        compose.onNode(hasText("offerte, ordini e consegne dei fornitori")).performClick()

        aspetta { compose.onAllNodes(hasText("Spostata in Fornitori")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("""{"gruppo_id":2,"impara":true}"""), corpiSposta)
        aspetta { compose.onAllNodes(hasText("Offerta capannone Bassano")).fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithText("Annulla").performClick()
        aspetta { corpiSposta.size == 2 }
        assertEquals("""{"gruppo_id":1,"impara":true}""", corpiSposta.last())
        aspetta { compose.onAllNodes(hasText("Offerta capannone Bassano")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `scorrere a sinistra e chiudere il foglio non sposta niente`() {
        serverPosta()
        posta()
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Ricorda questo mittente").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        assertTrue(corpiSposta.isEmpty())
        compose.onNodeWithText("Offerta capannone Bassano").assertExists()
    }

    @Test
    fun `scorrere a destra segna letta, e di nuovo da leggere`() {
        serverPosta()
        server.rispondi("POST /api/mail/812/letta", "{}")
        val vm = posta()
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeRight() }
        aspetta { server.richiesteA("POST /api/mail/812/letta").size == 1 }
        assertEquals("""{"letta":true}""", server.richiesteA("POST /api/mail/812/letta").single().body.readUtf8())
        aspetta { vm.stato.value.mail.first { it.id == 812L }.letta }
        compose.waitForIdle()
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeRight() }
        aspetta { server.richiesteA("POST /api/mail/812/letta").size == 2 }
        assertEquals("""{"letta":false}""", server.richiesteA("POST /api/mail/812/letta").last().body.readUtf8())
    }

    // --- Riquadro "Da fare" -----------------------------------------------------------------

    @Test
    fun `Da fare - nascosto se tutto e a zero`() {
        serverPosta(conteggi = """{"da_rispondere": 0, "urgenti": 2, "da_controllare": 0, "sospette": 0, "scadenze_prossime": 0}""")
        posta()
        compose.onNodeWithText("Da fare").assertDoesNotExist()
    }

    @Test
    fun `Da fare - toccare una piastrella e un filtro, e togli filtro lo toglie`() {
        serverPosta()
        server.rispondi("GET /api/mail?da_rispondere=true&limite=50", """{"mail": [${Esempi.MAIL.replace("2026-10-10", oggi)}], "altre": false}""")
        val vm = posta()
        compose.onNodeWithText("Da fare").assertExists()
        compose.onNodeWithContentDescription("5 da rispondere").performClick()
        aspetta { server.richiesteA("GET /api/mail").any { it.path == "/api/mail?da_rispondere=true&limite=50" } }
        aspetta { !vm.stato.value.caricamento }
        compose.onNodeWithText("togli filtro").assertExists().performClick()
        aspetta { vm.stato.value.filtro == eu.stgm.sol.rete.FiltroRapido.TUTTE }
        compose.onNodeWithText("togli filtro").assertDoesNotExist()
    }

    @Test
    fun `Da fare - le scadenze aprono la scheda Scadenze`() {
        serverPosta()
        var aperta = false
        posta(onApriScadenze = { aperta = true })
        compose.onNodeWithContentDescription("2 scadenze entro 7 giorni. Apre le scadenze.").performClick()
        assertTrue(aperta)
    }

    // --- Barra in basso della mail aperta (docs/aspetto.md §3) -------------------------------

    private fun dettaglio(json: String = Esempi.DETTAGLIO, bozza: String? = Esempi.bozza()): DettaglioViewModel {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/mail/812", json.replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail/820", json.replace("2026-10-10", oggi))
        if (bozza != null) server.rispondi("GET /api/bozze/55", bozza)
        val vm = DettaglioViewModel(client, CacheMemoria(), intervalloBozzaMs = 20)
        val id = if (json.contains("\"id\": 820")) 820L else 812L
        mostra { DettaglioScreen(vm, id) {} }
        aspetta { vm.stato.value.mail != null && !vm.stato.value.caricamento && !vm.bozza.stato.value.caricamento }
        aspetta { esiste(vm.stato.value.mail!!.oggetto) }
        return vm
    }

    @Test
    fun `barra - risposta pronta, Invia apre la conferma di sempre e non invia da sola`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Rifai") }
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        compose.onNodeWithText("Inviare questa mail?").assertExists()
        assertTrue(server.richiesteA("POST /api/bozze/55/invia").isEmpty())
        compose.onNodeWithText("Annulla").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio == null }
        assertTrue(server.richiesteA("POST /api/bozze/55/invia").isEmpty())
    }

    @Test
    fun `barra - si scrive direttamente nella risposta, Invia salva e poi chiede il si`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Rifai") }
        server.rispondi("PUT /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\ngiovedì alle 10.", versione = 4, modificata = true))
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\ngiovedì alle 10.", versione = 4, modificata = true))
        compose.onNode(hasSetTextAction() and hasText("Buongiorno Mario,\nl'offerta arriva giovedì."))
            .performScrollTo()
            .performTextReplacement("Buongiorno Mario,\ngiovedì alle 10.")
        compose.onNodeWithText("Modifiche non ancora salvate", substring = true).assertExists()
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        assertTrue(server.richiesteA("PUT /api/bozze/55").single().body.readUtf8().contains("giovedì alle 10."))
        assertEquals(4, vm.bozza.stato.value.confermaInvio!!.versione)
        assertTrue(server.richiesteA("POST /api/bozze/55/invia").isEmpty())
    }

    @Test
    fun `barra - senza risposta ma da rispondere, Prepara la risposta, poi Sol sta scrivendo`() {
        val senzaBozza = Esempi.DETTAGLIO.replace("\"bozza\": {\"id\": 55, \"stato\": \"pronta\"}", "\"bozza\": null")
        server.rispondi("POST /api/mail/812/bozza", Esempi.bozza("in_preparazione"), 202)
        server.rispondi("GET /api/bozze/55", Esempi.bozza("in_preparazione"))
        dettaglio(json = senzaBozza, bozza = null)
        compose.onNodeWithText("Invia").assertDoesNotExist()
        compose.onNodeWithText("Prepara la risposta").performClick()
        aspetta { server.richiesteA("POST /api/mail/812/bozza").isNotEmpty() }
        aspetta { compose.onAllNodes(hasText("Sol sta scrivendo la risposta…")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Prepara la risposta").assertDoesNotExist()
    }

    @Test
    fun `barra - mail sospetta senza risposta chiesta, nessuna barra`() {
        dettaglio(json = Esempi.DETTAGLIO_SOSPETTO, bozza = null)
        compose.onNodeWithText("Non è una truffa").assertExists()
        compose.onNodeWithText("Prepara la risposta").assertDoesNotExist()
        compose.onNodeWithText("Invia").assertDoesNotExist()
        compose.onNodeWithText("Rifai").assertDoesNotExist()
    }

    @Test
    fun `mail da controllare - riga compatta, Si conferma il gruppo`() {
        val daControllare = Esempi.DETTAGLIO.replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"da_controllare\"")
        server.rispondi("POST /api/mail/812/conferma", "{}")
        dettaglio(json = daControllare)
        compose.onNodeWithText("È nel gruppo giusto?").assertExists()
        compose.onNodeWithText("Sì").performClick()
        aspetta { server.richiesteA("POST /api/mail/812/conferma").isNotEmpty() }
    }
}
