package eu.stgm.sol.notifiche

import eu.stgm.sol.dati.MemoriaNovita
import eu.stgm.sol.dati.Novita
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.Testi

/**
 * Una notifica da mostrare. Al tocco si apre [mailId] se c'è, altrimenti la bozza
 * [bozzaId] (un sollecito non ha una mail ricevuta), altrimenti la Posta.
 */
data class Avviso(
    val chiave: String,
    val titolo: String,
    val testo: String,
    val mailId: Long?,
    val urgente: Boolean = false,
    val bozzaId: Long? = null,
)

/** Oltre questo numero di mail urgenti (o di bozze) nello stesso giro, una notifica di riepilogo. */
const val MASSIMO_URGENTI_SINGOLE = 4

/** L'avviso che il telefono non è più abbinato: una volta sola. */
val AVVISO_SCOLLEGATO = Avviso(
    chiave = "scollegato",
    titolo = "Sol non è più collegato",
    testo = "Apri l'app per abbinare di nuovo il telefono.",
    mailId = null,
)

/**
 * Cosa notificare di una risposta di GET /api/novita. Solo le cose che meritano
 * di interrompere Manuel: mail urgenti non lette, bozze diventate pronte,
 * scadenze di domani. Le "da rispondere" normali no: le vede aprendo l'app.
 *
 * Le bozze vengono SOLO da `bozze_nuove` (15.6, 15.13): `bozze_pronte` è il
 * totale, e notificarlo farebbe tornare lo stesso avviso a ogni giro.
 *
 * [primoGiro]: la prima chiamata senza cursore serve solo a prendere il cursore
 * (contratto §12), niente notifiche. [giaAvvisati]: le chiavi già notificate,
 * perché le scadenze di domani tornano a ogni giro della giornata.
 */
fun avvisiDaMostrare(novita: Novita, giaAvvisati: Set<String>, primoGiro: Boolean): List<Avviso> {
    if (primoGiro) return emptyList()
    val avvisi = mutableListOf<Avviso>()

    val urgenti = novita.urgenti
        .filter { !it.letta }
        .filter { chiaveUrgente(it.id) !in giaAvvisati }
    urgenti.take(MASSIMO_URGENTI_SINGOLE).forEach { mail ->
        avvisi += Avviso(
            chiave = chiaveUrgente(mail.id),
            titolo = "Mail urgente da ${mail.mittente.etichetta}",
            testo = mail.riassunto?.takeIf { it.isNotBlank() } ?: mail.oggetto,
            mailId = mail.id,
            urgente = true,
        )
    }
    val restanti = urgenti.drop(MASSIMO_URGENTI_SINGOLE)
    if (restanti.isNotEmpty()) {
        avvisi += Avviso(
            chiave = "urgenti-altre:" + restanti.joinToString(",") { it.id.toString() },
            titolo = if (restanti.size == 1) "Un'altra mail urgente" else "Altre ${restanti.size} mail urgenti",
            testo = restanti.joinToString(", ") { it.mittente.etichetta },
            mailId = null,
            urgente = true,
        )
    }

    val bozze = novita.bozzeNuove.filter { chiaveBozza(it.id) !in giaAvvisati }
    bozze.take(MASSIMO_URGENTI_SINGOLE).forEach { b ->
        avvisi += Avviso(
            chiave = chiaveBozza(b.id),
            titolo = "Risposta pronta: ${b.oggetto.ifBlank { "senza oggetto" }}",
            testo = "Sol l'ha preparata: leggila prima di inviarla.",
            mailId = b.mailId,
            bozzaId = b.id,
        )
    }
    val altreBozze = bozze.drop(MASSIMO_URGENTI_SINGOLE)
    if (altreBozze.isNotEmpty()) {
        avvisi += Avviso(
            chiave = "bozze-altre:" + altreBozze.joinToString(",") { it.id.toString() },
            titolo = if (altreBozze.size == 1) "Un'altra risposta pronta" else "Altre ${altreBozze.size} risposte pronte",
            testo = altreBozze.joinToString(", ") { it.oggetto },
            mailId = null,
        )
    }

    novita.scadenzeDomani.forEach { scadenza ->
        val chiave = chiaveScadenza(scadenza.id, scadenza.quando)
        if (chiave !in giaAvvisati) {
            avvisi += Avviso(
                chiave = chiave,
                titolo = "Domani: ${scadenza.descrizione}",
                testo = scadenza.ora?.let { "Scadenza di domani alle $it" } ?: "Scadenza di domani",
                mailId = scadenza.mailId,
            )
        }
    }
    return avvisi
}

/**
 * Le chiavi da ricordare dopo il giro: quelle di questa risposta già avvisate o
 * appena avvisate. Le altre si possono dimenticare (urgenti e bozze nuove non
 * tornano, il cursore va avanti), così l'elenco non cresce per sempre.
 */
fun avvisatiDaRicordare(novita: Novita, giaAvvisati: Set<String>, mostrati: List<Avviso>): Set<String> {
    val diQuestaRisposta = novita.urgenti.map { chiaveUrgente(it.id) } +
        novita.bozzeNuove.map { chiaveBozza(it.id) } +
        novita.scadenzeDomani.map { chiaveScadenza(it.id, it.quando) }
    return diQuestaRisposta.filter { it in giaAvvisati }.toSet() + mostrati.map { it.chiave }
}

private fun chiaveUrgente(id: Long) = "urgente:$id"
private fun chiaveBozza(id: Long) = "bozza:$id"
private fun chiaveScadenza(id: Long, quando: String) = "scadenza:$id:$quando"

enum class EsitoGiro { FATTO, NON_ABBINATO, NON_AUTORIZZATO, SENZA_RETE, ERRORE }

/** Un giro del controllo novità: chiede, decide, notifica, ricorda. */
class GiroNovita(
    private val client: SolClient,
    private val memoria: MemoriaNovita,
    private val mostra: (List<Avviso>) -> Unit,
) {
    suspend fun giro(): EsitoGiro {
        val cursore = memoria.cursore()
        return when (val esito = client.novita(cursore)) {
            is Esito.Ok -> {
                val novita = esito.dato
                val gia = memoria.avvisati()
                val avvisi = avvisiDaMostrare(novita, gia, primoGiro = cursore == null)
                if (avvisi.isNotEmpty()) mostra(avvisi)
                memoria.ricorda(novita.cursore ?: cursore, avvisatiDaRicordare(novita, gia, avvisi))
                EsitoGiro.FATTO
            }
            Esito.Irraggiungibile -> EsitoGiro.SENZA_RETE
            is Esito.Errore -> when (esito.codice) {
                Testi.CODICE_NON_ABBINATO -> EsitoGiro.NON_ABBINATO
                // Il client ha già dimenticato il token (confermato da /api/salute);
                // senza questo avviso Manuel scoprirebbe il problema solo aprendo l'app.
                Testi.CODICE_NON_AUTORIZZATO -> {
                    if (!memoria.avvisoScollegatoDato()) {
                        mostra(listOf(AVVISO_SCOLLEGATO))
                        memoria.segnaAvvisoScollegatoDato()
                    }
                    EsitoGiro.NON_AUTORIZZATO
                }
                else -> EsitoGiro.ERRORE
            }
        }
    }
}
