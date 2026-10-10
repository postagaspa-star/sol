package eu.stgm.sol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** Un pezzo del testo di un riassunto: testo normale o riferimento a una mail. */
sealed interface Pezzo {
    data class Testo(val testo: String) : Pezzo
    data class Riferimento(val mailId: Long) : Pezzo
}

private val RIFERIMENTO = Regex("""\[#(\d{1,18})]""")

/** Divide "Rossi chiede l'offerta [#812]." in testo e riferimenti (contratto §8). */
fun pezziRiassunto(testo: String): List<Pezzo> {
    val pezzi = mutableListOf<Pezzo>()
    var inizio = 0
    RIFERIMENTO.findAll(testo).forEach { trovato ->
        if (trovato.range.first > inizio) pezzi += Pezzo.Testo(testo.substring(inizio, trovato.range.first))
        val id = trovato.groupValues[1].toLongOrNull()
        pezzi += if (id != null) Pezzo.Riferimento(id) else Pezzo.Testo(trovato.value)
        inizio = trovato.range.last + 1
    }
    if (inizio < testo.length) pezzi += Pezzo.Testo(testo.substring(inizio))
    return pezzi
}

/** Come si legge un riferimento nel testo: corto, chiaro che si tocca, senza numeri. */
const val ETICHETTA_RIFERIMENTO = "[apri la mail]"

/** Il testo con i riferimenti cliccabili: ogni tocco chiama [apri] con l'id della mail. */
fun testoConRiferimenti(testo: String, colore: Color, apri: (Long) -> Unit): AnnotatedString = buildAnnotatedString {
    val stile = TextLinkStyles(
        style = SpanStyle(color = colore, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
    )
    pezziRiassunto(testo).forEach { pezzo ->
        when (pezzo) {
            is Pezzo.Testo -> append(pezzo.testo)
            is Pezzo.Riferimento -> withLink(
                LinkAnnotation.Clickable(tag = "mail:${pezzo.mailId}", styles = stile) { apri(pezzo.mailId) },
            ) { append(ETICHETTA_RIFERIMENTO) }
        }
    }
}
