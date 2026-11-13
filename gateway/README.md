# Infrastructure

Configuration des briques ajoutées au `docker-compose.yml` au fil du projet :

- `gateway/` : API gateway (routage, vérification du JWT, limitation de débit)
- `kafka/` : topics
- `observability/` : Prometheus, Grafana (tableaux de bord), OpenTelemetry

## Briques déjà dans `docker-compose.yml`

| Service | Image | Ports (hôte) | Rôle |
|---|---|---|---|
| `kafka` | `apache/kafka:3.9.0` | `9092` | Broker Kafka en mode KRaft (sans ZooKeeper) |
| `kafka-ui` | `provectuslabs/kafka-ui` | `8080` | Console web : topics, partitions, lag |
| `minio` | `cgr.dev/chainguard/minio` | `9000` (S3), `9001` (console) | Stockage objet S3 du Data Lake |
| `minio-init` | `cgr.dev/chainguard/minio-client` | — | Crée le bucket `LAKE_BUCKET` au premier démarrage |
| `mc` | `cgr.dev/chainguard/minio-client` | — | Client S3 à la demande (profil `tools`, ne démarre pas avec `make up`) |
| `spark-master` | `apache/spark:3.5.9` | `7077`, `8081` | Master Spark + interface web |
| `spark-worker` | `apache/spark:3.5.9` | — | Worker Spark |

Tous les ports sont liés à `127.0.0.1` : rien n'est exposé en dehors de la machine
par défaut. Pour partager un service à distance, voir « Partage à distance » ci-dessous.

### Listeners Kafka

- `INTERNAL` (`kafka:19092`) : utilisé par les conteneurs (services, Spark, kafka-ui).
- `EXTERNAL` (`localhost:9092`) : utilisé depuis l'hôte (outils, tests).

### Spark

Le dossier `./spark` est monté en lecture seule dans `/opt/spark-apps`. Lancer un job :

```bash
docker compose exec spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  /opt/spark-apps/streaming/<job>.py
```

Pour lire/écrire dans MinIO (S3A) :
`spark.hadoop.fs.s3a.endpoint=http://minio:9000`, `access.key=MINIO_ROOT_USER`,
`secret.key=MINIO_ROOT_PASSWORD`, `path.style.access=true`.

### Variables `.env`

`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `LAKE_BUCKET`, `SPARK_WORKER_MEMORY`,
`SPARK_WORKER_CORES`.

### MinIO, S3 et le partage : le modèle

Trois notions souvent confondues :

- **MinIO** = le **serveur** de stockage objet (conteneur `minio`). C'est lui qui
  détient réellement les fichiers.
- **S3** = un **protocole / une API** (issu d'Amazon S3), c'est-à-dire la *langue*
  pour parler à un stockage objet (« liste le bucket », « dépose cet objet »…).
  MinIO parle le protocole S3 : c'est pourquoi `mc`, `rclone`, `aws cli` ou
  Cyberduck fonctionnent sans modification. Ce n'est donc **pas** « S3 + MinIO » :
  MinIO est le serveur, S3 est la langue. (Comparaison : PostgreSQL est le serveur,
  SQL est la langue.)
- **Le tunnel** (`scripts/share-minio.sh`) = un simple **tuyau** temporaire. Il ne
  stocke rien ; il relaie le trafic pendant qu'il tourne.

Où vivent les données : dans le volume Docker `miniodata`, sur **cette machine**
(`/var/lib/docker/volumes/<projet>_miniodata/_data`). Rien n'est stocké dans le
cloud ni chez le fournisseur du tunnel. Quand la personne distante dépose un
fichier, il atterrit dans **votre** MinIO, sur **votre** disque :

```
mc / navigateur (distant)
    │  commande S3 en HTTPS
    ▼
https://xxxx.lhr.life        (localhost.run : la porte publique, ne stocke rien)
    │  transmise dans le tunnel SSH
    ▼
cette machine, localhost:9000   (MinIO : le serveur S3)
    │
    ▼
volume Docker miniodata          (le disque local : où le fichier est écrit)
```

Conséquences : la machine doit rester allumée et le tunnel ouvert ; les dépôts de
la personne distante remplissent **votre** disque ; l'URL est éphémère.

### Client S3 `mc` (voir et déposer sans interface web)

L'API S3 n'a pas d'interface : on l'utilise avec un client. Le service `mc`
(profil `tools`) a déjà l'alias `local` pointé sur le MinIO interne, et le dossier
courant de l'hôte est monté en lecture seule dans `/data-in`.

```bash
make mc-ls                               # arborescence du bucket du lake
make mc-up FILE=data.csv DEST=raw/       # dépose ./data.csv dans kmarket-lake/raw/
make mc ARGS="tree local/kmarket-lake"   # commande mc libre
make mc ARGS="rm local/kmarket-lake/raw/data.csv"
```

Interface graphique si besoin : la console web locale **http://localhost:9001**
(instantanée, pas de tunnel), ou un client bureau (Cyberduck, S3 Browser) pointé
sur l'URL du tunnel. La console web **via le tunnel** est à éviter (bundle JS
lourd, lent sur l'offre gratuite) ; préférer l'API S3 + `mc`.



Le réseau d'entreprise (Cisco Umbrella) bloque les VPN et tunnels classiques
(Tailscale, Cloudflare Tunnel, ngrok) mais laisse passer le SSH sortant.
`scripts/share-minio.sh` ouvre donc un tunnel SSH via `localhost.run` (ou `srv.us`)
et renvoie une URL `https://…` publique et temporaire.

```bash
make share-minio                 # expose l'API S3 (port 9000) — pour mc / rclone / aws
make share-minio MODE=console    # expose la console web (port 9001)
```

- Le tunnel n'est qu'un tuyau : aucune donnée n'est stockée chez le fournisseur.
  Les objets restent dans le volume Docker `miniodata`, sur la machine locale.
- L'URL change à chaque reconnexion et disparaît quand on ferme le terminal
  (ou quand la machine se met en veille).
- Transmettre à la personne distante l'URL **et** les identifiants
  `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.

Côté distant, connexion via l'API S3 (mode `api`) :

```bash
mc alias set kmarket https://<url-du-tunnel> <MINIO_ROOT_USER> <MINIO_ROOT_PASSWORD>
mc ls kmarket/kmarket-lake
```
