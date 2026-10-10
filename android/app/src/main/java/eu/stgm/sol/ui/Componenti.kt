package eu.stgm.sol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.stgm.sol.ui.tema.LocalSegni
import java.time.Instant

/** Altezza minima dei pulsanti: si toccano anche coi guanti o di fretta. */
val ALTEZZA_TOCCO = 52.dp

@Composable
fun PulsantePieno(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true, pericoloso: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = abilitato,
        modifier = modifier.heightIn(min = ALTEZZA_TOCCO),
        colors = if (pericoloso) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) { Text(testo, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun PulsanteVuoto(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = abilitato, modifier = modifier.heightIn(min = ALTEZZA_TOCCO)) {
        Text(testo, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PulsanteTesto(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true, colore: Color? = null) {
    TextButton(onClick = onClick, enabled = abilitato, modifier = modifier.heightIn(min = ALTEZZA_TOCCO)) {
        // Spento si vede spento anche se di solito è rosso (per esempio "Scarta").
        Text(testo, style = MaterialTheme.typography.labelLarge, color = if (abilitato) colore ?: Color.Unspecified else Color.Unspecified)
    }
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

/** La modalità prova si vede sempre, in cima alla posta. */
@Composable
fun AvvisoModalitaProva(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("MODALITÀ PROVA", style = MaterialTheme.typography.labelMedium)
            Text("Sol per ora suggerisce e non sposta niente.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

enum class TipoSegno { URGENTE, DA_RISPONDERE, BOZZA_PRONTA, DA_CONTROLLARE }

/** Un segno piccolo su una mail: parole, non solo colori. */
@Composable
fun Segno(tipo: TipoSegno, modifier: Modifier = Modifier) {
    val segni = LocalSegni.current
    val (testo, fondo, colore) = when (tipo) {
        TipoSegno.URGENTE -> Triple("Urgente", segni.urgenteFondo, segni.urgenteTesto)
        TipoSegno.DA_RISPONDERE -> Triple("Da rispondere", segni.rispondereFondo, segni.rispondereTesto)
        TipoSegno.BOZZA_PRONTA -> Triple("Bozza pronta", segni.bozzaFondo, segni.bozzaTesto)
        TipoSegno.DA_CONTROLLARE -> Triple("Da controllare", segni.controllareFondo, segni.controllareTesto)
    }
    Surface(color = fondo, contentColor = colore, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            testo,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun TitoloSezione(testo: String, modifier: Modifier = Modifier) {
    Text(
        testo,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
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

/**
 * Un filtro o un gruppo da scegliere. Scelto = pieno col colore principale e la
 * spunta: si distingue anche al sole, non solo per una sfumatura.
 */
@Composable
fun ChipScelta(testo: String, scelto: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = scelto,
        onClick = onClick,
        label = { Text(testo, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (scelto) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
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
