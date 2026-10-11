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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import eu.stgm.sol.AppDiProva
import eu.stgm.sol.Esempi
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.RegolaFinestre
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.ui.dettaglio.DettaglioScreen
import eu.stgm.sol.ui.dettaglio.DettaglioViewModel
import eu.stgm.sol.ui.dettaglio.Tono
import eu.stgm.sol.ui.posta.PostaScreen
import eu.stgm.sol.ui.posta.PostaViewModel
import eu.stgm.sol.ui.posta.RaccoltaScreen
import eu.stgm.sol.ui.posta.RicercaScreen
import eu.stgm.sol.ui.tema.SolTema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Manuel: Posta Importanti/Tutte e raccolte, gesti sulle righe, ricerca, la mail
 * aperta con la risposta di Sol, le pillole di tono e la conferma d'invio.
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

    /** "Amministrazione e fatture" è una raccolta (18.2): nella Posta Importanti è una riga sola. */
    private val gruppiConRaccolta = Esempi.GRUPPI_COLORATI.replace(
        "\"colore\": \"ambra\",",
        "\"colore\": \"ambra\", \"raccolta\": true,",
    )

    private fun serverPosta(daControllare: String = """{"mail": [], "altre": false}""") {
        server.rispondi("GET /api/gruppi", gruppiConRaccolta)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi(
            "GET /api/conteggi",
            """{"da_rispondere": 5, "da_controllare": 1, "gruppi": {"1": {"da_leggere": 4, "totale": 120},
               "3": {"da_leggere": 1, "totale": 6, "ultima": {"mittente": "Banca Esempio", "oggetto": "Estratto conto settembre", "ricevuta_il": null}}}}""",
        )
        server.rispondi("GET /api/mail", Esempi.PAGINA_MAIL.replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail?smistamento=da_controllare&limite=50", daControllare.replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail?gruppo_id=1&limite=50", """{"mail": [${Esempi.MAIL.replace("2026-10-10", oggi)}], "altre": false}""")
        server.rispondi("GET /api/mail?gruppo_id=3&limite=50", """{"mail": [${Esempi.MAIL_BIANCHI.replace("2026-10-10", oggi)}], "altre": false}""")
    }

    private fun posta(
        onApriSmista: () -> Unit = {},
        onApriRaccolta: (Long) -> Unit = {},
        onApriRicerca: () -> Unit = {},
    ): PostaViewModel {
        val vm = PostaViewModel(client, CacheMemoria())
        mostra { PostaScreen(vm, onApriMail = {}, onApriSmista = onApriSmista, onApriRaccolta = onApriRaccolta, onApriRicerca = onApriRicerca) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null && !vm.stato.value.caricamento }
        // Lo stato del ViewModel arriva prima del disegno: si aspetta di vedere le righe.
        aspetta { esiste("Offerta capannone Bassano") }
        return vm
    }

    private val corpiSposta get() = server.richiesteA("POST /api/mail/812/sposta").map { it.body.clone().readUtf8() }

    // --- Posta: Importanti, Tutte, raccolte (docs/aspetto.md §4) ----------------------------------

    @Test
    fun `Importanti - mail sotto il loro gruppo, la raccolta e una riga sola, Tutte mostra tutto`() {
        serverPosta()
        var raccolta: Long? = null
        val vm = posta(onApriRaccolta = { raccolta = it })
        assertTrue("sezione col nome del gruppo", esiste("Clienti"))
        assertTrue(esiste("Raccolte"))
        assertTrue("la riga chiusa dice quante e l'ultima", esiste("6 mail · Estratto conto settembre"))
        assertTrue("la fattura sta nella raccolta, non in elenco", !esiste("Fattura ottobre"))

        compose.onNodeWithText("Tutte").performClick()
        aspetta { esiste("Fattura ottobre") }
        assertEquals(eu.stgm.sol.ui.posta.ModoPosta.TUTTE, vm.stato.value.modo)
        assertTrue("in Tutte niente raccolte", !esiste("Raccolte"))

        compose.onNodeWithText("Importanti").performClick()
        aspetta { esiste("Raccolte") }
        compose.onNodeWithContentDescription("Amministrazione e fatture, 6 mail · Estratto conto settembre").performClick()
        assertEquals(3L, raccolta)
    }

    @Test
    fun `la raccolta si apre con le sue righe e segna tutte lette`() {
        serverPosta()
        server.rispondi("POST /api/mail/815/letta", "{}")
        val vm = PostaViewModel(client, CacheMemoria())
        val bianchiNonLetta = Esempi.MAIL_BIANCHI.replace("\"letta\": true", "\"letta\": false")
        server.rispondi("GET /api/mail?gruppo_id=3&limite=50", """{"mail": [${bianchiNonLetta.replace("2026-10-10", oggi)}], "altre": false}""")
        mostra { RaccoltaScreen(vm, 3, onApriMail = {}, onIndietro = {}) }
        aspetta { vm.stato.value.gruppoId == 3L && vm.stato.value.caricataAlmenoUnaVolta && !vm.stato.value.caricamento }
        aspetta { esiste("Fattura ottobre") && esiste("Amministrazione e fatture") }
        compose.onNodeWithText("Segna tutte lette").performClick()
        aspetta { server.richiesteA("POST /api/mail/815/letta").isNotEmpty() }
        assertEquals("""{"letta":true}""", server.richiesteA("POST /api/mail/815/letta").single().body.readUtf8())
    }

    @Test
    fun `mail da sistemare - la scheda apre Smista, la ricerca si apre dal pulsante tondo`() {
        serverPosta(daControllare = """{"mail": [${Esempi.MAIL_BIANCHI}], "altre": false}""")
        var smista = false
        var ricerca = false
        posta(onApriSmista = { smista = true }, onApriRicerca = { ricerca = true })
        aspetta { esiste("1 mail da sistemare") }
        compose.onNodeWithText("1 mail da sistemare").performClick()
        assertTrue(smista)
        compose.onNodeWithContentDescription("Cerca nella posta").performClick()
        assertTrue(ricerca)
    }

    @Test
    fun `modalita prova - la pillola spiega in un foglio, niente frasi fisse in pagina`() {
        serverPosta()
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = true))
        val vm = posta()
        aspetta { vm.stato.value.modalitaProva }
        aspetta { esiste("Prova") }
        assertTrue(!esiste("Sol per ora suggerisce", substring = true))
        compose.onNodeWithText("Prova").performClick()
        aspetta { esiste("Cos'è la modalità prova") }
    }

    private fun esiste(testo: String, substring: Boolean) =
        compose.onAllNodes(hasText(testo, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `ricerca - si scrive e arrivano le righe, vuota nessuna richiesta`() {
        serverPosta()
        server.rispondi("GET /api/mail?cerca=capannone&limite=50", """{"mail": [${Esempi.MAIL.replace("2026-10-10", oggi)}], "altre": false}""")
        val vm = PostaViewModel(client, CacheMemoria())
        mostra { RicercaScreen(vm, onApriMail = {}, onIndietro = {}) }
        compose.waitForIdle()
        assertTrue(server.richiesteA("GET /api/mail").isEmpty())
        compose.onNode(hasSetTextAction()).performTextInput("capannone")
        aspetta { server.richiesteA("GET /api/mail").any { it.path == "/api/mail?cerca=capannone&limite=50" } }
        aspetta { esiste("Offerta capannone Bassano") }
    }

    // --- Gesti (docs/aspetto.md §4) ----------------------------------------------------------

    @Test
    fun `scorrere a sinistra apre Sposta in, la scelta sposta e Annulla la rimette nel gruppo di prima`() {
        serverPosta()
        server.inFila("POST /api/mail/812/sposta", inFornitori, Esempi.MAIL.replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\""))
        posta()
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Ricorda questo mittente").assertExists()
        compose.onNode(hasText("offerte, ordini e consegne dei fornitori")).performClick()

        aspetta { esiste("Spostata in Fornitori") }
        assertEquals(listOf("""{"gruppo_id":2,"impara":true}"""), corpiSposta)

        compose.onNodeWithText("Annulla").performClick()
        aspetta { corpiSposta.size == 2 }
        assertEquals("""{"gruppo_id":1,"impara":true}""", corpiSposta.last())
    }

    @Test
    fun `nella raccolta la mail spostata altrove esce dalla vista`() {
        serverPosta()
        server.rispondi(
            "POST /api/mail/812/sposta",
            inFornitori.replace("2026-10-10", oggi),
        )
        val vm = PostaViewModel(client, CacheMemoria())
        mostra { RaccoltaScreen(vm, 1, onApriMail = {}, onIndietro = {}) }
        aspetta { vm.stato.value.gruppoId == 1L && vm.stato.value.mail.size == 1 && !vm.stato.value.caricamento }
        aspetta { esiste("Offerta capannone Bassano") }
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeLeft() }
        compose.onNode(hasText("offerte, ordini e consegne dei fornitori")).performClick()
        aspetta { corpiSposta.size == 1 }
        aspetta { !esiste("Offerta capannone Bassano") }
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

    // --- La mail aperta (docs/aspetto.md §5) ---------------------------------------------------

    private fun dettaglio(json: String = Esempi.DETTAGLIO, bozza: String? = Esempi.bozza()): DettaglioViewModel {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/mail/812", json.replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail/820", json.replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail/815", json.replace("2026-10-10", oggi))
        if (bozza != null) server.rispondi("GET /api/bozze/55", bozza)
        val vm = DettaglioViewModel(client, CacheMemoria(), intervalloBozzaMs = 20)
        val id = Regex("\"id\": (\\d+)").find(json)!!.groupValues[1].toLong()
        mostra { DettaglioScreen(vm, id) {} }
        aspetta { vm.stato.value.mail != null && !vm.stato.value.caricamento && !vm.bozza.stato.value.caricamento }
        aspetta { esiste(vm.stato.value.mail!!.oggetto) }
        return vm
    }

    @Test
    fun `Invia risposta apre la conferma di sempre e non invia da sola`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Invia risposta") }
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        compose.onNodeWithText("Inviare questa mail?").assertExists()
        assertTrue(server.richiesteA("POST /api/bozze/55/invia").isEmpty())
        compose.onNodeWithText("Annulla").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio == null }
        assertTrue(server.richiesteA("POST /api/bozze/55/invia").isEmpty())
    }

    @Test
    fun `si scrive direttamente nella risposta, Invia risposta salva e poi chiede il si`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Invia risposta") }
        server.rispondi("PUT /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\ngiovedì alle 10.", versione = 4, modificata = true))
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\ngiovedì alle 10.", versione = 4, modificata = true))
        compose.onNode(hasSetTextAction() and hasText("Buongiorno Mario,\nl'offerta arriva giovedì."))
            .performScrollTo()
            .performTextReplacement("Buongiorno Mario,\ngiovedì alle 10.")
        compose.onNodeWithText("Modifiche non salvate").assertExists()
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        assertTrue(server.richiesteA("PUT /api/bozze/55").single().body.readUtf8().contains("giovedì alle 10."))
        assertEquals(4, vm.bozza.stato.value.confermaInvio!!.versione)
        assertTrue(server.richiesteA("POST /api/bozze/55/invia").isEmpty())
    }

    @Test
    fun `pillole di tono - ognuna rifa la risposta con la sua istruzione`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        server.rispondi("POST /api/bozze/55/rifai", Esempi.bozza("in_preparazione"), 202)
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = "Buongiorno, arriva giovedì.", versione = 4))
        compose.onNodeWithText("Più breve").performScrollTo().performClick()
        aspetta { server.richiesteA("POST /api/bozze/55/rifai").size == 1 }
        val corpo = Json.parseToJsonElement(server.richiesteA("POST /api/bozze/55/rifai").single().body.clone().readUtf8()).jsonObject
        assertEquals(Tono.BREVE.istruzione, corpo["istruzioni"]!!.jsonPrimitive.content)
        aspetta { vm.bozza.stato.value.bozza?.testo == "Buongiorno, arriva giovedì." && !vm.bozza.stato.value.inAttesa }
        aspetta { esiste("Buongiorno, arriva giovedì.") }

        compose.onNodeWithText("Più formale").performScrollTo().performClick()
        aspetta { server.richiesteA("POST /api/bozze/55/rifai").size == 2 }
        val secondo = Json.parseToJsonElement(server.richiesteA("POST /api/bozze/55/rifai").last().body.clone().readUtf8()).jsonObject
        assertEquals(Tono.FORMALE.istruzione, secondo["istruzioni"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Altro - la richiesta a parole diventa l'istruzione`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        server.rispondi("POST /api/bozze/55/rifai", Esempi.bozza("in_preparazione"), 202)
        compose.onNodeWithText("Altro…").performScrollTo().performClick()
        aspetta { esiste("Come la vuoi?") }
        compose.onNode(hasContentDescription("Come rifare la risposta")).performTextInput("digli che giovedì va bene")
        compose.onNodeWithText("Rifai la risposta").performClick()
        aspetta { server.richiesteA("POST /api/bozze/55/rifai").size == 1 }
        val corpo = Json.parseToJsonElement(server.richiesteA("POST /api/bozze/55/rifai").single().body.clone().readUtf8()).jsonObject
        assertEquals("digli che giovedì va bene", corpo["istruzioni"]!!.jsonPrimitive.content)
    }

    @Test
    fun `senza risposta ma da rispondere, Prepara la risposta, poi Sol sta scrivendo`() {
        val senzaBozza = Esempi.DETTAGLIO.replace("\"bozza\": {\"id\": 55, \"stato\": \"pronta\"}", "\"bozza\": null")
        server.rispondi("POST /api/mail/812/bozza", Esempi.bozza("in_preparazione"), 202)
        server.rispondi("GET /api/bozze/55", Esempi.bozza("in_preparazione"))
        dettaglio(json = senzaBozza, bozza = null)
        compose.onNodeWithText("Invia risposta").assertDoesNotExist()
        compose.onNodeWithText("Prepara la risposta").performClick()
        aspetta { server.richiesteA("POST /api/mail/812/bozza").isNotEmpty() }
        aspetta { esiste("Sol sta scrivendo la risposta…") }
        compose.onNodeWithText("Prepara la risposta").assertDoesNotExist()
    }

    @Test
    fun `mail sospetta senza risposta chiesta, nessuna barra e l'avviso`() {
        dettaglio(json = Esempi.DETTAGLIO_SOSPETTO, bozza = null)
        compose.onNodeWithText("Non è una truffa").assertExists()
        assertTrue("l'avviso del contratto sopra il riassunto", esiste("potrebbe essere una truffa", substring = true))
        compose.onNodeWithText("Prepara la risposta").assertDoesNotExist()
        compose.onNodeWithText("Invia risposta").assertDoesNotExist()
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

    @Test
    fun `nella mail aperta niente frasi d'aiuto fisse`() {
        val vm = dettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        listOf("dettare", "Gli allegati si aprono", "Sol per ora suggerisce", "Oppure scorri").forEach {
            assertTrue(it, !esiste(it, substring = true))
        }
    }
}
