package eu.stgm.sol.rete

import eu.stgm.sol.dati.Abbinamento
import eu.stgm.sol.dati.Arretrato
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Casella
import eu.stgm.sol.dati.CollegamentoOutlook
import eu.stgm.sol.dati.Conteggi
import eu.stgm.sol.dati.CorpoAbbina
import eu.stgm.sol.dati.CorpoRiassunto
import eu.stgm.sol.dati.CorpoSposta
import eu.stgm.sol.dati.Dispositivo
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.dati.Indietro
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.ModificaBozza
import eu.stgm.sol.dati.Novita
import eu.stgm.sol.dati.NuovaScadenza
import eu.stgm.sol.dati.PaginaMail
import eu.stgm.sol.dati.Regola
import eu.stgm.sol.dati.Riassunto
import eu.stgm.sol.dati.Salute
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.dati.Versioni
import eu.stgm.sol.dati.leggiErroreServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Dove sta il server e con che token parlargli. */
data class Configurazione(val server: String, val token: String, val dispositivoId: Long? = null) {
    val completa: Boolean get() = server.isNotBlank() && token.isNotBlank()
}

/** I filtri rapidi della schermata Posta. */
enum class FiltroRapido { TUTTE, DA_RISPONDERE, URGENTI, DA_CONTROLLARE }

data class FiltroMail(
    val gruppoId: Long? = null,
    val filtro: FiltroRapido = FiltroRapido.TUTTE,
    val primaDi: Long? = null,
    val limite: Int = 50,
) {
    /** GET /api/mail con i parametri del contratto (§6). */
    fun percorso(): String {
        val parametri = buildList {
            gruppoId?.let { add("gruppo_id=$it") }
            when (filtro) {
                FiltroRapido.TUTTE -> Unit
                FiltroRapido.DA_RISPONDERE -> add("da_rispondere=true")
                FiltroRapido.URGENTI -> add("urgenza=alta")
                FiltroRapido.DA_CONTROLLARE -> add("smistamento=da_controllare")
            }
            add("limite=$limite")
            primaDi?.let { add("prima_di=$it") }
        }
        return "/api/mail?" + parametri.joinToString("&")
    }
}

/**
 * Com'è andato un invio riuscito. [bozza] null: il server ha detto sì ma la bozza
 * non si è potuta rileggere. [giaInviata]: era già partita a un tentativo
 * precedente e il server non l'ha rimandata (15.7).
 */
data class RisultatoInvio(val bozza: Bozza?, val giaInviata: Boolean = false)

/**
 * Il client delle API di Sol (docs/contratto-api.md, v1.1). Una finestra sul
 * server: niente logica di posta qui, solo richieste e traduzione degli esiti.
 *
 * [configurazione] si rilegge a ogni chiamata: dopo un nuovo abbinamento il
 * client giusto è sempre questo, senza ricrearlo. [alNonAutorizzato] parte quando
 * il server dice che questo telefono non è più abbinato (confermato da
 * /api/salute, 15.12): l'app dimentica token e dati e torna all'abbinamento.
 */
class SolClient(
    private val configurazione: suspend () -> Configurazione?,
    private val alNonAutorizzato: suspend () -> Unit = {},
) {
    private val revoca = Mutex()

    // --- Salute, versioni, dispositivi ----------------------------------------

    suspend fun versione(): Esito<Versioni> = chiama("GET", "/api/versione", null, Versioni.serializer())

    /** Col token: dice anche se il server riconosce ancora questo telefono (15.12). */
    suspend fun salute(): Esito<Salute> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val risposta = esegui(conf, "GET", "/api/salute", null) ?: return Esito.Irraggiungibile
        return interpreta(risposta.codice, risposta.corpo, Salute.serializer())
    }

    suspend fun dispositivi(): Esito<List<Dispositivo>> =
        chiama("GET", "/api/dispositivi", null, ListSerializer(Dispositivo.serializer()))

    suspend fun revocaDispositivo(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/dispositivi/$id", null)

    // --- Caselle --------------------------------------------------------------

    suspend fun caselle(): Esito<List<Casella>> =
        chiama("GET", "/api/caselle", null, ListSerializer(Casella.serializer()))

    suspend fun casella(id: Long): Esito<Casella> = chiama("GET", "/api/caselle/$id", null, Casella.serializer())

    /** 201 con la Casella se Google accetta. La password viaggia solo su https (15.13). */
    suspend fun aggiungiGmail(indirizzo: String, nome: String, passwordApp: String): Esito<Unit> {
        if (!passwordConsentita()) return Esito.Errore(Testi.PASSWORD_SU_HTTP, "non_valido")
        return chiamaSenzaDato(
            "POST",
            "/api/caselle",
            buildJsonObject {
                put("tipo", "gmail")
                put("indirizzo", indirizzo)
                put("nome", nome)
                put("password_app", passwordApp)
            }.toString(),
        )
    }

    suspend fun aggiungiOutlook(nome: String): Esito<CollegamentoOutlook> =
        chiama(
            "POST",
            "/api/caselle",
            buildJsonObject {
                put("tipo", "outlook")
                put("nome", nome)
            }.toString(),
            CollegamentoOutlook.serializer(),
        )

    suspend fun aggiungiFinta(nome: String): Esito<Unit> =
        chiamaSenzaDato(
            "POST",
            "/api/caselle",
            buildJsonObject {
                put("tipo", "finta")
                put("nome", nome)
            }.toString(),
        )

    /** 15.3: una casella in errore riprende da dove era, senza perdere niente. */
    suspend fun ricollegaGmail(id: Long, passwordApp: String): Esito<Unit> {
        if (!passwordConsentita()) return Esito.Errore(Testi.PASSWORD_SU_HTTP, "non_valido")
        return chiamaSenzaDato(
            "POST",
            "/api/caselle/$id/ricollega",
            buildJsonObject { put("password_app", passwordApp) }.toString(),
        )
    }

    suspend fun ricollegaOutlook(id: Long): Esito<CollegamentoOutlook> =
        chiama("POST", "/api/caselle/$id/ricollega", CORPO_VUOTO, CollegamentoOutlook.serializer())

    suspend fun togliCasella(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/caselle/$id", null)

    suspend fun controllaCasella(id: Long): Esito<Unit> =
        chiamaSenzaDato("POST", "/api/caselle/$id/controlla", CORPO_VUOTO)

    // --- Gruppi e regole ------------------------------------------------------

    suspend fun gruppi(): Esito<List<Gruppo>> = chiama("GET", "/api/gruppi", null, ListSerializer(Gruppo.serializer()))

    /** 15.14: con la frase che dice al decisore che mail ci vanno (se manca, il server usa il nome). */
    suspend fun creaGruppo(nome: String, descrizione: String?): Esito<Unit> =
        chiamaSenzaDato(
            "POST",
            "/api/gruppi",
            buildJsonObject {
                put("nome", nome)
                if (!descrizione.isNullOrBlank()) put("descrizione", descrizione)
            }.toString(),
        )

    suspend fun rinominaGruppo(id: Long, nome: String): Esito<Unit> =
        chiamaSenzaDato("PATCH", "/api/gruppi/$id", buildJsonObject { put("nome", nome) }.toString())

    suspend fun cambiaDescrizioneGruppo(id: Long, descrizione: String): Esito<Unit> =
        chiamaSenzaDato("PATCH", "/api/gruppi/$id", buildJsonObject { put("descrizione", descrizione) }.toString())

    suspend fun togliGruppo(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/gruppi/$id", null)

    suspend fun regole(): Esito<List<Regola>> = chiama("GET", "/api/regole", null, ListSerializer(Regola.serializer()))

    suspend fun togliRegola(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/regole/$id", null)

    // --- Mail -----------------------------------------------------------------

    suspend fun mail(filtro: FiltroMail): Esito<PaginaMail> = chiama("GET", filtro.percorso(), null, PaginaMail.serializer())

    suspend fun conteggi(): Esito<Conteggi> = chiama("GET", "/api/conteggi", null, Conteggi.serializer())

    suspend fun dettaglioMail(id: Long): Esito<Mail> = chiama("GET", "/api/mail/$id", null, Mail.serializer())

    suspend fun sposta(mailId: Long, gruppoId: Long, impara: Boolean): Esito<Mail> =
        chiamaPoiRileggi(
            "POST",
            "/api/mail/$mailId/sposta",
            JsonSol.encodeToString(CorpoSposta.serializer(), CorpoSposta(gruppoId, impara)),
            Mail.serializer(),
        ) { dettaglioMail(mailId) }

    suspend fun confermaGruppo(mailId: Long): Esito<Unit> =
        chiamaSenzaDato("POST", "/api/mail/$mailId/conferma", CORPO_VUOTO)

    suspend fun segnaLetta(mailId: Long, letta: Boolean): Esito<Unit> =
        chiamaSenzaDato("POST", "/api/mail/$mailId/letta", buildJsonObject { put("letta", letta) }.toString())

    suspend fun daRispondere(mailId: Long, valore: Boolean): Esito<Unit> =
        chiamaSenzaDato(
            "POST",
            "/api/mail/$mailId/rispondere",
            buildJsonObject { put("da_rispondere", valore) }.toString(),
        )

    // --- Bozze ----------------------------------------------------------------

    /** Una risposta nuova per una mail che non ne ha (o ne ha solo di scartate). */
    suspend fun preparaRisposta(mailId: Long, istruzioni: String?): Esito<Bozza> =
        chiama(
            "POST",
            "/api/mail/$mailId/bozza",
            buildJsonObject { if (!istruzioni.isNullOrBlank()) put("istruzioni", istruzioni) }.toString(),
            Bozza.serializer(),
        )

    /**
     * 15.5 e 15.13: rifà una bozza (risposta o sollecito) partendo da quella attuale,
     * modifiche di Manuel comprese. [daZero] solo quando lui ha detto di buttare le
     * sue modifiche.
     */
    suspend fun rifaiBozza(bozzaId: Long, istruzioni: String?, daZero: Boolean = false): Esito<Bozza> =
        chiama(
            "POST",
            "/api/bozze/$bozzaId/rifai",
            // Come lo scrive il contratto: "istruzioni" c'è sempre, anche null.
            buildJsonObject {
                put("istruzioni", istruzioni?.trim()?.ifEmpty { null })
                if (daZero) put("da_zero", true)
            }.toString(),
            Bozza.serializer(),
        )

    suspend fun bozza(id: Long): Esito<Bozza> = chiama("GET", "/api/bozze/$id", null, Bozza.serializer())

    /** Il contratto non dice cosa torna il PUT: se non è una Bozza, la si rilegge. */
    suspend fun salvaBozza(id: Long, modifica: ModificaBozza): Esito<Bozza> =
        chiamaPoiRileggi(
            "PUT",
            "/api/bozze/$id",
            JsonSol.encodeToString(ModificaBozza.serializer(), modifica),
            Bozza.serializer(),
        ) { bozza(id) }

    /**
     * L'unico punto dell'app che fa partire una mail. Chi lo chiama ha già riletto
     * la bozza, mostrato a Manuel destinatari, Cc, oggetto e prima riga di QUELLA
     * [versione] e avuto il suo sì ([eu.stgm.sol.ui.GestoreBozza.confermaInvio]).
     * Se nel frattempo la bozza è cambiata il server risponde 409 con la bozza
     * nuova (15.11) e non invia niente.
     */
    suspend fun inviaBozza(id: Long, versione: Int): Esito<RisultatoInvio> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val corpo = buildJsonObject {
            put("conferma", true)
            put("versione", versione)
        }.toString()
        val risposta = esegui(conf, "POST", "/api/bozze/$id/invia", corpo) ?: return Esito.Irraggiungibile
        if (risposta.codice !in 200..299) return fallimento(risposta.codice, risposta.corpo)
        val oggetto = try {
            JsonSol.parseToJsonElement(risposta.corpo.orEmpty()) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val giaInviata = (oggetto?.get("gia_inviata") as? JsonPrimitive)?.booleanOrNull == true
        val letta = oggetto?.let {
            try {
                JsonSol.decodeFromJsonElement(Bozza.serializer(), it)
            } catch (e: Exception) {
                null
            }
        }
        // Un sì resta un sì: anche se la bozza non si rilegge, la mail è partita.
        val bozza = letta ?: (bozza(id) as? Esito.Ok)?.dato
        return Esito.Ok(RisultatoInvio(bozza, giaInviata))
    }

    suspend fun scartaBozza(id: Long): Esito<Unit> = chiamaSenzaDato("POST", "/api/bozze/$id/scarta", CORPO_VUOTO)

    // --- Riassunti ------------------------------------------------------------

    suspend fun riassuntoOggi(): Esito<Riassunto> = chiama("GET", "/api/riassunti/oggi", null, Riassunto.serializer())

    suspend fun chiediRiassunto(richiesta: String): Esito<Riassunto> =
        chiama(
            "POST",
            "/api/riassunti",
            JsonSol.encodeToString(CorpoRiassunto.serializer(), CorpoRiassunto(richiesta)),
            Riassunto.serializer(),
        )

    suspend fun riassunto(id: Long): Esito<Riassunto> = chiama("GET", "/api/riassunti/$id", null, Riassunto.serializer())

    suspend fun riassunti(): Esito<List<Riassunto>> =
        chiama("GET", "/api/riassunti", null, ListSerializer(Riassunto.serializer()))

    // --- Scadenze -------------------------------------------------------------

    suspend fun scadenzeAperte(): Esito<List<Scadenza>> =
        chiama("GET", "/api/scadenze?stato=aperta", null, ListSerializer(Scadenza.serializer()))

    suspend fun cambiaStatoScadenza(id: Long, stato: String): Esito<Unit> =
        chiamaSenzaDato("PATCH", "/api/scadenze/$id", buildJsonObject { put("stato", stato) }.toString())

    suspend fun creaScadenza(nuova: NuovaScadenza): Esito<Unit> =
        chiamaSenzaDato("POST", "/api/scadenze", JsonSol.encodeToString(NuovaScadenza.serializer(), nuova))

    // --- Niente resta indietro ------------------------------------------------

    suspend fun indietro(): Esito<Indietro> = chiama("GET", "/api/indietro", null, Indietro.serializer())

    suspend fun preparaSollecito(inviataId: Long): Esito<Bozza> =
        chiama("POST", "/api/indietro/$inviataId/sollecito", CORPO_VUOTO, Bozza.serializer())

    suspend fun ignora(inviataId: Long): Esito<Unit> =
        chiamaSenzaDato("POST", "/api/indietro/$inviataId/ignora", CORPO_VUOTO)

    // --- Impostazioni, arretrato e novità ---------------------------------------

    suspend fun impostazioni(): Esito<ImpostazioniSol> =
        chiama("GET", "/api/impostazioni", null, ImpostazioniSol.serializer())

    /** PATCH con i soli campi cambiati (nomi del contratto). */
    suspend fun modificaImpostazioni(cambi: JsonObject): Esito<ImpostazioniSol> =
        chiamaPoiRileggi("PATCH", "/api/impostazioni", cambi.toString(), ImpostazioniSol.serializer()) { impostazioni() }

    /** 15.2: quante mail già smistate sono rimaste in Posta in arrivo. */
    suspend fun arretrato(): Esito<Arretrato> = chiama("GET", "/api/arretrato", null, Arretrato.serializer())

    /** 15.2: le sposta nella casella, solo col sì esplicito di Manuel. */
    suspend fun applicaArretrato(): Esito<Arretrato> =
        chiama("POST", "/api/arretrato/applica", CORPO_CONFERMA, Arretrato.serializer())

    suspend fun novita(dal: String?): Esito<Novita> {
        val percorso = if (dal.isNullOrBlank()) "/api/novita" else "/api/novita?dal=${java.net.URLEncoder.encode(dal, "UTF-8")}"
        return chiama("GET", percorso, null, Novita.serializer())
    }

    /**
     * Scarica l'APK nuovo da GET /scarica/sol.apk (percorso fisso, mai un URL
     * arrivato dal server). Senza token: è un file pubblico; prima di installarlo
     * l'app ne controlla impronta e dimensione (15.10).
     */
    suspend fun scaricaApk(destinazione: File): Boolean {
        val conf = configurazione() ?: return false
        val url = (conf.server + PERCORSO_APK).toHttpUrlOrNull() ?: return false
        if (!trasportoConsentito(url)) return false
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = Request.Builder().url(url).get().build()
                httpLento.newCall(richiesta).execute().use { risposta ->
                    val corpo = risposta.body
                    if (!risposta.isSuccessful || corpo == null) return@use false
                    destinazione.outputStream().use { uscita -> corpo.byteStream().copyTo(uscita) }
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
    }

    // --- Meccanica ------------------------------------------------------------

    private suspend fun passwordConsentita(): Boolean = IndirizzoServer.passwordConsentita(configurazione()?.server)

    private suspend fun <T> chiama(metodo: String, percorso: String, corpo: String?, serializer: KSerializer<T>): Esito<T> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val risposta = esegui(conf, metodo, percorso, corpo) ?: return Esito.Irraggiungibile
        return interpreta(risposta.codice, risposta.corpo, serializer)
    }

    private suspend fun chiamaSenzaDato(metodo: String, percorso: String, corpo: String?): Esito<Unit> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val risposta = esegui(conf, metodo, percorso, corpo) ?: return Esito.Irraggiungibile
        return if (risposta.codice in 200..299) Esito.Ok(Unit) else fallimento(risposta.codice, risposta.corpo)
    }

    /**
     * Una scrittura che di solito torna l'oggetto aggiornato. Se il corpo del 2xx
     * non si legge (vuoto, o un formato diverso) la scrittura è comunque andata:
     * si rilegge l'oggetto con [rileggi]. Se anche quello fallisce, l'errore porta
     * [CODICE_RILETTURA_FALLITA], per distinguerlo da un rifiuto vero.
     */
    private suspend fun <T> chiamaPoiRileggi(
        metodo: String,
        percorso: String,
        corpo: String?,
        serializer: KSerializer<T>,
        rileggi: suspend () -> Esito<T>,
    ): Esito<T> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val risposta = esegui(conf, metodo, percorso, corpo) ?: return Esito.Irraggiungibile
        if (risposta.codice !in 200..299) return fallimento(risposta.codice, risposta.corpo)
        val letto = try {
            JsonSol.decodeFromString(serializer, risposta.corpo.orEmpty())
        } catch (e: Exception) {
            null
        }
        if (letto != null) return Esito.Ok(letto)
        return when (val riletto = rileggi()) {
            is Esito.Ok -> riletto
            else -> Esito.Errore(
                "Fatto, ma non riesco a rileggere il risultato. Aggiorna la pagina.",
                CODICE_RILETTURA_FALLITA,
                risposta.codice,
            )
        }
    }

    /** null = rete caduta, server spento, o indirizzo che non si può usare in chiaro. */
    private suspend fun esegui(conf: Configurazione, metodo: String, percorso: String, corpo: String?): RispostaHttp? {
        val url = (conf.server + percorso).toHttpUrlOrNull() ?: return null
        if (!trasportoConsentito(url)) return RispostaHttp(CODICE_NON_PROTETTO, null)
        val richiesta = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${conf.token}")
            .method(metodo, corpo?.toRequestBody(JSON))
            .build()
        // Le scritture non si ritentano da sole: un invio ripetuto in silenzio
        // dopo una risposta persa potrebbe far partire due volte la stessa mail.
        val client = if (metodo == "GET") http else httpScritture
        val risposta = esegui(client, richiesta) ?: return null
        if (risposta.codice == 401 && percorso != "/api/salute") gestisciNonAutorizzato(conf)
        return risposta
    }

    /**
     * Il server non riconosce più il token. Prima di dimenticare tutto si chiede
     * conferma a /api/salute (15.12): solo `autenticato: true` dice il contrario.
     * Una volta per token, anche se tre richieste in parallelo prendono il 401.
     */
    private suspend fun gestisciNonAutorizzato(conf: Configurazione) {
        revoca.withLock {
            if (conf.token == tokenGiaRevocato) return
            val salute = salute()
            if (salute is Esito.Ok && salute.dato.autenticato == true) return
            tokenGiaRevocato = conf.token
        }
        alNonAutorizzato()
    }

    @Volatile
    private var tokenGiaRevocato: String? = null

    private data class RispostaHttp(val codice: Int, val corpo: String?)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val CORPO_VUOTO = "{}"
        private const val CORPO_CONFERMA = """{"conferma":true}"""
        const val PERCORSO_APK = "/scarica/sol.apk"

        /** La scrittura è riuscita ma il risultato non si è potuto rileggere. */
        const val CODICE_RILETTURA_FALLITA = "rilettura_fallita"

        /** L'indirizzo salvato è in chiaro e fuori casa: niente richiesta. */
        const val CODICE_INDIRIZZO_NON_PROTETTO = "indirizzo_non_protetto"
        private const val CODICE_NON_PROTETTO = -1

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Senza ritentativi e senza connessioni tenute aperte: una connessione
         * ferma già chiusa dal server farebbe fallire il primo invio dopo una pausa.
         */
        private val httpScritture: OkHttpClient = http.newBuilder()
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        private val httpLento: OkHttpClient = http.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** In chiaro solo verso la rete di casa (15.13): un indirizzo salvato prima resta controllato. */
        private fun trasportoConsentito(url: HttpUrl): Boolean = url.isHttps || IndirizzoServer.httpConsentito(url.host)

        private suspend fun esegui(client: OkHttpClient, richiesta: Request): RispostaHttp? =
            suspendCancellableCoroutine { continuazione ->
                val chiamata = client.newCall(richiesta)
                continuazione.invokeOnCancellation { chiamata.cancel() }
                chiamata.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuazione.isActive) continuazione.resume(null)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val letta = try {
                                response.use { RispostaHttp(it.code, it.body?.string()) }
                            } catch (e: IOException) {
                                null
                            }
                            if (continuazione.isActive) continuazione.resume(letta)
                        }
                    },
                )
            }

        internal fun <T> interpreta(codice: Int, corpo: String?, serializer: KSerializer<T>): Esito<T> {
            if (codice !in 200..299) return fallimento(codice, corpo)
            return try {
                Esito.Ok(JsonSol.decodeFromString(serializer, corpo.orEmpty()))
            } catch (e: Exception) {
                // SerializationException o IllegalArgumentException: il server
                // ha detto sì ma con un JSON che non riconosciamo.
                Esito.Errore(Testi.RISPOSTA_STRANA, stato = codice)
            }
        }

        /**
         * Un rifiuto del server in una frase da mostrare. Vince il suo `errore`
         * (è già scritto per l'utente), tranne per il 401: lì la frase nostra dice
         * anche cosa fare. Un 502/503/504 senza il JSON di Sol viene dal tunnel o
         * dal proxy: il server non c'è, che per Manuel è "non raggiungibile".
         */
        internal fun fallimento(codice: Int, corpo: String?): Esito<Nothing> {
            if (codice == CODICE_NON_PROTETTO) {
                return Esito.Errore(Testi.INDIRIZZO_NON_PROTETTO, CODICE_INDIRIZZO_NON_PROTETTO)
            }
            val errore = leggiErroreServer(corpo)
            if (errore == null && codice in 502..504) return Esito.Irraggiungibile
            val codiceErrore = if (codice == 401) Testi.CODICE_NON_AUTORIZZATO else errore?.codice ?: codiceDaStato(codice)
            val frase = if (codice == 401) {
                Testi.NON_AUTORIZZATO
            } else {
                errore?.errore ?: when (codiceErrore) {
                    "non_trovato" -> Testi.NON_TROVATO
                    "conflitto" -> Testi.CONFLITTO
                    "non_valido" -> Testi.NON_VALIDO
                    "troppi_tentativi" -> Testi.TROPPI_TENTATIVI
                    "casella_non_raggiungibile" -> Testi.CASELLA_NON_RAGGIUNGIBILE
                    "scrittore_non_disponibile" -> Testi.SCRITTORE_NON_DISPONIBILE
                    else -> if (codice >= 500) Testi.PROBLEMA_SERVER else Testi.GENERICO
                }
            }
            return Esito.Errore(frase, codiceErrore, codice, errore?.campo, errore?.corpo)
        }

        private fun codiceDaStato(codice: Int): String? = when (codice) {
            404 -> "non_trovato"
            409 -> "conflitto"
            422 -> "non_valido"
            429 -> "troppi_tentativi"
            else -> null
        }

        /**
         * POST /api/abbina: senza token (è proprio quello che si chiede) e senza
         * ritentativi. NonCancellable: il codice vale una volta sola, e un token
         * perso perché la schermata si è chiusa lascerebbe il codice consumato.
         */
        suspend fun abbina(server: String, codice: String, nome: String): Esito<Abbinamento> =
            withContext(NonCancellable) {
                val url = (server + "/api/abbina").toHttpUrlOrNull()
                    ?: return@withContext Esito.Errore("Questo indirizzo non sembra giusto.")
                if (!trasportoConsentito(url)) {
                    return@withContext Esito.Errore(Testi.INDIRIZZO_NON_PROTETTO, CODICE_INDIRIZZO_NON_PROTETTO)
                }
                val corpo = JsonSol.encodeToString(CorpoAbbina.serializer(), CorpoAbbina(codice, nome))
                val richiesta = Request.Builder().url(url).post(corpo.toRequestBody(JSON)).build()
                val risposta = esegui(httpScritture, richiesta) ?: return@withContext Esito.Irraggiungibile
                when (risposta.codice) {
                    in 200..299 -> interpreta(risposta.codice, risposta.corpo, Abbinamento.serializer())
                    401 -> Esito.Errore(
                        "Il codice è sbagliato o scaduto. Fanne preparare uno nuovo sul server e riprova.",
                        Testi.CODICE_NON_AUTORIZZATO,
                        401,
                    )
                    429 -> Esito.Errore(
                        "Troppi codici sbagliati. Aspetta 15 minuti e riprova.",
                        "troppi_tentativi",
                        429,
                    )
                    404, 405 -> Esito.Errore(
                        "A questo indirizzo non risponde Sol. Controlla l'indirizzo del server.",
                        "non_trovato",
                        risposta.codice,
                    )
                    else -> fallimento(risposta.codice, risposta.corpo)
                }
            }
    }
}
