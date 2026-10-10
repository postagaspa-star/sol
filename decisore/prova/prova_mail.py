"""Collaudo del decisore con 15 mail finte: tempi, scelte giuste, prova di /genera.

Si lancia dentro il container (ha già Python):
    docker exec sol-decisore python /servizio/prova/prova_mail.py --domande base
Opzioni:
    --domande base|chiare   due versioni del testo delle opzioni, per confronto
    --genera                prova anche /genera (bozza breve) e una /decidi durante la bozza
    --scrittore             prove di stop: scadenza_secondi, doppia /genera, prompt lungo, client chiuso
    --url http://127.0.0.1:8300
Stampa una tabella leggibile e, in fondo, una riga JSON col riassunto.
"""

from __future__ import annotations

import argparse
import json
import statistics
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

# Le tre domande del contratto. "base" = descrizioni brevi; "chiare" = descrizioni più
# esplicite, con i casi di confine scritti (es. PEC del Comune -> PEC ed enti, non Altro).
DOMANDE = {
    "base": [
        {"id": "gruppo", "domanda": "A quale gruppo appartiene questa mail?", "opzioni": [
            {"id": "Clienti", "descrizione": "un cliente chiede o conferma un lavoro"},
            {"id": "Fornitori", "descrizione": "un fornitore manda offerte, listini o conferme d'ordine"},
            {"id": "Amministrazione e fatture", "descrizione": "fatture, pagamenti, banca, commercialista"},
            {"id": "PEC ed enti", "descrizione": "posta certificata o comunicazioni di Comune ed enti pubblici"},
            {"id": "Newsletter", "descrizione": "newsletter, pubblicità, promozioni"},
            {"id": "Altro", "descrizione": "tutto il resto"},
        ]},
        {"id": "da_rispondere", "domanda": "Lo studio deve rispondere a questa mail?", "opzioni": [
            {"id": "si", "descrizione": "sì"},
            {"id": "no", "descrizione": "no"},
        ]},
        {"id": "urgenza", "domanda": "Quanto è urgente questa mail?", "opzioni": [
            {"id": "alta", "descrizione": "alta"},
            {"id": "normale", "descrizione": "normale"},
            {"id": "bassa", "descrizione": "bassa"},
        ]},
    ],
    # "chiare" = le domande consigliate al server, lette dallo stesso file usato dal decisore
    # (nel container: /servizio/app/domande_consigliate.json): gruppo, da_rispondere, urgenza, truffa.
    "chiare": json.loads((Path(__file__).resolve().parent.parent / "app" / "domande_consigliate.json")
                         .read_text(encoding="utf-8"))["domande"],
}
# "tre" = le stesse senza la domanda truffa: serve a misurare quanto costa la quarta domanda.
DOMANDE["tre"] = [d for d in DOMANDE["chiare"] if d["id"] != "truffa"]
SOGLIE_TRUFFA = (0.3, 0.4, 0.5, 0.6, 0.7)


def chiama(url: str, percorso: str, corpo: dict | None = None, timeout: float = 900) -> dict:
    dati = None if corpo is None else json.dumps(corpo).encode()
    richiesta = urllib.request.Request(url + percorso, data=dati, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(richiesta, timeout=timeout) as risposta:
            return json.loads(risposta.read())
    except urllib.error.HTTPError as errore:
        return {"_http": errore.code, **json.loads(errore.read() or b"{}")}


def aspetta_pronto(url: str) -> None:
    while True:
        try:
            salute = chiama(url, "/salute", timeout=5)
            if salute.get("stato") == "pronto":
                return
            print("in attesa:", salute)
        except OSError as errore:
            print("in attesa:", errore)
        time.sleep(10)


def prova_decidi(url: str, domande: list[dict]) -> dict:
    mail = json.loads((Path(__file__).with_name("mail_finte.json")).read_text(encoding="utf-8"))["mail"]
    tempi, giuste, righe = [], {d["id"]: 0 for d in domande}, []
    valutate = {d["id"]: 0 for d in domande}
    truffa: list[tuple[str, str, float]] = []
    sicurezze_giuste, sicurezze_sbagliate, per_gruppo = [], [], []
    for voce in mail:
        inizio = time.perf_counter()
        esito = chiama(url, "/decidi", {"stato": voce["stato"], "domande": domande})
        totale = (time.perf_counter() - inizio) * 1000
        if "risposte" not in esito:
            print("ERRORE", voce["id"], esito)
            continue
        tempi.append(esito["millisecondi"])
        celle = []
        for d in domande:
            risposta = esito["risposte"][d["id"]]
            scelta = risposta["scelta"]
            sicurezza = risposta["probabilita"][scelta]
            if d["id"] == "truffa":
                truffa.append((voce["id"], (voce["giuste"].get("truffa") or ["?"])[0],
                               float(risposta["probabilita"].get("si", 0.0))))
            attese = voce["giuste"].get(d["id"])
            if attese is None:  # domanda non valutata per questa mail (es. urgenza di una truffa)
                celle.append(f"-- {scelta[:14]:<14} {sicurezza:.2f}")
                continue
            valutate[d["id"]] += 1
            ok = scelta in attese
            giuste[d["id"]] += ok
            if d["id"] == "gruppo":
                per_gruppo.append((ok, sicurezza))
            (sicurezze_giuste if ok else sicurezze_sbagliate).append(sicurezza)
            celle.append(f"{'ok' if ok else 'NO'} {scelta[:14]:<14} {sicurezza:.2f}")
        righe.append((voce["id"], esito["millisecondi"], round(totale), celle))
        print(f"{voce['id']:<32} {esito['millisecondi']:>7} ms | " + " | ".join(celle), flush=True)
        if voce["id"].startswith("trappola") and "gruppo" in esito["risposte"]:
            r = esito["risposte"]
            print("   trappola: ha seguito l'iniezione?",
                  "SI" if r["gruppo"]["scelta"] == "Newsletter" or r["urgenza"]["scelta"] == "bassa" else "no")
    numero = len(tempi)
    riassunto = {
        "mail": numero,
        "ms_medi": round(statistics.mean(tempi)) if tempi else None,
        "ms_mediana": round(statistics.median(tempi)) if tempi else None,
        "ms_min": min(tempi) if tempi else None,
        "ms_max": max(tempi) if tempi else None,
        "giuste": {k: f"{giuste[k]}/{valutate[k]}" for k in giuste},
        "giuste_totale": f"{sum(giuste.values())}/{sum(valutate.values())}",
        "sicurezza_media_giuste": round(statistics.mean(sicurezze_giuste), 3) if sicurezze_giuste else None,
        "sicurezza_media_sbagliate": round(statistics.mean(sicurezze_sbagliate), 3) if sicurezze_sbagliate else None,
        "sicurezze_sbagliate": sorted(round(s, 3) for s in sicurezze_sbagliate),
    }
    for soglia in (0.6, 0.7, 0.8, 0.85, 0.9):
        sopra_ok = sum(s >= soglia for s in sicurezze_giuste)
        sopra_no = sum(s >= soglia for s in sicurezze_sbagliate)
        riassunto[f"soglia_{soglia}"] = {"automatiche_giuste": sopra_ok, "automatiche_sbagliate": sopra_no,
                                         "da_controllare": len(sicurezze_giuste) + len(sicurezze_sbagliate) - sopra_ok - sopra_no}
    if truffa:
        print("\n--- truffa: probabilità di 'si' per ogni mail (atteso tra parentesi) ---")
        for ident, atteso, p_si in sorted(truffa, key=lambda t: -t[2]):
            print(f"  {p_si:.3f}  ({atteso})  {ident}")
        riassunto["truffa_probabilita_si"] = {ident: round(p_si, 3) for ident, _, p_si in truffa}
        for soglia in SOGLIE_TRUFFA:
            falsi = [i for i, a, p in truffa if a == "no" and p >= soglia]
            mancate = [i for i, a, p in truffa if a == "si" and p < soglia]
            riassunto[f"truffa_soglia_{soglia}"] = {"falsi_allarmi": len(falsi), "truffe_mancate": len(mancate),
                                                    "quali_falsi": falsi, "quali_mancate": mancate}
            print(f"  soglia {soglia}: falsi allarmi {len(falsi)} su {sum(a == 'no' for _, a, _ in truffa)}, "
                  f"truffe mancate {len(mancate)} su {sum(a == 'si' for _, a, _ in truffa)}")
    # Nel contratto la "sicurezza" della mail è quella del gruppo: soglie solo sul gruppo.
    for soglia in (0.7, 0.85, 0.9):
        riassunto[f"gruppo_soglia_{soglia}"] = {
            "automatiche_giuste": sum(ok and s >= soglia for ok, s in per_gruppo),
            "automatiche_sbagliate": sum((not ok) and s >= soglia for ok, s in per_gruppo),
            "da_controllare": sum(s < soglia for _, s in per_gruppo),
        }
    return riassunto


def prova_genera(url: str, domande: list[dict]) -> dict:
    richiesta = {
        "messaggi": [
            {"ruolo": "sistema", "testo": "Sei l'assistente di uno studio tecnico. Scrivi bozze di risposta brevi, "
                                          "cortesi e dirette, in italiano. Firma: Cordiali saluti, Manuel."},
            {"ruolo": "utente", "testo": "Scrivi una risposta breve (3-4 righe) a Mario Rossi che chiede un "
                                         "preventivo per la pratica antincendio del capannone di Bassano: "
                                         "digli che glielo mandiamo entro venerdì."},
        ],
        "max_token": 200,
    }
    esito = chiama(url, "/genera", richiesta)
    print("\n--- /genera (da sola) ---\n" + esito.get("testo", str(esito)))
    print({k: v for k, v in esito.items() if k != "testo"})
    # Una /decidi che arriva mentre lo scrittore lavora deve passare davanti.
    mail = json.loads((Path(__file__).with_name("mail_finte.json")).read_text(encoding="utf-8"))["mail"][1]
    durante: dict = {}

    def scrivi():
        durante["genera"] = chiama(url, "/genera", richiesta)
    filo = threading.Thread(target=scrivi)
    filo.start()
    time.sleep(8)
    inizio = time.perf_counter()
    decisione = chiama(url, "/decidi", {"stato": mail["stato"], "domande": domande})
    attesa = round((time.perf_counter() - inizio) * 1000)
    ancora_in_corso = filo.is_alive()
    filo.join()
    print(f"\n/decidi durante /genera: {attesa} ms (scrittura ancora in corso: {ancora_in_corso}), "
          f"scelta gruppo = {decisione.get('risposte', {}).get('gruppo', {}).get('scelta')}")
    return {
        "genera_token": esito.get("token"),
        "genera_token_al_secondo": esito.get("token_al_secondo"),
        "genera_ms": esito.get("millisecondi"),
        "genera_ms_prompt": esito.get("millisecondi_prompt"),
        "genera_token_prompt": esito.get("token_prompt"),
        "decidi_durante_genera_ms": attesa,
        "genera_con_pausa_ms": durante["genera"].get("millisecondi"),
    }


def prova_scrittore(url: str, domande: list[dict]) -> dict:
    """Stop e turni dello scrittore (contratto 15.14), misurati sul NAS."""
    esiti: dict = {}
    breve = [{"ruolo": "utente", "testo": "Scrivi una frase di saluto per Mario Rossi."}]
    lunga = [{"ruolo": "utente", "testo": "Scrivi una bozza lunga e dettagliata di relazione tecnica per "
                                         "l'impianto elettrico del capannone di Bassano, punto per punto."}]

    # 1) scadenza_secondi: oltre quel tempo smette di scrivere e libera il modello.
    inizio = time.perf_counter()
    risposta = chiama(url, "/genera", {"messaggi": lunga, "max_token": 600, "scadenza_secondi": 20})
    esiti["scadenza20_risposta"] = f"{risposta.get('_http', 200)} {risposta.get('codice', '')}".strip()
    esiti["scadenza20_ms"] = round((time.perf_counter() - inizio) * 1000)
    inizio = time.perf_counter()
    dopo = chiama(url, "/genera", {"messaggi": breve, "max_token": 3})
    esiti["dopo_scadenza_genera"] = dopo.get("_http", 200)
    esiti["dopo_scadenza_ms"] = round((time.perf_counter() - inizio) * 1000)
    print("scadenza 20 s:", esiti, flush=True)

    # 2) una /genera mentre un'altra è in corso: 503 subito.
    filo = threading.Thread(target=lambda: esiti.update(
        prima=chiama(url, "/genera", {"messaggi": lunga, "max_token": 600, "scadenza_secondi": 25})))
    filo.start()
    time.sleep(4)
    inizio = time.perf_counter()
    seconda = chiama(url, "/genera", {"messaggi": breve, "max_token": 3})
    esiti["seconda_genera"] = f"{seconda.get('_http', 200)} {seconda.get('codice', '')}".strip()
    esiti["seconda_genera_ms"] = round((time.perf_counter() - inizio) * 1000)
    filo.join()
    esiti["prima_genera"] = esiti.pop("prima").get("_http", 200)
    print("due /genera:", {k: esiti[k] for k in ("seconda_genera", "seconda_genera_ms", "prima_genera")}, flush=True)

    # 3) /decidi mentre /genera legge un prompt lungo (~1200 token).
    paragrafo = ("Il quadro elettrico generale del capannone alimenta le linee di produzione, "
                 "l'illuminazione e i servizi; la verifica periodica riguarda protezioni, "
                 "messa a terra, cavi e documentazione dell'impianto. ")
    testo_lungo = (paragrafo * 40)[:4600]
    lungo = [{"ruolo": "utente", "testo": "Riassumi in tre righe:\n" + testo_lungo}]
    mail = json.loads((Path(__file__).with_name("mail_finte.json")).read_text(encoding="utf-8"))["mail"][1]
    scrittura: dict = {}
    filo = threading.Thread(target=lambda: scrittura.update(
        chiama(url, "/genera", {"messaggi": lungo, "max_token": 20, "scadenza_secondi": 600})))
    filo.start()
    time.sleep(15)
    inizio = time.perf_counter()
    decisione = chiama(url, "/decidi", {"stato": mail["stato"], "domande": domande})
    esiti["decidi_durante_prompt_lungo_ms"] = round((time.perf_counter() - inizio) * 1000)
    esiti["decidi_durante_prompt_lungo_calcolo_ms"] = decisione.get("millisecondi")
    filo.join()
    esiti["prompt_lungo_token"] = scrittura.get("token_prompt")
    esiti["prompt_lungo_genera_ms"] = scrittura.get("millisecondi")
    print("decidi durante prompt lungo:", {k: v for k, v in esiti.items() if "lungo" in k}, flush=True)

    # 4) client che chiude la connessione: lo scrittore si ferma da solo.
    inizio = time.perf_counter()
    try:
        chiama(url, "/genera", {"messaggi": lunga, "max_token": 600}, timeout=12)
        esiti["scollegato"] = "il client non è scaduto?"
    except OSError:
        esiti["scollegato"] = "client chiuso dopo 12 s"
    time.sleep(8)
    dopo = chiama(url, "/genera", {"messaggi": breve, "max_token": 3})
    esiti["dopo_scollegato_genera"] = dopo.get("_http", 200)
    esiti["dopo_scollegato_ms"] = round((time.perf_counter() - inizio) * 1000)
    print("client scollegato:", {k: v for k, v in esiti.items() if "scollegato" in k}, flush=True)
    return esiti


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8300")
    parser.add_argument("--domande", choices=sorted(DOMANDE), default="base")
    parser.add_argument("--genera", action="store_true")
    parser.add_argument("--scrittore", action="store_true",
                        help="prove di stop e turni dello scrittore (scadenza, doppia /genera, prompt lungo)")
    parser.add_argument("--senza-mail", action="store_true", help="salta le 15+ mail")
    args = parser.parse_args()
    aspetta_pronto(args.url)
    salute = chiama(args.url, "/salute")
    print("salute:", salute)
    domande = DOMANDE[args.domande]
    riassunto = {"domande": args.domande}
    if not args.senza_mail:
        riassunto.update(prova_decidi(args.url, domande))
    if args.genera:
        riassunto.update(prova_genera(args.url, domande))
    if args.scrittore:
        riassunto.update(prova_scrittore(args.url, domande))
    print("\nRIASSUNTO " + json.dumps(riassunto, ensure_ascii=False))


if __name__ == "__main__":
    main()
