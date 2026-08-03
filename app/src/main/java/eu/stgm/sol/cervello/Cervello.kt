package eu.stgm.sol.cervello

// Il cervello riceve il testo trascritto e restituisce la frase finale da
// dire. Le azioni succedono DURANTE l'elaborazione, ma sempre attraverso
// l'EsecutoreProposte e quindi il cancello conferme. Regola della casa:
// il cervello PROPONE, non esegue mai nulla in proprio.
interface Cervello {
    suspend fun elabora(testo: String): Risposta
}

data class Risposta(
    val frase: String,
)

// Una proposta di azione come arriva dal modello: nome del tool + argomenti
// (i valori restano stringhe: la validazione fine la fa l'esecutore).
data class Proposta(
    val tool: String,
    val argomenti: Map<String, String> = emptyMap(),
)
