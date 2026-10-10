package eu.stgm.sol.ui.dettaglio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.ALTEZZA_TOCCO
import eu.stgm.sol.ui.CampiBozza
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.GestoreBozza
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.RichiestaConferma
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.StatoBozza
import eu.stgm.sol.ui.Superficie
import eu.stgm.sol.ui.quandoCompleto
import eu.stgm.sol.ui.tema.LocalSegni
import eu.stgm.sol.ui.tema.morbido

/** Riga d'aiuto sotto i campi in cui si scrive a Sol. */
const val AIUTO_DETTATURA = "Puoi anche dettare col microfono della tastiera."

/** L'avviso della conferma quando a quella mail si è già risposto da un altro programma (15.13). */
const val AVVISO_GIA_RISPOSTO = "Hai già risposto a questa mail da un altro programma: vuoi mandare anche questa?"

private const val TITOLO_RISPOSTA = "Risposta pronta"

/** L'avviso in più della conferma per le risposte a una mail segnalata (16.3). */
const val AVVISO_TRUFFA_INVIO = "Stai rispondendo a una mail segnalata come possibile truffa."

/** La barra fissa in basso della mail aperta (docs/aspetto.md §3). */
sealed interface Barra {
    data object Nessuna : Barra

    /** Nessuna risposta, ma la mail va risposta: [Prepara la risposta]. */
    data object Prepara : Barra

    /** "Sol sta scrivendo la risposta…" con l'indicatore. */
    data object Scrivendo : Barra

    /** Un controllo in corso al posto dei pulsanti ("Rileggo la risposta...", "Controllo se è partita..."). */
    data class Attesa(val testo: String) : Barra

    /** Con una risposta pronta: [Rifai] [Invia →]. */
    data class RifaiInvia(val invioAttivo: Boolean, val rifaiAttivo: Boolean) : Barra
}

/**
 * Quale barra mostrare. [mail] null: un sollecito, che non risponde a una mail
 * ricevuta. Una mail sospetta senza risposta chiesta non ha barra: il gesto giusto
 * è "Non è una truffa", nell'avviso.
 */
fun barraPer(mail: Mail?, stato: StatoBozza): Barra {
    val bozza = stato.bozza
    if (stato.caricamento) return Barra.Nessuna
    if (stato.richiestaRisposta || stato.inAttesa || bozza?.inPreparazione == true) return Barra.Scrivendo
    if (bozza != null && !bozza.chiusa) {
        return when {
            stato.verificaInvio -> Barra.Attesa("Controllo se è partita...")
            stato.preparazioneConferma -> Barra.Attesa("Rileggo la risposta...")
            else -> Barra.RifaiInvia(
                invioAttivo = !stato.campiBloccati && stato.campiVisibili?.testo?.isNotBlank() == true,
                rifaiAttivo = !stato.campiBloccati,
            )
        }
    }
    if (mail == null || mail.sospetta || mail.rispostaData || bozza?.stato == Bozza.INVIATA) return Barra.Nessuna
    return if (mail.daRispondere) Barra.Prepara else Barra.Nessuna
}

@Composable
fun BarraRisposta(barra: Barra, onRifai: () -> Unit, onInvia: () -> Unit, onPrepara: () -> Unit) {
    if (barra == Barra.Nessuna) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 6.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = ALTEZZA_TOCCO + 20.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (barra) {
                    Barra.Prepara -> PulsantePieno("Prepara la risposta", onPrepara, Modifier.fillMaxWidth())
                    Barra.Scrivendo -> InAttesa("Sol sta scrivendo la risposta…")
                    is Barra.Attesa -> InAttesa(barra.testo)
                    is Barra.RifaiInvia -> {
                        PulsanteVuoto("Rifai", onRifai, Modifier.weight(1f), abilitato = barra.rifaiAttivo)
                        Spacer(Modifier.width(12.dp))
                        PulsantePieno(
                            "Invia",
                            onInvia,
                            Modifier.weight(1.6f),
                            abilitato = barra.invioAttivo,
                            icona = Icons.AutoMirrored.Filled.ArrowForward,
                        )
                    }
                    Barra.Nessuna -> Unit
                }
            }
        }
    }
}

@Composable
private fun InAttesa(testo: String) {
    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
    Text(testo, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
}

/**
 * La risposta sotto la mail (o il sollecito da solo): si scrive direttamente qui,
 * A e Oggetto si aprono al tocco. Le garanzie dell'invio stanno in [GestoreBozza] e
 * sono quelle di sempre: durante conferma e invio i campi sono bloccati.
 */
@Composable
fun RiquadroRisposta(
    gestore: GestoreBozza,
    stato: StatoBozza,
    giaRisposto: Boolean,
    modifier: Modifier = Modifier,
    titolo: String = TITOLO_RISPOSTA,
) {
    val bozza = stato.bozza
    // Il segno di spunta compare solo se la risposta parte mentre la si guarda.
    val apertaAllInizio = remember(bozza?.id) { bozza != null && !bozza.chiusa }
    Column(modifier.fillMaxWidth().animateContentSize(morbido())) {
        stato.errore?.let {
            Riquadro(it, grave = true, azione = "Chiudi", onAzione = gestore::consumaErrore, modifier = Modifier.padding(bottom = 8.dp))
        }
        when {
            stato.caricamento -> Text("Carico la risposta...", style = MaterialTheme.typography.bodyLarge)
            // Senza risposta, o mentre Sol la scrive, parla la barra in basso.
            bozza == null || bozza.inPreparazione || stato.richiestaRisposta -> Unit
            bozza.stato == Bozza.INVIATA -> RispostaInviata(bozza, conMovimento = apertaAllInizio)
            bozza.stato == Bozza.SCARTATA -> Text(
                "La risposta è stata scartata.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> ScriviRisposta(gestore, stato, bozza, giaRisposto, titolo)
        }
    }
}

@Composable
private fun RispostaInviata(bozza: Bozza, conMovimento: Boolean) {
    val segni = LocalSegni.current
    val spunta = remember { MutableTransitionState(!conMovimento).apply { targetState = true } }
    Superficie {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(visibleState = spunta, enter = scaleIn(morbido()) + fadeIn(morbido())) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = segni.accentoTesto, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Risposta inviata", style = MaterialTheme.typography.titleSmall)
                quandoCompleto(bozza.inviataIl).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ScriviRisposta(gestore: GestoreBozza, stato: StatoBozza, bozza: Bozza, giaRisposto: Boolean, titolo: String) {
    val segni = LocalSegni.current
    val campi = stato.campiVisibili ?: return
    val abilitato = stato.scrivibile
    var apriIndirizzi by rememberSaveable(bozza.id) { mutableStateOf(false) }
    var apriOggetto by rememberSaveable(bozza.id) { mutableStateOf(false) }
    val cambia: (CampiBozza) -> Unit = gestore::scrivi

    if (giaRisposto) {
        Riquadro(
            "Hai già risposto a questa mail da un altro programma. Questa bozza parte solo se lo confermi.",
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Superficie {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = segni.accentoTesto, modifier = Modifier.size(20.dp))
                // Senza testo non è una risposta "pronta": lo dice il riquadro qui sotto.
                Text(
                    if (bozza.senzaTesto && titolo == TITOLO_RISPOSTA) "Risposta" else titolo,
                    style = MaterialTheme.typography.titleSmall,
                    color = segni.accentoTesto,
                    modifier = Modifier.padding(start = 8.dp).weight(1f).semantics { heading() },
                )
                if (bozza.modificata) {
                    Text("modificata da te", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (bozza.stato == Bozza.ERRORE && !stato.verificaInvio) {
                Text(
                    "L'ultimo invio non è riuscito: ${bozza.errore ?: "motivo non indicato"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (apriIndirizzi) {
                CampoScritto("A", campi.a, abilitato, KeyboardType.Email, aiuto = "Più indirizzi separati da virgola") {
                    cambia(campi.copy(a = it))
                }
                CampoScritto("Cc", campi.cc, abilitato, KeyboardType.Email) { cambia(campi.copy(cc = it)) }
            } else {
                RigaCampo("A", campi.a.ifBlank { "(nessuno)" }, abilitato) { apriIndirizzi = true }
                if (campi.cc.isNotBlank()) RigaCampo("Cc", campi.cc, abilitato) { apriIndirizzi = true }
            }
            if (apriOggetto) {
                CampoScritto("Oggetto", campi.oggetto, abilitato, KeyboardType.Text) { cambia(campi.copy(oggetto = it)) }
            } else {
                RigaCampo("Oggetto", campi.oggetto.ifBlank { "(senza oggetto)" }, abilitato) { apriOggetto = true }
            }
            // Sol ha finito ma non ha scritto niente: non è un invio fallito, è una risposta da rifare (o da scrivere qui).
            if (bozza.senzaTesto && !stato.inModifica) {
                Riquadro(GestoreBozza.SENZA_TESTO + (bozza.errore?.let { " $it" } ?: ""), modifier = Modifier.padding(top = 8.dp))
                PulsanteTesto("Riprova a prepararla", { gestore.rifai(null) }, abilitato = abilitato)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            // Il testo si scrive qui, allineato ad A e Oggetto. Bloccato durante conferma e invio, ma sempre leggibile.
            BasicTextField(
                value = campi.testo,
                onValueChange = { cambia(campi.copy(testo = it)) },
                enabled = abilitato,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(vertical = 8.dp),
                decorationBox = { campo ->
                    Box {
                        if (campi.testo.isEmpty()) {
                            Text(
                                "Scrivi qui la risposta",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        campo()
                    }
                },
            )
            Text(AIUTO_DETTATURA, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stato.modificheNonSalvate) {
                Text(
                    "Modifiche non ancora salvate: restano su questo telefono finché non le salvi o le invii.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PulsanteTesto("Annulla modifiche", gestore::annullaModifica, abilitato = abilitato)
                    PulsanteTesto("Salva", { gestore.salvaModifica() }, abilitato = abilitato)
                }
            }
        }
    }
}

/** A, Cc, Oggetto chiusi: una riga da toccare per cambiarli. */
@Composable
private fun RigaCampo(etichetta: String, valore: String, abilitato: Boolean, onApri: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = abilitato, role = Role.Button, onClickLabel = "Cambia $etichetta", onClick = onApri),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$etichetta: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            valore,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (etichetta == "Oggetto") FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (abilitato) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CampoScritto(
    etichetta: String,
    valore: String,
    abilitato: Boolean,
    tastiera: KeyboardType,
    aiuto: String? = null,
    onCambia: (String) -> Unit,
) {
    OutlinedTextField(
        value = valore,
        onValueChange = onCambia,
        label = { Text(etichetta) },
        supportingText = aiuto?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = tastiera),
        enabled = abilitato,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** I messaggi brevi e le finestre della risposta (rifare da zero, conferma d'invio). */
@Composable
fun FinestreRisposta(gestore: GestoreBozza, stato: StatoBozza) {
    val messaggi = LocalMessaggi.current
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        gestore.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    // Ricomposta da capo (rotazione) con la conferma aperta: la si ricostruisce coi dati riletti.
    LaunchedEffect(Unit) { gestore.ricostruisciConferma() }
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
    stato.confermaInvio?.let { richiesta ->
        DialogoInvio(richiesta, onConferma = gestore::confermaInvio, onAnnulla = gestore::annullaInvio)
    }
}

/** "Rifai": cosa cambiare, a parole (o dettato). Vuoto = Sol la riscrive come crede. */
@Composable
fun DialogoRifai(onRifai: (String?) -> Unit, onAnnulla: () -> Unit) {
    var istruzioni by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Rifai la risposta") },
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
