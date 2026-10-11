package eu.stgm.sol.ui.tema

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * I 12 colori dei gruppi (docs/aspetto.md §1, contratto 17). Il server manda il
 * NOME; l'app lo traduce. [pieno]: il pallino delle sezioni; [tenue] + [inchiostro]:
 * il cerchio con le iniziali e la pillola del gruppo. I primi sei sono quelli del
 * prototipo, gli altri seguono la stessa logica.
 */
enum class ColoreGruppo(val nome: String, val pieno: Color, val tenue: Color, val inchiostro: Color) {
    BLU("blu", Color(0xFF2F6FDB), Color(0xFFEAF1FD), Color(0xFF1E4FA8)),
    VERDE("verde", Color(0xFF2B8A3E), Color(0xFFEBF7EE), Color(0xFF1F6A30)),
    AMBRA("ambra", Color(0xFFB7791F), Color(0xFFFBF3E3), Color(0xFF8A5A12)),
    VIOLA("viola", Color(0xFF7048E8), Color(0xFFF1ECFE), Color(0xFF5532C9)),
    GRIGIO("grigio", Color(0xFF868E96), Color(0xFFF1F3F5), Color(0xFF495057)),
    ARDESIA("ardesia", Color(0xFF495057), Color(0xFFEEF0F2), Color(0xFF343A40)),
    PETROLIO("petrolio", Color(0xFF0B7285), Color(0xFFE3F2F4), Color(0xFF0A5866)),
    ROSA("rosa", Color(0xFFD6336C), Color(0xFFFCEBF1), Color(0xFFA61E4D)),
    INDACO("indaco", Color(0xFF4263EB), Color(0xFFECF0FE), Color(0xFF364FC7)),
    OLIVA("oliva", Color(0xFF5C940D), Color(0xFFF0F7E6), Color(0xFF466F0A)),
    MARRONE("marrone", Color(0xFFA0522D), Color(0xFFF7EDE7), Color(0xFF7A3E1F)),
    CIANO("ciano", Color(0xFF1098AD), Color(0xFFE3F6F9), Color(0xFF0C6B7A)),
    ;

    /** Nel tema scuro il tenue è il colore al 20% sulla superficie scura (§1). */
    val tenueScuro: Color get() = lerp(SUPERFICIE_SCURA, pieno, 0.2f)

    /** Nel tema scuro le iniziali sono una tinta chiara del colore. */
    val inchiostroScuro: Color get() = lerp(pieno, Color.White, 0.6f)

    /** Sfondo del cerchio con le iniziali e della pillola, nel tema in uso. */
    val fondo: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalTemaScuro.current) tenueScuro else tenue

    /** Le iniziali e il testo della pillola, nel tema in uso. */
    val testo: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalTemaScuro.current) inchiostroScuro else inchiostro

    /** Il pallino della sezione: pieno; al buio un poco più chiaro, che si veda. */
    val punto: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalTemaScuro.current) lerp(pieno, Color.White, 0.25f) else pieno

    companion object {
        private val SUPERFICIE_SCURA = Color(0xFF1C1C1F)

        /** Un nome che l'app non conosce (o nessun nome: server vecchio) diventa ardesia. */
        fun daNome(nome: String?): ColoreGruppo {
            val pulito = nome?.trim()?.lowercase() ?: return ARDESIA
            return entries.firstOrNull { it.nome == pulito } ?: ARDESIA
        }
    }
}
