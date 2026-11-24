#!/usr/bin/env bash
# Génère un .env à partir de .env.example : secrets aléatoires (…PASSWORD, …_KEY, …SECRET), autres valeurs reprises.
# Usage : bash scripts/gen-env.sh > /chemin/du/fichier.env    (la CI s'en sert ; utile aussi pour une installation neuve)
# Attention : ne pas rediriger vers ton propre .env sans le vouloir, il serait remplacé.
set -euo pipefail

while IFS= read -r line || [ -n "$line" ]; do      # « || » : traite aussi une dernière ligne sans retour à la ligne
  case "$line" in ''|\#*) continue ;; esac         # lignes vides et commentaires
  key="${line%%=*}"
  case "$key" in
    *PASSWORD|*_KEY|*SECRET) echo "$key=$(openssl rand -hex 24)" ;;
    *) echo "$line" ;;
  esac
done < "${1:-.env.example}"