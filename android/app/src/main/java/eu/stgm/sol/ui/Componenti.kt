package eu.stgm.sol.ui

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalTemaScuro
import eu.stgm.sol.ui.tema.Sol
import eu.stgm.sol.ui.tema.morbido
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tocchi di almeno 48 dp (§11). */
val ALTEZZA_TOCCO = 48.dp

/** I margini laterali di tutte le pagine (prototipo: 20 px). */
val MARGINE = 20.dp

// --- Forme e ombre ------------------------------------------------------------------

/**
 * L'ombra leggera delle schede (`0 1px 2px` e, in rilievo, `0 10px 28px` al 6%).
 * Nel tema scuro niente ombre: le schede si distinguono per la superficie (§8).
 */
@Composable
fun Modifier.ombra(forma: Shape, rilievo: Boolean = false): Modifier =
    if (LocalTemaScuro.current) {
        this
    } else if (rilievo) {
        shadow(10.dp, forma, clip = false, ambientColor = Color(0x22141414), spotColor = Color(0x2E141414))
    } else {
        shadow(1.dp, forma, clip = false, ambientColor = Color(0x14141414), spotColor = Color(0x1F141414))
    }

/** L'ombra colorata del pulsante principale (`0 8px 20px` del suo colore al 28%). */
@Composable
fun Modifier.ombraAccento(forma: Shape, attiva: Boolean = true): Modifier =
    if (LocalTemaScuro.current || !attiva) this else shadow(8.dp, forma, clip = false, ambientColor = Sol.colori.accento.copy(alpha = 0.28f), spotColor = Sol.colori.accento.copy(alpha = 0.45f))

/**
 * Le icone delle barre di Android: scure sul fondo chiaro, chiare al buio (anche
 * quando il tema scelto nelle Impostazioni non è quello del telefono).
 */
@Composable
fun IconeBarraDiStato(scure: Boolean) {
    val vista = LocalView.current
    DisposableEffect(scure) {
        val finestra = (vista.context as? Activity)?.window
        if (finestra != null && !vista.isInEditMode) {
            val controllo = WindowCompat.getInsetsController(finestra, vista)
            controllo.isAppearanceLightStatusBars = scure
            controllo.isAppearanceLightNavigationBars = scure
        }
        onDispose {}
    }
}

// --- Testate --------------------------------------------------------------------------

/** "Posta", "Riassunti": il titolo grande a sinistra e i pulsanti tondi a destra, alti 48. */
@Composable
fun TestataPagina(titolo: String, modifier: Modifier = Modifier, accanto: @Composable RowScope.() -> Unit = {}, azioni: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(titolo, style = Sol.testi.titoloPagina, color = Sol.colori.testo, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        accanto()
        Spacer(Modifier.weight(1f))
        azioni()
    }
}

/** Il pulsante tondo grigio (indietro, cerca, altre azioni): 44 di disegno, 48 di tocco. */
@Composable
fun PulsanteTondo(icona: ImageVector, descrizione: String, onClick: () -> Unit, modifier: Modifier = Modifier, dimensioneIcona: Dp = 20.dp) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = descrizione, onClick = onClick)
            .semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).background(Sol.colori.superficie2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icona, contentDescription = null, tint = Sol.colori.testoMedio, modifier = Modifier.size(dimensioneIcona))
        }
    }
}

/** Il pulsante tondo col testo (l'iniziale del nome, in alto a destra in Oggi). */
@Composable
fun PulsanteTondoTesto(testo: String, descrizione: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = descrizione, onClick = onClick)
            .semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).background(Sol.colori.superficie2, CircleShape), contentAlignment = Alignment.Center) {
            Text(testo, style = Sol.testi.forte, color = Sol.colori.testoMedio)
        }
    }
}

// --- Schede ------------------------------------------------------------------------------

/** Una scheda bianca (raggio 20, o 24 per le schede grandi) con l'ombra leggera. */
@Composable
fun Scheda(
    modifier: Modifier = Modifier,
    raggio: Dp = 20.dp,
    rilievo: Boolean = false,
    colore: Color = Sol.colori.superficie,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    val forma = RoundedCornerShape(raggio)
    Column(modifier.fillMaxWidth().ombra(forma, rilievo).clip(forma).background(colore)) { contenuto() }
}

/** "IL PUNTO DI OGGI", "SOL PROPONE": 12 / 700, maiuscolo. */
@Composable
fun EtichettaMaiuscola(testo: String, modifier: Modifier = Modifier, colore: Color = Sol.colori.tenue) {
    Text(testo.uppercase(), style = Sol.testi.etichetta, color = colore, modifier = modifier)
}

/** Il titolo di una sezione dentro una pagina ("Da fare", "Aspettano te"). */
@Composable
fun TitoloSezione(testo: String, modifier: Modifier = Modifier) {
    Text(testo, style = Sol.testi.titoloSezione, color = Sol.colori.testo, modifier = modifier.semantics { heading() })
}

/** Il nome di un gruppo di righe sopra una scheda ("Clienti 2", "Raccolte"). */
@Composable
fun TestaSezioneElenco(nome: String, modifier: Modifier = Modifier, colore: Color? = null, numero: Int? = null) {
    Row(modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        if (colore != null) {
            Box(Modifier.size(8.dp).background(colore, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(nome, style = Sol.testi.secondario, fontWeight = FontWeight.Bold, color = Sol.colori.testoMedio)
        if (numero != null) {
            Spacer(Modifier.width(8.dp))
            Text("$numero", style = Sol.testi.secondario, color = Sol.colori.moltoTenue)
        }
    }
}

/** La riga sottile tra due righe di un elenco, rientrata dopo il cerchio (68). */
@Composable
fun RigaSottile(rientro: Dp = 68.dp) {
    Box(Modifier.padding(start = rientro).fillMaxWidth().height(1.dp).background(Sol.colori.riga))
}

/** La barra di avanzamento: alta 6, binario grigio, riempimento accento. */
@Composable
fun BarraAvanzamento(frazione: Float, modifier: Modifier = Modifier) {
    val valore by animateFloatAsState(frazione.coerceIn(0f, 1f), morbido(), label = "avanzamento")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Sol.colori.traccia)) {
        Box(Modifier.fillMaxWidth(valore).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Sol.colori.accento))
    }
}

// --- Cerchi e pillole ------------------------------------------------------------------------

/** Il cerchio con le iniziali: fondo tenue del gruppo e iniziali scure del colore. */
@Composable
fun CerchioIniziali(testo: String, colore: ColoreGruppo?, modifier: Modifier = Modifier, dimensione: Dp = 40.dp) {
    val fondo = colore?.fondo ?: Sol.colori.neutro
    val inchiostro = colore?.testo ?: Sol.colori.testoMedio
    Box(modifier.size(dimensione).background(fondo, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            testo,
            color = inchiostro,
            fontWeight = FontWeight.ExtraBold,
            fontSize = when {
                dimensione >= 48.dp -> 16.sp
                dimensione >= 44.dp -> 15.sp
                else -> 14.sp
            },
            fontFamily = Sol.testi.forte.fontFamily,
            maxLines = 1,
        )
    }
}

/** Un cerchio (o un quadrato tondo) con un'icona. */
@Composable
fun CerchioIcona(icona: ImageVector, fondo: Color, tinta: Color, modifier: Modifier = Modifier, dimensione: Dp = 44.dp, dimensioneIcona: Dp = 22.dp, forma: Shape = CircleShape) {
    Box(modifier.size(dimensione).background(fondo, forma), contentAlignment = Alignment.Center) {
        Icon(icona, contentDescription = null, tint = tinta, modifier = Modifier.size(dimensioneIcona))
    }
}

/** Come appare una pillola d'azione nelle schede di Oggi. */
enum class StilePillola { PIENA, TENUE, TRUFFA }

/** La pillola d'azione ("Rivedi", "Inizia", "Guarda"): alta 44, raggio pieno, 15 / 700. */
@Composable
fun PillolaAzione(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, stile: StilePillola = StilePillola.TENUE, abilitata: Boolean = true) {
    val c = Sol.colori
    val (fondo, inchiostro) = when (stile) {
        StilePillola.PIENA -> c.accento to c.suAccento
        StilePillola.TENUE -> c.accentoTenue to c.accentoTesto
        StilePillola.TRUFFA -> c.superficie to c.truffaTesto
    }
    val forma = RoundedCornerShape(999.dp)
    Box(
        modifier
            .heightIn(min = ALTEZZA_TOCCO)
            .clip(forma)
            .clickable(enabled = abilitata, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .background(fondo, forma)
                .then(if (stile == StilePillola.TRUFFA) Modifier.border(1.5.dp, c.truffaBordo, forma) else Modifier)
                .alpha(if (abilitata) 1f else 0.5f)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(testo, style = Sol.testi.pulsante, color = inchiostro, maxLines = 1)
        }
    }
}

/**
 * Il pulsante principale della pagina: alto 56 (o 60), raggio 18, accento pieno,
 * 17 / 800 bianco, con l'ombra del suo colore.
 */
@Composable
fun PulsanteGrande(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    abilitato: Boolean = true,
    altezza: Dp = 56.dp,
) {
    val c = Sol.colori
    val forma = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(altezza)
            .ombraAccento(forma, abilitato)
            .clip(forma)
            .background(if (abilitato) c.accento else c.superficie2)
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val inchiostro = if (abilitato) c.suAccento else c.tenue
        Text(testo, style = Sol.testi.pulsanteGrande, color = inchiostro, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (icona != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icona, contentDescription = null, tint = inchiostro, modifier = Modifier.size(18.dp))
        }
    }
}

/** Il pulsante grande bianco col bordo ("Cambia", "Annulla" nei fogli). */
@Composable
fun PulsanteGrandeBianco(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    abilitato: Boolean = true,
    altezza: Dp = 56.dp,
    coloreTesto: Color = Sol.colori.testo,
) {
    val c = Sol.colori
    val forma = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(altezza)
            .clip(forma)
            .background(c.superficie)
            .border(1.5.dp, c.bordoForte, forma)
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .alpha(if (abilitato) 1f else 0.5f)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icona != null) {
            Icon(icona, contentDescription = null, tint = coloreTesto, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(testo, style = Sol.testi.pulsanteGrande, color = coloreTesto, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Un collegamento di testo d'accento (dentro le schede: "Riprova", "Segna tutte lette"). */
@Composable
fun PulsanteTesto(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true, colore: Color? = null) {
    TextButton(onClick = onClick, enabled = abilitato, modifier = modifier.heightIn(min = ALTEZZA_TOCCO)) {
        Text(testo, style = Sol.testi.pulsante, color = if (abilitato) colore ?: Sol.colori.accentoTesto else Sol.colori.moltoTenue)
    }
}

/** Al massimo UN segno per riga (§4). */
enum class Segno { TRUFFA, RISPOSTA_PRONTA }

/** Il segno di una mail in elenco: possibile truffa prima di tutto, poi risposta pronta. */
fun segnoPer(mail: Mail): Segno? = when {
    mail.sospetta -> Segno.TRUFFA
    mail.bozzaPronta -> Segno.RISPOSTA_PRONTA
    else -> null
}

@Composable
fun PillolaSegno(segno: Segno, modifier: Modifier = Modifier) {
    val c = Sol.colori
    val (fondo, inchiostro, icona, testo) = when (segno) {
        Segno.TRUFFA -> Quattro(c.truffa, Color.White, Icone.Truffa, "Possibile truffa")
        Segno.RISPOSTA_PRONTA -> Quattro(c.accentoTenue, c.accentoTesto, Icone.Matita, "Risposta pronta")
    }
    Row(
        modifier.background(fondo, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icona, contentDescription = null, tint = inchiostro, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(testo, style = Sol.testi.etichetta.copy(letterSpacing = 0.sp), color = inchiostro, maxLines = 1)
    }
}

private data class Quattro<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** La pillola del gruppo: fondo tenue, nome nel colore scuro del gruppo, freccia in giù. */
@Composable
fun PillolaGruppo(nome: String, colore: ColoreGruppo, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, conFreccia: Boolean = onClick != null, larghezzaMassima: Dp = 160.dp) {
    val forma = RoundedCornerShape(999.dp)
    Box(
        modifier
            .heightIn(min = ALTEZZA_TOCCO)
            .clip(forma)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Sposta in un altro gruppo", onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(36.dp).background(colore.fondo, forma).padding(start = 12.dp, end = if (conFreccia) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(nome, style = Sol.testi.secondario, fontWeight = FontWeight.Bold, color = colore.testo, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = larghezzaMassima))
            if (conFreccia) {
                Spacer(Modifier.width(4.dp))
                Icon(Icone.Giu, contentDescription = null, tint = colore.testo, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** I segmenti "Importanti | Tutte": binario grigio, scelta bianca con ombra leggera. */
@Composable
fun Segmenti(opzioni: List<String>, scelta: Int, onScegli: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Sol.colori
    Row(
        modifier.fillMaxWidth().background(c.superficie2, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        opzioni.forEachIndexed { i, testo ->
            val attivo = i == scelta
            val forma = RoundedCornerShape(11.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (attivo) Modifier.ombra(forma) else Modifier)
                    .clip(forma)
                    // Al buio la scelta è la superficie più chiara (niente ombre, §8).
                    .background(if (attivo) (if (c.scuro) c.bordoForte else c.superficie) else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onScegli(i) })
                    .semantics { contentDescription = if (attivo) "$testo, scelta" else testo },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    testo,
                    style = Sol.testi.medio,
                    fontWeight = if (attivo) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (attivo) c.testo else c.barraInattiva,
                )
            }
        }
    }
}

// --- Righe d'elenco ----------------------------------------------------------------------------

/** Una riga con icona, nome, valore attuale e freccia (Impostazioni, collegamenti di Oggi). */
@Composable
fun RigaVoce(
    titolo: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    valore: String? = null,
    coloreTitolo: Color = Sol.colori.testo,
    coloreIcona: Color = Sol.colori.testoMedio,
    finale: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icona != null) {
            CerchioIcona(icona, Sol.colori.neutro, coloreIcona, dimensione = 36.dp, dimensioneIcona = 19.dp, forma = RoundedCornerShape(11.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(titolo, style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = coloreTitolo, modifier = Modifier.weight(1f))
        if (valore != null) {
            Text(
                valore,
                style = Sol.testi.secondario,
                color = Sol.colori.tenue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp),
            )
        }
        if (finale != null) {
            finale()
        } else if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icone.Avanti, contentDescription = null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
        }
    }
}

/** Una riga calma per gli elenchi vuoti (§7). */
@Composable
fun RigaVuota(testo: String, modifier: Modifier = Modifier) {
    Text(testo, style = Sol.testi.medio, color = Sol.colori.tenue, modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 4.dp))
}

// --- Stati (§7) ---------------------------------------------------------------------------

private val ORA = DateTimeFormatter.ofPattern("H:mm")

/** "Senza collegamento · dati delle 9:40": la pillola grigia quando si vedono dati vecchi. */
@Composable
fun PillolaSenzaRete(daCacheDel: Long, modifier: Modifier = Modifier) {
    val ora = ORA.format(Instant.ofEpochMilli(daCacheDel).atZone(ZoneId.systemDefault()))
    Row(
        modifier.background(Sol.colori.superficie2, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Sol.colori.tenue, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text("Senza collegamento · dati delle $ora", style = Sol.testi.piccolo, fontWeight = FontWeight.SemiBold, color = Sol.colori.testoMedio)
    }
}

/**
 * Il problema di una lettura: con dati vecchi la pillola grigia (si consulta tutto);
 * senza, una frase chiara nella scheda con "Riprova".
 */
@Composable
fun AvvisoLettura(errore: String?, daCacheDel: Long?, onRiprova: () -> Unit, modifier: Modifier = Modifier) {
    if (errore == null) return
    if (daCacheDel != null) {
        Box(modifier.fillMaxWidth().clickable(onClickLabel = "Riprova", onClick = onRiprova), contentAlignment = Alignment.CenterStart) {
            PillolaSenzaRete(daCacheDel)
        }
    } else {
        ErroreConRiprova(errore, onRiprova, modifier)
    }
}

/** Una frase chiara con cosa fare, in una scheda, e "Riprova". */
@Composable
fun ErroreConRiprova(testo: String, onRiprova: (() -> Unit)?, modifier: Modifier = Modifier) {
    Scheda(modifier) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = if (onRiprova == null) 14.dp else 4.dp)) {
            Text(testo, style = Sol.testi.medio, color = Sol.colori.testo)
            if (onRiprova != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { PulsanteTesto("Riprova", onRiprova) }
            }
        }
    }
}

/** Un avviso dentro una pagina: grave = colori della truffa (errori d'invio, rifiuti). */
@Composable
fun Riquadro(
    testo: String,
    modifier: Modifier = Modifier,
    grave: Boolean = false,
    azione: String? = null,
    onAzione: (() -> Unit)? = null,
) {
    val c = Sol.colori
    val fondo = if (grave) c.truffaTenue else c.superficie2
    val inchiostro = if (grave) c.truffaTesto else c.testo
    Column(modifier.fillMaxWidth().background(fondo, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (azione != null) 2.dp else 12.dp)) {
        Text(testo, style = Sol.testi.medio, color = inchiostro, modifier = Modifier.padding(end = 8.dp))
        if (azione != null && onAzione != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                PulsanteTesto(azione, onAzione, colore = if (grave) c.truffaTesto else c.accentoTesto)
            }
        }
    }
}

/**
 * L'avviso "possibile truffa" (16.3) nella mail aperta: scheda rossa tenue, il testo
 * del contratto, e sotto [contenuto] (il pulsante "Non è una truffa").
 */
@Composable
fun RiquadroTruffa(testo: String, modifier: Modifier = Modifier, contenuto: @Composable ColumnScope.() -> Unit = {}) {
    val c = Sol.colori
    Column(modifier.fillMaxWidth().background(c.truffaTenue, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            CerchioIcona(Icone.Truffa, c.truffa, Color.White, dimensione = 36.dp, dimensioneIcona = 19.dp)
            Spacer(Modifier.width(12.dp))
            Text(testo, style = Sol.testi.medio, color = c.truffaTesto)
        }
        contenuto()
    }
}

// --- Fogli dal basso e finestre -------------------------------------------------------------

/** Un foglio dal basso nello stile di Sol: superficie bianca, raggio 28. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoglioSol(onChiudi: () -> Unit, titolo: String? = null, contenuto: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onChiudi,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Sol.colori.superficie,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = MARGINE, end = MARGINE, bottom = 20.dp)
                .navigationBarsPadding(),
        ) {
            if (titolo != null) {
                Text(titolo, style = Sol.testi.titoloSezione, color = Sol.colori.testo, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
            }
            contenuto()
        }
    }
}

/** Una conferma prima di un gesto che non si annulla: un foglio dal basso con due pulsanti. */
@Composable
fun DialogoConferma(
    titolo: String,
    testo: String,
    conferma: String,
    onConferma: () -> Unit,
    onAnnulla: () -> Unit,
    pericolosa: Boolean = false,
    annulla: String = "Annulla",
) {
    FoglioSol(onChiudi = onAnnulla, titolo = titolo) {
        Text(testo, style = Sol.testi.testo, color = Sol.colori.testoCorpo)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulsanteGrandeBianco(annulla, onAnnulla, Modifier.weight(1f))
            if (pericolosa) {
                PulsanteGrandeBianco(conferma, onConferma, Modifier.weight(1f), coloreTesto = Sol.colori.truffaTesto)
            } else {
                PulsanteGrande(conferma, onConferma, Modifier.weight(1f))
            }
        }
    }
}

/** I colori del messaggio in basso: scuro sul fondo chiaro, chiaro al buio; "Annulla" nell'accento. */
@Composable
fun coloreAzioneMessaggio(): Color = if (Sol.colori.scuro) Color(0xFFB23A0B) else Color(0xFFFF9A66)

/** Il messaggio breve in basso ("Spostata in Clienti · Annulla"). */
@Composable
fun MessaggioSol(dati: SnackbarData) {
    Snackbar(
        dati,
        shape = RoundedCornerShape(16.dp),
        containerColor = Sol.colori.testo,
        contentColor = Sol.colori.fondo,
        actionColor = coloreAzioneMessaggio(),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Un gesto non riuscito, detto in una finestra: non sfugge anche a pagina scorsa. */
@Composable
fun DialogoErrore(testo: String?, onChiudi: () -> Unit) {
    if (testo == null) return
    AlertDialog(
        onDismissRequest = onChiudi,
        containerColor = Sol.colori.superficie,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Non è riuscito", style = Sol.testi.titoloSezione, color = Sol.colori.testo) },
        text = { Text(testo, style = Sol.testi.testo, color = Sol.colori.testoCorpo) },
        confirmButton = { PulsanteGrande("Ho capito", onChiudi, altezza = 48.dp) },
    )
}

/** Una riga di pulsanti che va a capo se non ci sta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilaPulsanti(modifier: Modifier = Modifier, contenuto: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { contenuto() }
}

/** Il contenitore delle pagine: margini laterali 20 e la riga in alto (14 sotto la barra di stato). */
val PaddingPagina = PaddingValues(start = MARGINE, end = MARGINE, top = 14.dp, bottom = 24.dp)

/** Un'area toccabile senza effetti, per le righe che contengono già i loro pulsanti. */
@Composable
fun Toccabile(onClick: () -> Unit, modifier: Modifier = Modifier, contenuto: @Composable BoxScope.() -> Unit) {
    Box(modifier.clickable(onClick = onClick), content = contenuto)
}

// --- Testi --------------------------------------------------------------------------------------

/**
 * Le iniziali per il cerchio del mittente: "Mario Rossi" → "MR"; senza nome
 * dall'indirizzo ("mario.rossi@..." → "MR", "info@..." → "I").
 */
fun iniziali(nome: String?, indirizzo: String): String {
    val separatori = Regex("[^\\p{L}\\p{N}]+")
    val daNome = nome?.takeUnless { it.isBlank() || it.contains('@') }?.split(separatori)?.filter { it.isNotEmpty() }.orEmpty()
    val parole = daNome.ifEmpty { indirizzo.substringBefore('@').split(separatori).filter { it.isNotEmpty() } }
    return when (parole.size) {
        0 -> "?"
        1 -> parole[0].take(1).uppercase()
        else -> (parole.first().take(1) + parole.last().take(1)).uppercase()
    }
}

/** Testo con stile della guida: scorciatoia per le righe semplici. */
@Composable
fun TestoSol(testo: String, stile: TextStyle, colore: Color, modifier: Modifier = Modifier, maxRighe: Int = Int.MAX_VALUE) {
    Text(testo, style = stile, color = colore, modifier = modifier, maxLines = maxRighe, overflow = TextOverflow.Ellipsis)
}
