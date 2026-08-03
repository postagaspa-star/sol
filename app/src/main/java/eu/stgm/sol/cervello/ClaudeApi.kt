package eu.stgm.sol.cervello

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// Chiamata diretta all'API Messages di Anthropic. Niente SDK per scelta:
// l'SDK Java è pensato per il server, su Android porta dipendenze pesanti;
// per un solo endpoint il contratto wire è la parte stabile dell'API, e il
// loop dei tool lo vogliamo comunque scritto in casa (deve passare tutto
// dal cancello conferme).
class ClaudeApi(
    private val chiave: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {

    class ApiException(val codice: Int, dettaglio: String) :
        Exception("HTTP $codice: $dettaglio")

    // Bloccante: va chiamata da Dispatchers.IO. Un solo nuovo tentativo, e
    // solo per errori di rete o 5xx/529: sul 429 o su un 4xx riprovare
    // subito è inutile.
    fun messaggi(sistema: String, messaggi: JSONArray, tools: JSONArray): JSONObject {
        val corpo = costruisciCorpo(sistema, messaggi, tools).toString()
        var ultimo: Exception? = null
        repeat(2) { tentativo ->
            try {
                val richiesta = Request.Builder()
                    .url(URL)
                    .header("x-api-key", chiave)
                    .header("anthropic-version", VERSIONE_API)
                    .post(corpo.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(richiesta).execute().use { r ->
                    val testo = r.body?.string() ?: ""
                    if (r.isSuccessful) return JSONObject(testo)
                    val errore = ApiException(r.code, testo.take(300))
                    if (r.code < 500) throw errore
                    ultimo = errore
                }
            } catch (e: IOException) {
                ultimo = e
            }
            if (tentativo == 0) Thread.sleep(800)
        }
        throw ultimo ?: IllegalStateException("nessuna risposta")
    }

    companion object {
        const val MODELLO = "claude-haiku-4-5"
        private const val URL = "https://api.anthropic.com/v1/messages"
        private const val VERSIONE_API = "2023-06-01"

        // Pura e testabile. disable_parallel_tool_use: un tool per risposta,
        // così il cancello valuta le azioni una alla volta, in ordine.
        fun costruisciCorpo(sistema: String, messaggi: JSONArray, tools: JSONArray): JSONObject =
            JSONObject()
                .put("model", MODELLO)
                .put("max_tokens", 500)
                .put("system", sistema)
                .put("messages", messaggi)
                .put("tools", tools)
                .put(
                    "tool_choice",
                    JSONObject().put("type", "auto").put("disable_parallel_tool_use", true),
                )
    }
}
