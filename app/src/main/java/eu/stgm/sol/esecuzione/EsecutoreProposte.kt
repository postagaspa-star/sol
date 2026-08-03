package eu.stgm.sol.esecuzione

import eu.stgm.sol.cervello.Proposta
import eu.stgm.sol.conferme.CancelloConferme
import eu.stgm.sol.conferme.CardVoceStub
import eu.stgm.sol.conferme.Esito
import eu.stgm.sol.conferme.RegoleDiRischio
import eu.stgm.sol.conferme.Rischio
import eu.stgm.sol.dati.FormattatoreDate
import java.time.LocalDate
import java.time.LocalDateTime

// La voce vista dall'esecutore: leggere una frase, ascoltarne una. Il
// servizio la implementa con TTS + trascrittore; nei test è un finto.
interface InterazioneVocale {
    suspend fun leggi(frase: String)
    suspend fun ascoltaUnaFrase(): String?
}

// L'unico punto del programma da cui si eseguono azioni. Il cervello NON
// tocca mai repo o pianificatore: consegna proposte, e qui il cancello
// decide. Qui vive anche l'"annulla" a caldo del giallo: l'ultima azione
// reversibile resta annullabile per una finestra breve.
class EsecutoreProposte(
    private val repo: PromemoriaRepo,
    private val pianificatore: Pianificatore,
    private val voce: InterazioneVocale,
    private val registro: (String, String) -> Unit,
    private val adesso: () -> LocalDateTime = { LocalDateTime.now() },
    private val orologioMillis: () -> Long = { System.currentTimeMillis() },
) {

    data class Risultato(
        val contenutoPerClaude: String,
        val fraseImmediata: String? = null,
        val turnoInterrotto: Boolean = false,
    )

    private class AzioneAnnullabile(
        val fraseDopoAnnullo: String,
        val entroMillis: Long,
        val annulla: suspend () -> Unit,
    )

    private val cancello = CancelloConferme(
        leggi = { voce.leggi(it) },
        ascoltaFrase = { voce.ascoltaUnaFrase() },
        mostraCard = { CardVoceStub }, // milestone 3: la card vera
    )

    private var ultima: AzioneAnnullabile? = null

    suspend fun esegui(proposta: Proposta): Risultato =
        when (RegoleDiRischio.di(proposta.tool)) {
            Rischio.VERDE, Rischio.GIALLO -> Risultato(eseguiDavvero(proposta))
            Rischio.ROSSO -> rosso(proposta)
        }

    private suspend fun rosso(proposta: Proposta): Risultato {
        val riepilogo = riepilogoRosso(proposta)
            ?: return Risultato("Nessun promemoria con quell'id.")
        val esito = cancello.doppiaConferma(proposta, riepilogo)
        registro("conferma_rossa", "${proposta.tool}: ${esito.javaClass.simpleName}")
        return when (esito) {
            is Esito.Confermato -> {
                voce.leggi(esito.frase)
                Risultato(eseguiDavvero(proposta))
            }
            // Rosso annullato: il turno si chiude QUI e la frase dell'esito
            // viene detta per certo — non si delega al riassunto del modello.
            else -> Risultato(
                contenutoPerClaude = "Azione annullata dall'utente.",
                fraseImmediata = esito.frase,
                turnoInterrotto = true,
            )
        }
    }

    private suspend fun riepilogoRosso(proposta: Proposta): String? = when (proposta.tool) {
        "elimina_promemoria" -> {
            val trovato = proposta.argomenti["id"]?.toIntOrNull()?.let { repo.perId(it) }
            trovato?.let {
                "Sto per eliminare definitivamente il promemoria: ${it.titolo}, " +
                    "${FormattatoreDate.leggibile(it.scadenzaIso, oggi())}. " +
                    "Per confermare di': ${CancelloConferme.FRASE_ROSSA}. Per annullare di': annulla."
            }
        }
        // Tool sconosciuto finito rosso per prudenza: chiedere comunque.
        else -> "Sto per eseguire l'azione ${proposta.tool}. " +
            "Per confermare di': ${CancelloConferme.FRASE_ROSSA}. Per annullare di': annulla."
    }

    // ---- Le azioni vere e proprie ----

    private suspend fun eseguiDavvero(p: Proposta): String = when (p.tool) {
        "lista_promemoria" -> {
            val attivi = repo.lista()
            if (attivi.isEmpty()) {
                "Nessun promemoria attivo."
            } else {
                attivi.joinToString("; ") {
                    "id ${it.id}: ${it.titolo} (${FormattatoreDate.leggibile(it.scadenzaIso, oggi())})"
                }
            }
        }

        "crea_promemoria" -> {
            val titolo = p.argomenti["titolo"]?.trim().orEmpty()
            val scadenza = p.argomenti["scadenza"]?.trim()?.takeIf { it.isNotBlank() }
            when {
                titolo.isBlank() -> "Errore: manca il titolo."
                scadenza != null && !FormattatoreDate.valida(scadenza) ->
                    "Errore: scadenza non valida, usa il formato YYYY-MM-DDTHH:MM."
                else -> {
                    val nuovo = repo.crea(titolo, scadenza)
                    pianificatore.pianifica(nuovo)
                    ricordaAnnullo("Annullato: ho tolto il promemoria ${nuovo.titolo}.") {
                        repo.elimina(nuovo.id)
                        pianificatore.cancella(nuovo.id)
                    }
                    "Creato il promemoria id ${nuovo.id}: ${nuovo.titolo}, " +
                        FormattatoreDate.leggibile(nuovo.scadenzaIso, oggi()) + "."
                }
            }
        }

        "completa_promemoria" -> conId(p) { prima ->
            repo.completa(prima.id)
            pianificatore.cancella(prima.id)
            ricordaAnnullo("Annullato: ${prima.titolo} è di nuovo attivo.") {
                repo.ripristina(prima)
                pianificatore.pianifica(prima)
            }
            "Completato: ${prima.titolo}."
        }

        "sposta_promemoria" -> conId(p) { prima ->
            val nuova = p.argomenti["nuova_scadenza"]?.trim().orEmpty()
            if (!FormattatoreDate.valida(nuova)) {
                "Errore: nuova scadenza non valida, usa il formato YYYY-MM-DDTHH:MM."
            } else {
                val dopo = repo.sposta(prima.id, nuova)
                pianificatore.cancella(prima.id)
                dopo?.let(pianificatore::pianifica)
                ricordaAnnullo(
                    "Annullato: ${prima.titolo} è tornato a " +
                        FormattatoreDate.leggibile(prima.scadenzaIso, oggi()) + "."
                ) {
                    repo.ripristina(prima)
                    pianificatore.cancella(prima.id)
                    pianificatore.pianifica(prima)
                }
                "Spostato: ${prima.titolo}, ora " +
                    FormattatoreDate.leggibile(nuova, oggi()) + "."
            }
        }

        "elimina_promemoria" -> conId(p) { prima ->
            repo.elimina(prima.id)
            pianificatore.cancella(prima.id)
            // Il rosso è definitivo per contratto: niente finestra di annullo,
            // la protezione sta PRIMA (doppia conferma), non dopo.
            ultima = null
            "Eliminato definitivamente: ${prima.titolo}."
        }

        "annulla_ultima_azione" -> annullaUltima()

        else -> "Tool sconosciuto: ${p.tool}."
    }

    private suspend fun conId(p: Proposta, blocco: suspend (Promemoria) -> String): String {
        val id = p.argomenti["id"]?.toIntOrNull()
            ?: return "Errore: manca l'id del promemoria."
        val trovato = repo.perId(id)
            ?: return "Nessun promemoria con id $id."
        return blocco(trovato)
    }

    // ---- L'annulla a caldo del giallo ----

    private fun ricordaAnnullo(fraseDopoAnnullo: String, annulla: suspend () -> Unit) {
        ultima = AzioneAnnullabile(fraseDopoAnnullo, orologioMillis() + FINESTRA_ANNULLO_MS, annulla)
    }

    fun annullabile(): Boolean =
        ultima?.let { orologioMillis() < it.entroMillis } ?: false

    suspend fun annullaUltima(): String {
        val da = ultima
        ultima = null
        return if (da == null || orologioMillis() >= da.entroMillis) {
            "Non c'era più nulla da annullare."
        } else {
            da.annulla()
            registro("annullato", da.fraseDopoAnnullo)
            da.fraseDopoAnnullo
        }
    }

    private fun oggi(): LocalDate = adesso().toLocalDate()

    private companion object {
        const val FINESTRA_ANNULLO_MS = 30_000L
    }
}
