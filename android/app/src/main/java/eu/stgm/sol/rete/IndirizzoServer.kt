package eu.stgm.sol.rete

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * L'indirizzo del server scritto da Manuel, ripulito e controllato prima di
 * salvarlo. Il token viaggia in ogni richiesta: in chiaro (http) lo si lascia
 * andare solo dentro la rete privata di casa (contratto 15.13), mai su internet,
 * e le password non viaggiano mai in chiaro.
 */
object IndirizzoServer {

    sealed interface Risultato {
        data class Valido(val url: String) : Risultato
        data class NonValido(val motivo: String) : Risultato
    }

    fun normalizza(grezzo: String): Risultato {
        val ripulito = grezzo.trim().trimEnd('/')
        if (ripulito.isEmpty()) return Risultato.NonValido("Scrivi l'indirizzo del server di casa.")
        val conSchema = if ("://" in ripulito) {
            ripulito
        } else {
            val host = ripulito.substringBefore('/').substringBefore(':')
            if (diCasa(host)) "http://$ripulito" else "https://$ripulito"
        }
        val url = conSchema.toHttpUrlOrNull()
            ?: return Risultato.NonValido("Questo indirizzo non sembra giusto. Esempio: https://sol.esempio.ts.net")
        if (url.scheme == "http" && !diCasa(url.host)) {
            return Risultato.NonValido(
                "Fuori dalla rete di casa l'indirizzo deve cominciare con https:// (è più sicuro). " +
                    "Usa l'indirizzo di Tailscale, per esempio https://sol.esempio.ts.net",
            )
        }
        // Solo schema, host, porta e percorso: query, frammenti e credenziali
        // incollati per sbaglio renderebbero sbagliate tutte le richieste.
        val base = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .apply { url.pathSegments.filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
            .build()
        return Risultato.Valido(base.toString().trimEnd('/'))
    }

    /**
     * Indirizzi della rete privata di casa (15.13): 192.168.x.x, 10.x.x.x,
     * 172.16-31.x.x. Niente nomi (`.local`, `.lan`): un nome può portare ovunque.
     */
    fun diCasa(host: String): Boolean {
        val parti = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parti.size != 4 || parti.any { it !in 0..255 }) return false
        val (a, b) = parti
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /** Il telefono stesso: niente esce in rete (serve solo alle prove sul PC). */
    fun locale(host: String): Boolean = host == "localhost" || host.startsWith("127.")

    /** Si può parlare in chiaro con questo server? Solo in casa (o dentro il telefono). */
    fun httpConsentito(host: String): Boolean = diCasa(host) || locale(host)

    /** Il collegamento non è protetto (http): la riga discreta "Collegamento di casa non protetto". */
    fun nonProtetto(server: String?): Boolean = server?.startsWith("http://") == true

    /** Le password (Gmail) viaggiano solo su https; in chiaro mai, nemmeno in casa. */
    fun passwordConsentita(server: String?): Boolean {
        val url = server?.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" || locale(url.host)
    }
}
