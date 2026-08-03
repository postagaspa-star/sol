package eu.stgm.sol.dati

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// La lettura delle date è una mitigazione, non cosmesi: la conferma ripete
// la data per esteso proprio per far emergere un "alle otto" capito male.
class FormattatoreDateTest {

    private val oggi = LocalDate.of(2026, 8, 3) // lunedì

    @Test
    fun `senza scadenza si dice cosi`() {
        assertEquals("senza scadenza", FormattatoreDate.leggibile(null, oggi))
        assertEquals("senza scadenza", FormattatoreDate.leggibile("", oggi))
    }

    @Test
    fun `oggi domani e dopodomani sono parole, non date`() {
        assertEquals("oggi alle 9", FormattatoreDate.leggibile("2026-08-03T09:00", oggi))
        assertEquals("domani alle 15 e 30", FormattatoreDate.leggibile("2026-08-04T15:30", oggi))
        assertEquals("dopodomani alle 8", FormattatoreDate.leggibile("2026-08-05T08:00", oggi))
    }

    @Test
    fun `oltre dopodomani si dice giorno e mese per esteso`() {
        assertEquals("giovedì 6 agosto alle 8", FormattatoreDate.leggibile("2026-08-06T08:00", oggi))
    }

    @Test
    fun `anno diverso si dice`() {
        assertEquals(
            "martedì 5 gennaio 2027 alle 10",
            FormattatoreDate.leggibile("2027-01-05T10:00", oggi),
        )
    }

    @Test
    fun `una scadenza illeggibile si restituisce come arriva, mai vuota`() {
        assertEquals("boh", FormattatoreDate.leggibile("boh", oggi))
    }

    @Test
    fun `millis e valida rifiutano il formato sbagliato`() {
        assertNotNull(FormattatoreDate.millis("2026-08-04T09:00"))
        assertNull(FormattatoreDate.millis("04/08/2026"))
        assertTrue(FormattatoreDate.valida("2026-08-04T09:00"))
        assertFalse(FormattatoreDate.valida("domani alle 9"))
    }
}
