MongoDB pour le catalogue : exigé explicitement par la diapositive 8.
Traefik pour la gateway : configuration en fichiers, sans le socket Docker. C’était la question restée ouverte entre NGINX et Kong.
Outbox transactionnelle avec un relais Python, plutôt qu’une publication directe ou que Debezium.
Choix GraphQL : erreurs dans extensions.code, mise à jour partielle, productsByIds, alias obligatoires sur type.