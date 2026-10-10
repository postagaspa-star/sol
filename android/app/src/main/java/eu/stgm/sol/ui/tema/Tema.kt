package eu.stgm.sol.ui.tema

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

// Blu ardesia sobrio. Contrasti alti (testo quasi nero su quasi bianco) perché
// l'app si usa anche in cantiere, al sole. Schema completo: con il solo primary
// Material riempirebbe il resto di lavanda.
private val Chiaro = lightColorScheme(
    primary = Color(0xFF2E4A6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2F0),
    onPrimaryContainer = Color(0xFF0E2239),
    secondary = Color(0xFF4A5B6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3EA),
    onSecondaryContainer = Color(0xFF17212C),
    tertiary = Color(0xFF6B4E00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E3B5),
    onTertiaryContainer = Color(0xFF2B1F00),
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF12161A),
    surface = Color(0xFFFAFBFC),
    onSurface = Color(0xFF12161A),
    surfaceVariant = Color(0xFFE1E6EC),
    onSurfaceVariant = Color(0xFF353D46),
    outline = Color(0xFF66707A),
    outlineVariant = Color(0xFFC2C9D1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6F9),
    surfaceContainer = Color(0xFFEEF1F5),
    surfaceContainerHigh = Color(0xFFE8ECF1),
    surfaceContainerHighest = Color(0xFFE2E7ED),
    inverseSurface = Color(0xFF26364A),
    inverseOnSurface = Color(0xFFEEF3F8),
    inversePrimary = Color(0xFFA8C4E6),
)

private val Scuro = darkColorScheme(
    primary = Color(0xFFA8C4E6),
    onPrimary = Color(0xFF0F2640),
    primaryContainer = Color(0xFF284360),
    onPrimaryContainer = Color(0xFFD8E5F5),
    secondary = Color(0xFFB8C6D6),
    onSecondary = Color(0xFF22303E),
    secondaryContainer = Color(0xFF384655),
    onSecondaryContainer = Color(0xFFDDE5EE),
    tertiary = Color(0xFFE8C46A),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF574000),
    onTertiaryContainer = Color(0xFFFBE6B6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE9EDF1),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE9EDF1),
    surfaceVariant = Color(0xFF3A424B),
    onSurfaceVariant = Color(0xFFC9D0D8),
    outline = Color(0xFF8D96A0),
    outlineVariant = Color(0xFF3F4750),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = Color(0xFF181C21),
    surfaceContainer = Color(0xFF1C2026),
    surfaceContainerHigh = Color(0xFF262B31),
    surfaceContainerHighest = Color(0xFF31363D),
    inverseSurface = Color(0xFFE2E6EB),
    inverseOnSurface = Color(0xFF1E2329),
    inversePrimary = Color(0xFF2E4A6B),
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

/** I colori dei segni sulle mail, leggibili in tutti e due i temi. */
@Immutable
data class ColoriSegni(
    val urgenteFondo: Color,
    val urgenteTesto: Color,
    val rispondereFondo: Color,
    val rispondereTesto: Color,
    val bozzaFondo: Color,
    val bozzaTesto: Color,
    val controllareFondo: Color,
    val controllareTesto: Color,
)

private val SegniChiaro = ColoriSegni(
    urgenteFondo = Color(0xFFB3261E), urgenteTesto = Color.White,
    rispondereFondo = Color(0xFFD6E2F0), rispondereTesto = Color(0xFF0E2239),
    bozzaFondo = Color(0xFFD5EBDD), bozzaTesto = Color(0xFF0D3A20),
    controllareFondo = Color(0xFFF6E3B5), controllareTesto = Color(0xFF2B1F00),
)

private val SegniScuro = ColoriSegni(
    urgenteFondo = Color(0xFFFFB4AB), urgenteTesto = Color(0xFF410002),
    rispondereFondo = Color(0xFF284360), rispondereTesto = Color(0xFFD8E5F5),
    bozzaFondo = Color(0xFF1F4D33), bozzaTesto = Color(0xFFD2F0DD),
    controllareFondo = Color(0xFF574000), controllareTesto = Color(0xFFFBE6B6),
)

val LocalSegni = staticCompositionLocalOf { SegniChiaro }

@Composable
fun SolTema(scuro: Boolean = isSystemInDarkTheme(), contenuto: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSegni provides if (scuro) SegniScuro else SegniChiaro) {
        MaterialTheme(colorScheme = if (scuro) Scuro else Chiaro, typography = Tipografia, content = contenuto)
    }
}
