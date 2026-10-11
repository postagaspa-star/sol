package eu.stgm.sol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
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

/** Quando prima del riferimento non c'è una frase da toccare (due riferimenti attaccati). */
const val ETICHETTA_RIFERIMENTO = "apri la mail"

private val FINE_FRASE = Regex("""[.!?;:]\s""")

/**
 * Il testo con i riferimenti toccabili (docs/aspetto.md §3): il segno `[#812]` non si
 * vede, si tocca la frase che lo precede, sottolineata leggera nello stesso colore
 * del testo. Ogni tocco chiama [apri] con l'id della mail.
 */
fun testoConRiferimenti(testo: String, colore: Color, apri: (Long) -> Unit): AnnotatedString = buildAnnotatedString {
    val stile = TextLinkStyles(style = SpanStyle(color = colore, textDecoration = TextDecoration.Underline))
    var inSospeso = ""
    var ultimo: Char? = null
    fun scrivi(pezzo: String) {
        append(pezzo)
        pezzo.lastOrNull()?.let { ultimo = it }
    }
    fun collega(frase: String, id: Long) =
        withLink(LinkAnnotation.Clickable(tag = "mail:$id", styles = stile) { apri(id) }) { scrivi(frase) }
    pezziRiassunto(testo).forEach { pezzo ->
        when (pezzo) {
            is Pezzo.Testo -> {
                scrivi(inSospeso)
                inSospeso = pezzo.testo
            }
            is Pezzo.Riferimento -> {
                // La frase da toccare: dall'ultimo punto (o dall'inizio) fino al segno.
                val fine = FINE_FRASE.findAll(inSospeso).lastOrNull()?.range?.last?.plus(1) ?: 0
                val frase = inSospeso.substring(fine).trimEnd()
                // Spazi e punteggiatura iniziali (", Bianchi...") restano fuori dal collegamento.
                val parole = frase.indexOfFirst { it.isLetterOrDigit() }.let { if (it < 0) frase.length else it }
                scrivi(inSospeso.substring(0, fine) + frase.substring(0, parole))
                val etichetta = frase.substring(parole)
                if (etichetta.isEmpty()) {
                    // Due riferimenti attaccati: il secondo ha la sua etichetta, staccata dal primo.
                    if (ultimo != null && ultimo?.isWhitespace() == false) scrivi(" ")
                    collega(ETICHETTA_RIFERIMENTO, pezzo.mailId)
                } else {
                    collega(etichetta, pezzo.mailId)
                }
                inSospeso = ""
            }
        }
    }
    scrivi(inSospeso)
}
