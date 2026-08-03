package eu.stgm.sol.conferme

import java.text.Normalizer

// Confronto deterministico della frase di conferma: minuscole, senza accenti,
// senza punteggiatura, spazi compattati. Qui l'AI non c'entra per scelta:
// o la frase è quella, o l'azione si annulla.
object Normalizzatore {

    fun normalizza(testo: String): String =
        Normalizer.normalize(testo, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
