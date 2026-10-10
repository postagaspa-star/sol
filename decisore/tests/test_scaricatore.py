import hashlib
import io

import pytest

from app.scaricatore import ErroreScaricamento, scarica

DATI = bytes(range(256)) * 4000  # ~1 MB
SHA = hashlib.sha256(DATI).hexdigest()


class Risposta(io.BytesIO):
    def __init__(self, dati: bytes, status: int):
        super().__init__(dati)
        self.status = status

    def getcode(self):
        return self.status


def server_finto(dati=DATI, accetta_ripresa=True, richieste=None):
    def apri(richiesta, timeout=None):
        if richieste is not None:
            richieste.append(richiesta)
        intervallo = richiesta.get_header("Range")
        if intervallo and accetta_ripresa:
            inizio = int(intervallo.split("=")[1].rstrip("-"))
            return Risposta(dati[inizio:], 206)
        return Risposta(dati, 200)
    return apri


def test_scarica_e_verifica(tmp_path):
    avanzamenti = []
    finale = scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA,
                     avanzamento=lambda f, t: avanzamenti.append(f), apri=server_finto())
    assert finale.read_bytes() == DATI
    assert avanzamenti[-1] == len(DATI)
    assert not (tmp_path / "m.gguf.parziale").exists()


def test_riprende_da_file_parziale(tmp_path):
    (tmp_path / "m.gguf.parziale").write_bytes(DATI[:300_000])
    richieste = []
    scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=server_finto(richieste=richieste))
    assert richieste[0].get_header("Range") == "bytes=300000-"
    assert (tmp_path / "m.gguf").read_bytes() == DATI


def test_riparte_da_zero_se_il_server_non_riprende(tmp_path):
    (tmp_path / "m.gguf.parziale").write_bytes(b"robaccia")
    scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=server_finto(accetta_ripresa=False))
    assert (tmp_path / "m.gguf").read_bytes() == DATI


def test_sha_sbagliata_butta_il_parziale(tmp_path):
    with pytest.raises(ErroreScaricamento, match="impronta") as errore:
        scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), "0" * 64, apri=server_finto())
    assert not errore.value.definitivo  # può essere un file rovinato per strada: si riprova
    assert not (tmp_path / "m.gguf.parziale").exists()
    assert not (tmp_path / "m.gguf").exists()


def test_file_incompleto_resta_per_la_ripresa(tmp_path):
    with pytest.raises(ErroreScaricamento, match="interrotto"):
        scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=server_finto(dati=DATI[:1000]))
    assert (tmp_path / "m.gguf.parziale").stat().st_size == 1000


def test_file_gia_presente_non_si_riscarica(tmp_path):
    (tmp_path / "m.gguf").write_bytes(DATI)

    def vietato(*_args, **_kwargs):
        raise AssertionError("non doveva scaricare")
    scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=vietato)


def test_parziale_gia_completo_si_verifica_senza_riscaricare(tmp_path):
    (tmp_path / "m.gguf.parziale").write_bytes(DATI)

    def vietato(*_args, **_kwargs):
        raise AssertionError("non doveva scaricare")
    scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=vietato)
    assert (tmp_path / "m.gguf").read_bytes() == DATI


def test_parziale_completo_ma_rovinato_si_riscarica(tmp_path):
    (tmp_path / "m.gguf.parziale").write_bytes(bytes(len(DATI)))
    scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=server_finto())
    assert (tmp_path / "m.gguf").read_bytes() == DATI


class RispostaSenzaFine:
    """Il server manda byte all'infinito: se lo scaricatore non si ferma, il test lo dice."""

    def __init__(self, limite: int, intestazioni=None):
        self.letti = 0
        self.limite = limite
        self.status = 200
        self.headers = intestazioni or {}

    def read(self, quanti):
        self.letti += quanti
        if self.letti > self.limite:
            raise AssertionError("ha continuato a scaricare oltre il dovuto")
        return b"x" * quanti

    def getcode(self):
        return self.status

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


def test_si_ferma_appena_supera_i_byte_attesi(tmp_path):
    from app.scaricatore import BLOCCO

    risposta = RispostaSenzaFine(limite=len(DATI) + 2 * BLOCCO)
    with pytest.raises(ErroreScaricamento) as errore:
        scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=lambda r, timeout=None: risposta)
    assert errore.value.definitivo  # riprovare non serve: è il file sbagliato
    assert not (tmp_path / "m.gguf.parziale").exists()


def test_dimensione_annunciata_diversa_non_scarica_niente(tmp_path):
    risposta = RispostaSenzaFine(limite=0, intestazioni={"Content-Length": str(len(DATI) * 3)})
    with pytest.raises(ErroreScaricamento) as errore:
        scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=lambda r, timeout=None: risposta)
    assert errore.value.definitivo and risposta.letti == 0


def test_ripresa_con_dimensione_totale_diversa(tmp_path):
    (tmp_path / "m.gguf.parziale").write_bytes(DATI[:1000])
    risposta = RispostaSenzaFine(limite=0, intestazioni={"Content-Range": f"bytes 1000-9999/{len(DATI) + 5}"})
    risposta.status = 206
    with pytest.raises(ErroreScaricamento) as errore:
        scarica("http://x/m.gguf", tmp_path / "m.gguf", len(DATI), SHA, apri=lambda r, timeout=None: risposta)
    assert errore.value.definitivo and not (tmp_path / "m.gguf.parziale").exists()
