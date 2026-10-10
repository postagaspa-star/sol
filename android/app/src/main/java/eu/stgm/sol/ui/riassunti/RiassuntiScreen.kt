package eu.stgm.sol.ui.riassunti

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Riassunto
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.FasciaCalda
import eu.stgm.sol.ui.PulsanteImpostazioni
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.Superficie
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.dettaglio.AIUTO_DETTATURA
import eu.stgm.sol.ui.quandoCompleto
import eu.stgm.sol.ui.testoConRiferimenti

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiassuntiScreen(vm: RiassuntiViewModel, onApriMail: (Long) -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.carica() }
    // Il riassunto si ricontrolla solo con la schermata aperta.
    DisposableEffect(Unit) {
        vm.riprendi()
        onDispose { vm.sospendi() }
    }

    Column(Modifier.fillMaxSize()) {
        FasciaCalda("Riassunti") {
            IconButton(onClick = vm::carica) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna") }
            PulsanteImpostazioni()
        }
        BarraCaricamento(stato.caricamento)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica)

            TitoloSezione("Oggi")
            val oggi = stato.oggi
            if (oggi == null) {
                Text(
                    if (stato.caricamento) "Carico il riassunto di oggi..." else "Il riassunto di oggi non è ancora pronto.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Riquadrato {
                    TestoRiassunto(oggi.testoVisibile.ifBlank { "Il riassunto di oggi non è ancora pronto." }, onApriMail)
                    oggi.aggiornatoIl?.let {
                        Text(
                            "Aggiornato ${quandoCompleto(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            TitoloSezione("Chiedi un riassunto")
            OutlinedTextField(
                value = stato.richiesta,
                onValueChange = vm::cambiaRichiesta,
                placeholder = { Text("Esempio: cosa ha scritto Bianchi questa settimana") },
                supportingText = { Text(AIUTO_DETTATURA) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.chiedi() }),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            PulsantePieno(
                if (stato.inCorso) "Sol sta scrivendo..." else "Chiedi",
                onClick = vm::chiedi,
                abilitato = !stato.inCorso,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            stato.erroreAzione?.let {
                Riquadro(
                    it,
                    grave = true,
                    azione = if (stato.sospeso) "Riprova" else "Chiudi",
                    onAzione = if (stato.sospeso) vm::riprendi else vm::consumaErroreAzione,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            stato.corrente?.let { corrente ->
                Riquadrato(Modifier.padding(top = 16.dp)) {
                    corrente.richiesta?.let {
                        Text("«$it»", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    if (stato.seguendo) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    val testo = corrente.testoVisibile
                    if (testo.isBlank()) {
                        Text(
                            when {
                                stato.seguendo -> "Sol sta cercando le mail giuste..."
                                corrente.inCorso -> "Sol ci sta ancora lavorando."
                                else -> "Nessun testo."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        TestoRiassunto(testo, onApriMail)
                    }
                    if (!stato.seguendo) PulsanteTesto("Chiudi", vm::chiudiCorrente)
                }
            }

            val recenti = stato.recenti.filter { it.id != stato.corrente?.id }
            if (recenti.isNotEmpty()) {
                TitoloSezione("Chiesti di recente")
                recenti.forEach { r ->
                    RigaRecente(r, onClick = { vm.apri(r) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TestoRiassunto(testo: String, onApriMail: (Long) -> Unit) {
    val colore = MaterialTheme.colorScheme.primary
    Text(testoConRiferimenti(testo, colore, onApriMail), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Riquadrato(modifier: Modifier = Modifier, contenuto: @Composable () -> Unit) {
    Superficie(modifier) { Column(Modifier.padding(16.dp)) { contenuto() } }
}

@Composable
private fun RigaRecente(r: Riassunto, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(r.richiesta ?: "Riassunto", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                quandoCompleto(r.creatoIl).ifBlank { null },
                when (r.stato) {
                    Riassunto.ERRORE -> "non riuscito"
                    Riassunto.IN_CODA, Riassunto.IN_CORSO -> "in preparazione"
                    else -> null
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
