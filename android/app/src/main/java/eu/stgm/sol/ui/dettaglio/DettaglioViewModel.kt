package eu.stgm.sol.ui.dettaglio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.RifBozza
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import eu.stgm.sol.ui.GestoreBozza
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

data class StatoDettaglio(
    val mailId: Long? = null,
    val mail: Mail? = null,
    val gruppi: List<Gruppo> = emptyList(),
    val modalitaProva: Boolean = false,
    val caricamento: Boolean = false,
    val inCorso: Boolean = false,
    /** Problema nel leggere la mail (con eventuali dati vecchi). */
    val errore: String? = null,
    val daCacheDel: Long? = null,
    val messaggio: String? = null,
    /** Un gesto (sposta, letta...) non è riuscito: si dice in una finestra. */
    val erroreAzione: String? = null,
)

/**
 * Una mail aperta. Un solo ViewModel per tutte le mail: [apri] lo riporta sulla
 * mail giusta (una notifica, un riferimento in un riassunto, una riga di elenco).
 */
class DettaglioViewModel(
    private val client: SolClient,
    private val cache: Cache,
    intervalloBozzaMs: Long = 2_000,
    /** Il testo delle bozze in modifica: sui file dell'app, così non si perde mai. */
    memoriaBozze: Cache = CacheMemoria(),
    verificaOgniMs: Long = 10_000,
    verificaPerMs: Long = 120_000,
) : ViewModel() {

    private val _stato = MutableStateFlow(StatoDettaglio())
    val stato: StateFlow<StatoDettaglio> = _stato.asStateFlow()

    val bozza = GestoreBozza(
        client,
        viewModelScope,
        intervalloBozzaMs,
        memoria = memoriaBozze,
        verificaOgniMs = verificaOgniMs,
        verificaPerMs = verificaPerMs,
        dopoInvio = ::dopoInvio,
    )

    fun apri(mailId: Long) {
        if (_stato.value.mailId == mailId && _stato.value.mail != null) {
            // La stessa mail riaperta: si mostra subito e si rilegge (può essere cambiata).
            bozza.riprendi()
            carica()
            return
        }
        _stato.value = StatoDettaglio(mailId = mailId, caricamento = true)
        bozza.imposta(null)
        carica()
    }

    /** La pagina si chiude o va in secondo piano. */
    fun sospendi() = bozza.ferma()

    fun carica() {
        val id = _stato.value.mailId ?: return
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val gruppi = async { leggiConCache(cache, "gruppi", ListSerializer(Gruppo.serializer())) { client.gruppi() } }
            val impostazioni = async { leggiConCache(cache, "impostazioni", ImpostazioniSol.serializer()) { client.impostazioni() } }
            val mail = leggiConCache(cache, chiave(id), Mail.serializer()) { client.dettaglioMail(id) }
            val g = gruppi.await()
            val i = impostazioni.await()
            if (_stato.value.mailId != id) return@launch
            _stato.update {
                it.copy(
                    mail = mail.dato ?: it.mail,
                    gruppi = (g.dato ?: it.gruppi).sortedBy { gruppo -> gruppo.ordine },
                    modalitaProva = i.dato?.modalitaProva ?: it.modalitaProva,
                    caricamento = false,
                    errore = mail.errore,
                    daCacheDel = mail.daCacheDel,
                )
            }
            // Senza rete la bozza non si legge: meglio nessuna che una vecchia da inviare.
            if (mail.daCacheDel == null) bozza.imposta(mail.dato?.bozza?.id)
        }
    }

    fun sposta(gruppoId: Long, impara: Boolean) {
        val mail = _stato.value.mail ?: return
        azione {
            when (val esito = client.sposta(mail.id, gruppoId, impara)) {
                is Esito.Ok -> {
                    val nuova = esito.dato
                    aggiorna(
                        mail.copy(
                            gruppoId = nuova.gruppoId ?: gruppoId,
                            gruppo = nuova.gruppo ?: nomeGruppo(gruppoId),
                            smistamento = nuova.smistamento.ifBlank { "corretta" },
                            origineSmistamento = nuova.origineSmistamento ?: "utente",
                            applicataInCasella = nuova.applicataInCasella,
                        ),
                    )
                    val dove = nuova.gruppo ?: nomeGruppo(gruppoId) ?: "il gruppo scelto"
                    val ricordo = if (impara) " Sol se lo ricorderà per questo mittente." else ""
                    val prova = if (_stato.value.modalitaProva) " (Modalità prova: nella casella vera non cambia niente.)" else ""
                    "Messa in $dove.$ricordo$prova"
                }
                else -> throw Fallita(esito.messaggio)
            }
        }
    }

    /** "Va bene così" per una mail da controllare. */
    fun confermaGruppo() {
        val mail = _stato.value.mail ?: return
        azione {
            when (val esito = client.confermaGruppo(mail.id)) {
                is Esito.Ok -> {
                    aggiorna(mail.copy(smistamento = "smistata"))
                    "Va bene: resta in ${mail.gruppo ?: "questo gruppo"}."
                }
                else -> throw Fallita(esito.messaggio)
            }
        }
    }

    fun cambiaLetta() {
        val mail = _stato.value.mail ?: return
        val letta = !mail.letta
        azione {
            when (val esito = client.segnaLetta(mail.id, letta)) {
                is Esito.Ok -> {
                    aggiorna(mail.copy(letta = letta))
                    if (letta) "Segnata come letta." else "Segnata come da leggere."
                }
                else -> throw Fallita(esito.messaggio)
            }
        }
    }

    fun cambiaDaRispondere() {
        val mail = _stato.value.mail ?: return
        val valore = !mail.daRispondere
        azione {
            when (val esito = client.daRispondere(mail.id, valore)) {
                is Esito.Ok -> {
                    aggiorna(mail.copy(daRispondere = valore))
                    if (valore) "Rimessa tra quelle da rispondere." else "Tolta da quelle da rispondere."
                }
                else -> throw Fallita(esito.messaggio)
            }
        }
    }

    /**
     * Rifà la bozza partendo da quella attuale (15.13); se non c'è, o è stata
     * scartata, ne chiede una nuova per la mail.
     */
    fun rifaiBozza(istruzioni: String?) {
        val mail = _stato.value.mail ?: return
        val attuale = bozza.stato.value.bozza
        if (attuale == null || attuale.stato == Bozza.SCARTATA) bozza.preparaPerMail(mail.id, istruzioni) else bozza.rifai(istruzioni)
    }

    /** Prima di aprire un'altra mail (da una notifica): c'è testo scritto e non salvato? */
    fun haModificheNonSalvate(): Boolean = bozza.haModificheNonSalvate()

    fun consumaMessaggio() = _stato.update { it.copy(messaggio = null) }
    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }

    private fun dopoInvio(inviata: Bozza) {
        val mail = _stato.value.mail ?: return
        if (mail.id == inviata.mailId || inviata.mailId == null) {
            aggiorna(mail.copy(rispostaData = true, bozza = RifBozza(inviata.id, inviata.stato)))
        }
    }

    private fun aggiorna(mail: Mail) {
        _stato.update { it.copy(mail = mail) }
        viewModelScope.launch(Dispatchers.IO) { cache.scrivi(chiave(mail.id), JsonSol.encodeToString(Mail.serializer(), mail)) }
    }

    private fun nomeGruppo(id: Long) = _stato.value.gruppi.firstOrNull { it.id == id }?.nome

    private class Fallita(val motivo: String?) : Exception()

    /** Un gesto sulla mail: uno alla volta, con l'esito sempre mostrato. */
    private fun azione(blocco: suspend () -> String) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            val esito = try {
                Result.success(blocco())
            } catch (e: Fallita) {
                Result.failure(e)
            }
            _stato.update {
                it.copy(
                    inCorso = false,
                    messaggio = esito.getOrNull(),
                    erroreAzione = (esito.exceptionOrNull() as? Fallita)?.motivo,
                )
            }
        }
    }

    private fun chiave(id: Long) = "mail-$id"
}
