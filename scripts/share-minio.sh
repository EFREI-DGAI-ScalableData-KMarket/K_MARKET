#!/usr/bin/env bash
# Partage le MinIO local sur Internet via un tunnel SSH (sans VPN, sans compte).
#
# Contexte : sur le réseau d'entreprise, Cisco Umbrella bloque Tailscale, Cloudflare
# Tunnel, ngrok... mais laisse passer le SSH sortant (port 22). localhost.run / srv.us
# exposent donc un service local derrière une URL publique https:// via un simple SSH.
#
# Le tunnel n'est qu'un tuyau : aucune donnée n'est stockée chez le fournisseur.
# Les objets restent dans le volume Docker `miniodata`, sur CE Mac.
# L'URL est éphémère : elle change à chaque reconnexion et meurt quand on ferme le terminal.
#
# Usage :
#   ./scripts/share-minio.sh                 # partage l'API S3 (port 9000) — pour mc/rclone/aws
#   ./scripts/share-minio.sh console         # partage la console web (port 9001)
#   ./scripts/share-minio.sh api srv.us      # choisir le fournisseur (localhost.run par défaut)
#
# Prérequis : MinIO démarré (docker compose up -d minio). ssh est déjà présent sur macOS.
set -euo pipefail

MODE="${1:-api}"
PROVIDER="${2:-localhost.run}"

case "$MODE" in
  api|s3)       LOCAL_PORT=9000; LABEL="API S3 MinIO" ;;
  console|ui)   LOCAL_PORT=9001; LABEL="console web MinIO" ;;
  *) echo "Mode inconnu : '$MODE' (attendu : api | console)"; exit 1 ;;
esac

# localhost.run utilise -R 80:... (user "nokey") ; srv.us utilise -R 1:... (user quelconque).
case "$PROVIDER" in
  localhost.run) REMOTE_SPEC="80:localhost:${LOCAL_PORT}"; SSH_USER="nokey" ;;
  srv.us)        REMOTE_SPEC="1:localhost:${LOCAL_PORT}";  SSH_USER="kmarket" ;;
  *) echo "Fournisseur inconnu : '$PROVIDER' (attendu : localhost.run | srv.us)"; exit 1 ;;
esac

# Clé SSH requise par les deux fournisseurs : on en crée une si besoin (sans passphrase).
if ! ls "$HOME"/.ssh/id_* >/dev/null 2>&1; then
  echo "Aucune clé SSH trouvée — création d'une clé ed25519 (sans passphrase)..."
  mkdir -p "$HOME/.ssh"
  ssh-keygen -t ed25519 -N "" -f "$HOME/.ssh/id_ed25519" >/dev/null
fi

# Vérifie que MinIO répond en local avant d'ouvrir le tunnel.
if ! curl -s -o /dev/null --max-time 5 "http://localhost:${LOCAL_PORT}"; then
  echo "⚠️  MinIO ne répond pas sur http://localhost:${LOCAL_PORT}"
  echo "    Démarre-le d'abord :  docker compose up -d minio"
  exit 1
fi

echo "──────────────────────────────────────────────────────────────"
echo " Partage du ${LABEL} (localhost:${LOCAL_PORT}) via ${PROVIDER}"
echo " Identifiants à transmettre : MINIO_ROOT_USER / MINIO_ROOT_PASSWORD (voir .env)"
echo " L'URL publique s'affiche ci-dessous. Laisse ce terminal OUVERT."
echo " Ctrl-C pour arrêter le partage (les données restent sur ce Mac)."
echo "──────────────────────────────────────────────────────────────"

exec ssh \
  -o StrictHostKeyChecking=accept-new \
  -o ServerAliveInterval=30 \
  -o ExitOnForwardFailure=yes \
  -R "${REMOTE_SPEC}" "${SSH_USER}@${PROVIDER}"
