#!/usr/bin/env bash
# Release local, sem CI: verifica, testa, analisa e gera os pacotes DESTE sistema em dist/release/.
#   scripts/release.sh             release a sério: exige uma versão final no pom e o git sem alterações por gravar
#   scripts/release.sh --ensaio    aceita -SNAPSHOT e alterações por gravar (para experimentar)
#   scripts/release.sh --somas     só recalcula dist/release/SHA256SUMS (depois de juntar ali os pacotes de outros sistemas)
#
# Cada sistema gera os seus pacotes (o jpackage não faz compilação cruzada): corra este script no Linux, e outra vez
# no Windows (Git Bash), e junte os ficheiros na mesma pasta dist/release/. Ver RELEASE.md.
#   Linux:   .deb + pacote portátil .tar.gz       Windows: .msi (precisa do WiX 3) + pacote portátil .zip
#   macOS:   .dmg
set -euo pipefail
cd "$(dirname "$0")/.."

ENSAIO=0
SO_SOMAS=0
for arg in "$@"; do
    case "$arg" in
        --ensaio) ENSAIO=1 ;;
        --somas) SO_SOMAS=1 ;;
        -h|--ajuda) sed -n '2,11p' "$0"; exit 0 ;;
        *) echo "Opção desconhecida: $arg (use --ajuda)" >&2; exit 2 ;;
    esac
done

SAIDA=dist/release

soma() {
    if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi
}

somas() {
    ( cd "$SAIDA"
      : > SHA256SUMS
      for f in *; do
          [ "$f" = SHA256SUMS ] || soma "$f" >> SHA256SUMS
      done
      cat SHA256SUMS )
}

if [ "$SO_SOMAS" = 1 ]; then
    [ -d "$SAIDA" ] || { echo "Não existe $SAIDA." >&2; exit 1; }
    somas
    exit 0
fi

VERSAO=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
case "$VERSAO" in
    *-SNAPSHOT)
        if [ "$ENSAIO" = 0 ]; then
            echo "A versão $VERSAO é de desenvolvimento. Defina a final (mvn versions:set -DnewVersion=X.Y.Z && mvn versions:commit) ou use --ensaio." >&2
            exit 1
        fi ;;
esac
if git rev-parse --git-dir > /dev/null 2>&1 && [ -n "$(git status --porcelain)" ] && [ "$ENSAIO" = 0 ]; then
    echo "Há alterações por gravar no git: faça commit antes da release (ou use --ensaio)." >&2
    exit 1
fi

case "$(uname -s)" in
    Linux*) SO=linux; NATIVO=deb ;;
    Darwin*) SO=macos; NATIVO=dmg ;;
    MINGW*|MSYS*|CYGWIN*) SO=windows; NATIVO=msi ;;
    *) echo "Sistema não suportado: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) ARQ=x64 ;;
    aarch64|arm64) ARQ=arm64 ;;
    *) ARQ=$(uname -m) ;;
esac
BASE="Oficina-$VERSAO-$SO-$ARQ"

rm -rf dist
mkdir -p "$SAIDA"

echo "[1/4] Compilar e testar..."
mvn -B -q clean verify
echo "[2/4] Análise estática (SpotBugs + FindSecBugs)..."
mvn -B -q -Psecurity -DskipTests compile spotbugs:check

echo "[3/4] Instalador ($NATIVO)..."
if [ "$SO" = windows ] && ! command -v candle.exe > /dev/null 2>&1; then
    echo "      WiX Toolset 3 não encontrado (candle.exe fora do PATH): sem .msi; fica só o pacote portátil." >&2
else
    scripts/empacotar.sh --sem-testes --tipo "$NATIVO"
    mv dist/*."$NATIVO" "$SAIDA/$BASE.$NATIVO"
fi

echo "[4/4] Pacote portátil..."
scripts/empacotar.sh --sem-testes
case "$SO" in
    windows) jar --create --file "$SAIDA/$BASE-portatil.zip" --no-manifest -C dist Oficina ;;
    linux) tar -czf "$SAIDA/$BASE-portatil.tar.gz" -C dist Oficina ;; # o tar guarda as permissões (o jar não)
    *) echo "      (sem pacote portátil em $SO: use o instalador)" ;;
esac
rm -rf dist/Oficina

somas > /dev/null
echo
echo "Pronto: $SAIDA"
ls -lh "$SAIDA" | tail -n +2
