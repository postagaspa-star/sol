package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaAzione
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.tema.Sol

/** Il sollecito di una mail inviata: la risposta da sola, con la stessa conferma prima dell'invio. */
@Composable
fun BozzaScreen(vm: BozzaViewModel, bozzaId: Long, giaLetta: Bozza?, onApriMail: (Long) -> Unit, onIndietro: () -> Unit) {
    val stato by vm.bozza.stato.collectAsStateWithLifecycle()
    var chiediScarta by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(bozzaId) {
        vm.apri(bozzaId, giaLetta)
        onDispose { vm.sospendi() }
    }
    val bozza = stato.bozza
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(start = MARGINE, end = MARGINE, top = 10.dp, bottom = 120.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
            }
            Text(
                if (bozza?.tipo == "sollecito") "Sollecito" else "Risposta",
                style = Sol.testi.titoloPagina,
                color = Sol.colori.testo,
                modifier = Modifier.padding(top = 12.dp).semantics { heading() },
            )
            if (bozza != null && bozza.oggetto.isNotBlank()) {
                Text(bozza.oggetto, style = Sol.testi.medio, color = Sol.colori.tenue, modifier = Modifier.padding(top = 6.dp))
            }
            RispostaDiSol(
                vm.bozza,
                stato,
                giaRisposto = false,
                onTono = { istruzioni -> vm.bozza.rifaiDallaBarra(istruzioni, vm::rifai) },
                modifier = Modifier.padding(top = 18.dp),
                titolo = "Sollecito di Sol",
            )
            FilaPulsanti(Modifier.padding(top = 14.dp)) {
                bozza?.mailId?.let { mailId -> PillolaAzione("Apri la mail", { onApriMail(mailId) }) }
                if (bozza != null && !bozza.chiusa && !bozza.inPreparazione) {
                    PulsanteTesto("Scarta", { chiediScarta = true }, abilitato = !stato.campiBloccati, colore = Sol.colori.truffaTesto)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        BarraRisposta(
            barraPer(null, stato),
            onInvia = vm.bozza::inviaDallaBarra,
            onPrepara = {},
            modifier = Modifier.align(Alignment.BottomCenter),
            testoInvia = if (bozza?.tipo == "sollecito") "Invia sollecito" else "Invia risposta",
        )
    }
    FinestreRisposta(vm.bozza, stato)
    if (chiediScarta) {
        DialogoConferma(
            titolo = "Scartare il sollecito?",
            testo = "Il sollecito non verrà inviato. Potrai sempre chiederne uno nuovo.",
            conferma = "Scarta",
            onConferma = {
                chiediScarta = false
                vm.bozza.scarta()
            },
            onAnnulla = { chiediScarta = false },
            pericolosa = true,
        )
    }
}
