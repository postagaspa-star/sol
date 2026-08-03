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
import eu.stgm.sol.MainActivity
import eu.stgm.sol.R
import eu.stgm.sol.SolApp
import eu.stgm.sol.cervello.Cervello
import eu.stgm.sol.cervello.CervelloEco
import eu.stgm.sol.cervello.Risposta
import eu.stgm.sol.dati.Impostazioni
import eu.stgm.sol.dati.Registro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Il servizio sempre attivo: orchestra wake word, trascrizione, cervello e
// voce. Due regole della casa che valgono per ogni riga di questo file:
//  1. un solo padrone del microfono per volta (wake OPPURE trascrittore);
//  2. ogni giro si chiude con una frase — il silenzio non è mai un esito.
class AscoltoService : Service() {

    private enum class Stato { SILENZIATO, VIGILE, ASCOLTO_COMANDO, RISPONDE }

    private var stato = Stato.VIGILE
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var registro: Registro
    private lateinit var wake: RilevatoreWake
    private lateinit var trascrittore: Trascrittore
    private lateinit var voce: VoceSol
    private lateinit var beeper: ToneGenerator

    // Milestone 2: qui entra CervelloClaude. L'eco serve a collaudare la
    // pipeline voce → testo → voce senza altre variabili in gioco.
    private val cervello: Cervello = CervelloEco()

    override fun onCreate() {
        super.onCreate()
        registro = Registro(this)
        wake = RilevatoreWake(this, ::annota) { main.post { avviaGiro() } }
        trascrittore = Trascrittore(this, ::annota)
        voce = VoceSol(this) {}
        beeper = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        avviaInPrimoPiano()
        if (conMicrofono()) {
            wake.prepara()
        } else {
            registro.annota("permesso_mancante", "RECORD_AUDIO")
        }
        registro.annota("servizio_avviato", "wake: ${wake.modalita}")
        scope.launch { allineaOrecchio() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AZIONE_PARLA -> main.post { avviaGiro() } // push-to-talk
            AZIONE_ORECCHIO_ON -> scope.launch {
                Impostazioni.impostaOrecchio(this@AscoltoService, true)
                registro.annota("orecchio_on")
                allineaOrecchio()
            }
            AZIONE_ORECCHIO_OFF -> scope.launch {
                Impostazioni.impostaOrecchio(this@AscoltoService, false)
                registro.annota("orecchio_off")
                allineaOrecchio()
            }
        }
        return START_STICKY
    }

    // ---- Il giro completo: wake/pulsante → beep → comando → risposta ----

    private fun avviaGiro() {
        if (stato == Stato.ASCOLTO_COMANDO || stato == Stato.RISPONDE) return
        stato = Stato.ASCOLTO_COMANDO
        aggiornaNotifica()
        wake.pausa() // handoff: il microfono passa al trascrittore
        beeper.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        trascrittore.avvia { testo -> main.post { suComando(testo) } }
    }

    private fun suComando(testo: String?) {
        if (testo == null) {
            rispondi("Non ho sentito nulla.")
            return
        }
        stato = Stato.RISPONDE
        aggiornaNotifica()
        scope.launch {
            val risposta = try {
                cervello.elabora(testo)
            } catch (e: Exception) {
                registro.annota("cervello_errore", e.message ?: "")
                Risposta("C'è stato un problema, riprova tra poco.")
            }
            rispondi(risposta.frase)
        }
    }

    // Ogni giro finisce qui: la frase chiude il cerchio, poi l'orecchio
    // torna com'era (vigile, o muto se l'utente l'ha spento).
    private fun rispondi(frase: String) {
        stato = Stato.RISPONDE
        aggiornaNotifica()
        voce.parla(frase) { main.post { scope.launch { allineaOrecchio() } } }
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
            Stato.RISPONDE -> getString(R.string.stato_risponde)
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
