package eu.stgm.sol.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import eu.stgm.sol.AppDiProva
import eu.stgm.sol.DepositoMemoria
import eu.stgm.sol.Esempi
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.RegolaFinestre
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.dati.PreferenzeAspetto
import eu.stgm.sol.dati.TemaScelto
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.ui.impostazioni.ImpostazioniScreen
import eu.stgm.sol.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.sol.ui.oggi.AzioniOggi
import eu.stgm.sol.ui.oggi.OggiScreen
import eu.stgm.sol.ui.oggi.OggiViewModel
import eu.stgm.sol.ui.smista.SmistaScreen
import eu.stgm.sol.ui.smista.SmistaViewModel
import eu.stgm.sol.ui.tema.SolTema
import eu.stgm.sol.ui.tutorial.TutorialAlPrimoAvvio
import eu.stgm.sol.ui.tutorial.TutorialScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.LocalTime

/** Le preferenze del telefono in memoria: tema e tutorial visto. */
class PreferenzeFinte(vistoAllInizio: Boolean = false) : PreferenzeAspetto {
    val temaScelto = MutableStateFlow(TemaScelto.AUTOMATICO)
    val visto = MutableStateFlow(vistoAllInizio)
    override val tema: Flow<TemaScelto> = temaScelto
    override suspend fun cambiaTema(tema: TemaScelto) {
        temaScelto.value = tema
    }
    override val tutorialVisto: Flow<Boolean> = visto
    override suspend fun segnaTutorialVisto() {
        visto.value = true
    }
}

/**
 * Le pagine nuove di Sol 2.0 toccate come Manuel: Oggi (ogni voce col suo pulsante,
 * tutto fatto, il saluto), Smista (va bene, cambia, fine), il tutorial (primo avvio
 * una volta sola, Salta, si riapre, nessuna chiamata al server) e le Impostazioni.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w393dp-h851dp-xxhdpi")
class PagineTest {
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

    private fun aspetta(condizione: () -> Boolean) = compose.waitUntil(20_000) { condizione() }
    private fun esiste(testo: String) = compose.onAllNodes(hasText(testo)).fetchSemanticsNodes().isNotEmpty()

    // --- Oggi -----------------------------------------------------------------------------

    private class Tocchi {
        val mail = mutableListOf<Long>()
        val bozze = mutableListOf<Pair<Long, Bozza?>>()
        var smista = 0
        var impostazioni = 0
        var scadenze = 0
        var aspetti = 0
        fun azioni() = AzioniOggi(
            apriMail = { mail += it },
            apriBozza = { id, b -> bozze += id to b },
            apriSmista = { smista++ },
            apriScadenze = { scadenze++ },
            apriAspetti = { aspetti++ },
            apriImpostazioni = { impostazioni++ },
        )
    }

    private fun serverOggi(json: String = Esempi.OGGI) {
        server.rispondi("GET /api/oggi", json.replace("2026-10-11", oggi))
        server.rispondi("GET /api/scadenze?stato=aperta", "[${Esempi.SCADENZA}]")
        server.rispondi("GET /api/indietro", Esempi.INDIETRO)
    }

    private fun paginaOggi(tocchi: Tocchi, ora: LocalTime = LocalTime.of(9, 0)): OggiViewModel {
        val vm = OggiViewModel(client, CacheMemoria())
        mostra { OggiScreen(vm, tocchi.azioni(), ora = { ora }) }
        aspetta { vm.stato.value.oggi != null && !vm.stato.value.caricamento }
        return vm
    }

    /** Oggi è un elenco pigro: prima si scorre fino alla voce, poi la si tocca. */
    private fun tocca(testo: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(testo))
        compose.onNodeWithText(testo).performClick()
    }

    @Test
    fun `Oggi - ogni voce ha il pulsante giusto`() {
        serverOggi()
        server.rispondi("POST /api/indietro/77/sollecito", Esempi.bozza(id = 90).replace("\"tipo\": \"risposta\"", "\"tipo\": \"sollecito\""))
        server.rispondi("PATCH /api/scadenze/4", "{}")
        val t = Tocchi()
        paginaOggi(t)
        aspetta { esiste("Possibile truffa") && esiste("Rispondi a Mario Rossi") }

        tocca("Guarda")
        assertEquals(listOf(820L), t.mail)
        tocca("Rivedi")
        assertEquals("la risposta pronta apre la mail", listOf(820L, 812L), t.mail)
        tocca("Inizia")
        assertEquals(1, t.smista)
        tocca("Rispondi")
        assertEquals(listOf(820L, 812L, 815L), t.mail)
        tocca("Prepara")
        aspetta { t.bozze.isNotEmpty() }
        assertEquals(90L, t.bozze.single().first)
        assertTrue("il sollecito appena scritto si mostra senza rileggerlo", t.bozze.single().second != null)
        tocca("Fatto")
        aspetta { server.richiesteA("PATCH /api/scadenze/4").isNotEmpty() }
        assertEquals("""{"stato":"fatta"}""", server.richiesteA("PATCH /api/scadenze/4").single().body.readUtf8())
    }

    @Test
    fun `Oggi - il cerchio con l'iniziale apre le Impostazioni, in fondo Scadenze e Aspetti risposta`() {
        serverOggi()
        val t = Tocchi()
        paginaOggi(t)
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        assertEquals(1, t.impostazioni)
        tocca("Scadenze")
        assertEquals(1, t.scadenze)
        tocca("Aspetti risposta")
        assertEquals(1, t.aspetti)
    }

    @Test
    fun `Oggi - tutto fatto, una riga calma e niente festa`() {
        serverOggi(Esempi.OGGI.replace(Regex("\"da_fare\": \\[.*?\\n      \\],", RegexOption.DOT_MATCHES_ALL), "\"da_fare\": [],"))
        val vm = paginaOggi(Tocchi())
        assertTrue(vm.stato.value.tuttoFatto)
        aspetta { esiste("Niente da fare. Bella giornata.") }
        compose.onNodeWithText("Guarda").assertDoesNotExist()
    }

    @Test
    fun `Oggi - il saluto segue l'ora del giorno e il nome`() {
        serverOggi()
        paginaOggi(Tocchi(), ora = LocalTime.of(15, 30))
        aspetta { esiste("Buon pomeriggio, Manuel") }
    }

    @Test
    fun `Oggi - di sera buonasera, senza nome solo il saluto`() {
        serverOggi(Esempi.OGGI.replace("\"nome\": \"Manuel\"", "\"nome\": \"\""))
        paginaOggi(Tocchi(), ora = LocalTime.of(20, 0))
        aspetta { esiste("Buonasera") }
    }

    // --- Smista -----------------------------------------------------------------------------

    private fun serverSmista() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        val prima = Esempi.MAIL_BIANCHI.replace("2026-10-10", oggi)
        val seconda = Esempi.MAIL.replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"da_controllare\"").replace("2026-10-10", oggi)
        server.rispondi("GET /api/mail?smistamento=da_controllare&limite=50", """{"mail": [$prima, $seconda, ${Esempi.MAIL_SOSPETTA}], "altre": false}""")
        server.rispondi("POST /api/mail/815/conferma", "{}")
        server.rispondi("POST /api/mail/812/sposta", Esempi.MAIL.replace("\"gruppo_id\": 1, \"gruppo\": \"Clienti\"", "\"gruppo_id\": 2, \"gruppo\": \"Fornitori\""))
    }

    @Test
    fun `Smista - va bene conferma, cambia sposta e ricorda, poi tutto sistemato`() {
        serverSmista()
        val vm = SmistaViewModel(client)
        var chiusa = false
        mostra { SmistaScreen(vm) { chiusa = true } }
        aspetta { !vm.stato.value.caricamento && vm.stato.value.attuale != null }
        aspetta { esiste("Fattura ottobre") }
        assertEquals("la possibile truffa non si smista", 2, vm.stato.value.totale)
        assertTrue(esiste("1 di 2"))

        compose.onNodeWithText("Va bene").performClick()
        aspetta { server.richiesteA("POST /api/mail/815/conferma").size == 1 }
        aspetta { esiste("Offerta capannone Bassano") && esiste("2 di 2") }

        compose.onNodeWithText("Cambia").performClick()
        aspetta { esiste("In quale gruppo va?") }
        compose.onNodeWithText("Fornitori").performClick()
        aspetta { server.richiesteA("POST /api/mail/812/sposta").size == 1 }
        assertEquals("""{"gruppo_id":2,"impara":true}""", server.richiesteA("POST /api/mail/812/sposta").single().body.readUtf8())

        aspetta { esiste("Tutto sistemato") }
        compose.onNodeWithText("Torna a Oggi").performClick()
        assertTrue(chiusa)
    }

    @Test
    fun `Smista - scorrere la scheda a destra vale va bene`() {
        serverSmista()
        val vm = SmistaViewModel(client)
        mostra { SmistaScreen(vm) {} }
        aspetta { !vm.stato.value.caricamento && vm.stato.value.attuale != null }
        aspetta { esiste("Fattura ottobre") }
        compose.onNodeWithText("Fattura ottobre").performTouchInput { swipeRight() }
        aspetta { server.richiesteA("POST /api/mail/815/conferma").size == 1 }
    }

    @Test
    fun `Smista - niente da sistemare`() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/mail?smistamento=da_controllare&limite=50", """{"mail": [], "altre": false}""")
        val vm = SmistaViewModel(client)
        mostra { SmistaScreen(vm) {} }
        aspetta { esiste("Niente da sistemare") }
    }

    // --- Tutorial (docs/aspetto.md §10) ------------------------------------------------------

    @Test
    fun `tutorial - parte al primo avvio una volta sola`() {
        val preferenze = PreferenzeFinte(vistoAllInizio = false)
        var aperture = 0
        var giro by mutableIntStateOf(0)
        mostra {
            // Un "riavvio" dell'app: la composizione riparte da capo.
            androidx.compose.runtime.key(giro) { TutorialAlPrimoAvvio(preferenze) { aperture++ } }
        }
        aspetta { aperture == 1 }
        assertTrue(preferenze.visto.value)
        giro++
        compose.waitForIdle()
        giro++
        compose.waitForIdle()
        assertEquals(1, aperture)
    }

    @Test
    fun `tutorial - gia visto non parte`() {
        val preferenze = PreferenzeFinte(vistoAllInizio = true)
        var aperture = 0
        mostra { TutorialAlPrimoAvvio(preferenze) { aperture++ } }
        compose.waitForIdle()
        assertEquals(0, aperture)
    }

    @Test
    fun `tutorial - Salta lo chiude`() {
        var fine = 0
        mostra { TutorialScreen { fine++ } }
        compose.onNodeWithText("Salta").performClick()
        assertEquals(1, fine)
    }

    @Test
    fun `tutorial - si prova tutto su dati finti, senza nessuna chiamata al server`() {
        var fine = 0
        mostra { TutorialScreen { fine++ } }
        // 1. Oggi
        aspetta { esiste("Ogni mattina qui trovi il punto della giornata e cosa fare.") }
        compose.onNodeWithText("Rivedi").performClick()
        // 2. Risposta di Sol: il tono cambia il testo, poi l'invio finto
        aspetta { esiste("Sol ti prepara la risposta. Cambiale il tono con un tocco.") }
        compose.onNodeWithText("Più breve").performScrollTo().performClick()
        aspetta { esiste("Ora tocca Invia risposta.") }
        assertTrue(esiste("Buongiorno, le mando il preventivo entro venerdì.\n\nCordiali saluti"))
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { esiste("Risposta inviata") }
        compose.onNodeWithText("Avanti").performClick()
        // 3. Gesti: a destra letta, a sinistra il foglio, poi Annulla
        aspetta { esiste("Scorri a destra per segnarla letta.") }
        compose.onNodeWithText("Consegna materiale cantiere").performTouchInput { swipeRight() }
        aspetta { esiste("Ora scorri a sinistra per spostarla.") }
        compose.onNodeWithText("Consegna materiale cantiere").performTouchInput { swipeLeft() }
        aspetta { esiste("Ricorda questo mittente") }
        compose.onNodeWithText("Clienti").performClick()
        aspetta { esiste("Spostata in Clienti") }
        compose.onNodeWithText("Annulla").performClick()
        aspetta { esiste("Avanti") }
        compose.onNodeWithText("Avanti").performClick()
        // 4. Smista
        aspetta { esiste("Quando Sol non è sicuro, decidi tu: va bene o cambia.") }
        compose.onNodeWithText("Va bene").performClick()
        // 5. Possibile truffa
        aspetta { esiste("Possibile truffa") }
        compose.onNodeWithText("Ho capito").performClick()
        // 6. Fine
        aspetta { esiste("Sei pronto.") }
        compose.onNodeWithText("Salta").assertDoesNotExist()
        compose.onNodeWithText("Inizia").performClick()
        assertEquals(1, fine)
        assertTrue("il tutorial non chiama mai il server", server.richieste.isEmpty())
    }

    @Test
    fun `tutorial - nei gesti si puo anche usare il lettore dello schermo`() {
        mostra { TutorialScreen {} }
        compose.onNodeWithText("Rivedi").performClick()
        compose.onNodeWithText("Invia risposta").performClick()
        compose.onNodeWithText("Avanti").performClick()
        aspetta { esiste("Scorri a destra per segnarla letta.") }
        val azioni = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { azioni.first { it.label == "Segna come letta" }.action() }
        aspetta { esiste("Ora scorri a sinistra per spostarla.") }
    }

    // --- Impostazioni -------------------------------------------------------------------------

    private fun serverImpostazioni() {
        server.rispondi("GET /api/caselle", "[${Esempi.CASELLA}]")
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/regole", """[{"id": 1, "tipo": "mittente", "valore": "bianchi@esempio.it", "gruppo_id": 3, "origine": "imparata"}]""")
        server.rispondi("GET /api/dispositivi", """[{"id": 3, "nome": "Telefono di Manuel", "tipo": "android"}]""")
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
    }

    private fun impostazioni(onTema: (TemaScelto) -> Unit = {}, onComeFunziona: () -> Unit = {}): ImpostazioniViewModel {
        serverImpostazioni()
        val vm = ImpostazioniViewModel(client, CacheMemoria(), DepositoMemoria(Configurazione(server.url, "x", 3)))
        mostra {
            ImpostazioniScreen(
                vm,
                tema = TemaScelto.AUTOMATICO,
                onTema = onTema,
                onIndietro = {},
                onAbbinaDiNuovo = {},
                onScollegato = {},
                onComeFunziona = onComeFunziona,
            )
        }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        aspetta { esiste("Tono delle risposte") }
        return vm
    }

    @Test
    fun `impostazioni - Come funziona Sol riapre il tutorial`() {
        var aperto = 0
        impostazioni(onComeFunziona = { aperto++ })
        compose.onNodeWithText("Come funziona Sol").performScrollTo().performClick()
        assertEquals(1, aperto)
    }

    @Test
    fun `impostazioni - il nome si scrive in un foglio e va al server`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni().replace("\"conserva_giorni\": 180", "\"conserva_giorni\": 180, \"nome\": \"Manuel\""))
        compose.onNodeWithText("Nome").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Manuel")
        compose.onNodeWithText("Salva").performClick()
        aspetta { server.richiesteA("PATCH /api/impostazioni").isNotEmpty() }
        assertEquals("""{"nome":"Manuel"}""", server.richiesteA("PATCH /api/impostazioni").single().body.readUtf8())
        aspetta { vm.stato.value.impostazioni?.nome == "Manuel" }
    }

    @Test
    fun `impostazioni - il tema si sceglie tra automatico, chiaro e scuro`() {
        var scelto: TemaScelto? = null
        impostazioni(onTema = { scelto = it })
        compose.onNodeWithText("Tema").performScrollTo().performClick()
        aspetta { esiste("Scuro") }
        compose.onNodeWithText("Scuro").performClick()
        assertEquals(TemaScelto.SCURO, scelto)
    }

    @Test
    fun `impostazioni - un gruppo diventa raccolta`() {
        val vm = impostazioni()
        server.rispondi("PATCH /api/gruppi/3", "{}")
        compose.onNodeWithText("Gruppi").performScrollTo().performClick()
        aspetta { esiste("Amministrazione e fatture") }
        compose.onNodeWithText("Amministrazione e fatture").performClick()
        aspetta { esiste("Raccolta in una riga") }
        compose.onNodeWithText("Raccolta in una riga").performClick()
        aspetta { server.richiesteA("PATCH /api/gruppi/3").isNotEmpty() }
        assertEquals("""{"raccolta":true}""", server.richiesteA("PATCH /api/gruppi/3").single().body.readUtf8())
        aspetta { vm.stato.value.gruppi.first { it.id == 3L }.raccolta }
    }

    @Test
    fun `impostazioni - Versione dice il carattere e la sua licenza, inclusa nell'app`() {
        impostazioni()
        compose.onNodeWithText("Versione e aggiornamento").performScrollTo().performClick()
        aspetta { esiste("Carattere Figtree, licenza SIL OFL 1.1") }
        compose.onNodeWithText("Leggi la licenza").performScrollTo().performClick()
        aspetta { compose.onAllNodes(hasText("SIL OPEN FONT LICENSE Version 1.1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `il carattere Figtree e incluso coi cinque pesi della guida`() {
        val pesi = (eu.stgm.sol.ui.tema.Figtree as androidx.compose.ui.text.font.FontListFontFamily).fonts.map { it.weight.weight }
        assertEquals(listOf(400, 500, 600, 700, 800), pesi)
    }

    @Test
    fun `impostazioni - niente spiegazioni fisse sotto gli interruttori`() {
        impostazioni()
        listOf("Accesa: Sol suggerisce", "Spenta: Sol sposta", "Se Sol è meno sicuro", "Più bassa =").forEach {
            assertTrue(it, compose.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isEmpty())
        }
    }
}
