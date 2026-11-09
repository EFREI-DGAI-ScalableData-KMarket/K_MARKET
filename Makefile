.PHONY: help up down reset seed reseed logs psql mongo explain backup restore-test kafka spark minio

help:            ## Liste les commandes
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-13s %s\n", $$1, $$2}'

.env:
	@echo "Fichier .env absent : cp .env.example .env, puis remplacez les valeurs." && exit 1

up: .env         ## Démarre les bases et lance le seed (une seule fois)
	docker compose up -d --build

down:            ## Arrête les conteneurs (les données sont conservées)
	docker compose down

reset: .env      ## Supprime les volumes et réinitialise tout (scripts d'init + seed)
	docker compose down -v
	docker compose up -d --build

seed: .env       ## Relance le seed (ne fait rien si les bases sont déjà peuplées)
	docker compose run --rm seed

ORDERS ?= 20000
reseed: .env     ## Vide les tables et regénère tout (make reseed ORDERS=500000)
	docker compose run --rm seed --reset --orders $(ORDERS)

logs:            ## Affiche les logs du seed
	docker compose logs seed

psql:            ## Ouvre psql en superutilisateur
	docker compose exec postgres psql -U postgres -d kmarket

mongo:           ## Ouvre mongosh sur la base kmarket
	docker compose exec mongo sh -c 'mongosh -u root -p "$$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin kmarket'

kafka:           ## Liste les topics Kafka (console web : http://localhost:8080)
	docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

spark:           ## Ouvre un shell Spark sur le master (console web : http://localhost:8081)
	docker compose exec spark-master /opt/spark/bin/spark-shell --master spark://spark-master:7077

minio:           ## Ouvre un shell mc dans le conteneur MinIO (console web : http://localhost:9001)
	docker compose exec minio sh

explain:         ## Démo EXPLAIN avant/après index (après make reseed ORDERS=500000)
	docker compose exec -T postgres psql -U postgres -d kmarket -X < db/demo/explain.sql

backup:          ## Sauvegarde PostgreSQL + MongoDB dans backups/
	./scripts/backup.sh

restore-test:    ## Restaure la dernière sauvegarde dans des bases de test et compare
	./scripts/restore-test.sh
