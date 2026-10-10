package eu.stgm.sol.dati

import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Gli ultimi dati visti, per poterli consultare anche senza rete o col server
 * spento (in cantiere succede). Una voce per schermata/filtro, sovrascritta a
 * ogni lettura riuscita.
 */
interface Cache {
    fun leggi(chiave: String): VoceCache?
    fun scrivi(chiave: String, json: String)
    fun cancella(chiave: String)
    fun svuota()
}

data class VoceCache(val json: String, val salvataIl: Long)

/**
 * Su file nella memoria privata dell'app (niente backup né trasferimento: v.
 * regole_backup.xml). Dentro ci sono pezzi di posta: si tiene poco e per poco,
 * [MASSIMO_GIORNI] giorni e [MASSIMO_BYTE] in tutto, i più vecchi vanno via prima.
 */
class CacheFile(
    private val cartella: File,
    private val orologio: () -> Long = System::currentTimeMillis,
    private val massimoByte: Long = MASSIMO_BYTE,
) : Cache {

    private var scrittureDallaPotatura = 0

    private fun file(chiave: String): File {
        val nome = chiave.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(cartella, "$nome.json")
    }

    override fun leggi(chiave: String): VoceCache? = try {
        val f = file(chiave)
        if (f.exists()) VoceCache(f.readText(), f.lastModified()) else null
    } catch (e: IOException) {
        null
    }

    override fun scrivi(chiave: String, json: String) {
        try {
            cartella.mkdirs()
            val f = file(chiave)
            val temporaneo = File(cartella, f.name + ".tmp")
            temporaneo.writeText(json)
            if (!temporaneo.renameTo(f)) {
                f.delete()
                temporaneo.renameTo(f)
            }
            f.setLastModified(orologio())
        } catch (e: IOException) {
            // Una cache che non si scrive non è un errore da mostrare.
        }
        synchronized(this) {
            if (++scrittureDallaPotatura >= OGNI_QUANTE_SCRITTURE) {
                scrittureDallaPotatura = 0
                pota()
            }
        }
    }

    override fun cancella(chiave: String) {
        file(chiave).delete()
    }

    override fun svuota() {
        cartella.listFiles()?.forEach { it.delete() }
    }

    /** Via i file più vecchi di [MASSIMO_GIORNI] giorni, poi i più vecchi finché si sta sotto il tetto. */
    fun pota() {
        val adesso = orologio()
        val limite = adesso - TimeUnit.DAYS.toMillis(MASSIMO_GIORNI)
        val rimasti = cartella.listFiles()?.filter { it.isFile }.orEmpty().filter { f ->
            if (f.lastModified() < limite) {
                f.delete()
                false
            } else {
                true
            }
        }
        var totale = rimasti.sumOf { it.length() }
        for (f in rimasti.sortedBy { it.lastModified() }) {
            if (totale <= massimoByte) break
            totale -= f.length()
            f.delete()
        }
    }

    companion object {
        const val MASSIMO_GIORNI = 30L
        const val MASSIMO_BYTE = 20L * 1024 * 1024
        private const val OGNI_QUANTE_SCRITTURE = 50
    }
}

class CacheMemoria(private val orologio: () -> Long = System::currentTimeMillis) : Cache {
    private val voci = ConcurrentHashMap<String, VoceCache>()
    override fun leggi(chiave: String): VoceCache? = voci[chiave]
    override fun scrivi(chiave: String, json: String) {
        voci[chiave] = VoceCache(json, orologio())
    }
    override fun cancella(chiave: String) {
        voci.remove(chiave)
    }
    override fun svuota() = voci.clear()
}

/**
 * Il risultato di una lettura per una schermata: il dato (fresco o dalla cache),
 * l'eventuale problema da mostrare e, se il dato è vecchio, di quando è.
 */
data class Lettura<T>(
    val dato: T?,
    val errore: String? = null,
    val codiceErrore: String? = null,
    val daCacheDel: Long? = null,
)

suspend fun <T> leggiConCache(
    cache: Cache,
    chiave: String,
    serializer: KSerializer<T>,
    chiamata: suspend () -> Esito<T>,
): Lettura<T> {
    val esito = chiamata()
    if (esito is Esito.Ok) {
        withContext(Dispatchers.IO) { cache.scrivi(chiave, JsonSol.encodeToString(serializer, esito.dato)) }
        return Lettura(esito.dato)
    }
    // Telefono non più abbinato: la posta salvata non si mostra più (l'app la sta cancellando).
    if (esito is Esito.Errore && esito.codice == Testi.CODICE_NON_AUTORIZZATO) {
        return Lettura(dato = null, errore = Testi.NON_AUTORIZZATO, codiceErrore = Testi.CODICE_NON_AUTORIZZATO)
    }
    val voce = withContext(Dispatchers.IO) { cache.leggi(chiave) }
    val vecchio = voce?.let {
        try {
            JsonSol.decodeFromString(serializer, it.json)
        } catch (e: Exception) {
            null
        }
    }
    return Lettura(
        dato = vecchio,
        errore = esito.messaggio,
        codiceErrore = (esito as? Esito.Errore)?.codice,
        daCacheDel = if (vecchio != null) voce?.salvataIl else null,
    )
}
