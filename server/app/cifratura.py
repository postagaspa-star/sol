"""Cifratura dei segreti delle caselle (password per le app di Gmail, token di
Microsoft) con Fernet. La chiave sta solo nella variabile d'ambiente SOL_CHIAVE."""

import logging

from cryptography.fernet import Fernet, InvalidToken

log = logging.getLogger("uvicorn.error")


class ErroreChiave(RuntimeError):
    pass


class Cifrario:
    def __init__(self, chiave: str):
        try:
            self._fernet = Fernet(chiave.encode("ascii"))
        except (ValueError, UnicodeEncodeError) as e:
            raise ErroreChiave(
                "SOL_CHIAVE non è una chiave valida. Generane una con:\n"
                '  python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"'
            ) from e

    def cifra(self, testo: str) -> str:
        return self._fernet.encrypt(testo.encode("utf-8")).decode("ascii")

    def decifra(self, cifrato: str) -> str:
        try:
            return self._fernet.decrypt(cifrato.encode("ascii")).decode("utf-8")
        except InvalidToken as e:
            # Succede se SOL_CHIAVE è cambiata: i segreti salvati prima non si leggono più.
            raise ErroreChiave("segreto salvato con un'altra chiave") from e


def prepara_cifrario(chiave: str | None, env: str) -> Cifrario:
    if chiave:
        return Cifrario(chiave)
    if env == "prod":
        raise ErroreChiave(
            "SOL_ENV=prod ma SOL_CHIAVE manca: senza, le password delle caselle non si possono "
            "salvare cifrate. Generane una con:\n"
            '  python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"'
        )
    log.warning(
        "SOL_CHIAVE manca: uso una chiave temporanea (solo sviluppo). Le password delle caselle "
        "salvate ora non si leggeranno più dopo il riavvio."
    )
    return Cifrario(Fernet.generate_key().decode("ascii"))
