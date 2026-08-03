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
import eu.stgm.sol.esecuzione.PianificatoreAllarmi
import eu.stgm.sol.esecuzione.PromemoriaRepoLocale
import kotlinx.coroutines.runBlocking

// Due compiti dopo un riavvio (o un aggiornamento dell'app):
//  1. rimettere le sveglie dei promemoria — AlarmManager le perde;
//  2. solo al riavvio: avvisare che serve un tocco per riattivare il
//     microfono. Da Android 11 un servizio partito da solo non può usarlo
//     (permesso "mentre l'app è in uso"): niente trucchi, una notifica
//     chiara e l'ascolto riparte dall'Activity in primo piano.
class AvvioDopoRiavvio : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                notificaRiattiva(ctx)
                ripianificaSveglie(ctx)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> ripianificaSveglie(ctx)
        }
    }

    private fun ripianificaSveglie(ctx: Context) {
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    val pianificatore = PianificatoreAllarmi(ctx)
                    PromemoriaRepoLocale(ctx.filesDir).lista().forEach(pianificatore::pianifica)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notificaRiattiva(ctx: Context) {
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
