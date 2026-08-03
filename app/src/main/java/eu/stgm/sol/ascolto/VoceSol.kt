package eu.stgm.sol.ascolto

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// La voce di Sol: TTS di sistema in italiano, gratis e senza rete.
// parla() riconsegna il controllo con onFine — chi parla per ultimo decide
// quando riaccendere l'orecchio (handoff del microfono). Attenzione: onFine
// arriva sul thread del TTS, chi lo usa deve tornare sul main da sé.
class VoceSol(ctx: Context, onPronta: () -> Unit) {

    private var pronta = false
    private val contatore = AtomicInteger(0)
    private val codaFine = ConcurrentHashMap<String, () -> Unit>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { esito ->
            if (esito == TextToSpeech.SUCCESS) {
                tts.language = Locale.ITALIAN
                pronta = true
                onPronta()
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) {
                codaFine.remove(id)?.invoke()
            }

            @Deprecated("richiesto dalla classe base")
            override fun onError(id: String) {
                codaFine.remove(id)?.invoke()
            }
        })
    }

    fun parla(frase: String, onFine: () -> Unit = {}) {
        if (!pronta) {
            // TTS non inizializzato: meglio proseguire il giro in silenzio
            // che bloccare la pipeline in eterno.
            onFine()
            return
        }
        val id = "sol-" + contatore.incrementAndGet()
        codaFine[id] = onFine
        tts.speak(frase, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun spegni() {
        tts.stop()
        tts.shutdown()
    }
}
