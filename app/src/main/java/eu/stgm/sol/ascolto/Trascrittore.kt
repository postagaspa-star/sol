package eu.stgm.sol.ascolto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// Trascrizione con il riconoscitore di sistema: gratis, e sui telefoni
// recenti gira on-device. Un ascolto per volta: avvia() garantisce
// esattamente UNA chiamata a onEsito, col testo oppure null (silenzio,
// rumore, errore). La diagnosi fine sta nel Registro — la pipeline a monte
// deve solo sapere se c'è testo. Da chiamare sul main thread.
class Trascrittore(
    private val ctx: Context,
    private val registro: (String, String) -> Unit,
) {

    private var recognizer: SpeechRecognizer? = null

    val disponibile: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun avvia(onEsito: (String?) -> Unit) {
        ferma()
        if (!disponibile) {
            registro("trascrizione_indisponibile", "")
            onEsito(null)
            return
        }
        var consegnato = false
        fun consegna(testo: String?) {
            if (consegnato) return
            consegnato = true
            onEsito(testo)
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val testo = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                    registro(
                        if (testo != null) "trascrizione_ok" else "trascrizione_vuota",
                        testo ?: "",
                    )
                    consegna(testo)
                }

                override fun onError(codice: Int) {
                    registro("trascrizione_errore", "codice $codice")
                    consegna(null)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        }
    }

    fun ferma() {
        recognizer?.destroy()
        recognizer = null
    }
}
