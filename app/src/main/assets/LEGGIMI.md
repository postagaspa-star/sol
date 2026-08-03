# Modelli della wake word «Ehi Sol»

Qui vanno due file scaricati dalla console Picovoice (https://console.picovoice.ai):

1. `ehi_sol_android.ppn` — la parola «Ehi Sol» addestrata per piattaforma
   Android, lingua Italiano (menu "Porcupine → Create wake word").
2. `porcupine_params_it.pv` — il modello base della lingua italiana (si
   scarica dal repository GitHub di Porcupine, cartella `lib/common`).

La chiave di accesso NON va qui: va in `C:\Users\andre\sol-keys\sol.properties`
come `picovoiceAccessKey=...` (fuori dal repo e da OneDrive, come per Pactum).

Finché questi file mancano l'app funziona lo stesso:
- con la sola chiave → parola di prova inglese «Porcupine»;
- senza nulla → pulsante Parla (push-to-talk).
