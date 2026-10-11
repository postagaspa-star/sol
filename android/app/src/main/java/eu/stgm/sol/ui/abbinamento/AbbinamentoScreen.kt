package eu.stgm.sol.ui.abbinamento

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.tema.Sol

/**
 * Abbinamento (docs/aspetto.md §7): sole grande, "Collega Sol al server di casa",
 * l'indirizzo, il codice di 8 cifre in 8 caselle grandi, il nome del telefono e
 * "Collega". Niente frasi d'aiuto fisse: gli errori dicono cosa fare.
 */
@Composable
fun AbbinamentoScreen(
    vm: AbbinamentoViewModel,
    onFatto: () -> Unit,
    onAnnulla: (() -> Unit)?,
    /** Il server non riconosceva più il telefono: si dice perché si è di nuovo qui. */
    abbinamentoPerso: Boolean = false,
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(stato.fatto) { if (stato.fatto) onFatto() }
    val c = Sol.colori
    val bordi = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accento,
        unfocusedBorderColor = c.traccia,
        focusedLabelColor = c.accentoTesto,
        cursorColor = c.accento,
        focusedContainerColor = c.superficie,
        unfocusedContainerColor = c.superficie,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = MARGINE, end = MARGINE, top = 10.dp, bottom = 28.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onAnnulla != null) PulsanteTondo(Icone.Indietro, "Indietro", onAnnulla)
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).background(c.accentoTenueForte, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icone.Sole, contentDescription = null, tint = c.accento, modifier = Modifier.size(52.dp))
            }
        }
        Text(
            "Collega Sol al server di casa",
            style = Sol.testi.titoloPagina,
            color = c.testo,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp).semantics { heading() },
        )
        if (abbinamentoPerso) Riquadro(Testi.NON_AUTORIZZATO, Modifier.padding(top = 18.dp), grave = true)

        // Le etichette stanno sopra i campi, come "Codice": niente etichette che fluttuano nel bordo.
        Etichetta("Indirizzo del server", Modifier.padding(top = 26.dp))
        OutlinedTextField(
            value = stato.indirizzo,
            onValueChange = vm::cambiaIndirizzo,
            placeholder = { Text("https://sol.esempio.ts.net") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = bordi,
            textStyle = Sol.testi.testo.copy(color = c.testo),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Indirizzo del server" },
        )

        Etichetta("Codice", Modifier.padding(top = 20.dp))
        CaselleCodice(stato.codice, vm::cambiaCodice, Modifier.padding(top = 8.dp))

        Etichetta("Nome del telefono", Modifier.padding(top = 20.dp))
        OutlinedTextField(
            value = stato.nome,
            onValueChange = vm::cambiaNome,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.abbina() }),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = bordi,
            textStyle = Sol.testi.testo.copy(color = c.testo),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Nome del telefono" },
        )
        stato.errore?.let { Riquadro(it, Modifier.padding(top = 14.dp), grave = true) }
        PulsanteGrande(
            if (stato.inCorso) "Collego…" else "Collega",
            onClick = vm::abbina,
            abilitato = !stato.inCorso,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }
}

@Composable
private fun Etichetta(testo: String, modifier: Modifier = Modifier) {
    Text(testo, style = Sol.testi.secondario, fontWeight = FontWeight.Bold, color = Sol.colori.testoMedio, modifier = modifier.padding(start = 4.dp))
}

/** Le 8 caselle grandi del codice: un campo solo, disegnato come 8 caselle. */
@Composable
private fun CaselleCodice(codice: String, onCambia: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Sol.colori
    var aFuoco by remember { mutableStateOf(false) }
    BasicTextField(
        value = codice,
        onValueChange = onCambia,
        singleLine = true,
        cursorBrush = SolidColor(c.accento.copy(alpha = 0f)),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        textStyle = Sol.testi.testo.copy(color = c.testo.copy(alpha = 0f)),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { aFuoco = it.isFocused }
            .semantics { contentDescription = "Codice di 8 cifre" },
        decorationBox = { campo ->
            Box {
                // Il campo vero resta sotto, trasparente: tiene tastiera, incolla e lettore dello schermo.
                Box(Modifier.matchParentSizeSafe()) { campo() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(8) { i ->
                        val cifra = codice.getOrNull(i)?.toString().orEmpty()
                        val attuale = aFuoco && i == codice.length.coerceAtMost(7)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(c.superficie, RoundedCornerShape(12.dp))
                                .border(if (attuale) 2.dp else 1.5.dp, if (attuale) c.accento else c.traccia, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(cifra, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = c.testo)
                        }
                    }
                }
            }
        },
    )
}

/** Il campo invisibile occupa la stessa altezza delle caselle, così ogni tocco lo prende. */
private fun Modifier.matchParentSizeSafe(): Modifier = this.fillMaxWidth().height(56.dp)
