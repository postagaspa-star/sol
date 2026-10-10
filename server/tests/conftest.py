import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.decisore import DecisoreNonDisponibile, RispostaNonValida  # noqa: E402

# Una chiave Fernet fissa e pubblica: va bene solo per i test.
CHIAVE_TEST = "q1bL0h0S0ZrJ8n4c1m3Vb7xY2aT6wE9rU5iO8pA3sD0="

# Sabato 10 ottobre 2026, 08:00 UTC = 10:00 a Roma.
ORA_INIZIALE = datetime(2026, 10, 10, 8, 0, 0, tzinfo=timezone.utc)


class Orologio:
    def __init__(self, iniziale: datetime):
        self.corrente = iniziale

    def avanza(self, **kwargs):
        self.corrente += timedelta(**kwargs)


@pytest.fixture
def orologio(monkeypatch):
    from app import clock

    o = Orologio(ORA_INIZIALE)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


class DecisoreFinto:
    """Al posto di sol-decisore: sceglie con parole chiave, sempre allo stesso modo."""

    def __init__(self):
        self.giu = False
        self.non_valido = False
        self.sicurezza = 0.93
        self.chiamate: list[tuple[str, list[str]]] = []
        self.generati: list[list[dict]] = []
        self.testo_generato = None  # per forzare il testo di /genera
        self.genera_giu = False
        self.genera_rifiuta = False  # /genera risponde 422 (richiesta troppo lunga)
        self.incerto = False  # probabilità basse su da_rispondere e urgenza
        # Truffa (contratto 16): None = decide con le parole chiave; un numero = probabilità
        # di "si" per tutte; "manca" = un decisore vecchio che non risponde a quella domanda.
        self.truffa = None
        self.rifiuti_di_fila = 0

    def salute(self, massima_eta: float = 30.0) -> dict:
        if self.giu:
            return {"stato": "non_disponibile", "modello": None}
        return {"stato": "pronto", "modello": "Modello di prova"}

    def segna_non_disponibile(self):
        pass

    def stato_pubblico(self) -> dict:
        return {**self.salute(), "percentuale": None, "errore": None}

    def _gruppo(self, testo: str, opzioni: list[str]) -> str:
        t = testo.lower()
        for parola, gruppo in (
            ("comune", "PEC ed enti"), ("fattur", "Amministrazione e fatture"), ("ordine", "Fornitori"),
            ("listino", "Fornitori"), ("webinar", "Newsletter"),
        ):
            if parola in t and gruppo in opzioni:
                return gruppo
        return "Clienti" if "Clienti" in opzioni else opzioni[0]

    PAROLE_TRUFFA = ("iban", "coordinate bancarie", "password", "inoltra", "sblocca")

    def decidi(self, stato: str, domande: list[dict], facoltative=frozenset()) -> dict:
        if self.giu:
            raise DecisoreNonDisponibile("giù")
        self.chiamate.append((stato, [d["id"] for d in domande]))
        if self.non_valido:
            raise RispostaNonValida("prova")
        risposte = {}
        for d in domande:
            opzioni = [o["id"] for o in d["opzioni"]]
            if d["id"] == "gruppo":
                scelta = self._gruppo(stato, opzioni)
            elif d["id"] in ("da_rispondere", "aspetta"):
                scelta = "si" if "?" in stato else "no"
            elif d["id"] == "urgenza":
                scelta = "alta" if "urgente" in stato.lower() else "normale"
            elif d["id"] == "truffa":
                if self.truffa == "manca":
                    continue
                if isinstance(self.truffa, float):
                    risposte["truffa"] = {"scelta": "si" if self.truffa >= 0.5 else "no",
                                          "probabilita": {"si": self.truffa, "no": round(1 - self.truffa, 4)}}
                    continue
                scelta = "si" if any(p in stato.lower() for p in self.PAROLE_TRUFFA) else "no"
            else:
                scelta = opzioni[0]
            p = self.sicurezza if d["id"] == "gruppo" else (0.5 if self.incerto and d["id"] != "truffa" else 0.9)
            resto = (1 - p) / max(1, len(opzioni) - 1)
            risposte[d["id"]] = {"scelta": scelta, "probabilita": {o: (p if o == scelta else resto) for o in opzioni}}
        return risposte

    def genera(self, messaggi: list[dict], max_token: int) -> str:
        if self.genera_giu or self.giu:
            raise DecisoreNonDisponibile("giù")
        if self.genera_rifiuta:
            richiesta = httpx.Request("POST", "http://decisore.finto:8300/genera")
            raise httpx.HTTPStatusError("422", request=richiesta, response=httpx.Response(422, request=richiesta))
        self.generati.append(messaggi)
        if self.testo_generato is not None:
            return self.testo_generato
        richiesta = messaggi[-1]["testo"]
        if richiesta.startswith("Riassumi in una riga"):
            return "Riassunto di prova della mail."
        if richiesta.startswith("Trova le scadenze"):
            from app import clock

            quando = (clock.oggi() + timedelta(days=5)).isoformat()
            return f'Ecco: [{{"descrizione": "Consegna relazione", "quando": "{quando}", "ora": "09:30"}}]'
        if "bozza" in richiesta or "sollecito" in richiesta:
            return "<think>ragiono</think>Buongiorno,\ngrazie per la mail. Le rispondo appena possibile."
        ids = re.findall(r"\[#(\d+)\]", richiesta)
        citate = " ".join(f"[#{i}]" for i in ids[:2])
        return f"Oggi {len(ids)} mail. Le più importanti: {citate} e anche [#999999]."


@pytest.fixture
def decisore():
    return DecisoreFinto()


@pytest.fixture
def ambiente(tmp_path, monkeypatch):
    monkeypatch.setenv("SOL_DB", str(tmp_path / "sol-test.db"))
    monkeypatch.setenv("SOL_SOTTOFONDO", "0")
    monkeypatch.setenv("SOL_CHIAVE", CHIAVE_TEST)
    monkeypatch.setenv("SOL_DECISORE_URL", "http://decisore.finto:8300")
    monkeypatch.setenv("SOL_APK_DIR", str(tmp_path / "apk"))
    for nome in ("SOL_ENV", "SOL_GROQ_KEY", "SOL_CEREBRAS_KEY", "SOL_MS_CLIENT_ID", "SOL_VERSIONI"):
        monkeypatch.delenv(nome, raising=False)
    return tmp_path


def collega_finti(app, decisore):
    """Mette i finti al posto di decisore e rete; i lavori 'in sottofondo' girano subito."""
    from app.scrittore import Scrittore

    s = app.state.servizi
    s.decisore = decisore
    s.lavoro.decisore = decisore
    s.scrittore = Scrittore(s.settings, decisore, dormi=lambda secondi: None)
    s.scrittore.pausa_minima = 0
    s.lavoro.scrittore = s.scrittore
    s.fabbrica.in_sottofondo = lambda funzione, *argomenti: funzione(*argomenti)
    return s


@pytest.fixture
def app(ambiente, orologio, decisore):
    from app.caselle import finta
    from app.main import create_app

    finta.REGISTRO.clear()
    applicazione = create_app()
    collega_finti(applicazione, decisore)
    return applicazione


@pytest.fixture
def client(app):
    with TestClient(app) as c:
        yield c


@pytest.fixture
def servizi(app):
    return app.state.servizi


@pytest.fixture
def token(app):
    from app import abbina, db

    with db.aperta(app.state.settings.db_path) as conn:
        codice, _ = abbina.nuovo_codice(conn)
        t, _ = abbina.abbina(conn, codice, "Telefono di prova", "android", abbina.clock.now())
    return t


@pytest.fixture
def auth(token):
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def finta(client, auth):
    """Una casella finta già controllata una volta: 10 mail arrivate."""
    r = client.post("/api/caselle", json={"tipo": "finta", "nome": "Prova"}, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def smista_tutto(servizi, massimo=200):
    for _ in range(massimo):
        if servizi.lavoro.passo_decisore() != "fatto":
            return
    raise AssertionError("il decisore non finisce")


def scrivi_tutto(servizi, massimo=300):
    for _ in range(massimo):
        if servizi.lavoro.passo_scrittore() != "fatto":
            return
    raise AssertionError("lo scrittore non finisce")


PASSWORD_APP = "abcd efgh ijkl mnop"


@pytest.fixture
def server_gmail(servizi):
    """Un Gmail finto collegato alla fabbrica delle caselle."""
    from finti_server import GmailFinto

    server = GmailFinto()
    servizi.fabbrica.imap_factory = server.imap
    servizi.fabbrica.smtp_factory = server.smtp
    return server


def aggiungi_gmail(client, auth, password=PASSWORD_APP) -> dict:
    r = client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": password},
                    headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


@pytest.fixture
def server_graph(servizi):
    """Un Microsoft Graph finto, con l'app Outlook configurata e accessi finti."""
    from finti_server import AccessoFinto, GraphFinto

    graph = GraphFinto()
    servizi.settings = servizi.settings.__class__(**{**servizi.settings.__dict__, "ms_client_id": "id-app-finta"})
    servizi.fabbrica.ms_client_id = "id-app-finta"
    servizi.fabbrica.graph_transport = graph.trasporto()
    graph.accessi = []

    def fabbrica_accesso(cache):
        a = AccessoFinto(cache)
        graph.accessi.append(a)
        return a

    servizi.fabbrica.accesso_factory = fabbrica_accesso
    return graph


def invia(client, auth, bozza_id, **altro):
    """Come fanno le app: si invia la versione della bozza appena vista (contratto 15.11)."""
    versione = client.get(f"/api/bozze/{bozza_id}", headers=auth).json()["versione"]
    return client.post(f"/api/bozze/{bozza_id}/invia", json={"conferma": True, "versione": versione, **altro},
                       headers=auth)


def mail_per_oggetto(client, auth, pezzo):
    tutte = client.get("/api/mail?limite=200", headers=auth).json()["mail"]
    trovate = [m for m in tutte if pezzo.lower() in m["oggetto"].lower()]
    assert trovate, f"nessuna mail con «{pezzo}»"
    return trovate[0]
