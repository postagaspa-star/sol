package eu.stgm.sol.ui.indietro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.AspettiTu
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaAzione
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.RigaVuota
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.StilePillola
import eu.stgm.sol.ui.TestaSezioneElenco
import eu.stgm.sol.ui.giorni
import eu.stgm.sol.ui.iniziali
import eu.stgm.sol.ui.posta.RigaMail
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.Sol

/**
 * Aspetti risposta, "niente resta indietro" (docs/aspetto.md §7): le mail che
 * aspettano te e quelle per cui aspetti tu, coi giorni passati e il pulsante giusto.
 */
@Composable
fun IndietroScreen(
    vm: IndietroViewModel,
    onApriMail: (Long) -> Unit,
    onApriBozza: (id: Long, appena: Bozza?) -> Unit,
    onIndietro: () -> Unit = {},
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    LaunchedEffect(Unit) { vm.carica() }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    LaunchedEffect(stato.sollecitoDaAprire) {
        val b = stato.sollecitoDaAprire ?: return@LaunchedEffect
        vm.sollecitoAperto()
        onApriBozza(b.id, b)
    }
    val dati = stato.dati

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = MARGINE, end = MARGINE, bottom = 28.dp)) {
        item(key = "testata") {
            Column(Modifier.statusBarsPadding().padding(top = 10.dp)) {
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
                }
                Text("Aspetti risposta", style = Sol.testi.titoloPagina, color = Sol.colori.testo, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            }
        }
        item(key = "avviso") { AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica, Modifier.padding(top = 14.dp)) }
        if (dati == null) return@LazyColumn
        item(key = "te") {
            Column(Modifier.padding(top = 20.dp)) {
                TestaSezioneElenco("Aspettano te", numero = dati.aspettanoTe.size)
                if (dati.aspettanoTe.isEmpty()) {
                    RigaVuota("Nessuna mail aspetta da troppo tempo.")
                } else {
                    Scheda(Modifier.padding(top = 8.dp)) {
                        dati.aspettanoTe.forEachIndexed { i, voce ->
                            if (i > 0) RigaSottile()
                            RigaMail(
                                voce.mail,
                                onClick = { onApriMail(voce.mail.id) },
                                colore = ColoreGruppo.daNome(stato.gruppi.firstOrNull { it.id == voce.mail.gruppoId }?.colore),
                                nota = "Aspetta da ${giorni(voce.giorni)}",
                            )
                            FilaPulsanti(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 12.dp)) {
                                PillolaAzione("Rivedi", { onApriMail(voce.mail.id) })
                                PulsanteTesto("Non serve", { vm.nonServeRisposta(voce.mail.id) }, abilitato = !stato.inCorso, colore = Sol.colori.tenue)
                            }
                        }
                    }
                }
            }
        }
        item(key = "tu") {
            Column(Modifier.padding(top = 18.dp)) {
                TestaSezioneElenco("Aspetti tu", numero = dati.aspettiTu.size)
                if (dati.aspettiTu.isEmpty()) {
                    RigaVuota("Nessuno ti deve una risposta da troppo tempo.")
                } else {
                    Scheda(Modifier.padding(top = 8.dp)) {
                        dati.aspettiTu.forEachIndexed { i, voce ->
                            if (i > 0) RigaSottile()
                            RigaAttesa(
                                voce,
                                inCorso = stato.inCorso,
                                onSollecito = { vm.preparaSollecito(voce.inviata.id) },
                                onApriSollecito = { id -> onApriBozza(id, null) },
                                onIgnora = { vm.ignora(voce.inviata.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
}

@Composable
private fun RigaAttesa(
    voce: AspettiTu,
    inCorso: Boolean,
    onSollecito: () -> Unit,
    onApriSollecito: (Long) -> Unit,
    onIgnora: () -> Unit,
) {
    val c = Sol.colori
    val a = voce.inviata.a.firstOrNull().orEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        CerchioIniziali(iniziali(null, a), null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(voce.inviata.a.joinToString(", "), style = Sol.testi.forte, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(voce.inviata.oggetto.ifBlank { "(senza oggetto)" }, style = Sol.testi.medio, color = c.testoMedio, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Nessuna risposta da ${giorni(voce.giorni)}", style = Sol.testi.secondario, color = c.tenue)
            FilaPulsanti(Modifier.padding(top = 6.dp)) {
                val sollecito = voce.bozzaSollecito
                if (sollecito != null && sollecito.stato != Bozza.SCARTATA && sollecito.stato != Bozza.INVIATA) {
                    PillolaAzione("Rivedi", { onApriSollecito(sollecito.id) }, stile = StilePillola.PIENA)
                } else {
                    PillolaAzione("Prepara sollecito", onSollecito, abilitata = !inCorso)
                }
                PulsanteTesto("Non serve", onIgnora, abilitato = !inCorso, colore = c.tenue)
            }
        }
    }
}
