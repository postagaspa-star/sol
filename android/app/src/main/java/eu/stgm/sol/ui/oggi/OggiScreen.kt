package eu.stgm.sol.ui.oggi

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.VoceOggi
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.BarraAvanzamento
import eu.stgm.sol.ui.CerchioIcona
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.EtichettaMaiuscola
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaAzione
import eu.stgm.sol.ui.PulsanteTondoTesto
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.RigaVoce
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.StilePillola
import eu.stgm.sol.ui.TitoloSezione
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.LocalMovimenti
import eu.stgm.sol.ui.tema.Sol
import eu.stgm.sol.ui.testoConRiferimenti
import java.time.LocalDate
import java.time.LocalTime

/** Dove portano i tocchi della pagina Oggi. */
class AzioniOggi(
    val apriMail: (Long) -> Unit = {},
    val apriBozza: (id: Long, appena: Bozza?) -> Unit = { _, _ -> },
    val apriSmista: () -> Unit = {},
    val apriScadenze: () -> Unit = {},
    val apriAspetti: () -> Unit = {},
    val apriImpostazioni: () -> Unit = {},
)

/**
 * La pagina iniziale (docs/prototipo/Main.dc.html): risponde a "cosa devo fare
 * adesso?". Il saluto, il punto di oggi e le cose da fare, ognuna col suo pulsante.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OggiScreen(vm: OggiViewModel, azioni: AzioniOggi, ora: () -> LocalTime = { LocalTime.now() }) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    LaunchedEffect(Unit) { vm.carica() }
    DisposableEffect(Unit) {
        vm.riprendi()
        onDispose { vm.sospendi() }
    }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    LaunchedEffect(stato.sollecitoDaAprire) {
        val b = stato.sollecitoDaAprire ?: return@LaunchedEffect
        vm.sollecitoAperto()
        azioni.apriBozza(b.id, b)
    }

    val oggi = stato.oggi
    val nome = oggi?.nome.orEmpty()
    val data = oggi?.data?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    val c = Sol.colori
    val t = Sol.testi

    PullToRefreshBox(isRefreshing = false, onRefresh = vm::carica, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = MARGINE, end = MARGINE, bottom = 28.dp)) {
            item(key = "testata") {
                Row(Modifier.statusBarsPadding().padding(top = 14.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icone.Sole, contentDescription = null, tint = c.accento, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sol", style = t.titoloSezione, color = c.testo, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.weight(1f))
                    PulsanteTondoTesto(nome.trim().take(1).uppercase().ifEmpty { "S" }, "Impostazioni", azioni.apriImpostazioni)
                }
            }
            item(key = "saluto") {
                Column(Modifier.padding(top = 18.dp)) {
                    Text(titoloSaluto(ora(), nome), style = t.titoloPagina, color = c.testo, modifier = Modifier.semantics { heading() })
                    if (oggi != null) {
                        Text(rigaData(data, oggi.mailOggi), style = t.medio, color = c.tenue, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item(key = "avviso") { AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(top = 14.dp)) }
            if (oggi != null || stato.caricamento) {
                item(key = "punto") { PuntoDiOggi(stato, azioni.apriMail, Modifier.padding(top = 18.dp)) }
            }
            if (oggi != null) {
                item(key = "dafare") {
                    Column(Modifier.padding(top = 26.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            TitoloSezione("Da fare", Modifier.weight(1f))
                            if (oggi.totaleOggi > 0) {
                                Text("${oggi.fatteOggi} di ${oggi.totaleOggi} fatte", style = t.secondario, fontWeight = FontWeight.SemiBold, color = c.tenue)
                            }
                        }
                        if (oggi.totaleOggi > 0) {
                            BarraAvanzamento(oggi.fatteOggi.toFloat() / oggi.totaleOggi, Modifier.padding(top = 10.dp))
                        }
                    }
                }
                if (stato.tuttoFatto) {
                    item(key = "fatto") { TuttoFatto(Modifier.padding(top = 14.dp)) }
                } else {
                    items(stato.voci, key = { "${it.tipo}-${it.mailId ?: it.inviataId ?: it.scadenzaId ?: it.conteggio}" }) { voce ->
                        SchedaVoce(voce, stato.inCorso, vm, azioni, Modifier.padding(top = 10.dp))
                    }
                }
                item(key = "collegamenti") {
                    Scheda(Modifier.padding(top = 22.dp)) {
                        RigaVoce("Scadenze", azioni.apriScadenze, icona = Icone.Calendario, valore = stato.numeroScadenze?.toString())
                        RigaSottile(64.dp)
                        RigaVoce("Aspetti risposta", azioni.apriAspetti, icona = Icone.Orologio, valore = stato.numeroAspetti?.toString())
                    }
                }
            }
        }
    }
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
}

/** La scheda "IL PUNTO DI OGGI": il riassunto con le frasi toccabili e la scadenza più vicina. */
@Composable
private fun PuntoDiOggi(stato: StatoOggi, apriMail: (Long) -> Unit, modifier: Modifier) {
    val c = Sol.colori
    val oggi = stato.oggi
    val forma = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().ombra(forma, rilievo = true).background(c.superficie, forma).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EtichettaMaiuscola("Il punto di oggi")
        val riassunto = oggi?.riassunto
        val testo = riassunto?.testoVisibile.orEmpty()
        when {
            testo.isNotBlank() -> Text(
                testoConRiferimenti(testo, c.testo, apriMail),
                style = Sol.testi.riassunto,
                color = c.testo,
            )
            oggi == null || riassunto == null || riassunto.inCorso -> RigheInPreparazione()
            else -> Text("Il riassunto di oggi non è ancora pronto.", style = Sol.testi.riassunto, color = c.tenue)
        }
        oggi?.scadenzaProssima?.let { s ->
            Row(
                Modifier.padding(top = 2.dp).background(c.fondo, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icone.Calendario, contentDescription = null, tint = c.testoMedio, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(pillolaScadenza(s), style = Sol.testi.piccolo, fontWeight = FontWeight.SemiBold, color = c.testoMedio, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Il riassunto che si prepara: tre righe grigie che pulsano piano (niente rotelle). */
@Composable
private fun RigheInPreparazione() {
    val muove = LocalMovimenti.current
    val alfa = if (muove) {
        val transizione = rememberInfiniteTransition(label = "pulsazione")
        transizione.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "alfa").value
    } else {
        0.7f
    }
    Column(
        Modifier.alpha(alfa).semantics(mergeDescendants = true) { contentDescription = "Il riassunto di oggi si sta preparando" },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        listOf(1f, 0.92f, 0.6f).forEach { f ->
            Box(Modifier.fillMaxWidth(f).height(14.dp).background(Sol.colori.superficie2, RoundedCornerShape(7.dp)))
        }
    }
}

/** Una voce di "Da fare": cerchio, titolo, riga sotto e il pulsante giusto (§3). */
@Composable
private fun SchedaVoce(voce: VoceOggi, inCorso: Boolean, vm: OggiViewModel, azioni: AzioniOggi, modifier: Modifier) {
    val testo = testoVoce(voce) ?: return
    val c = Sol.colori
    val t = Sol.testi
    val truffa = voce.tipo == VoceOggi.TRUFFA
    val forma = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (truffa) Modifier else Modifier.ombra(forma))
            .background(if (truffa) c.truffaTenue else c.superficie, forma)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (voce.tipo) {
            VoceOggi.TRUFFA -> CerchioIcona(Icone.Truffa, c.truffa, Color.White)
            VoceOggi.SMISTA -> CerchioIcona(Icone.Strati, c.neutro, c.testoMedio)
            VoceOggi.SCADENZA -> CerchioIcona(Icone.Calendario, c.neutro, c.testoMedio)
            else -> CerchioIniziali(voce.iniziali?.takeIf { it.isNotBlank() } ?: "?", ColoreGruppo.daNome(voce.colore), dimensione = 44.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(testo.titolo, style = t.forte, color = if (truffa) c.truffaTesto else c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (testo.sotto.isNotBlank()) {
                Text(
                    testo.sotto,
                    style = t.secondario,
                    color = if (truffa) c.truffaTestoSecondario else c.tenue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        PillolaAzione(testo.pulsante, abilitata = !inCorso, stile = testo.stile, onClick = {
            when (testo.azione) {
                AzioneVoce.APRI_MAIL -> voce.mailId?.let(azioni.apriMail)
                AzioneVoce.SMISTA -> azioni.apriSmista()
                AzioneVoce.APRI_BOZZA -> voce.bozzaId?.let { azioni.apriBozza(it, null) }
                AzioneVoce.PREPARA_SOLLECITO -> voce.inviataId?.let(vm::preparaSollecito)
                AzioneVoce.SCADENZA_FATTA -> voce.scadenzaId?.let(vm::scadenzaFatta)
            }
        })
    }
}

/** Tutto fatto: un cerchio con la spunta e una riga. Nessuna festa. */
@Composable
private fun TuttoFatto(modifier: Modifier) {
    val c = Sol.colori
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(c.accentoTenueForte, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icone.Spunta, contentDescription = null, tint = c.accento, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Niente da fare. Bella giornata.", style = Sol.testi.forte.copy(fontSize = 17.sp), color = c.testo)
    }
}

/** Per la pagina Oggi finta del tutorial: le stesse schede, senza ViewModel. */
@Composable
fun SchedaVoceFinta(titolo: String, sotto: String, iniziali: String, colore: ColoreGruppo, pulsante: String, stile: StilePillola, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sol.colori
    val forma = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().ombra(forma).background(c.superficie, forma).padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CerchioIniziali(iniziali, colore, dimensione = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titolo, style = Sol.testi.forte, color = c.testo)
            Text(sotto, style = Sol.testi.secondario, color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        PillolaAzione(pulsante, onClick, stile = stile)
    }
}
