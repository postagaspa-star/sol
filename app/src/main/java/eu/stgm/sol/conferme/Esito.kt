package eu.stgm.sol.conferme

// Regola della casa: OGNI esito parla, anche gli annullamenti. Chi usa Sol
// non guarda lo schermo: se l'app tace dopo una richiesta, non può sapere se
// l'azione è partita o no. Il silenzio non è mai un esito valido.
sealed class Esito(val frase: String) {

    class Confermato(frase: String) : Esito(frase)

    class Eseguito(frase: String) : Esito(frase)

    object AnnullatoTimeout :
        Esito("Non ho sentito la conferma: azione annullata.")

    object AnnullatoFraseDiversa :
        Esito("Non era la frase di conferma: azione annullata.")

    object AnnullatoDallUtente :
        Esito("Va bene, annullato.")

    class Fallito(frase: String) : Esito(frase)
}
