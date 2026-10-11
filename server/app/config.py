"""Configurazione dall'ambiente. I segreti (chiave di cifratura, chiavi degli
scrittori) stanno solo qui: mai nel database in chiaro, mai nelle API, mai nei log."""

import os
from dataclasses import dataclass
from pathlib import Path

VERSIONE = "2.0.0"

VERSIONI_PATH_DEFAULT = str(Path(__file__).resolve().parent / "versioni.json")
APK_DIR_DEFAULT = str(Path(__file__).resolve().parents[1] / "apk")

DECISORE_URL_DEFAULT = "http://sol-decisore:8300"
GROQ_URL = "https://api.groq.com/openai/v1"
CEREBRAS_URL = "https://api.cerebras.ai/v1"
# gpt-oss-120b: licenza Apache 2.0, davvero open-source (contratto, sezione 14). Si
# cambiano con SOL_*_MODELLO quando il fornitore toglie o rinomina un modello.
GROQ_MODELLO_DEFAULT = "openai/gpt-oss-120b"
CEREBRAS_MODELLO_DEFAULT = "gpt-oss-120b"
# Token che un fornitore accetta in una richiesta (testo + risposta). Il locale è il
# contesto del decisore (SOL_CONTESTO nel suo compose, base 4096). Per Groq e Cerebras
# il limite vero dei piani gratuiti sono i token al minuto: si sta larghi sotto.
CONTESTO_LOCALE_DEFAULT = 4096
CONTESTO_ESTERNI = 8000

# Abbinamento (contratto, sezione 2). Dietro Tailscale o Cloudflare tutte le richieste
# arrivano dallo stesso indirizzo: il limite dei tentativi sbagliati è uno per tutto il
# server, e quando si supera si annullano anche i codici ancora aperti.
CIFRE_CODICE = 8
DURATA_CODICE_MINUTI = 15
TENTATIVI_SBAGLIATI_MASSIMI = 10
FINESTRA_TENTATIVI_MINUTI = 15

# Gruppi: il decisore sceglie tra 2 e 16 opzioni (contratto, sezione 15.1).
GRUPPI_MASSIMI = 16
GRUPPI_MINIMI = 2


@dataclass(frozen=True)
class Settings:
    env: str
    db_path: str
    chiave: str | None
    decisore_url: str
    groq_key: str | None
    groq_modello: str
    cerebras_key: str | None
    cerebras_modello: str
    apk_dir: str
    versioni_path: str
    ms_client_id: str | None
    ms_tenant: str
    # Il lavoro in sottofondo si spegne nei test (SOL_SOTTOFONDO=0): lì i passi
    # si chiamano a mano, uno alla volta, con l'orologio finto.
    sottofondo: bool
    contesto_locale: int = CONTESTO_LOCALE_DEFAULT


def _vuoto_a_none(valore: str | None) -> str | None:
    if valore is None:
        return None
    valore = valore.strip()
    return valore or None


def _intero(valore: str | None, base: int, minimo: int) -> int:
    try:
        return max(minimo, int(valore)) if valore and valore.strip() else base
    except ValueError:
        return base


def carica_settings() -> Settings:
    e = os.environ.get
    return Settings(
        env=e("SOL_ENV", "dev").strip().lower(),
        db_path=e("SOL_DB", "sol.db"),
        chiave=_vuoto_a_none(e("SOL_CHIAVE")),
        decisore_url=(e("SOL_DECISORE_URL") or DECISORE_URL_DEFAULT).rstrip("/"),
        groq_key=_vuoto_a_none(e("SOL_GROQ_KEY")),
        groq_modello=_vuoto_a_none(e("SOL_GROQ_MODELLO")) or GROQ_MODELLO_DEFAULT,
        cerebras_key=_vuoto_a_none(e("SOL_CEREBRAS_KEY")),
        cerebras_modello=_vuoto_a_none(e("SOL_CEREBRAS_MODELLO")) or CEREBRAS_MODELLO_DEFAULT,
        apk_dir=e("SOL_APK_DIR", APK_DIR_DEFAULT),
        versioni_path=e("SOL_VERSIONI", VERSIONI_PATH_DEFAULT),
        ms_client_id=_vuoto_a_none(e("SOL_MS_CLIENT_ID")),
        ms_tenant=_vuoto_a_none(e("SOL_MS_TENANT")) or "organizations",
        sottofondo=e("SOL_SOTTOFONDO", "1").strip() not in ("0", "no", "false"),
        contesto_locale=_intero(e("SOL_CONTESTO_LOCALE"), CONTESTO_LOCALE_DEFAULT, 1024),
    )


def riassunto_config(s: Settings) -> str:
    """Riga per il log d'avvio: dice COSA è configurato, mai i valori segreti."""

    def stato(v: str | None) -> str:
        return "impostata" if v else "assente"

    return (
        f"env={s.env} db={s.db_path} decisore={s.decisore_url} "
        f"chiave={stato(s.chiave)} groq={stato(s.groq_key)}({s.groq_modello}) "
        f"cerebras={stato(s.cerebras_key)}({s.cerebras_modello}) contesto_locale={s.contesto_locale} "
        f"outlook={'configurato' if s.ms_client_id else 'non configurato'} "
        f"apk_dir={s.apk_dir} sottofondo={'acceso' if s.sottofondo else 'spento'}"
    )


def assicura_cartella_db(db_path: str) -> None:
    Path(db_path).resolve().parent.mkdir(parents=True, exist_ok=True)
