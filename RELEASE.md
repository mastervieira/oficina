# Como fazer uma release (local, sem CI)

Cada sistema gera os seus próprios pacotes: o `jpackage` não faz compilação cruzada. Corre-se o mesmo script no
Linux e no Windows, e juntam-se os ficheiros numa pasta para distribuir.

## 1. Preparar a versão

```
mvn versions:set -DnewVersion=1.1.0 && mvn versions:commit
git commit -am "Versão 1.1.0" && git tag v1.1.0
```

Antes da primeira release, e de tempos a tempos (ver também `spotbugs-exclude.xml` e `dependency-check-suppressions.xml`):

```
mvn versions:display-dependency-updates versions:display-plugin-updates     # o que está desatualizado
NVD_API_KEY=... mvn -Psecurity -DskipTests org.owasp:dependency-check-maven:check   # CVEs (chave fora do repositório)
osv-scanner scan -r .                                                        # outra base de CVEs
```

## 2. Gerar os pacotes

**No Linux:** `scripts/release.sh`
Testa, analisa (SpotBugs) e gera em `dist/release/`: o `.deb`, o pacote portátil `.tar.gz` e o `SHA256SUMS`.
Para experimentar sem versão final nem git limpo: `scripts/release.sh --ensaio`.

**No Windows** (a fazer uma vez por PC de build):
1. Instalar o JDK 25 completo (não só o JRE) e pô-lo no `PATH`/`JAVA_HOME`, o Maven 3.9.6 ou mais recente e o
   [Git para Windows](https://git-scm.com/download/win) (traz o Git Bash).
2. Para o `.msi`: instalar o [WiX Toolset 3.14](https://github.com/wixtoolset/wix3/releases) e pôr a pasta `bin`
   dele no `PATH`. Sem o WiX o script avisa e gera só o pacote portátil `.zip`.
3. No Git Bash, dentro do projeto: `scripts/release.sh`.

**Juntar:** copiar para a mesma pasta `dist/release/` os ficheiros do Windows e correr `scripts/release.sh --somas`
para refazer o `SHA256SUMS` com todos.

## 3. Distribuir

Copie a pasta `dist/release/` como quiser (pen USB, pasta partilhada, o seu site). Quem recebe pode confirmar o
ficheiro com `sha256sum -c SHA256SUMS`.

| Sistema | Como instalar |
|---|---|
| Linux (`.deb`) | `sudo apt install ./Oficina-X-linux-x64.deb` |
| Linux (portátil) | extrair o `.tar.gz` e correr `Oficina/bin/Oficina` |
| Windows (`.msi`) | duplo clique |
| Windows (portátil) | extrair o `.zip` e correr `Oficina\Oficina.exe` |

Os dados do utilizador ficam fora da instalação (`~/.local/share/Oficina` no Linux, `%APPDATA%\Oficina` no
Windows), por isso instalar uma versão nova por cima nunca toca na base de dados. Para atualizar, basta instalar o
pacote novo.

Os pacotes **não são assinados**: o Windows mostra o aviso SmartScreen ("Mais informações" → "Executar mesmo assim").
Assinar exige um certificado de assinatura de código (pago).

## 4. Avisos de atualização

Ficam desligados (não há servidor onde publicar o `latest.properties`). Para os ligar é preciso hospedar esse
ficheiro em **https** e compilar com `URL_ATUALIZACOES=https://.../latest.properties scripts/release.sh`. Ver
`VerificadorDeAtualizacoes`. Uma pasta partilhada ou um endereço http da rede local não são aceites.

## 5. O que está e não está testado

- **Testado (Linux):** `scripts/release.sh --ensaio` completo (testes, SpotBugs, `.deb`, pacote portátil e somas); a
  app empacotada arranca e cria a base de dados.
- **Não testado:** tudo o que é Windows (perfil `win` do JavaFX, `jpackage` com WiX, o `.zip`) e macOS.
- Os testes automáticos só foram corridos em Linux: alguns assumem caminhos POSIX e links simbólicos.

## Opcional: GitHub Actions

Em `.github/workflows/` há uma CI e uma release automática (Linux, Windows e macOS, com assinatura opcional por
segredos). Estão **desligadas** no repositório porque a conta do GitHub ficou bloqueada por um problema de
faturação, e nunca chegaram a correr. Para as voltar a ligar: resolver a faturação e
`gh workflow enable ci.yml` e `gh workflow enable release.yml`.
