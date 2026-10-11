package eu.stgm.sol.ui.riassunti

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Riassunto
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.EtichettaMaiuscola
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.TestaSezioneElenco
import eu.stgm.sol.ui.TestataPagina
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.quandoCompleto
import eu.stgm.sol.ui.tema.Sol
import eu.stgm.sol.ui.testoConRiferimenti

/**
 * Riassunti (docs/aspetto.md §7): la scheda di oggi, il campo grande "Cosa vuoi
 * sapere?", la risposta in una scheda coi riferimenti toccabili e quelli chiesti di
 * recente.
 */
@Composable
fun RiassuntiScreen(vm: RiassuntiViewModel, onApriMail: (Long) -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.carica() }
    // Il riassunto si ricontrolla solo con la schermata aperta.
    DisposableEffect(Unit) {
        vm.riprendi()
        onDispose { vm.sospendi() }
    }
    val c = Sol.colori
    val t = Sol.testi

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = MARGINE, end = MARGINE, top = 14.dp, bottom = 28.dp),
    ) {
        TestataPagina("Riassunti")
        AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica, Modifier.padding(top = 12.dp))

        val forma = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(top = 18.dp).fillMaxWidth().ombra(forma, rilievo = true).background(c.superficie, forma).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EtichettaMaiuscola("Oggi")
            val oggi = stato.oggi
            val testo = oggi?.testoVisibile.orEmpty()
            if (testo.isBlank()) {
                Text(
                    if (stato.caricamento) "Carico il riassunto di oggi..." else "Il riassunto di oggi non è ancora pronto.",
                    style = t.riassunto,
                    color = c.tenue,
                )
            } else {
                Text(testoConRiferimenti(testo, c.testo, onApriMail), style = t.riassunto, color = c.testo)
            }
            oggi?.aggiornatoIl?.let {
                Text("Aggiornato ${quandoCompleto(it)}", style = t.piccolo, color = c.moltoTenue)
            }
        }

        // Il campo grande: si scrive o si detta col microfono della tastiera.
        val formaCampo = RoundedCornerShape(20.dp)
        Column(Modifier.padding(top = 22.dp).fillMaxWidth().ombra(formaCampo).background(c.superficie, formaCampo).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                if (stato.richiesta.isEmpty()) Text("Cosa vuoi sapere?", style = t.riassunto, color = c.moltoTenue)
                BasicTextField(
                    value = stato.richiesta,
                    onValueChange = vm::cambiaRichiesta,
                    textStyle = t.riassunto.copy(color = c.testo),
                    cursorBrush = SolidColor(c.accento),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.chiedi() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Cosa vuoi sapere?" },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Spacer(Modifier.weight(1f))
                PulsanteGrande(if (stato.inCorso) "Sol sta scrivendo…" else "Chiedi", vm::chiedi, abilitato = !stato.inCorso, altezza = 48.dp)
            }
        }
        stato.erroreAzione?.let {
            Riquadro(
                it,
                grave = true,
                azione = if (stato.sospeso) "Riprova" else "Chiudi",
                onAzione = if (stato.sospeso) vm::riprendi else vm::consumaErroreAzione,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        stato.corrente?.let { corrente ->
            Scheda(Modifier.padding(top = 16.dp), raggio = 24.dp, rilievo = true) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icone.Sole, contentDescription = null, tint = c.accento, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(corrente.richiesta ?: "Riassunto", style = t.secondario, fontWeight = FontWeight.ExtraBold, color = c.accentoTesto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (stato.seguendo) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.accento, trackColor = c.traccia, gapSize = 0.dp)
                    }
                    val testo = corrente.testoVisibile
                    if (testo.isBlank()) {
                        Text(
                            when {
                                stato.seguendo -> "Sol sta cercando le mail giuste..."
                                corrente.inCorso -> "Sol ci sta ancora lavorando."
                                else -> "Nessun testo."
                            },
                            style = t.testo,
                            color = c.tenue,
                        )
                    } else {
                        Text(testoConRiferimenti(testo, c.testo, onApriMail), style = t.testo, color = c.testo)
                    }
                    if (!stato.seguendo) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { PulsanteTesto("Chiudi", vm::chiudiCorrente) }
                    }
                }
            }
        }

        val recenti = stato.recenti.filter { it.id != stato.corrente?.id }
        if (recenti.isNotEmpty()) {
            TestaSezioneElenco("Chiesti di recente", Modifier.padding(top = 22.dp))
            Scheda(Modifier.padding(top = 8.dp)) {
                recenti.forEachIndexed { i, r ->
                    if (i > 0) RigaSottile(16.dp)
                    RigaRecente(r, onClick = { vm.apri(r) })
                }
            }
        }
    }
}

@Composable
private fun RigaRecente(r: Riassunto, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(r.richiesta ?: "Riassunto", style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = Sol.colori.testo, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                quandoCompleto(r.creatoIl).ifBlank { null },
                when (r.stato) {
                    Riassunto.ERRORE -> "non riuscito"
                    Riassunto.IN_CODA, Riassunto.IN_CORSO -> "in preparazione"
                    else -> null
                },
            ).joinToString(" · "),
            style = Sol.testi.piccolo,
            color = Sol.colori.moltoTenue,
        )
    }
}
