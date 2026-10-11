package eu.stgm.sol.ui.scadenze

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FoglioSol
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaAzione
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteGrandeBianco
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.RigaVuota
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.TestaSezioneElenco
import eu.stgm.sol.ui.tema.Sol
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Il titolo di un giorno: "Oggi", "Domani", "Venerdì 16", "Passate". */
fun titoloGiorno(quando: String, oggi: LocalDate = LocalDate.now()): String {
    val giorno = runCatching { LocalDate.parse(quando) }.getOrNull() ?: return quando
    return when {
        giorno.isBefore(oggi) -> "Passate"
        giorno == oggi -> "Oggi"
        giorno == oggi.plusDays(1) -> "Domani"
        giorno.year == oggi.year -> DateTimeFormatter.ofPattern("EEEE d", Locale.ITALIAN).format(giorno)
        else -> DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN).format(giorno)
    }.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
}

/** Scadenze (docs/aspetto.md §7): un elenco per giorno, ognuna con "Fatto"; "+" per aggiungerne. */
@Composable
fun ScadenzeScreen(vm: ScadenzeViewModel, onIndietro: () -> Unit, onApriMail: (Long) -> Unit) {
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
    val perGiorno = stato.scadenze.groupBy { titoloGiorno(it.quando) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = MARGINE, end = MARGINE, bottom = 28.dp)) {
        item(key = "testata") {
            Column(Modifier.statusBarsPadding().padding(top = 10.dp)) {
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
                    Spacer(Modifier.weight(1f))
                    PulsanteTondo(Icone.Piu, "Aggiungi una scadenza", { aggiungi = true })
                }
                Text("Scadenze", style = Sol.testi.titoloPagina, color = Sol.colori.testo, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            }
        }
        item(key = "avviso") { AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica, Modifier.padding(top = 14.dp)) }
        if (stato.scadenze.isEmpty() && !stato.caricamento && stato.errore == null) {
            item(key = "vuoto") { RigaVuota("Nessuna scadenza aperta.", Modifier.padding(top = 8.dp)) }
        }
        perGiorno.entries.forEachIndexed { i, (giorno, scadenze) ->
            item(key = "giorno-$giorno") {
                Column(Modifier.padding(top = if (i == 0) 20.dp else 18.dp)) {
                    TestaSezioneElenco(giorno)
                    Scheda(Modifier.padding(top = 8.dp)) {
                        scadenze.forEachIndexed { j, s ->
                            if (j > 0) RigaSottile(16.dp)
                            RigaScadenza(
                                s,
                                abilitato = !stato.inCorso,
                                onFatta = { vm.segnaFatta(s.id) },
                                onApriMail = s.mailId?.let { id -> { onApriMail(id) } },
                            )
                        }
                    }
                }
            }
        }
    }

    if (aggiungi) {
        FoglioNuovaScadenza(
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
private fun RigaScadenza(s: Scadenza, abilitato: Boolean, onFatta: () -> Unit, onApriMail: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onApriMail != null) Modifier.clickable(role = Role.Button, onClickLabel = "Apri la mail", onClick = onApriMail) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.descrizione, style = Sol.testi.forte, color = Sol.colori.testo)
            s.ora?.let { Text("ore $it", style = Sol.testi.secondario, color = Sol.colori.tenue) }
        }
        Spacer(Modifier.width(10.dp))
        PillolaAzione("Fatto", onFatta, abilitata = abilitato)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoglioNuovaScadenza(
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
    val bordi = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sol.colori.accento, unfocusedBorderColor = Sol.colori.traccia)

    FoglioSol(onChiudi = onAnnulla, titolo = "Nuova scadenza") {
        OutlinedTextField(
            value = descrizione,
            onValueChange = { descrizione = it },
            label = { Text("Cosa scade") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp),
            colors = bordi,
            modifier = Modifier.fillMaxWidth(),
        )
        PulsanteGrandeBianco(
            giorno?.let { titoloGiorno(it.toString()) } ?: "Scegli il giorno",
            onClick = { scegliGiorno = true },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            icona = Icone.Calendario,
        )
        OutlinedTextField(
            value = ora,
            onValueChange = { ora = it },
            label = { Text("Ora (se serve)") },
            placeholder = { Text("9:30") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = bordi,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        if (errore != null) Riquadro(errore, grave = true, modifier = Modifier.padding(top = 10.dp))
        PulsanteGrande("Aggiungi", { onAggiungi(descrizione, giorno, ora) }, Modifier.fillMaxWidth().padding(top = 16.dp), abilitato = !inCorso)
    }

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
