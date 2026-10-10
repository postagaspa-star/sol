package eu.stgm.sol.aggiornamento

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import eu.stgm.sol.BuildConfig
import eu.stgm.sol.SolApp
import eu.stgm.sol.dati.VersioneApp
import eu.stgm.sol.notifiche.Notificatore
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface EsitoAggiornamento {
    data object GiaAggiornato : EsitoAggiornamento
    data class Avviato(val versione: String) : EsitoAggiornamento
    data class Rimandato(val versione: String) : EsitoAggiornamento
    data class NonRiuscito(val messaggio: String) : EsitoAggiornamento
}

/** La frase per Manuel. */
fun EsitoAggiornamento.testo(): String = when (this) {
    EsitoAggiornamento.GiaAggiornato -> "Hai già l'ultima versione."
    is EsitoAggiornamento.Avviato -> "Sto installando la versione $versione. Se Android lo chiede, conferma."
    is EsitoAggiornamento.Rimandato -> "La versione $versione è pronta: riprovo più tardi."
    is EsitoAggiornamento.NonRiuscito -> messaggio
}

/** L'impronta SHA-256 di un file, in esadecimale minuscolo. */
fun impronta(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ingresso ->
        val pezzo = ByteArray(64 * 1024)
        while (true) {
            val n = ingresso.read(pezzo)
            if (n < 0) break
            digest.update(pezzo, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Il file è proprio quello che il server ha pubblicato (15.10): stessa dimensione, stessa impronta. */
fun coincide(file: File, versione: VersioneApp): Boolean {
    val sha = versione.sha256?.trim()?.lowercase() ?: return false
    val byte = versione.byte ?: return false
    return file.isFile && file.length() == byte && impronta(file) == sha
}

/**
 * La parte senza Android dell'aggiornamento: chiede la versione, scarica l'APK e
 * lo controlla. Un file che non coincide non arriva mai a PackageInstaller.
 */
class PreparatoreAggiornamento(
    private val client: SolClient,
    private val cartella: File,
    private val codiceAttuale: Int,
) {
    sealed interface Preparazione {
        data class Pronto(val file: File, val versione: VersioneApp) : Preparazione
        data class NonRiuscito(val messaggio: String) : Preparazione
    }

    /** (versione più nuova della nostra o null, frase del problema o null). */
    suspend fun versioneNuova(): Pair<VersioneApp?, String?> = when (val e = client.versione()) {
        is Esito.Ok -> e.dato.android?.takeIf { it.codice > codiceAttuale } to null
        is Esito.Errore -> null to e.messaggio
        Esito.Irraggiungibile -> null to "Non riesco a controllare: il server di casa non risponde."
    }

    suspend fun prepara(versione: VersioneApp): Preparazione {
        if (versione.sha256.isNullOrBlank() || versione.byte == null) {
            return Preparazione.NonRiuscito("Il server non indica l'impronta dell'aggiornamento: per sicurezza non lo installo.")
        }
        return withContext(Dispatchers.IO) {
            cartella.mkdirs()
            val file = File(cartella, "sol-${versione.codice}.apk")
            // Già scaricato e controllato a un giro precedente (Manuel non ha ancora confermato).
            if (coincide(file, versione)) return@withContext Preparazione.Pronto(file, versione)
            cartella.listFiles()?.forEach { it.delete() }
            val temporaneo = File(cartella, "scaricamento.tmp")
            if (!client.scaricaApk(temporaneo)) {
                temporaneo.delete()
                return@withContext Preparazione.NonRiuscito("Non sono riuscito a scaricare l'aggiornamento. Riprovo più tardi.")
            }
            if (!coincide(temporaneo, versione)) {
                temporaneo.delete()
                return@withContext Preparazione.NonRiuscito(
                    "Il file scaricato non coincide con quello pubblicato dal server: l'ho scartato. Riprovo più tardi.",
                )
            }
            if (!temporaneo.renameTo(file)) {
                temporaneo.delete()
                return@withContext Preparazione.NonRiuscito("Non c'è spazio per l'aggiornamento.")
            }
            Preparazione.Pronto(file, versione)
        }
    }
}

/**
 * Aggiornamento automatico: se `android.codice` è più alto del nostro scarica
 * /scarica/sol.apk, ne controlla dimensione e impronta e lo passa a
 * PackageInstaller. Ogni fallimento torna come esito, mai come eccezione.
 *
 * In sottofondo ([inSottofondo]) Android spesso vuole la conferma di Manuel e
 * non lascia aprire la finestra: arriva una notifica che la apre. Un tentativo
 * in sottofondo al massimo al giorno per versione, così non insiste; il pulsante
 * delle Impostazioni riprova sempre.
 */
class Aggiornatore(private val context: Context) {

    suspend fun controlla(forza: Boolean = false, inSottofondo: Boolean = false): EsitoAggiornamento {
        val app = context.applicationContext as SolApp
        val preparatore = PreparatoreAggiornamento(
            app.client,
            File(context.cacheDir, "aggiornamenti"),
            BuildConfig.VERSION_CODE,
        )
        val (ultima, problema) = preparatore.versioneNuova()
        if (problema != null) return EsitoAggiornamento.NonRiuscito(problema)
        if (ultima == null) return EsitoAggiornamento.GiaAggiornato

        if (inSottofondo && !forza) {
            val rimandato = app.archivio.aggiornamentoRimandato()
            if (rimandato != null && rimandato.first == ultima.codice &&
                System.currentTimeMillis() - rimandato.second < TimeUnit.HOURS.toMillis(24)
            ) {
                return EsitoAggiornamento.Rimandato(ultima.nome)
            }
        }

        return when (val pronto = preparatore.prepara(ultima)) {
            is PreparatoreAggiornamento.Preparazione.Pronto -> {
                if (inSottofondo) app.archivio.registraAggiornamentoRimandato(ultima.codice, System.currentTimeMillis())
                if (installa(pronto.file, ultima)) {
                    EsitoAggiornamento.Avviato(ultima.nome)
                } else {
                    EsitoAggiornamento.NonRiuscito("Android non ha accettato l'aggiornamento. Riprova più tardi.")
                }
            }
            is PreparatoreAggiornamento.Preparazione.NonRiuscito -> EsitoAggiornamento.NonRiuscito(pronto.messaggio)
        }
    }

    private suspend fun installa(apk: File, versione: VersioneApp): Boolean = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        // Le sessioni rimaste in attesa di un giro precedente: una sola alla volta.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val parametri = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // Il sistema rifiuta un APK di un pacchetto diverso invece di installarlo come app nuova.
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(parametri)
            installer.openSession(sessionId).use { sessione ->
                apk.inputStream().use { ingresso ->
                    sessione.openWrite(NOME_APK, 0, apk.length()).use { uscita ->
                        ingresso.copyTo(uscita)
                        sessione.fsync(uscita)
                    }
                }
                sessione.commit(intentEsito(sessionId, versione).intentSender)
            }
            true
        } catch (e: IOException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        } catch (e: SecurityException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    private fun intentEsito(sessionId: Int, versione: VersioneApp): PendingIntent {
        val intent = Intent(context, AggiornamentoReceiver::class.java)
            .setAction(AggiornamentoReceiver.AZIONE)
            .setPackage(context.packageName)
            .putExtra(AggiornamentoReceiver.EXTRA_CODICE, versione.codice)
            .putExtra(AggiornamentoReceiver.EXTRA_NOME, versione.nome)
        // MUTABLE: il sistema ci scrive dentro l'esito e l'eventuale richiesta di conferma.
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flag)
    }

    private companion object {
        const val NOME_APK = "sol.apk"
    }
}

/** Sol è sullo schermo? Da dietro le quinte Android non lascia aprire finestre. */
object AppInPrimoPiano {
    @Volatile
    var visibile: Boolean = false
}

/**
 * L'esito di PackageInstaller. Se Android vuole la conferma: con l'app davanti
 * la finestra si apre subito, altrimenti una notifica la apre al tocco.
 */
class AggiornamentoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stato = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val codice = intent.getIntExtra(EXTRA_CODICE, 0)
        val nome = intent.getStringExtra(EXTRA_NOME)
        when (stato) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val conferma = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (AppInPrimoPiano.visibile) {
                    conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(conferma)
                } else {
                    Notificatore.mostraAggiornamento(context, nome, conferma)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                // Non riuscita: niente nuovi tentativi in sottofondo per un giorno.
                val app = context.applicationContext as? SolApp ?: return
                val attesa = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        if (codice > 0) app.archivio.registraAggiornamentoRimandato(codice, System.currentTimeMillis())
                    } finally {
                        attesa.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val AZIONE = "eu.stgm.sol.ESITO_INSTALLAZIONE"
        const val EXTRA_CODICE = "codice"
        const val EXTRA_NOME = "nome"
    }
}
