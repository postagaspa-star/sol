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
    fun `si tocca la frase che precede il riferimento, il segno non si vede`() {
        val aperte = mutableListOf<Long>()
        val testo = testoConRiferimenti("Rossi chiede l'offerta [#812]. Bianchi ha mandato la fattura [#815].", Color.Blue) { aperte += it }
        assertEquals("Rossi chiede l'offerta. Bianchi ha mandato la fattura.", testo.text)
        val link = testo.getLinkAnnotations(0, testo.length)
        assertEquals(2, link.size)
        link.forEach { (it.item as LinkAnnotation.Clickable).let { c -> c.linkInteractionListener!!.onClick(c) } }
        assertEquals(listOf(812L, 815L), aperte)
        assertEquals("Rossi chiede l'offerta", testo.text.substring(link[0].start, link[0].end))
        assertEquals("il punto e lo spazio restano fuori", "Bianchi ha mandato la fattura", testo.text.substring(link[1].start, link[1].end))
        assertEquals("mail:812", (link[0].item as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun `dentro una frase - la virgola resta fuori dal collegamento`() {
        val testo = testoConRiferimenti("Rossi [#812], Bianchi [#815]", Color.Blue) {}
        assertEquals("Rossi, Bianchi", testo.text)
        val link = testo.getLinkAnnotations(0, testo.length)
        assertEquals(listOf("Rossi", "Bianchi"), link.map { testo.text.substring(it.start, it.end) })
    }

    @Test
    fun `due riferimenti attaccati - il secondo ha la sua etichetta`() {
        val testo = testoConRiferimenti("Vedi la pratica [#1][#2]", Color.Blue) {}
        assertEquals("Vedi la pratica $ETICHETTA_RIFERIMENTO", testo.text)
        val link = testo.getLinkAnnotations(0, testo.length)
        assertEquals(listOf("Vedi la pratica", ETICHETTA_RIFERIMENTO), link.map { testo.text.substring(it.start, it.end) })
        assertEquals("solo un riferimento", ETICHETTA_RIFERIMENTO, testoConRiferimenti("[#3]", Color.Blue) {}.text)
    }
}
