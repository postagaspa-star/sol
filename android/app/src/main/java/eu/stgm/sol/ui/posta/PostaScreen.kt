package eu.stgm.sol.ui.posta

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.EtichettaGruppo
import eu.stgm.sol.ui.FasciaCalda
import eu.stgm.sol.ui.FoglioSpostaIn
import eu.stgm.sol.ui.PulsanteImpostazioni
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.RigaNonProtetto
import eu.stgm.sol.ui.Rotella
import eu.stgm.sol.ui.SegnaleMail
import eu.stgm.sol.ui.StrisciaProva
import eu.stgm.sol.ui.Vuoto
import eu.stgm.sol.ui.iniziali
import eu.stgm.sol.ui.parole
import eu.stgm.sol.ui.quandoBreve
import eu.stgm.sol.ui.segnaliPer
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalSegni
import eu.stgm.sol.ui.tema.LocalTemaScuro
import eu.stgm.sol.ui.tema.morbido
import eu.stgm.sol.ui.tema.movimentoRiga
import kotlinx.coroutines.withTimeoutOrNull

/** Quanto resta in vista "Spostata in Clienti · Annulla" (docs/aspetto.md §4). */
const val DURATA_ANNULLA_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostaScreen(
    vm: PostaViewModel,
    onApriMail: (Long) -> Unit,
    collegamentoNonProtetto: Boolean = false,
    onApriScadenze: () -> Unit = {},
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    // La mail col foglio "Sposta in" aperto, e quella appena spostata: le loro righe
    // restano scivolate via finché non si sa se escono dalla vista.
    var spostaDi by rememberSaveable { mutableStateOf<Long?>(null) }
    var inUscita by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { vm.carica() }

    LaunchedEffect(stato.spostamento, stato.erroreAzione) {
        if (stato.spostamento != null || stato.erroreAzione != null) inUscita = null
    }
    LaunchedEffect(stato.spostamento) {
        val s = stato.spostamento ?: return@LaunchedEffect
        try {
            val esito = withTimeoutOrNull(DURATA_ANNULLA_MS) {
                messaggi.showSnackbar(
                    s.frase,
                    actionLabel = if (s.annullabile) "Annulla" else null,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (esito == SnackbarResult.ActionPerformed) vm.annullaSpostamento()
        } finally {
            vm.consumaSpostamento()
        }
    }

    Column(Modifier.fillMaxSize()) {
        FasciaCalda("Posta") {
            IconButton(onClick = vm::carica) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna") }
            PulsanteImpostazioni()
        }
        if (stato.modalitaProva) StrisciaProva()
        if (collegamentoNonProtetto) RigaNonProtetto()
        BarraCaricamento(stato.caricamento)
        SchedeGruppi(stato.gruppi, stato.gruppoId, daLeggere = stato::daLeggere, onScegli = vm::scegliGruppo)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = vm::carica,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (stato.piastrelle.isNotEmpty()) {
                    item(key = "da-fare") {
                        DaFare(
                            stato.piastrelle,
                            filtro = stato.filtro,
                            onTocca = { voce -> voce.filtro?.let(vm::scegliFiltro) ?: onApriScadenze() },
                            modifier = movimentoRiga(),
                        )
                    }
                }
                if (stato.filtro != FiltroRapido.TUTTE) {
                    item(key = "filtro") { RigaFiltro(stato.filtro, onTogli = { vm.scegliFiltro(stato.filtro) }) }
                }
                item(key = "avviso") {
                    AvvisoLettura(
                        stato.errore,
                        stato.daCacheDel,
                        onRiprova = vm::carica,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (stato.mail.isEmpty()) {
                    item(key = "vuoto") {
                        when {
                            !stato.caricataAlmenoUnaVolta || stato.caricamento -> Rotella()
                            stato.errore == null -> Vuoto(testoVuoto(stato.filtro), Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
                items(stato.mail, key = { it.id }) { mail ->
                    RigaConGesti(
                        mail = mail,
                        colore = ColoreGruppo.daNome(stato.gruppo(mail.gruppoId)?.colore),
                        conGruppo = stato.gruppoId == null,
                        tenutaFuori = spostaDi == mail.id || inUscita == mail.id,
                        gestiAttivi = stato.inSpostamento == null && spostaDi == null,
                        onClick = { onApriMail(mail.id) },
                        onLetta = { vm.cambiaLetta(mail) },
                        onSposta = { spostaDi = mail.id },
                        modifier = movimentoRiga(),
                    )
                }
                if (stato.altre && stato.mail.isNotEmpty()) {
                    item(key = "altre") {
                        PulsanteVuoto(
                            if (stato.caricamentoAltre) "Carico..." else "Mostra mail più vecchie",
                            onClick = vm::caricaAltre,
                            abilitato = !stato.caricamentoAltre,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    val daSpostare = stato.mail.firstOrNull { it.id == spostaDi }
    if (daSpostare != null) {
        FoglioSpostaIn(
            gruppi = stato.gruppi,
            attuale = daSpostare.gruppoId,
            onScegli = { gruppoId, impara ->
                if (vm.sposta(daSpostare, gruppoId, impara)) inUscita = daSpostare.id
                spostaDi = null
            },
            onChiudi = { spostaDi = null },
        )
    } else if (spostaDi != null) {
        // La mail non c'è più (elenco ricaricato): niente foglio per una riga che non esiste.
        LaunchedEffect(spostaDi) { spostaDi = null }
    }
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
}

private fun testoVuoto(filtro: FiltroRapido) = when (filtro) {
    FiltroRapido.TUTTE -> "Nessuna mail qui."
    FiltroRapido.DA_RISPONDERE -> "Nessuna mail aspetta una risposta."
    FiltroRapido.URGENTI -> "Nessuna mail urgente."
    FiltroRapido.DA_CONTROLLARE -> "Niente da controllare: Sol è sicuro di tutti gli smistamenti."
    FiltroRapido.SOSPETTE -> "Nessuna mail da chiarire: niente possibili truffe."
}

/** UNA riga di schede: "Tutte" e i gruppi, ognuno col suo colore e le mail da leggere. */
@Composable
private fun SchedeGruppi(gruppi: List<Gruppo>, scelto: Long?, daLeggere: (Gruppo) -> Int, onScegli: (Long?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "tutte") { SchedaGruppo("Tutte", numero = 0, colore = null, scelta = scelto == null) { onScegli(null) } }
        items(gruppi, key = { it.id }) { g ->
            SchedaGruppo(g.nome, daLeggere(g), ColoreGruppo.daNome(g.colore), scelta = scelto == g.id) { onScegli(g.id) }
        }
    }
}

/**
 * Una scheda: scelta = piena del colore del gruppo (l'accento per "Tutte") con la
 * spunta, così si distingue anche al sole e non solo per la tinta.
 */
@Composable
private fun SchedaGruppo(nome: String, numero: Int, colore: ColoreGruppo?, scelta: Boolean, onClick: () -> Unit) {
    val segni = LocalSegni.current
    val pieno = colore?.base ?: segni.attivo
    val suPieno = if (colore == null) segni.suAttivo else Color.White
    val fondo by animateColorAsState(if (scelta) pieno else MaterialTheme.colorScheme.surfaceContainerLow, morbido(), label = "fondo scheda")
    val testo by animateColorAsState(if (scelta) suPieno else MaterialTheme.colorScheme.onSurface, morbido(), label = "testo scheda")
    val descrizione = if (numero > 0) "$nome, $numero da leggere" else nome
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = fondo,
        contentColor = testo,
        border = if (scelta) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = descrizione },
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                scelta -> Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                colore != null -> Box(Modifier.padding(end = 8.dp).size(10.dp).background(colore.punto, CircleShape))
            }
            Text(nome, style = MaterialTheme.typography.labelLarge, fontWeight = if (scelta) FontWeight.Bold else FontWeight.SemiBold)
            if (numero > 0) {
                Text(
                    "$numero",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (scelta) testo else colore?.testo ?: testo,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * Il riquadro "Da fare": UNA riga di piastrelle da toccare (= filtro; le scadenze
 * aprono la loro scheda). Ognuna è larga un quarto della riga anche quando sono meno
 * di quattro: non si allargano.
 */
@Composable
private fun DaFare(piastrelle: List<Piastrella>, filtro: FiltroRapido, onTocca: (VoceDaFare) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Text(
            "Da fare",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp).semantics { heading() },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val spazio = 6.dp
            val larghezza = (maxWidth - spazio * 3) / 4
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spazio)) {
                piastrelle.forEach { p ->
                    PiastrellaDaFare(
                        p,
                        scelta = p.voce.filtro != null && p.voce.filtro == filtro,
                        onClick = { onTocca(p.voce) },
                        larghezza = larghezza,
                        modifier = Modifier.width(larghezza).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PiastrellaDaFare(p: Piastrella, scelta: Boolean, onClick: () -> Unit, larghezza: Dp, modifier: Modifier) {
    val segni = LocalSegni.current
    val (icona, colore) = when (p.voce) {
        VoceDaFare.DA_RISPONDERE -> Icons.Filled.Edit to segni.accentoTesto
        VoceDaFare.TRUFFE -> Icons.Filled.Warning to segni.urgente
        VoceDaFare.DA_CONTROLLARE -> null to segni.controllare
        VoceDaFare.SCADENZE -> Icons.Filled.DateRange to MaterialTheme.colorScheme.onSurface
    }
    val tinta = if (scelta) segni.suAttivo else colore
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        // Una superficie col bordo (nel tema chiaro): si vede che è un pulsante anche sul fondo caldo.
        color = if (scelta) segni.attivo else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (scelta) segni.suAttivo else MaterialTheme.colorScheme.onSurface,
        border = if (scelta || LocalTemaScuro.current) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${p.numero} ${p.etichetta}" +
                    if (p.voce == VoceDaFare.SCADENZE) ". Apre le scadenze." else if (scelta) ". Filtro scelto: tocca per toglierlo." else ""
            },
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icona != null) {
                    Icon(icona, contentDescription = null, tint = tinta, modifier = Modifier.size(18.dp))
                } else {
                    Text("?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = tinta)
                }
                Spacer(Modifier.width(5.dp))
                Text("${p.numero}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tinta)
            }
            Text(
                p.etichettaCorta,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                fontSize = dimensioneEtichetta(larghezza - 8.dp),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/**
 * 14 sp, ma mai più grande di quanto serve perché la parola più lunga ("controllare",
 * circa 5 volte l'altezza del carattere) stia intera in [spazio]: su un telefono
 * stretto o col testo di sistema ingrandito le parole non si spezzano.
 */
@Composable
private fun dimensioneEtichetta(spazio: Dp): TextUnit {
    val densita = LocalDensity.current
    return with(densita) {
        val massima = (spazio.value / 5f).dp
        if (14.sp.toDp() <= massima) 14.sp else massima.toSp()
    }
}

/** "Da rispondere · togli filtro", sopra l'elenco quando un filtro è attivo. */
@Composable
private fun RigaFiltro(filtro: FiltroRapido, onTogli: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(filtro.nome(), style = MaterialTheme.typography.titleSmall)
        Text(" · ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = onTogli,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("togli filtro", style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * Una riga coi gesti (docs/aspetto.md §4): a destra letta ↔ da leggere, a sinistra
 * il foglio "Sposta in". Niente gesti che scartano o inviano. Per chi usa il
 * lettore dello schermo gli stessi gesti sono azioni della riga.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RigaConGesti(
    mail: Mail,
    colore: ColoreGruppo,
    conGruppo: Boolean,
    tenutaFuori: Boolean,
    gestiAttivi: Boolean,
    onClick: () -> Unit,
    onLetta: () -> Unit,
    onSposta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segni = LocalSegni.current
    val apriFoglio by rememberUpdatedState(onSposta)
    val scorrimento = rememberSwipeToDismissBoxState(
        confirmValueChange = { valore ->
            if (valore == SwipeToDismissBoxValue.EndToStart) apriFoglio()
            true
        },
        positionalThreshold = { larghezza -> larghezza * 0.3f },
    )
    // A destra la riga esce, torna e solo allora cambia (letta ↔ da leggere): un gesto,
    // una sola azione, e sotto la riga resta scritto quello che si sta facendo.
    LaunchedEffect(scorrimento.currentValue) {
        if (scorrimento.currentValue == SwipeToDismissBoxValue.StartToEnd) {
            scorrimento.reset()
            onLetta()
        }
    }
    // A sinistra resta fuori col foglio aperto; torna se la mail resta in questa vista.
    LaunchedEffect(scorrimento.currentValue, tenutaFuori) {
        if (scorrimento.currentValue == SwipeToDismissBoxValue.EndToStart && !tenutaFuori) scorrimento.reset()
    }
    val azioneLetta = if (mail.letta) "Segna da leggere" else "Segna come letta"
    SwipeToDismissBox(
        state = scorrimento,
        gesturesEnabled = gestiAttivi,
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(azioneLetta) { onLetta(); true },
                CustomAccessibilityAction("Sposta in...") { onSposta(); true },
            )
        },
        backgroundContent = {
            when (scorrimento.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> FondoGesto(
                    testo = if (mail.letta) "Da leggere" else "Letta",
                    icona = if (mail.letta) Icons.Filled.Email else Icons.Filled.Done,
                    fondo = segni.accentoTenue,
                    colore = segni.accentoTesto,
                    allaFine = false,
                )
                SwipeToDismissBoxValue.EndToStart -> FondoGesto(
                    testo = "Sposta in...",
                    icona = Icons.AutoMirrored.Filled.ArrowForward,
                    fondo = MaterialTheme.colorScheme.secondaryContainer,
                    colore = MaterialTheme.colorScheme.onSecondaryContainer,
                    allaFine = true,
                )
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            RigaMail(mail, onClick = onClick, colore = colore, conGruppo = conGruppo)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun FondoGesto(testo: String, icona: androidx.compose.ui.graphics.vector.ImageVector, fondo: Color, colore: Color, allaFine: Boolean) {
    // L'icona sta sul bordo da cui la riga si scosta, la parola subito dentro.
    Row(
        Modifier.fillMaxSize().background(fondo).padding(horizontal = 24.dp),
        horizontalArrangement = if (allaFine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allaFine) Text(testo, style = MaterialTheme.typography.labelLarge, color = colore)
        if (allaFine) Spacer(Modifier.width(10.dp))
        Icon(icona, contentDescription = null, tint = colore, modifier = Modifier.size(24.dp))
        if (!allaFine) Spacer(Modifier.width(10.dp))
        if (!allaFine) Text(testo, style = MaterialTheme.typography.labelLarge, color = colore)
    }
}

/**
 * Una mail in elenco (docs/aspetto.md §2): cerchio col colore del gruppo e le
 * iniziali, mittente e oggetto in grassetto se non letta, una riga di riassunto,
 * al massimo due segnali. [conGruppo]: anche l'etichetta del gruppo (in "Tutte").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RigaMail(
    mail: Mail,
    onClick: () -> Unit,
    colore: ColoreGruppo,
    conGruppo: Boolean,
    nota: String? = null,
) {
    val segni = LocalSegni.current
    val segnali = segnaliPer(mail)
    val quando = quandoBreve(mail.ricevutaIl)
    val riga = mail.riassunto?.takeIf { it.isNotBlank() } ?: mail.anteprima
    // Il lettore dello schermo legge prima i segnali importanti.
    val descrizione = buildString {
        segnaliPer(mail, massimo = Int.MAX_VALUE).forEach { append(it.parole()).append(". ") }
        if (!mail.letta) append("Non letta. ")
        append("Da ${mail.mittente.etichetta}. ${mail.oggetto.ifBlank { "Senza oggetto" }}.")
        if (riga.isNotBlank()) append(" $riga")
        if (conGruppo && mail.gruppo != null) append(" Gruppo ${mail.gruppo}.")
        if (quando.isNotBlank()) append(" $quando.")
        if (nota != null) append(" $nota.")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = descrizione }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CerchioIniziali(iniziali(mail.mittente.nome, mail.mittente.indirizzo), colore.base, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mail.mittente.etichetta,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (mail.letta) FontWeight.Medium else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!mail.letta) {
                    Box(Modifier.padding(start = 8.dp).size(9.dp).background(segni.accentoTesto, CircleShape))
                }
                Text(
                    quando,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (mail.letta) FontWeight.Normal else FontWeight.Bold,
                    color = if (mail.letta) MaterialTheme.colorScheme.onSurfaceVariant else segni.accentoTesto,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                mail.oggetto.ifBlank { "(senza oggetto)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (mail.letta) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (riga.isNotBlank()) {
                Text(
                    riga,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val etichetta = mail.gruppo?.takeIf { conGruppo }
            if (segnali.isNotEmpty() || etichetta != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    segnali.forEach { SegnaleMail(it, Modifier.align(Alignment.CenterVertically)) }
                    if (etichetta != null) EtichettaGruppo(etichetta, colore, Modifier.align(Alignment.CenterVertically))
                }
            }
            if (nota != null) {
                Text(
                    nota,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
