import asyncio
import logging

import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

from app.cifratura import Cifrario, ErroreChiave, prepara_cifrario
from conftest import CHIAVE_TEST, collega_finti


def test_cifra_e_decifra():
    c = Cifrario(CHIAVE_TEST)
    cifrato = c.cifra("abcd efgh ijkl mnop")
    assert "abcd" not in cifrato
    assert c.decifra(cifrato) == "abcd efgh ijkl mnop"


def test_chiave_diversa_non_legge():
    cifrato = Cifrario(CHIAVE_TEST).cifra("segreto")
    with pytest.raises(ErroreChiave):
        Cifrario(Fernet.generate_key().decode()).decifra(cifrato)


def test_chiave_non_valida():
    with pytest.raises(ErroreChiave) as e:
        Cifrario("corta")
    assert "Fernet.generate_key" in str(e.value)


def test_prod_senza_chiave_non_parte(ambiente, monkeypatch):
    monkeypatch.setenv("SOL_ENV", "prod")
    monkeypatch.delenv("SOL_CHIAVE")
    from app.main import create_app

    with pytest.raises(ErroreChiave) as e:
        create_app()
    assert "SOL_CHIAVE manca" in str(e.value)


def test_sviluppo_senza_chiave_avvisa(ambiente, monkeypatch, caplog):
    caplog.set_level(logging.WARNING, logger="uvicorn.error")
    c = prepara_cifrario(None, "dev")
    assert c.decifra(c.cifra("x")) == "x"
    assert "SOL_CHIAVE manca" in caplog.text


def test_log_di_avvio_senza_segreti(ambiente, monkeypatch, caplog):
    monkeypatch.setenv("SOL_GROQ_KEY", "gsk_segretissima")
    monkeypatch.setenv("SOL_CEREBRAS_KEY", "csk_segretissima")
    caplog.set_level(logging.INFO, logger="uvicorn.error")
    from app.main import create_app

    create_app()
    assert "Sol avvio" in caplog.text
    assert "groq=impostata" in caplog.text
    assert "segretissima" not in caplog.text and CHIAVE_TEST not in caplog.text


def test_le_chiavi_non_escono_dalle_api(ambiente, orologio, decisore, monkeypatch):
    monkeypatch.setenv("SOL_GROQ_KEY", "gsk_segretissima")
    from app import abbina, db
    from app.main import create_app

    app = create_app()
    collega_finti(app, decisore)
    with TestClient(app) as c:
        with db.aperta(app.state.settings.db_path) as conn:
            codice, _ = abbina.nuovo_codice(conn)
            token, _ = abbina.abbina(conn, codice, "x", "pc", abbina.clock.now())
        h = {"Authorization": f"Bearer {token}"}
        for percorso in ("/api/salute", "/api/impostazioni", "/api/versione", "/api/caselle", "/api/dispositivi"):
            assert "segretissima" not in c.get(percorso, headers=h).text


def test_lavoro_in_sottofondo_parte_e_si_ferma(ambiente, orologio, decisore, monkeypatch):
    """Con il sottofondo acceso il server controlla la casella da solo e si spegne pulito."""
    monkeypatch.setenv("SOL_SOTTOFONDO", "1")
    from app import abbina, db
    from app.main import create_app

    app = create_app()
    s = collega_finti(app, decisore)
    s.fabbrica.in_sottofondo = lambda f, *a: None  # il controllo lo fa il giro di posta
    with TestClient(app) as c:
        with db.aperta(app.state.settings.db_path) as conn:
            codice, _ = abbina.nuovo_codice(conn)
            token, _ = abbina.abbina(conn, codice, "x", "pc", abbina.clock.now())
        h = {"Authorization": f"Bearer {token}"}
        c.post("/api/caselle", json={"tipo": "finta"}, headers=h)
        s.lavoro.sveglia("posta")
        for _ in range(200):
            mail = c.get("/api/mail?limite=200", headers=h).json()["mail"]
            if len(mail) == 10 and all(m["smistamento"] != "in_attesa" for m in mail) and all(m["riassunto"] for m in mail):
                break
            asyncio.run(asyncio.sleep(0.05))
        else:
            raise AssertionError("il lavoro in sottofondo non ha finito")
    assert s.lavoro._loop is None  # spento
