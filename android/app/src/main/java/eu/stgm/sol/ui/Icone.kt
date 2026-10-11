package eu.stgm.sol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Le icone a tratto del prototipo (docs/prototipo/), disegnate dagli stessi percorsi
 * SVG (riquadro 24×24, tratto 2 px, estremi tondi). Il colore lo dà chi le usa.
 */
object Icone {
    private fun cerchio(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun rettangolo(x: Float, y: Float, l: Float, a: Float, r: Float) =
        "M${x + r},$y h${l - 2 * r} a$r,$r 0 0 1 $r,$r v${a - 2 * r} a$r,$r 0 0 1 ${-r},$r h${-(l - 2 * r)} " +
            "a$r,$r 0 0 1 ${-r},${-r} v${-(a - 2 * r)} a$r,$r 0 0 1 $r,${-r} z"

    private fun icona(nome: String, spessore: Float, tratti: List<String>, pieni: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(nome, 24.dp, 24.dp, 24f, 24f).apply {
            tratti.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = spessore,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            pieni.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private const val RAGGI = "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"

    /** Il simbolo di Sol: il sole col centro pieno. */
    val Sole = icona("sole", 2f, listOf(RAGGI, cerchio(12f, 12f, 4.2f)), listOf(cerchio(12f, 12f, 4.2f)))

    /** La voce "Oggi" della barra: il sole vuoto. */
    val Oggi = icona("oggi", 2f, listOf(RAGGI, cerchio(12f, 12f, 4f)))

    val Posta = icona(
        "posta", 2f,
        listOf(
            "M4 13.5l2.2-7.2A2 2 0 0 1 8.1 5h7.8a2 2 0 0 1 1.9 1.3l2.2 7.2",
            "M4 13.5V18a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 18v-4.5h-4.5l-1.5 2.5h-4l-1.5-2.5z",
        ),
    )
    val Riassunti = icona("riassunti", 2f, listOf("M5 6.5h14M5 11h14M5 15.5h9"))
    val Cerca = icona("cerca", 2.2f, listOf(cerchio(11f, 11f, 6.5f), "M16 16l4 4"))
    val Calendario = icona("calendario", 2f, listOf(rettangolo(3.5f, 5f, 17f, 15f, 3f), "M3.5 10h17M8 3v4M16 3v4"))
    val Truffa = icona("truffa", 2.2f, listOf("M12 3.5l9 16H3z", "M12 10v4.5M12 17.2v0.3"))
    val Strati = icona("strati", 2f, listOf("M12 3.5l8.5 4.5-8.5 4.5-8.5-4.5z", "M3.5 12.5l8.5 4.5 8.5-4.5", "M3.5 16.5l8.5 4.5 8.5-4.5"))
    val Matita = icona("matita", 2.4f, listOf("M4 20l4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z"))
    val Documento = icona("documento", 2f, listOf(rettangolo(4f, 4f, 16f, 16f, 3f), "M8 9h8M8 13h8M8 17h5"))
    val Ricevuta = icona("ricevuta", 2f, listOf("M7 3.5h10v17l-2.5-1.5-2.5 1.5-2.5-1.5L7 20.5z", "M10 8h4M10 12h4"))
    val Avanti = icona("avanti", 2.2f, listOf("M9 6l6 6-6 6"))
    val Indietro = icona("indietro", 2.4f, listOf("M15 5l-7 7 7 7"))
    val Giu = icona("giu", 2.6f, listOf("M6 9l6 6 6-6"))
    val AltreAzioni = icona(
        "altre", 2f, emptyList(),
        listOf(cerchio(5.5f, 12f, 1.8f), cerchio(12f, 12f, 1.8f), cerchio(18.5f, 12f, 1.8f)),
    )
    val Graffetta = icona(
        "graffetta", 2.2f,
        listOf("M20 11.5l-7.8 7.8a5 5 0 0 1-7.1-7.1l8-8a3.4 3.4 0 0 1 4.8 4.8l-8 8a1.7 1.7 0 0 1-2.4-2.4l7.3-7.3"),
    )
    val Invia = icona("invia", 2.6f, listOf("M5 12h14M13 6l6 6-6 6"))
    val Chiudi = icona("chiudi", 2.6f, listOf("M6 6l12 12M18 6L6 18"))
    val Spunta = icona("spunta", 2.6f, listOf("M5 12.5l4.5 4.5L19 7.5"))
    val Piu = icona("piu", 2.4f, listOf("M12 5v14M5 12h14"))
    val Persona = icona("persona", 2f, listOf(cerchio(12f, 8f, 3.5f), "M5 20c0-3.6 3.1-6 7-6s7 2.4 7 6"))
    val Fumetto = icona(
        "tono", 2f,
        listOf("M5 5h14a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 19 17h-7l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5v-9A1.5 1.5 0 0 1 5 5z"),
    )
    val Busta = icona("busta", 2f, listOf(rettangolo(3.5f, 5.5f, 17f, 13f, 2.5f), "M4 7l8 6 8-6"))
    val BustaAperta = icona(
        "busta-aperta", 2f,
        listOf("M4 10l8-5.5 8 5.5v8.5A1.5 1.5 0 0 1 18.5 20h-13A1.5 1.5 0 0 1 4 18.5z", "M4 10l8 5.5 8-5.5"),
    )
    val Scudo = icona("scudo", 2f, listOf("M12 3.5l7.5 3v5.5c0 4.5-3.2 7.6-7.5 9-4.3-1.4-7.5-4.5-7.5-9V6.5z"))
    val Telefono = icona("telefono", 2f, listOf(rettangolo(7f, 3f, 10f, 18f, 2.5f), "M11 18h2"))
    val Scollega = icona(
        "scollega", 2f,
        listOf("M14 4h4.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H14", "M10 16l4-4-4-4", "M14 12H4"),
    )
    val Campanella = icona("campanella", 2f, listOf("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0"))
    val Luna = icona("luna", 2f, listOf("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"))
    val Aiuto = icona(
        "aiuto", 2f,
        listOf(cerchio(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-0.6 0.3-1 0.9-1 1.6v0.6", "M12 17v0.3"),
    )
    val Info = icona("info", 2f, listOf(cerchio(12f, 12f, 9f), "M12 11v5M12 8v0.3"))
    val Cursori = icona("cursori", 2f, listOf("M4 7h10M18 7h2M4 17h4M12 17h8", cerchio(16f, 7f, 2f), cerchio(10f, 17f, 2f)))
    val Aggiorna = icona("aggiorna", 2f, listOf("M20 12a8 8 0 1 1-2.4-5.7", "M20 4v4.5h-4.5"))
    val Cartella = icona(
        "sposta", 2f,
        listOf("M3.5 7A1.5 1.5 0 0 1 5 5.5h4l2 2h8A1.5 1.5 0 0 1 20.5 9v8.5A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z", "M10 13h6M13.5 10.5L16 13l-2.5 2.5"),
    )
    val Casa = icona("casa", 2f, listOf("M4 11l8-6.5 8 6.5", "M6 9.5V19.5h12V9.5"))
    val Orologio = icona("orologio", 2f, listOf(cerchio(12f, 12f, 8.5f), "M12 7.5V12l3 2"))
}
