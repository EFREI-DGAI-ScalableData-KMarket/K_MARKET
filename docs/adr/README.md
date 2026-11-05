# ADR — fiches de décision

Une fiche par choix technique, une page maximum (au moins un ADR par volet).
Nom de fichier : `NNN-titre-court.md`.

## Modèle

```markdown
# ADR-NNN — <décision>

- Statut : proposée | acceptée | remplacée par ADR-XXX
- Date : AAAA-MM-JJ

## Contexte
Le besoin et les contraintes (volume, vitesse, exactitude, coût).

## Options
1. Option A — points forts / points faibles
2. Option B — points forts / points faibles

## Décision
La solution choisie et pourquoi (CAP/BASE : exactitude ou disponibilité ?).

## Conséquences
Ce que le choix apporte, ce qu'il coûte, les risques, le plan B.
```

## À écrire

| ADR | Volet | Sujet |
|---|---|---|
| 001 | 1 | PostgreSQL pour commandes, stock, paiements, auth (un schéma et un rôle par service) |
| 002 | 1 | MongoDB pour le catalogue (D1, validator `oneOf`) |
| 003 | 1 | Partitionnement et idempotence (D4) |
| 004 | 2 | Stockage objet et format de table (Delta ou Iceberg) |
| 005 | 3 | API gateway et authentification (D6) |
| 006 | 3 | Saga orchestrée par le service Commandes |
| 007 | 4 | Kafka + Spark Structured Streaming, niveau de garantie (exactly-once) |
| 008 | 1 | Écarts au schéma de données de référence |