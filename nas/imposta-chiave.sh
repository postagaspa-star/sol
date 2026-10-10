#!/bin/bash
# Inserisce una chiave nel file server/.env di Sol sul NAS e fa ripartire Sol.
# Si lancia dal PC, nel Prompt dei comandi:
#   ssh -t "<utente>"@<ip-del-nas> "bash /volume1/docker/Sol-NAS/imposta-chiave.sh"
# La chiave si incolla a un prompt che non la mostra: non finisce nella cronologia dei comandi,
# non passa da nessun programma di chat e non viene mai stampata.
set -e
CARTELLA="${SOL_CARTELLA:-/volume1/docker/Sol-NAS}"
FILE="${SOL_FILE_CHIAVI:-$CARTELLA/server/.env}"
cd "$CARTELLA"

echo "Quale chiave vuoi inserire?"
echo "  1) Groq (scrive bozze e riassunti)"
echo "  2) Cerebras (riserva, se Groq non risponde)"
echo "  3) Microsoft: ID applicazione (client)"
echo "  4) Microsoft: ID directory (tenant)"
echo "  5) Tailscale: chiave di accesso"
read -r -p "Numero: " N
case "$N" in
  1) NOME=SOL_GROQ_KEY ;;
  2) NOME=SOL_CEREBRAS_KEY ;;
  3) NOME=SOL_MS_CLIENT_ID ;;
  4) NOME=SOL_MS_TENANT ;;
  5) NOME=TS_AUTHKEY ;;
  *) echo "Numero non valido: non ho cambiato niente."; exit 1 ;;
esac

read -r -s -p "Incolla il valore (non si vede mentre lo incolli) e premi Invio: " VALORE
echo
VALORE="$(printf '%s' "$VALORE" | tr -d '[:space:]')"
if [ -z "$VALORE" ]; then
  echo "Era vuoto: non ho cambiato niente."; exit 1
fi
# Le chiavi vere usano solo lettere, numeri e - _ . : — qualsiasi altro carattere vuol dire
# che si è incollato qualcosa di sbagliato (o che romperebbe il file).
if ! printf '%s' "$VALORE" | grep -Eq '^[A-Za-z0-9_.:-]+$'; then
  unset VALORE
  echo "Il valore contiene caratteri che una chiave non ha: forse si è copiato qualcosa in più. Non l'ho salvato."
  exit 1
fi

touch "$FILE"
chmod 600 "$FILE"
if grep -q "^$NOME=" "$FILE"; then
  sed -i "s|^$NOME=.*|$NOME=$VALORE|" "$FILE"
else
  printf '%s=%s\n' "$NOME" "$VALORE" >> "$FILE"
fi
unset VALORE
echo "Salvata."

if [ -n "$SOL_SENZA_RIAVVIO" ]; then exit 0; fi
if [ "$NOME" = "TS_AUTHKEY" ]; then
  echo "Avvio Tailscale..."
  docker compose -p sol --profile tailscale up -d --force-recreate sol-tailscale >/dev/null 2>&1 \
    && echo "Tailscale avviato." || echo "Tailscale non è partito: scrivilo a chi ti segue con Sol."
fi
echo "Faccio ripartire Sol con la chiave nuova..."
docker compose -p sol up -d --no-deps --force-recreate sol >/dev/null 2>&1 \
  && echo "Fatto: Sol è ripartito." || echo "Sol non è ripartito: scrivilo a chi ti segue con Sol."
