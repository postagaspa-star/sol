package eu.stgm.sol

/** Il JSON degli esempi del contratto (docs/contratto-api.md). Solo dati finti. */
object Esempi {
    const val MAIL = """{
      "id": 812, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Mario Rossi", "indirizzo": "rossi@esempio.it"},
      "oggetto": "Offerta capannone Bassano",
      "anteprima": "Buongiorno, ci servirebbe l'offerta entro venerdì...",
      "riassunto": "Rossi chiede l'offerta per il capannone di Bassano entro venerdì.",
      "ricevuta_il": "2026-10-10T08:12:00+02:00",
      "letta": false, "risposta_data": false,
      "gruppo_id": 1, "gruppo": "Clienti",
      "da_rispondere": true, "urgenza": "alta",
      "sicurezza": 0.93,
      "smistamento": "smistata",
      "origine_smistamento": "decisore",
      "applicata_in_casella": false,
      "bozza": {"id": 55, "stato": "pronta"},
      "allegati": [{"nome": "pianta.pdf", "byte": 482113}]
    }"""

    const val MAIL_BIANCHI = """{
      "id": 815, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Luca Bianchi", "indirizzo": "bianchi@esempio.it"},
      "oggetto": "Fattura ottobre", "anteprima": "In allegato la fattura",
      "riassunto": "Bianchi manda la fattura di ottobre.",
      "ricevuta_il": "2026-10-10T07:40:00+02:00",
      "letta": true, "risposta_data": false, "gruppo_id": 3, "gruppo": "Amministrazione e fatture",
      "da_rispondere": false, "urgenza": "normale", "sicurezza": 0.71,
      "smistamento": "da_controllare", "origine_smistamento": "decisore",
      "applicata_in_casella": false, "bozza": null, "allegati": []
    }"""

    val PAGINA_MAIL = """{"mail": [$MAIL, $MAIL_BIANCHI], "altre": true}"""

    val DETTAGLIO = MAIL.trimEnd().removeSuffix("}") + """,
      "destinatari": ["studio@esempio.it"], "cc": [{"nome": "Anna Neri", "indirizzo": "neri@esempio.it"}],
      "corpo": "Buongiorno,\nci servirebbe l'offerta entro venerdì.\nMario Rossi",
      "probabilita": {"gruppo": {"Clienti": 0.93, "Fornitori": 0.04}, "da_rispondere": {"si": 0.88, "no": 0.12}}
    }"""

    fun bozza(
        stato: String = "pronta",
        id: Long = 55,
        testo: String = "Buongiorno Mario,\nl'offerta arriva giovedì.",
        versione: Int = 3,
        cc: String = "",
        modificata: Boolean = false,
    ) = """{
      "id": $id, "mail_id": 812, "tipo": "risposta", "stato": "$stato",
      "a": ["rossi@esempio.it"], "cc": [$cc], "oggetto": "Re: Offerta capannone Bassano",
      "testo": "${testo.replace("\n", "\\n")}", "istruzioni": null,
      "fornitore": "groq", "modificata": $modificata, "versione": $versione,
      "creata_il": "2026-10-10T08:13:00+02:00", "inviata_il": null, "errore": null
    }"""

    /** La mail di Rossi, già risposta da un altro programma (15.13). */
    val DETTAGLIO_GIA_RISPOSTA = DETTAGLIO.replace("\"risposta_data\": false", "\"risposta_data\": true")

    /** 16.2: "Bianchi" chiede di pagare su un IBAN nuovo, da un indirizzo strano. Tutto inventato. */
    const val MAIL_SOSPETTA = """{
      "id": 820, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Luca Bianchi", "indirizzo": "bianchi.amministrazione@esempio-pagamenti.it"},
      "oggetto": "Nuove coordinate bancarie", "anteprima": "Da oggi i pagamenti vanno sul nuovo IBAN",
      "riassunto": "Bianchi chiede di pagare la fattura su un IBAN nuovo, con urgenza.",
      "ricevuta_il": "2026-10-10T09:05:00+02:00",
      "letta": false, "risposta_data": false, "gruppo_id": 2, "gruppo": "Fornitori",
      "da_rispondere": false, "urgenza": "alta", "sicurezza": 0.91,
      "smistamento": "da_controllare", "origine_smistamento": "regola",
      "applicata_in_casella": false, "bozza": null, "allegati": [], "sospetta": true
    }"""

    val DETTAGLIO_SOSPETTO = MAIL_SOSPETTA.trimEnd().removeSuffix("}") + """,
      "destinatari": ["studio@esempio.it"], "cc": [],
      "corpo": "Buongiorno,\nda oggi i pagamenti vanno sul nuovo IBAN. Serve entro oggi.\nLuca Bianchi",
      "probabilita": {"gruppo": {"Fornitori": 0.91}, "truffa": {"si": 0.82, "no": 0.18}}
    }"""

    const val GRUPPI = """[
      {"id": 1, "nome": "Clienti", "ordine": 1, "cartella": "Sol/Clienti", "da_leggere": 4, "totale": 120},
      {"id": 2, "nome": "Fornitori", "ordine": 2, "cartella": "Sol/Fornitori", "da_leggere": 0, "totale": 40},
      {"id": 6, "nome": "Altro", "ordine": 6, "cartella": "Sol/Altro", "da_leggere": 1, "totale": 9}
    ]"""

    /** I gruppi col colore e la frase per Sol (contratto 15.14 e 17). */
    const val GRUPPI_COLORATI = """[
      {"id": 1, "nome": "Clienti", "ordine": 1, "colore": "blu", "descrizione": "un cliente chiede o conferma un lavoro", "da_leggere": 4, "totale": 120},
      {"id": 2, "nome": "Fornitori", "ordine": 2, "colore": "verde", "descrizione": "offerte, ordini e consegne dei fornitori", "totale": 40},
      {"id": 3, "nome": "Amministrazione e fatture", "ordine": 3, "colore": "ambra", "descrizione": "fatture, pagamenti, banca", "totale": 31},
      {"id": 4, "nome": "PEC ed enti", "ordine": 4, "colore": "viola", "descrizione": "PEC, Comune, Agenzia delle Entrate", "totale": 12},
      {"id": 6, "nome": "Altro", "ordine": 6, "colore": "ardesia", "descrizione": "tutto quello che non va negli altri gruppi", "da_leggere": 1, "totale": 9}
    ]"""

    fun impostazioni(prova: Boolean = true) = """{"modalita_prova": $prova, "soglia_sicurezza": 0.85, "giorni_risposta": 2,
      "giorni_sollecito": 7, "intervallo_controllo_minuti": 5, "firma": "Cordiali saluti\nManuel",
      "tono": "cortese e diretto", "scrittori": ["groq", "cerebras", "locale"], "conserva_giorni": 180}"""

    const val SCADENZA = """{"id": 4, "mail_id": 812, "descrizione": "Offerta capannone Bassano a Rossi",
      "quando": "2026-10-16", "ora": null, "stato": "aperta", "creata_il": "2026-10-10T08:14:00+02:00"}"""

    val INDIETRO = """{
      "aspettano_te": [{"mail": $MAIL, "giorni": 3}],
      "aspetti_tu": [{"inviata": {"id": 77, "casella_id": 1, "a": ["neri@esempio.it"], "oggetto": "Offerta compressore",
        "inviata_il": "2026-10-02T10:00:00+02:00", "anteprima": "Buongiorno, le mando l'offerta"}, "giorni": 8, "bozza_sollecito": null}]
    }"""

    const val CASELLA = """{"id": 1, "tipo": "outlook", "indirizzo": "studio@esempio.it", "nome": "Studio",
      "stato": "collegata", "ultimo_controllo": "2026-10-10T09:30:00+02:00", "errore": null,
      "attiva_dal": "2026-10-10T08:00:00+02:00"}"""

    const val VERSIONE = """{"server": "1.0.0", "android": {"codice": 1, "nome": "1.0.0"}, "pc": {"codice": 1, "nome": "1.0.0"}, "nota": "prima versione"}"""

    /** 18.1: la pagina Oggi, una voce per tipo, nell'ordine del contratto. */
    const val OGGI = """{
      "data": "2026-10-11", "nome": "Manuel", "mail_oggi": 14,
      "riassunto": {"id": 30, "tipo": "giornaliero", "richiesta": null, "stato": "pronto",
        "testo": "Rossi aspetta l'offerta del capannone [#812]. Bianchi ha mandato la fattura di ottobre.",
        "mail_citate": [812], "creato_il": "2026-10-11T07:00:00+02:00", "aggiornato_il": "2026-10-11T07:00:00+02:00"},
      "scadenza_prossima": {"id": 4, "descrizione": "Offerta a Rossi", "quando": "2026-10-16", "mail_id": 812},
      "da_fare": [
        {"tipo": "truffa", "mail_id": 820, "mittente": "Ferramenta Neri", "oggetto": "Nuovo IBAN", "riassunto": "Chiede di pagare su un IBAN nuovo", "colore": "verde", "iniziali": "FN"},
        {"tipo": "risposta", "mail_id": 812, "bozza_id": 55, "mittente": "Mario Rossi", "oggetto": "Offerta capannone Bassano", "riassunto": "Chiede l'offerta entro venerdì", "urgenza": "alta", "colore": "blu", "iniziali": "MR"},
        {"tipo": "smista", "conteggio": 3},
        {"tipo": "rispondi", "mail_id": 815, "mittente": "Luca Bianchi", "oggetto": "Fattura ottobre", "riassunto": "Chiede conferma del pagamento", "colore": "ambra", "iniziali": "LB"},
        {"tipo": "sollecito", "inviata_id": 77, "bozza_id": null, "a": "neri@esempio.it", "nome": "Anna Neri", "oggetto": "Offerta compressore", "giorni": 8, "iniziali": "AN"},
        {"tipo": "scadenza", "scadenza_id": 4, "descrizione": "Offerta a Rossi", "quando": "2026-10-16", "mail_id": 812}
      ],
      "fatte_oggi": 2, "totale_oggi": 10
    }"""
}
