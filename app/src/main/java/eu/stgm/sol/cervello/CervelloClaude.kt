package eu.stgm.sol.cervello

// Milestone 2: Claude Haiku 4.5 via API Anthropic con i tool della fase 1
// (crea/lista/completa/sposta/elimina promemoria). Il loop dei tool si
// scrive a mano, niente tool runner: ogni tool_use DEVE passare dal
// CancelloConferme e non devono esistere strade che lo saltano.
//
// Nel system prompt andranno data e ora correnti, il fuso (Europe/Rome) e
// una vista compatta dei promemoria: è ciò che permette al modello di
// risolvere "martedì prossimo" senza chiedere.
class CervelloClaude : Cervello {

    override suspend fun elabora(testo: String): Risposta {
        TODO("Milestone 2: chiamata a $MODELLO con tool calling")
    }

    private companion object {
        const val MODELLO = "claude-haiku-4-5"
    }
}
