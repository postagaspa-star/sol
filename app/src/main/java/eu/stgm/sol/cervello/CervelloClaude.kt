package eu.stgm.sol.cervello

import eu.stgm.sol.dati.FormattatoreDate
import eu.stgm.sol.esecuzione.EsecutoreProposte
import eu.stgm.sol.esecuzione.PromemoriaRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

// Claude Haiku 4.5 con tool calling, loop scritto a mano: ogni tool_use
// passa dall'EsecutoreProposte (e quindi dal cancello conferme), nessuna
// scorciatoia. Se un rosso viene annullato il turno si CHIUDE lì: la frase
// dell'esito la dice il codice, non il modello — la garanzia che "azione
// annullata" venga pronunciata non può dipendere da come Claude riassume.
class CervelloClaude(
    private val api: ClaudeApi,
    private val esecutore: EsecutoreProposte,
    private val repo: PromemoriaRepo,
    private val registro: (String, String) -> Unit,
    private val adesso: () -> LocalDateTime = { LocalDateTime.now() },
) : Cervello {

    override suspend fun elabora(testo: String): Risposta {
        val messaggi = JSONArray().put(
            JSONObject().put("role", "user").put("content", testo)
        )
        repeat(MAX_GIRI) {
            val sistema = sistema()
            val risposta = withContext(Dispatchers.IO) {
                api.messaggi(sistema, messaggi, DefinizioniTool.TOOLS)
            }
            annotaUso(risposta)
            val contenuti = risposta.getJSONArray("content")

            if (risposta.optString("stop_reason") != "tool_use") {
                return Risposta(primoTesto(contenuti) ?: "Fatto.")
            }

            // Il blocco assistant va rimandato indietro INTERO (testo +
            // tool_use), è il contratto dell'API.
            messaggi.put(JSONObject().put("role", "assistant").put("content", contenuti))

            val risultati = JSONArray()
            for (i in 0 until contenuti.length()) {
                val blocco = contenuti.getJSONObject(i)
                if (blocco.getString("type") != "tool_use") continue
                val proposta = Proposta(
                    tool = blocco.getString("name"),
                    argomenti = argomenti(blocco.getJSONObject("input")),
                )
                registro("proposta", proposta.tool)
                val r = esecutore.esegui(proposta)
                if (r.turnoInterrotto) {
                    return Risposta(r.fraseImmediata ?: "Azione annullata.")
                }
                risultati.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", blocco.getString("id"))
                        .put("content", r.contenutoPerClaude)
                )
            }
            messaggi.put(JSONObject().put("role", "user").put("content", risultati))
        }
        registro("cervello_errore", "superato il limite di $MAX_GIRI giri")
        return Risposta("Mi sono perso: riprova con parole più semplici.")
    }

    // Data, ora e vista dei promemoria nel system prompt: è ciò che permette
    // di risolvere "martedì prossimo" o "quello della bolletta" al primo
    // colpo, senza stato di conversazione.
    private suspend fun sistema(): String {
        val ora = adesso()
        val giorno = ora.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
        val mese = ora.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
        val minuti = String.format(Locale.ITALIAN, "%02d", ora.minute)
        val vista = repo.lista().joinToString("\n") {
            "- id ${it.id}: ${it.titolo} (${FormattatoreDate.leggibile(it.scadenzaIso, ora.toLocalDate())})"
        }.ifBlank { "(nessuno)" }
        return """
            Sei Sol, un assistente vocale personale in italiano. Le tue risposte vengono lette ad alta voce da una sintesi vocale: una o due frasi brevi e naturali, niente elenchi puntati, niente simboli, niente markdown, niente inglese.
            Oggi è $giorno ${ora.dayOfMonth} $mese ${ora.year}, sono le ${ora.hour}:$minuti (fuso Europe/Rome).
            Gestisci i promemoria dell'utente con i tool. Regole:
            - le scadenze sono nel formato YYYY-MM-DDTHH:MM e sempre nel futuro; se l'utente non dice l'ora, usa le 09:00 e dillo nella risposta;
            - riferisciti ai promemoria solo con gli id della lista qui sotto, non inventare mai un id;
            - se non è chiaro a quale promemoria si riferisce l'utente, chiediglielo invece di tirare a indovinare;
            - se la richiesta non riguarda i promemoria, spiega in una frase che per ora sai gestire solo i promemoria.
            Promemoria attivi:
            $vista
        """.trimIndent()
    }

    private fun argomenti(input: JSONObject): Map<String, String> {
        val mappa = mutableMapOf<String, String>()
        input.keys().forEach { chiave -> mappa[chiave] = input.get(chiave).toString() }
        return mappa
    }

    private fun primoTesto(contenuti: JSONArray): String? {
        for (i in 0 until contenuti.length()) {
            val blocco = contenuti.getJSONObject(i)
            if (blocco.getString("type") == "text") {
                return blocco.getString("text").trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun annotaUso(risposta: JSONObject) {
        val uso = risposta.optJSONObject("usage") ?: return
        registro(
            "claude_uso",
            "in=${uso.optInt("input_tokens")} out=${uso.optInt("output_tokens")}",
        )
    }

    private companion object {
        // Con un tool per giro (disable_parallel_tool_use) un comando reale
        // sta in 2-3 giri; oltre i 6 qualcosa è andato storto.
        const val MAX_GIRI = 6
    }
}
