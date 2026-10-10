package eu.stgm.sol

import eu.stgm.sol.dati.DepositoConfigurazione
import eu.stgm.sol.dati.MemoriaNovita
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.rete.SolClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un server Sol finto (MockWebServer): ogni "METODO /percorso" ha la sua
 * risposta, o una coda di risposte che si consumano in ordine (l'ultima resta).
 * Registra tutte le richieste, per contare per esempio quanti invii sono partiti.
 */
class ServerFinto : AutoCloseable {
    private val server = MockWebServer()
    private val risposte = mutableMapOf<String, ArrayDeque<MockResponse>>()
    val richieste = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                richieste += request
                val chiave = "${request.method} ${request.path}"
                val coda = synchronized(risposte) {
                    risposte[chiave] ?: risposte["${request.method} ${request.path?.substringBefore('?')}"]
                } ?: return MockResponse().setResponseCode(404).setBody("""{"errore":"Non trovato (finto)","codice":"non_trovato"}""")
                return synchronized(risposte) { if (coda.size > 1) coda.removeFirst() else coda.first() }
            }
        }
        server.start()
    }

    val url: String get() = server.url("").toString().trimEnd('/')

    fun rispondi(metodoPercorso: String, corpo: String, codice: Int = 200) =
        rispondi(metodoPercorso, MockResponse().setResponseCode(codice).setBody(corpo).setHeader("Content-Type", "application/json"))

    fun rispondi(metodoPercorso: String, vararg risposta: MockResponse) {
        synchronized(risposte) { risposte[metodoPercorso] = ArrayDeque(risposta.toList()) }
    }

    /** Più risposte JSON in fila per la stessa richiesta (per esempio una bozza che si prepara). */
    fun inFila(metodoPercorso: String, vararg corpi: String) =
        rispondi(metodoPercorso, *corpi.map { MockResponse().setBody(it).setHeader("Content-Type", "application/json") }.toTypedArray())

    fun richiesteA(metodoPercorso: String): List<RecordedRequest> =
        richieste.filter { "${it.method} ${it.path}" == metodoPercorso || "${it.method} ${it.path?.substringBefore('?')}" == metodoPercorso }

    fun client(
        token: String = "token-finto-di-prova",
        dispositivoId: Long? = 3,
        alNonAutorizzato: suspend () -> Unit = {},
    ): SolClient = SolClient({ Configurazione(url, token, dispositivoId) }, alNonAutorizzato)

    fun spegni() = server.shutdown()

    override fun close() {
        try {
            server.shutdown()
        } catch (e: Exception) {
            // già spento
        }
    }
}

class DepositoMemoria(iniziale: Configurazione? = null) : DepositoConfigurazione {
    private val valore = MutableStateFlow(iniziale)
    override val flusso: Flow<Configurazione?> = valore
    override suspend fun leggi(): Configurazione? = valore.value
    override suspend fun salva(configurazione: Configurazione) {
        valore.value = configurazione
    }
    override suspend fun dimentica() {
        valore.value = null
    }
}

class MemoriaNovitaFinta : MemoriaNovita {
    var cursore: String? = null
    var avvisati: Set<String> = emptySet()
    var avvisoScollegato = false
    override suspend fun cursore(): String? = cursore
    override suspend fun avvisati(): Set<String> = avvisati
    override suspend fun ricorda(cursore: String?, avvisati: Set<String>) {
        if (cursore != null) this.cursore = cursore
        this.avvisati = avvisati
    }
    override suspend fun avvisoScollegatoDato(): Boolean = avvisoScollegato
    override suspend fun segnaAvvisoScollegatoDato() {
        avvisoScollegato = true
    }
}

/**
 * Main = Unconfined vero (non virtuale): le chiamate di rete finiscono su
 * thread di OkHttp e le attese tra un giro e l'altro sono millisecondi veri.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegolaMain : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Aspetta (al massimo 5 s) che [condizione] diventi vera. */
fun aspetta(messaggio: String = "condizione mai vera", condizione: () -> Boolean) = runBlocking {
    try {
        withTimeout(5_000) {
            while (!condizione()) delay(10)
        }
    } catch (e: Exception) {
        throw AssertionError("Tempo scaduto: $messaggio", e)
    }
}
