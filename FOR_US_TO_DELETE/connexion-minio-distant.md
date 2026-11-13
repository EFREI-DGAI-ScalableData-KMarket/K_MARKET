# Se connecter au MinIO partagé

Partage d'un minIO. 

```
   (hôte)                                  client
 ┌───────────────────────┐              ┌───────────────────────┐
 │ make up               │              │                       │
 │  └─ MinIO local :9000 │   tunnel SSH │  mc alias "remote"    │
 │                       │◀─────────────┼── https ──────────────│
 │ make share-minio      │              │  mc ls remote/...     │
 └───────────────────────┘              └───────────────────────┘
```

---

## Côté hôte 

1. MinIO démarré (`make up` ou `docker compose up -d minio`).
2. Ouvrir le tunnel sur le port **9000** (API S3) et **laisser le terminal ouvert** :

   ```bash
   make share-minio
   ```


3. Transmettre :
   - l'**URL publique** affichée (ex. `https://abc123.lhr.life`) ;
   - `MINIO_ROOT_USER` et `MINIO_ROOT_PASSWORD` (valeurs de son `.env`) ;
   - le nom du bucket : `kmarket-lake` (valeur de `LAKE_BUCKET`).

> ⚠️ L'URL est éphémère : elle change à chaque relance et meurt quand le terminal est fermé. Il faut alors la retransmettre.

---

## Côté client 

### 1. Installer `mc`

```bash
curl -O https://dl.min.io/aistor/mc/release/linux-amd64/mc
chmod +x mc
sudo mv mc /usr/local/bin/mc
mc --version
```

> Vérifier que le fichier téléchargé est bien un binaire (`file mc` doit afficher `ELF 64-bit LSB executable`). Si c'est du texte (`ASCII text` ou `HTML document`), le téléchargement a échoué.

### 2. Configurer l'alias vers le MinIO partagé

```bash
mc alias set remote <URL_DU_TUNNEL> <MINIO_ROOT_USER> <MINIO_ROOT_PASSWORD>
```

Exemple :

```bash
mc alias set remote https://abc123.lhr.life minioadmin minioadmin
```

Si ça réussit : `Added 'remote' successfully.`

### 3. Tester l'accès

```bash
mc ls remote/kmarket-lake
mc ls remote/kmarket-lake/raw --recursive
mc cp remote/kmarket-lake/raw/fichier.csv ./
```

### 4. Si l'URL change

L'URL du tunnel change à chaque relance. Il suffit de **refaire `mc alias set`** avec la nouvelle URL (la commande écrase l'ancienne configuration) :

```bash
mc alias set remote <NOUVELLE_URL> <MINIO_ROOT_USER> <MINIO_ROOT_PASSWORD>
```

Pas besoin de supprimer l'alias avant.

---

## Récapitulatif

| Qui | Action |
|---|---|
|(hôte) | `make share-minio` (port 9000) ; laisser le terminal ouvert ; envoyer URL + user + password |
|  (client) | Installer `mc` ; `mc alias set remote <URL> <USER> <PASSWORD>` ; `mc ls remote/kmarket-lake` |

**Rappels :**
- L'URL est **éphémère** : à chaque relance du tunnel, refaire `mc alias set remote`.
- Le tunnel doit pointer vers le port **9000** (API S3), pas 9001 (console web).
- La console web (port 9001) n'est **pas nécessaire** pour le développement. `mc` suffit.
- Rien n'est stocké chez le fournisseur du tunnel ; les objets restent dans le volume `miniodata` sur la machine