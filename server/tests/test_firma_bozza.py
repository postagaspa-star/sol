"""Chiusura delle bozze: una sola firma, niente segnaposto.

Visto nella prova sul NAS col solo scrittore locale: il modello chiudeva con
"Cordiali saluti, [Il tuo Nome]" e il programma aggiungeva la firma che comincia anche lei
con "Cordiali saluti". Manuel avrebbe dovuto cancellare a mano due righe in ogni bozza."""

from app import prompt


def firmata(testo, firma):
    return prompt.testo_con_firma(testo, firma)


FIRMA = "Cordiali saluti\nManuel"


def test_chiusura_doppia_e_segnaposto_tolti():
    testo = "Gentile Mario Rossi,\n\nconfermo che le mando l'offerta entro lunedì.\n\nCordiali saluti,\n[Il tuo Nome]"
    assert firmata(testo, FIRMA) == (
        "Gentile Mario Rossi,\n\nconfermo che le mando l'offerta entro lunedì.\n\nCordiali saluti\nManuel"
    )


def test_altre_chiusure_e_nome_ripetuto():
    testo = "Buongiorno,\n\nricevuto, grazie.\n\nDistinti saluti\nManuel"
    assert firmata(testo, FIRMA) == "Buongiorno,\n\nricevuto, grazie.\n\nCordiali saluti\nManuel"
    testo = "Buongiorno,\n\nricevuto.\n\nIn attesa di un vostro riscontro, porgo cordiali saluti.\n[Firma]"
    assert firmata(testo, FIRMA) == "Buongiorno,\n\nricevuto.\n\nCordiali saluti\nManuel"


def test_firma_senza_saluti_tiene_la_chiusura_del_modello():
    testo = "Gentile Bianchi,\n\nva bene giovedì.\n\nCordiali saluti,\n[Nome]"
    assert firmata(testo, "Manuel") == "Gentile Bianchi,\n\nva bene giovedì.\n\nCordiali saluti,\n\nManuel"


def test_da_completare_e_testo_normale_restano():
    testo = "Gentile Neri,\n\nil prezzo è [da completare].\nSaluto con piacere la vostra proposta di collaborazione."
    atteso = testo + "\n\nCordiali saluti\nManuel"
    assert firmata(testo, FIRMA) == atteso
    testo = "Gentile Neri,\n\nle confermo la data.\n[da completare]"
    assert firmata(testo, FIRMA).endswith("[da completare]\n\nCordiali saluti\nManuel")


def test_firma_vuota_e_testo_gia_firmato():
    assert firmata("Buongiorno,\n\nok.", "") == "Buongiorno,\n\nok."
    assert firmata("Buongiorno,\n\nok.\n\nCordiali saluti\nManuel", FIRMA) == (
        "Buongiorno,\n\nok.\n\nCordiali saluti\nManuel"
    )
