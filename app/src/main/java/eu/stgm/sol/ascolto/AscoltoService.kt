package eu.stgm.sol.ascolto

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.stgm.sol.BuildConfig
import eu.stgm.sol.MainActivity
import eu.stgm.sol.R
import eu.stgm.sol.SolApp
import eu.stgm.sol.cervello.Cervello
import eu.stgm.sol.cervello.CervelloClaude
import eu.stgm.sol.cervello.CervelloEco
import eu.stgm.sol.cervello.ClaudeApi
import eu.stgm.sol.cervello.Risposta
import eu.stgm.sol.conferme.Normalizzatore
import eu.stgm.sol.dati.Impostazioni
import eu.stgm.sol.dati.Registro
import eu.stgm.sol.esecuzione.EsecutoreProposte
import eu.stgm.sol.esecuzione.InterazioneVocale
import eu.stgm.sol.esecuzione.PianificatoreAllarmi
import eu.stgm.sol.esecuzione.PromemoriaRepoLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// Il servizio sempre attivo: orchestra wake word, trascrizione, cervello e
// voce. Due regole della casa che valgono per ogni riga di questo file:
//  1. un solo padrone del microfono per volta (wake OPPURE trascrittore);
//  2. ogni giro si chiude con una frase — il silenzio non è mai un esito.
class AscoltoService : Service() {

    private enum class Stato {
        SILENZIATO,       // orecchio spento dall'utente
        VIGILE,           // Porcupine in ascolto della wake word
        ASCOLTO_COMANDO,  // trascrittore acceso: sta parlando lui
        ELABORA,          // il cervello sta lavorando (tool in corso)
        CONFERMA,         // finestra rossa aperta: aspetto "timbro e firma"
        RISPONDE,         // il TTS sta parlando
        ASCOLTO_ANNULLA,  // finestra gialla: "annulla" senza wake word
    }

    private var stato = Stato.VIGILE
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var registro: Registro
    private lateinit var wake: RilevatoreWake
    private lateinit var trascrittore: Trascrittore
    private lateinit var voce: VoceSol
    private lateinit var beeper: ToneGenerator
    private lateinit var esecutore: EsecutoreProposte
    private lateinit var cervello: Cervello

    override fun onCreate() {
        super.onCreate()
        registro = Registro(this)
        wake = RilevatoreWake(this, ::annota) { main.post { avviaGiro() } }
        trascrittore = Trascrittore(this, ::annota)
        voce = VoceSol(this) {}
        beeper = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

        val repo = PromemoriaRepoLocale(filesDir)
        esecutore = EsecutoreProposte(
            repo = repo,
            pianificatore = PianificatoreAllarmi(this),
            voce = VoceInterattiva(),
            registro = ::annota,
        )
        // Senza chiave Anthropic l'app resta un'eco: la pipeline si collauda
        // lo stesso, e nessuno si chiede perché "non risponde".
        cervello = if (BuildConfig.ANTHROPIC_API_KEY.isBlank()) {
            CervelloEco()
        } else {
            CervelloClaude(
                api = ClaudeApi(BuildConfig.ANTHROPIC_API_KEY),
                esecutore = esecutore,
                repo = repo,
                registro = ::annota,
            )
        }

        avviaInPrimoPiano()
        if (conMicrofono()) {
            wake.prepara()
        } else {
            registro.annota("permesso_mancante", "RECORD_AUDIO")
        }
        registro.annota(
            "servizio_avviato",
            "wake: ${wake.modalita}, cervello: ${cervello.javaClass.simpleName}",
        )
        scope.launch { allineaOrecchio() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AZIONE_PARLA -> main.post { avviaGiro() } // push-to-talk
            AZIONE_ORECCHIO_ON -> cambiaOrecchio(true)
            AZIONE_ORECCHIO_OFF -> cambiaOrecchio(false)
        }
        return START_STICKY
    }

    // L'impostazione si salva sempre; lo stato si tocca solo se il servizio
    // è fermo ad aspettare. A metà di un giro varrà al riallineamento.
    private fun cambiaOrecchio(attivo: Boolean) {
        scope.launch {
            Impostazioni.impostaOrecchio(this@AscoltoService, attivo)
            registro.annota(if (attivo) "orecchio_on" else "orecchio_off")
            if (stato == Stato.VIGILE || stato == Stato.SILENZIATO) allineaOrecchio()
        }
    }

    // ---- Il giro completo: wake/pulsante → beep → comando → risposta ----

    private fun avviaGiro() {
        if (stato != Stato.VIGILE && stato != Stato.SILENZIATO) return
        cambiaStato(Stato.ASCOLTO_COMANDO)
        wake.pausa() // handoff: il microfono passa al trascrittore
        beeper.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        trascrittore.avvia { testo -> main.post { suComando(testo) } }
    }

    private fun suComando(testo: String?) {
        if (testo == null) {
            rispondi("Non ho sentito nulla.")
            return
        }
        registro.annota("comando", testo)
        cambiaStato(Stato.ELABORA)
        scope.launch {
            val risposta = try {
                cervello.elabora(testo)
            } catch (e: Exception) {
                registro.annota("cervello_errore", e.message ?: e.javaClass.simpleName)
                Risposta("C'è stato un problema di collegamento: riprova tra poco.")
            }
            registro.annota("risposta", risposta.frase)
            if (esecutore.annullabile()) {
                // La coda della frase è deterministica: non si delega al
                // modello il compito di ricordare che si può annullare.
                rispondi(risposta.frase + " Per annullare di': annulla.") { finestraAnnulla() }
            } else {
                rispondi(risposta.frase)
            }
        }
    }

    // Ogni giro finisce qui: la frase chiude il cerchio, poi si torna
    // com'era (vigile, o muto se l'utente ha spento l'orecchio).
    private fun rispondi(frase: String, poi: () -> Unit = { riallinea() }) {
        cambiaStato(Stato.RISPONDE)
        voce.parla(frase) { main.post(poi) }
    }

    // Dopo un'azione gialla il microfono resta acceso per qualche secondo:
    // "annulla" funziona a voce nuda, senza wake word. Qualunque altra cosa
    // (o il silenzio) chiude la finestra senza conseguenze.
    private fun finestraAnnulla() {
        cambiaStato(Stato.ASCOLTO_ANNULLA)
        trascrittore.avvia { testo ->
            main.post {
                val detto = testo?.let(Normalizzatore::normalizza) ?: ""
                if ("annulla" in detto) {
                    registro.annota("annulla_a_caldo", detto)
                    scope.launch { rispondi(esecutore.annullaUltima()) }
                } else {
                    riallinea()
                }
            }
        }
    }

    private fun riallinea() {
        scope.launch { allineaOrecchio() }
    }

    private suspend fun allineaOrecchio() {
        if (Impostazioni.orecchioAttivo(this)) {
            stato = Stato.VIGILE
            wake.ascolta()
        } else {
            stato = Stato.SILENZIATO
            wake.pausa()
            trascrittore.ferma()
        }
        aggiornaNotifica()
    }

    private fun cambiaStato(nuovo: Stato) {
        stato = nuovo
        aggiornaNotifica()
    }

    // La voce vista dall'esecutore: come il cancello parla e ascolta durante
    // una conferma rossa. Il beep marca l'apertura della finestra.
    private inner class VoceInterattiva : InterazioneVocale {

        override suspend fun leggi(frase: String) {
            suspendCancellableCoroutine { cont ->
                voce.parla(frase) { if (cont.isActive) cont.resume(Unit) }
            }
        }

        override suspend fun ascoltaUnaFrase(): String? {
            cambiaStato(Stato.CONFERMA)
            return try {
                withContext(Dispatchers.Main) {
                    beeper.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                    suspendCancellableCoroutine<String?> { cont ->
                        trascrittore.avvia { testo -> if (cont.isActive) cont.resume(testo) }
                        cont.invokeOnCancellation { main.post { trascrittore.ferma() } }
                    }
                }
            } finally {
                cambiaStato(Stato.ELABORA)
            }
        }
    }

    // ---- Notifica persistente: lo stato di Sol a colpo d'occhio ----

    private fun avviaInPrimoPiano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                ID_NOTIFICA,
                costruisciNotifica(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(ID_NOTIFICA, costruisciNotifica())
        }
    }

    private fun costruisciNotifica(): Notification {
        val apri = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val parla = PendingIntent.getService(
            this,
            1,
            Intent(this, AscoltoService::class.java).setAction(AZIONE_PARLA),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val testoStato = when (stato) {
            Stato.SILENZIATO -> getString(R.string.stato_silenziato)
            Stato.ASCOLTO_COMANDO -> getString(R.string.stato_ascolto)
            Stato.ELABORA -> getString(R.string.stato_elabora)
            Stato.CONFERMA -> getString(R.string.stato_conferma)
            Stato.RISPONDE -> getString(R.string.stato_risponde)
            Stato.ASCOLTO_ANNULLA -> getString(R.string.stato_annulla)
            Stato.VIGILE -> when (wake.modalita) {
                RilevatoreWake.Modalita.EHI_SOL -> getString(R.string.stato_vigile)
                RilevatoreWake.Modalita.PROVA -> getString(R.string.stato_vigile_prova)
                RilevatoreWake.Modalita.SPENTO -> getString(R.string.stato_solo_pulsante)
            }
        }
        return NotificationCompat.Builder(this, SolApp.CANALE_SERVIZIO)
            .setSmallIcon(R.drawable.ic_sol)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(testoStato)
            .setContentIntent(apri)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.azione_parla), parla)
            .build()
    }

    private fun aggiornaNotifica() {
        getSystemService(NotificationManager::class.java)
            .notify(ID_NOTIFICA, costruisciNotifica())
    }

    // ---- Contorno ----

    private fun conMicrofono(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun annota(tipo: String, dettaglio: String) = registro.annota(tipo, dettaglio)

    override fun onDestroy() {
        registro.annota("servizio_fermato")
        wake.spegni()
        trascrittore.ferma()
        voce.spegni()
        beeper.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ID_NOTIFICA = 1
        const val AZIONE_PARLA = "eu.stgm.sol.PARLA"
        const val AZIONE_ORECCHIO_ON = "eu.stgm.sol.ORECCHIO_ON"
        const val AZIONE_ORECCHIO_OFF = "eu.stgm.sol.ORECCHIO_OFF"
    }
}
