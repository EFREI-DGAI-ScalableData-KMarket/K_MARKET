#!/usr/bin/env bash
# backup.sh — sauvegarde PostgreSQL + MongoDB dans backups/<date-heure>/
# Usage : make backup
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="backups/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"
DC="docker compose exec -T"

echo "Sauvegarde dans $DEST"

# PostgreSQL : format « custom » (-Fc) = compressé, et permet de restaurer table par table
$DC postgres pg_dump -U postgres -d kmarket -Fc > "$DEST/kmarket.pgdump"

# Rôles des services (non inclus dans pg_dump). Contient les empreintes des mots de passe :
# backups/ est dans .gitignore, ne jamais le commiter.
$DC postgres pg_dumpall -U postgres --roles-only > "$DEST/roles.sql"

# MongoDB : collections, index et validator de la base kmarket, en une archive compressée
$DC mongo sh -c 'mongodump --quiet -u root -p "$MONGO_INITDB_ROOT_PASSWORD" \
    --authenticationDatabase admin --db kmarket --archive --gzip' > "$DEST/kmarket.mongo.gz"

ls -lh "$DEST"
