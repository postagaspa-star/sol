"""Punto 5 della revisione: cambiare il file del modello senza dire peso e impronta."""

import pytest
from fastapi.testclient import TestClient

from app.configurazione import GGUF_FILE, ErroreConfigurazione, leggi
from app.servizio import crea_app

VARIABILI = ("SOL_GGUF_FILE", "SOL_GGUF_URL", "SOL_GGUF_BYTE", "SOL_GGUF_SHA256", "SOL_STILE_PROMPT",
             "SOL_THREADS")


@pytest.fixture(autouse=True)
def ambiente_pulito(monkeypatch):
    for nome in VARIABILI:
        monkeypatch.delenv(nome, raising=False)


def test_valori_di_base_vanno_bene():
    conf = leggi()
    assert conf.gguf_file == GGUF_FILE and len(conf.gguf_sha256) == 64


def test_cambio_file_senza_peso_e_impronta_rifiutato(monkeypatch):
    monkeypatch.setenv("SOL_GGUF_FILE", "Qwen_Qwen3.5-4B-Q5_K_M.gguf")
    with pytest.raises(ErroreConfigurazione, match="SOL_GGUF_BYTE"):
        leggi()
    monkeypatch.setenv("SOL_GGUF_BYTE", "3300000000")
    with pytest.raises(ErroreConfigurazione, match="SOL_GGUF_SHA256"):
        leggi()
    monkeypatch.setenv("SOL_GGUF_SHA256", "ab" * 32)
    conf = leggi()
    assert conf.gguf_byte == 3300000000 and conf.gguf_sha256 == "ab" * 32


def test_indirizzo_diverso_senza_peso_e_impronta_rifiutato(monkeypatch):
    monkeypatch.setenv("SOL_GGUF_URL", "https://esempio.it/modello.gguf")
    with pytest.raises(ErroreConfigurazione):
        leggi()


def test_valori_scritti_male(monkeypatch):
    monkeypatch.setenv("SOL_GGUF_SHA256", "non-una-impronta")
    with pytest.raises(ErroreConfigurazione, match="64"):
        leggi()
    monkeypatch.delenv("SOL_GGUF_SHA256")
    monkeypatch.setenv("SOL_THREADS", "tre")
    with pytest.raises(ErroreConfigurazione, match="SOL_THREADS"):
        leggi()


def test_configurazione_sbagliata_si_vede_in_salute_senza_partire(monkeypatch):
    monkeypatch.setenv("SOL_GGUF_FILE", "altro.gguf")
    with TestClient(crea_app()) as client:  # avvio vero (ciclo di vita), ma non deve scaricare nulla
        salute = client.get("/salute").json()
        risposta = client.post("/decidi", json={"stato": "x", "domande": [
            {"id": "g", "domanda": "?", "opzioni": [{"id": "a"}, {"id": "b"}]}]})
    assert salute["stato"] == "non_disponibile"
    assert "SOL_GGUF_BYTE" in salute["errore"]
    assert risposta.status_code == 503
