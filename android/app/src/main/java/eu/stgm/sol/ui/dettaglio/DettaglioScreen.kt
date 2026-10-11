package eu.stgm.sol.ui.dettaglio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Indirizzo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.CampiBozza
import eu.stgm.sol.ui.CerchioIniziali
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FoglioSpostaIn
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaAzione
import eu.stgm.sol.ui.PillolaGruppo
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.RiquadroTruffa
import eu.stgm.sol.ui.StatoBozza
import eu.stgm.sol.ui.StilePillola
import eu.stgm.sol.ui.dimensione
import eu.stgm.sol.ui.iniziali
import eu.stgm.sol.ui.leggiIndirizzi
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.quandoBreve
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.Sol
import kotlinx.coroutines.delay

/** Il testo del contratto (16.3): cosa fare prima di pagare o mandare documenti. */
const val AVVISO_TRUFFA =
    "Attenzione: questa mail potrebbe essere una truffa. Prima di pagare, cambiare coordinate bancarie o " +
        "mandare documenti, telefona al mittente a un numero che conosci già, non a quello scritto nella mail."

/** "rossi@esempio.it · oggi 08:12": il nome sta già sopra, qui conta l'indirizzo (tradisce le truffe). */
internal fun rigaDa(mittente: Indirizzo, ricevutaIl: String? = null): String {
    val indirizzo = mittente.indirizzo.trim().ifEmpty { mittente.nome?.trim().orEmpty() }
    val quando = quandoBreve(ricevutaIl).let { if (it.length == 5 && it[2] == ':') "oggi $it" else it }
    return when {
        quando.isBlank() -> indirizzo
        // Senza nome il titolo è già l'indirizzo: sotto basta l'ora.
        mittente.nome.isNullOrBlank() && mittente.indirizzo.isNotBlank() -> quando
        else -> "$indirizzo · $quando"
    }
}

/**
 * Gli indirizzi da aggiungere in Cc per "Rispondi a tutti": gli altri destinatari
 * della mail, senza la nostra casella e senza chi c'è già.
 */
fun altriDestinatari(mail: Mail, campi: CampiBozza): List<String> {
    val gia = (leggiIndirizzi(campi.a).first + leggiIndirizzi(campi.cc).first).map { it.lowercase() }.toSet()
    val nostra = mail.casella?.lowercase()
    return (mail.destinatari + mail.cc).map { it.indirizzo.trim() }
        .filter { it.isNotEmpty() && it.lowercase() != nostra && it.lowercase() !in gia }
        .distinctBy { it.lowercase() }
}

/**
 * Una mail aperta (docs/prototipo/Mail.dc.html): mittente, oggetto grande, "In breve",
 * il testo, gli allegati e la risposta di Sol con le pillole di tono. In basso
 * "Invia risposta".
 */
@Composable
fun DettaglioScreen(vm: DettaglioViewModel, mailId: Long, onIndietro: () -> Unit) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val risposta by vm.bozza.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var mostraSposta by rememberSaveable { mutableStateOf(false) }
    var chiediNonETruffa by rememberSaveable { mutableStateOf(false) }
    var chiediScarta by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(mailId) {
        vm.apri(mailId)
        onDispose { vm.sospendi() }
    }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    // Dopo l'invio la scheda si chiude con la spunta e la mail torna all'elenco (§5).
    var eraAperta by remember(mailId) { mutableStateOf(false) }
    LaunchedEffect(risposta.bozza?.id, risposta.bozza?.stato) {
        val b = risposta.bozza
        if (b != null && !b.chiusa) eraAperta = true
        if (b?.stato == Bozza.INVIATA && eraAperta) {
            delay(1_200)
            onIndietro()
        }
    }

    val mail = stato.mail
    val colore = ColoreGruppo.daNome(stato.gruppi.firstOrNull { it.id == mail?.gruppoId }?.colore)
    // Senza rete la risposta non si legge: niente barra per una bozza che non si vede.
    val barra = if (mail == null || stato.daCacheDel != null) Barra.Nessuna else barraPer(mail, risposta)
    val c = Sol.colori
    val t = Sol.testi

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
                Spacer(Modifier.weight(1f))
                if (mail != null) {
                    Box {
                        var menuAperto by rememberSaveable { mutableStateOf(false) }
                        PulsanteTondo(Icone.AltreAzioni, "Altre azioni", { menuAperto = true })
                        DropdownMenu(
                            expanded = menuAperto,
                            onDismissRequest = { menuAperto = false },
                            containerColor = c.superficie,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            MenuMail(
                                mail = mail,
                                risposta = risposta,
                                chiudi = { menuAperto = false },
                                onLetta = vm::cambiaLetta,
                                onRispondere = vm::cambiaDaRispondere,
                                onSegnalaTruffa = vm::segnalaTruffa,
                                onRispondi = vm::preparaRisposta,
                                onScarta = { chiediScarta = true },
                                onRispondiATutti = { altri ->
                                    risposta.campiVisibili?.let { campi ->
                                        val cc = (leggiIndirizzi(campi.cc).first + altri).joinToString(", ")
                                        vm.bozza.scrivi(campi.copy(cc = cc))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            AvvisoLettura(stato.errore, stato.daCacheDel, onRiprova = vm::carica, modifier = Modifier.padding(top = 8.dp))
            if (mail == null) return@Column
            val nomeGruppo = mail.gruppo ?: "Senza gruppo"
            val nomeGruppoLungo = nomeGruppo.length > 14
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CerchioIniziali(iniziali(mail.mittente.nome, mail.mittente.indirizzo), colore, dimensione = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(mail.mittente.etichetta, style = t.forteGrande, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rigaDa(mail.mittente, mail.ricevutaIl), style = t.secondario, color = c.tenue)
                }
                if (!nomeGruppoLungo) {
                    Spacer(Modifier.width(8.dp))
                    PillolaGruppo(nomeGruppo, colore, onClick = if (stato.inCorso) null else ({ mostraSposta = true }))
                }
            }
            // Un nome lungo ("Amministrazione e fatture") non schiaccia mittente e indirizzo: va sotto.
            if (nomeGruppoLungo) {
                PillolaGruppo(nomeGruppo, colore, Modifier.padding(start = 60.dp), onClick = if (stato.inCorso) null else ({ mostraSposta = true }), larghezzaMassima = 260.dp)
            }
            // 15.8: per le PEC il mittente sopra è quello della mail interna; la busta l'ha spedita il gestore.
            mail.pec?.let { pec ->
                Text(
                    "Arrivata come PEC da ${pec.mittenteBusta}" + (pec.gestore?.let { " (gestore $it)" } ?: ""),
                    style = t.piccolo,
                    color = c.tenue,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (mail.cc.isNotEmpty()) {
                Text("Cc: ${mail.cc.joinToString(", ") { it.etichetta }}", style = t.piccolo, color = c.tenue, modifier = Modifier.padding(top = 4.dp))
            }
            // Solo per le mail incerte; le altre non hanno nessun riquadro sul gruppo.
            if (mail.daControllare && !mail.sospetta) {
                GruppoGiusto(stato.inCorso, vm::confermaGruppo, { mostraSposta = true }, Modifier.padding(top = 12.dp))
            }
            Text(mail.oggetto.ifBlank { "(senza oggetto)" }, style = t.oggetto, color = c.testo, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
            if (mail.sospetta) {
                RiquadroTruffa(AVVISO_TRUFFA, Modifier.padding(top = 12.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                        PillolaAzione("Non è una truffa", { chiediNonETruffa = true }, stile = StilePillola.TRUFFA, abilitata = !stato.inCorso)
                    }
                }
            }
            mail.riassunto?.takeIf { it.isNotBlank() }?.let { breve ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.testo)) { append("In breve:") }
                        append(" ")
                        append(breve)
                    },
                    style = t.testo.copy(lineHeight = t.testo.fontSize * 1.45f),
                    color = c.testoMedio,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            SelectionContainer(Modifier.padding(top = 12.dp)) {
                Text(mail.corpo?.takeIf { it.isNotBlank() } ?: mail.anteprima, style = t.corpo, color = c.testoCorpo)
            }
            if (mail.allegati.isNotEmpty()) Allegati(mail, Modifier.padding(top = 10.dp))
            if (mail.rispostaData && risposta.bozza?.let { !it.chiusa } != true && risposta.bozza?.stato != Bozza.INVIATA) {
                Text("Hai già risposto.", style = t.secondario, fontWeight = FontWeight.Bold, color = c.accentoTesto, modifier = Modifier.padding(top = 14.dp))
            }
            if (stato.daCacheDel != null) {
                Riquadro("La risposta si vede solo quando il server di casa risponde.", modifier = Modifier.padding(top = 16.dp))
            } else {
                RispostaDiSol(
                    vm.bozza,
                    risposta,
                    giaRisposto = mail.rispostaData,
                    onTono = { istruzioni -> vm.bozza.rifaiDallaBarra(istruzioni, vm::rifaiBozza) },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        BarraRisposta(barra, onInvia = vm.bozza::inviaDallaBarra, onPrepara = vm::preparaRisposta, modifier = Modifier.align(Alignment.BottomCenter))
    }

    FinestreRisposta(vm.bozza, risposta)
    DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
    if (chiediScarta) {
        DialogoConferma(
            titolo = "Scartare la risposta?",
            testo = "La risposta non verrà inviata. Potrai sempre chiederne una nuova.",
            conferma = "Scarta",
            onConferma = {
                chiediScarta = false
                vm.bozza.scarta()
            },
            onAnnulla = { chiediScarta = false },
            pericolosa = true,
        )
    }
    if (chiediNonETruffa) {
        DialogoConferma(
            titolo = "Non è una truffa?",
            testo = "Sol la tratterà come una mail normale e, se va risposta, preparerà la bozza. " +
                "Fallo solo se sei sicuro, per esempio dopo aver telefonato al mittente.",
            conferma = "Sì, è a posto",
            onConferma = {
                chiediNonETruffa = false
                vm.nonETruffa()
            },
            onAnnulla = { chiediNonETruffa = false },
        )
    }
    if (mostraSposta && mail != null) {
        FoglioSpostaIn(
            gruppi = stato.gruppi,
            attuale = mail.gruppoId,
            onScegli = { gruppo, impara ->
                mostraSposta = false
                vm.sposta(gruppo, impara)
            },
            onChiudi = { mostraSposta = false },
        )
    }
}

/** Le azioni rare (§5). Niente "Segna come letta" in vista: aprire la mail la segna già. */
@Composable
private fun MenuMail(
    mail: Mail,
    risposta: StatoBozza,
    chiudi: () -> Unit,
    onLetta: () -> Unit,
    onRispondere: () -> Unit,
    onSegnalaTruffa: () -> Unit,
    onRispondi: () -> Unit,
    onScarta: () -> Unit,
    onRispondiATutti: (List<String>) -> Unit,
) {
    @Composable
    fun Voce(testo: String, azione: () -> Unit) {
        DropdownMenuItem(
            text = { Text(testo, style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = Sol.colori.testo) },
            onClick = {
                chiudi()
                azione()
            },
            modifier = Modifier.heightIn(min = 52.dp),
        )
    }
    Voce(if (mail.letta) "Segna da leggere" else "Segna come letta", onLetta)
    if (!mail.rispostaData) Voce(if (mail.daRispondere) "Non serve rispondere" else "Va risposta", onRispondere)
    // 16.3: meno in vista delle altre azioni, ma sempre a portata.
    if (!mail.sospetta) Voce("Segnala come possibile truffa", onSegnalaTruffa)
    val bozza = risposta.bozza
    val aperta = bozza != null && !bozza.chiusa && !bozza.inPreparazione
    if (bozza != null && aperta && !risposta.campiBloccati) {
        val altri = risposta.campiVisibili?.let { altriDestinatari(mail, it) }.orEmpty()
        if (altri.isNotEmpty()) Voce("Rispondi a tutti") { onRispondiATutti(altri) }
        Voce("Scarta la risposta", onScarta)
    }
    // Una mail senza barra in basso (che non chiede risposta, o sospetta): si risponde da qui.
    if (!aperta && barraPer(mail, risposta) == Barra.Nessuna && !risposta.caricamento && bozza?.stato != Bozza.INVIATA) {
        Voce("Rispondi", onRispondi)
    }
}

/** "È nel gruppo giusto? [Sì] [Cambia]": solo per le mail che Sol non è sicuro di aver messo bene. */
@Composable
private fun GruppoGiusto(occupato: Boolean, onSi: () -> Unit, onCambia: () -> Unit, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().ombra(forma).background(Sol.colori.superficie, forma).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("È nel gruppo giusto?", style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = Sol.colori.testo, modifier = Modifier.weight(1f))
        PillolaAzione("Sì", onSi, abilitata = !occupato)
        Spacer(Modifier.width(6.dp))
        PillolaAzione("Cambia", onCambia, abilitata = !occupato)
    }
}

/** Gli allegati come pillole bianche col bordo sottile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Allegati(mail: Mail, modifier: Modifier = Modifier) {
    val c = Sol.colori
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mail.allegati.forEach {
            val forma = RoundedCornerShape(10.dp)
            Row(
                Modifier.height(34.dp).background(c.superficie, forma).border(1.dp, c.traccia, forma).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icone.Graffetta, contentDescription = null, tint = c.testoCorpo, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("${it.nome} · ${dimensione(it.byte)}", style = Sol.testi.piccolo, fontWeight = FontWeight.SemiBold, color = c.testoMedio)
            }
        }
    }
}
