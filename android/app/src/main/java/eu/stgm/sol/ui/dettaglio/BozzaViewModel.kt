package eu.stgm.sol.ui.dettaglio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.ui.GestoreBozza

/**
 * Una bozza da sola, senza la mail sopra: il sollecito di una mail inviata
 * ("Niente resta indietro"), che non risponde a nessuna mail ricevuta.
 */
class BozzaViewModel(
    client: SolClient,
    intervalloMs: Long = 2_000,
    memoriaBozze: Cache = CacheMemoria(),
) : ViewModel() {
    val bozza = GestoreBozza(client, viewModelScope, intervalloMs, memoria = memoriaBozze)

    fun apri(id: Long, giaLetta: Bozza? = null) {
        if (giaLetta != null && giaLetta.id == id) bozza.mostra(giaLetta) else bozza.imposta(id)
    }

    /** 15.5: anche un sollecito si rifà con istruzioni, partendo da quello attuale. */
    fun rifai(istruzioni: String?) = bozza.rifai(istruzioni)

    fun sospendi() = bozza.ferma()
}
