Sur explain 

| Requête (500 055 commandes, 26 partitions) | Sans index | Avec index |
|---|---|---|
| Plan | `Parallel Seq Scan` sur chaque partition | `Bitmap Index Scan` sur chaque partition |
| Lignes lues pour en garder 314 | ~500 000 | 314 |
| Pages lues (8 Ko) | 4 276, dont 1 367 depuis le disque | 360, toutes en cache |
| Temps d'exécution | 40,6 ms | 1,1 ms |

| Commandes du mois en cours | Partitions lues |
|---|---|
| Filtre sur `created_at` | 1 sur 26, soit 24 586 lignes au lieu de 500 055 |

## Ce qui est solide

- **Le changement de plan est net.** On passe de « je lis tout et je filtre » (`Rows Removed by Filter: 79 348…`) à « je vais directement aux 314 lignes ».
- **Les pages lues sont l'argument principal** : 4 276 contre 360, soit 12 fois moins. Contrairement au temps, ce chiffre ne dépend pas de la machine.
- **Le gain grandit avec le volume.** Un parcours complet coûte proportionnellement au nombre de lignes. L'index ne coûte qu'en fonction des lignes trouvées. À 5 millions de commandes, la version sans index serait environ 10 fois plus lente, alors que celle avec index ne changerait presque pas.
- **L'élagage des partitions se lit directement dans le plan** : une seule partition apparaît.

## Les questions à préparer

1. **« 40 ms, c'est déjà rapide, à quoi bon ? »**
   À cause de l'échelle, et de la concurrence. Cent clients qui consultent leur historique en même temps, sans index, ce sont cent lectures complètes de la table.

2. **« La comparaison des temps est-elle équitable ? »**
   Pas tout à fait, et il vaut mieux le dire toi-même. Le premier passage a lu 1 367 pages depuis le disque (`read=1367`) : la lecture préalable n'a pas tout mis en cache, car PostgreSQL limite le cache utilisé par un parcours complet. Une partie des 40 ms vient donc du disque. C'est pour cela que l'argument principal doit être le nombre de pages, pas le temps. Pour corriger ça, il suffit de lancer deux fois la requête sans index et de garder la seconde mesure.

3. **« Pourquoi un Bitmap Heap Scan et pas un Index Scan ? »**
   Les 314 commandes sont réparties sur environ 300 pages différentes. PostgreSQL collecte d'abord leurs emplacements grâce à l'index (le bitmap), puis lit les pages dans l'ordre du disque. C'est plus efficace qu'aller et venir entre l'index et la table pour chaque ligne.

4. **« Le temps de préparation (2,2 ms) dépasse le temps d'exécution (1,1 ms) : pourquoi ? »**
   C'est la vraie faiblesse de la mesure. `customer_id` n'est pas la clé de partition, donc PostgreSQL doit planifier un passage sur **les 26 partitions**, dont 12 sont vides parce que créées à l'avance. La réponse est un compromis : partitionner par date accélère les requêtes par période (rapports, lake) mais ralentit un peu celles qui ne filtrent pas sur la date. On pourrait limiter l'avance à 3 mois au lieu de 12 pour réduire le nombre de partitions. Ce point a sa place dans l'ADR-003.

5. **« Pouvait-on faire mieux ? »**
   Oui. Un index composite `(customer_id, created_at)` renverrait les lignes déjà triées, et l'étape `Sort` disparaîtrait. Le gain est faible ici (tri de 42 Ko en mémoire), mais le dire montre que tu comprends ce que fait l'index.

Envie que je modifie le script pour la question 2 ? Il ferait deux mesures sans index et n'afficherait que la seconde, et il donnerait un résumé court au lieu des 170 lignes de plan.