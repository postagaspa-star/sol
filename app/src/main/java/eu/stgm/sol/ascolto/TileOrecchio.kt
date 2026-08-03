package eu.stgm.sol.ascolto

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.stgm.sol.dati.Impostazioni
import kotlinx.coroutines.runBlocking

// Il tile «Orecchio di Sol»: spegnere l'ascolto quando c'è un cliente in
// ufficio deve costare uno swipe, non un giro nelle impostazioni.
class TileOrecchio : TileService() {

    override fun onStartListening() {
        aggiorna(runBlocking { Impostazioni.orecchioAttivo(this@TileOrecchio) })
    }

    override fun onClick() {
        val nuovo = runBlocking {
            val attivo = Impostazioni.orecchioAttivo(this@TileOrecchio)
            Impostazioni.impostaOrecchio(this@TileOrecchio, !attivo)
            !attivo
        }
        val azione =
            if (nuovo) AscoltoService.AZIONE_ORECCHIO_ON else AscoltoService.AZIONE_ORECCHIO_OFF
        try {
            startService(Intent(this, AscoltoService::class.java).setAction(azione))
        } catch (_: Exception) {
            // Servizio non in esecuzione (es. dopo un riavvio): l'impostazione
            // è comunque salvata e varrà alla prossima accensione.
        }
        aggiorna(nuovo)
    }

    private fun aggiorna(attivo: Boolean) {
        qsTile?.apply {
            state = if (attivo) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
