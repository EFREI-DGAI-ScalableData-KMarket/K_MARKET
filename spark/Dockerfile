# Image Spark du projet : l'image officielle + le client PostgreSQL du job temps réel + le dossier des checkpoints.
FROM apache/spark:3.5.9-scala2.12-java17-python3-r-ubuntu

USER root
RUN pip install --no-cache-dir "psycopg[binary]==3.2.*" \
 && mkdir -p /opt/spark-checkpoints && chown spark:spark /opt/spark-checkpoints
USER spark