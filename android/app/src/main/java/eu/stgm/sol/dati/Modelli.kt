package eu.stgm.sol.dati

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Il JSON del contratto (docs/contratto-api.md). Nomi in snake_case sul filo,
 * camelCase qui. Tollerante: un campo nuovo del server non rompe l'app, uno
 * mancante prende il valore di base.
 */
@OptIn(ExperimentalSerializationApi::class)
val JsonSol = Json {
    ignoreUnknownKeys = true
    // Un numero dove il contratto scrive una stringa (il cursore "8131") si legge lo stesso.
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

// --- Abbinamento, versioni -------------------------------------------------------

@Serializable
data class CorpoAbbina(val codice: String, val nome: String, val tipo: String = "android")

@Serializable
data class Abbinamento(val token: String, val dispositivoId: Long? = null)

/** Una versione scaricabile. [sha256] e [byte] (15.10) servono a controllare il file scaricato. */
@Serializable
data class VersioneApp(val codice: Int = 0, val nome: String = "", val sha256: String? = null, val byte: Long? = null)

@Serializable
data class Versioni(
    val server: String? = null,
    val android: VersioneApp? = null,
    val pc: VersioneApp? = null,
    val nota: String? = null,
)

/**
 * GET /api/salute. [autenticato] (15.12) dice se il server riconosce ancora questo
 * telefono: un 200 da solo non lo prova. null = server più vecchio della v1.1.
 */
@Serializable
data class Salute(val ok: Boolean = false, val versione: String? = null, val autenticato: Boolean? = null)

@Serializable
data class Dispositivo(
    val id: Long,
    val nome: String = "",
    val tipo: String = "",
    val abbinatoIl: String? = null,
    val ultimoAccesso: String? = null,
)

// --- Caselle ---------------------------------------------------------------------

@Serializable
data class Casella(
    val id: Long,
    val tipo: String = "",
    val indirizzo: String? = null,
    val nome: String? = null,
    val stato: String = "",
    val ultimoControllo: String? = null,
    val errore: String? = null,
    val attivaDal: String? = null,
)

@Serializable
data class CollegamentoOutlook(
    val casella: Casella? = null,
    val codiceUtente: String,
    val urlVerifica: String,
    val scadeIl: String? = null,
)

// --- Gruppi e regole -------------------------------------------------------------

/**
 * Un gruppo della posta. [descrizione] (15.14): la frase che dice al decisore che
 * mail ci vanno; più è chiara, meglio Sol smista.
 */
@Serializable
data class Gruppo(
    val id: Long,
    val nome: String,
    val ordine: Int = 0,
    val cartella: String? = null,
    val daLeggere: Int = 0,
    val totale: Int = 0,
    val descrizione: String? = null,
    /** 17: il NOME del colore (blu, verde, ...); l'app lo traduce coi colori della guida. */
    val colore: String? = null,
) {
    companion object {
        /** Il decisore sceglie tra 2 e 16 opzioni (15.1). */
        const val MASSIMO = 16
    }
}

@Serializable
data class Regola(
    val id: Long,
    val tipo: String = "",
    val valore: String = "",
    val gruppoId: Long? = null,
    val origine: String = "",
    val creataIl: String? = null,
)

// --- Mail ------------------------------------------------------------------------

/**
 * Un indirizzo di posta. Il contratto scrive `destinatari: [...]` senza dire se
 * sono stringhe o oggetti: si accettano tutti e due (v. [IndirizzoSerializer]).
 */
@Serializable(with = IndirizzoSerializer::class)
data class Indirizzo(val nome: String? = null, val indirizzo: String = "") {
    /** "Mario Rossi" se c'è il nome, altrimenti l'indirizzo. */
    val etichetta: String get() = nome?.takeIf { it.isNotBlank() } ?: indirizzo
}

object IndirizzoSerializer : KSerializer<Indirizzo> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Indirizzo")

    override fun deserialize(decoder: Decoder): Indirizzo {
        val elemento = (decoder as JsonDecoder).decodeJsonElement()
        return when (elemento) {
            is JsonPrimitive -> Indirizzo(indirizzo = elemento.contentOrNull.orEmpty())
            is JsonObject -> Indirizzo(
                nome = (elemento["nome"] as? JsonPrimitive)?.contentOrNull,
                indirizzo = (elemento["indirizzo"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
            else -> Indirizzo()
        }
    }

    override fun serialize(encoder: Encoder, value: Indirizzo) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonObject {
                put("nome", value.nome)
                put("indirizzo", value.indirizzo)
            },
        )
    }
}

@Serializable
data class Allegato(val nome: String = "", val byte: Long = 0)

/**
 * Il riferimento a una bozza dentro una mail o una voce di "Niente resta indietro".
 * Il contratto non dice com'è `bozza_sollecito` quando c'è: si accetta un oggetto
 * (anche la Bozza intera) o il solo id.
 */
@Serializable
data class RifBozza(val id: Long, val stato: String = "")

object RifBozzaSerializer : KSerializer<RifBozza?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RifBozza")

    override fun deserialize(decoder: Decoder): RifBozza? {
        val elemento = (decoder as JsonDecoder).decodeJsonElement()
        return when (elemento) {
            is JsonObject -> (elemento["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { id ->
                RifBozza(id, (elemento["stato"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            is JsonPrimitive -> elemento.contentOrNull?.toLongOrNull()?.let { RifBozza(it) }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: RifBozza?) {
        (encoder as JsonEncoder).encodeJsonElement(
            if (value == null) {
                kotlinx.serialization.json.JsonNull
            } else {
                buildJsonObject {
                    put("id", value.id)
                    put("stato", value.stato)
                }
            },
        )
    }
}

@Serializable
data class Mail(
    val id: Long,
    val casellaId: Long? = null,
    val casella: String? = null,
    val mittente: Indirizzo = Indirizzo(),
    val oggetto: String = "",
    val anteprima: String = "",
    val riassunto: String? = null,
    val ricevutaIl: String? = null,
    val letta: Boolean = false,
    val rispostaData: Boolean = false,
    val gruppoId: Long? = null,
    val gruppo: String? = null,
    val daRispondere: Boolean = false,
    val urgenza: String = "normale",
    val sicurezza: Double? = null,
    val smistamento: String = "",
    val origineSmistamento: String? = null,
    val applicataInCasella: Boolean = false,
    @Serializable(with = RifBozzaSerializer::class)
    val bozza: RifBozza? = null,
    val allegati: List<Allegato> = emptyList(),
    // Solo nel dettaglio (GET /api/mail/{id}).
    val destinatari: List<Indirizzo> = emptyList(),
    val cc: List<Indirizzo> = emptyList(),
    val corpo: String? = null,
    val probabilita: JsonObject? = null,
    /** La busta della PEC (15.8): [mittente] e [oggetto] sono quelli della mail interna. */
    val pec: Pec? = null,
    /** 16.2: Sol pensa che possa essere un tentativo di truffa (IBAN nuovo, documenti, password...). */
    val sospetta: Boolean = false,
) {
    val urgente: Boolean get() = urgenza == URGENZA_ALTA

    /** 16.2: quanto Sol crede che sia una truffa (`probabilita.truffa.si`), solo nel dettaglio. */
    val probabilitaTruffa: Double?
        get() = ((probabilita?.get("truffa") as? JsonObject)?.get("si") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    val daControllare: Boolean get() = smistamento == SMISTAMENTO_DA_CONTROLLARE
    /** Una bozza pronta conta solo se a quella mail non hai già risposto (anche da Outlook). */
    val bozzaPronta: Boolean get() = bozza?.stato == Bozza.PRONTA && !rispostaData

    companion object {
        const val URGENZA_ALTA = "alta"
        const val SMISTAMENTO_DA_CONTROLLARE = "da_controllare"
    }
}

@Serializable
data class Pec(val mittenteBusta: String = "", val gestore: String? = null)

@Serializable
data class PaginaMail(val mail: List<Mail> = emptyList(), val altre: Boolean = false)

@Serializable
data class CorpoSposta(val gruppoId: Long, val impara: Boolean)

// --- Bozze -----------------------------------------------------------------------

@Serializable
data class Bozza(
    val id: Long,
    val mailId: Long? = null,
    val tipo: String = "risposta",
    val stato: String = "",
    val a: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val oggetto: String = "",
    val testo: String = "",
    val istruzioni: String? = null,
    val fornitore: String? = null,
    val modificata: Boolean = false,
    val creataIl: String? = null,
    val inviataIl: String? = null,
    val errore: String? = null,
    /** +1 a ogni cambiamento (15.11): l'invio manda la versione che Manuel ha visto. */
    val versione: Int? = null,
) {
    val inPreparazione: Boolean get() = stato == IN_PREPARAZIONE

    /** Sol ha finito ma non ha scritto niente: non è una risposta da inviare. */
    val senzaTesto: Boolean get() = testo.isBlank()

    /** La prima riga non vuota del testo, per la conferma d'invio. */
    val primaRiga: String get() = testo.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val chiusa: Boolean get() = stato == INVIATA || stato == SCARTATA

    /** Si può inviare una bozza pronta, o una andata in errore (resta reinviabile). */
    val inviabile: Boolean get() = (stato == PRONTA || stato == ERRORE) && a.isNotEmpty() && !senzaTesto

    companion object {
        const val IN_PREPARAZIONE = "in_preparazione"
        const val PRONTA = "pronta"
        const val INVIATA = "inviata"
        const val SCARTATA = "scartata"
        const val ERRORE = "errore"
    }
}

@Serializable
data class ModificaBozza(val a: List<String>, val cc: List<String>, val oggetto: String, val testo: String)

// --- Riassunti -------------------------------------------------------------------

@Serializable
data class Riassunto(
    val id: Long? = null,
    val richiesta: String? = null,
    val stato: String = "",
    val testoParziale: String? = null,
    val testo: String? = null,
    val mailCitate: List<Long> = emptyList(),
    val errore: String? = null,
    val creatoIl: String? = null,
    val aggiornatoIl: String? = null,
) {
    val inCorso: Boolean get() = stato == IN_CODA || stato == IN_CORSO

    /** Il testo da mostrare: quello finale se c'è, altrimenti quello che sta crescendo. */
    val testoVisibile: String get() = testo?.takeIf { it.isNotBlank() } ?: testoParziale.orEmpty()

    companion object {
        const val IN_CODA = "in_coda"
        const val IN_CORSO = "in_corso"
        const val PRONTO = "pronto"
        const val ERRORE = "errore"
    }
}

@Serializable
data class CorpoRiassunto(val richiesta: String)

// --- Scadenze --------------------------------------------------------------------

@Serializable
data class Scadenza(
    val id: Long,
    val mailId: Long? = null,
    val descrizione: String = "",
    val quando: String = "",
    val ora: String? = null,
    val stato: String = "aperta",
    val creataIl: String? = null,
) {
    companion object {
        const val APERTA = "aperta"
        const val FATTA = "fatta"
        const val SCARTATA = "scartata"
    }
}

@Serializable
data class NuovaScadenza(val descrizione: String, val quando: String, val ora: String? = null)

// --- Niente resta indietro -------------------------------------------------------

@Serializable
data class AspettaTe(val mail: Mail, val giorni: Int = 0)

@Serializable
data class Inviata(
    val id: Long,
    val casellaId: Long? = null,
    val a: List<String> = emptyList(),
    val oggetto: String = "",
    val inviataIl: String? = null,
    val anteprima: String = "",
)

@Serializable
data class AspettiTu(
    val inviata: Inviata,
    val giorni: Int = 0,
    @Serializable(with = RifBozzaSerializer::class)
    val bozzaSollecito: RifBozza? = null,
)

@Serializable
data class Indietro(
    val aspettanoTe: List<AspettaTe> = emptyList(),
    val aspettiTu: List<AspettiTu> = emptyList(),
)

// --- Impostazioni ----------------------------------------------------------------

/**
 * [modalitaProva] è obbligatorio di proposito: con tutti i campi di base, una
 * risposta qualunque (`{"ok": true}`) si leggerebbe come "modalità prova accesa"
 * e l'app direbbe una cosa falsa su cosa fa Sol con la posta vera.
 */
@Serializable
data class ImpostazioniSol(
    val modalitaProva: Boolean,
    val sogliaSicurezza: Double = 0.85,
    val giorniRisposta: Int = 2,
    val giorniSollecito: Int = 7,
    val intervalloControlloMinuti: Int = 5,
    val firma: String = "",
    val tono: String = "",
    val scrittori: List<String> = emptyList(),
    val conservaGiorni: Int = 180,
    /** 16.2: da questa probabilità in su una mail è "possibile truffa" (tra 0,2 e 0,9). */
    val sogliaTruffa: Double = 0.5,
) {
    companion object {
        const val SOGLIA_TRUFFA_MINIMA = 0.2
        const val SOGLIA_TRUFFA_MASSIMA = 0.9
    }
}

// --- Novità (notifiche) ----------------------------------------------------------

@Serializable
data class Novita(
    val cursore: String? = null,
    val urgenti: List<Mail> = emptyList(),
    val daRispondereNuove: Int = 0,
    /** Il totale delle bozze pronte (15.13): per le notifiche si usa SOLO [bozzeNuove]. */
    val bozzePronte: Int = 0,
    val scadenzeDomani: List<Scadenza> = emptyList(),
    val bozzeNuove: List<BozzaNuova> = emptyList(),
)

@Serializable
data class BozzaNuova(val id: Long, val mailId: Long? = null, val oggetto: String = "")

// --- Conteggi (15.4) e arretrato (15.2) -------------------------------------------------

@Serializable
data class ConteggioGruppo(val daLeggere: Int = 0, val totale: Int = 0)

@Serializable
data class Conteggi(
    val daRispondere: Int = 0,
    val urgenti: Int = 0,
    val daControllare: Int = 0,
    val daLeggere: Int = 0,
    val bozzePronte: Int = 0,
    /** 16.2: le mail segnalate come possibile truffa e non ancora chiarite. */
    val sospette: Int = 0,
    /** 17: scadenze aperte da oggi a 7 giorni compresi, senza quelle delle possibili truffe. */
    val scadenzeProssime: Int = 0,
    /** Chiave: id del gruppo, come testo (JSON non ha chiavi numeriche). */
    val gruppi: Map<String, ConteggioGruppo> = emptyMap(),
)

@Serializable
data class Arretrato(val daApplicare: Int = 0)

// --- Errori ----------------------------------------------------------------------

/**
 * `{"errore": "...", "codice": "...", "campo": "..."}`, anche dentro `detail` come lo manda
 * FastAPI. [corpo]: l'oggetto intero, per i dati in più (la bozza nuova di un 409, 15.11).
 */
data class ErroreServer(
    val errore: String?,
    val codice: String?,
    val campo: String? = null,
    val corpo: JsonObject? = null,
)

fun leggiErroreServer(corpo: String?): ErroreServer? {
    if (corpo.isNullOrBlank()) return null
    val radice = try {
        JsonSol.parseToJsonElement(corpo) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val oggetto = (radice["detail"] as? JsonObject) ?: radice
    val errore = (oggetto["errore"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val codice = (oggetto["codice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val campo = (oggetto["campo"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (errore == null && codice == null) return null
    return ErroreServer(errore?.takeIf { it.isNotBlank() }, codice, campo, oggetto)
}
