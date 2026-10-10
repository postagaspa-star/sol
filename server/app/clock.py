"""Unica fonte dell'ora. Nel database le date stanno in UTC (si ordinano come testo);
verso le app escono nell'ora italiana con il fuso (contratto, sezione 1).
I test sostituiscono clock.now()."""

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

FUSO = ZoneInfo("Europe/Rome")

GIORNI_SETTIMANA = ["lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"]


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    """Per il database: sempre UTC, al secondo."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


def adesso() -> str:
    return iso(now())


def da_iso(ts: str) -> datetime:
    dt = datetime.fromisoformat(ts)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def per_utente(ts: str | None) -> str | None:
    """Per le API: ora italiana con il fuso, es. 2026-10-10T09:30:00+02:00."""
    if not ts:
        return None
    return da_iso(ts).astimezone(FUSO).isoformat(timespec="seconds")


def oggi() -> date:
    return now().astimezone(FUSO).date()


def inizio_giorno(giorno: date) -> datetime:
    """Mezzanotte italiana di quel giorno, come istante UTC."""
    return datetime.combine(giorno, time(0, 0), tzinfo=FUSO).astimezone(timezone.utc)


def giorni_trascorsi(ts: str) -> int:
    return max(0, (now() - da_iso(ts)) // timedelta(days=1))


def data_valida(testo) -> bool:
    if not isinstance(testo, str) or len(testo) != 10:
        return False
    try:
        return date.fromisoformat(testo).isoformat() == testo
    except ValueError:
        return False


def ora_valida(testo) -> bool:
    if not isinstance(testo, str) or len(testo) != 5:
        return False
    try:
        time.fromisoformat(testo)
        return True
    except ValueError:
        return False
