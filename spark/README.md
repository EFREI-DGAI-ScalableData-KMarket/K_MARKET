# Jobs Spark

- `streaming/` : Spark Structured Streaming sur Kafka (fenêtres, watermark, checkpoint, alertes).
- `lake/` : transformations du Data Lake (raw → staging → curated → consumption), qualité, catalogue.

Le Data Lake est alimenté uniquement par ces pipelines, jamais par le seed.
