package eu.stgm.sol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

// Canali creati una volta sola all'avvio del processo: il servizio e il
// receiver di riavvio devono poter notificare senza occuparsi del setup.
class SolApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CANALE_SERVIZIO,
                getString(R.string.canale_servizio),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CANALE_AVVISI,
                getString(R.string.canale_avvisi),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        const val CANALE_SERVIZIO = "sol_servizio"
        const val CANALE_AVVISI = "sol_avvisi"
    }
}
