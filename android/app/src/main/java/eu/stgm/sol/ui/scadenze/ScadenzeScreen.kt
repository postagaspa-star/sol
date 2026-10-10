package eu.stgm.sol.ui.scadenze

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FasciaCalda
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.PulsanteImpostazioni
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.Rotella
import eu.stgm.sol.ui.Vuoto
import eu.stgm.sol.ui.dettaglio.AIUTO_DETTATURA
import eu.stgm.sol.ui.giornoScadenza
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScadenzeScreen(vm: ScadenzeViewModel, onApriMail: (Long) -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var aggiungi by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.carica() }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    LaunchedEffect(stato.aggiunta) {
        if (stato.aggiunta) {
            aggiungi = false
            vm.aggiuntaVista()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FasciaCalda("Scadenze") {
                IconButton(onClick = vm::carica) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna") }
                PulsanteImpostazioni()
            }
            BarraCaricamento(stato.caricamento || stato.inCorso)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica, Modifier.padding(16.dp, 8.dp))
                }
                if (stato.scadenze.isEmpty()) {
                    item {
                        if (stato.caricamento) Rotella() else Vuoto("Nessuna scadenza aperta.", Modifier.padding(horizontal = 8.dp))
                    }
                }
                items(stato.scadenze, key = { it.id }) { s ->
                    RigaScadenza(
                        s,
                        abilitato = !stato.inCorso,
                        onFatta = { vm.segnaFatta(s.id) },
                        onScarta = { vm.scarta(s.id) },
                        onApriMail = s.mailId?.let { id -> { onApriMail(id) } },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { aggiungi = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Aggiungi", style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (aggiungi) {
        DialogoNuovaScadenza(
            errore = stato.erroreAzione,
            inCorso = stato.inCorso,
            onAggiungi = vm::aggiungi,
            onAnnulla = {
                aggiungi = false
                vm.consumaErroreAzione()
            },
        )
    } else {
        DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
    }
}

@Composable
private fun RigaScadenza(s: Scadenza, abilitato: Boolean, onFatta: () -> Unit, onScarta: () -> Unit, onApriMail: (() -> Unit)?) {
    val oggi = LocalDate.now()
    val passata = runCatching { LocalDate.parse(s.quando).isBefore(oggi) }.getOrDefault(false)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            giornoScadenza(s.quando, oggi).replaceFirstChar { it.uppercase() } + (s.ora?.let { ", ore $it" } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            color = if (passata) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(s.descrizione, style = MaterialTheme.typography.bodyLarge)
        FilaPulsanti(Modifier.padding(top = 8.dp)) {
            PulsanteVuoto("Fatta", onFatta, abilitato = abilitato)
            if (onApriMail != null) PulsanteTesto("Apri la mail", onApriMail)
            PulsanteTesto("Togli", onScarta, abilitato = abilitato)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoNuovaScadenza(
    errore: String?,
    inCorso: Boolean,
    onAggiungi: (String, LocalDate?, String) -> Unit,
    onAnnulla: () -> Unit,
) {
    var descrizione by rememberSaveable { mutableStateOf("") }
    var giornoEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var ora by rememberSaveable { mutableStateOf("") }
    var scegliGiorno by rememberSaveable { mutableStateOf(false) }
    val giorno = giornoEpoch?.let { LocalDate.ofEpochDay(it) }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Nuova scadenza") },
        text = {
            Column {
                OutlinedTextField(
                    value = descrizione,
                    onValueChange = { descrizione = it },
                    label = { Text("Cosa scade") },
                    supportingText = { Text(AIUTO_DETTATURA) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                PulsanteVuoto(
                    giorno?.let { "Giorno: " + giornoScadenza(it.toString()) } ?: "Scegli il giorno",
                    onClick = { scegliGiorno = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = ora,
                    onValueChange = { ora = it },
                    label = { Text("Ora (se serve)") },
                    placeholder = { Text("9:30") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (errore != null) Riquadro(errore, grave = true, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { PulsantePieno("Aggiungi", { onAggiungi(descrizione, giorno, ora) }, abilitato = !inCorso) },
        dismissButton = { PulsanteTesto("Annulla", onAnnulla) },
    )

    if (scegliGiorno) {
        val oggiUtc = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val statoData = rememberDatePickerState(
            initialSelectedDateMillis = giorno?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: oggiUtc,
        )
        DatePickerDialog(
            onDismissRequest = { scegliGiorno = false },
            confirmButton = {
                PulsanteTesto("Scegli", onClick = {
                    statoData.selectedDateMillis?.let {
                        giornoEpoch = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    scegliGiorno = false
                })
            },
            dismissButton = { PulsanteTesto("Annulla", { scegliGiorno = false }) },
        ) { DatePicker(state = statoData) }
    }
}
