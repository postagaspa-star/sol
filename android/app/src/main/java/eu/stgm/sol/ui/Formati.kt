package eu.stgm.sol.ui

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val ITALIANO: Locale = Locale.ITALIAN
private val ORA = DateTimeFormatter.ofPattern("HH:mm", ITALIANO)
private val GIORNO_MESE = DateTimeFormatter.ofPattern("d MMM", ITALIANO)
private val DATA_COMPLETA = DateTimeFormatter.ofPattern("d MMM yyyy", ITALIANO)
private val GIORNO_LUNGO = DateTimeFormatter.ofPattern("EEEE d MMMM", ITALIANO)

/** "2026-10-10T09:30:00+02:00" → istante, null se illeggibile. */
fun leggiIstante(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (e: DateTimeParseException) {
        try {
            Instant.parse(iso)
        } catch (e2: DateTimeParseException) {
            null
        }
    }
}

/**
 * L'ora di una mail in elenco, come la direbbe una persona: "09:30" oggi,
 * "ieri", "8 ott" quest'anno, "8 ott 2025" prima.
 */
fun quandoBreve(iso: String?, zona: ZoneId = ZoneId.systemDefault(), oggi: LocalDate = LocalDate.now(zona)): String {
    val istante = leggiIstante(iso) ?: return ""
    val momento = istante.atZone(zona)
    val giorno = momento.toLocalDate()
    return when {
        giorno == oggi -> ORA.format(momento)
        giorno == oggi.minusDays(1) -> "ieri"
        giorno.year == oggi.year -> GIORNO_MESE.format(momento)
        else -> DATA_COMPLETA.format(momento)
    }
}

/** "oggi alle 09:30", "ieri alle 18:02", "8 ott alle 10:00". */
fun quandoCompleto(iso: String?, zona: ZoneId = ZoneId.systemDefault(), oggi: LocalDate = LocalDate.now(zona)): String {
    val istante = leggiIstante(iso) ?: return ""
    return quandoDaIstante(istante, zona, oggi)
}

fun quandoDaIstante(istante: Instant, zona: ZoneId = ZoneId.systemDefault(), oggi: LocalDate = LocalDate.now(zona)): String {
    val momento = istante.atZone(zona)
    val giorno = momento.toLocalDate()
    val ora = ORA.format(momento)
    return when {
        giorno == oggi -> "oggi alle $ora"
        giorno == oggi.minusDays(1) -> "ieri alle $ora"
        giorno.year == oggi.year -> "${GIORNO_MESE.format(momento)} alle $ora"
        else -> "${DATA_COMPLETA.format(momento)} alle $ora"
    }
}

/** La data di una scadenza ("2026-10-16"): "domani", "venerdì 16 ottobre"... */
fun giornoScadenza(quando: String, oggi: LocalDate = LocalDate.now()): String {
    val giorno = try {
        LocalDate.parse(quando)
    } catch (e: DateTimeParseException) {
        return quando
    }
    return when {
        giorno == oggi -> "oggi"
        giorno == oggi.plusDays(1) -> "domani"
        giorno.isBefore(oggi) -> "${GIORNO_LUNGO.format(giorno)} (passata)"
        giorno.year == oggi.year -> GIORNO_LUNGO.format(giorno)
        else -> "${GIORNO_LUNGO.format(giorno)} ${giorno.year}"
    }
}

/** "1 giorno", "3 giorni". */
fun giorni(n: Int): String = if (n == 1) "1 giorno" else "$n giorni"

/** Dimensione di un allegato in parole semplici. */
fun dimensione(byte: Long): String = when {
    byte >= 1_000_000 -> String.format(ITALIANO, "%.1f MB", byte / 1_000_000.0)
    byte >= 1_000 -> "${byte / 1_000} kB"
    else -> "$byte byte"
}

/**
 * Gli indirizzi scritti a mano in un campo ("rossi@esempio.it, bianchi@esempio.it"):
 * separati da virgole, punti e virgola, spazi o a capo. Il primo che non sembra
 * un indirizzo torna come errore, per dirlo a Manuel prima di salvare.
 */
fun leggiIndirizzi(testo: String): Pair<List<String>, String?> {
    val pezzi = testo.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    val sbagliato = pezzi.firstOrNull { !sembraIndirizzo(it) }
    return pezzi to sbagliato
}

private fun sembraIndirizzo(s: String): Boolean {
    val chiocciola = s.indexOf('@')
    return chiocciola > 0 && chiocciola == s.lastIndexOf('@') && s.indexOf('.', chiocciola) > chiocciola + 1 && !s.endsWith(".")
}
