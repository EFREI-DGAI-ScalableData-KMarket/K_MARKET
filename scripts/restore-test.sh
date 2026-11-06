#!/usr/bin/env bash
# restore-test.sh — prouve qu'une sauvegarde se restaure.
# Restaure dans des bases de test (kmarket_restore), compare avec les bases d'origine, puis les supprime.
# Les bases d'origine ne sont pas modifiées.
# Usage : make restore-test                 (dernière sauvegarde)
#         ./scripts/restore-test.sh backups/20261106-140000
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:-$(ls -d backups/*/ 2>/dev/null | sort | tail -1)}"
[ -n "$SRC" ] || { echo "Aucune sauvegarde : lancer d'abord make backup"; exit 1; }
SRC="${SRC%/}"

DC="docker compose exec -T"
PSQL="$DC -e PGOPTIONS=-cclient_min_messages=warning postgres psql -U postgres -X -q -v ON_ERROR_STOP=1"

# Nombre de lignes de chaque table (une table partitionnée compte une fois, partitions comprises)
pg_counts() {
  $PSQL -tA -F' ' -d "$1" <<'SQL'
SELECT format('SELECT %L, count(*) FROM %s', c.oid::regclass, c.oid::regclass)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('orders', 'inventory', 'payments', 'auth')
  AND c.relkind IN ('r', 'p') AND NOT c.relispartition
ORDER BY 1
\gexec
SQL
}

# Documents, index et présence du validator
MONGO_JS='
for (const name of ["catalog", "counters"]) print(name + " " + db.getCollection(name).countDocuments());
print("catalog.index " + db.catalog.getIndexes().length);
const info = db.getCollectionInfos({ name: "catalog" })[0];
print("catalog.validator " + (info && info.options.validator ? "oui" : "non"));'

mongo_counts() {
  $DC -e DB="$1" -e JS="$MONGO_JS" mongo sh -c 'mongosh --quiet -u root -p "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin "$DB" --eval "$JS"'
}

cleanup() {
  $PSQL -d postgres -c "DROP DATABASE IF EXISTS kmarket_restore" || true
  $DC -e JS='db.dropDatabase()' mongo sh -c 'mongosh --quiet -u root -p "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin kmarket_restore --eval "$JS"' >/dev/null || true
}
trap cleanup EXIT

echo "Sauvegarde testée : $SRC"

# --- PostgreSQL : restauration dans une base vierge ---
$PSQL -d postgres -c "DROP DATABASE IF EXISTS kmarket_restore" -c "CREATE DATABASE kmarket_restore"
$DC postgres pg_restore -U postgres -d kmarket_restore --exit-on-error < "$SRC/kmarket.pgdump"

# --- MongoDB : restauration sous un autre nom de base ---
$DC mongo sh -c 'mongorestore --quiet -u root -p "$MONGO_INITDB_ROOT_PASSWORD" \
    --authenticationDatabase admin --archive --gzip --drop \
    --nsFrom "kmarket.*" --nsTo "kmarket_restore.*"' < "$SRC/kmarket.mongo.gz"

# --- Comparaison ---
compare() {  # $1 = titre, $2 et $3 = fichiers « clé valeur »
  echo
  printf '%-30s %12s %12s  %s\n' "$1" "origine" "restaurée" ""
  join -a1 -a2 -e '-' -o 0,1.2,2.2 <(sort "$2") <(sort "$3") |
    awk '{ ok = ($2 == $3) ? "OK" : "ÉCART"; if (ok != "OK") bad = 1;
           printf "%-30s %12s %12s  %s\n", $1, $2, $3, ok }
         END { exit bad }'
}

TMP=$(mktemp -d)
pg_counts kmarket          > "$TMP/pg_src"
pg_counts kmarket_restore  > "$TMP/pg_dst"
mongo_counts kmarket         > "$TMP/mg_src"
mongo_counts kmarket_restore > "$TMP/mg_dst"

status=0
compare "PostgreSQL" "$TMP/pg_src" "$TMP/pg_dst" || status=1
compare "MongoDB"    "$TMP/mg_src" "$TMP/mg_dst" || status=1
rm -rf "$TMP"

echo
if [ "$status" -eq 0 ]; then
  echo "Restauration validée : toutes les données sont identiques."
else
  echo "ÉCHEC : des écarts ont été trouvés."
fi
exit "$status"
