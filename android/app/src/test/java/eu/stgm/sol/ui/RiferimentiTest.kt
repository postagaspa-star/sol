package eu.stgm.sol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class RiferimentiTest {

    @Test
    fun `testo e riferimenti si separano`() {
        assertEquals(
            listOf(
                Pezzo.Testo("Rossi chiede l'offerta "),
                Pezzo.Riferimento(812),
                Pezzo.Testo(", Bianchi la fattura "),
                Pezzo.Riferimento(815),
                Pezzo.Testo("."),
            ),
            pezziRiassunto("Rossi chiede l'offerta [#812], Bianchi la fattura [#815]."),
        )
    }

    @Test
    fun `riferimenti attaccati, in cima e in fondo`() {
        assertEquals(listOf(Pezzo.Riferimento(1), Pezzo.Riferimento(2)), pezziRiassunto("[#1][#2]"))
        assertEquals(listOf(Pezzo.Testo("Vedi "), Pezzo.Riferimento(9)), pezziRiassunto("Vedi [#9]"))
    }

    @Test
    fun `quello che non e un riferimento resta testo`() {
        val testo = "Prezzo [#abc] e [# 12] e [12] e #812"
        assertEquals(listOf(Pezzo.Testo(testo)), pezziRiassunto(testo))
        assertEquals(emptyList<Pezzo>(), pezziRiassunto(""))
    }

    @Test
    fun `ogni riferimento diventa un collegamento che apre la sua mail`() {
        val aperte = mutableListOf<Long>()
        val testo = testoConRiferimenti("Rossi [#812] e Bianchi [#815]", Color.Blue) { aperte += it }
        assertEquals("Rossi $ETICHETTA_RIFERIMENTO e Bianchi $ETICHETTA_RIFERIMENTO", testo.text)
        val link = testo.getLinkAnnotations(0, testo.length)
        assertEquals(2, link.size)
        link.forEach { (it.item as LinkAnnotation.Clickable).let { c -> c.linkInteractionListener!!.onClick(c) } }
        assertEquals(listOf(812L, 815L), aperte)
        // Il collegamento copre proprio l'etichetta della mail.
        val primo = link.first()
        assertEquals(ETICHETTA_RIFERIMENTO, testo.text.substring(primo.start, primo.end))
        assertEquals("mail:812", (primo.item as LinkAnnotation.Clickable).tag)
    }
}
