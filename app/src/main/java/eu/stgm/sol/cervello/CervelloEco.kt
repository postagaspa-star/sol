package eu.stgm.sol.cervello

// Milestone 1: nessuna AI, ripete ciò che ha capito. Serve a collaudare la
// pipeline voce → testo → voce (e a misurare la qualità della trascrizione
// sul campo) prima di attaccarci Claude.
class CervelloEco : Cervello {
    override suspend fun elabora(testo: String) =
        Risposta(frase = "Ho sentito: $testo")
}
