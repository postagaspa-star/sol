package eu.stgm.sol.ui.smista

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.BarraAvanzamento
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.ErroreConRiprova
import eu.stgm.sol.ui.EtichettaMaiuscola
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteGrandeBianco
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.iniziali
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.quandoBreve
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalMovimenti
import eu.stgm.sol.ui.tema.Sol
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Smista (docs/prototipo/Smista.dc.html): le mail incerte una alla volta, a schede.
 * "Va bene" o scorri a destra; "Cambia" o scorri a sinistra per scegliere il gruppo.
 */
@Composable
fun SmistaScreen(vm: SmistaViewModel, onChiudi: () -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.carica() }
    val c = Sol.colori
    val t = Sol.testi

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(start = MARGINE, end = MARGINE, top = 10.dp, bottom = 120.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                PulsanteTondo(Icone.Chiudi, "Chiudi e torna a Oggi", onChiudi, Modifier.align(Alignment.CenterStart), dimensioneIcona = 18.dp)
                Text("Smista", style = t.forteGrande, color = c.testo, modifier = Modifier.align(Alignment.Center).semantics { heading() })
                val contatore = when {
                    stato.caricamento || stato.errore != null -> ""
                    stato.finito -> "Fatto"
                    else -> "${stato.indice + 1} di ${stato.totale}"
                }
                Text(contatore, style = t.secondario, fontWeight = FontWeight.Bold, color = c.tenue, modifier = Modifier.align(Alignment.CenterEnd))
            }
            BarraAvanzamento(if (stato.caricamento) 0f else stato.frazione, Modifier.padding(top = 10.dp))

            val errore = stato.errore
            val attuale = stato.attuale
            when {
                errore != null -> ErroreConRiprova(errore, vm::carica, Modifier.padding(top = 22.dp))
                stato.finito -> Fine(stato.totale, stato.spostate, onChiudi)
                attuale != null -> {
                    PilaSchede(attuale, stato, vm)
                    if (stato.sceltaGruppo) {
                        GrigliaGruppi(stato.gruppi, attuale, vm::scegliGruppo, Modifier.padding(top = 18.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PulsanteTesto("Annulla", vm::annullaCambio, colore = c.tenue)
                        }
                    }
                }
            }
        }
        if (stato.attuale != null && !stato.sceltaGruppo && stato.errore == null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulsanteGrandeBianco("Cambia", vm::cambia, Modifier.weight(1f), icona = Icone.Indietro, abilitato = !stato.inCorso, altezza = 60.dp)
                PulsanteGrande("Va bene", vm::vaBene, Modifier.weight(1f), icona = Icone.Spunta, abilitato = !stato.inCorso, altezza = 60.dp)
            }
        }
    }
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
}

/** La pila: due schede dietro e quella in vista, che si trascina a destra o a sinistra. */
@Composable
private fun PilaSchede(mail: Mail, stato: StatoSmista, vm: SmistaViewModel) {
    val c = Sol.colori
    val scorrimento = remember(mail.id) { Animatable(0f) }
    val ambito = rememberCoroutineScope()
    val muove = LocalMovimenti.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 22.dp).height(400.dp)) {
        val larghezza = with(LocalDensity.current) { maxWidth.toPx() }
        val soglia = larghezza * 0.28f
        Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp).fillMaxWidth().height(360.dp).background(c.pila1, RoundedCornerShape(28.dp)))
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().height(372.dp).ombra(RoundedCornerShape(28.dp)).background(c.pila2, RoundedCornerShape(28.dp)))
        SchedaMail(
            mail,
            ColoreGruppo.daNome(stato.gruppi.firstOrNull { it.id == mail.gruppoId }?.colore),
            Modifier
                .offset { IntOffset(scorrimento.value.roundToInt(), 0) }
                .rotate(scorrimento.value / larghezza * 8f)
                .draggable(
                    enabled = !stato.inCorso && !stato.sceltaGruppo,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> ambito.launch { scorrimento.snapTo(scorrimento.value + delta) } },
                    onDragStopped = {
                        val x = scorrimento.value
                        when {
                            x > soglia -> {
                                // A destra = va bene: la scheda esce di lato.
                                if (muove) scorrimento.animateTo(larghezza * 1.3f, tween(200, easing = FastOutSlowInEasing))
                                vm.vaBene()
                                scorrimento.snapTo(0f)
                            }
                            x < -soglia -> {
                                scorrimento.animateTo(0f, tween(if (muove) 200 else 0))
                                vm.cambia()
                            }
                            else -> scorrimento.animateTo(0f, tween(if (muove) 200 else 0))
                        }
                    },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Va bene") { vm.vaBene(); true },
                        CustomAccessibilityAction("Cambia gruppo") { vm.cambia(); true },
                    )
                },
        )
    }
}

/** La scheda in vista: mittente, oggetto, riassunto e in fondo "SOL PROPONE". */
@Composable
internal fun SchedaMail(mail: Mail, colore: ColoreGruppo, modifier: Modifier = Modifier) {
    val c = Sol.colori
    val t = Sol.testi
    val forma = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().height(384.dp).ombra(forma, rilievo = true).background(c.superficie, forma).padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CerchioIniziali(iniziali(mail.mittente.nome, mail.mittente.indirizzo), null, dimensione = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(mail.mittente.etichetta, style = t.forte, fontWeight = FontWeight.ExtraBold, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mail.mittente.indirizzo, style = t.piccolo, color = c.moltoTenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(quandoBreve(mail.ricevutaIl), style = t.piccolo, color = c.moltoTenue)
        }
        Text(mail.oggetto.ifBlank { "(senza oggetto)" }, style = t.oggettoScheda, color = c.testo, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
        val riga = mail.riassunto?.takeIf { it.isNotBlank() } ?: mail.anteprima
        Text(riga, style = t.testo, color = c.testoCorpo, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        EtichettaMaiuscola("Sol propone", Modifier.padding(top = 14.dp), colore = c.moltoTenue)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Il nome del gruppo cede spazio: "abbastanza sicuro" resta su una riga.
            Row(
                Modifier.weight(1f, fill = false).height(40.dp).background(colore.fondo, RoundedCornerShape(999.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(colore.punto, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(mail.gruppo ?: "Nessun gruppo", style = t.forte, fontWeight = FontWeight.ExtraBold, color = colore.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(testoSicurezza(mail.sicurezza), style = t.secondario, color = c.tenue, maxLines = 1, softWrap = false)
        }
    }
}

/** "In quale gruppo va?": i gruppi in due colonne, ognuno col suo colore. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GrigliaGruppi(gruppi: List<Gruppo>, mail: Mail, onScegli: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("In quale gruppo va?", style = Sol.testi.secondario, fontWeight = FontWeight.Bold, color = Sol.colori.testoMedio)
        FlowRow(
            Modifier.padding(top = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            gruppi.forEach { g ->
                val colore = ColoreGruppo.daNome(g.colore)
                Row(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colore.fondo)
                        .clickable(role = Role.Button) { onScegli(g.id) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(colore.punto, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        g.nome,
                        style = Sol.testi.medio,
                        fontWeight = if (g.id == mail.gruppoId) FontWeight.ExtraBold else FontWeight.Bold,
                        color = colore.testo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Due colonne vere: l'ultimo gruppo da solo resta largo metà.
            if (gruppi.size % 2 == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Finito: la spunta, "Tutto sistemato" e "Torna a Oggi". */
@Composable
private fun Fine(totale: Int, spostate: Int, onChiudi: () -> Unit) {
    val c = Sol.colori
    Column(
        Modifier.fillMaxWidth().padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(88.dp).background(c.accentoTenueForte, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icone.Spunta, contentDescription = null, tint = c.accento, modifier = Modifier.size(40.dp))
        }
        Text(
            if (totale == 0) "Niente da sistemare" else "Tutto sistemato",
            style = Sol.testi.titoloPagina.copy(fontSize = Sol.testi.oggetto.fontSize * 1.04f),
            color = c.testo,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (spostate > 0) {
            Text(
                "Sol si ricorderà queste scelte per le prossime mail degli stessi mittenti.",
                style = Sol.testi.testo,
                color = c.testoCorpo,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 290.dp),
            )
        }
        PulsanteGrande("Torna a Oggi", onChiudi, Modifier.padding(top = 14.dp), altezza = 52.dp)
    }
}
