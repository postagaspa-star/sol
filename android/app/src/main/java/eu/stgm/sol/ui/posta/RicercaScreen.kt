package eu.stgm.sol.ui.posta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.RigaVuota
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.tema.Sol
import kotlinx.coroutines.delay

/** La ricerca (docs/aspetto.md §7): il campo in alto col cursore già dentro, risultati come righe di mail. */
@Composable
fun RicercaScreen(vm: PostaViewModel, onApriMail: (Long) -> Unit, onIndietro: () -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    var testo by rememberSaveable { mutableStateOf(stato.cerca.orEmpty()) }
    val fuoco = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fuoco.requestFocus() } }
    // Si cerca quando si smette di scrivere per un attimo.
    LaunchedEffect(testo) {
        delay(350)
        vm.cerca(testo)
    }
    val gesti = gestiElenco(vm, stato)
    val c = Sol.colori
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = MARGINE, end = MARGINE, bottom = 24.dp)) {
        item(key = "campo") {
            Row(Modifier.statusBarsPadding().padding(top = 10.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
                Spacer(Modifier.width(8.dp))
                val forma = RoundedCornerShape(16.dp)
                Row(
                    Modifier.weight(1f).height(48.dp).ombra(forma).background(c.superficie, forma).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icone.Cerca, contentDescription = null, tint = c.tenue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (testo.isEmpty()) Text("Cerca nella posta", style = Sol.testi.medio, color = c.moltoTenue)
                        BasicTextField(
                            value = testo,
                            onValueChange = { testo = it },
                            singleLine = true,
                            textStyle = Sol.testi.medio.copy(color = c.testo),
                            cursorBrush = SolidColor(c.accento),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.cerca(testo) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(fuoco).semantics { contentDescription = "Cerca nella posta" },
                        )
                    }
                }
            }
        }
        item(key = "avviso") { AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(top = 14.dp)) }
        if (testo.isNotBlank() && stato.mail.isEmpty() && stato.caricataAlmenoUnaVolta && !stato.caricamento && stato.errore == null && stato.cerca == testo) {
            item(key = "vuoto") { RigaVuota("Nessuna mail trovata.", Modifier.padding(top = 8.dp)) }
        }
        if (stato.mail.isNotEmpty()) {
            item(key = "risultati") {
                Column(Modifier.padding(top = 18.dp)) {
                    eu.stgm.sol.ui.Scheda {
                        stato.mail.forEachIndexed { i, m ->
                            if (i > 0) eu.stgm.sol.ui.RigaSottile()
                            RigaConGesti(
                                m,
                                eu.stgm.sol.ui.tema.ColoreGruppo.daNome(stato.gruppo(m.gruppoId)?.colore),
                                gesti,
                                onClick = { onApriMail(m.id) },
                                onLetta = { vm.cambiaLetta(m) },
                                conPuntoGruppo = true,
                            )
                        }
                    }
                }
            }
        }
        if (stato.altre && stato.mail.isNotEmpty()) {
            item(key = "altre") {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    PulsanteTesto(if (stato.caricamentoAltre) "Carico..." else "Mostra mail più vecchie", vm::caricaAltre, abilitato = !stato.caricamentoAltre)
                }
            }
        }
    }
}
