package eu.stgm.sol.ascolto

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import eu.stgm.sol.BuildConfig

// Wake word «Ehi Sol» con Porcupine, tutto on-device: prima della parola
// chiave nessun audio lascia il telefono. Tre modalità, in ordine di realtà:
//   EHI_SOL — chiave + modelli addestrati presenti in assets/
//   PROVA   — chiave presente ma modelli assenti → parola inglese di test
//   SPENTO  — niente chiave → si usa solo il pulsante Parla
// L'app resta usabile in tutti e tre i casi: la wake word è un di più,
// mai un requisito per funzionare.
class RilevatoreWake(
    private val ctx: Context,
    private val registro: (String, String) -> Unit,
    private val onWake: () -> Unit,
) {

    enum class Modalita { EHI_SOL, PROVA, SPENTO }

    var modalita = Modalita.SPENTO
        private set

    private var manager: PorcupineManager? = null

    fun prepara() {
        val chiave = BuildConfig.PICOVOICE_ACCESS_KEY
        if (chiave.isBlank()) {
            registro("wake_spenta", "manca la chiave Picovoice (sol-keys)")
            return
        }
        val assets = ctx.assets.list("")?.toSet() ?: emptySet()
        val personalizzata = KEYWORD in assets && MODELLO_IT in assets
        try {
            val builder = PorcupineManager.Builder().setAccessKey(chiave)
            if (personalizzata) {
                builder.setKeywordPath(KEYWORD).setModelPath(MODELLO_IT)
            } else {
                builder.setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
            }
            manager = builder
                .setSensitivity(0.6f)
                .build(ctx) {
                    registro("wake_rilevata", "")
                    onWake()
                }
            modalita = if (personalizzata) Modalita.EHI_SOL else Modalita.PROVA
            registro("wake_pronta", modalita.name)
        } catch (e: Exception) {
            registro("wake_errore", e.message ?: e.javaClass.simpleName)
            modalita = Modalita.SPENTO
        }
    }

    // Handoff del microfono: un solo padrone per volta. Il rilevatore va
    // messo in pausa PRIMA di accendere il Trascrittore, e riacceso dopo.
    fun ascolta() {
        try {
            manager?.start()
        } catch (e: Exception) {
            registro("wake_errore", "start: ${e.message}")
        }
    }

    fun pausa() {
        try {
            manager?.stop()
        } catch (_: Exception) {
        }
    }

    fun spegni() {
        pausa()
        manager?.delete()
        manager = null
        modalita = Modalita.SPENTO
    }

    private companion object {
        const val KEYWORD = "ehi_sol_android.ppn"
        const val MODELLO_IT = "porcupine_params_it.pv"
    }
}
