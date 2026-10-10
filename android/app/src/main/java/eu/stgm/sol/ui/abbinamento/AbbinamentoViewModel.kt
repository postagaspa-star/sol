package eu.stgm.sol.ui.abbinamento

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Abbinamento
import eu.stgm.sol.dati.DepositoConfigurazione
import eu.stgm.sol.rete.Configurazione
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.IndirizzoServer
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoAbbinamento(
    val indirizzo: String = "",
    val codice: String = "",
    val nome: String = "Telefono di Manuel",
    val inCorso: Boolean = false,
    val errore: String? = null,
    val fatto: Boolean = false,
)

/** Le cifre del codice, senza spazi o trattini che si scrivono per leggerlo meglio. */
fun soloCifre(testo: String): String = testo.filter { it.isDigit() }

class AbbinamentoViewModel(
    private val deposito: DepositoConfigurazione,
    private val abbina: suspend (server: String, codice: String, nome: String) -> Esito<Abbinamento> =
        { server, codice, nome -> SolClient.abbina(server, codice, nome) },
    /** Prima di salvare il nuovo abbinamento: via i dati del server di prima. */
    private val prima: suspend () -> Unit = {},
) : ViewModel() {

    private val _stato = MutableStateFlow(StatoAbbinamento())
    val stato: StateFlow<StatoAbbinamento> = _stato.asStateFlow()

    fun proponiIndirizzo(indirizzo: String?) {
        if (!indirizzo.isNullOrBlank() && _stato.value.indirizzo.isBlank()) _stato.update { it.copy(indirizzo = indirizzo) }
    }

    fun cambiaIndirizzo(testo: String) = _stato.update { it.copy(indirizzo = testo, errore = null) }
    fun cambiaCodice(testo: String) = _stato.update { it.copy(codice = soloCifre(testo).take(8), errore = null) }
    fun cambiaNome(testo: String) = _stato.update { it.copy(nome = testo, errore = null) }

    fun abbina() {
        val s = _stato.value
        if (s.inCorso) return
        val server = when (val r = IndirizzoServer.normalizza(s.indirizzo)) {
            is IndirizzoServer.Risultato.Valido -> r.url
            is IndirizzoServer.Risultato.NonValido -> {
                _stato.update { it.copy(errore = r.motivo) }
                return
            }
        }
        val codice = soloCifre(s.codice)
        if (codice.length != 8) {
            _stato.update { it.copy(errore = "Il codice è di 8 cifre: lo trovi sul server, dove l'hai fatto preparare.") }
            return
        }
        val nome = s.nome.trim().ifEmpty { "Telefono" }
        _stato.update { it.copy(inCorso = true, errore = null, indirizzo = server) }
        viewModelScope.launch {
            when (val esito = abbina(server, codice, nome)) {
                is Esito.Ok -> {
                    prima()
                    deposito.salva(Configurazione(server, esito.dato.token, esito.dato.dispositivoId))
                    _stato.update { it.copy(inCorso = false, fatto = true, codice = "") }
                }
                Esito.Irraggiungibile -> _stato.update {
                    it.copy(
                        inCorso = false,
                        errore = "Non trovo il server a questo indirizzo. Controlla l'indirizzo e che il telefono " +
                            "sia connesso (un indirizzo che comincia con 192.168 funziona solo col Wi-Fi di casa).",
                    )
                }
                is Esito.Errore -> _stato.update { it.copy(inCorso = false, errore = esito.messaggio) }
            }
        }
    }
}
