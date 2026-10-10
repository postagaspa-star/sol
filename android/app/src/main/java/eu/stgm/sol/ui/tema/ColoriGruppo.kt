package eu.stgm.sol.ui.tema

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * I 12 colori dei gruppi (docs/aspetto.md §1, contratto 17). Il server manda il
 * NOME ([nome]); l'app lo traduce in tinte adatte al tema. [base]: i cerchi con le
 * iniziali e la scheda scelta, sempre con testo bianco sopra.
 */
enum class ColoreGruppo(
    val nome: String,
    val base: Color,
    val chiaroFondo: Color,
    val chiaroTesto: Color,
    val scuroFondo: Color,
    val scuroTesto: Color,
) {
    BLU("blu", Color(0xFF1D4ED8), Color(0xFFDBEAFE), Color(0xFF1E40AF), Color(0xFF1E3A8A), Color(0xFFBFDBFE)),
    VERDE("verde", Color(0xFF15803D), Color(0xFFDCFCE7), Color(0xFF166534), Color(0xFF14532D), Color(0xFFBBF7D0)),
    AMBRA("ambra", Color(0xFFB45309), Color(0xFFFEF3C7), Color(0xFF92400E), Color(0xFF78350F), Color(0xFFFDE68A)),
    VIOLA("viola", Color(0xFF7E22CE), Color(0xFFF3E8FF), Color(0xFF6B21A8), Color(0xFF581C87), Color(0xFFE9D5FF)),
    GRIGIO("grigio", Color(0xFF4B5563), Color(0xFFF3F4F6), Color(0xFF374151), Color(0xFF374151), Color(0xFFE5E7EB)),
    ARDESIA("ardesia", Color(0xFF334155), Color(0xFFE2E8F0), Color(0xFF1E293B), Color(0xFF1E293B), Color(0xFFCBD5E1)),
    PETROLIO("petrolio", Color(0xFF0F766E), Color(0xFFCCFBF1), Color(0xFF115E59), Color(0xFF134E4A), Color(0xFF99F6E4)),
    ROSA("rosa", Color(0xFFBE185D), Color(0xFFFCE7F3), Color(0xFF9D174D), Color(0xFF831843), Color(0xFFFBCFE8)),
    INDACO("indaco", Color(0xFF4338CA), Color(0xFFE0E7FF), Color(0xFF3730A3), Color(0xFF312E81), Color(0xFFC7D2FE)),
    OLIVA("oliva", Color(0xFF4D7C0F), Color(0xFFECFCCB), Color(0xFF3F6212), Color(0xFF365314), Color(0xFFD9F99D)),
    MARRONE("marrone", Color(0xFF92400E), Color(0xFFFFEDD5), Color(0xFF7C2D12), Color(0xFF7C2D12), Color(0xFFFED7AA)),
    CIANO("ciano", Color(0xFF0E7490), Color(0xFFCFFAFE), Color(0xFF155E75), Color(0xFF164E63), Color(0xFFA5F3FC)),
    ;

    /** Lo sfondo dell'etichetta del gruppo nel tema in uso. */
    val fondo: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalTemaScuro.current) scuroFondo else chiaroFondo

    /** Il testo dell'etichetta del gruppo nel tema in uso. */
    val testo: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalTemaScuro.current) scuroTesto else chiaroTesto

    /**
     * Il pallino del gruppo (schede, foglio "Sposta in"): la tinta piena nel tema
     * chiaro; nel tema scuro quella dell'etichetta, che sul fondo scuro si vede.
     */
    val punto: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalTemaScuro.current) scuroTesto else base

    companion object {
        /** Un nome che l'app non conosce (o nessun nome: server vecchio) diventa ardesia. */
        fun daNome(nome: String?): ColoreGruppo {
            val pulito = nome?.trim()?.lowercase() ?: return ARDESIA
            return entries.firstOrNull { it.nome == pulito } ?: ARDESIA
        }
    }
}
