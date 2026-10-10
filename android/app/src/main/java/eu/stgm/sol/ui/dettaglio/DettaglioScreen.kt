package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Indirizzo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.EtichettaGruppo
import eu.stgm.sol.ui.FoglioSpostaIn
import eu.stgm.sol.ui.IconeBarraDiStato
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.Rotella
import eu.stgm.sol.ui.Segnale
import eu.stgm.sol.ui.Superficie
import eu.stgm.sol.ui.SegnaleMail
import eu.stgm.sol.ui.dimensione
import eu.stgm.sol.ui.iniziali
import eu.stgm.sol.ui.quandoCompleto
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalSegni
import eu.stgm.sol.ui.tema.LocalTemaScuro

/**
 * Una mail aperta (docs/aspetto.md §3): intestazione col cerchio colorato, l'ora e
 * il gruppo (si tocca per spostarla), oggetto, l'eventuale avviso truffa, "In
 * breve", il testo, gli allegati e la risposta. In basso la barra fissa.
 */
@Composable
fun DettaglioScreen(vm: DettaglioViewModel, mailId: Long, onIndietro: () -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val risposta by vm.bozza.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var mostraSposta by rememberSaveable { mutableStateOf(false) }
    var chiediNonETruffa by rememberSaveable { mutableStateOf(false) }
    var mostraRifai by rememberSaveable { mutableStateOf(false) }
    var chiediScarta by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(mailId) {
        vm.apri(mailId)
        onDispose { vm.sospendi() }
    }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }

    val mail = stato.mail
    val colore = ColoreGruppo.daNome(stato.gruppi.firstOrNull { it.id == mail?.gruppoId }?.colore)
    // Senza rete la risposta non si legge: niente barra per una bozza che non si vede.
    val barra = if (mail == null || stato.daCacheDel != null) Barra.Nessuna else barraPer(mail, risposta)

    Column(Modifier.fillMaxSize()) {
        Intestazione(
            mail = mail,
            colore = colore,
            occupato = stato.inCorso,
            onIndietro = onIndietro,
            onSposta = { mostraSposta = true },
        ) { chiudi ->
            if (mail != null) {
                MenuMail(
                    mail = mail,
                    risposta = risposta,
                    chiudi = chiudi,
                    onLetta = vm::cambiaLetta,
                    onRispondere = vm::cambiaDaRispondere,
                    onSegnalaTruffa = vm::segnalaTruffa,
                    onPrepara = vm::preparaRisposta,
                    onScarta = { chiediScarta = true },
                )
            }
        }
        BarraCaricamento(stato.caricamento || stato.inCorso)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(vertical = 8.dp))
            if (mail == null) {
                if (stato.caricamento) Rotella()
            } else {
                // Solo per le mail da controllare; le altre non hanno nessun riquadro sul gruppo.
                if (mail.daControllare && !mail.sospetta) {
                    GruppoGiusto(
                        occupato = stato.inCorso,
                        onSi = vm::confermaGruppo,
                        onCambia = { mostraSposta = true },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    mail.oggetto.ifBlank { "(senza oggetto)" },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
                if (mail.urgente) SegnaleMail(Segnale.URGENTE, Modifier.padding(top = 4.dp))
                // L'avviso truffa in cima, subito sotto l'oggetto (docs/aspetto.md §3).
                if (mail.sospetta) {
                    AvvisoTruffa(inCorso = stato.inCorso, onNonETruffa = { chiediNonETruffa = true })
                }
                Mittenti(mail)
                mail.riassunto?.takeIf { it.isNotBlank() }?.let { InBreve(it, colore) }
                SelectionContainer(Modifier.padding(top = 16.dp)) {
                    Text(mail.corpo?.takeIf { it.isNotBlank() } ?: mail.anteprima, style = MaterialTheme.typography.bodyLarge)
                }
                if (mail.allegati.isNotEmpty()) Allegati(mail)
                // Con una risposta aperta lo dice già il suo riquadro.
                val rispostaAperta = risposta.bozza?.let { !it.chiusa } == true
                if (mail.rispostaData && !rispostaAperta && risposta.bozza?.stato != Bozza.INVIATA) {
                    Text(
                        "Hai già risposto.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (stato.daCacheDel != null) {
                    Riquadro("La bozza si vede solo quando il server di casa risponde.")
                } else {
                    RiquadroRisposta(vm.bozza, risposta, giaRisposto = mail.rispostaData)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        BarraRisposta(
            barra,
            onRifai = { mostraRifai = true },
            onInvia = vm.bozza::inviaDallaBarra,
            onPrepara = vm::preparaRisposta,
        )
    }

    FinestreRisposta(vm.bozza, risposta)
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
    if (mostraRifai) {
        DialogoRifai(
            onRifai = { istruzioni ->
                mostraRifai = false
                vm.bozza.rifaiDallaBarra(istruzioni, vm::rifaiBozza)
            },
            onAnnulla = { mostraRifai = false },
        )
    }
    if (chiediScarta) {
        DialogoConferma(
            titolo = "Scartare la risposta?",
            testo = "La risposta non verrà inviata. Potrai sempre chiederne una nuova.",
            conferma = "Scarta",
            onConferma = {
                chiediScarta = false
                vm.bozza.scarta()
            },
            onAnnulla = { chiediScarta = false },
            pericolosa = true,
        )
    }
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
    if (mostraSposta && mail != null) {
        FoglioSpostaIn(
            gruppi = stato.gruppi,
            attuale = mail.gruppoId,
            onScegli = { gruppo, impara ->
                mostraSposta = false
                vm.sposta(gruppo, impara)
            },
            onChiudi = { mostraSposta = false },
        )
    }
}

/**
 * "Da: rossi@esempio.it": il nome c'è già nell'intestazione, qui conta l'indirizzo
 * (è quello che tradisce una truffa). Solo se manca l'indirizzo, il nome.
 */
internal fun rigaDa(mittente: Indirizzo): String =
    "Da: " + mittente.indirizzo.trim().ifEmpty { mittente.nome?.trim().orEmpty() }

/**
 * "← (MR) Mario Rossi ⋮" e sotto "oggi alle 08:12 · [Clienti ▾]": il gruppo si
 * tocca per spostare la mail. [menu] riceve la funzione che chiude il menu.
 */
@Composable
private fun Intestazione(
    mail: Mail?,
    colore: ColoreGruppo,
    occupato: Boolean,
    onIndietro: () -> Unit,
    onSposta: () -> Unit,
    menu: @Composable (chiudi: () -> Unit) -> Unit,
) {
    IconeBarraDiStato(scure = !LocalTemaScuro.current)
    var menuAperto by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onIndietro) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                if (mail != null) {
                    CerchioIniziali(iniziali(mail.mittente.nome, mail.mittente.indirizzo), colore.base)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            mail.mittente.etichetta,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // "oggi 08:12", come nella guida: più spazio per il nome del gruppo.
                            val quando = quandoCompleto(mail.ricevutaIl).replace(" alle ", " ")
                            if (quando.isNotBlank()) {
                                Text(
                                    "$quando · ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Il tocco prende tutta l'altezza (48 dp), l'etichetta resta piccola.
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !occupato, role = Role.Button, onClickLabel = "Sposta in un altro gruppo", onClick = onSposta)
                                    .heightIn(min = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                EtichettaGruppo(mail.gruppo ?: "Nessun gruppo", colore, conFreccia = true)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menuAperto = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Altre azioni") }
                        DropdownMenu(expanded = menuAperto, onDismissRequest = { menuAperto = false }) {
                            menu { menuAperto = false }
                        }
                    }
                } else {
                    Text("Mail", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Le azioni rare (docs/aspetto.md §3). Niente "Segna come letta" in vista: aprire la mail la segna già. */
@Composable
private fun MenuMail(
    mail: Mail,
    risposta: eu.stgm.sol.ui.StatoBozza,
    chiudi: () -> Unit,
    onLetta: () -> Unit,
    onRispondere: () -> Unit,
    onSegnalaTruffa: () -> Unit,
    onPrepara: () -> Unit,
    onScarta: () -> Unit,
) {
    @Composable
    fun Voce(testo: String, azione: () -> Unit) {
        DropdownMenuItem(
            text = { Text(testo, style = MaterialTheme.typography.bodyLarge) },
            onClick = {
                chiudi()
                azione()
            },
            modifier = Modifier.heightIn(min = 52.dp),
        )
    }
    Voce(if (mail.letta) "Segna come da leggere" else "Segna come letta", onLetta)
    if (!mail.rispostaData) Voce(if (mail.daRispondere) "Non serve rispondere" else "Va risposta", onRispondere)
    // 16.3: meno in vista delle altre azioni, ma sempre a portata.
    if (!mail.sospetta) Voce("Segnala come possibile truffa", onSegnalaTruffa)
    val bozza = risposta.bozza
    val aperta = bozza != null && !bozza.chiusa
    if (bozza != null && aperta && !bozza.inPreparazione && !risposta.campiBloccati) Voce("Scarta la risposta", onScarta)
    // Per una mail senza barra in basso (sospetta, o che non chiede risposta) la risposta si chiede da qui.
    if (!aperta && barraPer(mail, risposta) == Barra.Nessuna && !risposta.caricamento && bozza?.stato != Bozza.INVIATA) {
        Voce("Prepara una risposta", onPrepara)
    }
}

/**
 * "È nel gruppo giusto? [Sì] [Cambia]": solo per le mail che Sol non è sicuro di aver
 * messo bene. Su una superficie neutra, col "?" ambra del segnale: non si confonde
 * con "In breve", che ha il colore del gruppo.
 */
@Composable
private fun GruppoGiusto(occupato: Boolean, onSi: () -> Unit, onCambia: () -> Unit, modifier: Modifier = Modifier) {
    Superficie(modifier) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = LocalSegni.current.controllare)
            Text(
                "È nel gruppo giusto?",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            PulsanteTesto("Sì", onSi, abilitato = !occupato)
            PulsanteTesto("Cambia", onCambia, abilitato = !occupato)
        }
    }
}

/** Da chi, a chi; per le PEC anche chi ha spedito la busta (15.8). */
@Composable
private fun Mittenti(mail: Mail) {
    val stile = MaterialTheme.typography.bodySmall
    val colore = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 6.dp)) {
        Text(rigaDa(mail.mittente), style = stile, color = colore)
        mail.pec?.let { pec ->
            Text(
                "Arrivata come PEC da: ${pec.mittenteBusta}" + (pec.gestore?.let { " (gestore $it)" } ?: ""),
                style = stile,
                color = colore,
            )
        }
        if (mail.destinatari.isNotEmpty()) {
            Text("A: ${mail.destinatari.joinToString(", ") { it.etichetta }}", style = stile, color = colore)
        }
        if (mail.cc.isNotEmpty()) {
            Text("Cc: ${mail.cc.joinToString(", ") { it.etichetta }}", style = stile, color = colore)
        }
        // La casella in cui è arrivata, se non si vede già tra i destinatari (per esempio un inoltro).
        mail.casella?.takeIf { c -> (mail.destinatari + mail.cc).none { it.indirizzo.equals(c, ignoreCase = true) } }?.let {
            Text("Arrivata nella casella $it", style = stile, color = colore)
        }
    }
}

/** "In breve", con la tinta leggera del colore del gruppo (al buio più smorzata, resta una tinta). */
@Composable
private fun InBreve(testo: String, colore: ColoreGruppo) {
    Surface(
        color = if (LocalTemaScuro.current) colore.scuroFondo.copy(alpha = 0.6f) else colore.chiaroFondo,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("In breve", style = MaterialTheme.typography.labelMedium, color = colore.testo)
            Text(testo, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Allegati(mail: Mail) {
    Column(Modifier.padding(top = 16.dp)) {
        Text("Allegati", style = MaterialTheme.typography.titleSmall)
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
}

/** Il testo del contratto (16.3): cosa fare prima di pagare o mandare documenti. */
const val AVVISO_TRUFFA =
    "Attenzione: questa mail potrebbe essere una truffa. Prima di pagare, cambiare coordinate bancarie o " +
        "mandare documenti, telefona al mittente a un numero che conosci già, non a quello scritto nella mail."

/**
 * Sopra "In breve" in una mail sospetta: chiaro e ben visibile, ma con i colori
 * degli avvisi dell'app, non da allarme. Il pulsante resta pieno e leggibile.
 */
@Composable
private fun AvvisoTruffa(inCorso: Boolean, onNonETruffa: () -> Unit) {
    RiquadroTruffa(AVVISO_TRUFFA, Modifier.padding(top = 14.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.CenterEnd) {
            PulsanteVuoto("Non è una truffa", onNonETruffa, abilitato = !inCorso, colore = LocalSegni.current.truffaTesto)
        }
    }
}
