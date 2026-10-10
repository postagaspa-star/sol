package eu.stgm.sol.notifiche

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.stgm.sol.MainActivity
import eu.stgm.sol.R

/** Le notifiche di sistema di Sol: urgenti, promemoria e aggiornamenti. */
object Notificatore {
    private const val CANALE_URGENTI = "urgenti"
    private const val CANALE_PROMEMORIA = "promemoria"
    private const val CANALE_AGGIORNAMENTI = "aggiornamenti"
    private const val ID_AGGIORNAMENTO = 4242

    fun creaCanali(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CANALE_URGENTI, "Mail urgenti", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Mail che Sol considera urgenti"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CANALE_PROMEMORIA, "Bozze e scadenze", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Risposte pronte da leggere, scadenze di domani, collegamento col server"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CANALE_AGGIORNAMENTI, "Aggiornamenti dell'app", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Una versione nuova di Sol pronta da installare"
            },
        )
    }

    fun puoNotificare(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun mostra(context: Context, avvisi: List<Avviso>) {
        if (!puoNotificare(context)) return
        val manager = NotificationManagerCompat.from(context)
        avvisi.forEach { avviso ->
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    avviso.mailId?.let { putExtra(MainActivity.EXTRA_MAIL_ID, it) }
                    avviso.bozzaId?.let { putExtra(MainActivity.EXTRA_BOZZA_ID, it) }
                }
            val idNotifica = avviso.chiave.hashCode()
            val tocco = PendingIntent.getActivity(
                context,
                idNotifica,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notifica = NotificationCompat.Builder(context, if (avviso.urgente) CANALE_URGENTI else CANALE_PROMEMORIA)
                .setSmallIcon(R.drawable.ic_notifica)
                .setContentTitle(avviso.titolo)
                .setContentText(avviso.testo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(avviso.testo))
                .setContentIntent(tocco)
                .setAutoCancel(true)
                .setPriority(if (avviso.urgente) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try {
                manager.notify(idNotifica, notifica)
            } catch (e: SecurityException) {
                // Permesso tolto nel frattempo: le novità restano visibili aprendo l'app.
            }
        }
    }

    /**
     * L'aggiornamento è scaricato e controllato ma Android vuole la conferma di
     * Manuel: da dietro le quinte un'app non può aprire la finestra di sistema,
     * quindi una notifica che la apre al tocco. Sempre la stessa: non si accumula.
     */
    fun mostraAggiornamento(context: Context, versione: String?, conferma: Intent) {
        if (!puoNotificare(context)) return
        val tocco = PendingIntent.getActivity(
            context,
            ID_AGGIORNAMENTO,
            conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val titolo = "Versione nuova di Sol" + (versione?.let { " ($it)" } ?: "")
        val notifica = NotificationCompat.Builder(context, CANALE_AGGIORNAMENTI)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo)
            .setContentText("Tocca per installarla.")
            .setContentIntent(tocco)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_AGGIORNAMENTO, notifica)
        } catch (e: SecurityException) {
            // Senza permesso resta il pulsante "Controlla aggiornamenti" nelle Impostazioni.
        }
    }
}
