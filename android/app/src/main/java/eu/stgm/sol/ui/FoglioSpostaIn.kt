package eu.stgm.sol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.Sol

/** La descrizione da mostrare sotto il nome: niente se ripete solo il nome. */
fun descrizioneCorta(gruppo: Gruppo): String? =
    gruppo.descrizione?.trim()?.takeIf { it.isNotEmpty() && !it.equals(gruppo.nome, ignoreCase = true) }

/**
 * Il foglio "Sposta in" (docs/aspetto.md §4): i gruppi col loro colore, nome e
 * descrizione. Toccare un gruppo sposta subito. "Ricorda questo mittente" è acceso
 * di base: correggere una volta deve bastare per le mail che verranno.
 */
@Composable
fun FoglioSpostaIn(
    gruppi: List<Gruppo>,
    attuale: Long?,
    onScegli: (gruppoId: Long, impara: Boolean) -> Unit,
    onChiudi: () -> Unit,
) {
    var impara by rememberSaveable { mutableStateOf(true) }
    val c = Sol.colori
    FoglioSol(onChiudi = onChiudi, titolo = "Sposta in") {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.fondo)
                .toggleable(value = impara, role = Role.Switch, onValueChange = { impara = it })
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ricorda questo mittente", style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = c.testo, modifier = Modifier.weight(1f))
            Switch(
                checked = impara,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = c.accento, checkedThumbColor = c.suAccento),
            )
        }
        Spacer(Modifier.size(8.dp))
        gruppi.forEach { g ->
            val colore = ColoreGruppo.daNome(g.colore)
            val qui = g.id == attuale
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !qui, role = Role.Button) { onScegli(g.id, impara) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(colore.fondo, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).background(colore.punto, CircleShape))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.nome, style = Sol.testi.forte, color = c.testo)
                    descrizioneCorta(g)?.let {
                        Text(it, style = Sol.testi.secondario, color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (qui) Text("è qui", style = Sol.testi.piccolo, fontWeight = FontWeight.SemiBold, color = c.tenue, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
