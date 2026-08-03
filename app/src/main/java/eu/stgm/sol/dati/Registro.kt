package eu.stgm.sol.dati

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Registro azioni in JSONL, un file al mese in filesDir/registro/. È la base
// del collaudo sul campo (wake mancate, trascrizioni errate, esiti) e del
// debug remoto: se non è annotato qui, non è successo. Arriva da thread
// diversi (callback di Porcupine, del riconoscitore, del TTS), da cui il
// @Synchronized.
class Registro(private val ctx: Context) {

    @Synchronized
    fun annota(tipo: String, dettaglio: String = "") {
        val riga = JSONObject()
            .put("ts", ISO.format(Date()))
            .put("tipo", tipo)
            .put("dettaglio", dettaglio)
        file().appendText(riga.toString() + "\n")
    }

    private fun file(): File {
        val dir = File(ctx.filesDir, "registro").apply { mkdirs() }
        return File(dir, MESE.format(Date()) + ".jsonl")
    }

    private companion object {
        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY)
        val MESE = SimpleDateFormat("yyyy-MM", Locale.ITALY)
    }
}
