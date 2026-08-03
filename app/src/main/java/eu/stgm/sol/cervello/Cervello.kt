package eu.stgm.sol.cervello

// Il cervello riceve il testo trascritto e restituisce una frase da dire e,
// dalla milestone 2, le proposte di azione (tool call) da far passare dal
// cancello conferme. Regola della casa: il cervello PROPONE, non esegue mai.
interface Cervello {
    suspend fun elabora(testo: String): Risposta
}

data class Risposta(
    val frase: String,
    val proposte: List<Proposta> = emptyList(),
)

// Una proposta di azione come arriva dal modello: nome del tool + argomenti.
data class Proposta(
    val tool: String,
    val argomenti: Map<String, String> = emptyMap(),
)
