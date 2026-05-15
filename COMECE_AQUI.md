# Comece Aqui — guia de pontapé inicial

> **Objetivo**: te tirar do "abri o repo, só tem `docs/` e `INSTALACAO.md` — e agora?" até o primeiro `./mvnw compile` verde com um projeto Maven recém-inicializado, em **2-4h** de setup + leitura focada. Depois disso você está pronto pra Onda 1.
>
> **Trilha aberta**: avance quando estiver pronto. Sem pressa, sem cronograma rígido.

---

## §0. Visão geral em 30 segundos

O que vai acontecer, em ordem:

1. **Setup do ambiente** (§1, §2) — SDKMAN, GraalVM 21, Maven, Docker, k6. Detalhes em `INSTALACAO.md`.
2. **Validar setup** (§3) — `java -version`, `mvn -version`, etc. todos passando.
3. **Completar o setup do projeto Maven** (§4) — `api/` já existe via IntelliJ; substituir `pom.xml` mínimo pelo template completo, trocar `Main.java` default pelo placeholder, gerar `mvnw`, primeiro `./mvnw compile` verde. **Esta é a Onda 0.**
4. **Leitura mínima** (§5) — 4 capítulos de `docs/CONCEITOS.md` + §1, §3, §9.1 de `docs/RINHA_PLAN.md`. ~3h.
5. **Onda 1 — esqueleto fim-a-fim** (§6) — criar 11 arquivos `.java`, validar com `curl` contra `example-payloads.json`. ~1-2 dias.
6. **Critérios de pronto** (§7) — só passa pra Onda 2 quando responder correto contra `example-references.json`.
7. **Quando travar** (§8) — mapa indexado pra cada tipo de dúvida.

Os anti-padrões da §9 são os 10 erros que custam caro depois — leia antes de começar a codar.

---

## §1. Mentalidade — leia antes de qualquer coisa

A Rinha 2026 é uma **competição de microsegundos**. A meta é p99 ≤ 1 ms num hardware modesto (Mac Mini Haswell 2014, 1 CPU compartilhada, 350 MB RAM totais).

Três princípios não-negociáveis:

1. **Perf-first**: cada decisão técnica é avaliada por impacto em p99/RAM/recall. Frameworks bonitos perdem para `byte[]` cru.
2. **By-hand**: parser HTTP, parser JSON, índice ANN, distance kernel — todos hand-rolled. Bibliotecas só para o que não importa (HAProxy como LB, Maven como build).
3. **Medir antes de otimizar**: Onda 1 vai ter p99 ~30 ms. Está certo. Você só otimiza o que está medido — caso contrário gasta tempo no lugar errado.

**Pra ter na cabeça**:

> 1 ms = 2.6 milhões de ciclos de CPU @ 2.6 GHz.
> 1 syscall ≈ 1.000 ciclos.
> 1 cache miss em L3 ≈ 50-200 ciclos.
> 1 GC pause em Native Image Serial ≈ 5-200 ms (mata p99 inteiro).

Cada microsegundo conta. Cada alocação no hot path é dívida.

---

## §2. Pré-requisitos de software

> **Para o passo-a-passo detalhado de instalação com troubleshooting, ver `INSTALACAO.md`**.
> Esta seção é só o resumo.

Estado real do seu sistema:

| Ferramenta | Versão alvo | Como instalar |
|---|---|---|
| **SDKMAN** | qualquer | `curl -s "https://get.sdkman.io" \| bash` + `source ~/.sdkman/bin/sdkman-init.sh` |
| **GraalVM 21 LTS** | 21.0.x-graalce | `sdk install java 21.0.2-graalce` |
| **Maven** | 3.9.9 | `sdk install maven 3.9.9` |
| **Docker** | 24+ | `sudo apt install -y docker.io docker-compose-plugin` |
| **k6** | 0.50+ | já em `/snap/bin/k6` (se faltar: `sudo snap install k6`) |
| **git** | qualquer | normalmente já instalado |

### 2.1 Sequência mínima

```bash
# 1. SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. GraalVM 21
# Se "21.0.2-graalce" não estiver disponível, rode `sdk list java | grep graalce`
# e use a 21.0.x-graalce LTS listada (ou 21.0.x-graal Oracle, se preferir).
sdk install java 21.0.2-graalce
sdk default java 21.0.2-graalce

# 3. Maven
sdk install maven 3.9.9

# 4. Docker
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER     # logout/login depois
sudo systemctl enable --now docker

# 5. k6 (caso precise)
sudo snap install k6
```

Tudo dando errado em algum passo? Vá direto pra `INSTALACAO.md` §10 (troubleshooting).

---

## §3. Validação do setup (5 min)

Antes de inicializar o projeto, rode esses comandos. **Todos** devem funcionar:

```bash
# Versões
sdk current java          # → 21.x.x-graalce
java -version 2>&1        # → "GraalVM" + "21"
mvn -version              # → Apache Maven 3.9.9, Java 21
docker --version          # → Docker 24+
docker compose version    # → 2.x
k6 version                # → k6 v0.50+

# Dataset oficial disponível
ls -lh /home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026/resources/references.json.gz
# → ~16 MB

# Repositório do projeto (ainda só docs)
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
ls
# → COMECE_AQUI.md  INSTALACAO.md  docs/
```

Se algo falhar, resolva antes de continuar. Se tudo passou, prossiga para §4.

---

## §4. Completar o setup do projeto Maven (Onda 0) 🟢

> **Em uma frase**: você já tem `api/` com subpacotes vazios (criado via IntelliJ), mas falta `pom.xml` completo, `Main.java` placeholder e Maven Wrapper — até `./mvnw compile` ficar verde.
>
> **Tempo estimado**: 30-60 minutos. **Risco**: 🟢.

O subdiretório `api/` já existe com os subpacotes `server/`, `json/`, `knn/`, `dataset/`, `controllers/`. Mas o `pom.xml` que o IntelliJ gerou está mínimo (sem plugins, sem profile `native`), o `Main.java` é o template default ("Hello and welcome!"), e o Maven Wrapper não foi gerado. Vamos completar o setup na estrutura exata que o `docs/RINHA_PLAN.md` §7 documenta.

### 4.1 Validar a estrutura de diretórios existente

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection

# Confirmar subpacotes em main (já criados via IntelliJ)
ls api/src/main/java/org/fraudDetection/
# → controllers/  dataset/  json/  knn/  server/

# Criar subpacote de testes se faltar
mkdir -p api/src/test/java/org/fraudDetection

cd api
```

Layout esperado após esse passo:

```
api/
└── src/
    ├── main/java/org/fraudDetection/
    │   ├── server/
    │   ├── json/
    │   ├── knn/
    │   ├── dataset/
    │   └── controllers/
    └── test/java/org/fraudDetection/
```

Subpacotes vazios são intencionais — vão ser populados ao longo das ondas (`docs/RINHA_PLAN.md` §7 mostra o destino final).

### 4.2 Substituir `pom.xml` mínimo pelo template completo (Java 21 + Vector API + profile native)

Substitua `api/pom.xml` (que o IntelliJ deixou só com `modelVersion`/`groupId`/`artifactId`/`version`) por **exatamente** este conteúdo:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.fraudDetection</groupId>
    <artifactId>api</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <native.maven.plugin.version>0.10.3</native.maven.plugin.version>
        <main.class>org.fraudDetection.Main</main.class>
    </properties>

    <build>
        <finalName>api</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                    <compilerArgs>
                        <arg>--add-modules</arg>
                        <arg>jdk.incubator.vector</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.1</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>${main.class}</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>native</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.graalvm.buildtools</groupId>
                        <artifactId>native-maven-plugin</artifactId>
                        <version>${native.maven.plugin.version}</version>
                        <extensions>true</extensions>
                        <executions>
                            <execution>
                                <id>build-native</id>
                                <goals>
                                    <goal>compile-no-fork</goal>
                                </goals>
                                <phase>package</phase>
                            </execution>
                        </executions>
                        <configuration>
                            <mainClass>${main.class}</mainClass>
                            <imageName>api</imageName>
                            <buildArgs>
                                <buildArg>--enable-preview</buildArg>
                                <buildArg>--add-modules=jdk.incubator.vector</buildArg>
                                <buildArg>--no-fallback</buildArg>
                                <buildArg>-H:+UnlockExperimentalVMOptions</buildArg>
                            </buildArgs>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

Pontos críticos desse `pom.xml`:

- **Zero dependências runtime** — by-hand é regra (§1.2 da Mentalidade).
- `--add-modules jdk.incubator.vector` no compiler-plugin habilita Vector API (`docs/tecnologias/03-vector-api.md`). Sem isso, `import jdk.incubator.vector.*` não compila.
- **`--enable-preview` está só no profile `native`**, não no compiler-plugin. Motivo: se você compilar com Java 25 (default Ubuntu 24), `--enable-preview` exige `source=25`, conflitando com `source=21`. No Java 21 GraalVM (HotSpot) você pode reativar adicionando `<arg>--enable-preview</arg>` em `<compilerArgs>` se precisar de algum recurso de preview do 21.
- Profile `native` fica aqui desde já — você não vai rodar agora, mas evita ter que mexer no `pom.xml` na Onda 5.

### 4.3 Substituir `Main.java` template do IntelliJ pelo placeholder

Substitua `api/src/main/java/org/fraudDetection/Main.java` (que o IntelliJ deixou como "Hello and welcome!" + loop 1-5) por:

```java
package org.fraudDetection;

public class Main {
    public static void main(String[] args) {
        System.out.println("api: placeholder (Onda 1 trara NioServer na porta 9999)");
    }
}
```

Função: ter **algo compilável** já. Esse arquivo vai ser totalmente reescrito na Onda 1 pra instanciar e rodar o `NioServer`.

### 4.4 Gerar Maven Wrapper

Wrapper permite rodar `./mvnw` em vez de depender do Maven instalado globalmente — útil em CI e em containers.

```bash
# IMPORTANTE: estar DENTRO de api/ — se rodar do fraudDetection/ ele gera o wrapper na pasta errada
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
pwd  # deve terminar com .../fraudDetection/api
mvn -N io.takari:maven:wrapper -Dmaven=3.9.9
```

Resultado: gera (dentro de `api/`) `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` e `.mvn/wrapper/maven-wrapper.jar`.

> Se você acidentalmente rodou do `fraudDetection/` antes, vai ter `mvnw` duplicado no diretório-pai. Limpe com `rm /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/{mvnw,mvnw.cmd} && rm -rf /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/.mvn`.

> **Pegadinha**: o `mvn` do sistema (`apt install maven` → 3.8.7) usa o Java padrão do Ubuntu 24 (Java 25). O wrapper em si só gera arquivos — o Java não importa nesse passo. Mas para o `./mvnw clean compile` da próxima seção:
> - **Com Java 25 do sistema**: o `pom.xml` desta seção compila direto (foi por isso que removemos `--enable-preview` do compiler-plugin).
> - **Com Java 21 GraalVM via SDKMAN** (recomendado p/ Onda 5 native): `sdk install java 21.0.2-graalce && sdk use java 21.0.2-graalce`. Veja `INSTALACAO.md`.

### 4.5 Primeiro `./mvnw compile`

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api   # SEMPRE rodar daqui
./mvnw -version       # Maven 3.9.9 + Java 21 (GraalVM) ou Java 25 (Ubuntu)
./mvnw clean compile  # deve passar; warning "using incubating module: jdk.incubator.vector" é esperado
```

**Critério de saída da Onda 0**:

- ✅ `./mvnw -version` mostra Maven 3.9.9 (Java 21 ou 25, OK ambos para Onda 0).
- ✅ `./mvnw clean compile` retorna `BUILD SUCCESS`.
- ✅ `java --add-modules jdk.incubator.vector -cp target/classes org.fraudDetection.Main` imprime `api: placeholder...`.
- ✅ `./mvnw package` gera `target/api.jar` que roda com `java --add-modules jdk.incubator.vector -jar target/api.jar`.
- ✅ `pom.xml` tem profile `native` configurado (não testar agora — só verificar que existe).

### 4.6 Commit inicial

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
git status                        # deve listar api/pom.xml, api/Main.java modificados + mvnw novos
git add api/
git commit -m "Onda 0: completa setup do api/ (pom.xml + Main.java placeholder + wrapper)"
```

**Pronto.** Você tem a fundação. Hora de aprender o que vai construir em cima dela.

---

## §5. Trilha de leitura (antes de codar a Onda 1)

Os três documentos em `docs/` são a sua biblioteca de referência. Você **não** precisa ler tudo antes de começar — só o suficiente pra Onda 1. O resto consulta sob demanda.

### 5.1 Caminho rápido (~3-4h leitura) — recomendado se você já fez backend antes

- [ ] `docs/RINHA_PLAN.md` §1 — entenda **o problema** (regras, contrato da API, dataset, pontuação).
- [ ] `docs/RINHA_PLAN.md` §3 — entenda **o orçamento** (1ms = 2.6M ciclos, dist por etapa).
- [ ] `docs/CONCEITOS.md` §1 (k-NN), §6 (NIO Selector), §9 (zero-allocation), §10 (HTTP/1.1 by-hand) — base mínima pra Onda 1.
- [ ] `docs/RINHA_PLAN.md` §9.1 — checklist completo da Onda 1.

Pode começar a Onda 1 assim que terminar essa lista.

### 5.2 Caminho completo (~8-12h leitura) — se você quer entender tudo antes

- [ ] `docs/RINHA_PLAN.md` §1 (problema completo, exemplos de pontuação)
- [ ] `docs/CONCEITOS.md` na ordem 1 → 11 (todos os 11 conceitos)
- [ ] `docs/RINHA_PLAN.md` §3, §4, §5 (orçamento, mapa de impacto, stack com 11 alternativas)
- [ ] `docs/RINHA_PLAN.md` §6 (arquitetura), §8 (pipeline byte-a-byte)
- [ ] `docs/RINHA_PLAN.md` §9 (roadmap completo das 6 ondas)
- [ ] `docs/tecnologias/00-INDEX.md` (catálogo das 14 tecnologias) e os arquivos individuais 01–14 conforme interesse
- [ ] `docs/IMPACTO.md` (tabela cruzada decisão × métrica)
- [ ] (Opcional) Paper Malkov-Yashunin sobre HNSW: https://arxiv.org/abs/1603.09320

Quando terminar, você consegue prever cada decisão antes mesmo de implementar. Mas demora.

### 5.3 Por onda — leitura mínima

Antes de cada onda, pelo menos:

| Onda | Leituras obrigatórias |
|---|---|
| **0** (setup) | (este `COMECE_AQUI.md` §4) |
| **1** (esqueleto) | `docs/CONCEITOS.md` §1, §6, §9, §10 + `docs/RINHA_PLAN.md` §9.1 |
| **2** (int8 + SIMD) | `docs/CONCEITOS.md` §4, §5, §8 + `docs/RINHA_PLAN.md` §9.2 |
| **3** (HNSW) | `docs/CONCEITOS.md` §2, §3 + paper Malkov-Yashunin + `docs/RINHA_PLAN.md` §9.3 |
| **4** (containers) | `docs/RINHA_PLAN.md` §9.4 + `docs/tecnologias/08-docker.md`, `09-distroless.md`, `07-haproxy.md` |
| **5** (Native + PGO) | `docs/CONCEITOS.md` §7, §11 + `docs/RINHA_PLAN.md` §9.5 + `docs/tecnologias/02-graalvm-native-image.md`, `14-pgo.md` |

---

## §6. Onda 1 — esqueleto fim a fim (estimado 1-2 dias) 🟡

> Detalhes completos em `docs/RINHA_PLAN.md` §9.1. Esta seção é o resumo executável.
>
> **Em uma frase**: pipeline funciona ponta a ponta com brute force float32. Validar correctness contra `example-payloads.json`.

### 6.1 Pré-requisitos

- Onda 0 (§4) concluída — `./mvnw compile` verde com `Main.java` placeholder.
- Capítulos lidos: `docs/CONCEITOS.md` §1, §6, §9, §10.

### 6.2 Ordem de criação dos arquivos (não pular)

> 📖 **Walkthrough hands-on dos arquivos #1-#5 + `HealthController` codando linha-a-linha**: ver `docs/TUTORIAL_SERVER_NIO.md`. Esse tutorial sai da `./mvnw compile` verde até `curl /ready` retornando `200 OK`, com explicações inline de cada conceito NIO.
>
> 📖 **Walkthrough hands-on dos arquivos #6-#11 (JSON parser → dataset → KNN → `FraudController`) + dispatch `POST /fraud-score`**: ver `docs/TUTORIAL_JSON_KNN.md`. Continua de onde o tutorial NIO parou e fecha a Onda 1 (`{approved, fraud_score}` correto vs os oráculos do `REGRAS_DE_DETECCAO.md`). Inclui a limpeza dos 4 bloqueadores (bug do `matchMethod` POST, typo `HttpResponserWriter`, `HealthController` em `server/`, prints no `Main`).

1. **`Main.java`** — substitui o placeholder. Lê porta do `args[0]` (default 9999), instancia `NioServer`, chama `start()`.
2. **`server/NioServer.java`** — Selector loop (accept → read → write). Single-thread reactor.
3. **`server/ConnectionState.java`** — buffers reutilizáveis por conexão (`readBuffer` 4096B direto, `writeBuffer` 512B direto, `queryVector[14]`).
4. **`server/HttpParser.java`** — parser HTTP/1.1 mínimo (method, path, content-length, offset do body).
5. **`server/HttpResponseWriter.java`** — respostas canned (200 OK fixo pra `/ready`, template pra `/fraud-score`).

> 🔍 **Test point 1**: depois desses 5 arquivos + um handler stub no controller — rodar `./mvnw package && java -jar target/api.jar 9999`, em outro terminal `curl http://localhost:9999/ready` deve retornar `200 OK`. **Não avançar até funcionar.**

6. **`json/FraudRequestParser.java`** — walker no buffer JSON, popula `queryVector[14]` direto. Sem objeto intermediário. Trata `last_transaction: null` com sentinela `-1`.
7. **`dataset/MmapDataset.java` v1** — lê `references.json.gz` para `float[][]` em heap (lento, 3M·14·4B = ~168 MB, mas correto). Vai ser substituído por mmap binário na Onda 2.
8. **`knn/DistanceFunctions.java` v1** — euclidiana float32 escalar (`for (i=0; i<14; i++) sum += (a-b)*(a-b)`).
9. **`knn/HnswIndex.java` v1** — stub fazendo **brute force linear** (varre 3M vetores, mantém top-5 por label). Vai demorar 30+ ms por request — tudo bem. **Esse é o baseline de correctness** que a Onda 3 (HNSW de verdade) vai validar contra.
10. **`controllers/FraudController.java`** — handler de `POST /fraud-score`. Amarra `FraudRequestParser` → `HnswIndex.search` → `HttpResponseWriter`.
11. **`controllers/HealthController.java`** — handler de `GET /ready`.

> 🔍 **Test point 2** (critério de saída): `curl -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' -d @/home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026/resources/example-payloads.json` deve retornar `{approved, fraud_score}` corretos contra `example-references.json`. **Critério de saída da Onda 1.**

### 6.3 Critério de saída

- ✅ `curl POST /fraud-score` com cada exemplo retorna `{approved, fraud_score}` corretos.
- ✅ `curl GET /ready` retorna 200.
- ✅ Roda local, sem container.
- ✅ Latência irrelevante (~30 ms p99 esperado).

### 6.4 Se algo der errado

- "Request fica pendurada" → keep-alive não está fechando read corretamente (ver `docs/RINHA_PLAN.md` §12.2).
- "JSON parse erro em null" → tratar `last_transaction: null` com sentinela `-1` (ver `docs/RINHA_PLAN.md` §1.4 + §9.1).
- "OOM ao carregar references.json.gz" → 284 MB descomprimido em heap explode. Carregar em chunks ou já partir pra Onda 2 (mmap int8).

---

## §7. Critérios de "pronto pra próxima onda"

| Onda | Pronto quando... |
|---|---|
| **0 → 1** | `./mvnw compile` passa, `pom.xml` tem profile `native`, `Main.java` placeholder roda |
| **1 → 2** | curl com `example-payloads.json` retorna respostas corretas (vs `example-references.json`) |
| **2 → 3** | dataset cabe em ~42 MB, JMH mostra SIMD speedup ≥ 4×, respostas batem com Onda 1 |
| **3 → 4** | recall HNSW ≥ 95% top-5 vs brute force, latência search ~200-500 µs |
| **4 → 5** | k6 oficial roda contra Docker compose, `final_score` baseline registrado, RAM total < 350 MB |
| **5 → fim** | Native binary < 80 MB, sem warmup observável, Vector API gerou AVX2 |

Resista à tentação de pular ondas. A Onda 3 (HNSW) só faz sentido com Onda 2 (int8 + SIMD) certa, que só faz sentido com Onda 1 (correctness baseline) verde.

---

## §8. Quando travar — mapa de socorro

| Tipo de dúvida | Vá em |
|---|---|
| "Como instalar/configurar uma ferramenta?" | `INSTALACAO.md` (passo-a-passo Ubuntu 24 com troubleshooting) |
| "O que é essa tecnologia? Por que escolhemos?" | `docs/tecnologias/00-INDEX.md` → arquivo da tech específica |
| "O que é esse termo?" | `docs/RINHA_PLAN.md` glossário (final) → linka pro `docs/CONCEITOS.md` correspondente |
| "Bug específico, sintoma X" | `docs/RINHA_PLAN.md` §12 (armadilhas indexadas por sintoma) |
| "Métrica estourou (p99/RAM/recall)" | `docs/IMPACTO.md` seção "Se métrica X estoura, investigue Y" |
| "Por que essa decisão e não outra?" | `docs/RINHA_PLAN.md` §5.X (alternativas com veredito) |
| "Como medir/validar X?" | `docs/RINHA_PLAN.md` §10 (métricas) e §13 (comandos) |
| "Trade-off entre X e Y" | `docs/IMPACTO.md` (tabela cruzada decisão × métrica) |
| "Conceito teórico (HNSW, SIMD, mmap...)" | `docs/CONCEITOS.md` §1-§11 |
| "Alternativa para X (Netty, Jackson, Spring...)" | `docs/tecnologias/00-INDEX.md` seção "rejeitadas" |
| "Não sei por onde começar" | volta aqui (este arquivo), §0 ou §10 |

Mantém esses arquivos abertos na IDE em abas separadas — você vai pular entre eles.

---

## §9. Anti-padrões iniciais (evitar)

Erros que parecem produtivos mas custam caro depois:

1. **Otimizar antes de medir** — Onda 1 vai ter p99 ~30 ms. Não tente acertar tudo antes do baseline correto. Você não sabe ainda onde está o gargalo real.

2. **Pular pra Native Image cedo** — Native Image tem build time longo (1-5 min) e debug doloroso (`gdb`/`perf` em vez de JFR). Trabalhe em HotSpot até a Onda 4 mostrar baseline.

3. **Cair na tentação do `com.sun.net.httpserver` "só pra validar"** — ele aloca 50-200 µs/request e vicia o layout do código. A Onda 1 já entra com NIO Selector raw. **Não passe pela tentação.** (`docs/RINHA_PLAN.md` §12.2)

4. **Tentar HNSW antes de brute force funcionar** — você precisa do baseline exato pra validar recall do HNSW. Pula essa etapa e fica adivinhando se HNSW está correto.

5. **Logs em hot path** — qualquer `System.out.println`, `logger.info`, `LoggerFactory.getLogger().debug(...)` por request mata p99. Logue só erros 5xx.

6. **Allocation no hot path** — `new ArrayList<>()`, `String.format`, `new HashMap<>`, `Double.parseDouble(String)`, regex no `/fraud-score` = pressão de GC = pause = tail explode. Tudo via `byte[]` + offsets. (`docs/CONCEITOS.md` §9)

7. **Ignorar `example-payloads.json`** — esse arquivo é a verdade pra correctness. Use desde o `curl` manual da Onda 1. Comparar contra `example-references.json` é gratuito e detecta regressão imediato.

8. **Tentar tudo de uma vez** — uma onda por vez. Cada onda tem critério de saída. Não saia da Onda 2 até dataset estar em 42 MB e SIMD speedup ≥ 4×.

9. **Esquecer de medir** — k6 é a fonte da verdade do score. JMH é fonte da verdade de microbenchmarks. Sem medição, nenhuma decisão é defensável.

10. **Pular o ResourceConfig do Native Image** — se você usar reflection acidentalmente (Jackson, alguma lib), Native vai explodir em runtime com `ClassNotFoundException`. Manter zero-reflection é mais barato que listar tudo em `reflect-config.json`.

---

## §10. Próxima ação concreta — em uma frase

**Estado atual** (presumido): você tem `docs/`, este `COMECE_AQUI.md`, e `api/` já criado via IntelliJ com subpacotes vazios — mas com `pom.xml` mínimo e `Main.java` template default. Falta completar o setup.

**O que fazer agora, em ordem**:

1. Se ainda não instalou as ferramentas: **vá pra `INSTALACAO.md`** e rode os passos 0–8.
2. Se já tem tudo instalado: **rode a validação da §3** deste arquivo.
3. Se a validação passou: **execute a §4** (completar setup do projeto Maven) até ter `./mvnw compile` verde.
4. Depois: **leia os 4 capítulos de §5.1** (`docs/CONCEITOS.md` §1, §6, §9, §10 + `docs/RINHA_PLAN.md` §1, §3, §9.1).
5. Quando entender, **comece a Onda 1** (§6).

Tempo estimado pro fim da Onda 0:
- Setup do ambiente (se faltando): **30-90 min** (depende da rede).
- Completar projeto (§4): **30-60 min**.
- Leitura mínima da §5.1: **3-4 horas**.

**Total: 4-6 horas até ter base sólida e estar pronto pra escrever o primeiro `.java` da Onda 1.**

---

## Referência rápida — comandos do dia-a-dia

```bash
# Compilar (HotSpot, default)
cd fraudDetection/api && ./mvnw clean compile

# Empacotar JAR
./mvnw package

# Rodar local (sem container) — após Onda 1
java -jar target/api.jar 9999

# Curl manual de correctness
curl http://localhost:9999/ready
curl -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d @../../rinha-de-backend-2026/resources/example-payloads.json

# k6 oficial (após Onda 4)
k6 run ../../rinha-de-backend-2026/test/test.js

# Resultado
cat ../../rinha-de-backend-2026/test/results.json | jq '.scoring.final_score'

# Build native (após Onda 5)
./mvnw -Pnative package
./target/api 9999
```

Lista completa de comandos: `docs/RINHA_PLAN.md` §13.

---

**Boa sorte.** Cada microsegundo conta. 🏁
