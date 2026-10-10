package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.ui.PulsanteVuoto

/** Il sollecito di una mail inviata: la bozza da sola, con la stessa conferma prima dell'invio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BozzaScreen(vm: BozzaViewModel, bozzaId: Long, giaLetta: Bozza?, onApriMail: (Long) -> Unit, onIndietro: () -> Unit) {
    val stato by vm.bozza.stato.collectAsStateWithLifecycle()
    DisposableEffect(bozzaId) {
        vm.apri(bozzaId, giaLetta)
        onDispose { vm.sospendi() }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (stato.bozza?.tipo == "sollecito") "Sollecito" else "Bozza") },
            navigationIcon = {
                IconButton(onClick = onIndietro) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SezioneBozza(vm.bozza, onRifai = vm::rifai, titolo = "Testo da inviare", puoPreparare = false)
            stato.bozza?.mailId?.let { mailId ->
                PulsanteVuoto("Apri la mail", onClick = { onApriMail(mailId) }, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}
