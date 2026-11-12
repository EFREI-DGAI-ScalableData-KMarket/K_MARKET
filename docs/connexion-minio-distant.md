# Lancer sa propre stack ET se connecter à mon MinIO distant

Ce guide s'adresse à **une coéquipière** qui fait tourner **sa propre stack
complète** du projet avec `make up`, et qui veut **en plus** se connecter à
**mon** MinIO distant (via mon tunnel SSH) pour **comparer** son Data Lake avec
le mien.

Idée clé : après `make up`, elle a son **propre** MinIO en local
(`http://localhost:9000`). Pour atteindre le mien, elle ajoute un **second alias
`mc`** qui pointe vers mon **URL publique** (le tunnel `make share-minio`). Elle
peut alors comparer les deux buckets côte à côte.

```
 Sa machine                              Ma machine
 ┌───────────────────────┐              ┌───────────────────────┐
 │ make up               │              │ make up               │
 │  └─ MinIO local :9000 │              │  └─ MinIO local :9000 │
 │                       │   tunnel SSH │        ▲              │
 │ mc alias "local"  ────┼──────────────┼────────┘ (make        │
 │ mc alias "remote" ────┼─── https ───▶│          share-minio) │
 └───────────────────────┘              └───────────────────────┘
```

---

## Côté hôte (moi) — exposer mon MinIO

1. MinIO démarré (via `make up`, ou `docker compose up -d minio`).
2. Ouvrir le tunnel et **laisser ce terminal ouvert** :

   ```bash
   make share-minio              # partage l'API S3 (port 9000)
   ```

3. Lui transmettre :
   - l'**URL publique** affichée (ex. `https://abc123.lhr.life`) ;
   - `MINIO_ROOT_USER` et `MINIO_ROOT_PASSWORD` (valeurs de mon `.env`) ;
   - le nom du bucket : `kmarket-lake` (valeur de `LAKE_BUCKET`).

> ⚠️ L'URL est éphémère : elle change à chaque relance et meurt quand je ferme
> le terminal. Il faut alors la retransmettre.

---

## Côté coéquipière — sa stack + l'accès à mon MinIO

### 1. Lancer sa propre stack

```bash
cp .env.example .env      # puis remplacer chaque « change-me »
make up                   # bases + init + seed + son MinIO local
```

Son MinIO local est sur `http://localhost:9000` (console : `http://localhost:9001`),
avec les identifiants de **son** `.env`. Son bucket local du lake :
`make mc-ls` (il vise le MinIO interne de **sa** stack).

### 2. Ajouter un alias `mc` vers mon MinIO distant

Le `mc` du dépôt (`make mc`) vise le MinIO **interne** (`minio:9000`) : il ne
peut pas atteindre le mien. Elle installe donc un `mc` autonome :

```bash
# macOS
brew install minio/stable/mc
# Linux
curl -O https://dl.min.io/client/mc/release/linux-amd64/mc && chmod +x mc && sudo mv mc /usr/local/bin/
```

Puis elle configure **deux alias** — le sien et le mien :

```bash
# son MinIO local (identifiants de SON .env)
mc alias set local http://localhost:9000 <SON_MINIO_ROOT_USER> <SON_MINIO_ROOT_PASSWORD>

# mon MinIO distant (URL du tunnel + MES identifiants)
mc alias set remote https://abc123.lhr.life <MON_MINIO_ROOT_USER> <MON_MINIO_ROOT_PASSWORD>
```

### 3. Comparer les deux lakes

```bash
# lister chaque bucket
mc ls local/kmarket-lake  --recursive
mc ls remote/kmarket-lake --recursive

# comparer (ce qui diffère entre les deux)
mc diff local/kmarket-lake remote/kmarket-lake

# récupérer un fichier du mien pour l'examiner
mc cp remote/kmarket-lake/raw/mon-fichier.csv ./

# (optionnel) copier tout mon bucket vers le sien
mc mirror remote/kmarket-lake local/kmarket-lake
```

---

## Récapitulatif

| Qui | Action |
|---|---|
| Moi (hôte) | `make share-minio` ; laisser le terminal ouvert ; envoyer URL + user + password |
| Coéquipière | `make up` (sa stack) ; installer `mc` ; `mc alias set local …` + `mc alias set remote <mon-URL> …` ; puis `mc ls` / `mc diff` / `mc mirror` |

Rien n'est stocké chez le fournisseur du tunnel ; mes objets restent dans le
volume `miniodata` sur ma machine, et le partage s'arrête dès que je ferme le
terminal.

