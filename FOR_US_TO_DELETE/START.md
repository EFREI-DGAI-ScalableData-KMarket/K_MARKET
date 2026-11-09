make up                         # démarre les bases ; le seed ne fait rien si elles sont déjà remplies
docker compose ps               # postgres et mongo doivent être « healthy »

make reseed ORDERS=500000       # environ 1 min 30 : il faut du volume pour que l'EXPLAIN montre une différence
make explain

make backup
make restore-test


chmod +x scripts/*.sh