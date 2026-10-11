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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.sol.aggiornamento.AppInPrimoPiano
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.TemaScelto
import eu.stgm.sol.notifiche.Notificatore
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.FoglioSol
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.IconeBarraDiStato
import eu.stgm.sol.ui.MessaggioSol
import eu.stgm.sol.ui.Navigazione
import eu.stgm.sol.ui.Pagina
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteGrandeBianco
import eu.stgm.sol.ui.PulsanteTesto
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
import eu.stgm.sol.ui.posta.PallinoNumero
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
import eu.stgm.sol.ui.tutorial.TutorialAlPrimoAvvio
import eu.stgm.sol.ui.tutorial.TutorialScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** I messaggi brevi in basso ("Mail inviata", "Spostata in Clienti"), di tutta l'app. */
val LocalMessaggi = staticCompositionLocalOf { SnackbarHostState() }

/** Il tema scelto nelle Impostazioni, tradotto in chiaro o scuro. */
@Composable
fun temaScuro(tema: TemaScelto): Boolean = when (tema) {
    TemaScelto.AUTOMATICO -> isSystemInDarkTheme()
    TemaScelto.CHIARO -> false
    TemaScelto.SCURO -> true
}

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
        val app = application as SolApp
        setContent {
            val tema by app.archivio.tema.collectAsState(initial = TemaScelto.AUTOMATICO)
            val scuro = temaScuro(tema)
            SolTema(scuro = scuro) {
                IconeBarraDiStato(scure = !scuro)
                Radice(
                    tema = tema,
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

/**
 * In alto niente margine: ogni pagina passa sotto la barra di stato e ci pensa da
 * sé; ai lati e in basso sì.
 */
private val InsetsSottoLaBarra: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

@Composable
private fun Radice(tema: TemaScelto, richiesta: Pagina?, onRichiestaConsumata: () -> Unit, onNuovoAbbinamento: () -> Unit) {
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
            StatoAvvio.Lettura -> Box(Modifier.fillMaxSize().background(Sol.colori.fondo))
            is StatoAvvio.Pronto ->
                if (a.configurazione == null) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(messaggi) { MessaggioSol(it) } },
                        contentWindowInsets = InsetsSottoLaBarra,
                        containerColor = Sol.colori.fondo,
                    ) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                            val vm: AbbinamentoViewModel = viewModel {
                                AbbinamentoViewModel(app.archivio, prima = { app.svuotaDatiLocali() })
                            }
                            LaunchedEffect(Unit) { vm.proponiIndirizzo(app.archivio.ultimoServer()) }
                            AbbinamentoScreen(vm, onFatto = onNuovoAbbinamento, onAnnulla = null, abbinamentoPerso = perso)
                        }
                    }
                } else {
                    App(app, tema, messaggi, richiesta, onRichiestaConsumata, onNuovoAbbinamento)
                }
        }
    }
}

private val IconeSchede: Map<Scheda, ImageVector> = mapOf(
    Scheda.OGGI to Icone.Oggi,
    Scheda.POSTA to Icone.Posta,
    Scheda.RIASSUNTI to Icone.Riassunti,
)

/**
 * La barra in basso a 3 voci (docs/prototipo/Main.dc.html): la voce attiva ha la
 * pillola accento tenue dietro l'icona e il testo nell'accento; Posta ha il pallino
 * col numero delle importanti da leggere.
 */
@Composable
fun BarraSchede(scelta: Scheda, daLeggere: Int, onScegli: (Scheda) -> Unit) {
    val c = Sol.colori
    Column(Modifier.fillMaxWidth().background(c.barraSfondo)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.barraLinea))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Top,
        ) {
            Scheda.entries.forEach { scheda ->
                VoceBarra(scheda, attiva = scheda == scelta, numero = if (scheda == Scheda.POSTA) daLeggere else 0) { onScegli(scheda) }
            }
        }
    }
}

@Composable
private fun VoceBarra(scheda: Scheda, attiva: Boolean, numero: Int, onClick: () -> Unit) {
    val c = Sol.colori
    val colore = if (attiva) c.accentoTesto else c.barraInattiva
    val descrizione = if (numero > 0) "${scheda.etichetta}, $numero da leggere" else scheda.etichetta
    Column(
        Modifier
            .widthIn(min = 72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 40.dp),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = attiva
                contentDescription = descrizione
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(64.dp)
                .height(32.dp)
                .background(if (attiva) c.accentoTenueForte else c.barraSfondo.copy(alpha = 0f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(IconeSchede.getValue(scheda), contentDescription = null, tint = colore, modifier = Modifier.size(22.dp))
            // Il bordo sinistro del pallino resta fermo: con "99+" cresce verso destra, non copre l'icona.
            if (numero > 0) PallinoNumero(numero, Modifier.align(Alignment.TopStart).offset(x = 34.dp))
        }
        Text(
            scheda.etichetta,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (attiva) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = Sol.testi.etichetta.fontFamily,
            color = colore,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun App(
    app: SolApp,
    tema: TemaScelto,
    messaggi: SnackbarHostState,
    richiesta: Pagina?,
    onRichiestaConsumata: () -> Unit,
    onNuovoAbbinamento: () -> Unit,
) {
    var codice by rememberSaveable { mutableStateOf(Navigazione().codifica()) }
    val nav = remember(codice) { Navigazione.decodifica(codice) }
    val vai: (Navigazione) -> Unit = { codice = it.codifica() }
    val ambito = rememberCoroutineScope()
    // Il sollecito appena chiesto: la pagina della bozza lo mostra subito, senza rileggerlo.
    var sollecitoAppena by remember { mutableStateOf<Bozza?>(null) }

    val oggiVm: OggiViewModel = viewModel { OggiViewModel(app.client, app.cache) }
    val postaVm: PostaViewModel = viewModel { PostaViewModel(app.client, app.cache) }
    // Raccolta e ricerca hanno il loro elenco: la Posta resta com'era tornando indietro.
    val raccoltaVm: PostaViewModel = viewModel(key = "raccolta") { PostaViewModel(app.client, app.cache) }
    val ricercaVm: PostaViewModel = viewModel(key = "ricerca") { PostaViewModel(app.client, app.cache) }
    val smistaVm: SmistaViewModel = viewModel { SmistaViewModel(app.client) }
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
    // Il pallino della Posta si vede anche da Oggi: i conteggi si leggono all'avvio.
    LaunchedEffect(Unit) { postaVm.carica() }
    val statoPosta by postaVm.stato.collectAsStateWithLifecycle()

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
        if (scrivendo && nav.inCima != pagina) inSospeso = pagina else vai(Navigazione().apri(pagina))
    }

    // Al primo avvio dopo l'abbinamento, una volta sola (docs/aspetto.md §10).
    TutorialAlPrimoAvvio(app.archivio) { vai(nav.apri(Pagina.Tutorial)) }

    val dopoIndietro = nav.indietro()
    BackHandler(enabled = dopoIndietro != null) { dopoIndietro?.let(vai) }

    // La richiesta degli avvisi aspetta la fine del tutorial: una cosa alla volta.
    val tutorialVisto by app.archivio.tutorialVisto.collectAsState(initial = null)
    if (tutorialVisto == true && nav.inCima != Pagina.Tutorial) RichiestaNotifiche()

    val apriMail: (Long) -> Unit = { vai(nav.apri(Pagina.Mail(it))) }
    val apriBozza: (Long, Bozza?) -> Unit = { id, appena ->
        sollecitoAppena = appena
        vai(nav.apri(Pagina.Bozza(id)))
    }
    val indietro: () -> Unit = { vai(dopoIndietro ?: Navigazione()) }

    // Ogni scheda tiene il suo stato (scorrimento, filtri) anche quando si apre
    // una mail e si torna indietro: altrimenti la lista ripartirebbe dall'inizio.
    val statoSchede = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = { if (nav.inCima == null) BarraSchede(nav.scheda, statoPosta.daLeggere) { vai(nav.scegli(it)) } },
        snackbarHost = { SnackbarHost(messaggi) { MessaggioSol(it) } },
        contentWindowInsets = InsetsSottoLaBarra,
        containerColor = Sol.colori.fondo,
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            when (val pagina = nav.inCima) {
                is Pagina.Mail -> DettaglioScreen(dettaglioVm, pagina.id, onIndietro = indietro)
                is Pagina.Bozza -> BozzaScreen(bozzaVm, pagina.id, giaLetta = sollecitoAppena, onApriMail = apriMail, onIndietro = indietro)
                is Pagina.Raccolta -> RaccoltaScreen(raccoltaVm, pagina.gruppoId, onApriMail = apriMail, onIndietro = indietro)
                Pagina.Impostazioni -> ImpostazioniScreen(
                    impostazioniVm,
                    tema = tema,
                    onTema = { scelto -> ambito.launch { app.archivio.cambiaTema(scelto) } },
                    onIndietro = indietro,
                    onAbbinaDiNuovo = { vai(nav.apri(Pagina.Abbinamento)) },
                    onScollegato = onNuovoAbbinamento,
                    onComeFunziona = { vai(nav.apri(Pagina.Tutorial)) },
                )
                Pagina.Abbinamento -> {
                    LaunchedEffect(Unit) { abbinamentoVm.proponiIndirizzo(app.archivio.ultimoServer()) }
                    AbbinamentoScreen(abbinamentoVm, onFatto = onNuovoAbbinamento, onAnnulla = indietro)
                }
                Pagina.Smista -> SmistaScreen(smistaVm, onChiudi = indietro)
                Pagina.Ricerca -> RicercaScreen(ricercaVm, onApriMail = apriMail, onIndietro = indietro)
                Pagina.Scadenze -> ScadenzeScreen(scadenzeVm, onIndietro = indietro, onApriMail = apriMail)
                Pagina.AspettiRisposta -> IndietroScreen(indietroVm, onApriMail = apriMail, onApriBozza = apriBozza, onIndietro = indietro)
                // "Inizia" porta sempre a Oggi, anche se il tutorial è stato riaperto dalle Impostazioni.
                Pagina.Tutorial -> TutorialScreen(onFine = { vai(Navigazione()) })
                null -> statoSchede.SaveableStateProvider(nav.scheda.name) {
                    when (nav.scheda) {
                        Scheda.OGGI -> OggiScreen(
                            oggiVm,
                            AzioniOggi(
                                apriMail = apriMail,
                                apriBozza = apriBozza,
                                apriSmista = { vai(nav.apri(Pagina.Smista)) },
                                apriScadenze = { vai(nav.apri(Pagina.Scadenze)) },
                                apriAspetti = { vai(nav.apri(Pagina.AspettiRisposta)) },
                                apriImpostazioni = { vai(nav.apri(Pagina.Impostazioni)) },
                            ),
                        )
                        Scheda.POSTA -> PostaScreen(
                            postaVm,
                            onApriMail = apriMail,
                            onApriSmista = { vai(nav.apri(Pagina.Smista)) },
                            onApriRaccolta = { vai(nav.apri(Pagina.Raccolta(it))) },
                            onApriRicerca = { vai(nav.apri(Pagina.Ricerca)) },
                        )
                        Scheda.RIASSUNTI -> RiassuntiScreen(riassuntiVm, onApriMail = apriMail)
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
                gestore.salvaModifica(poi = { vai(Navigazione().apri(destinazione)) })
            },
            onApri = {
                inSospeso = null
                vai(Navigazione().apri(destinazione))
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
    FoglioSol(onChiudi = onResta, titolo = "Salvare le modifiche?") {
        Text(
            "Stai scrivendo una risposta. Se apri l'altra mail senza salvare, il testo resta qui sul " +
                "telefono e lo ritrovi tornando su questa.",
            style = Sol.testi.testo,
            color = Sol.colori.testoCorpo,
        )
        PulsanteGrande("Salva e apri", onSalva, Modifier.fillMaxWidth().padding(top = 20.dp))
        PulsanteGrandeBianco("Apri senza salvare", onApri, Modifier.fillMaxWidth().padding(top = 10.dp))
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            PulsanteTesto("Resta qui", onResta, colore = Sol.colori.tenue)
        }
    }
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
        annulla = "Non ora",
    )
}
