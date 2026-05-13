# Guia de Instalação — Ubuntu 24.04

> **Objetivo**: ter `./mvnw compile` verde e `docker run hello-world` funcionando ao final deste guia.
>
> **Tempo estimado**: 60-90 minutos (depende da velocidade da rede para downloads).
>
> **Pré-requisitos**: Ubuntu 24.04 LTS (Noble), 8 GB+ RAM, 10 GB+ livres em disco, conexão com internet.
>
> Para visão geral rápida, ver `COMECE_AQUI.md` §1. Este guia é o detalhado, com troubleshooting.

---

## Sumário

0. [Verificação prévia do sistema](#0-verificação-prévia-do-sistema)
1. [Instalar SDKMAN](#1-instalar-sdkman)
2. [Instalar GraalVM 21 LTS](#2-instalar-graalvm-21-lts)
3. [Instalar Maven 3.9](#3-instalar-maven-39)
4. [Instalar Docker Engine](#4-instalar-docker-engine)
5. [Verificar k6](#5-verificar-k6)
6. [Validar dataset oficial da Rinha](#6-validar-dataset-oficial-da-rinha)
7. [Configurar IDE (IntelliJ ou VS Code)](#7-configurar-ide)
8. [Validação final do setup](#8-validação-final-do-setup)
9. [Primeiro `./mvnw compile`](#9-primeiro-mvnw-compile)
10. [Troubleshooting comum](#10-troubleshooting-comum)
11. [Próximos passos](#11-próximos-passos)

---

## 0. Verificação prévia do sistema

Antes de instalar nada, confirme o ambiente:

```bash
# Sistema operacional — deve mostrar Ubuntu 24.04
lsb_release -a

# Arquitetura — deve ser x86_64 (ou amd64)
uname -m

# Espaço em disco — pelo menos 10 GB livres em ~/
df -h ~

# Memória RAM total — 8 GB recomendado
free -h

# Git já instalado?
git --version

# Curl e wget?
curl --version | head -1
wget --version | head -1

# Bash (precisa pra SDKMAN)
echo $SHELL
```

Se algum estiver faltando:
```bash
sudo apt update
sudo apt install -y curl wget git unzip zip
```

---

## 1. Instalar SDKMAN

**Por que usar SDKMAN**: gerencia múltiplas versões de Java e Maven sem conflito com Java instalado pelo apt. Permite trocar de versão por projeto.

### 1.1 Instalar

```bash
curl -s "https://get.sdkman.io" | bash
```

Vai imprimir algo como:
```
All done!
You are subscribed to the STABLE channel.
Please open a new terminal, or run the following in the existing one:
    source "$HOME/.sdkman/bin/sdkman-init.sh"
```

### 1.2 Carregar na sessão atual

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

### 1.3 Validar

```bash
sdk version
```

Deve mostrar algo como:
```
SDKMAN!
script: 5.18.2
native: 0.5.0
```

Se falhar com "command not found": ver §10 troubleshooting.

---

## 2. Instalar GraalVM 21 LTS

**Por que GraalVM**: nosso runtime de produção (Native Image, AOT compilation, Vector API estável). LTS = Long Term Support, recebe patches por anos.

**Por que 21 e não 25 (que está instalado pelo apt)**:
- LTS oficial.
- Vector API (incubator) estável e bem suportada por GraalVM.
- Native Image maduro (sem regressões recentes em SIMD intrinsics).
- Versões 22/23 tiveram problemas conhecidos com Vector API em Native (§12.1 do RINHA_PLAN.md).

### 2.1 Listar versões disponíveis

```bash
sdk list java | grep -E "21\..*-graal"
```

Vai mostrar várias variantes. Procure por `21.0.x-graalce` (Community Edition) ou `21.0.x-mandrel` (Red Hat fork focado em Native Image).

> **Nota**: o SDKMAN remove versões antigas do índice. Em 2026-05 a única CE 21 listada é `21.0.2-graalce` (a `21.0.4-graalce` foi removida). Se a 21.0.2 também sair, rode `sdk list java | grep graalce` e use a `21.0.x-graalce` LTS mais recente listada. Se preferir a versão mais nova com licença Oracle, use `21.0.11-graal` (GraalVM Oracle, não CE).

### 2.2 Instalar

```bash
# Opção A (recomendada): Community Edition (CE) — versão 21 LTS atual no índice SDKMAN
sdk install java 21.0.2-graalce

# Opção B (alternativa): Mandrel (Red Hat fork, otimizado para Native)
sdk install java 21.0.2-mandrel
```

Aceite com `y` quando perguntar "Do you want java 21 to be set as default?". Se passou, force depois:

```bash
sdk default java 21.0.2-graalce
```

### 2.3 Validar

```bash
java -version
```

Deve imprimir algo do tipo (formato GraalVM CE 21.0.2):
```
openjdk version "21.0.2" 2024-01-16
OpenJDK Runtime Environment GraalVM CE 21.0.2+13.1 (build 21.0.2+13-jvmci-23.1-b30)
OpenJDK 64-Bit Server VM GraalVM CE 21.0.2+13.1 (build 21.0.2+13-jvmci-23.1-b30, mixed mode, sharing)
```

Confirme:
- `21.0.x` (não 25!).
- "GraalVM" aparece em algum lugar (CE para Community Edition, ou Oracle se você escolheu `21.0.11-graal`).

```bash
echo $JAVA_HOME
which java
```

`JAVA_HOME` deve apontar para `~/.sdkman/candidates/java/current`.

### 2.4 Validar `native-image`

```bash
native-image --version
```

Deve imprimir versão do builder Native Image. Se errar com "command not found", instalar via:
```bash
gu install native-image
```
(Em GraalVM CE, `native-image` vem por default a partir de 21.)

---

## 3. Instalar Maven 3.9

**Por que Maven**: build tool, gerencia dependências e plugins. Já familiar no ecossistema Java. O profile `native` no `pom.xml` usa `org.graalvm.buildtools:native-maven-plugin`.

### 3.1 Instalar via SDKMAN

```bash
sdk install maven 3.9.9
```

### 3.2 Validar

```bash
mvn -version
```

Deve mostrar:
```
Apache Maven 3.9.9 (...)
Maven home: /home/arthurd3/.sdkman/candidates/maven/3.9.9
Java version: 21.0.2, vendor: GraalVM Community, runtime: /home/arthurd3/.sdkman/candidates/java/21.0.2-graalce
```

A linha "Java version" precisa mostrar **21**, não 25. Se mostrar 25, ver §10 troubleshooting.

### 3.3 Cache do Maven

A primeira execução baixa ~200 MB de plugins/dependências para `~/.m2/repository/`. É normal demorar 2-5 minutos.

---

## 4. Instalar Docker Engine

**Por que Docker**: rodar API + HAProxy em containers (Onda 4 do plano). Docker Engine é mais leve que Docker Desktop em Linux — não precisa de VM.

### 4.1 Instalar

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
```

Versões instaladas em Ubuntu 24:
- `docker.io` → Docker Engine 26+
- `docker-compose-plugin` → `docker compose` v2

### 4.2 Adicionar usuário ao grupo docker

```bash
sudo usermod -aG docker $USER
```

**Importante**: faça **logout/login** (ou `newgrp docker` numa nova shell) para aplicar.

### 4.3 Iniciar daemon (se necessário)

```bash
sudo systemctl enable --now docker
```

### 4.4 Validar

```bash
docker --version
docker compose version
docker run --rm hello-world
```

`hello-world` deve imprimir "Hello from Docker!".

Se der "permission denied": ver §10 troubleshooting.

---

## 5. Verificar k6

`k6` já está instalado no seu sistema em `/snap/bin/k6`.

### 5.1 Validar

```bash
k6 version
```

Deve mostrar `k6 v0.50+`.

### 5.2 Caso não tenha

```bash
sudo snap install k6
```

---

## 6. Validar dataset oficial da Rinha

O repositório oficial da Rinha 2026 tem o dataset (`references.json.gz`, ~16 MB) em `resources/`. Já está clonado em:

```
/home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026/
```

### 6.1 Verificar

```bash
ls -lh /home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026/resources/
```

Deve mostrar:
- `references.json.gz` (~16 MB)
- `mcc_risk.json`
- `normalization.json`
- `example-payloads.json`
- `example-references.json`

### 6.2 Caso esteja faltando

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/
git clone https://github.com/zanfranceschi/rinha-de-backend-2026
```

---

## 7. Configurar IDE

### 7.1 IntelliJ IDEA Community (recomendado)

```bash
sudo snap install intellij-idea-community --classic
```

Após abrir IntelliJ:

1. **File** → **Open** → selecionar `/home/arthurd3/Desktop/RINHA-BECK-END/fraud-detection/fraudAPI/`.
2. **File** → **Project Structure** → **Project**:
   - SDK: clique em "Add SDK" → "Add JDK from disk" → navegue para `~/.sdkman/candidates/java/21.0.2-graalce`.
   - Language level: 21.
3. **File** → **Settings** → **Build, Execution, Deployment** → **Compiler** → **Java Compiler**:
   - "Additional command line parameters": `--enable-preview --add-modules jdk.incubator.vector`
4. Recompile com `Ctrl+F9`.

### 7.2 VS Code (alternativa mais leve)

```bash
sudo snap install code --classic
```

Instalar extensão: **Extension Pack for Java** (Microsoft).

Settings (`Ctrl+,` → JSON):
```json
{
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-21",
      "path": "/home/arthurd3/.sdkman/candidates/java/21.0.2-graalce",
      "default": true
    }
  ],
  "java.compile.nullAnalysis.mode": "automatic"
}
```

---

## 8. Validação final do setup

Rode tudo de uma vez:

```bash
echo "=== SDKMAN ===" && sdk version
echo "=== Java ===" && java -version 2>&1
echo "=== Maven ===" && mvn -version | head -3
echo "=== Docker ===" && docker --version
echo "=== Docker Compose ===" && docker compose version
echo "=== k6 ===" && k6 version
echo "=== Dataset ===" && ls -lh /home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026/resources/references.json.gz
echo "=== Pom (esperado: Java 23 atual) ===" && grep -E "source|target" /home/arthurd3/Desktop/RINHA-BECK-END/fraud-detection/fraudAPI/pom.xml
```

Esperado:
- Java mostra "GraalVM 21".
- Maven mostra Java 21 também.
- Docker e Compose funcionam.
- k6 OK.
- Dataset 16 MB.
- pom.xml ainda em Java 23 (vai consertar na Onda 0).

---

## 9. Primeiro `./mvnw compile`

### 9.1 Antes da Onda 0

O `pom.xml` está em Java 23 e sem profile native (estado inicial). Tentar compilar agora:

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraud-detection/fraudAPI/
mvn clean compile
```

**Resultado esperado**: pode passar (Java 21 consegue compilar source 23 com flag `--enable-preview`) ou pode dar erro de versão.

Se passar: ótimo, continue para Onda 0.

Se der `bad source value: 23`: também ótimo — vai consertar na Onda 0 trocando para 21.

### 9.2 Após a Onda 0 (depois de editar pom.xml)

Quando a Onda 0 for executada (ver `RINHA_PLAN.md` §9.0 ou `COMECE_AQUI.md` §4):
- `pom.xml` em Java 21.
- Profile `native` configurado.
- Maven Wrapper gerado (`mvnw`).

```bash
./mvnw clean compile
```

Vai funcionar sem warnings de versão.

---

## 10. Troubleshooting comum

### 10.1 `sdk: command not found`

Causa: SDKMAN init não rodou na shell atual.

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

Se quer que carregue automaticamente em toda shell:
```bash
echo '[[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"' >> ~/.bashrc
source ~/.bashrc
```

### 10.2 `java -version` ainda mostra OpenJDK 25

Causa: o java do apt vem antes do java do SDKMAN no `$PATH`.

```bash
sdk default java 21.0.2-graalce
sdk use java 21.0.2-graalce        # na sessão atual
echo $PATH | tr ':' '\n' | head -5  # SDKMAN deve aparecer primeiro
```

Se persistir:
```bash
sudo update-alternatives --config java
# escolher manualmente o caminho do GraalVM
```

### 10.3 Docker `permission denied`

Sintoma:
```
docker: Got permission denied while trying to connect to the Docker daemon socket
```

Causa: usuário não está no grupo `docker`.

```bash
sudo usermod -aG docker $USER
# logout + login OU
newgrp docker
```

### 10.4 Docker daemon não está rodando

Sintoma:
```
Cannot connect to the Docker daemon at unix:///var/run/docker.sock
```

```bash
sudo systemctl start docker
sudo systemctl enable docker
```

### 10.5 `exec format error` em algum binário

Causa: arquitetura errada. Verifique:
```bash
arch    # deve ser x86_64
```

Se for `aarch64` (ARM), você precisa imagens Docker `linux/arm64` — mas a Rinha exige `linux/amd64`. Esse setup não vai funcionar em Mac M1/M2 sem WSL/Rosetta.

### 10.6 Maven baixando 200 MB de dependências

Normal na primeira vez. Maven popula `~/.m2/repository/` com plugins (`maven-compiler-plugin`, `native-maven-plugin`, etc.). Próximas builds usam o cache.

### 10.7 IntelliJ não acha o GraalVM

Manualmente:
1. **File** → **Project Structure** → **SDKs** → `+` → "Add JDK".
2. Apontar para `/home/arthurd3/.sdkman/candidates/java/21.0.2-graalce`.
3. Aplicar como Project SDK.

### 10.8 `native-image: command not found`

```bash
# Em GraalVM CE pré-instalado, native-image vem junto.
# Se não, instalar via gu (GraalVM Updater):
gu install native-image
```

### 10.9 SDK install falha com "Stop! java is not a valid candidate"

Cache stale. Atualizar:
```bash
sdk update
sdk selfupdate
```

### 10.10 Dataset `references.json.gz` ausente

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/
git clone https://github.com/zanfranceschi/rinha-de-backend-2026
ls rinha-de-backend-2026/resources/
```

---

## 11. Próximos passos

Setup pronto. Agora:

1. Ler `COMECE_AQUI.md` §2 (trilha de leitura).
2. Ler `docs/RINHA_PLAN.md` §1 + §3 + §9.0 (problema, orçamento, Onda 0).
3. Executar Onda 0 conforme `COMECE_AQUI.md` §4 (corrigir `pom.xml` para Java 21, adicionar profile `native`, gerar Maven wrapper).
4. Validar `./mvnw compile` verde.
5. Avançar para Onda 1.

**Para entender por que cada tecnologia foi escolhida**: ver `docs/tecnologias/00-INDEX.md` — catálogo com explicação completa de cada ferramenta.

---

**Tudo pronto?** Vá para `COMECE_AQUI.md` §4 e comece a Onda 0.
