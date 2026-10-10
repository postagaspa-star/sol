package eu.stgm.sol

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.sol.aggiornamento.AppInPrimoPiano
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.notifiche.Notificatore
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.rete.IndirizzoServer
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.LocalApriImpostazioni
import eu.stgm.sol.ui.Navigazione
import eu.stgm.sol.ui.Pagina
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** I messaggi brevi in basso ("Mail inviata", "Spostata in Clienti"), di tutta l'app. */
val LocalMessaggi = staticCompositionLocalOf { SnackbarHostState() }

class MainActivity : ComponentActivity() {

    /** La pagina da aprire arrivando da una notifica. null = avvio normale. */
    private val richiesta = mutableStateOf<Pagina?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Solo per un tocco vero sulla notifica: non dopo una rotazione, non dai recenti.
        val daiRecenti = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !daiRecenti) richiesta.value = intent?.paginaDellaNotifica()
        intent?.removeExtra(EXTRA_MAIL_ID)
        intent?.removeExtra(EXTRA_BOZZA_ID)
        setContent {
            SolTema {
                Radice(
                    richiesta = richiesta.value,
                    onRichiestaConsumata = { richiesta.value = null },
                    onNuovoAbbinamento = ::ricomincia,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppInPrimoPiano.visibile = true
    }

    override fun onStop() {
        AppInPrimoPiano.visibile = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        richiesta.value = intent.paginaDellaNotifica()
        intent.removeExtra(EXTRA_MAIL_ID)
        intent.removeExtra(EXTRA_BOZZA_ID)
    }

    /** La mail della notifica; per un sollecito (senza mail ricevuta) la sua bozza. */
    private fun Intent.paginaDellaNotifica(): Pagina? {
        val mail = if (hasExtra(EXTRA_MAIL_ID)) getLongExtra(EXTRA_MAIL_ID, -1L).takeIf { it >= 0 } else null
        if (mail != null) return Pagina.Mail(mail)
        val bozza = if (hasExtra(EXTRA_BOZZA_ID)) getLongExtra(EXTRA_BOZZA_ID, -1L).takeIf { it >= 0 } else null
        return bozza?.let { Pagina.Bozza(it) }
    }

    /**
     * Un altro abbinamento (o lo scollegamento) è un altro server: l'app riparte
     * da zero, senza i ViewModel, la cache in memoria e le pagine aperte di prima.
     */
    private fun ricomincia() {
        if (isFinishing) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_MAIL_ID = "mail_id"
        const val EXTRA_BOZZA_ID = "bozza_id"
    }
}

private sealed interface StatoAvvio {
    data object Lettura : StatoAvvio
    data class Pronto(val configurazione: Configurazione?) : StatoAvvio
}

@Composable
private fun Radice(richiesta: Pagina?, onRichiestaConsumata: () -> Unit, onNuovoAbbinamento: () -> Unit) {
    val app = LocalContext.current.applicationContext as SolApp
    val avvio by remember { app.archivio.flusso.map<Configurazione?, StatoAvvio> { StatoAvvio.Pronto(it) } }
        .collectAsState(initial = StatoAvvio.Lettura)
    val perso by app.archivio.abbinamentoPerso.collectAsState(initial = false)

    // Il telefono abbinato non lo è più (server che non lo riconosce, o scollegato):
    // si riparte da capo, così niente di quello che si vedeva resta in memoria.
    var eraAbbinato by remember { mutableStateOf(false) }
    LaunchedEffect(avvio) {
        val a = avvio as? StatoAvvio.Pronto ?: return@LaunchedEffect
        if (a.configurazione != null) eraAbbinato = true else if (eraAbbinato) onNuovoAbbinamento()
    }

    val messaggi = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalMessaggi provides messaggi) {
        when (val a = avvio) {
            StatoAvvio.Lettura -> Unit
            is StatoAvvio.Pronto ->
                if (a.configurazione == null) {
                    Scaffold(snackbarHost = { SnackbarHost(messaggi) }) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                            val vm: AbbinamentoViewModel = viewModel {
                                AbbinamentoViewModel(app.archivio, prima = { app.svuotaDatiLocali() })
                            }
                            LaunchedEffect(Unit) { vm.proponiIndirizzo(app.archivio.ultimoServer()) }
                            AbbinamentoScreen(vm, onFatto = onNuovoAbbinamento, onAnnulla = null, abbinamentoPerso = perso)
                        }
                    }
                } else {
                    App(app, messaggi, richiesta, onRichiestaConsumata, onNuovoAbbinamento)
                }
        }
    }
}

private val IconeSchede: Map<Scheda, ImageVector> = mapOf(
    Scheda.POSTA to Icons.Filled.Email,
    Scheda.RIASSUNTI to Icons.AutoMirrored.Filled.List,
    Scheda.INDIETRO to Icons.Filled.Notifications,
    Scheda.SCADENZE to Icons.Filled.DateRange,
)

/** La barra in basso con le quattro schede di tutti i giorni. */
@Composable
fun BarraSchede(scelta: Scheda, onScegli: (Scheda) -> Unit) {
    NavigationBar {
        Scheda.entries.forEach { scheda ->
            NavigationBarItem(
                selected = scelta == scheda,
                onClick = { onScegli(scheda) },
                icon = { Icon(IconeSchede.getValue(scheda), contentDescription = null) },
                label = {
                    // 14 sp: "In sospeso" ci sta intero anche su un telefono da 360 dp.
                    Text(
                        scheda.etichetta,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun App(
    app: SolApp,
    messaggi: SnackbarHostState,
    richiesta: Pagina?,
    onRichiestaConsumata: () -> Unit,
    onNuovoAbbinamento: () -> Unit,
) {
    var codice by rememberSaveable { mutableStateOf(Navigazione().codifica()) }
    val nav = remember(codice) { Navigazione.decodifica(codice) }
    val vai: (Navigazione) -> Unit = { codice = it.codifica() }
    // Il sollecito appena chiesto: la pagina della bozza lo mostra subito, senza rileggerlo.
    var sollecitoAppena by remember { mutableStateOf<Bozza?>(null) }

    val postaVm: PostaViewModel = viewModel { PostaViewModel(app.client, app.cache) }
    val dettaglioVm: DettaglioViewModel = viewModel {
        DettaglioViewModel(app.client, app.cache, memoriaBozze = app.bozzeInModifica)
    }
    val bozzaVm: BozzaViewModel = viewModel { BozzaViewModel(app.client, memoriaBozze = app.bozzeInModifica) }
    val riassuntiVm: RiassuntiViewModel = viewModel { RiassuntiViewModel(app.client, app.cache) }
    val indietroVm: IndietroViewModel = viewModel { IndietroViewModel(app.client, app.cache) }
    val scadenzeVm: ScadenzeViewModel = viewModel { ScadenzeViewModel(app.client, app.cache) }
    val impostazioniVm: ImpostazioniViewModel = viewModel {
        ImpostazioniViewModel(app.client, app.cache, app.archivio, svuotaDati = app::svuotaDatiLocali)
    }
    val abbinamentoVm: AbbinamentoViewModel = viewModel {
        AbbinamentoViewModel(app.archivio, prima = { app.svuotaDatiLocali() })
    }

    val configurazione by app.archivio.flusso.collectAsState(initial = null)
    val nonProtetto = IndirizzoServer.nonProtetto(configurazione?.server)

    // Arrivo da una notifica mentre si sta scrivendo una risposta: prima si chiede.
    var inSospeso by remember { mutableStateOf<Pagina?>(null) }
    LaunchedEffect(richiesta) {
        val pagina = richiesta ?: return@LaunchedEffect
        onRichiestaConsumata()
        val scrivendo = when (nav.inCima) {
            is Pagina.Mail -> dettaglioVm.haModificheNonSalvate()
            is Pagina.Bozza -> bozzaVm.bozza.haModificheNonSalvate()
            else -> false
        }
        if (scrivendo && nav.inCima != pagina) inSospeso = pagina else vai(Navigazione(Scheda.POSTA).apri(pagina))
    }

    val dopoIndietro = nav.indietro()
    BackHandler(enabled = dopoIndietro != null) { dopoIndietro?.let(vai) }

    RichiestaNotifiche()

    val apriMail: (Long) -> Unit = { vai(nav.apri(Pagina.Mail(it))) }
    val indietro: () -> Unit = { vai(dopoIndietro ?: Navigazione()) }

    // Ogni scheda tiene il suo stato (scorrimento, filtri) anche quando si apre
    // una mail e si torna indietro: altrimenti la lista ripartirebbe dall'inizio.
    val statoSchede = rememberSaveableStateHolder()

    CompositionLocalProvider(LocalApriImpostazioni provides { vai(nav.apri(Pagina.Impostazioni)) }) {
        Scaffold(
            bottomBar = { if (nav.inCima == null) BarraSchede(nav.scheda) { vai(nav.scegli(it)) } },
            snackbarHost = { SnackbarHost(messaggi) },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                when (val pagina = nav.inCima) {
                    is Pagina.Mail -> DettaglioScreen(dettaglioVm, pagina.id, onIndietro = indietro)
                    is Pagina.Bozza -> BozzaScreen(
                        bozzaVm,
                        pagina.id,
                        giaLetta = sollecitoAppena,
                        onApriMail = apriMail,
                        onIndietro = indietro,
                    )
                    Pagina.Impostazioni -> ImpostazioniScreen(
                        impostazioniVm,
                        onIndietro = indietro,
                        onAbbinaDiNuovo = { vai(nav.apri(Pagina.Abbinamento)) },
                        onScollegato = onNuovoAbbinamento,
                    )
                    Pagina.Abbinamento -> {
                        LaunchedEffect(Unit) { abbinamentoVm.proponiIndirizzo(app.archivio.ultimoServer()) }
                        AbbinamentoScreen(abbinamentoVm, onFatto = onNuovoAbbinamento, onAnnulla = indietro)
                    }
                    null -> statoSchede.SaveableStateProvider(nav.scheda.name) {
                        when (nav.scheda) {
                            Scheda.POSTA -> PostaScreen(postaVm, onApriMail = apriMail, collegamentoNonProtetto = nonProtetto)
                            Scheda.RIASSUNTI -> RiassuntiScreen(riassuntiVm, onApriMail = apriMail)
                            Scheda.INDIETRO -> IndietroScreen(
                                indietroVm,
                                onApriMail = apriMail,
                                onApriBozza = { id, appena ->
                                    sollecitoAppena = appena
                                    vai(nav.apri(Pagina.Bozza(id)))
                                },
                            )
                            Scheda.SCADENZE -> ScadenzeScreen(scadenzeVm, onApriMail = apriMail)
                        }
                    }
                }
            }
        }
    }

    inSospeso?.let { destinazione ->
        val gestore = if (nav.inCima is Pagina.Bozza) bozzaVm.bozza else dettaglioVm.bozza
        DialogoModificheInCorso(
            onSalva = {
                inSospeso = null
                gestore.salvaModifica(poi = { vai(Navigazione(Scheda.POSTA).apri(destinazione)) })
            },
            onApri = {
                inSospeso = null
                vai(Navigazione(Scheda.POSTA).apri(destinazione))
            },
            onResta = { inSospeso = null },
        )
    }
}

/**
 * Una notifica porta altrove mentre Manuel sta scrivendo una risposta. Niente si
 * perde in nessun caso: senza salvare, il testo resta sul telefono per quella bozza.
 */
@Composable
internal fun DialogoModificheInCorso(onSalva: () -> Unit, onApri: () -> Unit, onResta: () -> Unit) {
    AlertDialog(
        onDismissRequest = onResta,
        title = { Text("Salvare le modifiche?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Stai scrivendo una risposta. Se apri l'altra mail senza salvare, il testo resta qui sul " +
                        "telefono e lo ritrovi tornando su questa.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                PulsantePieno("Salva e apri", onSalva, modifier = Modifier.fillMaxWidth())
                PulsanteVuoto("Apri senza salvare", onApri, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PulsanteTesto("Resta qui", onResta) },
    )
}

/**
 * Android 13 e oltre: prima una spiegazione, poi la richiesta di sistema. Una
 * volta sola; se Manuel dice "non ora" le novità restano visibili aprendo l'app.
 */
@Composable
private fun RichiestaNotifiche() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val app = context.applicationContext as SolApp
    val ambito = rememberCoroutineScope()
    val fatta by app.archivio.richiestaNotificheFatta.collectAsState(initial = null)
    var mostra by rememberSaveable { mutableStateOf(false) }
    val lancio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(fatta) {
        if (fatta == false && !Notificatore.puoNotificare(context)) mostra = true
    }
    if (!mostra) return
    val chiudi = {
        mostra = false
        ambito.launch { app.archivio.registraRichiestaNotificheFatta() }
        Unit
    }
    DialogoConferma(
        titolo = "Avvisi di Sol",
        testo = "Sol ti avvisa solo per le mail urgenti, le risposte pronte da leggere e le scadenze di domani. " +
            "Nient'altro. Android ora ti chiede il permesso.",
        conferma = "Va bene",
        onConferma = {
            chiudi()
            lancio.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onAnnulla = chiudi,
    )
}
