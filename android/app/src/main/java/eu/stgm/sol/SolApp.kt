package eu.stgm.sol

import android.app.Application
import eu.stgm.sol.dati.Archivio
import eu.stgm.sol.dati.CacheFile
import eu.stgm.sol.notifiche.Notificatore
import eu.stgm.sol.notifiche.NovitaWorker
import eu.stgm.sol.rete.SolClient
import java.io.File
import kotlin.concurrent.thread

/** Gli oggetti condivisi di tutta l'app, creati una volta sola. */
class SolApp : Application() {
    val archivio: Archivio by lazy { Archivio(this) }

    /** Gli ultimi dati visti, per consultarli senza rete. */
    val cache: CacheFile by lazy { CacheFile(File(filesDir, "ultimi-dati")) }

    /** Il testo delle bozze in modifica, per non perderlo (anche se Android chiude l'app). */
    val bozzeInModifica: CacheFile by lazy { CacheFile(File(filesDir, "bozze-in-modifica")) }

    val client: SolClient by lazy { SolClient({ archivio.leggi() }, alNonAutorizzato = ::scollegatoDalServer) }

    /** Il server non riconosce più il telefono: via token e dati, e l'abbinamento lo dirà. */
    private suspend fun scollegatoDalServer() {
        archivio.dimentica(perso = true)
        svuotaDatiLocali()
    }

    /** Un altro server, un nuovo abbinamento o uno scollegamento: niente dati di prima. */
    fun svuotaDatiLocali() {
        cache.svuota()
        bozzeInModifica.svuota()
    }

    override fun onCreate() {
        super.onCreate()
        Notificatore.creaCanali(this)
        NovitaWorker.pianifica(this)
        thread(name = "pota-cache", isDaemon = true) {
            cache.pota()
            bozzeInModifica.pota()
        }
    }
}
