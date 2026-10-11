package eu.stgm.sol.ui.dettaglio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.CampiBozza
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.FoglioSol
import eu.stgm.sol.ui.GestoreBozza
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteGrandeBianco
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.RichiestaConferma
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.StatoBozza
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.quandoCompleto
import eu.stgm.sol.ui.tema.LocalMovimenti
import eu.stgm.sol.ui.tema.Sol
import eu.stgm.sol.ui.tema.morbido

/** L'avviso della conferma quando a quella mail si è già risposto da un altro programma (15.13). */
const val AVVISO_GIA_RISPOSTO = "Hai già risposto a questa mail da un altro programma: vuoi mandare anche questa?"

/** L'avviso in più della conferma per le risposte a una mail segnalata (16.3). */
const val AVVISO_TRUFFA_INVIO = "Stai rispondendo a una mail segnalata come possibile truffa."

/**
 * Le pillole di tono sotto la risposta di Sol (§5): ognuna rifà la risposta con la
 * sua istruzione, partendo da quella di adesso.
 */
enum class Tono(val etichetta: String, val istruzione: String) {
    BREVE("Più breve", "Rendila più breve: tieni solo l'essenziale."),
    FORMALE("Più formale", "Rendila più formale, dando del lei."),
    TEMPO("Chiedi più tempo", "Chiedi con garbo qualche giorno in più prima di dare una risposta completa."),
}

/** La barra fissa in basso della mail aperta (§5). */
sealed interface Barra {
    data object Nessuna : Barra

    /** Nessuna risposta, ma la mail va risposta: "Prepara la risposta". */
    data object Prepara : Barra

    /** Sol sta scrivendo: il pulsante c'è ma è spento. */
    data object Scrivendo : Barra

    /** Un controllo in corso al posto dell'invio ("Rileggo la risposta...", "Controllo se è partita..."). */
    data class Attesa(val testo: String) : Barra

    /** Con una risposta pronta: "Invia risposta". */
    data class Invia(val attivo: Boolean) : Barra
}

/**
 * Quale barra mostrare. [mail] null: un sollecito, che non risponde a una mail
 * ricevuta. Una mail sospetta senza risposta chiesta non ha barra: il gesto giusto
 * è "Non è una truffa", nell'avviso. Una mail che non chiede risposta neppure: si
 * risponde dal menu.
 */
fun barraPer(mail: Mail?, stato: StatoBozza): Barra {
    val bozza = stato.bozza
    if (stato.caricamento) return Barra.Nessuna
    if (stato.richiestaRisposta || stato.inAttesa || bozza?.inPreparazione == true) return Barra.Scrivendo
    if (bozza != null && !bozza.chiusa) {
        return when {
            stato.verificaInvio -> Barra.Attesa("Controllo se è partita...")
            stato.preparazioneConferma -> Barra.Attesa("Rileggo la risposta...")
            else -> Barra.Invia(attivo = !stato.campiBloccati && stato.campiVisibili?.testo?.isNotBlank() == true)
        }
    }
    if (mail == null || mail.sospetta || mail.rispostaData || bozza?.stato == Bozza.INVIATA) return Barra.Nessuna
    return if (mail.daRispondere) Barra.Prepara else Barra.Nessuna
}

/** La barra in basso: un pulsante pieno alto 56, tutta larghezza, su una sfumatura del fondo. */
@Composable
fun BarraRisposta(barra: Barra, onInvia: () -> Unit, onPrepara: () -> Unit, modifier: Modifier = Modifier, testoInvia: String = "Invia risposta") {
    if (barra == Barra.Nessuna) return
    val fondo = Sol.colori.fondo
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to fondo.copy(alpha = 0f), 0.3f to fondo))
            .navigationBarsPadding()
            .padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 22.dp),
    ) {
        when (barra) {
            Barra.Prepara -> PulsanteGrande("Prepara la risposta", onPrepara, Modifier.fillMaxWidth())
            Barra.Scrivendo -> PulsanteGrande("Sol sta scrivendo…", {}, Modifier.fillMaxWidth(), abilitato = false)
            is Barra.Attesa -> PulsanteGrande(barra.testo, {}, Modifier.fillMaxWidth(), abilitato = false)
            is Barra.Invia -> PulsanteGrande(testoInvia, onInvia, Modifier.fillMaxWidth(), icona = Icone.Invia, abilitato = barra.attivo)
            Barra.Nessuna -> Unit
        }
    }
}

/**
 * La risposta di Sol (§5): una scheda bianca in rilievo col sole, "a {destinatario}"
 * (toccando si aprono A, Cc e Oggetto), il testo da scrivere direttamente e le
 * pillole di tono. Le garanzie dell'invio stanno in [GestoreBozza], uguali a prima.
 */
@Composable
fun RispostaDiSol(
    gestore: GestoreBozza,
    stato: StatoBozza,
    giaRisposto: Boolean,
    onTono: (String?) -> Unit,
    modifier: Modifier = Modifier,
    titolo: String = "Risposta di Sol",
) {
    val bozza = stato.bozza
    // Il segno di spunta compare solo se la risposta parte mentre la si guarda.
    val apertaAllInizio = remember(bozza?.id) { bozza != null && !bozza.chiusa }
    // Mentre Sol riscrive il testo resta in vista, sbiadito.
    var ultimoTesto by remember(bozza?.id) { mutableStateOf("") }
    val rifacendo = stato.richiestaRisposta || stato.inAttesa || bozza?.inPreparazione == true
    // L'ultima pillola toccata resta evidenziata: si vede che tono si è chiesto.
    var tonoScelto by rememberSaveable(bozza?.id) { mutableStateOf<String?>(null) }
    val conTono: (String?) -> Unit = {
        tonoScelto = it
        onTono(it)
    }
    if (bozza != null && !bozza.inPreparazione && bozza.testo.isNotBlank()) ultimoTesto = bozza.testo
    // Niente animateContentSize qui: taglierebbe l'ombra della scheda.
    Column(modifier.fillMaxWidth()) {
        stato.errore?.let {
            Riquadro(it, grave = true, azione = "Chiudi", onAzione = gestore::consumaErrore, modifier = Modifier.padding(bottom = 10.dp))
        }
        when {
            stato.caricamento -> Unit
            rifacendo -> SchedaRisposta(titolo, destinatari = null, onDestinatari = null) {
                if (ultimoTesto.isNotBlank()) {
                    Text(ultimoTesto, style = Sol.testi.testo, color = Sol.colori.testo, modifier = Modifier.alpha(0.4f))
                } else {
                    Text("Sol sta scrivendo la risposta…", style = Sol.testi.testo, color = Sol.colori.tenue)
                }
                BarraCheScorre()
            }
            bozza == null -> Unit
            bozza.stato == Bozza.INVIATA -> RispostaInviata(bozza, conMovimento = apertaAllInizio)
            bozza.stato == Bozza.SCARTATA -> Text("La risposta è stata scartata.", style = Sol.testi.medio, color = Sol.colori.tenue)
            else -> ScriviRisposta(gestore, stato, bozza, giaRisposto, titolo, tonoScelto, conTono)
        }
    }
}

/** La barra sottile che scorre una volta sola mentre Sol riscrive (niente rotelle in loop). */
@Composable
private fun BarraCheScorre() {
    val avanzamento = remember { Animatable(0.05f) }
    val muove = LocalMovimenti.current
    LaunchedEffect(Unit) {
        if (muove) avanzamento.animateTo(0.92f, tween(9_000, easing = FastOutSlowInEasing)) else avanzamento.snapTo(0.6f)
    }
    LinearProgressIndicator(
        progress = { avanzamento.value },
        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        color = Sol.colori.accento,
        trackColor = Sol.colori.traccia,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** La scheda bianca in rilievo con l'intestazione "☀ Risposta di Sol · a rossi@esempio.it". */
@Composable
internal fun SchedaRisposta(
    titolo: String,
    destinatari: String?,
    onDestinatari: (() -> Unit)?,
    contenuto: @Composable () -> Unit,
) {
    val c = Sol.colori
    val forma = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().ombra(forma, rilievo = true).background(c.superficie, forma).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icone.Sole, contentDescription = null, tint = c.accento, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(titolo, style = Sol.testi.secondario, fontWeight = FontWeight.ExtraBold, color = c.accentoTesto, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            if (destinatari != null) {
                Text(
                    "a $destinatari",
                    style = Sol.testi.piccolo,
                    color = c.moltoTenue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (onDestinatari != null) Modifier.clickable(role = Role.Button, onClickLabel = "Cambia destinatari e oggetto", onClick = onDestinatari) else Modifier)
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        contenuto()
    }
}

@Composable
private fun RispostaInviata(bozza: Bozza, conMovimento: Boolean) {
    val c = Sol.colori
    val spunta = remember { MutableTransitionState(!conMovimento).apply { targetState = true } }
    val forma = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().ombra(forma).background(c.superficie, forma).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visibleState = spunta, enter = scaleIn(morbido()) + fadeIn(morbido())) {
            Box(Modifier.size(40.dp).background(c.accentoTenueForte, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(Icone.Spunta, contentDescription = null, tint = c.accento, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("Risposta inviata", style = Sol.testi.forte, color = c.testo)
            quandoCompleto(bozza.inviataIl).takeIf { it.isNotBlank() }?.let {
                Text(it, style = Sol.testi.piccolo, color = c.tenue)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriviRisposta(
    gestore: GestoreBozza,
    stato: StatoBozza,
    bozza: Bozza,
    giaRisposto: Boolean,
    titolo: String,
    tonoScelto: String?,
    onTono: (String?) -> Unit,
) {
    val c = Sol.colori
    val campi = stato.campiVisibili ?: return
    val abilitato = stato.scrivibile
    var apriCampi by rememberSaveable(bozza.id) { mutableStateOf(false) }
    var altro by rememberSaveable { mutableStateOf(false) }
    val cambia: (CampiBozza) -> Unit = gestore::scrivi

    if (giaRisposto) {
        Riquadro(
            "Hai già risposto a questa mail da un altro programma. Questa risposta parte solo se lo confermi.",
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
    SchedaRisposta(
        if (bozza.senzaTesto && titolo == "Risposta di Sol") "Risposta" else titolo,
        destinatari = campi.a.ifBlank { "(nessuno)" },
        onDestinatari = if (abilitato) ({ apriCampi = !apriCampi }) else null,
    ) {
        if (bozza.stato == Bozza.ERRORE && !stato.verificaInvio) {
            Text(
                "L'ultimo invio non è riuscito: ${bozza.errore ?: "motivo non indicato"}",
                style = Sol.testi.secondario,
                fontWeight = FontWeight.SemiBold,
                color = c.truffaTesto,
            )
        }
        if (apriCampi) {
            CampoScritto("A", campi.a, abilitato, KeyboardType.Email) { cambia(campi.copy(a = it)) }
            CampoScritto("Cc", campi.cc, abilitato, KeyboardType.Email) { cambia(campi.copy(cc = it)) }
            CampoScritto("Oggetto", campi.oggetto, abilitato, KeyboardType.Text) { cambia(campi.copy(oggetto = it)) }
        }
        // Sol ha finito ma non ha scritto niente: non è un invio fallito, è una risposta da rifare (o da scrivere qui).
        if (bozza.senzaTesto && !stato.inModifica) {
            Riquadro(GestoreBozza.SENZA_TESTO + (bozza.errore?.let { " $it" } ?: ""), azione = "Riprova a prepararla", onAzione = { gestore.rifai(null) })
        }
        BasicTextField(
            value = campi.testo,
            onValueChange = { cambia(campi.copy(testo = it)) },
            enabled = abilitato,
            textStyle = Sol.testi.testo.copy(color = c.testo),
            cursorBrush = SolidColor(c.accento),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { campo ->
                Box {
                    if (campi.testo.isEmpty()) Text("Scrivi qui la risposta", style = Sol.testi.testo, color = c.moltoTenue)
                    campo()
                }
            },
        )
        if (stato.modificheNonSalvate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Modifiche non salvate", style = Sol.testi.piccolo, color = c.tenue, modifier = Modifier.weight(1f))
                PulsanteTesto("Annulla", gestore::annullaModifica, abilitato = abilitato)
                PulsanteTesto("Salva", { gestore.salvaModifica() }, abilitato = abilitato)
            }
        }
        // Le pillole sono alte 38 ma si toccano su 48: tra le righe basta lo spazio del tocco.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tono.entries.forEach { tono ->
                PillolaTono(tono.etichetta, scelta = tonoScelto == tono.istruzione, abilitata = abilitato) { onTono(tono.istruzione) }
            }
            PillolaTono("Altro…", scelta = altro, abilitata = abilitato) { altro = true }
        }
    }
    if (altro) {
        FoglioAltro(onRifai = { istruzioni ->
            altro = false
            onTono(istruzioni)
        }, onAnnulla = { altro = false })
    }
}

/** Una pillola di tono: alta 38, bordo sottile; scelta = accento tenue. */
@Composable
internal fun PillolaTono(testo: String, scelta: Boolean, abilitata: Boolean, onClick: () -> Unit) {
    val c = Sol.colori
    val forma = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(forma)
            .clickable(enabled = abilitata, role = Role.Button, onClick = onClick)
            .semantics { selected = scelta },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            testo,
            style = Sol.testi.secondario,
            fontWeight = FontWeight.Bold,
            color = if (scelta) c.accentoTestoForte else c.testoMedio,
            modifier = Modifier
                .height(38.dp)
                .background(if (scelta) c.accentoTenueForte else c.superficie, forma)
                .border(1.5.dp, if (scelta) c.bordoScelto else c.traccia, forma)
                .alpha(if (abilitata) 1f else 0.5f)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun CampoScritto(etichetta: String, valore: String, abilitato: Boolean, tastiera: KeyboardType, onCambia: (String) -> Unit) {
    OutlinedTextField(
        value = valore,
        onValueChange = onCambia,
        label = { Text(etichetta) },
        keyboardOptions = KeyboardOptions(keyboardType = tastiera),
        enabled = abilitato,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sol.colori.accento, unfocusedBorderColor = Sol.colori.traccia),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Altro…": come rifarla, a parole (o dettata). Vuoto = Sol la riscrive come crede. */
@Composable
private fun FoglioAltro(onRifai: (String?) -> Unit, onAnnulla: () -> Unit) {
    var istruzioni by rememberSaveable { mutableStateOf("") }
    FoglioSol(onChiudi = onAnnulla, titolo = "Come la vuoi?") {
        OutlinedTextField(
            value = istruzioni,
            onValueChange = { istruzioni = it },
            placeholder = { Text("Esempio: digli che giovedì va bene") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sol.colori.accento, unfocusedBorderColor = Sol.colori.traccia),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Come rifare la risposta" },
            minLines = 3,
        )
        Spacer(Modifier.height(16.dp))
        PulsanteGrande("Rifai la risposta", { onRifai(istruzioni.trim().ifEmpty { null }) }, Modifier.fillMaxWidth())
    }
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
                "Se vuoi tenerle, scegli invece un tono (per esempio «Più breve»).",
            conferma = "Rifalla da zero",
            onConferma = gestore::confermaDaZero,
            onAnnulla = gestore::annullaDaZero,
            pericolosa = true,
        )
    }
    stato.confermaInvio?.let { richiesta ->
        FoglioInvio(richiesta, onConferma = gestore::confermaInvio, onAnnulla = gestore::annullaInvio)
    }
}

/**
 * La conferma esplicita, in un foglio dal basso (§5): chi riceve, cosa, come comincia,
 * e che parte davvero. Tutto viene dalla bozza appena riletta: è quella versione che
 * parte (15.11).
 */
@Composable
fun FoglioInvio(richiesta: RichiestaConferma, onConferma: () -> Unit, onAnnulla: () -> Unit) {
    val c = Sol.colori
    val t = Sol.testi
    FoglioSol(onChiudi = onAnnulla, titolo = if (richiesta.cambiata) "La risposta è cambiata" else "Inviare questa mail?") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (richiesta.cambiata) {
                Riquadro("È cambiata dopo che l'hai controllata: ricontrollala prima di inviarla.", grave = true)
            }
            if (richiesta.sospetta) RiquadroTruffa(AVVISO_TRUFFA_INVIO)
            if (richiesta.giaRisposto) Riquadro(AVVISO_GIA_RISPOSTO, grave = true)
            RigaConferma("A", richiesta.a.joinToString(", "))
            if (richiesta.cc.isNotEmpty()) RigaConferma("Cc", richiesta.cc.joinToString(", "))
            RigaConferma("Oggetto", richiesta.oggetto.ifBlank { "(senza oggetto)" })
            RigaConferma("Comincia così", "«${richiesta.primaRiga}»")
            Text("La mail parte subito e non si può ritirare.", style = t.secondario, color = c.tenue, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulsanteGrandeBianco("Annulla", onAnnulla, Modifier.weight(1f))
            PulsanteGrande(
                when {
                    richiesta.sospetta -> "Invia lo stesso"
                    richiesta.giaRisposto -> "Manda anche questa"
                    else -> "Invia"
                },
                onConferma,
                Modifier.weight(1.3f),
            )
        }
    }
}

@Composable
private fun RigaConferma(etichetta: String, valore: String) {
    Column {
        Text(etichetta, style = Sol.testi.piccolo, fontWeight = FontWeight.SemiBold, color = Sol.colori.tenue)
        Text(valore, style = Sol.testi.testo, fontWeight = FontWeight.SemiBold, color = Sol.colori.testo)
    }
}
