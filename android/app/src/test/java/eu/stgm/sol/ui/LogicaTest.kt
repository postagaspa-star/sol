package eu.stgm.sol.ui

import eu.stgm.sol.dati.CacheFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/** Navigazione, date in parole, indirizzi scritti a mano, cache su file. */
class LogicaTest {
    private val roma = ZoneId.of("Europe/Rome")
    private val oggi = LocalDate.of(2026, 10, 10)

    @Test
    fun `indietro chiude le pagine, poi torna alla posta, poi esce`() {
        val n = Navigazione(Scheda.SCADENZE).apri(Pagina.Mail(812)).apri(Pagina.Bozza(55))
        assertEquals(Pagina.Bozza(55), n.inCima)
        val uno = n.indietro()!!
        assertEquals(Pagina.Mail(812), uno.inCima)
        val due = uno.indietro()!!
        assertEquals(Navigazione(Scheda.SCADENZE), due)
        assertEquals(Navigazione(Scheda.POSTA), due.indietro())
        assertNull(Navigazione().indietro())
    }

    @Test
    fun `la stessa mail aperta due volte non si impila`() {
        val n = Navigazione().apri(Pagina.Mail(1)).apri(Pagina.Mail(2)).apri(Pagina.Mail(1))
        assertEquals(listOf(Pagina.Mail(2), Pagina.Mail(1)), n.pagine)
    }

    @Test
    fun `la navigazione sopravvive come testo`() {
        val n = Navigazione(Scheda.INDIETRO, listOf(Pagina.Bozza(9), Pagina.Mail(812), Pagina.Impostazioni, Pagina.Abbinamento))
        assertEquals(n, Navigazione.decodifica(n.codifica()))
        assertEquals(Navigazione(), Navigazione.decodifica(null))
        assertEquals(Navigazione(), Navigazione.decodifica("ROTTO|mail:x"))
    }

    @Test
    fun `l'ora di una mail come la direbbe una persona`() {
        assertEquals("08:12", quandoBreve("2026-10-10T08:12:00+02:00", roma, oggi))
        assertEquals("ieri", quandoBreve("2026-10-09T18:00:00+02:00", roma, oggi))
        assertEquals("2 ott", quandoBreve("2026-10-02T10:00:00+02:00", roma, oggi))
        assertEquals("2 ott 2025", quandoBreve("2025-10-02T10:00:00+02:00", roma, oggi))
        assertEquals("", quandoBreve("non è una data", roma, oggi))
        assertEquals("oggi alle 08:12", quandoCompleto("2026-10-10T06:12:00Z", roma, oggi))
    }

    @Test
    fun `il giorno di una scadenza`() {
        assertEquals("oggi", giornoScadenza("2026-10-10", oggi))
        assertEquals("domani", giornoScadenza("2026-10-11", oggi))
        assertEquals("venerdì 16 ottobre", giornoScadenza("2026-10-16", oggi))
        assertEquals("lunedì 5 ottobre (passata)", giornoScadenza("2026-10-05", oggi))
    }

    @Test
    fun `indirizzi scritti a mano`() {
        assertEquals(listOf("rossi@esempio.it", "bianchi@esempio.it") to null, leggiIndirizzi(" rossi@esempio.it ;bianchi@esempio.it\n"))
        assertEquals("bianchi", leggiIndirizzi("rossi@esempio.it, bianchi").second)
        assertEquals("neri@esempio", leggiIndirizzi("neri@esempio").second)
        assertEquals(emptyList<String>() to null, leggiIndirizzi("  "))
    }

    @Test
    fun `Da - con il nome vuoto solo l'indirizzo`() {
        assertEquals("rossi@esempio.it", eu.stgm.sol.ui.dettaglio.daChi("", "rossi@esempio.it"))
        assertEquals("rossi@esempio.it", eu.stgm.sol.ui.dettaglio.daChi(null, "rossi@esempio.it"))
        assertEquals("rossi@esempio.it", eu.stgm.sol.ui.dettaglio.daChi("rossi@esempio.it", "rossi@esempio.it"))
        assertEquals("Mario Rossi <rossi@esempio.it>", eu.stgm.sol.ui.dettaglio.daChi("Mario Rossi", "rossi@esempio.it"))
    }

    @Test
    fun `cache su file - via i file oltre 30 giorni e oltre il tetto di spazio`() {
        val cartella = Files.createTempDirectory("sol-cache-pota").toFile()
        var adesso = 100L * 24 * 3600 * 1000
        val cache = CacheFile(cartella, orologio = { adesso }, massimoByte = 1_000)
        cache.scrivi("vecchia", "x".repeat(10))
        adesso += 31L * 24 * 3600 * 1000
        cache.scrivi("a", "x".repeat(600))
        adesso += 1000
        cache.scrivi("b", "x".repeat(600))
        cache.pota()
        assertNull("più vecchia di 30 giorni", cache.leggi("vecchia"))
        assertNull("oltre il tetto va via la più vecchia", cache.leggi("a"))
        assertEquals(600, cache.leggi("b")!!.json.length)
        cache.cancella("b")
        assertNull(cache.leggi("b"))
        cartella.deleteRecursively()
    }

    @Test
    fun `cache su file - scrive, rilegge, chiavi strane, svuota`() {
        val cartella = Files.createTempDirectory("sol-cache").toFile()
        val cache = CacheFile(cartella, orologio = { 1_000L })
        cache.scrivi("mail-tutte-URGENTI", """{"mail":[]}""")
        cache.scrivi("../fuori", "x")
        assertEquals("""{"mail":[]}""", cache.leggi("mail-tutte-URGENTI")!!.json)
        assertEquals("x", cache.leggi("../fuori")!!.json)
        assertEquals(cartella, cartella.listFiles()!!.first().parentFile)
        cache.svuota()
        assertNull(cache.leggi("mail-tutte-URGENTI"))
        cartella.deleteRecursively()
    }
}
