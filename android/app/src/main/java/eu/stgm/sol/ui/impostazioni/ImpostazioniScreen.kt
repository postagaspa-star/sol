package eu.stgm.sol.ui.impostazioni

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.sol.BuildConfig
import eu.stgm.sol.LocalMessaggi
import eu.stgm.sol.aggiornamento.Aggiornatore
import eu.stgm.sol.aggiornamento.testo
import eu.stgm.sol.dati.Casella
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.dati.TemaScelto
import eu.stgm.sol.rete.Testi
import eu.stgm.sol.ui.AvvisoLettura
import eu.stgm.sol.ui.CerchioIcona
import eu.stgm.sol.ui.DialogoConferma
import eu.stgm.sol.ui.DialogoErrore
import eu.stgm.sol.ui.FoglioSol
import eu.stgm.sol.ui.Icone
import eu.stgm.sol.ui.MARGINE
import eu.stgm.sol.ui.PillolaAzione
import eu.stgm.sol.ui.PulsanteGrande
import eu.stgm.sol.ui.PulsanteGrandeBianco
import eu.stgm.sol.ui.PulsanteTesto
import eu.stgm.sol.ui.PulsanteTondo
import eu.stgm.sol.ui.RigaSottile
import eu.stgm.sol.ui.RigaVoce
import eu.stgm.sol.ui.RigaVuota
import eu.stgm.sol.ui.Riquadro
import eu.stgm.sol.ui.Scheda
import eu.stgm.sol.ui.Segmenti
import eu.stgm.sol.ui.TestaSezioneElenco
import eu.stgm.sol.ui.descrizioneCorta
import eu.stgm.sol.ui.ombra
import eu.stgm.sol.ui.quandoCompleto
import eu.stgm.sol.ui.tema.ColoreGruppo
import eu.stgm.sol.ui.tema.Sol
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Una conferma in sospeso: titolo, testo, pulsante, azione. */
private data class Conferma(val titolo: String, val testo: String, val pulsante: String, val pericolosa: Boolean = false, val azione: () -> Unit)

/** Le pagine dentro le impostazioni, per gli elenchi lunghi. */
private const val SOTTO_GRUPPI = "gruppi"
private const val SOTTO_MITTENTI = "mittenti"
private const val SOTTO_DISPOSITIVI = "dispositivi"

/** Il nome del tema come si legge nella riga. */
fun nomeTema(tema: TemaScelto): String = when (tema) {
    TemaScelto.AUTOMATICO -> "Automatico"
    TemaScelto.CHIARO -> "Chiaro"
    TemaScelto.SCURO -> "Scuro"
}

/** "85%": le soglie si leggono in percentuale. */
fun percentuale(valore: Double): String = "${(valore * 100).roundToInt()}%"

/** Il pallino della casella: verde collegata, rosso in errore, grigio in collegamento. */
private fun statoBreve(c: Casella): String = when (c.stato) {
    "collegata" -> "Collegata"
    "in_collegamento" -> "Collegamento in corso"
    "errore" -> "Da ricollegare"
    else -> c.stato
}

private fun statoCompleto(c: Casella): String = when (c.stato) {
    "collegata" -> "Collegata" + (c.ultimoControllo?.let { ", ultimo controllo ${quandoCompleto(it)}" } ?: "")
    "in_collegamento" -> "Collegamento in corso"
    "errore" -> "Problema: ${c.errore ?: "Sol non riesce a leggerla"}"
    else -> c.stato
}

private fun nomeTipo(tipo: String): String = when (tipo) {
    "gmail" -> "Gmail"
    "outlook" -> "Outlook"
    "finta" -> "Prova"
    else -> tipo
}

/**
 * Impostazioni (docs/aspetto.md §7): un elenco a gruppi in schede bianche, ogni voce
 * con icona, nome, valore attuale e freccia. Le spiegazioni stanno nei fogli che si
 * aprono toccando la voce, mai sotto gli interruttori (§9).
 */
@Composable
fun ImpostazioniScreen(
    vm: ImpostazioniViewModel,
    tema: TemaScelto,
    onTema: (TemaScelto) -> Unit,
    onIndietro: () -> Unit,
    onAbbinaDiNuovo: () -> Unit,
    onScollegato: () -> Unit,
    onComeFunziona: () -> Unit,
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val messaggi = LocalMessaggi.current
    var conferma by remember { mutableStateOf<Conferma?>(null) }
    var foglio by rememberSaveable { mutableStateOf<String?>(null) }
    var sotto by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.carica() }
    LaunchedEffect(stato.messaggio) {
        val m = stato.messaggio ?: return@LaunchedEffect
        vm.consumaMessaggio()
        messaggi.showSnackbar(m)
    }
    LaunchedEffect(stato.casellaAggiunta) {
        if (stato.casellaAggiunta) {
            foglio = null
            vm.casellaAggiuntaVista()
        }
    }
    LaunchedEffect(stato.scollegato) { if (stato.scollegato) onScollegato() }
    BackHandler(enabled = sotto != null) { sotto = null }

    val chiudiFoglio = {
        foglio = null
        vm.consumaErroreAzione()
    }
    val imp = stato.impostazioni

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = MARGINE, end = MARGINE, top = 10.dp, bottom = 32.dp),
    ) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsanteTondo(Icone.Indietro, "Indietro", { if (sotto != null) sotto = null else onIndietro() })
        }
        val titolo = when (sotto) {
            SOTTO_GRUPPI -> "Gruppi"
            SOTTO_MITTENTI -> "Mittenti ricordati"
            SOTTO_DISPOSITIVI -> "Dispositivi"
            else -> "Impostazioni"
        }
        Text(titolo, style = Sol.testi.titoloPagina, color = Sol.colori.testo, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        AvvisoLettura(stato.errore, stato.daCacheDel, vm::carica, Modifier.padding(top = 14.dp))

        when (sotto) {
            SOTTO_GRUPPI -> PaginaGruppi(stato, onGruppo = { foglio = "gruppo:$it" }, onAggiungi = { foglio = "nuovo-gruppo" })
            SOTTO_MITTENTI -> PaginaMittenti(stato, vm)
            SOTTO_DISPOSITIVI -> PaginaDispositivi(stato) { d, questo ->
                conferma = Conferma(
                    "Scollegare «${d.nome}»?",
                    if (questo) {
                        "Questo telefono non potrà più usare Sol finché non lo abbini di nuovo con un codice."
                    } else {
                        "Quel dispositivo non potrà più usare Sol finché non viene abbinato di nuovo."
                    },
                    "Scollega",
                    pericolosa = true,
                ) { vm.revocaDispositivo(d.id) }
            }
            else -> {
                Sezione("Tu") {
                    RigaVoce("Nome", { foglio = "nome" }, icona = Icone.Persona, valore = imp?.nome?.ifBlank { null } ?: "Non scritto")
                    RigaSottile(64.dp)
                    RigaVoce("Firma", { foglio = "firma" }, icona = Icone.Matita, valore = imp?.firma?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "Nessuna")
                    RigaSottile(64.dp)
                    RigaVoce("Tono delle risposte", { foglio = "tono" }, icona = Icone.Fumetto, valore = imp?.tono?.ifBlank { null } ?: "Normale")
                }

                Sezione("Caselle di posta") {
                    stato.caselle.forEach { c ->
                        RigaCasella(
                            c,
                            onClick = { foglio = "casella:${c.id}" },
                            onRicollega = ricollega(c, vm) { foglio = it },
                            abilitato = !stato.inCorso,
                        )
                        RigaSottile(64.dp)
                    }
                    RigaVoce("Aggiungi Gmail", { foglio = "gmail" }, icona = Icone.Piu)
                    RigaSottile(64.dp)
                    RigaVoce("Aggiungi Outlook", { foglio = "outlook" }, icona = Icone.Piu)
                    RigaSottile(64.dp)
                    RigaVoce("Casella di prova", {
                        conferma = Conferma(
                            "Aggiungere una casella di prova?",
                            "Contiene solo mail inventate: serve per vedere come lavora Sol senza toccare la posta vera.",
                            "Aggiungi",
                        ) { vm.aggiungiFinta("Prova") }
                    }, icona = Icone.Piu)
                }

                Sezione("Smistamento") {
                    RigaVoce("Gruppi", { sotto = SOTTO_GRUPPI }, icona = Icone.Cartella, valore = "${stato.gruppi.size}")
                    RigaSottile(64.dp)
                    RigaVoce("Mittenti ricordati", { sotto = SOTTO_MITTENTI }, icona = Icone.Busta, valore = "${stato.regole.size}")
                    RigaSottile(64.dp)
                    RigaVoce("Quanto deve essere sicuro Sol", { foglio = "sicurezza" }, icona = Icone.Cursori, valore = imp?.let { percentuale(it.sogliaSicurezza) })
                    RigaSottile(64.dp)
                    RigaInterruttore(
                        "Modalità prova",
                        Icone.Scudo,
                        acceso = imp?.modalitaProva == true,
                        abilitato = imp != null && !stato.inCorso,
                    ) { attiva ->
                        if (attiva) {
                            vm.cambiaModalitaProva(true)
                        } else {
                            conferma = Conferma(
                                "Spegnere la modalità prova?",
                                "Da adesso Sol sposta nelle cartelle (Outlook) e nelle etichette (Gmail) le mail nuove " +
                                    "che smista. Quelle già smistate restano dove sono, a meno che tu non scelga di " +
                                    "spostarle. Sol non cancella mai niente e non invia niente senza il tuo sì.",
                                "Spegni",
                            ) { vm.cambiaModalitaProva(false) }
                        }
                    }
                }

                Sezione("Sicurezza") {
                    RigaVoce("Controllo truffe", { foglio = "truffe" }, icona = Icone.Truffa, valore = imp?.let { percentuale(it.sogliaTruffa) })
                    RigaSottile(64.dp)
                    RigaVoce("Dispositivi collegati", { sotto = SOTTO_DISPOSITIVI }, icona = Icone.Telefono, valore = "${stato.dispositivi.size}")
                    RigaSottile(64.dp)
                    RigaVoce(
                        "Server di casa",
                        { foglio = "server" },
                        icona = Icone.Casa,
                        valore = stato.server?.substringAfter("://")?.trimEnd('/'),
                    )
                    RigaSottile(64.dp)
                    RigaVoce(
                        "Scollega questo telefono",
                        {
                            conferma = Conferma(
                                "Scollegare questo telefono?",
                                "Sol dimentica il codice d'accesso su questo telefono. Per usarlo di nuovo servirà un codice nuovo.",
                                "Scollega",
                                pericolosa = true,
                            ) { vm.scollegaQuesto() }
                        },
                        icona = Icone.Scollega,
                        coloreTitolo = Sol.colori.truffaTesto,
                        coloreIcona = Sol.colori.truffa,
                        finale = {},
                    )
                }

                Sezione("Promemoria") {
                    RigaVoce("Aspettano te dopo", { foglio = "giorni-risposta" }, icona = Icone.Orologio, valore = imp?.let { giorniTesto(it.giorniRisposta) })
                    RigaSottile(64.dp)
                    RigaVoce("Sollecito dopo", { foglio = "giorni-sollecito" }, icona = Icone.Campanella, valore = imp?.let { giorniTesto(it.giorniSollecito) })
                }

                Sezione("App") {
                    RigaVoce("Tema", { foglio = "tema" }, icona = Icone.Luna, valore = nomeTema(tema))
                    RigaSottile(64.dp)
                    RigaVoce("Come funziona Sol", onComeFunziona, icona = Icone.Aiuto)
                    RigaSottile(64.dp)
                    val nuova = stato.versioni?.android?.let { it.codice > BuildConfig.VERSION_CODE } == true
                    RigaVoce(
                        "Versione e aggiornamento",
                        { foglio = "versione" },
                        icona = Icone.Aggiorna,
                        valore = if (nuova) "Nuova versione" else BuildConfig.VERSION_NAME,
                    )
                }
            }
        }
    }

    Fogli(foglio, stato, vm, tema, onTema, onAbbinaDiNuovo, chiudiFoglio, apri = { foglio = it }, chiedi = { conferma = it })

    // Gli errori dei gesti fatti fuori dai fogli con un campo (che li mostrano dentro).
    if (foglio == null || !foglioConErrori(foglio)) DialogoErrore(stato.erroreAzione, vm::consumaErroreAzione)
    // 15.2: spenta la modalità prova, le mail già smistate si spostano solo col sì di Manuel.
    stato.arretratoDaApplicare?.let { quante ->
        DialogoConferma(
            titolo = "Spostare anche le mail di prima?",
            testo = "Ci sono $quante mail già smistate rimaste in Posta in arrivo. Vuoi spostarle anche nella casella? " +
                "Quelle che hai già spostato o archiviato tu restano dove sono.",
            conferma = "Spostale",
            onConferma = vm::applicaArretrato,
            onAnnulla = vm::lasciaArretrato,
            annulla = "Lasciale dove sono",
        )
    }
    conferma?.let { c ->
        DialogoConferma(
            titolo = c.titolo,
            testo = c.testo,
            conferma = c.pulsante,
            onConferma = {
                conferma = null
                c.azione()
            },
            onAnnulla = { conferma = null },
            pericolosa = c.pericolosa,
        )
    }
}

private fun foglioConErrori(foglio: String?): Boolean =
    foglio == "gmail" || foglio == "outlook" || foglio == "ricollega-outlook" || foglio?.startsWith("ricollega:") == true

private fun giorniTesto(n: Int): String = if (n == 1) "1 giorno" else "$n giorni"

/** 15.3: una casella in errore si ricollega senza perdere niente. */
private fun ricollega(c: Casella, vm: ImpostazioniViewModel, apri: (String) -> Unit): (() -> Unit)? = when {
    c.stato != "errore" -> null
    c.tipo == "gmail" -> { { apri("ricollega:${c.id}") } }
    c.tipo == "outlook" -> { {
        apri("ricollega-outlook")
        vm.ricollegaOutlook(c.id)
    } }
    else -> null
}

@Composable
private fun Sezione(titolo: String, contenuto: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        TestaSezioneElenco(titolo)
        Scheda(Modifier.padding(top = 8.dp)) { contenuto() }
    }
}

@Composable
private fun RigaInterruttore(titolo: String, icona: androidx.compose.ui.graphics.vector.ImageVector, acceso: Boolean, abilitato: Boolean, onCambia: (Boolean) -> Unit) {
    val c = Sol.colori
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = acceso, enabled = abilitato, role = Role.Switch, onValueChange = onCambia)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CerchioIcona(icona, c.neutro, c.testoMedio, dimensione = 36.dp, dimensioneIcona = 19.dp, forma = RoundedCornerShape(11.dp))
        Spacer(Modifier.width(12.dp))
        Text(titolo, style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = c.testo, modifier = Modifier.weight(1f))
        Switch(checked = acceso, onCheckedChange = null, enabled = abilitato, colors = coloriInterruttore())
    }
}

@Composable
private fun coloriInterruttore() = SwitchDefaults.colors(
    checkedTrackColor = Sol.colori.accento,
    checkedThumbColor = Sol.colori.suAccento,
    uncheckedTrackColor = Sol.colori.superficie2,
    uncheckedBorderColor = Sol.colori.traccia,
    uncheckedThumbColor = Sol.colori.tenue,
)

@Composable
private fun RigaCasella(c: Casella, onClick: () -> Unit, onRicollega: (() -> Unit)?, abilitato: Boolean) {
    val colori = Sol.colori
    val pallino = when (c.stato) {
        "collegata" -> colori.ok
        "errore" -> colori.truffa
        else -> colori.moltoTenue
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CerchioIcona(Icone.Busta, colori.neutro, colori.testoMedio, dimensione = 36.dp, dimensioneIcona = 19.dp, forma = RoundedCornerShape(11.dp))
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(colori.superficie, CircleShape)
                    .padding(2.dp)
                    .background(pallino, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.nome ?: c.indirizzo ?: nomeTipo(c.tipo), style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = colori.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.indirizzo?.takeIf { it != c.nome }, statoBreve(c)).joinToString(" · "),
                style = Sol.testi.secondario,
                color = if (c.stato == "errore") colori.truffaTesto else colori.tenue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRicollega != null) {
            Spacer(Modifier.width(8.dp))
            PillolaAzione("Ricollega", onRicollega, abilitata = abilitato)
        } else {
            Spacer(Modifier.width(6.dp))
            Icon(Icone.Avanti, contentDescription = null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
        }
    }
}

// --- Le pagine interne ------------------------------------------------------------------

@Composable
private fun PaginaGruppi(stato: StatoImpostazioni, onGruppo: (Long) -> Unit, onAggiungi: () -> Unit) {
    Scheda(Modifier.padding(top = 20.dp)) {
        stato.gruppi.forEachIndexed { i, g ->
            if (i > 0) RigaSottile(68.dp)
            val colore = ColoreGruppo.daNome(g.colore)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClick = { onGruppo(g.id) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(colore.fondo, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).background(colore.punto, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.nome, style = Sol.testi.forte, color = Sol.colori.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(descrizioneCorta(g), if (g.raccolta) "raccolta" else null).joinToString(" · ").ifBlank { "${g.totale} mail" },
                        style = Sol.testi.secondario,
                        color = Sol.colori.tenue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icone.Avanti, contentDescription = null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
            }
        }
        if (stato.gruppi.isNotEmpty()) RigaSottile(16.dp)
        if (stato.puoAggiungereGruppo) {
            RigaVoce("Aggiungi un gruppo", onAggiungi, icona = Icone.Piu, coloreTitolo = Sol.colori.accentoTesto, coloreIcona = Sol.colori.accento)
        } else {
            Text(ImpostazioniViewModel.TROPPI_GRUPPI, style = Sol.testi.secondario, color = Sol.colori.tenue, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun PaginaMittenti(stato: StatoImpostazioni, vm: ImpostazioniViewModel) {
    if (stato.regole.isEmpty()) {
        RigaVuota("Nessun mittente ricordato.", Modifier.padding(top = 8.dp))
        return
    }
    Scheda(Modifier.padding(top = 20.dp)) {
        stato.regole.forEachIndexed { i, r ->
            if (i > 0) RigaSottile(16.dp)
            val gruppo = stato.gruppi.firstOrNull { it.id == r.gruppoId }?.nome ?: "?"
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.valore, style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = Sol.colori.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "in $gruppo · " + if (r.tipo == "dominio") "tutto il dominio" else "questo mittente",
                        style = Sol.testi.secondario,
                        color = Sol.colori.tenue,
                    )
                }
                PulsanteTesto("Togli", { vm.togliRegola(r.id) }, abilitato = !stato.inCorso, colore = Sol.colori.tenue)
            }
        }
    }
}

@Composable
private fun PaginaDispositivi(stato: StatoImpostazioni, onScollega: (eu.stgm.sol.dati.Dispositivo, Boolean) -> Unit) {
    if (stato.dispositivi.isEmpty()) {
        RigaVuota("Nessun dispositivo.", Modifier.padding(top = 8.dp))
        return
    }
    Scheda(Modifier.padding(top = 20.dp)) {
        stato.dispositivi.forEachIndexed { i, d ->
            if (i > 0) RigaSottile(64.dp)
            val questo = d.id == stato.questoDispositivo
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CerchioIcona(Icone.Telefono, Sol.colori.neutro, Sol.colori.testoMedio, dimensione = 36.dp, dimensioneIcona = 19.dp, forma = RoundedCornerShape(11.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.nome + if (questo) " · questo telefono" else "", style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = Sol.colori.testo)
                    Text(
                        listOfNotNull(
                            if (d.tipo == "pc") "Computer" else "Telefono",
                            d.ultimoAccesso?.let { "ultimo uso ${quandoCompleto(it)}" },
                        ).joinToString(" · "),
                        style = Sol.testi.secondario,
                        color = Sol.colori.tenue,
                    )
                }
                PulsanteTesto("Scollega", { onScollega(d, questo) }, abilitato = !stato.inCorso, colore = Sol.colori.truffaTesto)
            }
        }
    }
}

// --- I fogli -------------------------------------------------------------------------------

@Composable
private fun Fogli(
    foglio: String?,
    stato: StatoImpostazioni,
    vm: ImpostazioniViewModel,
    tema: TemaScelto,
    onTema: (TemaScelto) -> Unit,
    onAbbinaDiNuovo: () -> Unit,
    chiudi: () -> Unit,
    apri: (String) -> Unit,
    chiedi: (Conferma) -> Unit,
) {
    val f = foglio ?: return
    val imp = stato.impostazioni
    fun idDa(prefisso: String) = f.removePrefix(prefisso).toLongOrNull()
    when {
        f == "nome" && imp != null -> FoglioTesto("Nome", "Come ti saluta Sol", imp.nome, massimo = 40) {
            chiudi()
            it?.let(vm::cambiaNome)
        }
        f == "firma" && imp != null -> FoglioTesto("Firma", "Firma delle risposte", imp.firma, righe = 4) {
            chiudi()
            it?.let(vm::cambiaFirma)
        }
        f == "tono" && imp != null -> FoglioTesto("Tono delle risposte", "Per esempio: cortese e diretto", imp.tono) {
            chiudi()
            it?.let(vm::cambiaTono)
        }
        f == "sicurezza" && imp != null -> FoglioSoglia(
            "Quanto deve essere sicuro Sol",
            "Se Sol è meno sicuro di così, la mail va in Smista e decidi tu.",
            imp.sogliaSicurezza,
            0.5f..0.99f,
        ) {
            chiudi()
            it?.let(vm::cambiaSoglia)
        }
        f == "truffe" && imp != null -> FoglioSoglia(
            "Controllo truffe",
            "Più bassa: più mail segnalate come possibile truffa, anche qualcuna normale. Più alta: meno segnalazioni, ma qualche truffa può sfuggire.",
            imp.sogliaTruffa,
            ImpostazioniSol.SOGLIA_TRUFFA_MINIMA.toFloat()..ImpostazioniSol.SOGLIA_TRUFFA_MASSIMA.toFloat(),
        ) {
            chiudi()
            it?.let(vm::cambiaSogliaTruffa)
        }
        f == "giorni-risposta" && imp != null -> FoglioGiorni(
            "Aspettano te dopo",
            "Dopo quanti giorni una mail senza risposta finisce in «Aspettano te».",
            imp.giorniRisposta,
            1..30,
        ) {
            chiudi()
            it?.let(vm::cambiaGiorniRisposta)
        }
        f == "giorni-sollecito" && imp != null -> FoglioGiorni(
            "Sollecito dopo",
            "Dopo quanti giorni senza risposta Sol propone un sollecito.",
            imp.giorniSollecito,
            1..60,
        ) {
            chiudi()
            it?.let(vm::cambiaGiorniSollecito)
        }
        f == "tema" -> FoglioSol(onChiudi = chiudi, titolo = "Tema") {
            val opzioni = TemaScelto.entries
            Segmenti(opzioni.map(::nomeTema), opzioni.indexOf(tema), { onTema(opzioni[it]) })
            PulsanteGrande("Fatto", chiudi, Modifier.fillMaxWidth().padding(top = 20.dp))
        }
        f == "versione" -> FoglioVersione(stato, vm::mostraMessaggio, chiudi)
        f == "server" -> FoglioSol(onChiudi = chiudi, titolo = "Server di casa") {
            SelectionContainer {
                Text(stato.server ?: "Nessun indirizzo", style = Sol.testi.forte, color = Sol.colori.testo)
            }
            stato.versioni?.server?.let {
                Text("Versione $it", style = Sol.testi.secondario, color = Sol.colori.tenue, modifier = Modifier.padding(top = 4.dp))
            }
            if (stato.collegamentoNonProtetto) {
                Riquadro(
                    "Collegamento di casa non protetto: va bene per l'installazione. Quando Tailscale funziona, " +
                        "rifai l'abbinamento con l'indirizzo https.",
                    Modifier.padding(top = 14.dp),
                )
            }
            PulsanteGrandeBianco("Abbina di nuovo", {
                chiudi()
                onAbbinaDiNuovo()
            }, Modifier.fillMaxWidth().padding(top = 18.dp))
        }
        f.startsWith("casella:") -> {
            val casella = stato.caselle.firstOrNull { it.id == idDa("casella:") }
            if (casella == null) {
                LaunchedEffect(f) { chiudi() }
            } else {
                FoglioCasella(
                    casella,
                    abilitato = !stato.inCorso,
                    onRicollega = ricollega(casella, vm, apri),
                    onControlla = {
                        chiudi()
                        vm.controllaCasella(casella.id)
                    },
                    onTogli = {
                        chiudi()
                        chiedi(
                            Conferma(
                                "Scollegare ${casella.nome ?: casella.indirizzo ?: "la casella"}?",
                                "Sol smette di leggerla e dimentica le sue mail. Nella casella vera non cambia niente: " +
                                    "nessuna mail viene cancellata.",
                                "Scollega",
                                pericolosa = true,
                            ) { vm.togliCasella(casella.id) },
                        )
                    },
                    onChiudi = chiudi,
                )
            }
        }
        f == "gmail" -> FoglioGmail(stato.erroreAzione, stato.inCorso, stato.passwordConsentita, vm::aggiungiGmail, chiudi)
        f.startsWith("ricollega:") -> {
            val casella = stato.caselle.firstOrNull { it.id == idDa("ricollega:") }
            if (casella != null) {
                FoglioRicollegaGmail(casella, stato.erroreAzione, stato.inCorso, stato.passwordConsentita, { vm.ricollegaGmail(casella.id, it) }, chiudi)
            }
        }
        f == "outlook" || f == "ricollega-outlook" -> FoglioOutlook(
            stato.outlook,
            stato.erroreAzione,
            stato.inCorso,
            ricollegamento = f == "ricollega-outlook",
            onInizia = vm::aggiungiOutlook,
        ) {
            chiudi()
            vm.chiudiOutlook()
        }
        f == "nuovo-gruppo" -> FoglioNuovoGruppo(
            onAggiungi = { nome, descrizione ->
                chiudi()
                vm.aggiungiGruppo(nome, descrizione)
            },
            onAnnulla = chiudi,
        )
        f.startsWith("gruppo:") -> {
            val gruppo = stato.gruppi.firstOrNull { it.id == idDa("gruppo:") }
            if (gruppo == null) {
                LaunchedEffect(f) { chiudi() }
            } else {
                FoglioGruppo(
                    gruppo,
                    posizione = stato.gruppi.indexOf(gruppo),
                    quanti = stato.gruppi.size,
                    abilitato = !stato.inCorso,
                    vm = vm,
                    apri = apri,
                    onTogli = {
                        chiudi()
                        chiedi(Conferma("Togliere il gruppo «${gruppo.nome}»?", "Le sue mail passano in Altro.", "Togli", pericolosa = true) { vm.togliGruppo(gruppo.id) })
                    },
                    onChiudi = chiudi,
                )
            }
        }
        f.startsWith("rinomina:") -> {
            val gruppo = stato.gruppi.firstOrNull { it.id == idDa("rinomina:") }
            if (gruppo != null) {
                FoglioTesto("Rinomina gruppo", "Nome", gruppo.nome) { nome ->
                    apri("gruppo:${gruppo.id}")
                    nome?.let { vm.rinominaGruppo(gruppo.id, it) }
                }
            }
        }
        f.startsWith("descrizione:") -> {
            val gruppo = stato.gruppi.firstOrNull { it.id == idDa("descrizione:") }
            if (gruppo != null) {
                FoglioTesto("Che mail vanno in «${gruppo.nome}»?", "Una frase", gruppo.descrizione.orEmpty(), righe = 3) { d ->
                    apri("gruppo:${gruppo.id}")
                    d?.let { vm.cambiaDescrizioneGruppo(gruppo.id, it) }
                }
            }
        }
    }
}

@Composable
private fun bordiCampo() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Sol.colori.accento,
    unfocusedBorderColor = Sol.colori.traccia,
    focusedLabelColor = Sol.colori.accentoTesto,
    cursorColor = Sol.colori.accento,
)

@Composable
private fun Campo(
    valore: String,
    onCambia: (String) -> Unit,
    etichetta: String,
    modifier: Modifier = Modifier,
    righe: Int = 1,
    tastiera: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = valore,
        onValueChange = onCambia,
        label = { Text(etichetta) },
        singleLine = righe == 1,
        minLines = righe,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = tastiera,
            capitalization = if (tastiera == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = bordiCampo(),
        textStyle = Sol.testi.testo.copy(color = Sol.colori.testo),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Un testo da cambiare: null = annullato. */
@Composable
private fun FoglioTesto(titolo: String, etichetta: String, iniziale: String, righe: Int = 1, massimo: Int? = null, onFine: (String?) -> Unit) {
    var testo by rememberSaveable { mutableStateOf(iniziale) }
    FoglioSol(onChiudi = { onFine(null) }, titolo = titolo) {
        Campo(testo, { testo = if (massimo != null) it.take(massimo) else it }, etichetta, righe = righe)
        PulsanteGrande("Salva", { onFine(testo) }, Modifier.fillMaxWidth().padding(top = 18.dp), abilitato = testo != iniziale)
    }
}

@Composable
private fun FoglioSoglia(titolo: String, spiegazione: String, iniziale: Double, intervallo: ClosedFloatingPointRange<Float>, onFine: (Double?) -> Unit) {
    var valore by remember { mutableFloatStateOf(iniziale.toFloat()) }
    FoglioSol(onChiudi = { onFine(null) }, titolo = titolo) {
        Text(spiegazione, style = Sol.testi.testo, color = Sol.colori.testoCorpo)
        Text(percentuale(valore.toDouble()), style = Sol.testi.titoloPagina, color = Sol.colori.testo, modifier = Modifier.padding(top = 18.dp))
        CursoreSol(valore, { valore = it }, intervallo, titolo)
        PulsanteGrande("Salva", { onFine(valore.toDouble()) }, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** Il cursore nello stile di Sol: binario sottile come la barra di avanzamento, pomello tondo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CursoreSol(valore: Float, onCambia: (Float) -> Unit, intervallo: ClosedFloatingPointRange<Float>, descrizione: String) {
    val c = Sol.colori
    val colori = SliderDefaults.colors(thumbColor = c.accento, activeTrackColor = c.accento, inactiveTrackColor = c.traccia)
    Slider(
        value = valore,
        onValueChange = onCambia,
        valueRange = intervallo,
        colors = colori,
        modifier = Modifier.semantics { contentDescription = descrizione },
        thumb = {
            Box(Modifier.size(26.dp).ombra(CircleShape).background(c.superficie, CircleShape).border(3.dp, c.accento, CircleShape))
        },
        track = { stato ->
            SliderDefaults.Track(
                sliderState = stato,
                modifier = Modifier.height(6.dp),
                colors = colori,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

@Composable
private fun FoglioGiorni(titolo: String, spiegazione: String, iniziale: Int, intervallo: IntRange, onFine: (Int?) -> Unit) {
    var giorni by remember { mutableIntStateOf(iniziale) }
    FoglioSol(onChiudi = { onFine(null) }, titolo = titolo) {
        Text(spiegazione, style = Sol.testi.testo, color = Sol.colori.testoCorpo)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            PulsanteTondoTestoGrande("−", "Un giorno in meno", giorni > intervallo.first) { giorni-- }
            Text(giorniTesto(giorni), style = Sol.testi.titoloSezione, color = Sol.colori.testo, modifier = Modifier.padding(horizontal = 24.dp))
            PulsanteTondoTestoGrande("+", "Un giorno in più", giorni < intervallo.last) { giorni++ }
        }
        PulsanteGrande("Salva", { onFine(giorni) }, Modifier.fillMaxWidth().padding(top = 22.dp), abilitato = giorni != iniziale)
    }
}

@Composable
private fun PulsanteTondoTestoGrande(testo: String, descrizione: String, abilitato: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Sol.colori.superficie2)
            .clickable(enabled = abilitato, role = Role.Button, onClickLabel = descrizione, onClick = onClick)
            .semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (abilitato) Sol.colori.testo else Sol.colori.moltoTenue)
    }
}

@Composable
private fun FoglioVersione(stato: StatoImpostazioni, onEsito: (String) -> Unit, onChiudi: () -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var controllo by remember { mutableStateOf(false) }
    val nuova = stato.versioni?.android?.let { it.codice > BuildConfig.VERSION_CODE } == true
    FoglioSol(onChiudi = onChiudi, titolo = "Versione") {
        Text("Sol per Android ${BuildConfig.VERSION_NAME}", style = Sol.testi.forte, color = Sol.colori.testo)
        stato.versioni?.server?.let { Text("Server di casa: versione $it", style = Sol.testi.secondario, color = Sol.colori.tenue, modifier = Modifier.padding(top = 4.dp)) }
        if (nuova) Text("C'è una versione nuova dell'app.", style = Sol.testi.medio, fontWeight = FontWeight.Bold, color = Sol.colori.accentoTesto, modifier = Modifier.padding(top = 12.dp))
        PulsanteGrande(
            if (controllo) "Controllo…" else "Controlla aggiornamenti",
            onClick = {
                controllo = true
                ambito.launch {
                    val esito = Aggiornatore(context).controlla(forza = true)
                    controllo = false
                    onEsito(esito.testo())
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            abilitato = !controllo,
        )
        // La licenza del carattere va distribuita con l'app (SIL OFL 1.1): il testo sta negli assets.
        var licenza by remember { mutableStateOf<String?>(null) }
        Text("Carattere Figtree, licenza SIL OFL 1.1", style = Sol.testi.secondario, color = Sol.colori.tenue, modifier = Modifier.padding(top = 18.dp))
        if (licenza == null) {
            PulsanteTesto("Leggi la licenza", {
                licenza = runCatching { context.assets.open(LICENZA_FIGTREE).bufferedReader().use { it.readText() } }.getOrDefault("")
            })
        } else {
            SelectionContainer {
                Text(licenza.orEmpty(), style = Sol.testi.piccolo, color = Sol.colori.testoCorpo, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Il testo della licenza del carattere, incluso nell'app. */
const val LICENZA_FIGTREE = "licenze/figtree-OFL.txt"

@Composable
private fun FoglioCasella(
    c: Casella,
    abilitato: Boolean,
    onRicollega: (() -> Unit)?,
    onControlla: () -> Unit,
    onTogli: () -> Unit,
    onChiudi: () -> Unit,
) {
    FoglioSol(onChiudi = onChiudi, titolo = c.nome ?: c.indirizzo ?: nomeTipo(c.tipo)) {
        Text(listOfNotNull(c.indirizzo, nomeTipo(c.tipo)).distinct().joinToString(" · "), style = Sol.testi.medio, color = Sol.colori.testoMedio)
        Text(
            statoCompleto(c),
            style = Sol.testi.secondario,
            color = if (c.stato == "errore") Sol.colori.truffaTesto else Sol.colori.tenue,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (onRicollega != null) PulsanteGrande("Ricollega", onRicollega, Modifier.fillMaxWidth().padding(top = 18.dp), abilitato = abilitato)
        PulsanteGrandeBianco("Controlla adesso", onControlla, Modifier.fillMaxWidth().padding(top = if (onRicollega != null) 10.dp else 18.dp), abilitato = abilitato)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            PulsanteTesto("Scollega la casella", onTogli, abilitato = abilitato, colore = Sol.colori.truffaTesto)
        }
    }
}

@Composable
private fun FoglioGmail(
    errore: String?,
    inCorso: Boolean,
    passwordConsentita: Boolean,
    onAggiungi: (String, String, String) -> Unit,
    onChiudi: () -> Unit,
) {
    var indirizzo by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    FoglioSol(onChiudi = onChiudi, titolo = "Aggiungi Gmail") {
        Text(
            "Serve la «password per le app»: 16 lettere che Google crea apposta per Sol. Si fa da Account Google → " +
                "Sicurezza → Password per le app (con la verifica in due passaggi attiva).",
            style = Sol.testi.medio,
            color = Sol.colori.testoCorpo,
        )
        Campo(indirizzo, { indirizzo = it }, "Indirizzo Gmail", Modifier.padding(top = 14.dp), tastiera = KeyboardType.Email)
        Campo(nome, { nome = it }, "Nome (facoltativo)", Modifier.padding(top = 10.dp))
        // 15.13: la password non viaggia mai in chiaro, nemmeno in casa.
        if (passwordConsentita) {
            Campo(password, { password = it }, "Password per le app", Modifier.padding(top = 10.dp), tastiera = KeyboardType.Password, password = true)
        } else {
            Riquadro(Testi.PASSWORD_SU_HTTP, Modifier.padding(top = 10.dp), grave = true)
        }
        if (errore != null && errore != Testi.PASSWORD_SU_HTTP) Riquadro(errore, Modifier.padding(top = 10.dp), grave = true)
        PulsanteGrande(
            if (inCorso) "Sol sta provando la casella…" else "Collega",
            { onAggiungi(indirizzo, nome, password) },
            Modifier.fillMaxWidth().padding(top = 18.dp),
            abilitato = !inCorso && passwordConsentita,
        )
    }
}

/** 15.3: la password per le app nuova di una Gmail in errore; Sol riprende da dove era. */
@Composable
private fun FoglioRicollegaGmail(
    casella: Casella,
    errore: String?,
    inCorso: Boolean,
    passwordConsentita: Boolean,
    onRicollega: (String) -> Unit,
    onChiudi: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    FoglioSol(onChiudi = onChiudi, titolo = "Ricollega ${casella.indirizzo ?: casella.nome ?: "Gmail"}") {
        Text(
            "Di solito succede quando la password per le app è stata revocata. Creane una nuova e scrivila qui: " +
                "mail, risposte e mittenti ricordati restano.",
            style = Sol.testi.medio,
            color = Sol.colori.testoCorpo,
        )
        if (passwordConsentita) {
            Campo(password, { password = it }, "Password per le app nuova", Modifier.padding(top = 14.dp), tastiera = KeyboardType.Password, password = true)
        } else {
            Riquadro(Testi.PASSWORD_SU_HTTP, Modifier.padding(top = 14.dp), grave = true)
        }
        if (errore != null && errore != Testi.PASSWORD_SU_HTTP) Riquadro(errore, Modifier.padding(top = 10.dp), grave = true)
        PulsanteGrande(
            if (inCorso) "Sol sta provando la casella…" else "Ricollega",
            { onRicollega(password) },
            Modifier.fillMaxWidth().padding(top = 18.dp),
            abilitato = !inCorso && passwordConsentita,
        )
    }
}

@Composable
private fun FoglioOutlook(
    collegamento: CollegamentoInCorso?,
    errore: String?,
    inCorso: Boolean,
    ricollegamento: Boolean,
    onInizia: (String) -> Unit,
    onChiudi: () -> Unit,
) {
    val context = LocalContext.current
    var nome by rememberSaveable { mutableStateOf("Studio") }
    val c = Sol.colori
    FoglioSol(onChiudi = onChiudi, titolo = if (ricollegamento) "Ricollega Outlook" else "Aggiungi Outlook") {
        when {
            collegamento == null && ricollegamento -> Text(
                if (inCorso) "Chiedo il codice a Microsoft…" else "Non sono riuscito ad avere il codice.",
                style = Sol.testi.testo,
                color = c.testoCorpo,
            )
            collegamento == null -> {
                Text(
                    "Sol ti darà un codice da scrivere sul sito di Microsoft. Poi accedi con l'account della casella: " +
                        "Sol non vede mai la tua password.",
                    style = Sol.testi.medio,
                    color = c.testoCorpo,
                )
                Campo(nome, { nome = it }, "Nome della casella", Modifier.padding(top = 14.dp))
                PulsanteGrande("Continua", { onInizia(nome) }, Modifier.fillMaxWidth().padding(top = 18.dp), abilitato = !inCorso)
            }
            !collegamento.finito -> {
                Text("1. Apri questo indirizzo", style = Sol.testi.forte, color = c.testo)
                SelectionContainer { Text(collegamento.url, style = Sol.testi.medio, color = c.accentoTesto, modifier = Modifier.padding(top = 4.dp)) }
                PulsanteGrandeBianco("Apri il sito", { apriIndirizzo(context, collegamento.url) }, Modifier.fillMaxWidth().padding(top = 10.dp), altezza = 48.dp)
                Text("2. Scrivi questo codice", style = Sol.testi.forte, color = c.testo, modifier = Modifier.padding(top = 18.dp))
                SelectionContainer {
                    Text(collegamento.codice, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = c.testo, modifier = Modifier.padding(top = 4.dp))
                }
                PulsanteTesto("Copia il codice", { copia(context, collegamento.codice) })
                Text("3. Accedi con l'account della casella", style = Sol.testi.forte, color = c.testo, modifier = Modifier.padding(top = 12.dp))
                Text("Sol aspetta che tu finisca…", style = Sol.testi.secondario, color = c.tenue, modifier = Modifier.padding(top = 12.dp))
                PulsanteGrandeBianco("Annulla", onChiudi, Modifier.fillMaxWidth().padding(top = 16.dp), altezza = 48.dp)
            }
            collegamento.stato == "collegata" -> {
                Text("Fatto: la casella è collegata.", style = Sol.testi.forte, color = c.testo)
                PulsanteGrande("Chiudi", onChiudi, Modifier.fillMaxWidth().padding(top = 18.dp))
            }
            else -> {
                Riquadro("Il collegamento non è riuscito. ${collegamento.errore ?: ""}".trim(), grave = true)
                PulsanteGrandeBianco("Chiudi", onChiudi, Modifier.fillMaxWidth().padding(top = 18.dp))
            }
        }
        if (errore != null) Riquadro(errore, Modifier.padding(top = 10.dp), grave = true)
    }
}

/** 15.14: il nome e la frase che dice a Sol che mail ci vanno. */
@Composable
private fun FoglioNuovoGruppo(onAggiungi: (String, String) -> Unit, onAnnulla: () -> Unit) {
    var nome by rememberSaveable { mutableStateOf("") }
    var descrizione by rememberSaveable { mutableStateOf("") }
    FoglioSol(onChiudi = onAnnulla, titolo = "Nuovo gruppo") {
        Campo(nome, { nome = it }, "Nome")
        Campo(descrizione, { descrizione = it }, "Che mail ci vanno? (una frase)", Modifier.padding(top = 10.dp), righe = 2)
        Text(
            "Per esempio: mail dei cantieri aperti, sopralluoghi e verbali. Più è chiara, meglio Sol smista.",
            style = Sol.testi.secondario,
            color = Sol.colori.tenue,
            modifier = Modifier.padding(top = 8.dp),
        )
        PulsanteGrande("Aggiungi", { onAggiungi(nome, descrizione) }, Modifier.fillMaxWidth().padding(top = 18.dp), abilitato = nome.isNotBlank())
    }
}

/** Un gruppo: colore, nome, che mail ci vanno, raccolta, posizione, togli. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoglioGruppo(
    g: Gruppo,
    posizione: Int,
    quanti: Int,
    abilitato: Boolean,
    vm: ImpostazioniViewModel,
    apri: (String) -> Unit,
    onTogli: () -> Unit,
    onChiudi: () -> Unit,
) {
    val c = Sol.colori
    val attuale = ColoreGruppo.daNome(g.colore)
    FoglioSol(onChiudi = onChiudi, titolo = g.nome) {
        Text("Colore", style = Sol.testi.secondario, fontWeight = FontWeight.Bold, color = c.testoMedio)
        FlowRow(
            Modifier.padding(top = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 6,
        ) {
            ColoreGruppo.entries.forEach { colore ->
                val scelto = colore == attuale
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = abilitato && !scelto, role = Role.RadioButton, onClickLabel = colore.nome) { vm.cambiaColoreGruppo(g.id, colore.nome) }
                        .semantics {
                            selected = scelto
                            contentDescription = colore.nome
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .then(if (scelto) Modifier.border(2.dp, c.testo, CircleShape) else Modifier)
                            .padding(if (scelto) 4.dp else 0.dp)
                            .background(colore.punto, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (scelto) Icon(Icone.Spunta, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Scheda(Modifier.padding(top = 16.dp), colore = c.fondo) {
            RigaVoce("Nome", { apri("rinomina:${g.id}") }, valore = g.nome)
            RigaSottile(16.dp)
            RigaVoce("Che mail ci vanno", { apri("descrizione:${g.id}") }, valore = descrizioneCorta(g) ?: "Solo il nome")
            RigaSottile(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = g.raccolta, enabled = abilitato, role = Role.Switch) { vm.cambiaRaccoltaGruppo(g.id, it) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Raccolta in una riga", style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = c.testo, modifier = Modifier.weight(1f))
                Switch(checked = g.raccolta, onCheckedChange = null, enabled = abilitato, colors = coloriInterruttore())
            }
            RigaSottile(16.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Posizione ${posizione + 1} di $quanti", style = Sol.testi.medio, fontWeight = FontWeight.SemiBold, color = c.testo, modifier = Modifier.weight(1f))
                if (posizione > 0) {
                    PulsanteTondo(Icone.Giu, "Sposta su", { if (abilitato) vm.spostaGruppo(g.id, su = true) }, Modifier.rotate(180f))
                }
                if (posizione < quanti - 1) {
                    PulsanteTondo(Icone.Giu, "Sposta giù", { if (abilitato) vm.spostaGruppo(g.id, su = false) })
                }
            }
        }
        if (!g.nome.equals("Altro", ignoreCase = true)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                PulsanteTesto("Togli il gruppo", onTogli, abilitato = abilitato, colore = c.truffaTesto)
            }
        }
    }
}

/** Solo indirizzi https: è il sito di Microsoft, mai altro. */
private fun apriIndirizzo(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nessun browser: l'indirizzo resta scritto e si può copiare.
    }
}

private fun copia(context: Context, testo: String) {
    val appunti = context.getSystemService(ClipboardManager::class.java) ?: return
    appunti.setPrimaryClip(ClipData.newPlainText("Codice Microsoft", testo))
}
