package eu.stgm.sol.fotografo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.stgm.sol.AppDiProva
import eu.stgm.sol.BarraSchede
import eu.stgm.sol.DepositoMemoria
import eu.stgm.sol.Esempi
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.RegolaFinestre
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.ui.LocalApriImpostazioni
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.abbinamento.AbbinamentoScreen
import eu.stgm.sol.ui.abbinamento.AbbinamentoViewModel
import eu.stgm.sol.ui.dettaglio.BozzaScreen
import eu.stgm.sol.ui.dettaglio.BozzaViewModel
import eu.stgm.sol.ui.dettaglio.DettaglioScreen
import eu.stgm.sol.ui.dettaglio.DettaglioViewModel
import eu.stgm.sol.ui.impostazioni.ImpostazioniScreen
import eu.stgm.sol.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.sol.ui.indietro.IndietroScreen
import eu.stgm.sol.ui.indietro.IndietroViewModel
import eu.stgm.sol.ui.posta.PostaScreen
import eu.stgm.sol.ui.posta.PostaViewModel
import eu.stgm.sol.ui.riassunti.RiassuntiScreen
import eu.stgm.sol.ui.riassunti.RiassuntiViewModel
import eu.stgm.sol.ui.scadenze.ScadenzeScreen
import eu.stgm.sol.ui.scadenze.ScadenzeViewModel
import eu.stgm.sol.ui.tema.SolTema
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Il fotografo: disegna le schermate vere (ViewModel veri contro il server
 * finto) in PNG, chiare e scure, per guardarle senza telefono.
 *   gradlew :app:testDebugUnitTest -Pfotografo -Pfotografo.cartella=C:/percorso
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w393dp-h851dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class FotografoTest {
    // Le coroutine dei ViewModel riprendono sul thread di OkHttp: sotto Robolectric
    // il ritorno sul thread principale a volte resta in coda per sempre.
    @get:Rule(order = 0)
    val main = RegolaMain()

    @get:Rule(order = 1)
    val finestre = RegolaFinestre()

    @get:Rule(order = 2)
    val compose = createComposeRule()

    private val server = ServerFinto()
    private val client = server.client()
    private val cache = CacheMemoria()
    private val cartella = File(System.getProperty("fotografo.cartella") ?: "build/fotografo").also { it.mkdirs() }
    private var scuro by mutableStateOf(false)
    private val oggi get() = LocalDate.now().toString()

    @After
    fun chiudi() = server.close()

    private fun mostra(contenuto: @Composable () -> Unit) {
        val messaggi = SnackbarHostState()
        compose.setContent {
            SolTema(scuro = scuro) {
                CompositionLocalProvider(LocalMessaggi provides messaggi, LocalApriImpostazioni provides {}) {
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { contenuto() }
                        SnackbarHost(messaggi, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    private fun scatta(nome: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(cartella, "$nome.png").path)
    }

    /** Con i dialoghi e i fogli: tutte le finestre. */
    private fun scattaSchermo(nome: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(cartella, "$nome.png").path)
    }

    private fun chiaroEScuro(nome: String, schermo: Boolean = false) {
        scuro = false
        if (schermo) scattaSchermo("${nome}_chiaro") else scatta("${nome}_chiaro")
        scuro = true
        if (schermo) scattaSchermo("${nome}_scuro") else scatta("${nome}_scuro")
        scuro = false
        compose.waitForIdle()
    }

    // Largo: sotto Robolectric, con la macchina carica, il disegno a volte arriva tardi.
    private fun aspetta(condizione: () -> Boolean) = compose.waitUntil(20_000) { condizione() }

    /** Lo stato dei ViewModel arriva prima del disegno: prima di fotografare si aspetta di vedere il testo. */
    private fun esiste(testo: String) = compose.onAllNodes(hasText(testo, substring = true)).fetchSemanticsNodes().isNotEmpty()

    // --- Dati finti in più per un elenco vero -------------------------------------------------

    private val pecComune = """{
      "id": 818, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Comune di Bassano", "indirizzo": "protocollo@pec.esempio.it"},
      "oggetto": "Avvio del procedimento pratica 2026/114", "anteprima": "Si comunica l'avvio del procedimento",
      "riassunto": "Il Comune avvia la pratica del capannone: 30 giorni per le integrazioni.",
      "ricevuta_il": "2026-10-10T07:05:00+02:00", "letta": false, "risposta_data": false,
      "gruppo_id": 4, "gruppo": "PEC ed enti", "da_rispondere": false, "urgenza": "normale", "sicurezza": 0.97,
      "smistamento": "smistata", "origine_smistamento": "regola", "applicata_in_casella": false, "bozza": null,
      "allegati": [], "pec": {"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}
    }"""

    private val fornitoreNeri = """{
      "id": 816, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Anna Neri", "indirizzo": "neri@esempio.it"},
      "oggetto": "Conferma ordine compressore", "anteprima": "Confermiamo la consegna per martedì",
      "riassunto": "Neri conferma la consegna del compressore martedì mattina.",
      "ricevuta_il": "2026-10-09T16:20:00+02:00", "letta": true, "risposta_data": false,
      "gruppo_id": 2, "gruppo": "Fornitori", "da_rispondere": true, "urgenza": "normale", "sicurezza": 0.9,
      "smistamento": "smistata", "origine_smistamento": "decisore", "applicata_in_casella": false,
      "bozza": {"id": 57, "stato": "pronta"}, "allegati": []
    }"""

    private fun elencoPosta() =
        """{"mail": [${Esempi.MAIL}, $pecComune, ${Esempi.MAIL_BIANCHI}, $fornitoreNeri], "altre": true}""".replace("2026-10-10", oggi)

    private fun serverPosta(prova: Boolean = true) {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = prova))
        server.rispondi(
            "GET /api/conteggi",
            """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "da_leggere": 12, "bozze_pronte": 4,
               "sospette": 0, "scadenze_prossime": 2, "gruppi": {"1": {"da_leggere": 4}, "4": {"da_leggere": 1}}}""",
        )
        server.rispondi("GET /api/mail", elencoPosta())
        server.rispondi("GET /api/mail?da_rispondere=true&limite=50", """{"mail": [${Esempi.MAIL}, $fornitoreNeri], "altre": false}""".replace("2026-10-10", oggi))
        server.rispondi("GET /api/mail?gruppo_id=1&limite=50", """{"mail": [${Esempi.MAIL}], "altre": false}""".replace("2026-10-10", oggi))
    }

    private fun apriPosta(prova: Boolean = true, nonProtetto: Boolean = false): PostaViewModel {
        serverPosta(prova)
        val vm = PostaViewModel(client, cache)
        mostra { PostaScreen(vm, onApriMail = {}, collegamentoNonProtetto = nonProtetto) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null && !vm.stato.value.caricamento }
        // Lo stato del ViewModel arriva prima del disegno: si aspetta di vedere le righe.
        aspetta { esiste("Offerta capannone Bassano") }
        return vm
    }

    // --- Posta -----------------------------------------------------------------------------

    @Test
    fun posta() {
        val vm = apriPosta()
        chiaroEScuro("01_posta")
        compose.onNodeWithContentDescription("5 da rispondere").performClick()
        aspetta { vm.stato.value.mail.size == 2 && !vm.stato.value.caricamento }
        aspetta { esiste("togli filtro") && esiste("Conferma ordine compressore") && !esiste("Avvio del procedimento") }
        chiaroEScuro("01b_posta_filtro_da_rispondere")
        compose.onNodeWithText("togli filtro").performClick()
        aspetta { vm.stato.value.mail.size == 4 && !vm.stato.value.caricamento }
        aspetta { esiste("Avvio del procedimento") }
        compose.onNodeWithContentDescription("Clienti, 4 da leggere").performClick()
        aspetta { vm.stato.value.gruppoId == 1L && vm.stato.value.mail.size == 1 && !vm.stato.value.caricamento }
        aspetta { esiste("Offerta capannone Bassano") && !esiste("Avvio del procedimento") && !esiste("Da fare") }
        chiaroEScuro("01c_posta_scheda_clienti")
    }

    /** Il riquadro "Da fare" su un telefono stretto: quattro piastrelle in una riga, testi interi. */
    @Test
    @Config(qualifiers = "it-rIT-w360dp-h800dp-xhdpi")
    fun posta360dp() {
        serverPosta()
        server.rispondi(
            "GET /api/conteggi",
            """{"da_rispondere": 5, "da_controllare": 3, "sospette": 2, "scadenze_prossime": 2, "gruppi": {"1": {"da_leggere": 4}}}""",
        )
        val vm = PostaViewModel(client, cache)
        mostra { PostaScreen(vm, onApriMail = {}) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi?.sospette == 2 && !vm.stato.value.caricamento }
        aspetta { esiste("Offerta capannone Bassano") && esiste("possibili") }
        chiaroEScuro("01d_posta_360dp_quattro_piastrelle")
    }

    @Test
    fun postaSenzaServerEVuota() {
        val vm = apriPosta(prova = false, nonProtetto = true)
        server.rispondi("GET /api/mail", "<html>Bad Gateway</html>", 502)
        vm.carica()
        aspetta { vm.stato.value.errore != null && !vm.stato.value.caricamento }
        aspetta { esiste("Riprova") }
        chiaroEScuro("02_posta_senza_server")
        server.rispondi("GET /api/mail", """{"mail": [], "altre": false}""")
        server.rispondi("GET /api/conteggi", """{"da_rispondere": 0, "urgenti": 0, "da_controllare": 0, "sospette": 0, "scadenze_prossime": 0}""")
        vm.carica()
        aspetta { vm.stato.value.errore == null && !vm.stato.value.caricamento && vm.stato.value.conteggi?.daRispondere == 0 }
        aspetta { esiste("Nessuna mail qui.") && !esiste("Da fare") }
        chiaroEScuro("02b_posta_vuota_niente_da_fare")
    }

    @Test
    fun postaGesti() {
        apriPosta(prova = false)
        // A metà gesto: si vede lo sfondo del gesto, poi si torna indietro senza fare niente.
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput {
            down(center)
            moveBy(Offset(330f, 0f))
        }
        chiaroEScuro("03_gesto_a_destra_letta")
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput {
            moveBy(Offset(-330f, 0f))
            up()
        }
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput {
            down(center)
            moveBy(Offset(-330f, 0f))
        }
        chiaroEScuro("03b_gesto_a_sinistra_sposta")
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput {
            moveBy(Offset(330f, 0f))
            up()
        }
        compose.waitForIdle()

        server.inFila(
            "POST /api/mail/812/sposta",
            Esempi.MAIL.replace("2026-10-10", oggi)
                .replace("\"gruppo_id\": 1, \"gruppo\": \"Clienti\"", "\"gruppo_id\": 2, \"gruppo\": \"Fornitori\"")
                .replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"corretta\""),
        )
        compose.onNodeWithText("Offerta capannone Bassano").performTouchInput { swipeLeft() }
        aspetta { esiste("Ricorda questo mittente") }
        chiaroEScuro("03c_foglio_sposta_in", schermo = true)
        compose.onNode(hasText("offerte, ordini e consegne dei fornitori")).performClick()
        aspetta { esiste("Spostata in Fornitori") }
        chiaroEScuro("03d_spostata_annulla", schermo = true)
    }

    @Test
    fun postaTruffa() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi(
            "GET /api/mail",
            """{"mail": [${Esempi.MAIL_SOSPETTA}, ${Esempi.MAIL}, ${Esempi.MAIL_BIANCHI}], "altre": false}""".replace("2026-10-10", oggi),
        )
        server.rispondi("GET /api/conteggi", """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "sospette": 1}""")
        val vm = PostaViewModel(client, cache)
        mostra { PostaScreen(vm, onApriMail = {}) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null }
        aspetta { esiste("Nuove coordinate bancarie") && esiste("Possibile truffa") }
        chiaroEScuro("20_posta_truffa")
    }

    // --- Mail aperta -----------------------------------------------------------------------

    private fun serverDettaglio(json: String = Esempi.DETTAGLIO, bozza: String? = Esempi.bozza()) {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/mail/812", json.replace("2026-10-10", oggi))
        if (bozza != null) server.rispondi("GET /api/bozze/55", bozza)
    }

    private fun apriDettaglio(id: Long = 812, vm: DettaglioViewModel = DettaglioViewModel(client, cache, intervalloBozzaMs = 50)): DettaglioViewModel {
        mostra { DettaglioScreen(vm, id) {} }
        aspetta { vm.stato.value.mail != null && !vm.stato.value.caricamento }
        aspetta { esiste(vm.stato.value.mail!!.oggetto) }
        return vm
    }

    @Test
    fun dettaglio() {
        serverDettaglio()
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Risposta pronta") }
        chiaroEScuro("04_dettaglio")
        compose.onNodeWithText("Puoi anche dettare col microfono della tastiera.").performScrollTo()
        chiaroEScuro("04b_dettaglio_risposta")

        // Si scrive direttamente nel riquadro; A e Oggetto si aprono al tocco.
        compose.onNodeWithText("rossi@esempio.it").performClick()
        compose.onNodeWithText("Re: Offerta capannone Bassano").performClick()
        compose.onNode(hasSetTextAction() and hasText("Buongiorno Mario,\nl'offerta arriva giovedì."))
            .performScrollTo()
            .performTextReplacement("Buongiorno Mario,\nl'offerta arriva giovedì entro le 12.")
        compose.onNodeWithText("Modifiche non ancora salvate", substring = true).performScrollTo()
        chiaroEScuro("04c_dettaglio_si_scrive_nella_risposta")

        server.rispondi("PUT /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\nl'offerta arriva giovedì entro le 12.", versione = 4, modificata = true))
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\nl'offerta arriva giovedì entro le 12.", versione = 4, modificata = true))
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        chiaroEScuro("05_conferma_invio", schermo = true)
        compose.onNodeWithText("Annulla").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio == null }

        compose.onNodeWithContentDescription("Altre azioni").performClick()
        aspetta { esiste("Scarta la risposta") }
        chiaroEScuro("05b_menu_azioni_rare", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Clienti").performClick()
        aspetta { esiste("Ricorda questo mittente") }
        chiaroEScuro("06_sposta_in_dal_dettaglio", schermo = true)
    }

    @Test
    fun dettaglioDaControllare() {
        val bianchi = Esempi.MAIL_BIANCHI.trimEnd().removeSuffix("}") + """,
          "destinatari": ["studio@esempio.it"], "cc": [],
          "corpo": "Buongiorno,\nin allegato la fattura di ottobre.\nCordiali saluti\nLuca Bianchi"}"""
        serverDettaglio(json = bianchi, bozza = null)
        server.rispondi("GET /api/mail/815", bianchi.replace("2026-10-10", oggi))
        apriDettaglio(id = 815)
        aspetta { esiste("È nel gruppo giusto?") }
        chiaroEScuro("06b_dettaglio_da_controllare")
    }

    @Test
    fun dettaglioPreparaEScrivendo() {
        val senzaBozza = Esempi.DETTAGLIO.replace("\"bozza\": {\"id\": 55, \"stato\": \"pronta\"}", "\"bozza\": null")
        serverDettaglio(json = senzaBozza, bozza = null)
        server.rispondi("POST /api/mail/812/bozza", Esempi.bozza("in_preparazione"), 202)
        server.rispondi("GET /api/bozze/55", Esempi.bozza("in_preparazione"))
        val vm = DettaglioViewModel(client, cache, intervalloBozzaMs = 60_000)
        apriDettaglio(vm = vm)
        aspetta { esiste("Prepara la risposta") }
        chiaroEScuro("06c_dettaglio_prepara_la_risposta")
        compose.onNodeWithText("Prepara la risposta").performClick()
        aspetta { esiste("Sol sta scrivendo la risposta…") }
        chiaroEScuro("06d_dettaglio_sol_sta_scrivendo")
    }

    @Test
    fun dettaglioPecGiaRisposta() {
        val pec = Esempi.DETTAGLIO_GIA_RISPOSTA
            .replace("\"nome\": \"Mario Rossi\", \"indirizzo\": \"rossi@esempio.it\"", "\"nome\": \"\", \"indirizzo\": \"rossi@esempio.it\"")
            .trimEnd().removeSuffix("}") +
            """, "pec": {"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}}"""
        serverDettaglio(json = pec, bozza = Esempi.bozza(modificata = true))
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Risposta pronta") }
        chiaroEScuro("07_dettaglio_pec_da_senza_nome")
        compose.onNodeWithText("Puoi anche dettare col microfono della tastiera.").performScrollTo()
        chiaroEScuro("07b_risposta_gia_risposto")
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        chiaroEScuro("07c_conferma_gia_risposto", schermo = true)
    }

    @Test
    fun rispostaSenzaTestoEVerifica() {
        serverDettaglio(bozza = Esempi.bozza(testo = ""))
        val vm = apriDettaglio(vm = DettaglioViewModel(client, cache, verificaOgniMs = 60_000, verificaPerMs = 120_000))
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Riprova a prepararla") }
        compose.onNodeWithText("Riprova a prepararla").performScrollTo()
        chiaroEScuro("08_risposta_senza_testo")

        // Invio con esito incerto: la frase del server, e al posto di "Invia" il controllo in corso.
        server.rispondi("GET /api/bozze/55", Esempi.bozza())
        vm.bozza.imposta(null)
        vm.bozza.imposta(55)
        aspetta { vm.bozza.stato.value.bozza?.testo?.isNotBlank() == true }
        vm.bozza.chiediInvio()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        server.rispondi(
            "POST /api/bozze/55/invia",
            """{"errore": "Non riesco a capire se la mail è già partita. Guarda in Posta inviata prima di riprovare.", "codice": "casella_non_raggiungibile"}""",
            502,
        )
        vm.bozza.confermaInvio()
        aspetta { vm.bozza.stato.value.verificaInvio }
        aspetta { esiste("Controllo se è partita...") }
        compose.onNodeWithText("Risposta pronta").performScrollTo()
        chiaroEScuro("08b_verifica_invio")
    }

    @Test
    fun rispostaInviata() {
        serverDettaglio()
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata").replace("\"inviata_il\": null", "\"inviata_il\": \"${oggi}T08:30:00+02:00\""))
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Risposta pronta") }
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        compose.onNodeWithText("Invia adesso").performClick()
        aspetta { vm.bozza.stato.value.bozza?.stato == Bozza.INVIATA && vm.stato.value.mail?.rispostaData == true }
        aspetta { esiste("Risposta inviata") }
        compose.onNodeWithText("Risposta inviata").performScrollTo()
        chiaroEScuro("08c_risposta_inviata")
    }

    @Test
    fun dettaglioTruffa() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/mail/820", Esempi.DETTAGLIO_SOSPETTO.replace("2026-10-10", oggi))
        apriDettaglio(id = 820)
        aspetta { esiste("Non è una truffa") }
        chiaroEScuro("21_dettaglio_truffa")
        compose.onNodeWithText("Non è una truffa").performClick()
        chiaroEScuro("22_non_e_una_truffa", schermo = true)
    }

    @Test
    fun truffaConfermaInvio() {
        // Una risposta chiesta a mano per una mail sospetta: il server la prepara, la conferma avvisa.
        val sospetta812 = Esempi.DETTAGLIO.trimEnd().removeSuffix("}") + """, "sospetta": true}"""
        serverDettaglio(json = sospetta812, bozza = Esempi.bozza(modificata = true))
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Risposta pronta") }
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        chiaroEScuro("23_conferma_risposta_truffa", schermo = true)
    }

    @Test
    fun truffaSegnala() {
        serverDettaglio()
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Risposta pronta") }
        compose.onNodeWithContentDescription("Altre azioni").performClick()
        aspetta { esiste("Segnala come possibile truffa") }
        chiaroEScuro("24_segnala_come_truffa", schermo = true)
    }

    @Test
    fun sollecito() {
        val sollecito = Esempi.bozza(id = 60).replace("\"tipo\": \"risposta\"", "\"tipo\": \"sollecito\"")
            .replace("\"mail_id\": 812", "\"mail_id\": null")
            .replace("Re: Offerta capannone Bassano", "Offerta compressore")
            .replace("Buongiorno Mario,\\nl'offerta arriva giovedì.", "Buongiorno,\\nle scrivo per sapere se ha ricevuto l'offerta del compressore.")
        server.rispondi("GET /api/bozze/60", sollecito)
        val vm = BozzaViewModel(client, intervalloMs = 50)
        mostra { BozzaScreen(vm, 60, giaLetta = JsonSol.decodeFromString(Bozza.serializer(), sollecito), onApriMail = {}, onIndietro = {}) }
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Testo da inviare") }
        chiaroEScuro("18_sollecito")
    }

    @Test
    fun salvareLeModifiche() {
        mostra { eu.stgm.sol.DialogoModificheInCorso(onSalva = {}, onApri = {}, onResta = {}) }
        chiaroEScuro("19_salvare_le_modifiche", schermo = true)
    }

    // --- Le altre schede: colori nuovi e fascia calda ----------------------------------------

    @Test
    fun riassunti() {
        server.rispondi(
            "GET /api/riassunti/oggi",
            """{"testo": "Oggi 14 mail, 3 importanti. Rossi chiede l'offerta per il capannone di Bassano entro venerdì [#812]. Bianchi ha mandato la fattura di ottobre [#815].", "aggiornato_il": "${oggi}T09:00:00+02:00", "mail_citate": [812, 815], "stato": "pronto"}""",
        )
        server.rispondi("GET /api/riassunti", """[{"id": 7, "richiesta": "le mail di Bianchi di questa settimana", "stato": "pronto", "testo": "x", "creato_il": "${oggi}T08:00:00+02:00"}]""")
        val vm = RiassuntiViewModel(client, cache, intervalloMs = 10)
        mostra { RiassuntiScreen(vm) {} }
        aspetta { vm.stato.value.oggi != null }
        aspetta { esiste("Oggi 14 mail") && esiste("le mail di Bianchi di questa settimana") }
        chiaroEScuro("09_riassunti")

        server.rispondi("POST /api/riassunti", """{"id": 9, "stato": "in_coda"}""", 202)
        server.rispondi("GET /api/riassunti/9", "<html>Bad Gateway</html>", 502)
        vm.cambiaRichiesta("le mail di Rossi")
        vm.chiedi()
        aspetta { vm.stato.value.erroreAzione != null }
        compose.onNodeWithText("Riprova").performScrollTo()
        chiaroEScuro("09b_riassunto_senza_server")
    }

    @Test
    fun indietro() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/indietro", Esempi.INDIETRO.replace("2026-10-10", oggi))
        val vm = IndietroViewModel(client, cache)
        mostra { IndietroScreen(vm, {}) { _, _ -> } }
        aspetta { vm.stato.value.dati != null && vm.stato.value.gruppi.isNotEmpty() }
        aspetta { esiste("Offerta capannone Bassano") && esiste("Offerta compressore") }
        chiaroEScuro("10_in_sospeso")
    }

    @Test
    fun scadenze() {
        val domani = LocalDate.now().plusDays(1)
        server.rispondi(
            "GET /api/scadenze?stato=aperta",
            """[${Esempi.SCADENZA.replace("2026-10-16", domani.toString())},
               {"id": 5, "descrizione": "Collaudo impianto Bianchi", "quando": "${domani.plusDays(5)}", "ora": "10:30", "stato": "aperta"}]""",
        )
        val vm = ScadenzeViewModel(client, cache)
        mostra { ScadenzeScreen(vm) {} }
        aspetta { vm.stato.value.scadenze.isNotEmpty() }
        aspetta { esiste("Collaudo impianto Bianchi") }
        chiaroEScuro("15_scadenze")
        compose.onNodeWithText("Aggiungi", useUnmergedTree = true).performClick()
        chiaroEScuro("15b_nuova_scadenza", schermo = true)
    }

    private fun serverImpostazioni(caselle: String = "[${Esempi.CASELLA}]") {
        server.rispondi("GET /api/caselle", caselle)
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/regole", """[{"id": 1, "tipo": "mittente", "valore": "bianchi@esempio.it", "gruppo_id": 3, "origine": "imparata"}]""")
        server.rispondi("GET /api/dispositivi", """[{"id": 3, "nome": "Telefono di Manuel", "tipo": "android", "ultimo_accesso": "${oggi}T09:00:00+02:00"}, {"id": 4, "nome": "PC dello studio", "tipo": "pc"}]""")
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
    }

    @Test
    fun impostazioni() {
        serverImpostazioni(
            "[${Esempi.CASELLA}, {\"id\": 2, \"tipo\": \"gmail\", \"indirizzo\": \"rossi.personale@gmail.com\", \"nome\": \"Personale\", \"stato\": \"errore\", \"errore\": \"Google ha rifiutato la password per le app.\"}]",
        )
        server.rispondi("PATCH /api/gruppi/2", """{"id": 2, "nome": "Fornitori", "colore": "petrolio"}""")
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione(server.url, "x", 3)))
        mostra { ImpostazioniScreen(vm, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}) }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        aspetta { esiste("rossi.personale@gmail.com") && esiste("PEC ed enti") }
        chiaroEScuro("11_impostazioni")
        compose.onNodeWithText("Aggiungi un gruppo").performScrollTo()
        chiaroEScuro("11b_impostazioni_gruppi_colorati")
        compose.onNodeWithContentDescription("Colore di Fornitori: verde").performScrollTo().performClick()
        aspetta { esiste("Colore di «Fornitori»") }
        chiaroEScuro("11c_colore_del_gruppo", schermo = true)
        compose.onNodeWithText("petrolio").performClick()
        aspetta { vm.stato.value.gruppi.first { it.id == 2L }.colore == "petrolio" }
        compose.onNodeWithText("Aggiungi un gruppo").performClick()
        chiaroEScuro("11d_nuovo_gruppo", schermo = true)
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Ricollega").performScrollTo().performClick()
        chiaroEScuro("11e_ricollega_gmail", schermo = true)
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Modalità prova").performScrollTo()
        chiaroEScuro("12_impostazioni_come_lavora")
        compose.onNodeWithText("Tono delle risposte").performScrollTo()
        chiaroEScuro("12b_impostazioni_firma_tono")
        compose.onNodeWithText("Controlla aggiornamenti").performScrollTo()
        chiaroEScuro("12c_impostazioni_dispositivi_versione")
        compose.onNodeWithText("Aggiungi Gmail").performScrollTo().performClick()
        chiaroEScuro("13_aggiungi_gmail", schermo = true)
    }

    @Test
    fun impostazioniInChiaroEArretrato() {
        serverImpostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/arretrato", """{"da_applicare": 37}""")
        // Il telefono è abbinato in chiaro (rete di casa): niente password Gmail.
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione("http://192.168.1.51:8200", "x", 3)))
        mostra { ImpostazioniScreen(vm, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}) }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        compose.onNodeWithText("Aggiungi Gmail").performScrollTo().performClick()
        chiaroEScuro("13b_gmail_in_chiaro_bloccata", schermo = true)
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Abbina di nuovo").performScrollTo()
        chiaroEScuro("12d_questo_telefono_non_protetto")
        vm.cambiaModalitaProva(false)
        aspetta { vm.stato.value.arretratoDaApplicare != null }
        chiaroEScuro("12e_arretrato_dopo_la_prova", schermo = true)
    }

    @Test
    fun truffaSoglia() {
        serverImpostazioni()
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione(server.url, "x", 3)))
        mostra { ImpostazioniScreen(vm, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}) }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        compose.onNodeWithText("Dopo quanti giorni una mail senza risposta finisce in «In sospeso»").performScrollTo()
        chiaroEScuro("25_impostazioni_controllo_truffe")
    }

    @Test
    fun outlook() {
        serverImpostazioni(caselle = "[]")
        server.rispondi(
            "POST /api/caselle",
            """{"casella": {"id": 2, "tipo": "outlook", "nome": "Studio", "stato": "in_collegamento"}, "codice_utente": "ABCD-EFGH", "url_verifica": "https://microsoft.com/devicelogin"}""",
            202,
        )
        server.rispondi("GET /api/caselle/2", """{"id": 2, "tipo": "outlook", "nome": "Studio", "stato": "in_collegamento"}""")
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione(server.url, "x", 3)))
        mostra { ImpostazioniScreen(vm, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}) }
        aspetta { vm.stato.value.impostazioni != null }
        compose.onNodeWithText("Aggiungi Outlook").performScrollTo().performClick()
        compose.onNodeWithText("Continua").performClick()
        aspetta { vm.stato.value.outlook != null }
        aspetta { esiste("ABCD-EFGH") }
        chiaroEScuro("14_aggiungi_outlook", schermo = true)
    }

    @Test
    @Config(qualifiers = "it-rIT-w360dp-h800dp-xhdpi")
    fun barraSchede() {
        var scelta by mutableStateOf(Scheda.POSTA)
        mostra {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                BarraSchede(scelta) { scelta = it }
            }
        }
        scatta("17_barra_360dp_chiaro")
        scelta = Scheda.INDIETRO
        scuro = true
        scatta("17b_barra_360dp_scuro")
    }

    @Test
    fun abbinamento() {
        val vm = AbbinamentoViewModel(DepositoMemoria())
        mostra { AbbinamentoScreen(vm, onFatto = {}, onAnnulla = null) }
        chiaroEScuro("16_abbinamento")
        compose.onNodeWithText("Collega").performScrollTo()
        compose.onNodeWithText("Indirizzo del server").performTextInput("http://sol.esempio.it")
        compose.onNodeWithText("Collega").performClick()
        chiaroEScuro("16b_abbinamento_errore")
    }

    @Test
    fun abbinamentoPerso() {
        val vm = AbbinamentoViewModel(DepositoMemoria())
        mostra { AbbinamentoScreen(vm, onFatto = {}, onAnnulla = null, abbinamentoPerso = true) }
        chiaroEScuro("16c_abbinamento_perso")
    }
}
