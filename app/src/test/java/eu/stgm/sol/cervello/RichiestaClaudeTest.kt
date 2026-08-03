package eu.stgm.sol.cervello

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Il corpo della richiesta è un contratto: modello giusto, tool presenti,
// un tool per volta (il cancello valuta le azioni in ordine, mai in blocco).
class RichiestaClaudeTest {

    @Test
    fun `il corpo della richiesta rispetta il contratto`() {
        val messaggi = JSONArray().put(
            JSONObject().put("role", "user").put("content", "ricordami la bolletta")
        )
        val corpo = ClaudeApi.costruisciCorpo("prompt di sistema", messaggi, DefinizioniTool.TOOLS)

        assertEquals("claude-haiku-4-5", corpo.getString("model"))
        assertEquals("prompt di sistema", corpo.getString("system"))
        assertEquals(500, corpo.getInt("max_tokens"))
        assertEquals(6, corpo.getJSONArray("tools").length())
        assertEquals(1, corpo.getJSONArray("messages").length())
        val sceltaTool = corpo.getJSONObject("tool_choice")
        assertEquals("auto", sceltaTool.getString("type"))
        assertTrue(sceltaTool.getBoolean("disable_parallel_tool_use"))
    }

    @Test
    fun `ogni tool ha nome descrizione e schema`() {
        for (i in 0 until DefinizioniTool.TOOLS.length()) {
            val tool = DefinizioniTool.TOOLS.getJSONObject(i)
            assertTrue(tool.getString("name").isNotBlank())
            assertTrue(tool.getString("description").isNotBlank())
            assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
        }
    }
}
