package eu.stgm.sol.ui

/** Le tre voci della barra in basso (docs/aspetto.md §2). */
enum class Scheda(val etichetta: String) {
    OGGI("Oggi"),
    POSTA("Posta"),
    RIASSUNTI("Riassunti"),
}

/** Le pagine che si aprono sopra una scheda. */
sealed interface Pagina {
    data class Mail(val id: Long) : Pagina
    data class Bozza(val id: Long) : Pagina
    data class Raccolta(val gruppoId: Long) : Pagina
    data object Impostazioni : Pagina
    data object Abbinamento : Pagina
    data object Smista : Pagina
    data object Ricerca : Pagina
    data object Scadenze : Pagina
    data object AspettiRisposta : Pagina
    data object Tutorial : Pagina
}

/**
 * Dove si trova Manuel: una scheda e, sopra, una pila di pagine. Indietro
 * chiude l'ultima pagina; da una scheda torna a Oggi; da Oggi esce.
 */
data class Navigazione(val scheda: Scheda = Scheda.OGGI, val pagine: List<Pagina> = emptyList()) {
    val inCima: Pagina? get() = pagine.lastOrNull()

    fun scegli(scheda: Scheda) = Navigazione(scheda)

    fun apri(pagina: Pagina): Navigazione =
        if (inCima == pagina) this else copy(pagine = pagine.filterNot { it == pagina } + pagina)

    /** null = si esce dall'app. */
    fun indietro(): Navigazione? = when {
        pagine.isNotEmpty() -> copy(pagine = pagine.dropLast(1))
        scheda != Scheda.OGGI -> Navigazione(Scheda.OGGI)
        else -> null
    }

    /** In testo, per sopravvivere alla rotazione e alla chiusura da parte di Android. */
    fun codifica(): String = (listOf(scheda.name) + pagine.map {
        when (it) {
            is Pagina.Mail -> "mail:${it.id}"
            is Pagina.Bozza -> "bozza:${it.id}"
            is Pagina.Raccolta -> "raccolta:${it.gruppoId}"
            Pagina.Impostazioni -> "impostazioni"
            Pagina.Abbinamento -> "abbinamento"
            Pagina.Smista -> "smista"
            Pagina.Ricerca -> "ricerca"
            Pagina.Scadenze -> "scadenze"
            Pagina.AspettiRisposta -> "aspetti"
            Pagina.Tutorial -> "tutorial"
        }
    }).joinToString("|")

    companion object {
        fun decodifica(testo: String?): Navigazione {
            val parti = testo?.split('|').orEmpty()
            val scheda = Scheda.entries.firstOrNull { it.name == parti.firstOrNull() } ?: Scheda.OGGI
            val pagine = parti.drop(1).mapNotNull { p ->
                when {
                    p.startsWith("mail:") -> p.removePrefix("mail:").toLongOrNull()?.let { Pagina.Mail(it) }
                    p.startsWith("bozza:") -> p.removePrefix("bozza:").toLongOrNull()?.let { Pagina.Bozza(it) }
                    p.startsWith("raccolta:") -> p.removePrefix("raccolta:").toLongOrNull()?.let { Pagina.Raccolta(it) }
                    p == "impostazioni" -> Pagina.Impostazioni
                    p == "abbinamento" -> Pagina.Abbinamento
                    p == "smista" -> Pagina.Smista
                    p == "ricerca" -> Pagina.Ricerca
                    p == "scadenze" -> Pagina.Scadenze
                    p == "aspetti" -> Pagina.AspettiRisposta
                    p == "tutorial" -> Pagina.Tutorial
                    else -> null
                }
            }
            return Navigazione(scheda, pagine)
        }
    }
}
