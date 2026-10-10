package eu.stgm.sol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.ui.tema.ColoreGruppo

/** La descrizione da mostrare sotto il nome: niente se ripete solo il nome. */
fun descrizioneCorta(gruppo: Gruppo): String? =
    gruppo.descrizione?.trim()?.takeIf { it.isNotEmpty() && !it.equals(gruppo.nome, ignoreCase = true) }

/**
 * Il foglio "Sposta in" (docs/aspetto.md §4): i gruppi col loro colore, nome e
 * descrizione corta. Toccare un gruppo sposta subito. "Ricorda questo mittente" è
 * acceso di base: correggere una volta deve bastare per le mail che verranno.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoglioSpostaIn(
    gruppi: List<Gruppo>,
    attuale: Long?,
    onScegli: (gruppoId: Long, impara: Boolean) -> Unit,
    onChiudi: () -> Unit,
) {
    var impara by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(
        onDismissRequest = onChiudi,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "Sposta in",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = impara, role = Role.Switch, onValueChange = { impara = it })
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ricorda questo mittente", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = impara, onCheckedChange = null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            gruppi.forEach { g ->
                val colore = ColoreGruppo.daNome(g.colore)
                val qui = g.id == attuale
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clickable(enabled = !qui, role = Role.Button) { onScegli(g.id, impara) }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).background(colore.punto, CircleShape))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(g.nome, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        descrizioneCorta(g)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (qui) {
                        Text(
                            "è qui",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
