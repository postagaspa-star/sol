"""Smistamento: prima le regole (dell'utente, poi quelle fisse), poi il decisore.

Ordine:
1. regola `mittente` (indirizzo esatto), poi regola `dominio` (anche i sottodomini).
   Per una PEC vera le regole guardano il mittente della busta, mai quello della mail
   interna (che scrive chi manda la PEC: contratto 15.8);
2. regole fisse: PEC (busta con daticert.xml, dominio di PEC, casella PEC) → "PEC ed enti";
   header List-Unsubscribe → "Newsletter" come proposta, ma il decisore decide comunque se
   serve una risposta: se dice di sì con sicurezza, la mail va `da_controllare` (un ordine
   mandato da un gestionale ha anche lui List-Unsubscribe);
3. il decisore, con tre domande: gruppo, da_rispondere, urgenza. Se la probabilità del
   gruppo scelto sta sotto `soglia_sicurezza` la mail resta `da_controllare`.

Spostamenti nella casella vera (contratto 15.2): una mail smistata mentre la modalità
prova è spenta si segna `applica = 1` e la sposta il giro di posta; quelle smistate
prima restano "arretrato" e si spostano solo se l'utente lo chiede."""

import json
import sqlite3
from pathlib import Path

from . import clock, db, prompt
from .caselle.testo import oggetto_risposta

DOMINI_PUBBLICI = {
    "gmail.com", "googlemail.com", "libero.it", "virgilio.it", "alice.it", "tim.it", "tin.it",
    "hotmail.com", "hotmail.it", "outlook.com", "outlook.it", "live.com", "live.it", "msn.com",
    "yahoo.com", "yahoo.it", "icloud.com", "me.com", "tiscali.it", "fastwebnet.it", "email.it",
    "inwind.it", "aruba.it", "proton.me", "protonmail.com", "gmx.com", "gmx.it",
}
# Gestori di PEC noti: il loro dominio dice già che è posta certificata.
DOMINI_PEC = {"legalmail.it", "postecert.it", "arubapec.it", "pec.it", "sicurezzapostale.it", "pecimprese.it"}

# Le domande sono ESATTAMENTE quelle consigliate dal decisore (contratto 15.14): il file
# app/domande_decisore.json è una copia di decisore/app/domande_consigliate.json (un test
# controlla che non divergano). Con questi testi il decisore ha già pronta la parte fissa
# della domanda e la prima mail non aspetta ~40 s in più.
_CONSIGLIATE = {
    d["id"]: d
    for d in json.loads((Path(__file__).with_name("domande_decisore.json")).read_text(encoding="utf-8"))["domande"]
}
DOMANDA_GRUPPO = _CONSIGLIATE["gruppo"]["domanda"]
DOMANDA_DA_RISPONDERE = _CONSIGLIATE["da_rispondere"]
DOMANDA_URGENZA = _CONSIGLIATE["urgenza"]
# Sotto questa probabilità la risposta su da_rispondere/urgenza è incerta: si tiene il
# valore più prudente (contratto 15.14).
SOGLIA_INCERTEZZA = 0.6
PRUDENTE = {"da_rispondere": "si", "urgenza": "normale"}
DOMANDA_INVIATA = {
    "id": "aspetta",
    "domanda": "Questa mail inviata aspetta una risposta?",
    "opzioni": [
        {"id": "si", "descrizione": "fa una domanda, chiede una conferma, un documento o una decisione"},
        {"id": "no", "descrizione": "è un ringraziamento, una conferma o un'informazione che non chiede niente"},
    ],
}
RISPOSTE_NEWSLETTER = {
    "da_rispondere": {"scelta": "no", "probabilita": {"si": 0.0, "no": 1.0}},
    "urgenza": {"scelta": "bassa", "probabilita": {"alta": 0.0, "normale": 0.0, "bassa": 1.0}},
}


def dominio(indirizzo: str) -> str:
    return (indirizzo or "").rsplit("@", 1)[-1].lower().strip()


def dominio_pubblico(d: str) -> bool:
    return d in DOMINI_PUBBLICI


def _e_pec(d: str) -> bool:
    if not d:
        return False
    if "pec" in d.split("."):
        return True
    return any(d == p or d.endswith("." + p) for p in DOMINI_PEC)


def mittente_per_regole(m) -> str:
    """Il mittente su cui valgono le regole: per una PEC vera quello della busta."""
    return (m["pec_mittente_busta"] if m["pec_mittente_busta"] else m["mittente_indirizzo"]) or ""


def regola_utente(conn: sqlite3.Connection, mittente: str) -> sqlite3.Row | None:
    mittente = (mittente or "").lower()
    riga = conn.execute(
        "SELECT * FROM regole WHERE tipo = 'mittente' AND valore = ?", (mittente,)
    ).fetchone()
    if riga:
        return riga
    # esempio.it vale anche per ufficio.esempio.it; vince il dominio più lungo.
    parti = dominio(mittente).split(".")
    for i in range(len(parti) - 1):
        riga = conn.execute(
            "SELECT * FROM regole WHERE tipo = 'dominio' AND valore = ?", (".".join(parti[i:]),)
        ).fetchone()
        if riga:
            return riga
    return None


def regola_fissa(mittente: str, newsletter: bool, pec: bool, indirizzo_casella: str) -> str | None:
    """La chiave del gruppo iniziale scelto da una regola fissa, o None."""
    if pec or _e_pec(dominio(mittente)) or _e_pec(dominio(indirizzo_casella)):
        return "pec"
    if newsletter:
        return "newsletter"
    return None


def prova_accesa(conn: sqlite3.Connection) -> bool:
    return bool(db.impostazioni(conn)["modalita_prova"])


def smista_con_regole(conn: sqlite3.Connection, mail_id: int) -> bool:
    """Prova a dare il gruppo con le regole, appena la mail arriva. Vero se ci è riuscito."""
    m = conn.execute(
        "SELECT m.*, c.indirizzo AS indirizzo_casella FROM mail m JOIN caselle c ON c.id = m.casella_id"
        " WHERE m.id = ?",
        (mail_id,),
    ).fetchone()
    regola = regola_utente(conn, mittente_per_regole(m))
    if regola:
        gruppo_id, fissa = regola["gruppo_id"], None
    else:
        fissa = regola_fissa(mittente_per_regole(m), bool(m["newsletter"]), bool(m["pec_mittente_busta"]),
                             m["indirizzo_casella"])
        gruppo = db.gruppo_per_chiave(conn, fissa) if fissa else None
        if gruppo is None:
            return False
        gruppo_id = gruppo["id"]
    if fissa == "newsletter":
        # Solo una proposta: resta in_attesa finché il decisore non dice se serve rispondere.
        conn.execute(
            "UPDATE mail SET gruppo_id = ?, regola_fissa = 'newsletter', origine_smistamento = 'regola' WHERE id = ?",
            (gruppo_id, mail_id),
        )
        return True
    conn.execute(
        "UPDATE mail SET gruppo_id = ?, smistamento = 'smistata', origine_smistamento = 'regola',"
        " sicurezza = 1.0, regola_fissa = ?, applica = ? WHERE id = ?",
        (gruppo_id, fissa, 0 if prova_accesa(conn) else 1, mail_id),
    )
    return True


def domanda_gruppo(conn: sqlite3.Connection) -> dict:
    opzioni = [
        {"id": g["nome"], "descrizione": g["descrizione"] or g["nome"]}
        for g in conn.execute("SELECT nome, descrizione FROM gruppi ORDER BY ordine, id")
    ]
    return {"id": "gruppo", "domanda": DOMANDA_GRUPPO, "opzioni": opzioni}


def _chiave_gruppo(conn: sqlite3.Connection, gruppo_id) -> str | None:
    if gruppo_id is None:
        return None
    g = conn.execute("SELECT chiave FROM gruppi WHERE id = ?", (gruppo_id,)).fetchone()
    return g["chiave"] if g else None


def valuta(conn: sqlite3.Connection, m: sqlite3.Row, decisore, soglia: float) -> None:
    """Fa le domande che mancano per questa mail e salva le risposte.
    Solleva le eccezioni del decisore (chi chiama decide se riprovare)."""
    in_attesa = m["smistamento"] == "in_attesa"
    newsletter_utente = (
        not in_attesa
        and _chiave_gruppo(conn, m["gruppo_id"]) == "newsletter"
        and (m["regola_fissa"] != "newsletter" or m["smistamento"] == "corretta")
    )
    if newsletter_utente:
        # L'utente ha deciso che questo mittente è una newsletter: non si chiede niente.
        risposte = dict(RISPOSTE_NEWSLETTER)
    else:
        serve_gruppo = in_attesa and m["gruppo_id"] is None
        domande = ([domanda_gruppo(conn)] if serve_gruppo else []) + [DOMANDA_DA_RISPONDERE, DOMANDA_URGENZA]
        risposte = decisore.decidi(prompt.stato_per_decisore(m), domande)
    salva_valutazione(conn, m["id"], risposte, soglia)


def salva_valutazione(conn: sqlite3.Connection, mail_id: int, risposte: dict, soglia: float) -> None:
    with db.transazione(conn):
        m = conn.execute("SELECT * FROM mail WHERE id = ?", (mail_id,)).fetchone()
        if m is None:
            return  # la casella è stata eliminata nel frattempo
        probabilita = json.loads(m["probabilita"]) if m["probabilita"] else {}
        campi: dict = {}
        risposte, incerti = scelte_prudenti(risposte)
        p_si = float(risposte["da_rispondere"]["probabilita"].get("si", 0.0))
        if "gruppo" in risposte and m["smistamento"] == "in_attesa":
            scelta = risposte["gruppo"]["scelta"]
            g = conn.execute("SELECT id FROM gruppi WHERE nome = ?", (scelta,)).fetchone()
            if g is None:  # il gruppo è stato cancellato mentre il decisore pensava
                g = db.gruppo_per_chiave(conn, "altro")
            sicurezza = float(risposte["gruppo"]["probabilita"].get(scelta, 0.0))
            campi.update(
                gruppo_id=g["id"],
                sicurezza=sicurezza,
                smistamento="smistata" if sicurezza >= soglia else "da_controllare",
                origine_smistamento="decisore",
            )
            probabilita["gruppo"] = risposte["gruppo"]["probabilita"]
        elif m["smistamento"] == "in_attesa" and m["regola_fissa"] == "newsletter":
            # Proposta Newsletter da List-Unsubscribe: se chiede davvero una risposta, la
            # guarda l'utente invece di finire tra le newsletter senza bozza.
            chiede = risposte["da_rispondere"]["scelta"] == "si" and (p_si >= soglia or "da_rispondere" in incerti)
            campi.update(
                smistamento="da_controllare" if chiede else "smistata",
                sicurezza=round(1.0 - p_si, 4) if chiede else 1.0,
            )
        if m["gruppo_id"] is not None and "gruppo" not in probabilita:
            g = conn.execute("SELECT nome FROM gruppi WHERE id = ?", (m["gruppo_id"],)).fetchone()
            if g:
                probabilita["gruppo"] = {g["nome"]: 1.0}
        # Se l'utente ha già deciso da sé (POST /rispondere) la sua scelta resta. Una risposta
        # automatica ("sono in ferie") non chiede mai una risposta.
        if m["da_rispondere"] is not None:
            da_rispondere = bool(m["da_rispondere"])
        elif m["automatica"]:
            da_rispondere = False
        else:
            da_rispondere = risposte["da_rispondere"]["scelta"] == "si"
        # "Incerto" vale solo per le scelte prese davvero dal decisore.
        decisa_da_altri = {"da_rispondere": m["da_rispondere"] is not None or bool(m["automatica"]),
                           "urgenza": bool(m["urgenza"])}
        incerti_validi = [c for c in incerti if not decisa_da_altri[c]]
        campi.update(
            da_rispondere=1 if da_rispondere else 0,
            urgenza=m["urgenza"] or risposte["urgenza"]["scelta"],
            seq_valutata=db.prossimo_seq(conn),
            incerti=json.dumps(incerti_validi),
        )
        if campi.get("smistamento") == "smistata" and not prova_accesa(conn):
            campi["applica"] = 1
        probabilita["da_rispondere"] = risposte["da_rispondere"]["probabilita"]
        probabilita["urgenza"] = risposte["urgenza"]["probabilita"]
        campi["probabilita"] = json.dumps(probabilita)
        assegnazioni = ", ".join(f"{k} = ?" for k in campi)
        conn.execute(f"UPDATE mail SET {assegnazioni} WHERE id = ?", (*campi.values(), mail_id))
        if da_rispondere and not m["risposta_data"]:
            crea_bozza_automatica(conn, mail_id)
        if da_rispondere or risposte["urgenza"]["scelta"] == "alta":
            db.segna_riassunto_oggi_da_rifare(conn)


def scelte_prudenti(risposte: dict) -> tuple[dict, list[str]]:
    """Se il decisore è poco sicuro su da_rispondere o urgenza (sotto 0,6), vale il valore
    prudente: meglio una bozza in più che una richiesta persa."""
    risposte = dict(risposte)
    incerti = []
    for chiave, prudente in PRUDENTE.items():
        r = risposte.get(chiave)
        if r and float(r["probabilita"].get(r["scelta"], 0.0)) < SOGLIA_INCERTEZZA:
            risposte[chiave] = {**r, "scelta": prudente}
            incerti.append(chiave)
    return risposte, incerti


def lascia_all_utente(conn: sqlite3.Connection, mail_id: int) -> None:
    """Il decisore non riesce a valutare questa mail: la decide l'utente. Se una regola
    aveva già proposto il gruppo vale quella, altrimenti va in Altro da controllare."""
    m = conn.execute("SELECT * FROM mail WHERE id = ?", (mail_id,)).fetchone()
    if m is None or m["smistamento"] != "in_attesa":
        return
    if m["gruppo_id"] is not None:
        conn.execute(
            "UPDATE mail SET smistamento = 'smistata', sicurezza = 1.0, applica = ? WHERE id = ?",
            (0 if prova_accesa(conn) else 1, mail_id),
        )
        return
    altro = db.gruppo_per_chiave(conn, "altro")
    conn.execute(
        "UPDATE mail SET gruppo_id = ?, smistamento = 'da_controllare', sicurezza = 0.0 WHERE id = ?",
        (altro["id"], mail_id),
    )


def crea_bozza_automatica(conn: sqlite3.Connection, mail_id: int) -> int | None:
    """Prenota la bozza (in_preparazione): la scrive lo scrittore quando tocca a lei."""
    esiste = conn.execute(
        "SELECT 1 FROM bozze WHERE mail_id = ? AND tipo = 'risposta' AND stato != 'scartata'", (mail_id,)
    ).fetchone()
    if esiste:
        return None
    m = conn.execute("SELECT mittente_indirizzo, oggetto FROM mail WHERE id = ?", (mail_id,)).fetchone()
    cur = conn.execute(
        "INSERT INTO bozze (mail_id, tipo, stato, a, oggetto, creata_il) VALUES (?, 'risposta', 'in_preparazione', ?, ?, ?)",
        (mail_id, json.dumps([m["mittente_indirizzo"]] if m["mittente_indirizzo"] else []),
         oggetto_risposta(m["oggetto"]), clock.adesso()),
    )
    return cur.lastrowid
