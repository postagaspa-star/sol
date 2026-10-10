package eu.stgm.sol.ui.posta

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.AvvisoModalitaProva
import eu.stgm.sol.ui.BarraCaricamento
import eu.stgm.sol.ui.ChipScelta
import eu.stgm.sol.ui.PulsanteImpostazioni
import eu.stgm.sol.ui.PulsanteVuoto
import eu.stgm.sol.ui.RigaNonProtetto
import eu.stgm.sol.ui.Rotella
import eu.stgm.sol.ui.Segno
import eu.stgm.sol.ui.TipoSegno
import eu.stgm.sol.ui.Vuoto
import eu.stgm.sol.ui.quandoBreve

private val NomiFiltri = listOf(
    FiltroRapido.DA_RISPONDERE to "Da rispondere",
    FiltroRapido.URGENTI to "Urgenti",
    FiltroRapido.DA_CONTROLLARE to "Da controllare",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostaScreen(vm: PostaViewModel, onApriMail: (Long) -> Unit, collegamentoNonProtetto: Boolean = false) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.carica() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Posta") },
            actions = {
                IconButton(onClick = vm::carica) { Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna") }
                PulsanteImpostazioni()
            },
        )
        BarraCaricamento(stato.caricamento)
        if (collegamentoNonProtetto) RigaNonProtetto()
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = vm::carica,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (stato.modalitaProva) {
                    item { AvvisoModalitaProva(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
                item {
                    SceltaGruppo(
                        gruppi = stato.gruppi.map { it.id to (it.nome to stato.daLeggere(it)) },
                        scelto = stato.gruppoId,
                        onScegli = vm::scegliGruppo,
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(NomiFiltri) { (filtro, nome) ->
                            // 15.4: il numero dice subito se vale la pena aprire il filtro.
                            val numero = stato.numero(filtro)
                            ChipScelta(
                                if (numero != null && numero > 0) "$nome ($numero)" else nome,
                                scelto = stato.filtro == filtro,
                                onClick = { vm.scegliFiltro(filtro) },
                            )
                        }
                    }
                }
                item {
                    AvvisoLettura(
                        stato.errore,
                        stato.daCacheDel,
                        onRiprova = vm::carica,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (stato.mail.isEmpty()) {
                    item {
                        when {
                            !stato.caricataAlmenoUnaVolta || stato.caricamento -> Rotella()
                            stato.errore == null -> Vuoto(testoVuoto(stato.filtro), Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
                items(stato.mail, key = { it.id }) { mail ->
                    RigaMail(mail, onClick = { onApriMail(mail.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (stato.altre && stato.mail.isNotEmpty()) {
                    item {
                        PulsanteVuoto(
                            if (stato.caricamentoAltre) "Carico..." else "Mostra mail più vecchie",
                            onClick = vm::caricaAltre,
                            abilitato = !stato.caricamentoAltre,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun testoVuoto(filtro: FiltroRapido) = when (filtro) {
    FiltroRapido.TUTTE -> "Nessuna mail qui."
    FiltroRapido.DA_RISPONDERE -> "Nessuna mail aspetta una risposta."
    FiltroRapido.URGENTI -> "Nessuna mail urgente."
    FiltroRapido.DA_CONTROLLARE -> "Niente da controllare: Sol è sicuro di tutti gli smistamenti."
}

/** "Tutte" + i gruppi, col numero delle mail da leggere. */
@Composable
private fun SceltaGruppo(gruppi: List<Pair<Long, Pair<String, Int>>>, scelto: Long?, onScegli: (Long?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ChipScelta("Tutte", scelto = scelto == null, onClick = { onScegli(null) }) }
        items(gruppi) { (id, dati) ->
            val (nome, daLeggere) = dati
            ChipScelta(if (daLeggere > 0) "$nome ($daLeggere)" else nome, scelto = scelto == id, onClick = { onScegli(id) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RigaMail(mail: Mail, onClick: () -> Unit, nota: String? = null) {
    val descrizione = buildString {
        append(if (mail.letta) "" else "Non letta. ")
        append("Da ${mail.mittente.etichetta}. ${mail.oggetto}.")
        if (mail.urgente) append(" Urgente.")
        if (mail.daRispondere && !mail.rispostaData) append(" Da rispondere.")
        if (mail.bozzaPronta) append(" Bozza pronta.")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = descrizione }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Il pallino delle non lette: chi scorre di fretta le trova subito.
        Surface(
            shape = CircleShape,
            color = if (mail.letta) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 9.dp, end = 10.dp).size(10.dp),
        ) {}
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    mail.mittente.etichetta,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (mail.letta) FontWeight.Medium else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    quandoBreve(mail.ricevutaIl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                mail.oggetto.ifBlank { "(senza oggetto)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (mail.letta) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val riga = mail.riassunto?.takeIf { it.isNotBlank() } ?: mail.anteprima
            if (riga.isNotBlank()) {
                Text(
                    riga,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val segni = buildList {
                if (mail.urgente) add(TipoSegno.URGENTE)
                if (mail.daRispondere && !mail.rispostaData) add(TipoSegno.DA_RISPONDERE)
                if (mail.bozzaPronta) add(TipoSegno.BOZZA_PRONTA)
                if (mail.daControllare) add(TipoSegno.DA_CONTROLLARE)
            }
            if (segni.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    segni.forEach { Segno(it) }
                }
            }
            if (nota != null) {
                Text(
                    nota,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
