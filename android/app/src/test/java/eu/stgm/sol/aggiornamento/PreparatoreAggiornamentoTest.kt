package eu.stgm.sol.aggiornamento

import eu.stgm.sol.ServerFinto
import eu.stgm.sol.dati.VersioneApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/** 15.10: un APK che non coincide con quello pubblicato non arriva mai a PackageInstaller. */
class PreparatoreAggiornamentoTest {
    private val server = ServerFinto()
    private val cartella = Files.createTempDirectory("sol-aggiornamenti").toFile()
    private val contenuto = "APK FINTO DI PROVA, NON INSTALLABILE"
    private val sha = MessageDigest.getInstance("SHA-256").digest(contenuto.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val preparatore = PreparatoreAggiornamento(server.client(), cartella, codiceAttuale = 1)

    @After
    fun chiudi() {
        server.close()
        cartella.deleteRecursively()
    }

    private fun versione(sha256: String? = sha, byte: Long? = contenuto.length.toLong()) =
        VersioneApp(codice = 2, nome = "1.1.0", sha256 = sha256, byte = byte)

    @Test
    fun `versione nuova trovata, la nostra no`() = runBlocking {
        server.rispondi("GET /api/versione", """{"server": "1.1.0", "android": {"codice": 2, "nome": "1.1.0", "sha256": "$sha", "byte": ${contenuto.length}}}""")
        assertEquals(2, preparatore.versioneNuova().first!!.codice)
        server.rispondi("GET /api/versione", """{"server": "1.0.0", "android": {"codice": 1, "nome": "1.0.0"}}""")
        assertNull(preparatore.versioneNuova().first)
    }

    @Test
    fun `file giusto - pronto, e al giro dopo non si riscarica`() = runBlocking {
        server.rispondi("GET /scarica/sol.apk", contenuto)
        val pronto = preparatore.prepara(versione()) as PreparatoreAggiornamento.Preparazione.Pronto
        assertTrue(coincide(pronto.file, versione()))
        preparatore.prepara(versione())
        assertEquals("già scaricato e controllato", 1, server.richiesteA("GET /scarica/sol.apk").size)
    }

    @Test
    fun `impronta diversa - scartato e niente installazione`() = runBlocking {
        server.rispondi("GET /scarica/sol.apk", contenuto)
        val e = preparatore.prepara(versione(sha256 = "0".repeat(64)))
        assertTrue(e is PreparatoreAggiornamento.Preparazione.NonRiuscito)
        assertFalse("il file sbagliato non resta", cartella.listFiles()!!.any { it.length() > 0 })
    }

    @Test
    fun `dimensione diversa - scartato`() = runBlocking {
        server.rispondi("GET /scarica/sol.apk", contenuto)
        assertTrue(preparatore.prepara(versione(byte = 5)) is PreparatoreAggiornamento.Preparazione.NonRiuscito)
    }

    @Test
    fun `senza impronta dal server non si scarica nemmeno`() = runBlocking {
        val e = preparatore.prepara(versione(sha256 = null)) as PreparatoreAggiornamento.Preparazione.NonRiuscito
        assertTrue(e.messaggio.contains("impronta"))
        assertTrue(server.richiesteA("GET /scarica/sol.apk").isEmpty())
    }
}
