package eu.stgm.sol.dati

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

// Dalle scadenze ISO alle frasi da leggere ad alta voce: "domani alle 9",
// "giovedì 6 agosto alle 15 e 30". La conferma parlata ripete SEMPRE la
// data per esteso: è la mitigazione del rischio "alle otto" capito male.
object FormattatoreDate {

    fun leggibile(iso: String?, oggi: LocalDate): String {
        if (iso.isNullOrBlank()) return "senza scadenza"
        val dt = try {
            LocalDateTime.parse(iso)
        } catch (e: DateTimeParseException) {
            return iso
        }
        val giorno = when (dt.toLocalDate()) {
            oggi -> "oggi"
            oggi.plusDays(1) -> "domani"
            oggi.plusDays(2) -> "dopodomani"
            else -> {
                val nomeGiorno = dt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
                val nomeMese = dt.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
                val anno = if (dt.year != oggi.year) " ${dt.year}" else ""
                "$nomeGiorno ${dt.dayOfMonth} $nomeMese$anno"
            }
        }
        val ora = if (dt.minute == 0) {
            "alle ${dt.hour}"
        } else {
            "alle ${dt.hour} e ${String.format(Locale.ITALIAN, "%02d", dt.minute)}"
        }
        return "$giorno $ora"
    }

    fun millis(iso: String): Long? = try {
        LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    fun valida(iso: String): Boolean = try {
        LocalDateTime.parse(iso)
        true
    } catch (e: DateTimeParseException) {
        false
    }
}
