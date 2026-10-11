package eu.stgm.sol.ui.posta

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.CerchioIcona
import eu.stgm.sol.ui.FoglioSol
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.RigaVuota
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.Segmenti
import eu.stgm.sol.ui.TestaSezioneElenco
import eu.stgm.sol.ui.TestataPagina
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.Sol

/**
 * La Posta (docs/prototipo/Posta.dc.html): titolo e ricerca, "Importanti | Tutte",
 * la scheda delle mail da sistemare, le sezioni dei gruppi e le raccolte chiuse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostaScreen(
    vm: PostaViewModel,
    onApriMail: (Long) -> Unit,
    onApriSmista: () -> Unit = {},
    onApriRaccolta: (Long) -> Unit = {},
    onApriRicerca: () -> Unit = {},
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    var spiegaProva by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.carica() }
    val gesti = gestiElenco(vm, stato)

    PullToRefreshBox(isRefreshing = false, onRefresh = vm::carica, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = MARGINE, end = MARGINE, bottom = 24.dp),
        ) {
            item(key = "testata") {
                TestataPagina(
                    "Posta",
                    modifier = Modifier.statusBarsPadding().padding(top = 14.dp),
                    accanto = { if (stato.modalitaProva) PillolaProva { spiegaProva = true } },
                ) {
                    PulsanteTondo(Icone.Cerca, "Cerca nella posta", onApriRicerca)
                }
            }
            item(key = "avviso") {
                AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(top = 14.dp))
            }
            item(key = "segmenti") {
                Segmenti(
                    listOf("Importanti", "Tutte"),
                    scelta = stato.modo.ordinal,
                    onScegli = { vm.scegliModo(ModoPosta.entries[it]) },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (stato.daSistemare > 0) {
                item(key = "smista") { SchedaSmista(stato.daSistemare, onApriSmista, Modifier.padding(top = 14.dp)) }
            }
            if (stato.mail.isEmpty() && stato.raccolte.isEmpty()) {
                item(key = "vuoto") {
                    if (stato.caricataAlmenoUnaVolta && !stato.caricamento && stato.errore == null) {
                        RigaVuota("Nessuna mail qui.", Modifier.padding(top = 8.dp))
                    }
                }
            }
            when (stato.modo) {
                ModoPosta.IMPORTANTI -> importanti(stato, gesti, vm, onApriMail, onApriRaccolta)
                ModoPosta.TUTTE -> if (stato.mail.isNotEmpty()) {
                    item(key = "tutte") {
                        SchedaRighe(stato.mail, stato, gesti, vm, onApriMail, conPuntoGruppo = true, modifier = Modifier.padding(top = 20.dp))
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

    if (spiegaProva) {
        FoglioSol(onChiudi = { spiegaProva = false }, titolo = "Cos'è la modalità prova") {
            Text(
                "Sol smista e prepara le risposte, ma nella casella vera non sposta niente: le mail restano dove sono. " +
                    "Quando ti fidi, spegnila nelle Impostazioni, in Smistamento.",
                style = Sol.testi.testo,
                color = Sol.colori.testoCorpo,
            )
        }
    }
}

private fun LazyListScope.importanti(
    stato: StatoPosta,
    gesti: GestiRighe,
    vm: PostaViewModel,
    onApriMail: (Long) -> Unit,
    onApriRaccolta: (Long) -> Unit,
) {
    stato.sezioni.forEachIndexed { i, sezione ->
        item(key = "sezione-${sezione.gruppo?.id ?: "senza"}") {
            Column(Modifier.padding(top = if (i == 0) 20.dp else 18.dp)) {
                val colore = ColoreGruppo.daNome(sezione.gruppo?.colore)
                TestaSezioneElenco(sezione.gruppo?.nome ?: "Senza gruppo", colore = colore.punto, numero = sezione.mail.size)
                SchedaRighe(sezione.mail, stato, gesti, vm, onApriMail, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    val raccolte = stato.raccolte
    if (raccolte.isNotEmpty()) {
        item(key = "raccolte") {
            Column(Modifier.padding(top = 18.dp)) {
                TestaSezioneElenco("Raccolte")
                Scheda(Modifier.padding(top = 8.dp)) {
                    raccolte.forEachIndexed { i, r ->
                        if (i > 0) RigaSottile()
                        RigaRaccoltaChiusa(r) { onApriRaccolta(r.gruppo.id) }
                    }
                }
            }
        }
    }
}

/** Le righe di un gruppo (o di "Tutte") in una scheda bianca, separate da righe sottili. */
@Composable
private fun SchedaRighe(
    mail: List<Mail>,
    stato: StatoPosta,
    gesti: GestiRighe,
    vm: PostaViewModel,
    onApriMail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    conPuntoGruppo: Boolean = false,
) {
    Scheda(modifier) {
        mail.forEachIndexed { i, m ->
            if (i > 0) RigaSottile()
            RigaConGesti(
                m,
                ColoreGruppo.daNome(stato.gruppo(m.gruppoId)?.colore),
                gesti,
                onClick = { onApriMail(m.id) },
                onLetta = { vm.cambiaLetta(m) },
                conPuntoGruppo = conPuntoGruppo,
            )
        }
    }
}

/** Una raccolta chiusa: icona, nome, "6 mail · ultima…", freccia. */
@Composable
private fun RigaRaccoltaChiusa(r: RigaRaccolta, onClick: () -> Unit) {
    val colore = ColoreGruppo.daNome(r.gruppo.colore)
    val icona = if (r.gruppo.nome.contains("fattur", ignoreCase = true)) Icone.Ricevuta else Icone.Documento
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${r.gruppo.nome}, ${r.sotto}" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CerchioIcona(icona, colore.fondo, colore.testo, dimensione = 40.dp, dimensioneIcona = 20.dp, forma = RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.gruppo.nome, style = Sol.testi.medio, fontWeight = FontWeight.Bold, color = Sol.colori.testo)
            Text(r.sotto, style = Sol.testi.piccolo, color = Sol.colori.moltoTenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icone.Avanti, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
    }
}

/** "3 mail da sistemare · Smista": solo se ci sono mail incerte (§4). */
@Composable
private fun SchedaSmista(numero: Int, onSmista: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sol.colori
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.accentoTenue)
            .clickable(role = Role.Button, onClick = onSmista)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CerchioIcona(Icone.Strati, c.superficie, c.accentoTesto, dimensione = 40.dp, dimensioneIcona = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (numero == 1) "1 mail da sistemare" else "$numero mail da sistemare", style = Sol.testi.medio, fontWeight = FontWeight.Bold, color = c.accentoTestoForte)
            Text("Sol non è sicuro del gruppo", style = Sol.testi.piccolo, color = c.accentoTestoForte.copy(alpha = 0.85f))
        }
        Box(
            Modifier.heightIn(min = 40.dp).background(c.accento, RoundedCornerShape(999.dp)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Smista", style = Sol.testi.secondario, fontWeight = FontWeight.Bold, color = c.suAccento)
        }
    }
}

/** La pillola ambra "Prova" accanto al titolo: toccandola si spiega (§4). */
@Composable
private fun PillolaProva(onClick: () -> Unit) {
    val c = Sol.colori
    Box(
        Modifier
            .padding(start = 10.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClickLabel = "Cos'è la modalità prova", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Prova",
            style = Sol.testi.piccolo,
            fontWeight = FontWeight.Bold,
            color = c.provaTesto,
            modifier = Modifier.background(c.provaFondo, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Il pallino col numero sulla voce Posta della barra. */
@Composable
fun PallinoNumero(numero: Int, modifier: Modifier = Modifier) {
    if (numero <= 0) return
    Box(
        modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .background(Sol.colori.accento, RoundedCornerShape(9.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (numero > 99) "99+" else "$numero", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, color = Sol.colori.suAccento)
    }
}

/** La pagina di una raccolta (Newsletter, Fatture…): righe come nella Posta, "Segna tutte lette". */
@Composable
fun RaccoltaScreen(vm: PostaViewModel, gruppoId: Long, onApriMail: (Long) -> Unit, onIndietro: () -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(gruppoId) { vm.scegliGruppo(gruppoId) }
    val gesti = gestiElenco(vm, stato)
    val gruppo = stato.gruppo(gruppoId)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = MARGINE, end = MARGINE, bottom = 24.dp)) {
        item(key = "testata") {
            Column(Modifier.statusBarsPadding().padding(top = 10.dp)) {
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
                    Spacer(Modifier.weight(1f))
                    if (stato.mail.any { !it.letta }) PulsanteTesto("Segna tutte lette", vm::segnaTutteLette)
                }
                Text(gruppo?.nome ?: "Raccolta", style = Sol.testi.titoloPagina, color = Sol.colori.testo, modifier = Modifier.padding(top = 12.dp))
            }
        }
        item(key = "avviso") { AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(top = 14.dp)) }
        if (stato.mail.isEmpty()) {
            item(key = "vuoto") {
                if (stato.caricataAlmenoUnaVolta && !stato.caricamento && stato.errore == null) RigaVuota("Nessuna mail qui.", Modifier.padding(top = 8.dp))
            }
        } else {
            item(key = "righe") { SchedaRighe(stato.mail, stato, gesti, vm, onApriMail, modifier = Modifier.padding(top = 18.dp)) }
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
