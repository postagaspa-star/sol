package eu.stgm.sol.ascolto

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import eu.stgm.sol.MainActivity
import eu.stgm.sol.R
import eu.stgm.sol.SolApp

// Da Android 11 un servizio partito da solo dopo il riavvio NON può usare il
// microfono (permesso "mentre l'app è in uso"). Niente trucchi: una notifica
// chiara, un tocco, e l'ascolto riparte dall'Activity in primo piano.
class AvvioDopoRiavvio : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val apri = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_RIATTIVA, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notifica = NotificationCompat.Builder(ctx, SolApp.CANALE_AVVISI)
            .setSmallIcon(R.drawable.ic_sol)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.riattiva_dopo_riavvio))
            .setContentIntent(apri)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_NOTIFICA, notifica)
    }

    private companion object {
        const val ID_NOTIFICA = 2
    }
}
