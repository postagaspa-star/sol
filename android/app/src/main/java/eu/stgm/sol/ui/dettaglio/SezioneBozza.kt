package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.ui.CampiBozza
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.GestoreBozza
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RichiestaConferma
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.StatoBozza
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.quandoCompleto

/** Riga d'aiuto sotto i campi in cui si scrive a Sol. */
const val AIUTO_DETTATURA = "Puoi anche dettare col microfono della tastiera."

/** L'avviso della conferma quando a quella mail si è già risposto da un altro programma (15.13). */
const val AVVISO_GIA_RISPOSTO = "Hai già risposto a questa mail da un altro programma: vuoi mandare anche questa?"

/** L'avviso in più della conferma per le risposte a una mail segnalata (16.3). */
const val AVVISO_TRUFFA_INVIO = "Stai rispondendo a una mail segnalata come possibile truffa."

/**
 * La bozza sotto una mail (o da sola, per un sollecito). [onRifai]: rifare la bozza
 * (o prepararne una se non c'è) con le istruzioni di Manuel. [giaRisposto]: a
 * quella mail ha già risposto da un altro programma.
 */
@Composable
fun SezioneBozza(
    gestore: GestoreBozza,
    onRifai: (String?) -> Unit,
    titolo: String = "Risposta",
    giaRisposto: Boolean = false,
    puoPreparare: Boolean = true,
) {
    val stato by gestore.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var mostraRifai by rememberSaveable { mutableStateOf(false) }
    var chiediScarta by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        gestore.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    // Ricomposta da capo (rotazione) con la conferma aperta: la si ricostruisce coi dati riletti.
    LaunchedEffect(Unit) { gestore.ricostruisciConferma() }

    Column(Modifier.fillMaxWidth()) {
        TitoloSezione(titolo)
        stato.errore?.let { Riquadro(it, grave = true, azione = "Chiudi", onAzione = gestore::consumaErrore) }
        if (stato.inCorso) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))

        val bozza = stato.bozza
        val campi = stato.campi
        when {
            stato.caricamento -> Text("Carico la bozza...", style = MaterialTheme.typography.bodyLarge)
            bozza == null || (bozza.stato == Bozza.SCARTATA && puoPreparare) -> {
                Text(
                    if (bozza == null) "Sol non ha preparato una bozza per questa mail." else "La bozza è stata scartata.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (puoPreparare) {
                    PulsanteVuoto(
                        "Prepara una risposta",
                        onClick = { mostraRifai = true },
                        abilitato = !stato.inCorso,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            bozza.inPreparazione -> {
                Text("Sol sta scrivendo la bozza...", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            campi != null -> ModificaCampi(
                campi = campi,
                abilitato = !stato.campiBloccati,
                onCambia = gestore::cambiaCampi,
                onSalva = { gestore.salvaModifica() },
                onAnnulla = gestore::annullaModifica,
            )
            // Sol ha finito ma non ha scritto niente: non è un invio fallito, è una risposta da rifare.
            bozza.senzaTesto && !bozza.chiusa -> {
                Riquadro(GestoreBozza.SENZA_TESTO + (bozza.errore?.let { " $it" } ?: ""))
                FilaPulsanti(Modifier.padding(top = 12.dp)) {
                    PulsantePieno("Riprova a prepararla", { gestore.rifai(null) }, abilitato = !stato.campiBloccati)
                    PulsanteVuoto("Scrivila tu", gestore::avviaModifica, abilitato = !stato.campiBloccati)
                }
            }
            else -> MostraBozza(
                bozza = bozza,
                stato = stato,
                giaRisposto = giaRisposto,
                onModifica = gestore::avviaModifica,
                onRifai = { mostraRifai = true },
                onScarta = { chiediScarta = true },
                onInvia = gestore::chiediInvio,
            )
        }
    }

    if (mostraRifai) {
        DialogoRifai(
            onRifai = { istruzioni ->
                mostraRifai = false
                onRifai(istruzioni)
            },
            onAnnulla = { mostraRifai = false },
        )
    }
    if (stato.chiediDaZero) {
        DialogoConferma(
            titolo = "Rifarla da zero?",
            testo = "Le tue modifiche vanno perse e Sol riscrive la risposta da capo. " +
                "Se vuoi tenerle, scrivi invece cosa cambiare (per esempio «più breve»).",
            conferma = "Rifalla da zero",
            onConferma = gestore::confermaDaZero,
            onAnnulla = gestore::annullaDaZero,
            pericolosa = true,
        )
    }
    if (chiediScarta) {
        DialogoConferma(
            titolo = "Scartare la bozza?",
            testo = "La bozza non verrà inviata. Potrai sempre chiederne una nuova.",
            conferma = "Scarta",
            onConferma = {
                chiediScarta = false
                gestore.scarta()
            },
            onAnnulla = { chiediScarta = false },
        )
    }
    stato.confermaInvio?.let { richiesta ->
        DialogoInvio(richiesta, onConferma = gestore::confermaInvio, onAnnulla = gestore::annullaInvio)
    }
}

@Composable
private fun MostraBozza(
    bozza: Bozza,
    stato: StatoBozza,
    giaRisposto: Boolean,
    onModifica: () -> Unit,
    onRifai: () -> Unit,
    onScarta: () -> Unit,
    onInvia: () -> Unit,
) {
    if (giaRisposto && !bozza.chiusa) {
        Riquadro(
            "Hai già risposto a questa mail da un altro programma. Questa bozza parte solo se lo confermi.",
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (bozza.stato) {
                Bozza.INVIATA -> Text(
                    "Inviata ${quandoCompleto(bozza.inviataIl)}".trim(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Bozza.SCARTATA -> Text("Bozza scartata", style = MaterialTheme.typography.labelLarge)
                Bozza.ERRORE -> if (!stato.verificaInvio) {
                    Text(
                        "L'ultimo invio non è riuscito: ${bozza.errore ?: "motivo non indicato"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            CampoLettura("A", bozza.a.joinToString(", ").ifBlank { "(nessuno)" })
            if (bozza.cc.isNotEmpty()) CampoLettura("Cc", bozza.cc.joinToString(", "))
            CampoLettura("Oggetto", bozza.oggetto.ifBlank { "(senza oggetto)" })
            SelectionContainer {
                Text(bozza.testo, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            }
            if (bozza.modificata) {
                Text(
                    "Modificata da te",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (!bozza.chiusa) {
        val bloccato = stato.campiBloccati
        FilaPulsanti(Modifier.padding(top = 12.dp)) {
            PulsanteVuoto("Modifica", onModifica, abilitato = !bloccato)
            PulsanteVuoto("Rifai con istruzioni", onRifai, abilitato = !bloccato)
            PulsanteTesto("Scarta", onScarta, abilitato = !bloccato, colore = MaterialTheme.colorScheme.error)
        }
        PulsantePieno(
            when {
                stato.verificaInvio -> "Controllo se è partita..."
                stato.preparazioneConferma -> "Rileggo la risposta..."
                else -> "Invia..."
            },
            onClick = onInvia,
            abilitato = !bloccato,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@Composable
private fun CampoLettura(etichetta: String, valore: String) {
    Text(
        "$etichetta: $valore",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (etichetta == "Oggetto") FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun ModificaCampi(
    campi: CampiBozza,
    abilitato: Boolean,
    onCambia: (CampiBozza) -> Unit,
    onSalva: () -> Unit,
    onAnnulla: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = campi.a,
            onValueChange = { onCambia(campi.copy(a = it)) },
            label = { Text("A") },
            supportingText = { Text("Più indirizzi separati da virgola") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = abilitato,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = campi.cc,
            onValueChange = { onCambia(campi.copy(cc = it)) },
            label = { Text("Cc") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = abilitato,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = campi.oggetto,
            onValueChange = { onCambia(campi.copy(oggetto = it)) },
            label = { Text("Oggetto") },
            enabled = abilitato,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = campi.testo,
            onValueChange = { onCambia(campi.copy(testo = it)) },
            label = { Text("Testo") },
            supportingText = { Text(AIUTO_DETTATURA) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            enabled = abilitato,
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        )
        FilaPulsanti {
            PulsantePieno("Salva modifiche", onSalva, abilitato = abilitato)
            PulsanteTesto("Annulla", onAnnulla, abilitato = abilitato)
        }
    }
}

@Composable
private fun DialogoRifai(onRifai: (String?) -> Unit, onAnnulla: () -> Unit) {
    var istruzioni by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Rifai la bozza") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Scrivi cosa cambiare: Sol parte dalla risposta di adesso, modifiche tue comprese.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = istruzioni,
                    onValueChange = { istruzioni = it },
                    placeholder = { Text("Esempio: digli che giovedì va bene") },
                    supportingText = { Text(AIUTO_DETTATURA) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    minLines = 3,
                )
            }
        },
        confirmButton = { PulsantePieno("Rifai", onClick = { onRifai(istruzioni.trim().ifEmpty { null }) }) },
        dismissButton = { PulsanteTesto("Annulla", onAnnulla) },
    )
}

/**
 * La conferma esplicita: chi riceve, cosa, come comincia, e che parte davvero.
 * Tutto viene dalla bozza appena riletta: è quella versione che parte (15.11).
 */
@Composable
fun DialogoInvio(richiesta: RichiestaConferma, onConferma: () -> Unit, onAnnulla: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(if (richiesta.cambiata) "La risposta è cambiata" else "Inviare questa mail?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (richiesta.cambiata) {
                    Riquadro("È cambiata dopo che l'hai controllata: ricontrollala prima di inviarla.", grave = true)
                }
                if (richiesta.sospetta) RiquadroTruffa(AVVISO_TRUFFA_INVIO)
                if (richiesta.giaRisposto) Riquadro(AVVISO_GIA_RISPOSTO, grave = true)
                Text("A: ${richiesta.a.joinToString(", ")}", style = MaterialTheme.typography.bodyLarge)
                if (richiesta.cc.isNotEmpty()) {
                    Text("Cc: ${richiesta.cc.joinToString(", ")}", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Oggetto: ${richiesta.oggetto.ifBlank { "(senza oggetto)" }}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Comincia così: «${richiesta.primaRiga}»", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "La mail parte subito e non si può ritirare.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            PulsantePieno(
                when {
                    richiesta.sospetta -> "Invia lo stesso"
                    richiesta.giaRisposto -> "Manda anche questa"
                    else -> "Invia adesso"
                },
                onConferma,
            )
        },
        dismissButton = { PulsanteTesto("Annulla", onAnnulla) },
    )
}
