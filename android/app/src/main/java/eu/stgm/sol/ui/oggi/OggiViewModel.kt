package eu.stgm.sol.ui.oggi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.Indietro
import eu.stgm.sol.dati.Oggi
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.dati.VoceOggi
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import eu.stgm.sol.ui.StilePillola
import eu.stgm.sol.ui.giornoScadenza
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Le frasi della pagina (le compone l'app: il server non scrive testi, 18.1) -------

/** Buongiorno fino all'una, buon pomeriggio fino alle sei, poi buonasera. */
fun saluto(ora: LocalTime): String = when (ora.hour) {
    in 5..12 -> "Buongiorno"
    in 13..17 -> "Buon pomeriggio"
    else -> "Buonasera"
}

/** "Buongiorno, Manuel"; senza nome solo il saluto (18.3). */
fun titoloSaluto(ora: LocalTime, nome: String): String =
    nome.trim().let { if (it.isEmpty()) saluto(ora) else "${saluto(ora)}, $it" }

private val GIORNO_LUNGO = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

/** "Sabato 11 ottobre · 14 mail nuove". */
fun rigaData(data: LocalDate, mailOggi: Int): String {
    val giorno = GIORNO_LUNGO.format(data).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    val mail = when (mailOggi) {
        0 -> "nessuna mail nuova"
        1 -> "1 mail nuova"
        else -> "$mailOggi mail nuove"
    }
    return "$giorno · $mail"
}

/** Cosa fa il pulsante di una voce di "Da fare". */
enum class AzioneVoce { APRI_MAIL, SMISTA, APRI_BOZZA, PREPARA_SOLLECITO, SCADENZA_FATTA }

/** Come appare una scheda di "Da fare": titolo, riga sotto, pulsante. */
data class TestoVoce(
    val titolo: String,
    val sotto: String,
    val pulsante: String,
    val stile: StilePillola,
    val azione: AzioneVoce,
)

/** Le frasi e il pulsante giusto per ogni voce (docs/aspetto.md §3); null = tipo sconosciuto. */
fun testoVoce(v: VoceOggi, oggi: LocalDate = LocalDate.now()): TestoVoce? {
    val chi = v.mittente?.takeIf { it.isNotBlank() } ?: "il mittente"
    val oggetto = v.oggetto?.takeIf { it.isNotBlank() } ?: "(senza oggetto)"
    val urgente = v.urgenza == "alta"
    return when (v.tipo) {
        VoceOggi.TRUFFA -> TestoVoce(
            "Possibile truffa",
            v.riassunto?.takeIf { it.isNotBlank() } ?: oggetto,
            "Guarda", StilePillola.TRUFFA, AzioneVoce.APRI_MAIL,
        )
        VoceOggi.RISPOSTA -> TestoVoce(
            "Rispondi a $chi",
            "$oggetto · " + if (urgente) "urgente, risposta pronta" else "risposta pronta",
            "Rivedi", StilePillola.PIENA, AzioneVoce.APRI_MAIL,
        )
        VoceOggi.SMISTA -> TestoVoce(
            "Smista ${v.conteggio} mail",
            "Sol non è sicuro del gruppo",
            "Inizia", StilePillola.TENUE, AzioneVoce.SMISTA,
        )
        // I titoli stanno su una riga: il verbo è già sul pulsante, nel titolo basta chi.
        VoceOggi.RISPONDI -> TestoVoce(
            chi,
            if (urgente) "$oggetto · urgente" else oggetto,
            "Rispondi", StilePillola.TENUE, AzioneVoce.APRI_MAIL,
        )
        VoceOggi.SOLLECITO -> {
            val nome = v.nome?.takeIf { it.isNotBlank() } ?: v.a ?: "Il destinatario"
            val giorni = if (v.giorni == 1) "1 giorno" else "${v.giorni} giorni"
            if (v.bozzaId != null) {
                TestoVoce(nome, "Non risponde da $giorni · sollecito pronto", "Rivedi", StilePillola.TENUE, AzioneVoce.APRI_BOZZA)
            } else {
                TestoVoce(nome, "Non risponde da $giorni · $oggetto", "Prepara", StilePillola.TENUE, AzioneVoce.PREPARA_SOLLECITO)
            }
        }
        VoceOggi.SCADENZA -> TestoVoce(
            v.descrizione?.takeIf { it.isNotBlank() } ?: "Scadenza",
            v.quando?.let { giornoScadenza(it, oggi).replaceFirstChar { c -> c.titlecase(Locale.ITALIAN) } }.orEmpty(),
            "Fatto", StilePillola.TENUE, AzioneVoce.SCADENZA_FATTA,
        )
        else -> null
    }
}

/** La pillola della scadenza più vicina: "Venerdì: offerta a Rossi". */
fun pillolaScadenza(s: Scadenza, oggi: LocalDate = LocalDate.now()): String {
    val giorno = try {
        val d = LocalDate.parse(s.quando)
        when (d) {
            oggi -> "Oggi"
            oggi.plusDays(1) -> "Domani"
            else -> if (d.isBefore(oggi.plusDays(7))) DateTimeFormatter.ofPattern("EEEE", Locale.ITALIAN).format(d) else DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN).format(d)
        }.replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    } catch (e: Exception) {
        s.quando
    }
    val cosa = s.descrizione.replaceFirstChar { it.lowercase(Locale.ITALIAN) }
    return "$giorno: $cosa"
}

// --- Il ViewModel -----------------------------------------------------------------------

data class StatoOggi(
    val oggi: Oggi? = null,
    val caricamento: Boolean = false,
    val caricataAlmenoUnaVolta: Boolean = false,
    val errore: String? = null,
    /** I dati mostrati sono quelli salvati in quel momento (epoch ms), non freschi. */
    val daCacheDel: Long? = null,
    /** Per i collegamenti in fondo: scadenze aperte e mail che aspettano. */
    val numeroScadenze: Int? = null,
    val numeroAspetti: Int? = null,
    val inCorso: Boolean = false,
    val erroreAzione: String? = null,
    val messaggio: String? = null,
    /** Il sollecito appena preparato: la pagina lo apre. */
    val sollecitoDaAprire: Bozza? = null,
) {
    val voci: List<VoceOggi> get() = oggi?.daFare.orEmpty().filter { testoVoce(it) != null }
    val tuttoFatto: Boolean get() = oggi != null && voci.isEmpty()
}

/**
 * La pagina Oggi (18.1): una chiamata per tutto, più i numeri dei due collegamenti.
 * Mentre il riassunto di oggi si prepara la pagina si rilegge da sola, piano.
 */
class OggiViewModel(
    private val client: SolClient,
    private val cache: Cache,
    private val intervalloMs: Long = 4_000,
    private val giriMassimi: Int = 30,
) : ViewModel() {

    private val _stato = MutableStateFlow(StatoOggi())
    val stato: StateFlow<StatoOggi> = _stato.asStateFlow()

    private var lettura: Job? = null
    private var attesaRiassunto: Job? = null
    private var visibile = false

    fun carica() {
        lettura?.cancel()
        _stato.update { it.copy(caricamento = true) }
        lettura = viewModelScope.launch {
            val scadenze = async { leggiConCache(cache, "scadenze", ListSerializer(Scadenza.serializer())) { client.scadenzeAperte() } }
            val indietro = async { leggiConCache(cache, "indietro", Indietro.serializer()) { client.indietro() } }
            val oggi = leggiConCache(cache, "oggi", Oggi.serializer()) { client.oggi() }
            val s = scadenze.await()
            val i = indietro.await()
            _stato.update {
                it.copy(
                    oggi = oggi.dato ?: it.oggi,
                    caricamento = false,
                    caricataAlmenoUnaVolta = true,
                    errore = oggi.errore,
                    daCacheDel = oggi.daCacheDel,
                    numeroScadenze = s.dato?.count { sc -> sc.stato == Scadenza.APERTA } ?: it.numeroScadenze,
                    numeroAspetti = i.dato?.let { d -> d.aspettanoTe.size + d.aspettiTu.size } ?: it.numeroAspetti,
                )
            }
            seRiassuntoInCorso()
        }
    }

    /** La pagina è in vista: se il riassunto si sta preparando si ricontrolla. */
    fun riprendi() {
        visibile = true
        seRiassuntoInCorso()
    }

    fun sospendi() {
        visibile = false
        attesaRiassunto?.cancel()
        attesaRiassunto = null
    }

    private fun seRiassuntoInCorso() {
        if (!visibile || attesaRiassunto?.isActive == true) return
        if (_stato.value.oggi?.riassunto?.inCorso != true) return
        attesaRiassunto = viewModelScope.launch {
            repeat(giriMassimi) {
                delay(intervalloMs)
                val esito = client.oggi()
                if (esito is Esito.Ok) {
                    _stato.update { it.copy(oggi = esito.dato) }
                    if (esito.dato.riassunto?.inCorso != true) return@launch
                }
            }
        }
    }

    /** "Fatto" su una scadenza: si segna fatta e la pagina si rilegge (cambiano i numeri). */
    fun scadenzaFatta(id: Long) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.cambiaStatoScadenza(id, Scadenza.FATTA)) {
                is Esito.Ok -> {
                    _stato.update { s ->
                        s.copy(
                            inCorso = false,
                            messaggio = "Segnata come fatta.",
                            oggi = s.oggi?.let { o ->
                                o.copy(daFare = o.daFare.filterNot { it.scadenzaId == id }, fatteOggi = o.fatteOggi + 1)
                            },
                        )
                    }
                    carica()
                }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    /** "Prepara" su un sollecito: Sol lo scrive e la pagina della bozza si apre. */
    fun preparaSollecito(inviataId: Long) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.preparaSollecito(inviataId)) {
                is Esito.Ok -> _stato.update { it.copy(inCorso = false, sollecitoDaAprire = esito.dato) }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    fun sollecitoAperto() = _stato.update { it.copy(sollecitoDaAprire = null) }
    fun consumaMessaggio() = _stato.update { it.copy(messaggio = null) }
    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }
}
