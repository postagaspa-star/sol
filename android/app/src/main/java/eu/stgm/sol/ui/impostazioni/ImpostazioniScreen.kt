package eu.stgm.sol.ui.impostazioni

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.BuildConfig
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.aggiornamento.Aggiornatore
import eu.stgm.sol.aggiornamento.testo
import eu.stgm.sol.dati.Casella
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.PulsantePieno
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.quandoCompleto
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Una conferma in sospeso: titolo, testo, pulsante, azione. */
private data class Conferma(val titolo: String, val testo: String, val pulsante: String, val azione: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpostazioniScreen(
    vm: ImpostazioniViewModel,
    onIndietro: () -> Unit,
    onAbbinaDiNuovo: () -> Unit,
    onScollegato: () -> Unit,
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var conferma by remember { mutableStateOf<Conferma?>(null) }
    var dialogo by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.carica() }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    LaunchedEffect(stato.casellaAggiunta) {
        if (stato.casellaAggiunta) {
            dialogo = null
            vm.casellaAggiuntaVista()
        }
    }
    LaunchedEffect(stato.scollegato) { if (stato.scollegato) onScollegato() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Impostazioni") },
            navigationIcon = {
                IconButton(onClick = onIndietro) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
            },
            actions = { IconButton(onClick = vm::carica) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna") } },
        )
        BarraCaricamento(stato.caricamento || stato.inCorso)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica)

            // --- Caselle ---
            TitoloSezione("Caselle di posta")
            if (stato.caselle.isEmpty()) {
                Text("Nessuna casella collegata.", style = MaterialTheme.typography.bodyLarge)
            }
            stato.caselle.forEach { c ->
                RigaCasella(
                    c,
                    abilitato = !stato.inCorso,
                    onControlla = { vm.controllaCasella(c.id) },
                    // 15.3: una casella in errore si ricollega senza perdere niente.
                    onRicollega = when {
                        c.stato != "errore" -> null
                        c.tipo == "gmail" -> { { dialogo = "ricollega:${c.id}" } }
                        c.tipo == "outlook" -> { {
                            dialogo = "ricollega-outlook"
                            vm.ricollegaOutlook(c.id)
                        } }
                        else -> null
                    },
                    onTogli = {
                        conferma = Conferma(
                            "Scollegare ${c.nome ?: c.indirizzo ?: "la casella"}?",
                            "Sol smette di leggerla e dimentica le sue mail. Nella casella vera non cambia niente: " +
                                "nessuna mail viene cancellata.",
                            "Scollega",
                        ) { vm.togliCasella(c.id) }
                    },
                )
            }
            FilaPulsanti(Modifier.padding(top = 8.dp)) {
                PulsanteVuoto("Aggiungi Gmail", { dialogo = "gmail" })
                PulsanteVuoto("Aggiungi Outlook", { dialogo = "outlook" })
                PulsanteTesto("Casella di prova", {
                    conferma = Conferma(
                        "Aggiungere una casella di prova?",
                        "Contiene solo mail inventate: serve per vedere come lavora Sol senza toccare la posta vera.",
                        "Aggiungi",
                    ) { vm.aggiungiFinta("Prova") }
                })
            }

            // --- Gruppi ---
            TitoloSezione("Gruppi")
            Text(
                "Ogni gruppo ha una frase che dice a Sol che mail ci vanno: più è chiara, meglio Sol smista.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            stato.gruppi.forEach { g ->
                RigaGruppo(
                    g,
                    abilitato = !stato.inCorso,
                    onRinomina = { dialogo = "rinomina:${g.id}" },
                    onDescrizione = { dialogo = "descrizione:${g.id}" },
                    onTogli = if (g.nome.equals("Altro", ignoreCase = true)) {
                        null
                    } else {
                        {
                            conferma = Conferma(
                                "Togliere il gruppo «${g.nome}»?",
                                "Le sue mail passano in Altro.",
                                "Togli",
                            ) { vm.togliGruppo(g.id) }
                        }
                    },
                )
            }
            if (stato.puoAggiungereGruppo) {
                PulsanteVuoto("Aggiungi un gruppo", { dialogo = "gruppo" }, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text(
                    ImpostazioniViewModel.TROPPI_GRUPPI,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // --- Regole ---
            TitoloSezione("Regole imparate")
            if (stato.regole.isEmpty()) {
                Text(
                    "Ancora nessuna. Quando sposti una mail con «Ricordati questo mittente», Sol crea una regola qui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            stato.regole.forEach { r ->
                val gruppo = stato.gruppi.firstOrNull { it.id == r.gruppoId }?.nome ?: "?"
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.valore}  →  $gruppo", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            (if (r.tipo == "dominio") "Tutto il dominio" else "Questo mittente") +
                                (if (r.origine == "manuale") " · scritta a mano" else " · imparata"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PulsanteTesto("Togli", { vm.togliRegola(r.id) }, abilitato = !stato.inCorso)
                }
                HorizontalDivider()
            }

            // --- Come lavora Sol ---
            TitoloSezione("Come lavora Sol")
            stato.impostazioni?.let { imp ->
                ComeLavora(
                    imp,
                    abilitato = !stato.inCorso,
                    onModalitaProva = { attiva ->
                        if (attiva) {
                            vm.cambiaModalitaProva(true)
                        } else {
                            conferma = Conferma(
                                "Spegnere la modalità prova?",
                                "Da adesso Sol sposta nelle cartelle (Outlook) e nelle etichette (Gmail) le mail nuove " +
                                    "che smista. Quelle già smistate restano dove sono, a meno che tu non scelga di " +
                                    "spostarle. Sol non cancella mai niente e non invia niente senza il tuo sì.",
                                "Spegni",
                            ) { vm.cambiaModalitaProva(false) }
                        }
                    },
                    onSoglia = vm::cambiaSoglia,
                    onGiorniRisposta = vm::cambiaGiorniRisposta,
                    onGiorniSollecito = vm::cambiaGiorniSollecito,
                    onFirma = vm::cambiaFirma,
                    onTono = vm::cambiaTono,
                )
            } ?: Text("Le impostazioni si vedono quando il server di casa risponde.", style = MaterialTheme.typography.bodyMedium)

            // --- Dispositivi ---
            TitoloSezione("Dispositivi abbinati")
            stato.dispositivi.forEach { d ->
                val questo = d.id == stato.questoDispositivo
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.nome + if (questo) " (questo telefono)" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                if (d.tipo == "pc") "Computer" else "Telefono",
                                d.ultimoAccesso?.let { "ultimo uso ${quandoCompleto(it)}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PulsanteTesto("Scollega", {
                        conferma = Conferma(
                            "Scollegare «${d.nome}»?",
                            if (questo) {
                                "Questo telefono non potrà più usare Sol finché non lo abbini di nuovo con un codice."
                            } else {
                                "Quel dispositivo non potrà più usare Sol finché non viene abbinato di nuovo."
                            },
                            "Scollega",
                        ) { vm.revocaDispositivo(d.id) }
                    }, abilitato = !stato.inCorso)
                }
                HorizontalDivider()
            }

            // --- Questo telefono ---
            TitoloSezione("Questo telefono")
            stato.server?.let { Text("Server di casa: $it", style = MaterialTheme.typography.bodyMedium) }
            if (stato.collegamentoNonProtetto) {
                Text(
                    "Collegamento di casa non protetto: va bene per l'installazione. Quando Tailscale funziona, " +
                        "rifai l'abbinamento con l'indirizzo https.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilaPulsanti(Modifier.padding(top = 8.dp)) {
                PulsanteVuoto("Abbina di nuovo", onAbbinaDiNuovo)
                PulsanteTesto("Scollega questo telefono", {
                    conferma = Conferma(
                        "Scollegare questo telefono?",
                        "Sol dimentica il codice d'accesso su questo telefono. Per usarlo di nuovo servirà un codice nuovo.",
                        "Scollega",
                    ) { vm.scollegaQuesto() }
                }, colore = MaterialTheme.colorScheme.error)
            }

            // --- Versione ---
            TitoloSezione("Versione")
            Versione(stato.versioni?.server, stato.versioni?.android?.let { it.codice > BuildConfig.VERSION_CODE } == true, vm::mostraMessaggio)
            Row(Modifier.heightIn(min = 32.dp)) {}
        }
    }

    when {
        dialogo == "gmail" -> DialogoGmail(stato.erroreAzione, stato.inCorso, stato.passwordConsentita, vm::aggiungiGmail) {
            dialogo = null
            vm.consumaErroreAzione()
        }
        dialogo?.startsWith("ricollega:") == true -> {
            val id = dialogo!!.removePrefix("ricollega:").toLongOrNull()
            val casella = stato.caselle.firstOrNull { it.id == id }
            if (casella != null) {
                DialogoRicollegaGmail(
                    casella = casella,
                    errore = stato.erroreAzione,
                    inCorso = stato.inCorso,
                    passwordConsentita = stato.passwordConsentita,
                    onRicollega = { vm.ricollegaGmail(casella.id, it) },
                    onChiudi = {
                        dialogo = null
                        vm.consumaErroreAzione()
                    },
                )
            }
        }
        dialogo == "outlook" || dialogo == "ricollega-outlook" -> DialogoOutlook(
            stato.outlook,
            stato.erroreAzione,
            stato.inCorso,
            ricollegamento = dialogo == "ricollega-outlook",
            onInizia = vm::aggiungiOutlook,
        ) {
            dialogo = null
            vm.chiudiOutlook()
            vm.consumaErroreAzione()
        }
        dialogo == "gruppo" -> DialogoNuovoGruppo(
            onAggiungi = { nome, descrizione ->
                dialogo = null
                vm.aggiungiGruppo(nome, descrizione)
            },
            onAnnulla = { dialogo = null },
        )
        dialogo?.startsWith("descrizione:") == true -> {
            val id = dialogo!!.removePrefix("descrizione:").toLongOrNull()
            val gruppo = stato.gruppi.firstOrNull { it.id == id }
            if (gruppo != null) {
                DialogoDescrizione(gruppo) { descrizione ->
                    dialogo = null
                    descrizione?.let { vm.cambiaDescrizioneGruppo(gruppo.id, it) }
                }
            }
        }
        dialogo?.startsWith("rinomina:") == true -> {
            val id = dialogo!!.removePrefix("rinomina:").toLongOrNull()
            val gruppo = stato.gruppi.firstOrNull { it.id == id }
            if (gruppo != null) {
                DialogoNome("Rinomina gruppo", gruppo.nome, "Salva") { nome ->
                    dialogo = null
                    nome?.let { vm.rinominaGruppo(gruppo.id, it) }
                }
            }
        }
    }
    // Gli errori dei gesti fatti fuori dalle finestre di Gmail e Outlook (che li
    // mostrano dentro): una finestra, perché la pagina può essere scorsa lontano.
    val finestraConErrori = dialogo == "gmail" || dialogo == "outlook" || dialogo == "ricollega-outlook" ||
        dialogo?.startsWith("ricollega:") == true
    if (!finestraConErrori) DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
    // 15.2: spenta la modalità prova, le mail già smistate si spostano solo col sì di Manuel.
    stato.arretratoDaApplicare?.let { quante ->
        DialogoConferma(
            titolo = "Spostare anche le mail di prima?",
            testo = "Ci sono $quante mail già smistate rimaste in Posta in arrivo. Vuoi spostarle anche nella casella? " +
                "Quelle che hai già spostato o archiviato tu restano dove sono.",
            conferma = "Spostale",
            onConferma = vm::applicaArretrato,
            onAnnulla = vm::lasciaArretrato,
            annulla = "Lasciale dove sono",
        )
    }
    conferma?.let { c ->
        DialogoConferma(
            titolo = c.titolo,
            testo = c.testo,
            conferma = c.pulsante,
            onConferma = {
                conferma = null
                c.azione()
            },
            onAnnulla = { conferma = null },
        )
    }
}

private fun statoCasella(c: Casella): String = when (c.stato) {
    "collegata" -> "Collegata" + (c.ultimoControllo?.let { ", ultimo controllo ${quandoCompleto(it)}" } ?: "")
    "in_collegamento" -> "Collegamento in corso"
    "errore" -> "Problema: ${c.errore ?: "Sol non riesce a leggerla"}"
    else -> c.stato
}

@Composable
private fun RigaCasella(
    c: Casella,
    abilitato: Boolean,
    onControlla: () -> Unit,
    onRicollega: (() -> Unit)?,
    onTogli: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            val tipo = when (c.tipo) {
                "gmail" -> "Gmail"
                "outlook" -> "Outlook"
                "finta" -> "Prova (mail inventate)"
                else -> c.tipo
            }
            Text(c.nome ?: c.indirizzo ?: tipo, style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(c.indirizzo, tipo).distinct().joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(
                statoCasella(c),
                style = MaterialTheme.typography.bodyMedium,
                color = if (c.stato == "errore") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilaPulsanti(Modifier.padding(top = 4.dp)) {
                if (onRicollega != null) PulsantePieno("Ricollega", onRicollega, abilitato = abilitato)
                PulsanteTesto("Controlla adesso", onControlla, abilitato = abilitato)
                PulsanteTesto("Scollega", onTogli, abilitato = abilitato, colore = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RigaGruppo(
    g: Gruppo,
    abilitato: Boolean,
    onRinomina: () -> Unit,
    onDescrizione: () -> Unit,
    onTogli: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(g.nome, style = MaterialTheme.typography.bodyLarge)
        Text(
            g.descrizione?.takeIf { it.isNotBlank() }?.let { "«$it»" } ?: "Nessuna frase: Sol usa solo il nome.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${g.totale} mail" + (if (g.daLeggere > 0) ", ${g.daLeggere} da leggere" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilaPulsanti {
            PulsanteTesto("Che mail ci vanno", onDescrizione, abilitato = abilitato)
            PulsanteTesto("Rinomina", onRinomina, abilitato = abilitato)
            if (onTogli != null) PulsanteTesto("Togli", onTogli, abilitato = abilitato)
        }
    }
    HorizontalDivider()
}

@Composable
private fun ComeLavora(
    imp: ImpostazioniSol,
    abilitato: Boolean,
    onModalitaProva: (Boolean) -> Unit,
    onSoglia: (Double) -> Unit,
    onGiorniRisposta: (Int) -> Unit,
    onGiorniSollecito: (Int) -> Unit,
    onFirma: (String) -> Unit,
    onTono: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Modalità prova", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (imp.modalitaProva) {
                    "Accesa: Sol suggerisce e non sposta niente."
                } else {
                    "Spenta: Sol sposta nelle caselle le mail nuove che smista."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = imp.modalitaProva, onCheckedChange = onModalitaProva, enabled = abilitato)
    }
    HorizontalDivider()

    var soglia by remember(imp.sogliaSicurezza) { mutableFloatStateOf(imp.sogliaSicurezza.toFloat()) }
    Text(
        "Sicurezza minima: ${(soglia * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Se Sol è meno sicuro di così, la mail finisce tra quelle «da controllare».",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = soglia,
        onValueChange = { soglia = it },
        onValueChangeFinished = { onSoglia(soglia.toDouble()) },
        valueRange = 0.5f..0.99f,
        enabled = abilitato,
    )
    HorizontalDivider()

    Contatore(
        "Dopo quanti giorni una mail senza risposta finisce in «In sospeso»",
        imp.giorniRisposta,
        1..30,
        abilitato,
        onGiorniRisposta,
    )
    Contatore(
        "Dopo quanti giorni senza risposta Sol propone un sollecito",
        imp.giorniSollecito,
        1..60,
        abilitato,
        onGiorniSollecito,
    )

    var firma by rememberSaveable(imp.firma) { mutableStateOf(imp.firma) }
    OutlinedTextField(
        value = firma,
        onValueChange = { firma = it },
        label = { Text("Firma delle risposte") },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        minLines = 2,
    )
    if (firma != imp.firma) PulsanteVuoto("Salva la firma", { onFirma(firma) }, abilitato = abilitato)

    var tono by rememberSaveable(imp.tono) { mutableStateOf(imp.tono) }
    OutlinedTextField(
        value = tono,
        onValueChange = { tono = it },
        label = { Text("Tono delle risposte") },
        placeholder = { Text("cortese e diretto") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    if (tono != imp.tono) PulsanteVuoto("Salva il tono", { onTono(tono) }, abilitato = abilitato)
}

@Composable
private fun Contatore(testo: String, valore: Int, intervallo: IntRange, abilitato: Boolean, onCambia: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(testo, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        PulsanteVuoto("−", { onCambia(valore - 1) }, abilitato = abilitato && valore > intervallo.first)
        Text(
            "$valore",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        PulsanteVuoto("+", { onCambia(valore + 1) }, abilitato = abilitato && valore < intervallo.last)
    }
    HorizontalDivider()
}

@Composable
private fun Versione(versioneServer: String?, nuovaDisponibile: Boolean, onEsito: (String) -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var controllo by remember { mutableStateOf(false) }
    Text("Sol per Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
    versioneServer?.let { Text("Server di casa: versione $it", style = MaterialTheme.typography.bodyMedium) }
    if (nuovaDisponibile) {
        Text(
            "C'è una versione nuova dell'app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    PulsanteVuoto(
        if (controllo) "Controllo..." else "Controlla aggiornamenti",
        onClick = {
            controllo = true
            ambito.launch {
                val esito = Aggiornatore(context).controlla(forza = true)
                controllo = false
                onEsito(esito.testo())
            }
        },
        abilitato = !controllo,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DialogoGmail(
    errore: String?,
    inCorso: Boolean,
    passwordConsentita: Boolean,
    onAggiungi: (String, String, String) -> Unit,
    onChiudi: () -> Unit,
) {
    var indirizzo by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("Aggiungi Gmail") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Serve la «password per le app»: una password speciale di 16 lettere che Google crea apposta " +
                        "per programmi come Sol. Non è la password del tuo account e si può revocare quando vuoi.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Dove si crea: Account Google → Sicurezza → Verifica in due passaggi (deve essere attiva) → " +
                        "Password per le app. Scegli un nome, per esempio «Sol», e copia le 16 lettere qui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = indirizzo,
                    onValueChange = { indirizzo = it },
                    label = { Text("Indirizzo Gmail") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    label = { Text("Nome (facoltativo)") },
                    placeholder = { Text("Personale") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 15.13: la password non viaggia mai in chiaro, nemmeno in casa.
                if (passwordConsentita) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password per le app") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Riquadro(Testi.PASSWORD_SU_HTTP, grave = true)
                }
                if (inCorso) Text("Sol sta provando la casella...", style = MaterialTheme.typography.bodyMedium)
                if (errore != null && errore != Testi.PASSWORD_SU_HTTP) Riquadro(errore, grave = true)
            }
        },
        confirmButton = {
            PulsantePieno("Collega", { onAggiungi(indirizzo, nome, password) }, abilitato = !inCorso && passwordConsentita)
        },
        dismissButton = { PulsanteTesto("Annulla", onChiudi) },
    )
}

/** 15.3: la password per le app nuova di una Gmail in errore; Sol riprende da dove era. */
@Composable
private fun DialogoRicollegaGmail(
    casella: Casella,
    errore: String?,
    inCorso: Boolean,
    passwordConsentita: Boolean,
    onRicollega: (String) -> Unit,
    onChiudi: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("Ricollega ${casella.indirizzo ?: casella.nome ?: "Gmail"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Di solito succede quando la password per le app è stata revocata o cambiata. Creane una nuova " +
                        "(Account Google → Sicurezza → Password per le app) e copiala qui. Mail, bozze e regole restano.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (passwordConsentita) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password per le app nuova") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Riquadro(Testi.PASSWORD_SU_HTTP, grave = true)
                }
                if (inCorso) Text("Sol sta provando la casella...", style = MaterialTheme.typography.bodyMedium)
                if (errore != null && errore != Testi.PASSWORD_SU_HTTP) Riquadro(errore, grave = true)
            }
        },
        confirmButton = {
            PulsantePieno("Ricollega", { onRicollega(password) }, abilitato = !inCorso && passwordConsentita)
        },
        dismissButton = { PulsanteTesto("Annulla", onChiudi) },
    )
}

/** 15.14: il nome e la frase che dice a Sol che mail ci vanno. */
@Composable
private fun DialogoNuovoGruppo(onAggiungi: (String, String) -> Unit, onAnnulla: () -> Unit) {
    var nome by rememberSaveable { mutableStateOf("") }
    var descrizione by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Nuovo gruppo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    label = { Text("Nome") },
                    placeholder = { Text("Cantieri") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = descrizione,
                    onValueChange = { descrizione = it },
                    label = { Text("Che mail ci vanno? (una frase)") },
                    supportingText = {
                        Text("Esempio: mail dei cantieri aperti, sopralluoghi e verbali. Più è chiara, meglio Sol smista.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = { PulsantePieno("Aggiungi", { onAggiungi(nome, descrizione) }, abilitato = nome.isNotBlank()) },
        dismissButton = { PulsanteTesto("Annulla", onAnnulla) },
    )
}

@Composable
private fun DialogoDescrizione(gruppo: Gruppo, onFine: (String?) -> Unit) {
    var descrizione by rememberSaveable { mutableStateOf(gruppo.descrizione.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onFine(null) },
        title = { Text("Che mail vanno in «${gruppo.nome}»?") },
        text = {
            OutlinedTextField(
                value = descrizione,
                onValueChange = { descrizione = it },
                label = { Text("Una frase") },
                supportingText = {
                    Text("Esempio: un cliente chiede un preventivo o conferma un lavoro. Più è chiara, meglio Sol smista.")
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = { PulsantePieno("Salva", { onFine(descrizione) }, abilitato = descrizione.isNotBlank()) },
        dismissButton = { PulsanteTesto("Annulla", { onFine(null) }) },
    )
}

@Composable
private fun DialogoOutlook(
    collegamento: CollegamentoInCorso?,
    errore: String?,
    inCorso: Boolean,
    ricollegamento: Boolean,
    onInizia: (String) -> Unit,
    onChiudi: () -> Unit,
) {
    val context = LocalContext.current
    var nome by rememberSaveable { mutableStateOf("Studio") }
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(if (ricollegamento) "Ricollega Outlook" else "Aggiungi Outlook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    collegamento == null && ricollegamento -> Text(
                        if (inCorso) "Chiedo il codice a Microsoft..." else "Non sono riuscito ad avere il codice.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    collegamento == null -> {
                        Text(
                            "Sol ti darà un codice da scrivere sul sito di Microsoft. Poi accedi con l'account della casella: " +
                                "Sol non vede mai la tua password.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = nome,
                            onValueChange = { nome = it },
                            label = { Text("Nome della casella") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    !collegamento.finito -> {
                        Text("1. Apri questo indirizzo:", style = MaterialTheme.typography.bodyLarge)
                        SelectionContainer { Text(collegamento.url, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
                        PulsanteVuoto("Apri il sito", { apriIndirizzo(context, collegamento.url) })
                        Text("2. Scrivi questo codice:", style = MaterialTheme.typography.bodyLarge)
                        SelectionContainer {
                            Text(
                                collegamento.codice,
                                fontSize = 30.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        PulsanteTesto("Copia il codice", { copia(context, collegamento.codice) })
                        Text("3. Accedi con l'account della casella.", style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text("Sol aspetta che tu finisca...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    collegamento.stato == "collegata" ->
                        Text("Fatto: la casella è collegata.", style = MaterialTheme.typography.bodyLarge)
                    else -> Riquadro(
                        "Il collegamento non è riuscito. ${collegamento.errore ?: ""}".trim(),
                        grave = true,
                    )
                }
                if (errore != null) Riquadro(errore, grave = true)
            }
        },
        confirmButton = {
            when {
                collegamento == null && ricollegamento -> PulsanteTesto("Annulla", onChiudi)
                collegamento == null -> PulsantePieno("Continua", { onInizia(nome) }, abilitato = !inCorso)
                else -> PulsantePieno(if (collegamento.finito) "Chiudi" else "Annulla", onChiudi)
            }
        },
        dismissButton = { if (collegamento == null && !ricollegamento) PulsanteTesto("Annulla", onChiudi) },
    )
}

@Composable
private fun DialogoNome(titolo: String, iniziale: String, pulsante: String, onFine: (String?) -> Unit) {
    var nome by rememberSaveable { mutableStateOf(iniziale) }
    AlertDialog(
        onDismissRequest = { onFine(null) },
        title = { Text(titolo) },
        text = {
            OutlinedTextField(
                value = nome,
                onValueChange = { nome = it },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { PulsantePieno(pulsante, { onFine(nome) }, abilitato = nome.isNotBlank()) },
        dismissButton = { PulsanteTesto("Annulla", { onFine(null) }) },
    )
}

/** Solo indirizzi https: è il sito di Microsoft, mai altro. */
private fun apriIndirizzo(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nessun browser: l'indirizzo resta scritto e si può copiare.
    }
}

private fun copia(context: Context, testo: String) {
    val appunti = context.getSystemService(ClipboardManager::class.java) ?: return
    appunti.setPrimaryClip(ClipData.newPlainText("Codice Microsoft", testo))
}
