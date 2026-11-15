#!/data/data/com.termux/files/usr/bin/bash
# =========================================================
# Relais SMS TempoAfrik — à lancer dans Termux sur un téléphone
# Android DÉDIÉ (une SIM = un numéro public du site).
#
# ⚠️  TOUS les SMS reçus par cette SIM deviennent visibles sur le site.
#     N'utilise jamais ton téléphone ou ta SIM personnelle.
#
# Prérequis (une seule fois) :
#   1. Installer Termux ET l'application Termux:API (F-Droid)
#   2. pkg install termux-api jq curl
#   3. Autoriser l'accès aux SMS : termux-sms-list  (accepter la demande)
#   4. Désactiver l'économie de batterie pour Termux
#   5. Ajouter le numéro dans Supabase (table numbers), voir le guide
# Lancement :  bash relais-sms.sh
# =========================================================

API_URL="https://VOTRE-PROJET.supabase.co/functions/v1/inbound-sms"
SECRET="COLLE_ICI_INBOUND_SECRET"
MY_NUMBER="+2290197000000"     # le numéro de la SIM de CE téléphone, au format international
INTERVAL=5                      # secondes entre deux vérifications
HEARTBEAT=120                   # signal "en ligne" toutes les 2 minutes

STATE="$HOME/.tempoafrik_vus"
for cmd in termux-sms-list jq curl sha1sum; do
  command -v "$cmd" >/dev/null || { echo "Manque : $cmd  →  pkg install termux-api jq curl"; exit 1; }
done

lire_sms() {
  # Un SMS par ligne : {"from":..., "body":..., "ref":...}
  termux-sms-list -l 40 -t inbox 2>/dev/null \
    | jq -c '.[] | {from: .number, body: .body, raw: ((.number//"") + "|" + (.received//"") + "|" + (.body//""))}' 2>/dev/null \
    | while read -r l; do
        ref=$(printf '%s' "$(echo "$l" | jq -r .raw)" | sha1sum | cut -c1-40)
        echo "$l" | jq -c --arg ref "$ref" 'del(.raw) + {ref: $ref}'
      done
}

envoyer() {
  curl -fsS -m 15 -X POST "$API_URL" \
    -H "content-type: application/json" -H "x-secret: $SECRET" -d "$1" >/dev/null
}

# Premier lancement : on marque les SMS déjà présents comme "vus"
# pour ne PAS publier l'historique du téléphone.
if [ ! -f "$STATE" ]; then
  lire_sms | jq -r .ref > "$STATE"
  echo "Initialisation : $(wc -l < "$STATE") anciens SMS ignorés."
fi

termux-wake-lock 2>/dev/null
echo "Relais actif pour $MY_NUMBER — Ctrl+C pour arrêter."
last_hb=0

while true; do
  lire_sms | while read -r sms; do
    ref=$(echo "$sms" | jq -r .ref)
    grep -qx "$ref" "$STATE" && continue
    payload=$(echo "$sms" | jq -c --arg to "$MY_NUMBER" '. + {to: $to}')
    if envoyer "$payload"; then
      echo "$ref" >> "$STATE"
      echo "$(date +%H:%M:%S) → SMS de $(echo "$sms" | jq -r .from) transmis"
    else
      echo "$(date +%H:%M:%S) ✗ échec d'envoi, nouvel essai au prochain tour"
    fi
  done

  now=$(date +%s)
  if (( now - last_hb >= HEARTBEAT )); then
    envoyer "{\"to\":\"$MY_NUMBER\",\"heartbeat\":true}" && last_hb=$now
  fi

  # garde le fichier d'état léger
  if [ "$(wc -l < "$STATE")" -gt 3000 ]; then tail -n 1000 "$STATE" > "$STATE.tmp" && mv "$STATE.tmp" "$STATE"; fi
  sleep "$INTERVAL"
done
