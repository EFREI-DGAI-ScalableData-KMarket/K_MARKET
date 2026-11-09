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
| `spark-master` | `apache/spark:3.5.9` | `7077`, `8081` | Master Spark + interface web |
| `spark-worker` | `apache/spark:3.5.9` | — | Worker Spark |
| `tailscale` | `tailscale/tailscale` | — | Expose la stack sur le tailnet (démo à distance) |

Tous les ports sont liés à `127.0.0.1`. L'accès distant passe par Tailscale.

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
`SPARK_WORKER_CORES`, `TS_AUTHKEY`, `TS_HOSTNAME`, `TS_EXTRA_ARGS`.
