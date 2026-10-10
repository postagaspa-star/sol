package eu.stgm.sol.notifiche

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.stgm.sol.SolApp
import eu.stgm.sol.aggiornamento.Aggiornatore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/**
 * Ogni 15 minuti (il minimo di WorkManager) chiede al server le novità e
 * notifica solo quelle importanti. Nello stesso giro guarda se c'è una
 * versione nuova dell'app.
 */
class NovitaWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SolApp
        val giro = GiroNovita(app.client, app.archivio) { avvisi -> Notificatore.mostra(applicationContext, avvisi) }
        val esito = giro.giro()
        if (esito == EsitoGiro.FATTO) controllaAggiornamento()
        return when (esito) {
            EsitoGiro.SENZA_RETE -> Result.retry()
            EsitoGiro.FATTO, EsitoGiro.NON_ABBINATO, EsitoGiro.NON_AUTORIZZATO, EsitoGiro.ERRORE -> Result.success()
        }
    }

    private suspend fun controllaAggiornamento() {
        if (!aggiornamento.tryLock()) return
        try {
            Aggiornatore(applicationContext).controlla(inSottofondo = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Il prossimo giro ci riprova: un aggiornamento non deve mai fermare le notifiche.
        } finally {
            aggiornamento.unlock()
        }
    }

    companion object {
        private const val NOME_LAVORO = "novita"
        private val aggiornamento = Mutex()

        fun pianifica(context: Context) {
            val richiesta = PeriodicWorkRequestBuilder<NovitaWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NOME_LAVORO, ExistingPeriodicWorkPolicy.UPDATE, richiesta)
        }
    }
}

/** Dopo un riavvio o un aggiornamento dell'app i controlli ripartono da soli. */
class AvvioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NovitaWorker.pianifica(context)
        }
    }
}
