package eu.stgm.sol.esecuzione

// Strato di esecuzione: interfacce neutre, un'implementazione per provider.
// I primi tre strati non sanno quale provider stanno usando — è questo che
// permette di aggiungere calendario (fase 2) ed email (fase 3) senza
// riscrivere le fondamenta.
//
// Regola del doppio ecosistema (Google + Microsoft 365), decisa il 03/08/2026:
// in LETTURA si aggregano entrambi (i conflitti si controllano sull'unione),
// in SCRITTURA si va sul primario: Google.
//
// Fase 1: PromemoriaRepoLocale su file (zero OAuth, zero rete). Se un giorno
// servisse la sync col cloud, si aggiunge un adapter dietro questa stessa
// interfaccia.
data class Promemoria(
    val id: Int,
    val titolo: String,
    val scadenzaIso: String?,
    val completato: Boolean = false,
)

interface PromemoriaRepo {
    suspend fun crea(titolo: String, scadenzaIso: String?): Promemoria
    suspend fun lista(): List<Promemoria> // solo attivi (non completati)
    suspend fun perId(id: Int): Promemoria?
    suspend fun completa(id: Int): Promemoria?
    suspend fun sposta(id: Int, nuovaScadenzaIso: String): Promemoria?
    suspend fun elimina(id: Int): Promemoria?

    // Per l'annulla a caldo: rimette un promemoria com'era (reinserendolo
    // se nel frattempo è stato tolto).
    suspend fun ripristina(promemoria: Promemoria)
}

object ScritturaPrimaria {
    const val PROVIDER = "google"
}
