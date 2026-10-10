package eu.stgm.sol.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalSegni
import eu.stgm.sol.ui.tema.LocalTemaScuro
import java.time.Instant

/** Altezza minima dei pulsanti: si toccano anche coi guanti o di fretta. */
val ALTEZZA_TOCCO = 52.dp

/**
 * Il pulsante principale (Invia, Chiedi, Collega). Etichetta grande e in grassetto:
 * il bianco sull'arancio del tema scuro regge solo come testo grande (docs/aspetto.md §1).
 */
@Composable
fun PulsantePieno(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    abilitato: Boolean = true,
    pericoloso: Boolean = false,
    icona: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = abilitato,
        modifier = modifier.heightIn(min = ALTEZZA_TOCCO),
        colors = if (pericoloso) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(testo, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (icona != null) Icon(icona, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}

/** [colore]: il testo, per un pulsante dentro un riquadro colorato (per esempio l'avviso truffa). */
@Composable
fun PulsanteVuoto(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true, colore: Color? = null) {
    OutlinedButton(
        onClick = onClick,
        enabled = abilitato,
        modifier = modifier.heightIn(min = ALTEZZA_TOCCO),
        border = BorderStroke(1.dp, if (abilitato) colore ?: MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(testo, style = MaterialTheme.typography.labelLarge, color = if (abilitato) colore ?: LocalSegni.current.accentoTesto else Color.Unspecified)
    }
}

@Composable
fun PulsanteTesto(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true, colore: Color? = null) {
    TextButton(onClick = onClick, enabled = abilitato, modifier = modifier.heightIn(min = ALTEZZA_TOCCO)) {
        // Spento si vede spento anche se di solito è rosso (per esempio "Scarta"). Di base l'accento
        // "da testo": al buio quello chiaro, leggibile anche sul fondo delle finestre.
        Text(testo, style = MaterialTheme.typography.labelLarge, color = if (abilitato) colore ?: LocalSegni.current.accentoTesto else Color.Unspecified)
    }
}

/**
 * Le icone della barra di stato di Android: chiare sulla fascia calda, scure sulle
 * pagine chiare. Ogni schermata dice cosa c'è sotto.
 */
@Composable
fun IconeBarraDiStato(scure: Boolean) {
    val vista = LocalView.current
    DisposableEffect(scure) {
        val finestra = (vista.context as? Activity)?.window
        if (finestra != null && !vista.isInEditMode) {
            WindowCompat.getInsetsController(finestra, vista).isAppearanceLightStatusBars = scure
        }
        onDispose {}
    }
}

/**
 * La fascia calda in alto (docs/aspetto.md §1): sfumatura da sinistra a destra,
 * titolo bianco grande. Passa sotto la barra di stato. [indietro]: la freccia.
 */
@Composable
fun FasciaCalda(
    titolo: String,
    modifier: Modifier = Modifier,
    indietro: (() -> Unit)? = null,
    azioni: @Composable RowScope.() -> Unit = {},
) {
    val segni = LocalSegni.current
    IconeBarraDiStato(scure = false)
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(segni.fasciaInizio, segni.fasciaFine))),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = if (indietro == null) 16.dp else 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (indietro != null) {
                    IconButton(onClick = indietro) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                }
                // I titoli lunghi ("Niente resta indietro") un po' più piccoli: interi anche a 360 dp.
                Text(
                    titolo,
                    style = if (titolo.length <= 14) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    // Grassetto: bianco sull'arancio regge come testo grande (docs/aspetto.md §1).
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                azioni()
            }
        }
    }
}

/** Una superficie (scheda) della guida: bianca sul fondo caldo, col bordo solo nel tema chiaro. */
@Composable
fun Superficie(modifier: Modifier = Modifier, contenuto: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        border = if (LocalTemaScuro.current) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) { contenuto() }
}

/** Un riquadro di avviso. [grave]: colori d'errore. */
@Composable
fun Riquadro(
    testo: String,
    modifier: Modifier = Modifier,
    grave: Boolean = false,
    icona: ImageVector = if (grave) Icons.Filled.Warning else Icons.Filled.Info,
    azione: String? = null,
    onAzione: (() -> Unit)? = null,
) {
    val fondo = if (grave) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val testoColore = if (grave) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = fondo, contentColor = testoColore, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icona, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp))
                Text(testo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
            if (azione != null && onAzione != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onAzione, modifier = Modifier.heightIn(min = ALTEZZA_TOCCO)) {
                        Text(azione, style = MaterialTheme.typography.labelLarge, color = testoColore)
                    }
                }
            }
        }
    }
}

/**
 * Il problema di una lettura: se ci sono dati vecchi lo dice ("stai vedendo gli
 * ultimi dati di ieri alle 18:02"), così Manuel sa che cosa sta guardando.
 */
@Composable
fun AvvisoLettura(errore: String?, daCacheDel: Long?, onRiprova: () -> Unit, modifier: Modifier = Modifier) {
    if (errore == null) return
    val testo = if (daCacheDel != null) {
        "$errore\nStai vedendo gli ultimi dati, di ${quandoDaIstante(Instant.ofEpochMilli(daCacheDel))}."
    } else {
        errore
    }
    Riquadro(testo, modifier, grave = true, azione = "Riprova", onAzione = onRiprova)
}

/**
 * Un avviso di possibile truffa (16.3): colori propri, ben visibili ma non da
 * allarme. [contenuto] sotto il testo (per esempio il pulsante "Non è una truffa").
 */
@Composable
fun RiquadroTruffa(testo: String, modifier: Modifier = Modifier, contenuto: @Composable () -> Unit = {}) {
    val segni = LocalSegni.current
    Surface(
        color = segni.truffaFondo,
        contentColor = segni.truffaTesto,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = segni.truffaIcona,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp).size(24.dp),
                )
                Text(testo, style = MaterialTheme.typography.bodyLarge)
            }
            contenuto()
        }
    }
}

/** La modalità prova: una striscia sottile ambra in cima alla Posta, non in ogni mail. */
@Composable
fun StrisciaProva(modifier: Modifier = Modifier) {
    val segni = LocalSegni.current
    Text(
        "Prova: Sol suggerisce e non sposta niente",
        style = MaterialTheme.typography.labelMedium,
        color = segni.provaTesto,
        maxLines = 2,
        modifier = modifier
            .fillMaxWidth()
            .background(segni.provaFondo)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

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

/** Il cerchio col colore del gruppo e le iniziali bianche. */
@Composable
fun CerchioIniziali(testo: String, colore: Color, modifier: Modifier = Modifier, dimensione: Dp = 40.dp) {
    Box(modifier.size(dimensione).background(colore, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            testo,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (dimensione >= 40.dp) 16.sp else 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * L'etichetta di un gruppo nel suo colore. [conFreccia]: si tocca per spostare la
 * mail (nel dettaglio).
 */
@Composable
fun EtichettaGruppo(nome: String, colore: ColoreGruppo, modifier: Modifier = Modifier, conFreccia: Boolean = false) {
    Row(
        modifier
            .background(colore.fondo, RoundedCornerShape(8.dp))
            .padding(start = 8.dp, end = if (conFreccia) 4.dp else 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Un nome lungo si accorcia, la freccia resta sempre in vista.
        Text(
            nome,
            style = MaterialTheme.typography.labelSmall,
            color = colore.testo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (conFreccia) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colore.testo, modifier = Modifier.size(18.dp))
        }
    }
}

/** I segnali di una mail, dal più importante (docs/aspetto.md §2). */
enum class Segnale { TRUFFA, URGENTE, DA_CONTROLLARE, RISPOSTA_PRONTA }

/** I segnali da mostrare su una riga: nell'ordine della guida, al massimo [massimo]. */
fun segnaliPer(mail: Mail, massimo: Int = 2): List<Segnale> = buildList {
    if (mail.sospetta) add(Segnale.TRUFFA)
    if (mail.urgente) add(Segnale.URGENTE)
    if (mail.daControllare) add(Segnale.DA_CONTROLLARE)
    if (mail.bozzaPronta) add(Segnale.RISPOSTA_PRONTA)
}.take(massimo)

/** Come lo legge il lettore dello schermo. */
fun Segnale.parole(): String = when (this) {
    Segnale.TRUFFA -> "Possibile truffa"
    Segnale.URGENTE -> "Urgente"
    Segnale.DA_CONTROLLARE -> "Da controllare"
    Segnale.RISPOSTA_PRONTA -> "Risposta pronta"
}

/** Un segnale: sempre simbolo + parola, mai solo un colore. */
@Composable
fun SegnaleMail(segnale: Segnale, modifier: Modifier = Modifier) {
    val segni = LocalSegni.current
    val stile = MaterialTheme.typography.labelSmall
    when (segnale) {
        // L'unica etichetta piena: non si confonde con "urgente", che è solo testo rosso.
        Segnale.TRUFFA -> Row(
            modifier
                .background(segni.truffaPieno, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = segni.suTruffaPieno, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("Possibile truffa", style = stile, fontWeight = FontWeight.Bold, color = segni.suTruffaPieno)
        }
        Segnale.URGENTE -> SegnaleTesto("!", "urgente", segni.urgente, modifier)
        Segnale.DA_CONTROLLARE -> SegnaleTesto("?", "da controllare", segni.controllare, modifier)
        Segnale.RISPOSTA_PRONTA -> Row(modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = segni.accentoTesto, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("risposta pronta", style = stile, color = segni.accentoTesto)
        }
    }
}

@Composable
private fun SegnaleTesto(simbolo: String, parola: String, colore: Color, modifier: Modifier) {
    Row(modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(simbolo, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = colore)
        Spacer(Modifier.width(4.dp))
        Text(parola, style = MaterialTheme.typography.labelSmall, color = colore)
    }
}

@Composable
fun TitoloSezione(testo: String, modifier: Modifier = Modifier) {
    Text(
        testo,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun Vuoto(testo: String, modifier: Modifier = Modifier) {
    Text(
        testo,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 8.dp),
    )
}

@Composable
fun BarraCaricamento(visibile: Boolean, modifier: Modifier = Modifier) {
    if (visibile) LinearProgressIndicator(modifier.fillMaxWidth()) else Box(modifier.fillMaxWidth().heightIn(min = 4.dp))
}

@Composable
fun Rotella(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Una conferma prima di un gesto che non si annulla. */
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
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(titolo) },
        text = { Text(testo, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { PulsantePieno(conferma, onConferma, pericoloso = pericolosa) },
        dismissButton = { PulsanteTesto(annulla, onAnnulla) },
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

/** Un gesto non riuscito, detto in una finestra: non sfugge anche a pagina scorsa. */
@Composable
fun DialogoErrore(testo: String?, onChiudi: () -> Unit) {
    if (testo == null) return
    AlertDialog(
        onDismissRequest = onChiudi,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("Non è riuscito") },
        text = { Text(testo, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { PulsantePieno("Ho capito", onChiudi) },
    )
}

/** Chi apre le Impostazioni (la rotella in alto in ogni scheda). null = nessuna rotella. */
val LocalApriImpostazioni = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun PulsanteImpostazioni() {
    val apri = LocalApriImpostazioni.current ?: return
    IconButton(onClick = apri) { Icon(Icons.Filled.Settings, contentDescription = "Impostazioni") }
}

/**
 * Riga discreta quando l'app parla col server in chiaro (http, solo in casa):
 * va bene per l'installazione, poi si passa all'indirizzo https di Tailscale.
 */
@Composable
fun RigaNonProtetto(modifier: Modifier = Modifier) {
    Text(
        "Collegamento di casa non protetto",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
