package eu.stgm.sol.ui.posta

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FoglioSpostaIn
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.PillolaSegno
import eu.stgm.sol.ui.Segno
import eu.stgm.sol.ui.iniziali
import eu.stgm.sol.ui.quandoBreve
import eu.stgm.sol.ui.segnoPer
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.Sol
import kotlinx.coroutines.withTimeoutOrNull

/** Quanto resta in vista "Spostata in Clienti · Annulla" (docs/aspetto.md §4). */
const val DURATA_ANNULLA_MS = 5_000L

/** Chi ha il foglio "Sposta in" aperto e quale riga è appena uscita. */
class GestiRighe(
    val spostaDi: Long?,
    val inUscita: Long?,
    val attivi: Boolean,
    val apriSposta: (Long) -> Unit,
)

/**
 * I gesti di un elenco di mail (docs/aspetto.md §4), uguali in Posta, nelle raccolte e
 * nella ricerca: il foglio "Sposta in", "Spostata in X · Annulla" per 5 secondi e la
 * finestra se un gesto non riesce.
 */
@Composable
fun gestiElenco(vm: PostaViewModel, stato: StatoPosta): GestiRighe {
    val messaggi = LocalMessaggi.current
    // La mail col foglio aperto, e quella appena spostata: le loro righe restano
    // scivolate via finché non si sa se escono dalla vista.
    var spostaDi by rememberSaveable { mutableStateOf<Long?>(null) }
    var inUscita by rememberSaveable { mutableStateOf<Long?>(null) }

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
    return GestiRighe(spostaDi, inUscita, stato.inSpostamento == null && spostaDi == null) { spostaDi = it }
}

/**
 * Una riga coi gesti: a destra letta ↔ da leggere, a sinistra il foglio "Sposta in".
 * Niente gesti che scartano o inviano. Per il lettore dello schermo gli stessi gesti
 * sono azioni della riga.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RigaConGesti(
    mail: Mail,
    colore: ColoreGruppo,
    gesti: GestiRighe,
    onClick: () -> Unit,
    onLetta: () -> Unit,
    modifier: Modifier = Modifier,
    conPuntoGruppo: Boolean = false,
) {
    val c = Sol.colori
    val tenutaFuori = gesti.spostaDi == mail.id || gesti.inUscita == mail.id
    val apriFoglio by rememberUpdatedState { gesti.apriSposta(mail.id) }
    val scorrimento = rememberSwipeToDismissBoxState(
        confirmValueChange = { valore ->
            if (valore == SwipeToDismissBoxValue.EndToStart) apriFoglio()
            true
        },
        positionalThreshold = { larghezza -> larghezza * 0.3f },
    )
    // A destra la riga esce, torna e solo allora cambia: un gesto, una sola azione.
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
        gesturesEnabled = gesti.attivi,
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(azioneLetta) { onLetta(); true },
                CustomAccessibilityAction("Sposta in...") { gesti.apriSposta(mail.id); true },
            )
        },
        backgroundContent = {
            when (scorrimento.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> FondoGesto(
                    if (mail.letta) "Da leggere" else "Letta",
                    if (mail.letta) Icone.Busta else Icone.BustaAperta,
                    c.accentoTenue, c.accentoTesto, allaFine = false,
                )
                SwipeToDismissBoxValue.EndToStart -> FondoGesto("Sposta in...", Icone.Cartella, c.superficie2, c.testoMedio, allaFine = true)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        Box(Modifier.background(c.superficie)) {
            RigaMail(mail, onClick = onClick, colore = colore, conPuntoGruppo = conPuntoGruppo)
        }
    }
}

@Composable
private fun FondoGesto(testo: String, icona: ImageVector, fondo: Color, colore: Color, allaFine: Boolean) {
    Row(
        Modifier.fillMaxSize().background(fondo).padding(horizontal = 24.dp),
        horizontalArrangement = if (allaFine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allaFine) Text(testo, style = Sol.testi.pulsante, color = colore)
        if (allaFine) Spacer(Modifier.width(10.dp))
        Icon(icona, contentDescription = null, tint = colore, modifier = Modifier.size(22.dp))
        if (!allaFine) Spacer(Modifier.width(10.dp))
        if (!allaFine) Text(testo, style = Sol.testi.pulsante, color = colore)
    }
}

/**
 * Una mail in elenco (docs/aspetto.md §4): cerchio con le iniziali, mittente (800 se
 * non letta), ora (accento se non letta, rossa se urgente), oggetto, una riga di
 * riassunto e al massimo un segno. [conPuntoGruppo]: in "Tutte" il pallino del gruppo
 * accanto all'ora. [nota]: in "Aspetti risposta", i giorni passati.
 */
@Composable
fun RigaMail(mail: Mail, onClick: () -> Unit, colore: ColoreGruppo, conPuntoGruppo: Boolean = false, nota: String? = null) {
    val c = Sol.colori
    val t = Sol.testi
    val segno = segnoPer(mail)
    val quando = quandoBreve(mail.ricevutaIl)
    val riga = mail.riassunto?.takeIf { it.isNotBlank() } ?: mail.anteprima
    val nonLetta = !mail.letta
    // Il lettore dello schermo legge prima i segnali importanti.
    val descrizione = buildString {
        if (segno == Segno.TRUFFA) append("Possibile truffa. ")
        if (mail.urgente) append("Urgente. ")
        if (nonLetta) append("Non letta. ")
        append("Da ${mail.mittente.etichetta}. ${mail.oggetto.ifBlank { "Senza oggetto" }}.")
        if (riga.isNotBlank()) append(" $riga")
        if (segno == Segno.RISPOSTA_PRONTA) append(" Risposta pronta.")
        if (conPuntoGruppo && mail.gruppo != null) append(" Gruppo ${mail.gruppo}.")
        if (quando.isNotBlank()) append(" $quando.")
        if (nota != null) append(" $nota.")
    }
    val coloreOra = when {
        mail.urgente -> c.truffa
        nonLetta -> c.accentoTesto
        else -> c.moltoTenue
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = descrizione }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CerchioIniziali(iniziali(mail.mittente.nome, mail.mittente.indirizzo), colore)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mail.mittente.etichetta,
                    style = t.forte,
                    fontWeight = if (nonLetta) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (nonLetta) c.testo else c.testoMedio,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conPuntoGruppo) {
                    Box(Modifier.padding(start = 8.dp).size(7.dp).background(colore.punto, CircleShape))
                } else if (nonLetta && mail.urgente) {
                    Box(Modifier.padding(start = 8.dp).size(7.dp).background(c.truffa, CircleShape))
                }
                Text(
                    quando,
                    style = t.piccolo,
                    fontWeight = if (nonLetta || mail.urgente) FontWeight.Bold else FontWeight.Normal,
                    color = coloreOra,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                mail.oggetto.ifBlank { "(senza oggetto)" },
                style = t.medio,
                fontWeight = if (nonLetta) FontWeight.SemiBold else FontWeight.Medium,
                color = if (nonLetta) c.testo else c.testoMedio,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (riga.isNotBlank() || segno != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        riga,
                        style = t.secondario,
                        color = if (nonLetta) c.tenue else c.moltoTenue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (segno != null) {
                        Spacer(Modifier.width(6.dp))
                        PillolaSegno(segno)
                    }
                }
            }
            if (nota != null) {
                Text(nota, style = t.secondario, fontWeight = FontWeight.SemiBold, color = c.tenue, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
