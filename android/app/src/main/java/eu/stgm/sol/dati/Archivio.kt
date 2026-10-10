package eu.stgm.sol.dati

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import eu.stgm.sol.rete.Configurazione
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dove sta l'abbinamento. Interfaccia: nei test vive in memoria. */
interface DepositoConfigurazione {
    val flusso: Flow<Configurazione?>
    suspend fun leggi(): Configurazione?
    suspend fun salva(configurazione: Configurazione)
    suspend fun dimentica()
}

/** Cosa ricorda il controllo delle novità tra un giro e l'altro. */
interface MemoriaNovita {
    suspend fun cursore(): String?
    suspend fun avvisati(): Set<String>
    suspend fun ricorda(cursore: String?, avvisati: Set<String>)

    /** L'avviso "Sol non è più collegato" è già partito (si dice una volta sola). */
    suspend fun avvisoScollegatoDato(): Boolean
    suspend fun segnaAvvisoScollegatoDato()
}

private val Context.dataStore by preferencesDataStore(name = "sol")

/**
 * Le impostazioni del telefono (DataStore). Il token è cifrato con una chiave
 * dell'Android Keystore che non esce mai dal telefono: chi copiasse i file
 * dell'app non potrebbe usarlo.
 */
class Archivio(private val context: Context) : DepositoConfigurazione, MemoriaNovita {

    private object Chiavi {
        val SERVER = stringPreferencesKey("server")
        val TOKEN_CIFRATO = stringPreferencesKey("token_cifrato")
        val DISPOSITIVO_ID = longPreferencesKey("dispositivo_id")
        val CURSORE = stringPreferencesKey("cursore_novita")
        val AVVISATI = stringSetPreferencesKey("avvisati")
        val RICHIESTA_NOTIFICHE_FATTA = booleanPreferencesKey("richiesta_notifiche_fatta")

        // Il server non riconosce più questo telefono: la schermata di abbinamento lo dice.
        val ABBINAMENTO_PERSO = booleanPreferencesKey("abbinamento_perso")
        val AVVISO_SCOLLEGATO_DATO = booleanPreferencesKey("avviso_scollegato_dato")

        // L'ultimo tentativo d'aggiornamento in sottofondo (o non riuscito): per quella
        // versione il prossimo dopo un giorno (dal pulsante delle Impostazioni sempre).
        val AGGIORNAMENTO_FALLITO_CODICE = intPreferencesKey("aggiornamento_fallito_codice")
        val AGGIORNAMENTO_FALLITO_IL = longPreferencesKey("aggiornamento_fallito_il")
    }

    // Decifrare passa dal Keystore, che su certi telefoni è lento: fuori dal thread principale.
    override val flusso: Flow<Configurazione?> = context.dataStore.data.map { p ->
        val server = p[Chiavi.SERVER] ?: return@map null
        val token = p[Chiavi.TOKEN_CIFRATO]?.let(Cassaforte::decifra) ?: return@map null
        Configurazione(server, token, p[Chiavi.DISPOSITIVO_ID])
    }.flowOn(Dispatchers.Default)

    override suspend fun leggi(): Configurazione? = flusso.first()

    override suspend fun salva(configurazione: Configurazione) {
        val cifrato = withContext(Dispatchers.Default) { Cassaforte.cifra(configurazione.token) }
        context.dataStore.edit { p ->
            // Un altro server: il cursore e gli avvisi di prima non valgono più.
            if (p[Chiavi.SERVER] != configurazione.server) {
                p.remove(Chiavi.CURSORE)
                p.remove(Chiavi.AVVISATI)
            }
            p[Chiavi.SERVER] = configurazione.server
            p[Chiavi.TOKEN_CIFRATO] = cifrato
            val id = configurazione.dispositivoId
            if (id != null) p[Chiavi.DISPOSITIVO_ID] = id else p.remove(Chiavi.DISPOSITIVO_ID)
            p.remove(Chiavi.ABBINAMENTO_PERSO)
            p.remove(Chiavi.AVVISO_SCOLLEGATO_DATO)
        }
    }

    override suspend fun dimentica() = dimentica(perso = false)

    /** [perso]: è stato il server a non riconoscere più il telefono (non un gesto di Manuel). */
    suspend fun dimentica(perso: Boolean) {
        context.dataStore.edit { p ->
            p.remove(Chiavi.TOKEN_CIFRATO)
            p.remove(Chiavi.DISPOSITIVO_ID)
            p.remove(Chiavi.CURSORE)
            p.remove(Chiavi.AVVISATI)
            if (perso) p[Chiavi.ABBINAMENTO_PERSO] = true else p.remove(Chiavi.ABBINAMENTO_PERSO)
        }
    }

    val abbinamentoPerso: Flow<Boolean> = context.dataStore.data.map { p -> p[Chiavi.ABBINAMENTO_PERSO] ?: false }

    override suspend fun avvisoScollegatoDato(): Boolean =
        context.dataStore.data.first()[Chiavi.AVVISO_SCOLLEGATO_DATO] ?: false

    override suspend fun segnaAvvisoScollegatoDato() {
        context.dataStore.edit { p -> p[Chiavi.AVVISO_SCOLLEGATO_DATO] = true }
    }

    /** L'ultimo indirizzo usato, per riproporlo nell'abbinamento. */
    suspend fun ultimoServer(): String? = context.dataStore.data.first()[Chiavi.SERVER]

    override suspend fun cursore(): String? = context.dataStore.data.first()[Chiavi.CURSORE]

    override suspend fun avvisati(): Set<String> = context.dataStore.data.first()[Chiavi.AVVISATI].orEmpty()

    override suspend fun ricorda(cursore: String?, avvisati: Set<String>) {
        context.dataStore.edit { p ->
            if (cursore != null) p[Chiavi.CURSORE] = cursore
            p[Chiavi.AVVISATI] = avvisati
        }
    }

    /** (codice, quando) dell'ultimo tentativo in sottofondo o non riuscito, null = nessuno. */
    suspend fun aggiornamentoRimandato(): Pair<Int, Long>? {
        val p = context.dataStore.data.first()
        val codice = p[Chiavi.AGGIORNAMENTO_FALLITO_CODICE] ?: return null
        return codice to (p[Chiavi.AGGIORNAMENTO_FALLITO_IL] ?: 0L)
    }

    suspend fun registraAggiornamentoRimandato(codice: Int, quando: Long) {
        context.dataStore.edit { p ->
            p[Chiavi.AGGIORNAMENTO_FALLITO_CODICE] = codice
            p[Chiavi.AGGIORNAMENTO_FALLITO_IL] = quando
        }
    }

    val richiestaNotificheFatta: Flow<Boolean> =
        context.dataStore.data.map { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] ?: false }

    suspend fun registraRichiestaNotificheFatta() {
        context.dataStore.edit { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] = true }
    }
}

/** AES-GCM con la chiave nell'Android Keystore. */
private object Cassaforte {
    private const val ALIAS = "sol_token"
    private const val TRASFORMAZIONE = "AES/GCM/NoPadding"

    private fun chiave(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generatore = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generatore.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generatore.generateKey()
    }

    fun cifra(testo: String): String {
        val cifrario = Cipher.getInstance(TRASFORMAZIONE).apply { init(Cipher.ENCRYPT_MODE, chiave()) }
        val dati = cifrario.doFinal(testo.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cifrario.iv + dati, Base64.NO_WRAP)
    }

    /** null se non si decifra (chiave persa dopo un ripristino): si rifà l'abbinamento. */
    fun decifra(grezzo: String): String? = try {
        val tutto = Base64.decode(grezzo, Base64.NO_WRAP)
        val iv = tutto.copyOfRange(0, 12)
        val dati = tutto.copyOfRange(12, tutto.size)
        val cifrario = Cipher.getInstance(TRASFORMAZIONE).apply {
            init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, iv))
        }
        String(cifrario.doFinal(dati), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
