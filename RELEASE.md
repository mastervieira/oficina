# Como fazer uma release

## 1. Antes (na sua máquina)

```
mvn versions:display-dependency-updates versions:display-plugin-updates     # o que está desatualizado
NVD_API_KEY=... mvn -Psecurity -DskipTests org.owasp:dependency-check-maven:check   # CVEs (chave fora do repositório)
osv-scanner scan -r .                                                        # outra base de CVEs
mvn -Psecurity -DskipTests compile spotbugs:check                            # análise estática
```

Falsos positivos: ver `spotbugs-exclude.xml` e `dependency-check-suppressions.xml` (cada entrada com a razão).

## 2. Publicar

```
mvn versions:set -DnewVersion=1.1.0 && mvn versions:commit
git commit -am "Versão 1.1.0" && git tag v1.1.0
git push origin main v1.1.0
mvn versions:set -DnewVersion=1.2.0-SNAPSHOT && mvn versions:commit         # continuar o desenvolvimento
```

Ao receber a tag, o GitHub Actions (`.github/workflows/release.yml`) corre os testes e a análise estática, gera um
instalador por sistema (Linux `.deb`, Windows `.msi`, macOS `.dmg` Intel e ARM) e cria a release com `SHA256SUMS` e
`latest.properties`. A tag tem de ser igual à versão do pom, senão o fluxo pára.

As aplicações instaladas consultam `…/releases/latest/download/latest.properties` ao arrancar e **avisam** o
utilizador (não descarregam nada). Uma versão com sufixo (`1.2.0-rc1`) fica como pré-lançamento e não avisa ninguém.
Para desligar numa instalação: `-Doficina.atualizacoes=off`.

## 3. Assinatura (opcional, mas sem ela os sistemas avisam)

Sem segredos, os pacotes saem **sem assinatura**: o Windows mostra o aviso SmartScreen e o macOS pode bloquear a
aplicação. Para assinar, crie estes segredos em *Settings → Secrets and variables → Actions*:

| Segredo | Para quê |
|---|---|
| `MACOS_CERT_P12`, `MACOS_CERT_PASSWORD` | certificado "Developer ID Application" (.p12 em base64) e a sua palavra-passe |
| `MACOS_SIGNING_NAME` | o nome do certificado sem o prefixo (ex.: `Ana Silva (ABCDE12345)`) |
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` | notarização (palavra-passe específica da app) |
| `WINDOWS_CERT_PFX`, `WINDOWS_CERT_PASSWORD` | certificado de assinatura de código (.pfx em base64) e a sua palavra-passe |

## 4. O que está e não está testado

- **Testado aqui (Linux):** `scripts/empacotar.sh` (app-image e `.deb`), a app empacotada a arrancar e a consultar
  `latest.properties`, e todos os testes.
- **Não testado:** os workflows nunca correram (validados só com `actionlint`); Windows e macOS (instaladores,
  perfis do JavaFX, WiX); a assinatura e a notarização (os passos só correm se os segredos existirem; um JVM pode
  precisar de *entitlements* para ser notarizado).
- Os testes só correm em Linux: alguns assumem caminhos POSIX e links simbólicos.
- Ainda não há repositório remoto: crie-o no GitHub (`git remote add origin …`) antes da primeira tag.
