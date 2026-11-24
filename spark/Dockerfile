# Image Spark du projet : l'image officielle + les dépendances Python du job temps réel + le dossier des checkpoints.
FROM apache/spark:3.5.9-scala2.12-java17-python3-r-ubuntu

USER root
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
 && mkdir -p /opt/spark-checkpoints && chown spark:spark /opt/spark-checkpoints
USER spark