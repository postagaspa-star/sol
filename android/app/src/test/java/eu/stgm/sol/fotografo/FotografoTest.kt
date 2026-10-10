package eu.stgm.sol.fotografo

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.stgm.sol.DepositoMemoria
import eu.stgm.sol.Esempi
import eu.stgm.sol.BarraSchede
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.RegolaMain
import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.abbinamento.AbbinamentoScreen
import eu.stgm.sol.ui.abbinamento.AbbinamentoViewModel
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
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Senza SolApp: niente WorkManager né notifiche mentre si fotografa. */
class AppFotografo : Application()

/**
 * Il fotografo: disegna le schermate vere (ViewModel veri contro il server
 * finto) in PNG, chiare e scure, per guardarle senza telefono.
 *   gradlew :app:testDebugUnitTest -Pfotografo -Pfotografo.cartella=C:/percorso
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = AppFotografo::class, qualifiers = "it-rIT-w393dp-h851dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class FotografoTest {
    // Le coroutine dei ViewModel riprendono sul thread di OkHttp: sotto Robolectric
    // il ritorno sul thread principale a volte resta in coda per sempre.
    @get:Rule(order = 0)
    val main = RegolaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val server = ServerFinto()
    private val client = server.client()
    private val cache = CacheMemoria()
    private val cartella = File(System.getProperty("fotografo.cartella") ?: "build/fotografo").also { it.mkdirs() }
    private var scuro by mutableStateOf(false)

    /**
     * Robolectric non disegna le finestre dei dialoghi e Compose ne aspetterebbe
     * per sempre la misura: a ogni giro d'attesa si misurano a mano tutte le
     * radici di Compose (come nel fotografo di Pactum). Sul telefono non serve.
     */
    private val misuratore = object : IdlingResource {
        override fun getName() = "fotografo-misura-i-dialoghi"
        override fun isIdleNow(): Boolean {
            try {
                misuraTutto()
            } catch (_: Throwable) {
            }
            return true
        }
        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {}
    }

    @Before
    fun prepara() {
        IdlingRegistry.getInstance().register(misuratore)
    }

    @After
    fun chiudi() {
        IdlingRegistry.getInstance().unregister(misuratore)
        server.close()
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun misuraTutto() {
        val radici = try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val istanza = wmg.getMethod("getInstance").invoke(null)
            val campo = wmg.getDeclaredField("mRoots").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (campo.get(istanza) as List<Any>).mapNotNull { it.javaClass.getMethod("getView").invoke(it) as? View }
        } catch (e: ReflectiveOperationException) {
            emptyList()
        }
        fun cerca(v: View) {
            if (v is ViewRootForTest && v.view.isAttachedToWindow) v.measureAndLayoutForTest()
            if (v is ViewGroup) for (i in 0 until v.childCount) cerca(v.getChildAt(i))
        }
        radici.toList().forEach { cerca(it) }
    }

    private fun mostra(contenuto: @Composable () -> Unit) {
        compose.setContent {
            SolTema(scuro = scuro) {
                CompositionLocalProvider(LocalMessaggi provides remember { SnackbarHostState() }) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { contenuto() }
                }
            }
        }
    }

    private fun scatta(nome: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(cartella, "$nome.png").path)
    }

    /** Con i dialoghi: tutte le finestre. */
    private fun scattaSchermo(nome: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(cartella, "$nome.png").path)
    }

    private fun chiaroEScuro(nome: String) {
        scuro = false
        scatta("${nome}_chiaro")
        scuro = true
        scatta("${nome}_scuro")
        scuro = false
    }

    private fun aspetta(condizione: () -> Boolean) = compose.waitUntil(10_000) { condizione() }

    private val GRUPPI_CON_DESCRIZIONE = """[
      {"id": 1, "nome": "Clienti", "ordine": 1, "descrizione": "un cliente chiede o conferma un lavoro", "da_leggere": 4, "totale": 120},
      {"id": 2, "nome": "Fornitori", "ordine": 2, "descrizione": "offerte, ordini e consegne dei fornitori", "totale": 40},
      {"id": 6, "nome": "Altro", "ordine": 6, "descrizione": "tutto quello che non va negli altri gruppi", "da_leggere": 1, "totale": 9}
    ]"""

    private fun serverPosta() {
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni(prova = true))
        val oggi = LocalDate.now()
        server.rispondi(
            "GET /api/mail",
            Esempi.PAGINA_MAIL.replace("2026-10-10", oggi.toString()),
        )
    }

    @Test
    fun posta() {
        serverPosta()
        server.rispondi(
            "GET /api/conteggi",
            """{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "da_leggere": 12, "bozze_pronte": 4,
               "gruppi": {"1": {"da_leggere": 4, "totale": 120}}}""",
        )
        val vm = PostaViewModel(client, cache)
        mostra { PostaScreen(vm, onApriMail = {}, collegamentoNonProtetto = true) }
        aspetta { vm.stato.value.caricataAlmenoUnaVolta && vm.stato.value.conteggi != null }
        chiaroEScuro("01_posta")
        server.rispondi("GET /api/mail", "<html>Bad Gateway</html>", 502)
        vm.carica()
        aspetta { vm.stato.value.errore != null && !vm.stato.value.caricamento }
        scatta("02_posta_senza_server")
    }

    @Test
    fun dettaglio() {
        serverPosta()
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO)
        server.rispondi("GET /api/bozze/55", Esempi.bozza())
        val vm = DettaglioViewModel(client, cache)
        mostra { DettaglioScreen(vm, 812) {} }
        aspetta { vm.bozza.stato.value.bozza != null }
        chiaroEScuro("03_dettaglio")
        compose.onNodeWithText("Invia...").performScrollTo()
        scatta("04_dettaglio_bozza")
        compose.onNodeWithText("Invia...").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        scattaSchermo("05_conferma_invio")
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Sposta in...").performScrollTo().performClick()
        scattaSchermo("06_sposta_in")
    }

    @Test
    fun dettaglioPecGiaRisposta() {
        serverPosta()
        val pec = Esempi.DETTAGLIO_GIA_RISPOSTA
            .replace("\"nome\": \"Mario Rossi\", \"indirizzo\": \"rossi@esempio.it\"", "\"nome\": \"\", \"indirizzo\": \"rossi@esempio.it\"")
            .trimEnd().removeSuffix("}") +
            """, "pec": {"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}}"""
        server.rispondi("GET /api/mail/812", pec)
        server.rispondi("GET /api/bozze/55", Esempi.bozza(modificata = true))
        val vm = DettaglioViewModel(client, cache)
        mostra { DettaglioScreen(vm, 812) {} }
        aspetta { vm.bozza.stato.value.bozza != null }
        scatta("03b_dettaglio_pec_da_senza_nome")
        compose.onNodeWithText("Invia...").performScrollTo()
        scatta("04b_bozza_gia_risposto")
        compose.onNodeWithText("Invia...").performClick()
        aspetta { vm.bozza.stato.value.confermaInvio != null }
        scattaSchermo("05b_conferma_gia_risposto")
    }

    @Test
    fun bozzaVuotaEVerifica() {
        serverPosta()
        server.rispondi("GET /api/mail/812", Esempi.DETTAGLIO)
        server.rispondi("GET /api/bozze/55", Esempi.bozza(testo = ""))
        val vm = DettaglioViewModel(client, cache, verificaOgniMs = 60_000, verificaPerMs = 120_000)
        mostra { DettaglioScreen(vm, 812) {} }
        aspetta { vm.bozza.stato.value.bozza != null }
        compose.onNodeWithText("Riprova a prepararla").performScrollTo()
        scatta("04c_bozza_senza_testo")

        // Invio con esito incerto: la frase del server, e "Invia" spento mentre si ricontrolla.
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
        compose.onNodeWithText("Controllo se è partita...").performScrollTo()
        scatta("04d_bozza_verifica_invio")
    }

    @Test
    fun salvareLeModifiche() {
        mostra { eu.stgm.sol.DialogoModificheInCorso(onSalva = {}, onApri = {}, onResta = {}) }
        scattaSchermo("19_salvare_le_modifiche")
    }

    @Test
    fun riassunti() {
        server.rispondi(
            "GET /api/riassunti/oggi",
            """{"testo": "Oggi 14 mail, 3 importanti. Rossi chiede l'offerta per il capannone di Bassano entro venerdì [#812]. Bianchi ha mandato la fattura di ottobre [#815].", "aggiornato_il": "${LocalDate.now()}T09:00:00+02:00", "mail_citate": [812, 815], "stato": "pronto"}""",
        )
        server.rispondi("GET /api/riassunti", """[{"id": 7, "richiesta": "le mail di Bianchi di questa settimana", "stato": "pronto", "testo": "x", "creato_il": "${LocalDate.now()}T08:00:00+02:00"}]""")
        val vm = RiassuntiViewModel(client, cache, intervalloMs = 10)
        mostra { RiassuntiScreen(vm) {} }
        aspetta { vm.stato.value.oggi != null }
        chiaroEScuro("07_riassunti")

        server.rispondi("POST /api/riassunti", """{"id": 9, "stato": "in_coda"}""", 202)
        server.rispondi("GET /api/riassunti/9", "<html>Bad Gateway</html>", 502)
        vm.cambiaRichiesta("le mail di Rossi")
        vm.chiedi()
        aspetta { vm.stato.value.erroreAzione != null }
        compose.onNodeWithText("Riprova").performScrollTo()
        scatta("07b_riassunto_senza_server")
    }

    @Test
    fun indietro() {
        server.rispondi("GET /api/indietro", Esempi.INDIETRO)
        val vm = IndietroViewModel(client, cache)
        mostra { IndietroScreen(vm, {}) { _, _ -> } }
        aspetta { vm.stato.value.dati != null }
        chiaroEScuro("08_in_sospeso")
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
        chiaroEScuro("09_scadenze")
        compose.onNodeWithText("Aggiungi", useUnmergedTree = true).performClick()
        scattaSchermo("10_nuova_scadenza")
    }

    @Test
    fun impostazioni() {
        server.rispondi("GET /api/caselle", "[${Esempi.CASELLA}, {\"id\": 2, \"tipo\": \"gmail\", \"indirizzo\": \"rossi.personale@gmail.com\", \"nome\": \"Personale\", \"stato\": \"errore\", \"errore\": \"Google ha rifiutato la password per le app.\"}]")
        server.rispondi("GET /api/gruppi", GRUPPI_CON_DESCRIZIONE)
        server.rispondi("GET /api/regole", """[{"id": 1, "tipo": "mittente", "valore": "bianchi@esempio.it", "gruppo_id": 1, "origine": "imparata"}]""")
        server.rispondi("GET /api/dispositivi", """[{"id": 3, "nome": "Telefono di Manuel", "tipo": "android", "ultimo_accesso": "${LocalDate.now()}T09:00:00+02:00"}, {"id": 4, "nome": "PC dello studio", "tipo": "pc"}]""")
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione(server.url, "x", 3)))
        mostra { ImpostazioniScreen(vm, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}) }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        chiaroEScuro("11_impostazioni")
        compose.onNodeWithText("Aggiungi un gruppo").performScrollTo()
        scatta("11b_impostazioni_gruppi")
        compose.onNodeWithText("Aggiungi un gruppo").performClick()
        scattaSchermo("11c_nuovo_gruppo")
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Ricollega").performScrollTo().performClick()
        scattaSchermo("11d_ricollega_gmail")
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Modalità prova").performScrollTo()
        scatta("12_impostazioni_come_lavora")
        compose.onNodeWithText("Tono delle risposte").performScrollTo()
        scatta("12b_impostazioni_firma_tono")
        compose.onNodeWithText("Controlla aggiornamenti").performScrollTo()
        scatta("12c_impostazioni_dispositivi_versione")
        compose.onNodeWithText("Aggiungi Gmail").performScrollTo().performClick()
        scattaSchermo("13_aggiungi_gmail")
    }

    @Test
    fun impostazioniInChiaroEArretrato() {
        server.rispondi("GET /api/caselle", "[${Esempi.CASELLA}]")
        server.rispondi("GET /api/gruppi", GRUPPI_CON_DESCRIZIONE)
        server.rispondi("GET /api/regole", "[]")
        server.rispondi("GET /api/dispositivi", "[]")
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
        server.rispondi("PATCH /api/impostazioni", Esempi.impostazioni(prova = false))
        server.rispondi("GET /api/arretrato", """{"da_applicare": 37}""")
        // Il telefono è abbinato in chiaro (rete di casa): niente password Gmail.
        val vm = ImpostazioniViewModel(client, cache, DepositoMemoria(Configurazione("http://192.168.1.51:8200", "x", 3)))
        mostra { ImpostazioniScreen(vm, onIndietro = {}, onAbbinaDiNuovo = {}, onScollegato = {}) }
        aspetta { vm.stato.value.impostazioni != null && !vm.stato.value.caricamento }
        compose.onNodeWithText("Aggiungi Gmail").performScrollTo().performClick()
        scattaSchermo("13b_gmail_in_chiaro_bloccata")
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithText("Abbina di nuovo").performScrollTo()
        scatta("12d_questo_telefono_non_protetto")
        vm.cambiaModalitaProva(false)
        aspetta { vm.stato.value.arretratoDaApplicare != null }
        scattaSchermo("12e_arretrato_dopo_la_prova")
    }

    @Test
    fun outlook() {
        server.rispondi("GET /api/caselle", "[]")
        server.rispondi("GET /api/gruppi", Esempi.GRUPPI)
        server.rispondi("GET /api/regole", "[]")
        server.rispondi("GET /api/dispositivi", "[]")
        server.rispondi("GET /api/versione", Esempi.VERSIONE)
        server.rispondi("GET /api/impostazioni", Esempi.impostazioni())
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
        try {
            aspetta { vm.stato.value.outlook != null }
        } finally {
            println("DEBUG outlook=" + vm.stato.value.copy(caselle = emptyList(), gruppi = emptyList()) + " richieste=" + server.richieste.map { it.method + " " + it.path })
        }
        scattaSchermo("14_aggiungi_outlook")
    }

    @Test
    @Config(qualifiers = "it-rIT-w360dp-h800dp-xhdpi")
    fun barraSchede() {
        var scelta by mutableStateOf(Scheda.POSTA)
        mostra {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                BarraSchede(scelta) { scelta = it }
            }
        }
        scatta("17_barra_360dp")
        scelta = Scheda.INDIETRO
        scuro = true
        scatta("18_barra_360dp_scuro")
    }

    @Test
    fun abbinamento() {
        val vm = AbbinamentoViewModel(DepositoMemoria())
        mostra { AbbinamentoScreen(vm, onFatto = {}, onAnnulla = null) }
        chiaroEScuro("15_abbinamento")
        compose.onNodeWithText("Collega").performScrollTo()
        compose.onNodeWithText("Indirizzo del server").performTextInput("http://sol.esempio.it")
        compose.onNodeWithText("Collega").performClick()
        scatta("16_abbinamento_errore")
    }

    @Test
    fun abbinamentoPerso() {
        val vm = AbbinamentoViewModel(DepositoMemoria())
        mostra { AbbinamentoScreen(vm, onFatto = {}, onAnnulla = null, abbinamentoPerso = true) }
        scatta("15b_abbinamento_perso")
    }
}
