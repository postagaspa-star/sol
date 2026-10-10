package eu.stgm.sol.ui.indietro

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.AspettiTu
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FasciaCalda
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.PulsanteImpostazioni
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.Rotella
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.Vuoto
import eu.stgm.sol.ui.giorni
import eu.stgm.sol.ui.posta.RigaMail
import eu.stgm.sol.ui.tema.ColoreGruppo

@Composable
fun IndietroScreen(vm: IndietroViewModel, onApriMail: (Long) -> Unit, onApriBozza: (id: Long, appena: Bozza?) -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    LaunchedEffect(Unit) { vm.carica() }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    LaunchedEffect(stato.sollecitoDaAprire) {
        val b = stato.sollecitoDaAprire ?: return@LaunchedEffect
        vm.sollecitoAperto()
        onApriBozza(b.id, b)
    }

    Column(Modifier.fillMaxSize()) {
        FasciaCalda("Niente resta indietro") {
            IconButton(onClick = vm::carica) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna") }
            PulsanteImpostazioni()
        }
        BarraCaricamento(stato.caricamento || stato.inCorso)
        val dati = stato.dati
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (dati == null) {
                if (stato.caricamento) item { Rotella() }
                return@LazyColumn
            }
            item { TitoloSezione("Aspettano una tua risposta", Modifier.padding(horizontal = 16.dp)) }
            if (dati.aspettanoTe.isEmpty()) {
                item { Vuoto("Nessuna mail aspetta da troppo tempo.", Modifier.padding(horizontal = 8.dp)) }
            }
            items(dati.aspettanoTe, key = { "te-" + it.mail.id }) { voce ->
                RigaMail(
                    voce.mail,
                    onClick = { onApriMail(voce.mail.id) },
                    colore = ColoreGruppo.daNome(stato.gruppi.firstOrNull { it.id == voce.mail.gruppoId }?.colore),
                    conGruppo = true,
                    nota = "Aspetta da ${giorni(voce.giorni)}",
                )
                FilaPulsanti(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 8.dp)) {
                    PulsanteVuoto("Rispondi", onClick = { onApriMail(voce.mail.id) })
                    PulsanteTesto("Non serve", onClick = { vm.nonServeRisposta(voce.mail.id) }, abilitato = !stato.inCorso)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item { TitoloSezione("Aspetti tu una risposta", Modifier.padding(horizontal = 16.dp)) }
            if (dati.aspettiTu.isEmpty()) {
                item { Vuoto("Nessuno ti deve una risposta da troppo tempo.", Modifier.padding(horizontal = 8.dp)) }
            }
            items(dati.aspettiTu, key = { "tu-" + it.inviata.id }) { voce ->
                RigaAttesa(
                    voce,
                    inCorso = stato.inCorso,
                    onSollecito = { vm.preparaSollecito(voce.inviata.id) },
                    onApriSollecito = { id -> onApriBozza(id, null) },
                    onIgnora = { vm.ignora(voce.inviata.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
}

@Composable
private fun RigaAttesa(
    voce: AspettiTu,
    inCorso: Boolean,
    onSollecito: () -> Unit,
    onApriSollecito: (Long) -> Unit,
    onIgnora: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "A: ${voce.inviata.a.joinToString(", ")}",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(voce.inviata.oggetto.ifBlank { "(senza oggetto)" }, style = MaterialTheme.typography.bodyLarge)
        if (voce.inviata.anteprima.isNotBlank()) {
            Text(
                voce.inviata.anteprima,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Nessuna risposta da ${giorni(voce.giorni)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        FilaPulsanti(Modifier.padding(top = 8.dp)) {
            val sollecito = voce.bozzaSollecito
            if (sollecito != null && sollecito.stato != Bozza.SCARTATA && sollecito.stato != Bozza.INVIATA) {
                PulsantePieno("Apri il sollecito", onClick = { onApriSollecito(sollecito.id) })
            } else {
                PulsantePieno("Prepara sollecito", onClick = onSollecito, abilitato = !inCorso)
            }
            PulsanteTesto("Ignora", onClick = onIgnora, abilitato = !inCorso)
        }
    }
}
