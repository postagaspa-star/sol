package eu.stgm.sol.conferme

import eu.stgm.sol.cervello.Proposta
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

// Il notaio. Claude propone, questo cancello dispone:
//   VERDE  → esegui e basta
//   GIALLO → esegui subito, poi "annulla" resta caldo per qualche secondo
//   ROSSO  → doppia conferma: card a schermo + frase dedicata a voce.
// La frase rossa vale solo mentre la finestra è aperta, il confronto è
// deterministico (Normalizzatore), il timeout ANNULLA e lo dice a voce.
// Un "sì" generico non basta mai per il rosso.
//
// Le dipendenze sono lambda per due motivi: il cancello resta collaudabile
// su JVM, e non sa nulla di Android — sa solo ascoltare una frase e mostrare
// una card (la card vera arriva con la milestone 3).
class CancelloConferme(
    private val ascoltaFrase: suspend () -> String?, // null = non ha sentito nulla
    private val mostraCard: suspend (Proposta) -> CardConferma,
) {

    suspend fun doppiaConferma(proposta: Proposta): Esito {
        val card = mostraCard(proposta)
        val udito = withTimeoutOrNull(FINESTRA) { ascoltaFrase() }
        card.chiudi()
        val normalizzato = udito?.let(Normalizzatore::normalizza)
        return when {
            card.pulsantePremuto -> Esito.Confermato("Confermato: eseguo.")
            udito == null -> Esito.AnnullatoTimeout
            normalizzato == FRASE_ROSSA -> Esito.Confermato("Confermato: eseguo.")
            normalizzato in FRASI_ANNULLA -> Esito.AnnullatoDallUtente
            else -> Esito.AnnullatoFraseDiversa
        }
    }

    companion object {
        // "Timbro e firma": il gesto dell'atto irrevocabile di un perito
        // industriale. Facile da ricordare, e non scappa parlando al telefono
        // — anche perché vale solo dentro la finestra di conferma.
        const val FRASE_ROSSA = "timbro e firma"
        val FRASI_ANNULLA = setOf("annulla", "no", "lascia stare", "lascia perdere")
        val FINESTRA = 20.seconds
    }
}

// Contratto minimo della card di conferma: la UI vera (payload esatto in
// vista, pulsante esplicito "Elimina davvero…") arriva con la milestone 3,
// ma il cancello è già collaudabile per la via vocale.
interface CardConferma {
    val pulsantePremuto: Boolean
    fun chiudi()
}
