package eu.stgm.sol.conferme

// La classe di rischio la decide il codice, mai il modello. Ogni tool nuovo
// DEVE comparire in questa mappa: il test ConfermeTest tiene il conto.
enum class Rischio { VERDE, GIALLO, ROSSO }

object RegoleDiRischio {

    val perTool = mapOf(
        // Fase 1 — promemoria (i tool arrivano con la milestone 2)
        "lista_promemoria" to Rischio.VERDE,
        "crea_promemoria" to Rischio.GIALLO,
        "completa_promemoria" to Rischio.GIALLO,
        "sposta_promemoria" to Rischio.GIALLO,
        // Eliminare è il banco di prova del rosso: sbagliare qui costa zero,
        // così quando il rosso proteggerà le email il meccanismo sarà rodato.
        "elimina_promemoria" to Rischio.ROSSO,
    )

    // Un tool non mappato è un errore di programmazione: nel dubbio, massima
    // cautela invece di esecuzione libera.
    fun di(tool: String): Rischio = perTool[tool] ?: Rischio.ROSSO
}
