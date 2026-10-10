package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.FasciaCalda
import eu.stgm.sol.ui.FilaPulsanti
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteVuoto

/** Il sollecito di una mail inviata: la bozza da sola, con la stessa conferma prima dell'invio. */
@Composable
fun BozzaScreen(vm: BozzaViewModel, bozzaId: Long, giaLetta: Bozza?, onApriMail: (Long) -> Unit, onIndietro: () -> Unit) {
    val stato by vm.bozza.stato.collectAsStateWithLifecycle()
    var mostraRifai by rememberSaveable { mutableStateOf(false) }
    var chiediScarta by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(bozzaId) {
        vm.apri(bozzaId, giaLetta)
        onDispose { vm.sospendi() }
    }
    Column(Modifier.fillMaxSize()) {
        FasciaCalda(if (stato.bozza?.tipo == "sollecito") "Sollecito" else "Bozza", indietro = onIndietro)
        BarraCaricamento(stato.inCorso)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            RiquadroRisposta(vm.bozza, stato, giaRisposto = false, titolo = "Testo da inviare")
            val bozza = stato.bozza
            FilaPulsanti(Modifier.padding(top = 16.dp)) {
                bozza?.mailId?.let { mailId -> PulsanteVuoto("Apri la mail", onClick = { onApriMail(mailId) }) }
                if (bozza != null && !bozza.chiusa && !bozza.inPreparazione) {
                    PulsanteTesto(
                        "Scarta",
                        { chiediScarta = true },
                        abilitato = !stato.campiBloccati,
                        colore = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        BarraRisposta(
            barraPer(null, stato),
            onRifai = { mostraRifai = true },
            onInvia = vm.bozza::inviaDallaBarra,
            onPrepara = {},
        )
    }
    FinestreRisposta(vm.bozza, stato)
    if (mostraRifai) {
        DialogoRifai(
            onRifai = { istruzioni ->
                mostraRifai = false
                vm.bozza.rifaiDallaBarra(istruzioni, vm::rifai)
            },
            onAnnulla = { mostraRifai = false },
        )
    }
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
