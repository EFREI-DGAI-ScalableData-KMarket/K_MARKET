##  Ce que prouve « PASSED »

Chaque test envoie une vraie requête HTTP à la pile lancée (gateway → service → PostgreSQL), puis **vérifie le résultat** avec des `assert` : le code HTTP, le contenu de la réponse et, souvent, l'état de la base avant et après l'appel. Un test est `PASSED` quand toutes ses vérifications sont vraies.

Ce que les 21 `PASSED` démontrent :
- **le service respecte son contrat** (`inventory.openapi.yaml`) : codes de retour, format des réponses et des erreurs ;
- **les règles métier exigées par le sujet sont tenues** : idempotence, compensation de la Saga, RBAC, absence de survente ;
- **le résultat est reproductible** : n'importe qui relance `make test-stock` et obtient le même verdict.

## Les 21 tests

**Sécurité (5)**

| Test | Ce qu'il vérifie | Pourquoi c'est important |
|---|---|---|
| `test_health_sans_jeton` | `/v1/health` répond 200 sans jeton | La sonde de santé doit rester accessible |
| `test_401_sans_jeton` | Une route protégée sans jeton renvoie 401 | Aucune route métier n'est ouverte |
| `test_401_jeton_mal_signe` | Un jeton signé avec un autre secret renvoie 401 | Impossible de fabriquer un faux jeton |
| `test_401_jeton_expire` | Un jeton expiré renvoie 401 | Les jetons courts (15 min) sont bien appliqués |
| `test_403_role_insuffisant` | `analyst` est refusé sur les entrepôts, `service_account` aussi, et `logistics` ne peut pas réserver | Le RBAC est appliqué route par route |

**Entrepôts et lecture du stock (4)**

| Test | Ce qu'il vérifie |
|---|---|
| `test_warehouses` | Chaque entrepôt a les champs du contrat ; `fill_rate` = `used_units / capacity_units` ; `near_full` au-delà de 90 % ; l'entrepôt à 91 % du seed déclenche bien l'alerte |
| `test_stock_summary` | Le total d'un produit est égal à la somme de ses entrepôts |
| `test_stock_produit_inconnu` | Un produit inconnu renvoie 404 au format `Error` |
| `test_stock_par_entrepot` | La ligne d'un entrepôt donné est la bonne ; un entrepôt inconnu renvoie 404 |

**Création du stock, appelée par le catalogue (3)**

| Test | Ce qu'il vérifie |
|---|---|
| `test_create_stock` | Une création renvoie 201 ; la même création une seconde fois renvoie 409 |
| `test_create_stock_entrepot_inconnu` | Un entrepôt inexistant renvoie 404 |
| `test_create_stock_corps_invalide` | Un champ manquant ou une quantité négative renvoie 400 |

**Saga : réservation du stock pour une commande (6)**

| Test | Ce qu'il vérifie | Lien avec le sujet |
|---|---|---|
| `test_cycle_reserve_commit_release` | Réserver baisse le stock de 3. Un **rejeu** ne le baisse pas une deuxième fois. `commit` passe la réservation en `committed`. `release` rend le stock. Un deuxième `release` ne le rend pas deux fois. Un `commit` après libération est refusé (409) | Idempotence et compensation de la Saga |
| `test_release_une_ligne` | On peut libérer un seul produit d'une commande ; la commande ne peut plus être confirmée ensuite | Compensation partielle |
| `test_reserve_stock_insuffisant_tout_ou_rien` | Si un produit manque, **rien** n'est réservé, même le produit disponible ; la réponse 422 détaille ce qui manque | Atomicité de la transaction |
| `test_reserve_corps_invalide` | Une quantité à 0 ou une liste vide renvoie 400 | Validation des entrées |
| `test_commit_release_commande_inconnue` | `commit` ou `release` sur une commande inconnue renvoie 404 | — |
| `test_reserve_concurrent_sans_survente` | 8 commandes simultanées veulent chacune tout le stock d'un entrepôt. Le stock ne passe jamais sous zéro, et la baisse correspond exactement au nombre de réservations acceptées | Cohérence sous concurrence |

**Expéditions, onglet Logistique (3)**

| Test | Ce qu'il vérifie |
|---|---|
| `test_cycle_expedition` | Création en `preparing`. Doublon refusé. Passage direct à `delivered` refusé. `shipped` remplit `shipped_at`. Retour à `preparing` refusé. `delivered` remplit `delivered_at` |
| `test_expedition_erreurs` | Entrepôt ou expédition inconnus : 404 ; statut inventé : 400 |
| `test_liste_expeditions_filtre_et_pagination` | Le filtre par statut fonctionne. La page 1 et la page 2 ne se recouvrent pas. Les plus récentes arrivent en premier. Une `limit` au-delà de 200 est refusée |