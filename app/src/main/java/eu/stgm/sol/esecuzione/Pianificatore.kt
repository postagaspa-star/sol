package eu.stgm.sol.esecuzione

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import eu.stgm.sol.MainActivity
import eu.stgm.sol.R
import eu.stgm.sol.SolApp
import eu.stgm.sol.dati.FormattatoreDate

// Le sveglie delle scadenze. Interfaccia separata dall'implementazione
// Android così l'EsecutoreProposte si collauda su JVM con un finto
// pianificatore.
interface Pianificatore {
    fun pianifica(promemoria: Promemoria)
    fun cancella(id: Int)
}

class PianificatoreAllarmi(private val ctx: Context) : Pianificatore {

    override fun pianifica(promemoria: Promemoria) {
        val quando = promemoria.scadenzaIso?.let(FormattatoreDate::millis) ?: return
        if (quando <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(promemoria.id, promemoria.titolo)
        // Esatta dove si può; dove l'utente ha revocato il permesso (31-32)
        // si ripiega su una finestra di 10 minuti: meglio in ritardo che mai.
        val esatta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (esatta) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, quando, 10 * 60_000L, pi)
        }
    }

    override fun cancella(id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(id, ""))
    }

    // requestCode = id del promemoria: una sveglia per promemoria, e la
    // cancellazione ritrova sempre la sua.
    private fun pendingIntent(id: Int, titolo: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            id,
            Intent(ctx, SvegliaPromemoria::class.java)
                .putExtra(SvegliaPromemoria.EXTRA_ID, id)
                .putExtra(SvegliaPromemoria.EXTRA_TITOLO, titolo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class SvegliaPromemoria : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val titolo = intent.getStringExtra(EXTRA_TITOLO)?.takeIf { it.isNotBlank() } ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val apri = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notifica = NotificationCompat.Builder(ctx, SolApp.CANALE_PROMEMORIA)
            .setSmallIcon(R.drawable.ic_sol)
            .setContentTitle(ctx.getString(R.string.promemoria))
            .setContentText(titolo)
            .setContentIntent(apri)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + id, notifica)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITOLO = "titolo"
    }
}
