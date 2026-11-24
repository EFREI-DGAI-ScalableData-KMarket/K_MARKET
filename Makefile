.PHONY: help up up-lake up-stream up-spark up-lake-stream up-all down reset reset-all seed reseed logs orders-logs orders-events psql mongo explain backup restore-test kafka spark minio mc mc-ls mc-up share-minio traffic kafka-tail fix-net test-catalog test-stock migrate migrate-orders-outbox migrate-idempotency-hash
help:            ## Liste les commandes
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-13s %s\n", $$1, $$2}'

.env:
	@echo "Fichier .env absent : cp .env.example .env, puis remplacez les valeurs." && exit 1

# --- Démarrage par profil -------------------------------------------------
# Recette commune : démarre la pile puis applique les migrations idempotentes
# (ex. création de orders.outbox sur une base déjà initialisée). $(1) = profils.
define compose_up
	docker compose $(1) up -d --build
	$(MAKE) migrate
endef

up: .env         ## Démarre les bases + seed (rien d'autre)
	$(call compose_up,)

up-lake: .env    ## Démarre les bases + seed + MinIO (Data Lake)
	$(call compose_up,--profile lake)

up-stream: .env  ## Démarre les bases + seed + Kafka (streaming)
	$(call compose_up,--profile stream)

up-spark: .env   ## Démarre les bases + seed + Spark
	$(call compose_up,--profile spark)

up-lake-stream: .env  ## Bases + seed + MinIO + Kafka
	$(call compose_up,--profile lake --profile stream)

up-all: .env     ## Démarre tout (bases + seed + MinIO + Kafka + Spark)
	$(call compose_up,--profile lake --profile stream --profile spark)

down:            ## Arrête les conteneurs (les données sont conservées)
	docker compose --profile lake --profile stream --profile spark down

reset: .env      ## Supprime les volumes et réinitialise tout (scripts d'init + seed)
	docker compose --profile lake --profile stream --profile spark down -v
	$(call compose_up,)

reset-all: .env  ## Réinitialise tout (bases + MinIO + Kafka + Spark)
	docker compose --profile lake --profile stream --profile spark down -v
	$(call compose_up,--profile lake --profile stream --profile spark)

seed: .env       ## Relance le seed (ne fait rien si les bases sont déjà peuplées)
	docker compose run --rm seed

ORDERS ?= 20000
reseed: .env     ## Vide les tables et regénère tout (make reseed ORDERS=500000)
	docker compose run --rm seed --reset --orders $(ORDERS)

logs:            ## Affiche les logs du seed
	docker compose logs seed

orders-logs:     ## Affiche les logs du service Commandes (+ relais outbox si lancé)
	docker compose logs -f orders-service orders-outbox-relay

orders-events:   ## Événements Kafka de Commandes en direct (topic orders.events)
	$(MAKE) kafka-tail TOPIC=orders.events

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

mc:              ## Client S3 mc : passer la commande dans ARGS (make mc ARGS="ls local/kmarket-lake")
	docker compose run --rm mc $(ARGS)

mc-ls:           ## Liste tout le contenu du bucket du lake (arborescence)
	docker compose run --rm mc ls --recursive local/kmarket-lake

mc-up:           ## Envoie un fichier de l'hôte vers le lake (make mc-up FILE=chemin DEST=raw/)
	docker compose run --rm mc cp /data-in/$(FILE) local/kmarket-lake/$(DEST)

share-minio:     ## Partage MinIO sur Internet via tunnel SSH, sans VPN (make share-minio ; ou MODE=console)
	./scripts/share-minio.sh $(MODE)

explain:         ## Démo EXPLAIN avant/après index (après make reseed ORDERS=500000)
	docker compose exec -T postgres psql -U postgres -d kmarket -X < data/demo/explain.sql

# --- Migrations (idempotentes) sur une base déjà initialisée --------------
# Les scripts data/postgres/*.sql ne tournent qu'au premier démarrage (volume vide).
# Ces migrations ajoutent ce qui manque aux bases existantes et sont rejouables.
migrate: .env    ## Applique toutes les migrations idempotentes (lancé aussi par `make up`)
	$(MAKE) migrate-orders-outbox
	$(MAKE) migrate-idempotency-hash

migrate-orders-outbox: .env  ## Crée orders.outbox + droits si absents (transactional outbox)
	@echo "Attente de PostgreSQL..."
	@until docker compose exec -T postgres pg_isready -U postgres -d kmarket -q; do sleep 1; done
	docker compose exec -T postgres psql -U postgres -d kmarket -v ON_ERROR_STOP=1 -X \
	  < data/postgres/migrations/2026_add_orders_outbox.sql

migrate-idempotency-hash: .env  ## Ajoute idempotency_keys.request_hash si absent (même clé + requête différente -> 409)
	@echo "Attente de PostgreSQL..."
	@until docker compose exec -T postgres pg_isready -U postgres -d kmarket -q; do sleep 1; done
	docker compose exec -T postgres psql -U postgres -d kmarket -v ON_ERROR_STOP=1 -X \
	  < data/postgres/migrations/2026_add_idempotency_request_hash.sql

backup:          ## Sauvegarde PostgreSQL + MongoDB dans backups/
	./scripts/backup.sh

restore-test:    ## Restaure la dernière sauvegarde dans des bases de test et compare
	./scripts/restore-test.sh

fix-net:         ## Codespace : autorise le trafic entre conteneurs (règle iptables-legacy)
	sudo iptables-legacy -C FORWARD -i br-+ -o br-+ -j ACCEPT 2>/dev/null || \
	sudo iptables-legacy -I FORWARD -i br-+ -o br-+ -j ACCEPT

test-stock: .env   ## Tests d'intégration du service Stock (pile lancée + seed requis)
	@set -a && . ./.env && set +a && python -m pytest services/stock-service/tests -v -p no:cacheprovider


test-catalog: .env   ## Tests d'intégration du service Catalogue (pile lancée + seed requis)
	@set -a && . ./.env && set +a && python -m pytest services/catalog-service/tests -v -p no:cacheprovider

kafka-tail:      ## Événements Kafka en direct (make kafka-tail TOPIC=inventory.shipment-status-changed)
	docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
	  --topic $(or $(TOPIC),inventory.stock-changed) --from-beginning --property print.key=true
traffic: .env    ## Trafic de démo vers la gateway (make traffic DURATION=300 RPS=10)
	@set -a && . ./.env && set +a && python scripts/traffic.py --duration $(or $(DURATION),120) --rps $(or $(RPS),10)