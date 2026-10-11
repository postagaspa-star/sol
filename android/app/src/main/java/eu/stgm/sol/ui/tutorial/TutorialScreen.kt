package eu.stgm.sol.ui.tutorial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.Indirizzo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.PreferenzeAspetto
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.EtichettaMaiuscola
import eu.stgm.sol.ui.FoglioSpostaIn
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaSegno
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteGrandeBianco
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.Segno
import eu.stgm.sol.ui.StilePillola
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.coloreAzioneMessaggio
import eu.stgm.sol.ui.dettaglio.AVVISO_TRUFFA
import eu.stgm.sol.ui.dettaglio.PillolaTono
import eu.stgm.sol.ui.dettaglio.SchedaRisposta
import eu.stgm.sol.ui.oggi.SchedaVoceFinta
import eu.stgm.sol.ui.oggi.rigaData
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.posta.DURATA_ANNULLA_MS
import eu.stgm.sol.ui.posta.GestiRighe
import eu.stgm.sol.ui.posta.RigaConGesti
import eu.stgm.sol.ui.posta.RigaMail
import eu.stgm.sol.ui.smista.GrigliaGruppi
import eu.stgm.sol.ui.smista.SchedaMail
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalMovimenti
import eu.stgm.sol.ui.tema.Sol
import eu.stgm.sol.ui.tema.morbido
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Oggi, risposta, gesti, Smista, truffa, fine (docs/aspetto.md §10). */
const val PASSI_TUTORIAL = 6

/**
 * Al primo avvio dopo l'abbinamento il tutorial parte da solo, una volta sola: si
 * segna visto appena si apre, così non torna nemmeno se l'app viene chiusa a metà
 * (si riapre da Impostazioni → "Come funziona Sol").
 */
@Composable
fun TutorialAlPrimoAvvio(preferenze: PreferenzeAspetto, onApri: () -> Unit) {
    val visto by preferenze.tutorialVisto.collectAsState(initial = true)
    val apri by rememberUpdatedState(onApri)
    LaunchedEffect(visto) {
        if (!visto) {
            preferenze.segnaTutorialVisto()
            apri()
        }
    }
}

// Dati finti: il tutorial non chiama mai il server e non invia niente.
private val GRUPPI_FINTI = listOf(
    Gruppo(1, "Clienti", ordine = 1, colore = "blu", descrizione = "Preventivi, ordini e lavori dei clienti"),
    Gruppo(2, "Fornitori", ordine = 2, colore = "verde", descrizione = "Offerte e consegne dei fornitori"),
    Gruppo(3, "Cantieri", ordine = 3, colore = "ambra", descrizione = "Sopralluoghi e verbali"),
    Gruppo(4, "Altro", ordine = 4, colore = "grigio"),
)

private val MAIL_GESTI = Mail(
    id = -1,
    mittente = Indirizzo("Luca Bianchi", "luca.bianchi@esempio.it"),
    oggetto = "Consegna materiale cantiere",
    riassunto = "Il materiale arriva giovedì mattina.",
    gruppoId = 2,
    gruppo = "Fornitori",
)

private val MAIL_FERMA = Mail(
    id = -3,
    mittente = Indirizzo("Giulia Verdi", "giulia.verdi@esempio.it"),
    oggetto = "Sopralluogo magazzino",
    riassunto = "Chiede se giovedì pomeriggio va bene.",
    letta = true,
    gruppoId = 1,
    gruppo = "Clienti",
)

private val MAIL_SMISTA = Mail(
    id = -2,
    mittente = Indirizzo("Studio Verdi", "info@studioverdi.esempio.it"),
    oggetto = "Richiesta di sopralluogo",
    riassunto = "Chiedono un sopralluogo per un capannone la prossima settimana.",
    gruppoId = 1,
    gruppo = "Clienti",
    sicurezza = 0.55,
)

private const val RISPOSTA_LUNGA =
    "Buongiorno signor Rossi,\n\nla ringrazio per la richiesta. Le preparo il preventivo per il capannone " +
        "e glielo mando entro venerdì, insieme ai tempi di consegna.\n\nCordiali saluti"
private const val RISPOSTA_BREVE = "Buongiorno, le mando il preventivo entro venerdì.\n\nCordiali saluti"
private const val RISPOSTA_FORMALE =
    "Gentile signor Rossi,\n\nla ringrazio per la cortese richiesta. Le trasmetterò il preventivo entro venerdì, " +
        "unitamente ai tempi di consegna.\n\nDistinti saluti"
/**
 * Il tutorial (docs/aspetto.md §10): a tutto schermo, stesso stile dell'app, una
 * frase grande per passo ed elementi veri da provare su dati finti. "Salta" in alto
 * a destra, i pallini in basso.
 */
@Composable
fun TutorialScreen(onFine: () -> Unit) {
    var passo by rememberSaveable { mutableIntStateOf(0) }
    val avanti: () -> Unit = { if (passo < PASSI_TUTORIAL - 1) passo++ else onFine() }
    val muove = LocalMovimenti.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Sol.colori.fondo)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (passo < PASSI_TUTORIAL - 1) PulsanteTesto("Salta", onFine, colore = Sol.colori.testoMedio)
        }
        AnimatedContent(
            targetState = passo,
            transitionSpec = {
                if (muove) fadeIn(tween(200)) togetherWith fadeOut(tween(200)) else EnterTransition.None togetherWith ExitTransition.None
            },
            label = "passo",
            modifier = Modifier.weight(1f),
        ) { p ->
            when (p) {
                0 -> PassoOggi(avanti)
                1 -> PassoRisposta(avanti)
                2 -> PassoGesti(avanti)
                3 -> PassoSmista(avanti)
                4 -> PassoTruffa(avanti)
                else -> PassoFine(onFine)
            }
        }
        Pallini(passo, Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 18.dp))
    }
}

/** I pallini di avanzamento: quello attuale è una pillola nell'accento. */
@Composable
private fun Pallini(passo: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Passo ${passo + 1} di $PASSI_TUTORIAL" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PASSI_TUTORIAL) { i ->
            val larghezza by animateDpAsState(if (i == passo) 22.dp else 8.dp, morbido(), label = "pallino")
            Box(
                Modifier
                    .height(8.dp)
                    .width(larghezza)
                    .background(if (i == passo) Sol.colori.accento else Sol.colori.traccia, CircleShape),
            )
        }
    }
}

/**
 * Un passo: la frase grande in alto (cambia mentre si prova), la prova sotto e i
 * pulsanti in basso, sotto il pollice.
 */
@Composable
private fun Passo(frase: String, basso: @Composable ColumnScope.() -> Unit = {}, prova: @Composable ColumnScope.() -> Unit) {
    val muove = LocalMovimenti.current
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = MARGINE, end = MARGINE, top = 8.dp, bottom = 12.dp),
        ) {
            AnimatedContent(
                targetState = frase,
                transitionSpec = {
                    if (muove) fadeIn(tween(200)) togetherWith fadeOut(tween(200)) else EnterTransition.None togetherWith ExitTransition.None
                },
                label = "frase",
            ) { testo ->
                Text(
                    testo,
                    style = Sol.testi.titoloSezione.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
                    color = Sol.colori.testo,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            prova()
        }
        Column(Modifier.fillMaxWidth().padding(start = MARGINE, end = MARGINE, bottom = 10.dp)) { basso() }
    }
}

// --- 1. Oggi --------------------------------------------------------------------------------

@Composable
private fun PassoOggi(avanti: () -> Unit) {
    val c = Sol.colori
    Passo("Ogni mattina qui trovi il punto della giornata e cosa fare.") {
        Text("Buongiorno, Manuel", style = Sol.testi.titoloPagina, color = c.testo)
        Text(rigaData(LocalDate.now(), 14), style = Sol.testi.medio, color = c.tenue, modifier = Modifier.padding(top = 4.dp))
        val forma = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(top = 18.dp).fillMaxWidth().ombra(forma, rilievo = true).background(c.superficie, forma).padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            EtichettaMaiuscola("Il punto di oggi")
            Text(
                "Rossi aspetta il preventivo del capannone. Bianchi conferma la consegna di giovedì.",
                style = Sol.testi.riassunto,
                color = c.testo,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        TitoloSezione("Da fare", Modifier.padding(top = 24.dp))
        SchedaVoceFinta(
            titolo = "Rispondi a Rossi",
            sotto = "Preventivo capannone · risposta pronta",
            iniziali = "MR",
            colore = ColoreGruppo.BLU,
            pulsante = "Rivedi",
            stile = StilePillola.PIENA,
            onClick = avanti,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

// --- 2. Risposta di Sol -------------------------------------------------------------------------

@Composable
private fun PassoRisposta(avanti: () -> Unit) {
    val c = Sol.colori
    var tono by rememberSaveable { mutableStateOf<String?>(null) }
    var inviata by rememberSaveable { mutableStateOf(false) }
    val testo = when (tono) {
        "breve" -> RISPOSTA_BREVE
        "formale" -> RISPOSTA_FORMALE
        else -> RISPOSTA_LUNGA
    }
    val frase = when {
        inviata -> "Fatto. Nell'app vera Sol ti chiede prima la conferma."
        tono != null -> "Ora tocca Invia risposta."
        else -> "Sol ti prepara la risposta. Cambiale il tono con un tocco."
    }
    Passo(
        frase,
        basso = {
            if (inviata) {
                PulsanteGrande("Avanti", avanti, Modifier.fillMaxWidth())
            } else {
                PulsanteGrande("Invia risposta", { inviata = true }, Modifier.fillMaxWidth(), icona = Icone.Invia)
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CerchioIniziali("MR", ColoreGruppo.BLU, dimensione = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Mario Rossi", style = Sol.testi.forte, color = c.testo)
                Text("Preventivo capannone", style = Sol.testi.secondario, color = c.tenue)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (inviata) {
            val forma = RoundedCornerShape(24.dp)
            Row(Modifier.fillMaxWidth().ombra(forma).background(c.superficie, forma).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                val muove = LocalMovimenti.current
                val spunta = remember { androidx.compose.animation.core.MutableTransitionState(!muove).apply { targetState = true } }
                AnimatedVisibility(visibleState = spunta, enter = scaleIn(morbido()) + fadeIn(morbido())) {
                    Box(Modifier.size(40.dp).background(c.accentoTenueForte, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icone.Spunta, contentDescription = null, tint = c.accento, modifier = Modifier.size(20.dp))
                    }
                }
                Text("Risposta inviata", style = Sol.testi.forte, color = c.testo, modifier = Modifier.padding(start = 12.dp))
            }
        } else {
            SchedaRisposta("Risposta di Sol", destinatari = "mario.rossi@esempio.it", onDestinatari = null) {
                Text(testo, style = Sol.testi.testo, color = c.testo, modifier = Modifier.fillMaxWidth().animateContentSize(morbido()))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillolaTono("Più breve", tono == "breve", true) { tono = "breve" }
                    PillolaTono("Più formale", tono == "formale", true) { tono = "formale" }
                }
            }
        }
    }
}

// --- 3. Gesti ------------------------------------------------------------------------------------

@Composable
private fun PassoGesti(avanti: () -> Unit) {
    val c = Sol.colori
    // 0 = scorri a destra, 1 = scorri a sinistra, 2 = spostata (c'è "Annulla"), 3 = fatto.
    var fase by rememberSaveable { mutableIntStateOf(0) }
    var letta by rememberSaveable { mutableStateOf(false) }
    var foglio by rememberSaveable { mutableStateOf(false) }
    var spostataIn by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(fase) {
        if (fase == 2) {
            delay(DURATA_ANNULLA_MS)
            fase = 3
        }
    }
    val mail = MAIL_GESTI.copy(letta = letta)
    val frase = when (fase) {
        0 -> "Scorri a destra per segnarla letta."
        1 -> "Ora scorri a sinistra per spostarla."
        2 -> "Hai cambiato idea? Tocca Annulla."
        else -> "Bene. Gli stessi gesti valgono in tutta la Posta."
    }
    Passo(
        frase,
        basso = {
            when {
                // "Spostata in X · Annulla" in basso, come nell'app.
                fase == 2 && spostataIn != null -> Row(
                    Modifier.fillMaxWidth().background(c.testo, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Spostata in $spostataIn", style = Sol.testi.medio, color = c.fondo, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                    PulsanteTesto("Annulla", {
                        spostataIn = null
                        fase = 3
                    }, colore = coloreAzioneMessaggio())
                }
                fase == 3 -> PulsanteGrande("Avanti", avanti, Modifier.fillMaxWidth())
            }
        },
    ) {
        Scheda {
            AnimatedVisibility(visible = spostataIn == null, exit = shrinkVertically(morbido()) + fadeOut(morbido())) {
                Column {
                    RigaConGesti(
                        mail,
                        colore = ColoreGruppo.VERDE,
                        gesti = GestiRighe(
                            spostaDi = if (foglio) mail.id else null,
                            inUscita = if (spostataIn != null) mail.id else null,
                            attivi = !foglio && fase < 2,
                            apriSposta = { if (fase >= 1) foglio = true },
                        ),
                        onClick = {},
                        onLetta = {
                            letta = !letta
                            if (fase == 0) fase = 1
                        },
                    )
                    RigaSottile()
                }
            }
            // Una seconda riga ferma: si vede la mail che esce dall'elenco.
            RigaMail(MAIL_FERMA, onClick = {}, colore = ColoreGruppo.BLU)
        }
    }
    if (foglio) {
        FoglioSpostaIn(
            gruppi = GRUPPI_FINTI,
            attuale = mail.gruppoId,
            onScegli = { id, _ ->
                foglio = false
                spostataIn = GRUPPI_FINTI.firstOrNull { it.id == id }?.nome
                fase = 2
            },
            onChiudi = { foglio = false },
        )
    }
}

// --- 4. Smista -----------------------------------------------------------------------------------

@Composable
private fun PassoSmista(avanti: () -> Unit) {
    var cambia by rememberSaveable { mutableStateOf(false) }
    val scorrimento = remember { Animatable(0f) }
    val ambito = rememberCoroutineScope()
    val muove = LocalMovimenti.current
    Passo(
        "Quando Sol non è sicuro, decidi tu: va bene o cambia.",
        basso = {
            if (!cambia) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulsanteGrandeBianco("Cambia", { cambia = true }, Modifier.weight(1f), icona = Icone.Indietro, altezza = 60.dp)
                    PulsanteGrande("Va bene", avanti, Modifier.weight(1f), icona = Icone.Spunta, altezza = 60.dp)
                }
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val larghezza = with(LocalDensity.current) { maxWidth.toPx() }
            SchedaMail(
                MAIL_SMISTA,
                ColoreGruppo.BLU,
                Modifier
                    .offset { IntOffset(scorrimento.value.roundToInt(), 0) }
                    .rotate(scorrimento.value / larghezza * 8f)
                    .draggable(
                        enabled = !cambia,
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { d -> ambito.launch { scorrimento.snapTo(scorrimento.value + d) } },
                        onDragStopped = {
                            val x = scorrimento.value
                            when {
                                x > larghezza * 0.28f -> {
                                    if (muove) scorrimento.animateTo(larghezza * 1.3f, tween(200, easing = FastOutSlowInEasing))
                                    avanti()
                                }
                                x < -larghezza * 0.28f -> {
                                    scorrimento.animateTo(0f, tween(if (muove) 200 else 0))
                                    cambia = true
                                }
                                else -> scorrimento.animateTo(0f, tween(if (muove) 200 else 0))
                            }
                        },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Va bene") { avanti(); true },
                            CustomAccessibilityAction("Cambia gruppo") { cambia = true; true },
                        )
                    },
            )
        }
        if (cambia) {
            GrigliaGruppi(GRUPPI_FINTI, MAIL_SMISTA, { avanti() }, Modifier.padding(top = 18.dp))
        }
    }
}

// --- 5. Possibile truffa -------------------------------------------------------------------------

@Composable
private fun PassoTruffa(avanti: () -> Unit) {
    val c = Sol.colori
    Passo(
        "Se vedi questo, telefona al mittente a un numero che conosci già prima di pagare.",
        basso = { PulsanteGrande("Ho capito", avanti, Modifier.fillMaxWidth()) },
    ) {
        Scheda {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
                CerchioIniziali("A", null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Amministrazione", style = Sol.testi.forte, fontWeight = FontWeight.ExtraBold, color = c.testo)
                    Text("Nuove coordinate bancarie", style = Sol.testi.medio, color = c.testoMedio)
                    PillolaSegno(Segno.TRUFFA, Modifier.padding(top = 4.dp))
                }
            }
        }
        RiquadroTruffa(AVVISO_TRUFFA, Modifier.padding(top = 14.dp))
    }
}

// --- 6. Fine -------------------------------------------------------------------------------------

@Composable
private fun PassoFine(onFine: () -> Unit) {
    val c = Sol.colori
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = MARGINE),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(96.dp).background(c.accentoTenueForte, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icone.Sole, contentDescription = null, tint = c.accento, modifier = Modifier.size(52.dp))
            }
            Text(
                "Sei pronto.",
                style = Sol.testi.titoloPagina,
                color = c.testo,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 22.dp).semantics { heading() },
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = MARGINE).heightIn(min = 60.dp)) {
            PulsanteGrande("Inizia", onFine, Modifier.fillMaxWidth())
        }
    }
}
