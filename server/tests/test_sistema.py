import json
import re

from conftest import collega_finti


def test_versione_senza_token(client):
    r = client.get("/api/versione")
    assert r.status_code == 200
    # Senza file nella cartella APK l'impronta non c'è (contratto 15.10).
    assert r.json() == {
        "server": "1.1.0",
        "android": {"codice": 2, "nome": "1.1.0", "sha256": None, "byte": None},
        "pc": {"codice": 2, "nome": "1.1.0", "sha256": None, "byte": None},
        "nota": "Nuovo aspetto: gruppi colorati, riquadro Da fare, mail aperta con la risposta in evidenza e barra Invia, gesti veloci; segnale di possibile truffa.",
    }


def test_versione_con_impronta_dei_file_veri(client, ambiente):
    import hashlib

    cartella = ambiente / "apk"
    cartella.mkdir(exist_ok=True)
    (cartella / "sol.apk").write_bytes(b"APK-FINTO-1")
    (cartella / "sol-pc.zip").write_bytes(b"ZIP")
    dati = client.get("/api/versione").json()
    assert dati["android"]["sha256"] == hashlib.sha256(b"APK-FINTO-1").hexdigest()
    assert dati["android"]["byte"] == len(b"APK-FINTO-1")
    assert dati["pc"]["sha256"] == hashlib.sha256(b"ZIP").hexdigest() and dati["pc"]["byte"] == 3
    # Il file cambia: l'impronta si ricalcola.
    import os
    import time

    (cartella / "sol.apk").write_bytes(b"APK-FINTO-22")
    os.utime(cartella / "sol.apk", (time.time() + 5, time.time() + 5))
    dati = client.get("/api/versione").json()
    assert dati["android"]["sha256"] == hashlib.sha256(b"APK-FINTO-22").hexdigest()
    assert dati["android"]["byte"] == 12


def test_versione_file_rotto_usa_i_valori_di_base(ambiente, orologio, decisore, monkeypatch):
    rotto = ambiente / "versioni.json"
    rotto.write_text("{non è json", encoding="utf-8")
    monkeypatch.setenv("SOL_VERSIONI", str(rotto))
    from fastapi.testclient import TestClient

    from app.main import create_app

    app = create_app()
    collega_finti(app, decisore)
    with TestClient(app) as c:
        dati = c.get("/api/versione").json()
    assert dati["server"] == "1.1.0" and dati["android"]["codice"] == 1


def test_salute_senza_token_e_ridotta(client):
    assert client.get("/api/salute").json() == {"ok": True, "versione": "1.1.0", "autenticato": False}


def test_salute_token_sbagliato_e_ridotta(client):
    r = client.get("/api/salute", headers={"Authorization": "Bearer sbagliato"})
    assert r.json() == {"ok": True, "versione": "1.1.0", "autenticato": False}


def test_salute_token_revocato_non_autenticato(client, app, auth):
    from app import db

    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("DELETE FROM dispositivi")
    assert client.get("/api/salute", headers=auth).json()["autenticato"] is False


def test_salute_completa(client, auth, finta):
    dati = client.get("/api/salute", headers=auth).json()
    assert dati["ok"] is True and dati["autenticato"] is True
    assert dati["decisore"] == {"stato": "pronto", "modello": "Modello di prova", "percentuale": None, "errore": None}
    # Senza chiavi resta solo il locale (il /genera del decisore).
    assert dati["scrittore"] == {"stato": "solo_locale", "fornitore": "locale", "riserve": []}
    assert dati["caselle"][0]["indirizzo"] == "demo@esempio.it"
    assert dati["caselle"][0]["stato"] == "collegata"
    assert dati["caselle"][0]["ultimo_controllo"].endswith("+02:00")
    assert dati["modalita_prova"] is True
    assert dati["coda"]["da_smistare"] > 0
    assert set(dati["coda"]) == {"da_smistare", "bozze_in_preparazione"}


def test_salute_decisore_giu(client, auth, decisore):
    decisore.giu = True
    dati = client.get("/api/salute", headers=auth).json()
    assert dati["decisore"]["stato"] == "non_disponibile"
    assert dati["scrittore"]["stato"] == "non_disponibile"


def test_api_protette_senza_token(client):
    for metodo, percorso in [
        ("get", "/api/caselle"), ("get", "/api/mail"), ("get", "/api/gruppi"), ("get", "/api/bozze"),
        ("get", "/api/impostazioni"), ("get", "/api/novita"), ("get", "/api/indietro"),
        ("get", "/api/scadenze"), ("get", "/api/riassunti/oggi"), ("get", "/api/dispositivi"),
        ("post", "/api/bozze/1/invia"),
    ]:
        r = getattr(client, metodo)(percorso)
        assert r.status_code == 401, percorso
        assert r.json()["codice"] == "non_autorizzato"
        assert r.json()["errore"]


def _tutte_le_rotte(app):
    for r in app.routes:
        if hasattr(r, "original_router"):
            prefisso = r.include_context.prefix or ""
            for x in r.original_router.routes:
                for metodo in getattr(x, "methods", None) or ():
                    yield metodo, prefisso + x.path
        else:
            for metodo in getattr(r, "methods", None) or ():
                yield metodo, r.path


def test_tutte_le_rotte_vogliono_il_token(client, app):
    """Ogni rotta, tranne le quattro pubbliche del contratto, risponde 401 senza token."""
    pubbliche = {("GET", "/api/versione"), ("GET", "/api/salute"), ("POST", "/api/abbina"), ("GET", "/scarica/{nome_file}")}
    rotte = [(m, p) for m, p in _tutte_le_rotte(app) if m not in ("HEAD", "OPTIONS")]
    assert len(rotte) > 40
    aperte = []
    for metodo, percorso in rotte:
        if (metodo, percorso) in pubbliche:
            continue
        url = re.sub(r"\{[a-z_]+\}", "1", percorso)
        r = client.request(metodo, url, json={})
        if r.status_code != 401:
            aperte.append((metodo, percorso, r.status_code))
    assert aperte == []


def test_formato_errori(client, auth):
    r = client.get("/api/mail/12345", headers=auth)
    assert r.status_code == 404
    assert r.json() == {"errore": "Mail non trovata.", "codice": "non_trovato"}
    r = client.get("/api/mail?limite=9999", headers=auth)
    assert r.status_code == 422
    # 15.13: la frase è per l'utente, il nome del campo va a parte.
    assert r.json() == {"errore": "Si possono chiedere da 1 a 200 mail per volta.", "codice": "non_valido",
                        "campo": "limite"}
    r = client.patch("/api/impostazioni", json={"giorni_risposta": 0}, headers=auth)
    assert r.json()["campo"] == "giorni_risposta" and "giorni_risposta" not in r.json()["errore"]
    r = client.get("/api/non-esiste", headers=auth)
    assert r.status_code == 404 and r.json()["codice"] == "non_trovato"


def test_numero_enorme_non_fa_cadere(client, auth):
    r = client.get("/api/mail/99999999999999999999999", headers=auth)
    assert r.status_code in (404, 422)


def test_cors_chiuso(client, auth):
    r = client.get("/api/salute", headers={**auth, "Origin": "https://sito-qualsiasi.esempio.it"})
    assert "access-control-allow-origin" not in r.headers
    r = client.options("/api/caselle", headers={"Origin": "https://x.esempio.it", "Access-Control-Request-Method": "GET"})
    assert "access-control-allow-origin" not in r.headers


def test_scarica(client, ambiente):
    assert client.get("/scarica/sol.apk").status_code == 404
    assert client.get("/scarica/altro.exe").status_code == 404
    (ambiente / "apk").mkdir(exist_ok=True)
    (ambiente / "apk" / "sol.apk").write_bytes(b"APK-FINTO")
    r = client.get("/scarica/sol.apk")
    assert r.status_code == 200
    assert r.content == b"APK-FINTO"
    assert r.headers["content-type"] == "application/vnd.android.package-archive"


def test_niente_documentazione_pubblica(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_versioni_json_coerente():
    from pathlib import Path

    dati = json.loads((Path(__file__).resolve().parents[1] / "app" / "versioni.json").read_text(encoding="utf-8"))
    assert dati["server"] == "1.1.0"
    assert dati["android"] == {"codice": 2, "nome": "1.1.0"} and dati["pc"] == {"codice": 2, "nome": "1.1.0"}
