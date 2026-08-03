package eu.stgm.sol.conferme

import eu.stgm.sol.cervello.Proposta
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

// Il notaio. Claude propone, questo cancello dispone:
//   VERDE  → esegui e basta
//   GIALLO → esegui subito, poi "annulla" resta caldo per qualche secondo
//   ROSSO  → doppia conferma: card a schermo + frase dedicata a voce.
// Il riepilogo viene LETTO prima di ascoltare (dice esattamente cosa sta
// per succedere e come confermare), la frase vale solo mentre la finestra
// è aperta, il confronto è deterministico (Normalizzatore), il timeout
// ANNULLA — e l'annullamento parla, come ogni esito. Un "sì" generico non
// basta mai per il rosso.
//
// Le dipendenze sono lambda per due motivi: il cancello resta collaudabile
// su JVM, e non sa nulla di Android — sa solo leggere una frase, ascoltarne
// una e mostrare una card (quella vera arriva con la milestone 3).
class CancelloConferme(
    private val leggi: suspend (String) -> Unit,
    private val ascoltaFrase: suspend () -> String?, // null = non ha sentito nulla
    private val mostraCard: suspend (Proposta) -> CardConferma,
) {

    suspend fun doppiaConferma(proposta: Proposta, riepilogo: String): Esito {
        val card = mostraCard(proposta)
        try {
            leggi(riepilogo)
            val udito = withTimeoutOrNull(FINESTRA) { ascoltaFrase() }
            val normalizzato = udito?.let(Normalizzatore::normalizza)
            return when {
                card.pulsantePremuto -> Esito.Confermato("Confermato: eseguo.")
                udito == null -> Esito.AnnullatoTimeout
                normalizzato == FRASE_ROSSA -> Esito.Confermato("Confermato: eseguo.")
                normalizzato in FRASI_ANNULLA -> Esito.AnnullatoDallUtente
                else -> Esito.AnnullatoFraseDiversa
            }
        } finally {
            card.chiudi()
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
// vista, pulsante esplicito "Elimina davvero…") arriva con la milestone 3.
interface CardConferma {
    val pulsantePremuto: Boolean
    fun chiudi()
}

// Milestone 2: il rosso funziona per la sola via vocale.
object CardVoceStub : CardConferma {
    override val pulsantePremuto = false
    override fun chiudi() {}
}
