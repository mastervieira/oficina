#!/usr/bin/env bash
# Popula a base de dados da aplicação (na pasta de dados do utilizador, ver PastaDeDados) com dados fictícios para desenvolvimento:
# 100 máquinas (todos os estados), 500 localizações ("Móvel A · Prateleira 1 · Secção C") e 10 000 artigos,
# com fornecedores, stock, planos e intervenções.
#
# Há uma única base de dados. Feche a aplicação antes de correr.
#   scripts/seed.sh              popula a base se estiver vazia; se já tiver dados, recusa e não altera nada
#   scripts/seed.sh --recriar    guarda a base atual em backups/antes-do-seed-....db (ao lado da base) e gera tudo de novo
#   scripts/seed.sh --ajuda      todas as opções (--semente, --maquinas, --localizacoes, --artigos, [ficheiro.db])
# Depois, abrir a aplicação normalmente:  java --enable-native-access=ALL-UNNAMED -jar target/oficina.jar
set -euo pipefail
cd "$(dirname "$0")/.."

# --recriar tira a base do sítio: com a aplicação aberta, ela continuaria a usar o ficheiro antigo.
for arg in "$@"; do
    if [ "$arg" = "--recriar" ] && pgrep -f '^([^ ]*/)?java .*-jar .*oficina\.jar' > /dev/null 2>&1; then
        echo "A aplicação parece estar aberta (oficina.jar). Feche-a antes de usar --recriar." >&2
        exit 1
    fi
done

JAR=target/oficina.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f -print -quit)" ]; then
    echo "A compilar (o JAR não existe ou está desatualizado)..."
    mvn -q package -DskipTests
fi

exec java --enable-native-access=ALL-UNNAMED -cp "$JAR" pt.oficina.tools.SeedDados "$@"
