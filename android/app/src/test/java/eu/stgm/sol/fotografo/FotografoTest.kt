package eu.stgm.sol.fotografo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import eu.stgm.sol.dati.TemaScelto
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.ui.MessaggioSol
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
import eu.stgm.sol.ui.oggi.AzioniOggi
import eu.stgm.sol.ui.oggi.OggiScreen
import eu.stgm.sol.ui.oggi.OggiViewModel
import eu.stgm.sol.ui.posta.PostaScreen
import eu.stgm.sol.ui.posta.PostaViewModel
import eu.stgm.sol.ui.posta.RaccoltaScreen
import eu.stgm.sol.ui.posta.RicercaScreen
import eu.stgm.sol.ui.riassunti.RiassuntiScreen
import eu.stgm.sol.ui.riassunti.RiassuntiViewModel
import eu.stgm.sol.ui.scadenze.ScadenzeScreen
import eu.stgm.sol.ui.scadenze.ScadenzeViewModel
import eu.stgm.sol.ui.smista.SmistaScreen
import eu.stgm.sol.ui.smista.SmistaViewModel
import eu.stgm.sol.ui.tema.Sol
import eu.stgm.sol.ui.tema.SolTema
import eu.stgm.sol.ui.tutorial.TutorialScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Il fotografo: disegna le schermate vere (ViewModel veri contro il server
 * finto) in PNG, chiare e scure, per confrontarle col prototipo senza telefono.
 *   gradlew :app:testDebugUnitTest -Pfotografo -Pfotografo.cartella=C:/percorso
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w390dp-h844dp-xxhdpi")
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

    /** Come nell'app: le pagine delle schede hanno sotto la barra a tre voci. */
    private fun mostra(scheda: Scheda? = null, daLeggere: Int = 0, contenuto: @Composable () -> Unit) {
        val messaggi = SnackbarHostState()
        compose.setContent {
            SolTema(scuro = scuro, movimenti = false) {
                CompositionLocalProvider(LocalMessaggi provides messaggi) {
                    Scaffold(
                        bottomBar = { if (scheda != null) BarraSchede(scheda, daLeggere) {} },
                        snackbarHost = { SnackbarHost(messaggi) { MessaggioSol(it) } },
                        containerColor = Sol.colori.fondo,
                    ) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) { contenuto() }
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

    /** Negli elenchi pigri si scorre fino alla riga. */
    private fun scorriA(testo: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(testo, substring = true))
        compose.waitForIdle()
    }

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
      "ricevuta_il": "2026-10-10T06:20:00+02:00", "letta": true, "risposta_data": false,
      "gruppo_id": 2, "gruppo": "Fornitori", "da_rispondere": true, "urgenza": "normale", "sicurezza": 0.9,
      "smistamento": "smistata", "origine_smistamento": "decisore", "applicata_in_casella": false,
      "bozza": {"id": 57, "stato": "pronta"}, "allegati": []
    }"""

    private val clienteVerdi = """{
      "id": 819, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Giulia Verdi", "indirizzo": "verdi@esempio.it"},
      "oggetto": "Sopralluogo magazzino Thiene", "anteprima": "Possiamo vederci giovedì?",
      "riassunto": "Verdi chiede un sopralluogo al magazzino giovedì pomeriggio.",
      "ricevuta_il": "2026-10-10T07:50:00+02:00", "letta": true, "risposta_data": false,
      "gruppo_id": 1, "gruppo": "Clienti", "da_rispondere": true, "urgenza": "normale", "sicurezza": 0.88,
      "smistamento": "smistata", "origine_smistamento": "decisore", "applicata_in_casella": false,
      "bozza": null, "allegati": []
    }"""

    private val gruppiConRaccolte = Esempi.GRUPPI_COLORATI
        .replace("\"colore\": \"ambra\",", "\"colore\": \"ambra\", \"raccolta\": true,")
        .replace(
            "{\"id\": 6, \"nome\": \"Altro\"",
            "{\"id\": 5, \"nome\": \"Newsletter\", \"ordine\": 5, \"colore\": \"grigio\", \"raccolta\": true, \"descrizione\": \"newsletter e pubblicità\", \"totale\": 14},\n      {\"id\": 6, \"nome\": \"Altro\"",
        )

    private val conteggiPosta = """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "da_leggere": 12, "bozze_pronte": 4,
       "sospette": 0, "scadenze_prossime": 2, "gruppi": {
         "1": {"da_leggere": 4, "totale": 120}, "4": {"da_leggere": 1, "totale": 12},
         "3": {"da_leggere": 2, "totale": 6, "ultima": {"mittente": "Banca Esempio", "oggetto": "Estratto conto settembre", "ricevuta_il": null}},
         "5": {"da_leggere": 3, "totale": 14, "ultima": {"mittente": "Fiera Esempio", "oggetto": "Inviti alla fiera dell'edilizia", "ricevuta_il": null}}}}"""

    private fun elencoPosta() =
        """{"mail": [${Esempi.MAIL}, $clienteVerdi, $pecComune, ${Esempi.MAIL_BIANCHI}, $fornitoreNeri], "altre": true}""".replace("2026-10-10", oggi)

    private fun serverPosta(prova: Boolean = false) {
        server.rispondi("GET /api/gruppi", gruppiConRaccolte)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = prova))
        server.rispondi("GET /api/conteggi", conteggiPosta)
        server.rispondi("GET /api/mail", elencoPosta())
        server.rispondi(
            "GET /api/mail?smistamento=da_controllare&limite=50",
            """{"mail": [${Esempi.MAIL_BIANCHI}, ${Esempi.MAIL_BIANCHI.replace("\"id\": 815", "\"id\": 817")}, ${Esempi.MAIL_BIANCHI.replace("\"id\": 815", "\"id\": 821")}], "altre": false}""",
        )
        server.rispondi("GET /api/mail?gruppo_id=3&limite=50", """{"mail": [${Esempi.MAIL_BIANCHI}], "altre": false}""".replace("2026-10-10", oggi))
    }

    private fun apriPosta(prova: Boolean = false): PostaViewModel {
        serverPosta(prova)
        val vm = PostaViewModel(client, cache)
        mostra(Scheda.POSTA, daLeggere = 5) { PostaScreen(vm, onApriMail = {}) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null && !vm.stato.value.caricamento }
        aspetta { esiste("Offerta capannone Bassano") }
        return vm
    }

    // --- Oggi -----------------------------------------------------------------------------

    private fun serverOggi(json: String = Esempi.OGGI) {
        server.rispondi("GET /api/oggi", json.replace("2026-10-11", oggi))
        server.rispondi("GET /api/scadenze?stato=aperta", "[${Esempi.SCADENZA}]")
        server.rispondi("GET /api/indietro", Esempi.INDIETRO)
    }

    private fun apriOggi(ora: LocalTime = LocalTime.of(9, 0)): OggiViewModel {
        val vm = OggiViewModel(client, cache)
        mostra(Scheda.OGGI, daLeggere = 5) { OggiScreen(vm, AzioniOggi(), ora = { ora }) }
        aspetta { vm.stato.value.oggi != null && !vm.stato.value.caricamento }
        return vm
    }

    @Test
    fun oggi() {
        serverOggi()
        apriOggi()
        aspetta { esiste("Buongiorno, Manuel") && esiste("Possibile truffa") }
        chiaroEScuro("01_oggi")
        scorriA("Aspetti risposta")
        chiaroEScuro("01b_oggi_in_fondo")
    }

    @Test
    fun oggiTuttoFatto() {
        serverOggi(
            Esempi.OGGI.replace(Regex("\"da_fare\": \\[.*?\\n      \\],", RegexOption.DOT_MATCHES_ALL), "\"da_fare\": [],")
                .replace("\"fatte_oggi\": 2, \"totale_oggi\": 10", "\"fatte_oggi\": 8, \"totale_oggi\": 8"),
        )
        apriOggi(ora = LocalTime.of(18, 30))
        aspetta { esiste("Niente da fare. Bella giornata.") }
        chiaroEScuro("01c_oggi_tutto_fatto")
    }

    @Test
    fun oggiInPreparazioneESenzaCollegamento() {
        serverOggi(
            Esempi.OGGI.replace("\"stato\": \"pronto\",\n        \"testo\": \"Rossi aspetta l'offerta del capannone [#812]. Bianchi ha mandato la fattura di ottobre.\",", "\"stato\": \"in_corso\", \"testo\": null,"),
        )
        val vm = apriOggi(ora = LocalTime.of(7, 10))
        aspetta { vm.stato.value.oggi?.riassunto?.inCorso == true }
        chiaroEScuro("01d_oggi_riassunto_in_preparazione")
        vm.sospendi()
        server.spegni()
        vm.carica()
        aspetta { vm.stato.value.errore != null && !vm.stato.value.caricamento }
        aspetta { esiste("Senza collegamento") }
        chiaroEScuro("01e_oggi_senza_collegamento")
    }

    @Test
    @Config(qualifiers = "it-rIT-w360dp-h800dp-xhdpi")
    fun oggi360dp() {
        serverOggi()
        apriOggi()
        aspetta { esiste("Possibile truffa") }
        chiaroEScuro("01f_oggi_360dp")
    }

    // --- Posta -----------------------------------------------------------------------------

    @Test
    fun posta() {
        val vm = apriPosta()
        aspetta { esiste("3 mail da sistemare") }
        chiaroEScuro("02_posta_importanti")
        scorriA("Inviti alla fiera")
        chiaroEScuro("02b_posta_raccolte")
        compose.onNodeWithText("Tutte").performClick()
        aspetta { vm.stato.value.modo == eu.stgm.sol.ui.posta.ModoPosta.TUTTE }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Posta"))
        aspetta { esiste("Fattura ottobre") }
        chiaroEScuro("02c_posta_tutte")
    }

    @Test
    fun postaProva() {
        apriPosta(prova = true)
        aspetta { esiste("Prova") }
        compose.onNodeWithText("Prova").performClick()
        aspetta { esiste("Cos'è la modalità prova") }
        chiaroEScuro("02d_posta_cos_e_la_prova", schermo = true)
    }

    @Test
    fun postaSenzaServerEVuota() {
        server.rispondi("GET /api/gruppi", "<html>Bad Gateway</html>", 502)
        server.rispondi("GET /api/impostazioni", "<html>Bad Gateway</html>", 502)
        server.rispondi("GET /api/conteggi", "<html>Bad Gateway</html>", 502)
        server.rispondi("GET /api/mail", "<html>Bad Gateway</html>", 502)
        val vm = PostaViewModel(client, cache)
        mostra(Scheda.POSTA) { PostaScreen(vm, onApriMail = {}) }
        aspetta { vm.stato.value.errore != null && !vm.stato.value.caricamento }
        aspetta { esiste("Riprova") }
        chiaroEScuro("02e_posta_senza_server")

        serverPosta()
        vm.carica()
        aspetta { vm.stato.value.errore == null && vm.stato.value.mail.isNotEmpty() && !vm.stato.value.caricamento }
        server.spegni()
        vm.carica()
        aspetta { vm.stato.value.errore != null && !vm.stato.value.caricamento }
        aspetta { esiste("Senza collegamento") }
        chiaroEScuro("02f_posta_senza_collegamento")
    }

    @Test
    fun postaVuota() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/conteggi", "{}")
        server.rispondi("GET /api/mail", """{"mail": [], "altre": false}""")
        val vm = PostaViewModel(client, cache)
        mostra(Scheda.POSTA) { PostaScreen(vm, onApriMail = {}) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && !vm.stato.value.caricamento }
        aspetta { esiste("Nessuna mail qui.") }
        chiaroEScuro("02g_posta_vuota")
    }

    @Test
    fun postaTruffa() {
        server.rispondi("GET /api/gruppi", gruppiConRaccolte)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi(
            "GET /api/mail",
            """{"mail": [${Esempi.MAIL_SOSPETTA}, ${Esempi.MAIL}, $fornitoreNeri], "altre": false}""".replace("2026-10-10", oggi),
        )
        server.rispondi("GET /api/conteggi", conteggiPosta)
        val vm = PostaViewModel(client, cache)
        mostra(Scheda.POSTA, daLeggere = 5) { PostaScreen(vm, onApriMail = {}) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null }
        aspetta { esiste("Nuove coordinate bancarie") && esiste("Possibile truffa") }
        chiaroEScuro("02h_posta_truffa")
    }

    @Test
    @Config(qualifiers = "it-rIT-w360dp-h800dp-xhdpi")
    fun posta360dp() {
        apriPosta()
        aspetta { esiste("3 mail da sistemare") }
        chiaroEScuro("02i_posta_360dp")
    }

    @Test
    fun postaGesti() {
        apriPosta()
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
    fun raccolta() {
        serverPosta()
        val bianchiNonLetta = Esempi.MAIL_BIANCHI.replace("\"letta\": true", "\"letta\": false")
        val banca = Esempi.MAIL_BIANCHI.replace("\"id\": 815", "\"id\": 830")
            .replace("\"nome\": \"Luca Bianchi\", \"indirizzo\": \"bianchi@esempio.it\"", "\"nome\": \"Banca Esempio\", \"indirizzo\": \"avvisi@banca.esempio.it\"")
            .replace("Fattura ottobre", "Estratto conto settembre").replace("Bianchi manda la fattura di ottobre.", "L'estratto conto di settembre è disponibile.")
        server.rispondi("GET /api/mail?gruppo_id=3&limite=50", """{"mail": [$bianchiNonLetta, $banca], "altre": false}""".replace("2026-10-10", oggi))
        val vm = PostaViewModel(client, cache)
        mostra { RaccoltaScreen(vm, 3, onApriMail = {}, onIndietro = {}) }
        aspetta { vm.stato.value.gruppoId == 3L && vm.stato.value.mail.size == 2 && !vm.stato.value.caricamento }
        aspetta { esiste("Estratto conto settembre") }
        chiaroEScuro("04_raccolta")
    }

    @Test
    fun ricerca() {
        serverPosta()
        server.rispondi("GET /api/mail?cerca=capannone&limite=50", """{"mail": [${Esempi.MAIL}, $pecComune], "altre": false}""".replace("2026-10-10", oggi))
        val vm = PostaViewModel(client, cache)
        mostra { RicercaScreen(vm, onApriMail = {}, onIndietro = {}) }
        compose.waitForIdle()
        chiaroEScuro("05_ricerca")
        compose.onNode(hasSetTextAction()).performTextInput("capannone")
        aspetta { esiste("Avvio del procedimento") }
        chiaroEScuro("05b_ricerca_risultati")
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
        aspetta { esiste("Risposta di Sol") }
        chiaroEScuro("06_mail_aperta")
        compose.onNodeWithText("Più breve").performScrollTo()
        chiaroEScuro("06b_mail_risposta")

        compose.onNode(hasSetTextAction() and hasText("Buongiorno Mario,\nl'offerta arriva giovedì."))
            .performScrollTo()
            .performTextReplacement("Buongiorno Mario,\nl'offerta arriva giovedì entro le 12.")
        compose.onNodeWithText("Modifiche non salvate").performScrollTo()
        chiaroEScuro("06c_si_scrive_nella_risposta")

        server.rispondi("PUT /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\nl'offerta arriva giovedì entro le 12.", versione = 4, modificata = true))
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = "Buongiorno Mario,\nl'offerta arriva giovedì entro le 12.", versione = 4, modificata = true))
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        chiaroEScuro("06d_conferma_invio", schermo = true)
        compose.onNodeWithText("Annulla").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio == null }

        compose.onNodeWithContentDescription("Altre azioni").performClick()
        aspetta { esiste("Scarta la risposta") }
        chiaroEScuro("06e_menu_azioni_rare", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNode(hasText("Clienti")).performScrollTo().performClick()
        aspetta { esiste("Ricorda questo mittente") }
        chiaroEScuro("06f_sposta_in_dal_dettaglio", schermo = true)
    }

    @Test
    fun dettaglioToni() {
        serverDettaglio()
        val vm = apriDettaglio(vm = DettaglioViewModel(client, cache, intervalloBozzaMs = 60_000))
        aspetta { vm.bozza.stato.value.bozza != null }
        server.rispondi("POST /api/bozze/55/rifai", Esempi.bozza("in_preparazione"), 202)
        compose.onNodeWithText("Più breve").performScrollTo().performClick()
        aspetta { server.richiesteA("POST /api/bozze/55/rifai").isNotEmpty() }
        aspetta { esiste("Sol sta scrivendo…") }
        compose.onNodeWithText("Risposta di Sol").performScrollTo()
        chiaroEScuro("06g_tono_sol_riscrive")
    }

    @Test
    fun dettaglioAltro() {
        serverDettaglio()
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        compose.onNodeWithText("Altro…").performScrollTo().performClick()
        aspetta { esiste("Come la vuoi?") }
        chiaroEScuro("06h_altro_come_la_vuoi", schermo = true)
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
        chiaroEScuro("06i_mail_da_controllare")
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
        chiaroEScuro("06j_prepara_la_risposta")
        compose.onNodeWithText("Prepara la risposta").performClick()
        aspetta { esiste("Sol sta scrivendo la risposta…") }
        chiaroEScuro("06k_sol_sta_scrivendo")
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
        aspetta { esiste("Risposta di Sol") }
        chiaroEScuro("06l_pec_gia_risposta")
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        chiaroEScuro("06m_conferma_gia_risposto", schermo = true)
    }

    @Test
    fun rispostaSenzaTestoEVerifica() {
        serverDettaglio(bozza = Esempi.bozza(testo = ""))
        val vm = apriDettaglio(vm = DettaglioViewModel(client, cache, verificaOgniMs = 60_000, verificaPerMs = 120_000))
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Riprova a prepararla") }
        compose.onNodeWithText("Riprova a prepararla").performScrollTo()
        chiaroEScuro("06n_risposta_senza_testo")

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
        chiaroEScuro("06o_verifica_invio")
    }

    @Test
    fun rispostaInviata() {
        serverDettaglio()
        server.rispondi("POST /api/bozze/55/invia", Esempi.bozza("inviata").replace("\"inviata_il\": null", "\"inviata_il\": \"${oggi}T08:30:00+02:00\""))
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Invia risposta") }
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        compose.onNodeWithText("Invia").performClick()
        aspetta { vm.bozza.stato.value.bozza?.stato == Bozza.INVIATA && vm.stato.value.mail?.rispostaData == true }
        aspetta { esiste("Risposta inviata") }
        compose.onNodeWithText("Risposta inviata").performScrollTo()
        chiaroEScuro("06p_risposta_inviata")
    }

    @Test
    fun dettaglioTruffa() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/mail/820", Esempi.DETTAGLIO_SOSPETTO.replace("2026-10-10", oggi))
        apriDettaglio(id = 820)
        aspetta { esiste("Non è una truffa") }
        chiaroEScuro("06q_mail_truffa")
        compose.onNodeWithText("Non è una truffa").performClick()
        chiaroEScuro("06r_non_e_una_truffa", schermo = true)
    }

    @Test
    fun truffaConfermaInvio() {
        // Una risposta chiesta a mano per una mail sospetta: il server la prepara, la conferma avvisa.
        val sospetta812 = Esempi.DETTAGLIO.trimEnd().removeSuffix("}") + """, "sospetta": true}"""
        serverDettaglio(json = sospetta812, bozza = Esempi.bozza(modificata = true))
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Invia risposta") }
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        chiaroEScuro("06s_conferma_risposta_truffa", schermo = true)
    }

    @Test
    fun truffaSegnala() {
        serverDettaglio()
        val vm = apriDettaglio()
        aspetta { vm.bozza.stato.value.bozza != null }
        compose.onNodeWithContentDescription("Altre azioni").performClick()
        aspetta { esiste("Segnala come possibile truffa") }
        compose.onNodeWithText("Segnala come possibile truffa").performClick()
        chiaroEScuro("06t_segnala_come_truffa", schermo = true)
    }

    // --- Smista -----------------------------------------------------------------------------

    private fun serverSmista(vuota: Boolean = false) {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        val verdeIncerta = clienteVerdi.replace("\"smistamento\": \"smistata\"", "\"smistamento\": \"da_controllare\"").replace("\"sicurezza\": 0.88", "\"sicurezza\": 0.62")
        val lista = if (vuota) "" else "${Esempi.MAIL_BIANCHI}, $verdeIncerta, ${Esempi.MAIL_BIANCHI.replace("\"id\": 815", "\"id\": 817")}"
        server.rispondi("GET /api/mail?smistamento=da_controllare&limite=50", """{"mail": [$lista], "altre": false}""".replace("2026-10-10", oggi))
        server.rispondi("POST /api/mail/815/conferma", "{}")
        server.rispondi("POST /api/mail/819/conferma", "{}")
        server.rispondi("POST /api/mail/817/conferma", "{}")
    }

    @Test
    fun smista() {
        serverSmista()
        val vm = SmistaViewModel(client)
        mostra { SmistaScreen(vm) {} }
        aspetta { !vm.stato.value.caricamento && vm.stato.value.attuale != null }
        aspetta { esiste("Fattura ottobre") }
        chiaroEScuro("07_smista")
        compose.onNodeWithText("Cambia").performClick()
        aspetta { esiste("In quale gruppo va?") }
        chiaroEScuro("07b_smista_cambia")
        compose.onNodeWithText("Annulla").performClick()
        repeat(3) {
            compose.onNodeWithText("Va bene").performClick()
            compose.waitForIdle()
            aspetta { !vm.stato.value.inCorso }
        }
        aspetta { esiste("Tutto sistemato") }
        chiaroEScuro("07c_smista_fine")
    }

    @Test
    fun smistaVuota() {
        serverSmista(vuota = true)
        val vm = SmistaViewModel(client)
        mostra { SmistaScreen(vm) {} }
        aspetta { esiste("Niente da sistemare") }
        chiaroEScuro("07d_smista_niente")
    }

    // --- Riassunti, Scadenze, Aspetti risposta -------------------------------------------------

    @Test
    fun riassunti() {
        server.rispondi(
            "GET /api/riassunti/oggi",
            """{"testo": "Oggi 14 mail, 3 importanti. Rossi chiede l'offerta per il capannone di Bassano entro venerdì [#812]. Bianchi ha mandato la fattura di ottobre [#815].", "aggiornato_il": "${oggi}T09:00:00+02:00", "mail_citate": [812, 815], "stato": "pronto"}""",
        )
        server.rispondi(
            "GET /api/riassunti",
            """[{"id": 7, "richiesta": "le mail di Bianchi di questa settimana", "stato": "pronto", "testo": "x", "creato_il": "${oggi}T08:00:00+02:00"},
               {"id": 6, "richiesta": "cosa manca per la pratica del capannone", "stato": "pronto", "testo": "y", "creato_il": "2026-10-09T17:00:00+02:00"}]""",
        )
        val vm = RiassuntiViewModel(client, cache, intervalloMs = 10)
        mostra(Scheda.RIASSUNTI, daLeggere = 5) { RiassuntiScreen(vm) {} }
        aspetta { vm.stato.value.oggi != null }
        aspetta { esiste("Oggi 14 mail") && esiste("le mail di Bianchi di questa settimana") }
        chiaroEScuro("08_riassunti")

        server.rispondi("POST /api/riassunti", """{"id": 9, "stato": "in_coda"}""", 202)
        server.rispondi("GET /api/riassunti/9", "<html>Bad Gateway</html>", 502)
        vm.cambiaRichiesta("le mail di Rossi")
        vm.chiedi()
        aspetta { vm.stato.value.erroreAzione != null }
        compose.onNodeWithText("Riprova").performScrollTo()
        chiaroEScuro("08b_riassunto_senza_server")
    }

    @Test
    fun scadenze() {
        val domani = LocalDate.now().plusDays(1)
        server.rispondi(
            "GET /api/scadenze?stato=aperta",
            """[${Esempi.SCADENZA.replace("2026-10-16", LocalDate.now().toString())},
               {"id": 6, "descrizione": "Rinnovo PEC dello studio", "quando": "$domani", "ora": "09:00", "stato": "aperta"},
               {"id": 5, "descrizione": "Collaudo impianto Bianchi", "quando": "${domani.plusDays(4)}", "ora": "10:30", "stato": "aperta"}]""",
        )
        val vm = ScadenzeViewModel(client, cache)
        mostra { ScadenzeScreen(vm, onIndietro = {}, onApriMail = {}) }
        aspetta { vm.stato.value.scadenze.isNotEmpty() }
        aspetta { esiste("Collaudo impianto Bianchi") }
        chiaroEScuro("09_scadenze")
        compose.onNodeWithContentDescription("Aggiungi una scadenza").performClick()
        aspetta { esiste("Nuova scadenza") }
        chiaroEScuro("09b_nuova_scadenza", schermo = true)
    }

    @Test
    fun aspettiRisposta() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI_COLORATI)
        server.rispondi("GET /api/indietro", Esempi.INDIETRO.replace("2026-10-10", oggi))
        val vm = IndietroViewModel(client, cache)
        mostra { IndietroScreen(vm, {}, { _, _ -> }) }
        aspetta { vm.stato.value.dati != null && vm.stato.value.gruppi.isNotEmpty() }
        aspetta { esiste("Offerta capannone Bassano") && esiste("Offerta compressore") }
        chiaroEScuro("10_aspetti_risposta")
    }

    @Test
    fun sollecito() {
        val sollecito = Esempi.bozza(id = 60).replace("\"tipo\": \"risposta\"", "\"tipo\": \"sollecito\"")
            .replace("\"mail_id\": 812", "\"mail_id\": null")
            .replace("Re: Offerta capannone Bassano", "Offerta compressore")
            .replace("Buongiorno Mario,\\nl'offerta arriva giovedì.", "Buongiorno,\\nle scrivo per sapere se ha ricevuto l'offerta del compressore.")
            .replace("\"a\": [\"rossi@esempio.it\"]", "\"a\": [\"neri@esempio.it\"]")
        server.rispondi("GET /api/bozze/60", sollecito)
        val vm = BozzaViewModel(client, intervalloMs = 50)
        mostra { BozzaScreen(vm, 60, giaLetta = JsonSol.decodeFromString(Bozza.serializer(), sollecito), onApriMail = {}, onIndietro = {}) }
        aspetta { vm.bozza.stato.value.bozza != null }
        aspetta { esiste("Sollecito di Sol") }
        chiaroEScuro("14_sollecito")
    }

    @Test
    fun salvareLeModifiche() {
        mostra { eu.stgm.sol.DialogoModificheInCorso(onSalva = {}, onApri = {}, onResta = {}) }
        aspetta { esiste("Salvare le modifiche?") }
        chiaroEScuro("15_salvare_le_modifiche", schermo = true)
    }

    // --- Impostazioni -------------------------------------------------------------------------

    private fun serverImpostazioni(caselle: String = "[${Esempi.CASELLA}]") {
        server.rispondi("GET /api/caselle", caselle)
        server.rispondi("GET /api/gruppi", gruppiConRaccolte)
        server.rispondi(
            "GET /api/regole",
            """[{"id": 1, "tipo": "mittente", "valore": "bianchi@esempio.it", "gruppo_id": 3, "origine": "imparata"},
               {"id": 2, "tipo": "dominio", "valore": "pec.esempio.it", "gruppo_id": 4, "origine": "manuale"}]""",
        )
        server.rispondi(
            "GET /api/dispositivi",
            """[{"id": 3, "nome": "Telefono di Manuel", "tipo": "android", "ultimo_accesso": "${oggi}T09:00:00+02:00"}, {"id": 4, "nome": "PC dello studio", "tipo": "pc"}]""",
        )
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni().replace("\"conserva_giorni\": 180", "\"conserva_giorni\": 180, \"nome\": \"Manuel\""))
    }

    private fun apriImpostazioni(server: String = this.server.url): ImpostazioniViewModel {
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione(server, "x", 3)))
        mostra {
            ImpostazioniScreen(vm, TemaScelto.AUTOMATICO, onTema = {}, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}, onComeFunziona = {})
        }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        aspetta { esiste("Tono delle risposte") }
        return vm
    }

    @Test
    fun impostazioni() {
        serverImpostazioni(
            "[${Esempi.CASELLA}, {\"id\": 2, \"tipo\": \"gmail\", \"indirizzo\": \"rossi.personale@gmail.com\", \"nome\": \"Personale\", \"stato\": \"errore\", \"errore\": \"Google ha rifiutato la password per le app.\"}]",
        )
        server.rispondi("PATCH /api/gruppi/2", "{}")
        val vm = apriImpostazioni()
        aspetta { esiste("rossi.personale@gmail.com") }
        chiaroEScuro("11_impostazioni")
        compose.onNodeWithText("Versione e aggiornamento").performScrollTo()
        chiaroEScuro("11b_impostazioni_in_fondo")

        compose.onNodeWithText("Gruppi").performScrollTo().performClick()
        aspetta { esiste("Aggiungi un gruppo") }
        chiaroEScuro("11c_gruppi")
        compose.onNodeWithText("Fornitori").performClick()
        aspetta { esiste("Raccolta in una riga") }
        chiaroEScuro("11d_gruppo", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Aggiungi un gruppo").performClick()
        aspetta { esiste("Nuovo gruppo") }
        chiaroEScuro("11e_nuovo_gruppo", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Indietro").performClick()
        aspetta { esiste("Mittenti ricordati") }

        compose.onNodeWithText("Mittenti ricordati").performScrollTo().performClick()
        aspetta { esiste("pec.esempio.it") }
        chiaroEScuro("11f_mittenti_ricordati")
        compose.onNodeWithContentDescription("Indietro").performClick()
        aspetta { esiste("Dispositivi collegati") }
        compose.onNodeWithText("Dispositivi collegati").performScrollTo().performClick()
        aspetta { esiste("PC dello studio") }
        chiaroEScuro("11g_dispositivi")
        compose.onNodeWithContentDescription("Indietro").performClick()
        aspetta { esiste("Quanto deve essere sicuro Sol") }

        compose.onNodeWithText("Quanto deve essere sicuro Sol").performScrollTo().performClick()
        aspetta { esiste("va in Smista") }
        chiaroEScuro("11h_quanto_sicuro", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Controllo truffe").performScrollTo().performClick()
        aspetta { esiste("Più bassa") }
        chiaroEScuro("11i_controllo_truffe", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Tema").performScrollTo().performClick()
        aspetta { esiste("Automatico") }
        chiaroEScuro("11j_tema", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Versione e aggiornamento").performScrollTo().performClick()
        aspetta { esiste("Controlla aggiornamenti") }
        chiaroEScuro("11k_versione", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Studio").performScrollTo().performClick()
        aspetta { esiste("Controlla adesso") }
        chiaroEScuro("11l_casella", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Aggiungi Gmail").performScrollTo().performClick()
        aspetta { esiste("password per le app") }
        chiaroEScuro("11m_aggiungi_gmail", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Ricollega").performScrollTo().performClick()
        aspetta { esiste("Ricollega rossi.personale@gmail.com") }
        chiaroEScuro("11n_ricollega_gmail", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Nome").performScrollTo().performClick()
        aspetta { esiste("Come ti saluta Sol") }
        chiaroEScuro("11o_nome", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Aspettano te dopo").performScrollTo().performClick()
        aspetta { esiste("Un giorno in più") || compose.onAllNodes(hasContentDescription("Un giorno in più")).fetchSemanticsNodes().isNotEmpty() }
        chiaroEScuro("11p_giorni", schermo = true)
        vm.consumaMessaggio()
    }

    @Test
    fun impostazioniInChiaroEArretrato() {
        serverImpostazioni()
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/arretrato", """{"da_applicare": 37}""")
        // Il telefono è abbinato in chiaro (rete di casa): niente password Gmail.
        val vm = apriImpostazioni(server = "http://192.168.1.51:8200")
        compose.onNodeWithText("Aggiungi Gmail").performScrollTo().performClick()
        aspetta { esiste("password per le app") }
        chiaroEScuro("11q_gmail_in_chiaro_bloccata", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Server di casa").performScrollTo().performClick()
        aspetta { esiste("Abbina di nuovo") }
        chiaroEScuro("11r_server_non_protetto", schermo = true)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        vm.cambiaModalitaProva(false)
        aspetta { vm.stato.value.arretratoDaApplicare != null }
        chiaroEScuro("11s_arretrato_dopo_la_prova", schermo = true)
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
        val vm = apriImpostazioni()
        compose.onNodeWithText("Aggiungi Outlook").performScrollTo().performClick()
        compose.onNodeWithText("Continua").performClick()
        aspetta { vm.stato.value.outlook != null }
        aspetta { esiste("ABCD-EFGH") }
        chiaroEScuro("11t_aggiungi_outlook", schermo = true)
    }

    // --- Abbinamento, barra, tutorial -----------------------------------------------------------

    @Test
    fun abbinamento() {
        val vm = AbbinamentoViewModel(DepositoMemoria())
        mostra { AbbinamentoScreen(vm, onFatto = {}, onAnnulla = null) }
        chiaroEScuro("12_abbinamento")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("https://sol.esempio.ts.net")
        compose.onNode(hasContentDescription("Codice di 8 cifre")).performTextInput("1234")
        compose.onNodeWithText("Collega").performScrollTo().performClick()
        aspetta { vm.stato.value.errore != null }
        chiaroEScuro("12b_abbinamento_errore")
    }

    @Test
    fun abbinamentoPerso() {
        val vm = AbbinamentoViewModel(DepositoMemoria())
        vm.proponiIndirizzo("https://sol.esempio.ts.net")
        mostra { AbbinamentoScreen(vm, onFatto = {}, onAnnulla = {}, abbinamentoPerso = true) }
        chiaroEScuro("12c_abbinamento_perso")
    }

    @Test
    @Config(qualifiers = "it-rIT-w360dp-h800dp-xhdpi")
    fun barraSchede() {
        var scelta by mutableStateOf(Scheda.OGGI)
        mostra {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                BarraSchede(scelta, 128) { scelta = it }
            }
        }
        chiaroEScuro("16_barra_360dp_oggi")
        scelta = Scheda.POSTA
        chiaroEScuro("16b_barra_360dp_posta")
    }

    @Test
    fun tutorial() {
        mostra { TutorialScreen {} }
        aspetta { esiste("Ogni mattina qui trovi") }
        chiaroEScuro("13_tutorial_1_oggi")
        compose.onNodeWithText("Rivedi").performClick()
        aspetta { esiste("Cambiale il tono") }
        chiaroEScuro("13b_tutorial_2_risposta")
        compose.onNodeWithText("Più breve").performScrollTo().performClick()
        aspetta { esiste("Ora tocca Invia risposta.") }
        chiaroEScuro("13c_tutorial_2_dopo_il_tono")
        compose.onNodeWithText("Invia risposta").performClick()
        aspetta { esiste("Risposta inviata") }
        chiaroEScuro("13d_tutorial_2_inviata")
        compose.onNodeWithText("Avanti").performClick()
        aspetta { esiste("Scorri a destra") }
        chiaroEScuro("13e_tutorial_3_gesti")
        compose.onNodeWithText("Consegna materiale cantiere").performTouchInput { swipeRight() }
        aspetta { esiste("Ora scorri a sinistra") }
        compose.onNodeWithText("Consegna materiale cantiere").performTouchInput { swipeLeft() }
        aspetta { esiste("Ricorda questo mittente") }
        chiaroEScuro("13f_tutorial_3_foglio", schermo = true)
        compose.onNodeWithText("Clienti").performClick()
        aspetta { esiste("Spostata in Clienti") }
        chiaroEScuro("13g_tutorial_3_annulla")
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Avanti").performClick()
        aspetta { esiste("Quando Sol non è sicuro") }
        chiaroEScuro("13h_tutorial_4_smista")
        compose.onNodeWithText("Va bene").performClick()
        aspetta { esiste("telefona al mittente") }
        chiaroEScuro("13i_tutorial_5_truffa")
        compose.onNodeWithText("Ho capito").performClick()
        aspetta { esiste("Sei pronto.") }
        chiaroEScuro("13j_tutorial_6_fine")
    }
}
