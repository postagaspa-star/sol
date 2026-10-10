package eu.stgm.sol.ui.abbinamento

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.ui.FasciaCalda
import eu.stgm.sol.ui.Riquadro

/** Il primo passo: collegare il telefono al server di casa con il codice di 8 cifre. */
@Composable
fun AbbinamentoScreen(
    vm: AbbinamentoViewModel,
    onFatto: () -> Unit,
    onAnnulla: (() -> Unit)?,
    /** Il server non riconosceva più il telefono: si dice perché si è di nuovo qui. */
    abbinamentoPerso: Boolean = false,
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(stato.fatto) { if (stato.fatto) onFatto() }

    Column(Modifier.fillMaxSize()) {
        FasciaCalda("Collega Sol", indietro = onAnnulla)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (abbinamentoPerso) Riquadro(Testi.NON_AUTORIZZATO, grave = true)
            Text(
                "Per usare Sol questo telefono va collegato al server di casa, una volta sola.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Sul server fai preparare un codice di abbinamento: vale 15 minuti e si usa una volta.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = stato.indirizzo,
                onValueChange = vm::cambiaIndirizzo,
                label = { Text("Indirizzo del server") },
                placeholder = { Text("https://sol.esempio.ts.net") },
                supportingText = {
                    Text(
                        "Per esempio https://sol.esempio.ts.net (quello di Tailscale, protetto). " +
                            "Solo per l'installazione, col Wi-Fi di casa: http://192.168.1.51:8200",
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = stato.codice,
                onValueChange = vm::cambiaCodice,
                label = { Text("Codice di 8 cifre") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = stato.nome,
                onValueChange = vm::cambiaNome,
                label = { Text("Nome di questo telefono") },
                supportingText = { Text("Serve a riconoscerlo tra i dispositivi abbinati.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.abbina() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            stato.errore?.let { Riquadro(it, grave = true) }
            PulsantePieno(
                if (stato.inCorso) "Collego..." else "Collega",
                onClick = vm::abbina,
                abilitato = !stato.inCorso,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
