#!/usr/bin/env bash
# Gera o pacote da aplicação com o runtime do Java incluído (o utilizador não precisa de instalar Java).
#   scripts/empacotar.sh                    app-image: pasta dist/Oficina/ com o executável (qualquer sistema, sem ferramentas extra)
#   scripts/empacotar.sh --tipo deb         instalador do sistema; tipos: deb, rpm (Linux), msi, exe (Windows), dmg, pkg (macOS)
#   --sem-testes                            não corre os testes (a CI já os correu antes)
#   -- ARGS...                              o que vem a seguir passa tal e qual ao jpackage (ex.: opções de assinatura do macOS)
#
# O jpackage NÃO faz compilação cruzada: o pacote de cada sistema gera-se nesse sistema (e o JavaFX do jar também:
# ver os perfis javafx-* no pom.xml). Requisitos por tipo:
#   deb: dpkg-deb e fakeroot   rpm: rpmbuild   msi/exe: WiX Toolset 3   dmg/pkg: Xcode Command Line Tools
#   (macOS: assinar e "notarizar" com conta Apple Developer, senão o Gatekeeper bloqueia; Windows: sem assinatura
#   de código aparece o aviso SmartScreen.)
# URL_ATUALIZACOES=https://.../latest.properties (variável de ambiente) liga a verificação de novas versões na app.
# A versão vem do pom.xml (sem o sufixo -SNAPSHOT, que o jpackage não aceita). Os dados do utilizador ficam fora da
# instalação (ver PastaDeDados), por isso instalar uma versão nova por cima nunca toca na base de dados.
set -euo pipefail
cd "$(dirname "$0")/.."

TIPO=app-image
SEM_TESTES=0
EXTRA=()
while [ $# -gt 0 ]; do
    case "$1" in
        --tipo) TIPO="${2:?falta o tipo}"; shift 2 ;;
        --sem-testes) SEM_TESTES=1; shift ;;
        --) shift; EXTRA=("$@"); break ;;
        -h|--ajuda) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "Opção desconhecida: $1 (use --ajuda)" >&2; exit 2 ;;
    esac
done

command -v jpackage > /dev/null || { echo "jpackage não encontrado: use um JDK 25 completo (não só o JRE)." >&2; exit 1; }

# URL_ATUALIZACOES=https://.../latest.properties  liga a verificação de novas versões na app (ver VerificadorDeAtualizacoes)
MVN_OPCOES=()
if [ "$SEM_TESTES" = 1 ]; then
    MVN_OPCOES+=(-DskipTests)
fi
if [ -n "${URL_ATUALIZACOES:-}" ]; then
    MVN_OPCOES+=("-Datualizacoes.url=$URL_ATUALIZACOES")
fi
if [ "$SEM_TESTES" = 1 ]; then echo "A compilar..."; else echo "A compilar e testar..."; fi
# ${ARRAY[@]+"${ARRAY[@]}"}: funciona com arrays vazios também no bash 3.2 do macOS
mvn -q clean package ${MVN_OPCOES[@]+"${MVN_OPCOES[@]}"}

VERSAO_POM=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
VERSAO="${VERSAO_POM%-SNAPSHOT}"
if [ "$VERSAO" != "$VERSAO_POM" ]; then
    echo "Aviso: $VERSAO_POM é uma versão em desenvolvimento; o pacote leva a versão $VERSAO." >&2
fi

# Módulos do JDK que a aplicação usa (java.desktop: ImageIO; java.xml*, java.naming, ...: Apache POI; java.sql: SQLite;
# java.net.http: verificação de atualizações).
# O jdeps não serve de fonte aqui: sobre o jar com o JavaFX misturado devolve módulos que só existem em certos JDKs.
MODULOS=java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,java.xml,java.xml.crypto,jdk.unsupported

rm -rf dist
mkdir -p dist/entrada
cp target/oficina.jar dist/entrada/

OPCOES=()
case "$TIPO" in
    deb|rpm) OPCOES+=(--linux-package-name oficina --linux-shortcut --linux-menu-group Office) ;;
    # O UUID identifica a aplicação para as atualizações do Windows: nunca o mudar.
    msi|exe) OPCOES+=(--win-menu --win-shortcut --win-upgrade-uuid 919d153b-8421-432b-9d96-4287b670a18e) ;;
    dmg|pkg) OPCOES+=(--mac-package-identifier pt.oficina.app) ;;
esac

jpackage --type "$TIPO" --dest dist \
    --name Oficina --app-version "$VERSAO" --vendor Oficina --description "Gestão de máquinas, stock e manutenção" \
    --input dist/entrada --main-jar oficina.jar --main-class pt.oficina.Launcher \
    --add-modules "$MODULOS" --java-options "--enable-native-access=ALL-UNNAMED" \
    ${OPCOES[@]+"${OPCOES[@]}"} \
    ${EXTRA[@]+"${EXTRA[@]}"}

rm -rf dist/entrada
echo "Pronto:"
ls -1 dist
