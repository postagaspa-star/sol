"""Un database della v1.0 si aggiorna da solo alla v1.1 senza perdere niente."""

from app import db


def test_da_v1_a_v1_1(tmp_path, monkeypatch):
    percorso = str(tmp_path / "vecchio.db")
    monkeypatch.setattr(db, "MIGRAZIONI", db.MIGRAZIONI[:1])
    db.init_db(percorso)
    with db.aperta(percorso) as conn:
        conn.execute(
            "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il) VALUES ('finta', 'x@esempio.it', 'x',"
            " 'collegata', '2026-10-01T00:00:00+00:00', '2026-10-01T00:00:00+00:00')"
        )
        conn.execute(
            "INSERT INTO mail (casella_id, id_esterno, oggetto, ricevuta_il, smistamento, gruppo_id)"
            " VALUES (1, 'a', 'Vecchia', '2026-10-02T00:00:00+00:00', 'smistata', 1)"
        )
    monkeypatch.undo()
    db.init_db(percorso)
    db.init_db(percorso)  # una seconda volta non fa niente
    with db.aperta(percorso) as conn:
        assert [r[0] for r in conn.execute("SELECT versione FROM versione_schema ORDER BY versione")] == [1, 2]
        m = conn.execute("SELECT oggetto, applica, incerti, automatica FROM mail").fetchone()
        assert tuple(m) == ("Vecchia", 0, "[]", 0)  # l'arretrato non si sposta da solo
        clienti = conn.execute("SELECT descrizione FROM gruppi WHERE chiave = 'clienti'").fetchone()[0]
        assert clienti == db.DESCRIZIONI_2["clienti"]
        assert conn.execute("SELECT versione FROM bozze").fetchall() == []
        conn.execute("SELECT * FROM mail_illeggibili")
        conn.execute("SELECT * FROM interno")
