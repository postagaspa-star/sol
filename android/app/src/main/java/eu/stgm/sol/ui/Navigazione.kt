package eu.stgm.sol.ui

/**
 * Le quattro schede fisse della barra in basso: quelle di tutti i giorni. Le
 * Impostazioni, che si aprono di rado, sono una pagina (la rotella in alto):
 * con cinque voci le etichette non ci starebbero intere su un telefono stretto.
 */
enum class Scheda(val etichetta: String) {
    POSTA("Posta"),
    RIASSUNTI("Riassunti"),
    INDIETRO("In sospeso"),
    SCADENZE("Scadenze"),
}

/** Le pagine che si aprono sopra una scheda. */
sealed interface Pagina {
    data class Mail(val id: Long) : Pagina
    data class Bozza(val id: Long) : Pagina
    data object Impostazioni : Pagina
    data object Abbinamento : Pagina
}

/**
 * Dove si trova Manuel: una scheda e, sopra, una pila di pagine. Indietro
 * chiude l'ultima pagina; da una scheda torna alla Posta; dalla Posta esce.
 */
data class Navigazione(val scheda: Scheda = Scheda.POSTA, val pagine: List<Pagina> = emptyList()) {
    val inCima: Pagina? get() = pagine.lastOrNull()

    fun scegli(scheda: Scheda) = Navigazione(scheda)

    fun apri(pagina: Pagina): Navigazione =
        if (inCima == pagina) this else copy(pagine = pagine.filterNot { it == pagina } + pagina)

    /** null = si esce dall'app. */
    fun indietro(): Navigazione? = when {
        pagine.isNotEmpty() -> copy(pagine = pagine.dropLast(1))
        scheda != Scheda.POSTA -> Navigazione(Scheda.POSTA)
        else -> null
    }

    /** In testo, per sopravvivere alla rotazione e alla chiusura da parte di Android. */
    fun codifica(): String = (listOf(scheda.name) + pagine.map {
        when (it) {
            is Pagina.Mail -> "mail:${it.id}"
            is Pagina.Bozza -> "bozza:${it.id}"
            Pagina.Impostazioni -> "impostazioni"
            Pagina.Abbinamento -> "abbinamento"
        }
    }).joinToString("|")

    companion object {
        fun decodifica(testo: String?): Navigazione {
            val parti = testo?.split('|').orEmpty()
            val scheda = Scheda.entries.firstOrNull { it.name == parti.firstOrNull() } ?: Scheda.POSTA
            val pagine = parti.drop(1).mapNotNull { p ->
                when {
                    p.startsWith("mail:") -> p.removePrefix("mail:").toLongOrNull()?.let { Pagina.Mail(it) }
                    p.startsWith("bozza:") -> p.removePrefix("bozza:").toLongOrNull()?.let { Pagina.Bozza(it) }
                    p == "impostazioni" -> Pagina.Impostazioni
                    p == "abbinamento" -> Pagina.Abbinamento
                    else -> null
                }
            }
            return Navigazione(scheda, pagine)
        }
    }
}
