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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.stgm.sol.R

/**
 * I colori di Sol 2.0 (docs/aspetto.md §1 e il prototipo in docs/prototipo/). 60%
 * fondo neutro, 30% superfici bianche, 10% accento arancio sulle azioni principali.
 */
@Immutable
data class ColoriSol(
    val fondo: Color,
    val superficie: Color,
    /** Pulsanti tondi, binario dei segmenti. */
    val superficie2: Color,
    val testo: Color,
    /** Testo e icone su superficie2 (pulsanti tondi), nomi delle sezioni. */
    val testoMedio: Color,
    /** Corpo delle mail, riassunti secondari. */
    val testoCorpo: Color,
    val tenue: Color,
    val moltoTenue: Color,
    val riga: Color,
    /** Binario della barra di avanzamento, bordo delle pillole e degli allegati. */
    val traccia: Color,
    /** Cerchi neutri (icona di Smista, mittente incerto). */
    val neutro: Color,
    /** Bordo del pulsante bianco "Cambia". */
    val bordoForte: Color,
    val accento: Color,
    val suAccento: Color,
    /** Pulsanti secondari (Inizia, Rivedi), riquadro "mail da sistemare". */
    val accentoTenue: Color,
    /** Voce attiva della barra in basso, pillola di tono scelta, cerchio della spunta. */
    val accentoTenueForte: Color,
    val accentoTesto: Color,
    val accentoTestoForte: Color,
    val bordoScelto: Color,
    val truffa: Color,
    val truffaTenue: Color,
    val truffaTesto: Color,
    val truffaTestoSecondario: Color,
    val truffaBordo: Color,
    /** Le schede dietro nella pila di Smista. */
    val pila1: Color,
    val pila2: Color,
    val barraSfondo: Color,
    val barraLinea: Color,
    val barraInattiva: Color,
    /** La pillola "Prova" (modalità prova): ambra discreta. */
    val provaFondo: Color,
    val provaTesto: Color,
    val ok: Color,
    val scuro: Boolean,
)

private val Chiaro = ColoriSol(
    fondo = Color(0xFFF4F4F2),
    superficie = Color(0xFFFFFFFF),
    superficie2 = Color(0xFFE7E6E2),
    testo = Color(0xFF1B1B1F),
    testoMedio = Color(0xFF3F3F46),
    testoCorpo = Color(0xFF52525B),
    tenue = Color(0xFF6B6B73),
    // #8A8A92 del prototipo fa 3,5:1 sul bianco: per i testi piccoli serve 4,5 (§11).
    moltoTenue = Color(0xFF75757D),
    riga = Color(0xFFEFEEEB),
    traccia = Color(0xFFE4E3DF),
    neutro = Color(0xFFF1EFEA),
    bordoForte = Color(0xFFDAD9D5),
    accento = Color(0xFFD9480F),
    suAccento = Color(0xFFFFFFFF),
    accentoTenue = Color(0xFFFFF1E8),
    accentoTenueForte = Color(0xFFFFE8D9),
    accentoTesto = Color(0xFFB23A0B),
    accentoTestoForte = Color(0xFF8F2E08),
    bordoScelto = Color(0xFFF5B48C),
    truffa = Color(0xFFC92A2A),
    truffaTenue = Color(0xFFFDEEEC),
    truffaTesto = Color(0xFF9B1C1C),
    truffaTestoSecondario = Color(0xFF6F2A2A),
    truffaBordo = Color(0xFFE8B4B0),
    pila1 = Color(0xFFE9E8E4),
    pila2 = Color(0xFFF0EFEC),
    barraSfondo = Color(0xF7FFFFFF),
    barraLinea = Color(0xFFE7E6E2),
    barraInattiva = Color(0xFF52525B),
    provaFondo = Color(0xFFFBF3E3),
    provaTesto = Color(0xFF8A5A12),
    ok = Color(0xFF2B8A3E),
    scuro = false,
)

private val Scuro = ColoriSol(
    fondo = Color(0xFF121214),
    superficie = Color(0xFF1C1C1F),
    superficie2 = Color(0xFF2A2A2E),
    testo = Color(0xFFF2F2F3),
    testoMedio = Color(0xFFD4D4D8),
    testoCorpo = Color(0xFFBDBDC4),
    tenue = Color(0xFFA1A1AA),
    moltoTenue = Color(0xFF85858E),
    riga = Color(0xFF2C2C30),
    traccia = Color(0xFF2E2E33),
    neutro = Color(0xFF2A2A2E),
    bordoForte = Color(0xFF3A3A40),
    accento = Color(0xFFE8590C),
    suAccento = Color(0xFFFFFFFF),
    accentoTenue = Color(0xFF3A2418),
    accentoTenueForte = Color(0xFF4A2C1B),
    accentoTesto = Color(0xFFFF9A66),
    accentoTestoForte = Color(0xFFFFB98F),
    bordoScelto = Color(0xFF8A4A26),
    truffa = Color(0xFFE03131),
    truffaTenue = Color(0xFF3A1C1C),
    truffaTesto = Color(0xFFFFA8A8),
    truffaTestoSecondario = Color(0xFFE9B4B4),
    truffaBordo = Color(0xFF6E3030),
    pila1 = Color(0xFF1F1F23),
    pila2 = Color(0xFF242428),
    barraSfondo = Color(0xFF1C1C1F),
    barraLinea = Color(0xFF2C2C30),
    barraInattiva = Color(0xFFA1A1AA),
    provaFondo = Color(0xFF3A2E14),
    provaTesto = Color(0xFFF2C46B),
    ok = Color(0xFF69DB7C),
    scuro = true,
)

/** Gli stili di testo della guida (§1), in Figtree se l'app ce l'ha. */
@Immutable
data class TestiSol(
    /** 30 / 800: "Buongiorno, Manuel", "Posta". */
    val titoloPagina: TextStyle,
    /** 25 / 800: l'oggetto della mail aperta. */
    val oggetto: TextStyle,
    /** 23 / 800: l'oggetto nella scheda di Smista. */
    val oggettoScheda: TextStyle,
    /** 21 / 800: "Da fare", "Sol" accanto al sole. */
    val titoloSezione: TextStyle,
    /** 17 / 800: mittente della mail aperta, titolo di Smista, pulsanti grandi. */
    val forteGrande: TextStyle,
    /** 16 / 700: titolo di una voce, mittente. */
    val forte: TextStyle,
    /** 17 / 400, interlinea 1,45: il punto di oggi. */
    val riassunto: TextStyle,
    /** 16 / 400, interlinea 1,5: risposta di Sol, "In breve". */
    val testo: TextStyle,
    /** 15 / 400, interlinea 1,55: corpo della mail. */
    val corpo: TextStyle,
    /** 15 / 400: sottotitoli. */
    val medio: TextStyle,
    /** 14 / 400: dettagli. */
    val secondario: TextStyle,
    /** 13 / 400: ora, indirizzo. */
    val piccolo: TextStyle,
    /** 12 / 700 maiuscolo: "IL PUNTO DI OGGI". */
    val etichetta: TextStyle,
    /** 15 / 700: pillole. */
    val pulsante: TextStyle,
    /** 17 / 800: pulsanti pieni grandi. */
    val pulsanteGrande: TextStyle,
)

private fun testi(famiglia: FontFamily) = TestiSol(
    titoloPagina = TextStyle(fontFamily = famiglia, fontSize = 30.sp, lineHeight = 34.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
    oggetto = TextStyle(fontFamily = famiglia, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    oggettoScheda = TextStyle(fontFamily = famiglia, fontSize = 23.sp, lineHeight = 27.6.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titoloSezione = TextStyle(fontFamily = famiglia, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    forteGrande = TextStyle(fontFamily = famiglia, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold),
    forte = TextStyle(fontFamily = famiglia, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    riassunto = TextStyle(fontFamily = famiglia, fontSize = 17.sp, lineHeight = 24.6.sp),
    testo = TextStyle(fontFamily = famiglia, fontSize = 16.sp, lineHeight = 24.sp),
    corpo = TextStyle(fontFamily = famiglia, fontSize = 15.sp, lineHeight = 23.2.sp),
    medio = TextStyle(fontFamily = famiglia, fontSize = 15.sp, lineHeight = 20.sp),
    secondario = TextStyle(fontFamily = famiglia, fontSize = 14.sp, lineHeight = 19.sp),
    piccolo = TextStyle(fontFamily = famiglia, fontSize = 13.sp, lineHeight = 17.sp),
    etichetta = TextStyle(fontFamily = famiglia, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
    pulsante = TextStyle(fontFamily = famiglia, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    pulsanteGrande = TextStyle(fontFamily = famiglia, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold),
)

/**
 * Figtree (licenza SIL OFL 1.1, testo in `assets/licenze/figtree-OFL.txt`) è incluso
 * nell'app: un solo file variabile (`res/font/figtree.ttf`, asse wght 300-900) da
 * cui si prendono i pesi della guida. Niente richieste a Google durante l'uso.
 */
@OptIn(ExperimentalTextApi::class)
val Figtree: FontFamily = FontFamily(
    listOf(
        400 to FontWeight.Normal,
        500 to FontWeight.Medium,
        600 to FontWeight.SemiBold,
        700 to FontWeight.Bold,
        800 to FontWeight.ExtraBold,
    ).map { (peso, nome) ->
        Font(R.font.figtree, weight = nome, variationSettings = FontVariation.Settings(FontVariation.weight(peso)))
    },
)

val LocalColori = staticCompositionLocalOf { Chiaro }
val LocalTesti = staticCompositionLocalOf { testi(FontFamily.Default) }

/** Il tema in uso è scuro: le tinte dei gruppi e le ombre cambiano (§8). */
val LocalTemaScuro = staticCompositionLocalOf { false }

/**
 * Movimenti brevi e calmi (§1). false = il sistema ha le animazioni disattivate:
 * si tolgono tutte.
 */
val LocalMovimenti = staticCompositionLocalOf { true }

/** Scorciatoie: `Sol.colori.accento`, `Sol.testi.forte`. */
object Sol {
    val colori: ColoriSol
        @Composable get() = LocalColori.current
    val testi: TestiSol
        @Composable get() = LocalTesti.current
}

@Composable
fun SolTema(
    scuro: Boolean = isSystemInDarkTheme(),
    movimenti: Boolean = ValueAnimator.areAnimatorsEnabled(),
    contenuto: @Composable () -> Unit,
) {
    val famiglia = Figtree
    val colori = if (scuro) Scuro else Chiaro
    val stili = remember(famiglia) { testi(famiglia) }
    val schema = if (scuro) {
        darkColorScheme(
            primary = colori.accento, onPrimary = colori.suAccento,
            primaryContainer = colori.accentoTenue, onPrimaryContainer = colori.accentoTesto,
            secondary = colori.testoMedio, onSecondary = colori.fondo,
            secondaryContainer = colori.superficie2, onSecondaryContainer = colori.testo,
            tertiary = colori.provaTesto, tertiaryContainer = colori.provaFondo, onTertiaryContainer = colori.provaTesto,
            background = colori.fondo, onBackground = colori.testo,
            surface = colori.fondo, onSurface = colori.testo,
            surfaceVariant = colori.superficie2, onSurfaceVariant = colori.tenue,
            outline = colori.bordoForte, outlineVariant = colori.riga,
            error = colori.truffa, onError = Color.White,
            errorContainer = colori.truffaTenue, onErrorContainer = colori.truffaTesto,
            surfaceContainerLowest = colori.fondo, surfaceContainerLow = colori.superficie,
            surfaceContainer = colori.superficie, surfaceContainerHigh = colori.superficie,
            surfaceContainerHighest = colori.superficie2,
            inverseSurface = Color(0xFFE7E7EA), inverseOnSurface = Color(0xFF1B1B1F), inversePrimary = Color(0xFFB23A0B),
        )
    } else {
        lightColorScheme(
            primary = colori.accento, onPrimary = colori.suAccento,
            primaryContainer = colori.accentoTenue, onPrimaryContainer = colori.accentoTesto,
            secondary = colori.testoMedio, onSecondary = Color.White,
            secondaryContainer = colori.superficie2, onSecondaryContainer = colori.testo,
            tertiary = colori.provaTesto, tertiaryContainer = colori.provaFondo, onTertiaryContainer = colori.provaTesto,
            background = colori.fondo, onBackground = colori.testo,
            surface = colori.fondo, onSurface = colori.testo,
            surfaceVariant = colori.superficie2, onSurfaceVariant = colori.tenue,
            outline = colori.bordoForte, outlineVariant = colori.riga,
            error = colori.truffa, onError = Color.White,
            errorContainer = colori.truffaTenue, onErrorContainer = colori.truffaTesto,
            surfaceContainerLowest = Color.White, surfaceContainerLow = colori.superficie,
            surfaceContainer = colori.superficie, surfaceContainerHigh = colori.superficie,
            surfaceContainerHighest = colori.superficie2,
            inverseSurface = Color(0xFF26262B), inverseOnSurface = Color(0xFFF2F2F3), inversePrimary = Color(0xFFFF9A66),
        )
    }
    val base = Typography()
    val tipografia = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = famiglia),
        displayMedium = base.displayMedium.copy(fontFamily = famiglia),
        displaySmall = base.displaySmall.copy(fontFamily = famiglia),
        headlineLarge = base.headlineLarge.copy(fontFamily = famiglia),
        headlineMedium = base.headlineMedium.copy(fontFamily = famiglia),
        headlineSmall = stili.titoloSezione.copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = stili.titoloSezione,
        titleMedium = stili.forte.copy(fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = stili.forte,
        bodyLarge = stili.testo,
        bodyMedium = stili.medio,
        bodySmall = stili.secondario,
        labelLarge = stili.pulsante,
        labelMedium = stili.secondario.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = stili.piccolo.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(
        LocalColori provides colori,
        LocalTesti provides stili,
        LocalTemaScuro provides scuro,
        LocalMovimenti provides movimenti,
    ) {
        MaterialTheme(colorScheme = schema, typography = tipografia, content = contenuto)
    }
}
