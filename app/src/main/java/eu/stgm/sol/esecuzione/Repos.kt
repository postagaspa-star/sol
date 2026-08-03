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
// Tabella di marcia:
//   Milestone 2 → PromemoriaRepoLocale con Room (zero OAuth, zero rete)
//   Fase 2      → CalendarioRepo: GoogleCalendarAdapter + GraphCalendarAdapter
//   Fase 3      → EmailRepo (provider da decidere con la domanda al papà)
interface PromemoriaRepo {
    suspend fun crea(titolo: String, scadenzaIso: String?): String
    suspend fun lista(): List<String>
    suspend fun completa(id: String): Boolean
    suspend fun elimina(id: String): Boolean
}

object ScritturaPrimaria {
    const val PROVIDER = "google"
}
