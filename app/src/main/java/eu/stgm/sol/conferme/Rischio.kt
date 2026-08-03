package eu.stgm.sol.conferme

// La classe di rischio la decide il codice, mai il modello. Ogni tool nuovo
// DEVE comparire in questa mappa: il test ConfermeTest la tiene allineata
// con le definizioni dei tool passate a Claude.
enum class Rischio { VERDE, GIALLO, ROSSO }

object RegoleDiRischio {

    val perTool = mapOf(
        // Fase 1 — promemoria
        "lista_promemoria" to Rischio.VERDE,
        "crea_promemoria" to Rischio.GIALLO,
        "completa_promemoria" to Rischio.GIALLO,
        "sposta_promemoria" to Rischio.GIALLO,
        // Annullare rimette le cose com'erano: è la rete di sicurezza del
        // giallo, non un'azione da proteggere.
        "annulla_ultima_azione" to Rischio.VERDE,
        // Eliminare è il banco di prova del rosso: sbagliare qui costa zero,
        // così quando il rosso proteggerà le email il meccanismo sarà rodato.
        "elimina_promemoria" to Rischio.ROSSO,
    )

    // Un tool non mappato è un errore di programmazione: nel dubbio, massima
    // cautela invece di esecuzione libera.
    fun di(tool: String): Rischio = perTool[tool] ?: Rischio.ROSSO
}
