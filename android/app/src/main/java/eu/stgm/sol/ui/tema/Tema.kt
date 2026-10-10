package eu.stgm.sol.ui.tema

import android.animation.ValueAnimator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// I colori di docs/aspetto.md (v1.3): accento caldo "sole" e sfondi caldi. I
// contrasti sono quelli della guida: testo normale almeno 4,5:1, testo grande e
// icone almeno 3:1. Il bianco sul pulsante scuro (#EA580C) fa 3,56:1: per questo
// le etichette dei pulsanti pieni sono grandi e in grassetto (v. PulsantePieno).
private val Chiaro = lightColorScheme(
    primary = Color(0xFFC2410C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF78584A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5E8DF),
    onSecondaryContainer = Color(0xFF3B2A22),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = Color(0xFFFFFBF7),
    onBackground = Color(0xFF1C1917),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF1C1917),
    surfaceVariant = Color(0xFFF1E9E2),
    onSurfaceVariant = Color(0xFF57534E),
    outline = Color(0xFF78716C),
    outlineVariant = Color(0xFFE7DED6),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFBF3EC),
    surfaceContainerHigh = Color(0xFFF6EDE5),
    surfaceContainerHighest = Color(0xFFF1E7DE),
    inverseSurface = Color(0xFF292524),
    inverseOnSurface = Color(0xFFFAFAF9),
    inversePrimary = Color(0xFFFB923C),
)

private val Scuro = darkColorScheme(
    primary = Color(0xFFEA580C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7C2D12),
    onPrimaryContainer = Color(0xFFFED7AA),
    secondary = Color(0xFFD6BFB2),
    onSecondary = Color(0xFF3B2A22),
    secondaryContainer = Color(0xFF3A2E27),
    onSecondaryContainer = Color(0xFFF2E3DA),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = Color(0xFF14110F),
    onBackground = Color(0xFFF5F0EB),
    surface = Color(0xFF14110F),
    onSurface = Color(0xFFF5F0EB),
    surfaceVariant = Color(0xFF2E2622),
    onSurfaceVariant = Color(0xFFD6CCC4),
    outline = Color(0xFFA8A29E),
    outlineVariant = Color(0xFF3F3632),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    // Errori calmi anche al buio: niente blocco rosso pieno.
    errorContainer = Color(0xFF4A1F1D),
    onErrorContainer = Color(0xFFFECACA),
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = Color(0xFF1F1A17),
    surfaceContainer = Color(0xFF241E1B),
    surfaceContainerHigh = Color(0xFF2B2420),
    surfaceContainerHighest = Color(0xFF352C27),
    inverseSurface = Color(0xFFE7E5E4),
    inverseOnSurface = Color(0xFF1C1917),
    // "Annulla" nei messaggi in basso (fondo chiaro): più scuro dell'accento per restare leggibile.
    inversePrimary = Color(0xFF9A3412),
)

/** Testo più grande della base Material: si legge col telefono in mano, in piedi. */
private val Tipografia = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = base.bodySmall.copy(fontSize = 15.sp, lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** I colori dell'accento e dei segnali (docs/aspetto.md §1), per tema. */
@Immutable
data class ColoriSegni(
    /** La fascia calda in alto: sfumatura da sinistra a destra, titolo bianco. */
    val fasciaInizio: Color,
    val fasciaFine: Color,
    /** Scheda o filtro scelto, icona attiva della barra in basso. */
    val attivo: Color,
    /** Il testo sopra [attivo]. */
    val suAttivo: Color,
    /** L'accento usato come testo o icona (risposta pronta, pallino delle non lette). */
    val accentoTesto: Color,
    /** Sfondo chiaro dell'accento (gesto "letta", indicatore della barra in basso). */
    val accentoTenue: Color,
    /** Urgente: punto esclamativo e parola in rosso, senza riempimento. */
    val urgente: Color,
    /** Da controllare: punto interrogativo e parola in ambra. */
    val controllare: Color,
    /** Possibile truffa: etichetta piena rossa, testo bianco. */
    val truffaPieno: Color,
    val suTruffaPieno: Color,
    /** La striscia sottile della modalità prova. */
    val provaFondo: Color,
    val provaTesto: Color,
    /** L'avviso "possibile truffa" nel dettaglio: chiaro ma calmo (niente rosso pieno). */
    val truffaFondo: Color,
    val truffaTesto: Color,
    val truffaIcona: Color,
)

private val SegniChiaro = ColoriSegni(
    fasciaInizio = Color(0xFFEA580C), fasciaFine = Color(0xFFC2410C),
    attivo = Color(0xFFC2410C), suAttivo = Color(0xFFFFFFFF),
    accentoTesto = Color(0xFFC2410C), accentoTenue = Color(0xFFFFEDD5),
    urgente = Color(0xFFB91C1C), controllare = Color(0xFFB45309),
    truffaPieno = Color(0xFFB91C1C), suTruffaPieno = Color(0xFFFFFFFF),
    provaFondo = Color(0xFFFEF3C7), provaTesto = Color(0xFF78350F),
    truffaFondo = Color(0xFFFDE8E6), truffaTesto = Color(0xFF450A0A), truffaIcona = Color(0xFFB91C1C),
)

private val SegniScuro = ColoriSegni(
    fasciaInizio = Color(0xFF9A3412), fasciaFine = Color(0xFF7C2D12),
    attivo = Color(0xFFFB923C), suAttivo = Color(0xFF1C0D02),
    accentoTesto = Color(0xFFFB923C), accentoTenue = Color(0xFF431407),
    urgente = Color(0xFFF87171), controllare = Color(0xFFFBBF24),
    truffaPieno = Color(0xFFB91C1C), suTruffaPieno = Color(0xFFFFFFFF),
    provaFondo = Color(0xFF3D2A0A), provaTesto = Color(0xFFFDE68A),
    truffaFondo = Color(0xFF3A2321), truffaTesto = Color(0xFFF5DDDA), truffaIcona = Color(0xFFFFB4AB),
)

val LocalSegni = staticCompositionLocalOf { SegniChiaro }

/** Il tema in uso è scuro: le etichette dei gruppi cambiano tinta (docs/aspetto.md §1). */
val LocalTemaScuro = staticCompositionLocalOf { false }

/**
 * Movimenti brevi e calmi (docs/aspetto.md §5). false = il sistema ha le
 * animazioni disattivate: si tolgono tutte.
 */
val LocalMovimenti = staticCompositionLocalOf { true }

@Composable
fun SolTema(
    scuro: Boolean = isSystemInDarkTheme(),
    movimenti: Boolean = ValueAnimator.areAnimatorsEnabled(),
    contenuto: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSegni provides if (scuro) SegniScuro else SegniChiaro,
        LocalTemaScuro provides scuro,
        LocalMovimenti provides movimenti,
    ) {
        MaterialTheme(colorScheme = if (scuro) Scuro else Chiaro, typography = Tipografia, content = contenuto)
    }
}
