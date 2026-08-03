package eu.stgm.sol.cervello

import org.json.JSONArray

// I tool della fase 1. Ogni nome DEVE avere una classe di rischio in
// RegoleDiRischio: il test ConfermeTest tiene le due liste allineate.
object DefinizioniTool {

    val TOOLS: JSONArray = JSONArray(
        """
        [
          {
            "name": "crea_promemoria",
            "description": "Crea un promemoria. Usalo quando l'utente vuole ricordarsi di fare qualcosa.",
            "input_schema": {
              "type": "object",
              "properties": {
                "titolo": {"type": "string", "description": "Cosa ricordare, in breve"},
                "scadenza": {"type": "string", "description": "Quando, formato YYYY-MM-DDTHH:MM, sempre nel futuro; ometti se l'utente non ha detto quando"}
              },
              "required": ["titolo"]
            }
          },
          {
            "name": "lista_promemoria",
            "description": "Elenca i promemoria attivi.",
            "input_schema": {"type": "object", "properties": {}}
          },
          {
            "name": "completa_promemoria",
            "description": "Segna un promemoria come fatto.",
            "input_schema": {
              "type": "object",
              "properties": {"id": {"type": "integer", "description": "L'id del promemoria, preso dalla lista"}},
              "required": ["id"]
            }
          },
          {
            "name": "sposta_promemoria",
            "description": "Cambia la scadenza di un promemoria esistente.",
            "input_schema": {
              "type": "object",
              "properties": {
                "id": {"type": "integer"},
                "nuova_scadenza": {"type": "string", "description": "YYYY-MM-DDTHH:MM"}
              },
              "required": ["id", "nuova_scadenza"]
            }
          },
          {
            "name": "elimina_promemoria",
            "description": "Elimina definitivamente un promemoria. Solo quando l'utente chiede esplicitamente di eliminare o cancellare.",
            "input_schema": {
              "type": "object",
              "properties": {"id": {"type": "integer"}},
              "required": ["id"]
            }
          },
          {
            "name": "annulla_ultima_azione",
            "description": "Annulla l'ultima azione appena eseguita. Usalo quando l'utente dice di annullare.",
            "input_schema": {"type": "object", "properties": {}}
          }
        ]
        """
    )

    val NOMI: List<String> =
        (0 until TOOLS.length()).map { TOOLS.getJSONObject(it).getString("name") }
}
