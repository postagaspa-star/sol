package eu.stgm.sol.esecuzione

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Archivio locale su file JSON, con scrittura atomica (file temporaneo +
// rename). Niente database per scelta: per qualche decina di promemoria un
// file è più che sufficiente, e non porta annotation processor in build.
// Se servirà di più, Room si infila dietro PromemoriaRepo senza toccare
// il resto. Gli id non si riusano mai: un id eliminato resta eliminato.
class PromemoriaRepoLocale(dir: File) : PromemoriaRepo {

    private val file = File(dir, "promemoria.json")
    private val mutex = Mutex()
    private var cache: Archivio? = null

    private class Archivio(
        var prossimoId: Int,
        val elenco: MutableList<Promemoria>,
    )

    override suspend fun crea(titolo: String, scadenzaIso: String?): Promemoria =
        scrivi { archivio ->
            val nuovo = Promemoria(archivio.prossimoId, titolo, scadenzaIso)
            archivio.prossimoId += 1
            archivio.elenco.add(nuovo)
            nuovo
        }

    override suspend fun lista(): List<Promemoria> =
        leggi { archivio -> archivio.elenco.filter { !it.completato }.sortedBy { it.id } }

    override suspend fun perId(id: Int): Promemoria? =
        leggi { archivio -> archivio.elenco.firstOrNull { it.id == id } }

    override suspend fun completa(id: Int): Promemoria? =
        scrivi { archivio -> sostituisci(archivio, id) { it.copy(completato = true) } }

    override suspend fun sposta(id: Int, nuovaScadenzaIso: String): Promemoria? =
        scrivi { archivio -> sostituisci(archivio, id) { it.copy(scadenzaIso = nuovaScadenzaIso) } }

    override suspend fun elimina(id: Int): Promemoria? =
        scrivi { archivio ->
            val trovato = archivio.elenco.firstOrNull { it.id == id }
            if (trovato != null) archivio.elenco.removeAll { it.id == id }
            trovato
        }

    override suspend fun ripristina(promemoria: Promemoria) {
        scrivi { archivio ->
            archivio.elenco.removeAll { it.id == promemoria.id }
            archivio.elenco.add(promemoria)
        }
    }

    private fun sostituisci(
        archivio: Archivio,
        id: Int,
        trasforma: (Promemoria) -> Promemoria,
    ): Promemoria? {
        val indice = archivio.elenco.indexOfFirst { it.id == id }
        if (indice < 0) return null
        val nuovo = trasforma(archivio.elenco[indice])
        archivio.elenco[indice] = nuovo
        return nuovo
    }

    // ---- Persistenza ----

    private suspend fun <T> leggi(blocco: (Archivio) -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { blocco(archivio()) } }

    private suspend fun <T> scrivi(blocco: (Archivio) -> T): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val archivio = archivio()
                val risultato = blocco(archivio)
                salva(archivio)
                risultato
            }
        }

    private fun archivio(): Archivio =
        cache ?: carica().also { cache = it }

    private fun carica(): Archivio {
        if (!file.exists()) return Archivio(1, mutableListOf())
        return try {
            val json = JSONObject(file.readText())
            val elenco = mutableListOf<Promemoria>()
            val righe = json.getJSONArray("elenco")
            for (i in 0 until righe.length()) {
                val r = righe.getJSONObject(i)
                elenco.add(
                    Promemoria(
                        id = r.getInt("id"),
                        titolo = r.getString("titolo"),
                        scadenzaIso = r.optString("scadenza").takeIf { it.isNotBlank() },
                        completato = r.optBoolean("completato", false),
                    )
                )
            }
            Archivio(json.optInt("prossimo_id", (elenco.maxOfOrNull { it.id } ?: 0) + 1), elenco)
        } catch (e: Exception) {
            // File corrotto: si riparte, ma la copia resta lì per il debug.
            file.copyTo(File(file.parentFile, "promemoria.corrotto.json"), overwrite = true)
            Archivio(1, mutableListOf())
        }
    }

    private fun salva(archivio: Archivio) {
        val righe = JSONArray()
        archivio.elenco.forEach { p ->
            righe.put(
                JSONObject()
                    .put("id", p.id)
                    .put("titolo", p.titolo)
                    .put("scadenza", p.scadenzaIso ?: "")
                    .put("completato", p.completato)
            )
        }
        val json = JSONObject()
            .put("prossimo_id", archivio.prossimoId)
            .put("elenco", righe)
        val temporaneo = File(file.parentFile, file.name + ".tmp")
        temporaneo.writeText(json.toString())
        if (!temporaneo.renameTo(file)) {
            // Su alcuni filesystem il rename su file esistente fallisce:
            // si passa dalla cancellazione, comunque dopo la scrittura completa.
            file.delete()
            temporaneo.renameTo(file)
        }
    }
}
