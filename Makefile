.PHONY: help up down reset seed reseed logs psql mongo

help:            ## Liste les commandes
	@grep -E '^[a-z]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-8s %s\n", $$1, $$2}'

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
