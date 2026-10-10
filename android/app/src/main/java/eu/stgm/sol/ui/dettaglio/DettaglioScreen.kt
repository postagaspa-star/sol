package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.Rotella
import eu.stgm.sol.ui.Segno
import eu.stgm.sol.ui.TipoSegno
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.dimensione
import eu.stgm.sol.ui.quandoCompleto
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DettaglioScreen(vm: DettaglioViewModel, mailId: Long, onIndietro: () -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var mostraSposta by rememberSaveable { mutableStateOf(false) }
    var chiediNonETruffa by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(mailId) {
        vm.apri(mailId)
        onDispose { vm.sospendi() }
    }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Mail") },
            navigationIcon = {
                IconButton(onClick = onIndietro) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
            },
        )
        BarraCaricamento(stato.caricamento || stato.inCorso)
        val mail = stato.mail
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(bottom = 8.dp))
            if (mail == null) {
                if (stato.caricamento) Rotella()
            } else {
                if (mail.sospetta) {
                    AvvisoTruffa(inCorso = stato.inCorso, onNonETruffa = { chiediNonETruffa = true })
                }
                Intestazione(mail)
                Gruppo(
                    mail = mail,
                    modalitaProva = stato.modalitaProva,
                    inCorso = stato.inCorso,
                    onSposta = { mostraSposta = true },
                    onVaBene = vm::confermaGruppo,
                )
                FilaPulsanti(Modifier.padding(top = 12.dp)) {
                    PulsanteVuoto(
                        if (mail.letta) "Segna da leggere" else "Segna come letta",
                        vm::cambiaLetta,
                        abilitato = !stato.inCorso,
                    )
                    if (!mail.rispostaData) {
                        PulsanteVuoto(
                            if (mail.daRispondere) "Non serve rispondere" else "Da rispondere",
                            vm::cambiaDaRispondere,
                            abilitato = !stato.inCorso,
                        )
                    }
                    // 16.3: meno in vista delle altre azioni, ma sempre a portata.
                    if (!mail.sospetta) {
                        PulsanteTesto("Segnala come possibile truffa", vm::segnalaTruffa, abilitato = !stato.inCorso)
                    }
                }
                TitoloSezione("Testo")
                SelectionContainer {
                    Text(
                        mail.corpo?.takeIf { it.isNotBlank() } ?: mail.anteprima,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (mail.allegati.isNotEmpty()) {
                    TitoloSezione("Allegati")
                    mail.allegati.forEach {
                        Text("${it.nome}  (${dimensione(it.byte)})", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Gli allegati si aprono dalla casella di posta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 16.dp))
                if (stato.daCacheDel != null) {
                    Riquadro("La bozza si vede solo quando il server di casa risponde.", modifier = Modifier.padding(top = 12.dp))
                } else {
                    SezioneBozza(vm.bozza, onRifai = vm::rifaiBozza, giaRisposto = mail.rispostaData)
                }
                Row(Modifier.heightIn(min = 32.dp)) {}
            }
        }
    }

    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
    if (chiediNonETruffa) {
        DialogoConferma(
            titolo = "Non è una truffa?",
            testo = "Sol la tratterà come una mail normale e, se va risposta, preparerà la bozza. " +
                "Fallo solo se sei sicuro, per esempio dopo aver telefonato al mittente.",
            conferma = "Sì, è a posto",
            onConferma = {
                chiediNonETruffa = false
                vm.nonETruffa()
            },
            onAnnulla = { chiediNonETruffa = false },
        )
    }
    val mail = stato.mail
    if (mostraSposta && mail != null) {
        DialogoSposta(
            gruppi = stato.gruppi,
            attuale = mail.gruppoId,
            onSposta = { gruppo, impara ->
                mostraSposta = false
                vm.sposta(gruppo, impara)
            },
            onAnnulla = { mostraSposta = false },
        )
    }
}

/** "Mario Rossi <rossi@esempio.it>"; con il nome vuoto (o uguale all'indirizzo) solo l'indirizzo. */
internal fun daChi(nome: String?, indirizzo: String): String {
    val n = nome?.trim().orEmpty()
    return when {
        n.isEmpty() || n.equals(indirizzo, ignoreCase = true) -> indirizzo
        indirizzo.isBlank() -> n
        else -> "$n <$indirizzo>"
    }
}

@Composable
private fun Intestazione(mail: Mail) {
    Text(mail.oggetto.ifBlank { "(senza oggetto)" }, style = MaterialTheme.typography.titleLarge)
    Text(
        "Da: ${daChi(mail.mittente.nome, mail.mittente.indirizzo)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 6.dp),
    )
    // 15.8: per le PEC il mittente sopra è quello della mail interna; la busta l'ha spedita il gestore.
    mail.pec?.let { pec ->
        Text(
            "Arrivata come PEC da: ${pec.mittenteBusta}" + (pec.gestore?.let { " (gestore $it)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (mail.destinatari.isNotEmpty()) {
        Text("A: ${mail.destinatari.joinToString(", ") { it.etichetta }}", style = MaterialTheme.typography.bodyMedium)
    }
    if (mail.cc.isNotEmpty()) {
        Text("Cc: ${mail.cc.joinToString(", ") { it.etichetta }}", style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        listOfNotNull(quandoCompleto(mail.ricevutaIl).ifBlank { null }, mail.casella?.let { "in $it" }).joinToString(" "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val segni = buildList {
        if (mail.sospetta) add(TipoSegno.TRUFFA)
        if (mail.urgente) add(TipoSegno.URGENTE)
        if (mail.daRispondere && !mail.rispostaData) add(TipoSegno.DA_RISPONDERE)
        if (mail.bozzaPronta) add(TipoSegno.BOZZA_PRONTA)
        if (mail.daControllare) add(TipoSegno.DA_CONTROLLARE)
    }
    if (segni.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            segni.forEach { Segno(it) }
        }
    }
    if (mail.rispostaData) {
        Text(
            "Hai già risposto.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    mail.riassunto?.takeIf { it.isNotBlank() }?.let {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("In breve", style = MaterialTheme.typography.labelMedium)
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Il testo del contratto (16.3): cosa fare prima di pagare o mandare documenti. */
const val AVVISO_TRUFFA =
    "Attenzione: questa mail potrebbe essere una truffa. Prima di pagare, cambiare coordinate bancarie o " +
        "mandare documenti, telefona al mittente a un numero che conosci già, non a quello scritto nella mail."

/**
 * In cima al dettaglio di una mail sospetta: chiaro e ben visibile, ma con i colori
 * degli avvisi dell'app, non da allarme. Il pulsante resta pieno e leggibile.
 */
@Composable
private fun AvvisoTruffa(inCorso: Boolean, onNonETruffa: () -> Unit) {
    RiquadroTruffa(AVVISO_TRUFFA, Modifier.padding(bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) {
            PulsanteVuoto("Non è una truffa", onNonETruffa, abilitato = !inCorso)
        }
    }
}

/** Da dove viene il gruppo, detto semplice. */
private fun origine(mail: Mail): String = when (mail.origineSmistamento) {
    "regola" -> "Scelto da una regola."
    "utente" -> "Scelto da te."
    "decisore" -> mail.sicurezza?.let { "Scelto da Sol (sicuro al ${(it * 100).roundToInt()}%)." } ?: "Scelto da Sol."
    else -> if (mail.smistamento == "in_attesa") "Sol non l'ha ancora smistata." else ""
}

@Composable
private fun Gruppo(mail: Mail, modalitaProva: Boolean, inCorso: Boolean, onSposta: () -> Unit, onVaBene: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Gruppo: ${mail.gruppo ?: "nessuno"}", style = MaterialTheme.typography.titleSmall)
            origine(mail).takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mail.sospetta) {
                Text(
                    "È tra quelle da controllare perché potrebbe essere una truffa.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (mail.daControllare) {
                Text(
                    "Sol non è sicuro: controlla se il gruppo va bene.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!mail.applicataInCasella && modalitaProva) {
                Text(
                    "Modalità prova: nella casella vera la mail non è stata spostata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FilaPulsanti(Modifier.padding(top = 8.dp)) {
                // Su una possibile truffa il gesto giusto è "Non è una truffa", in cima.
                if (mail.daControllare && !mail.sospetta) PulsantePieno("Va bene così", onVaBene, abilitato = !inCorso)
                PulsanteVuoto("Sposta in...", onSposta, abilitato = !inCorso)
            }
        }
    }
}

@Composable
private fun DialogoSposta(
    gruppi: List<Gruppo>,
    attuale: Long?,
    onSposta: (Long, Boolean) -> Unit,
    onAnnulla: () -> Unit,
) {
    var scelto by rememberSaveable { mutableStateOf(attuale) }
    // Acceso di base: correggere una volta deve bastare per le prossime mail.
    var impara by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Sposta in...") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                gruppi.forEach { g ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(selected = scelto == g.id, role = Role.RadioButton, onClick = { scelto = g.id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = scelto == g.id, onClick = null)
                        Text(g.nome, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .toggleable(value = impara, role = Role.Checkbox, onValueChange = { impara = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = impara, onCheckedChange = null)
                    Text(
                        "Ricordati questo mittente",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            PulsantePieno(
                "Sposta",
                onClick = { scelto?.let { onSposta(it, impara) } },
                abilitato = scelto != null && scelto != attuale,
            )
        },
        dismissButton = { PulsanteTesto("Annulla", onAnnulla) },
    )
}
