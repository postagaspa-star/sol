# Sol — server (FastAPI + SQLite)

Il server che lavora in sottofondo sulla posta: legge le caselle, smista, riassume,
prepara le bozze, trova le scadenze. Fonte di verità delle API: [../docs/contratto-api.md](../docs/contratto-api.md) (v1.4).

## Avvio in locale

1. `python -m venv .venv` poi `.venv\Scripts\activate` (Windows) o `source .venv/bin/activate`
2. `pip install -r requirements-dev.txt` (per i test; l'immagine Docker usa solo `requirements.txt`)
3. `uvicorn app.main:create_app --factory --port 8000` → http://127.0.0.1:8000/api/salute
4. Abbinare un'app: `python -m app.abbina` stampa un codice di 8 cifre (15 minuti, una volta sola).
   Sul NAS: `docker exec sol python -m app.abbina`.
5. Per provare senza caselle vere: `POST /api/caselle` con `{"tipo": "finta"}` (mail inventate).

Le versioni in `requirements.txt` sono fissate: si aggiornano a mano, tutte insieme, dopo aver
rifatto girare i test.

## Variabili d'ambiente

| Variabile | A cosa serve |
|---|---|
| `SOL_ENV` | `prod` in produzione: senza `SOL_CHIAVE` il server non parte |
| `SOL_DB` | percorso del database (base `sol.db`; nel container `/data/sol.db`) |
| `SOL_CHIAVE` | chiave Fernet per cifrare password Gmail e token Microsoft. Generarla con `python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"`. Se cambia, le caselle vanno ricollegate («Ricollega») |
| `SOL_DECISORE_URL` | indirizzo del decisore (base `http://sol-decisore:8300`) |
| `SOL_CONTESTO_LOCALE` | token del modello locale (deve essere uguale a `SOL_CONTESTO` del decisore, base 4096) |
| `SOL_GROQ_KEY`, `SOL_GROQ_MODELLO` | scrittore Groq (modello base `openai/gpt-oss-120b`) |
| `SOL_CEREBRAS_KEY`, `SOL_CEREBRAS_MODELLO` | scrittore Cerebras (modello base `gpt-oss-120b`) |
| `SOL_APK_DIR` | cartella con `sol.apk` e `sol-pc.zip` per `/scarica` (e per l'impronta in `/api/versione`) |
| `SOL_MS_CLIENT_ID`, `SOL_MS_TENANT` | app registrata su Microsoft Entra per Outlook (tenant base `organizations`) |
| `SOL_SOTTOFONDO` | `0` spegne il lavoro in sottofondo (solo per prove) |

Senza chiavi Groq/Cerebras resta solo lo scrittore locale (il decisore: lento, risposte brevi).

## Outlook: registrazione dell'app (una volta)

Su portal.azure.com → Microsoft Entra ID → Registrazioni app → Nuova: account di qualsiasi
organizzazione, "Consenti flussi client pubblici" = Sì, permessi delegati di Microsoft Graph
`Mail.ReadWrite`, `Mail.Send`, `offline_access`, `User.Read`. L'"ID applicazione (client)" va in `SOL_MS_CLIENT_ID`.

## Gmail

Serve la verifica in due passaggi attiva e una "password per le app" (myaccount.google.com → Sicurezza).
Se Google la revoca (per esempio al cambio password), dall'app: «Ricollega» con la nuova.

## Test

`.venv\Scripts\python.exe -m pytest -q` (dalla cartella `server/`). Nessuna rete: Gmail, Outlook,
decisore e scrittori sono sostituiti da server finti.
