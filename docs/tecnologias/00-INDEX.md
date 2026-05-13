# Tecnologias — Catálogo

> Cada arquivo deste diretório explica uma tecnologia em profundidade: **o que é**, **objetivo geral**, **pra que vamos usar no projeto**, **como funciona**, **alternativas** (com pros/contras), **pegadinhas** e **referências externas**.
>
> **Diferença entre este catálogo e outros docs**:
> - **`RINHA_PLAN.md` §5** — decisão compacta com tabela de veredito (escolhi X, rejeitei Y).
> - **`CONCEITOS.md`** — algoritmos abstratos (k-NN, HNSW, SIMD, mmap).
> - **Este diretório** — ferramentas concretas com explicação completa e referências externas.
>
> Use este catálogo quando quiser **entender uma tecnologia em profundidade** ou **descobrir alternativas**.

---

## Como ler

Cada arquivo é autocontido. Pode ler em qualquer ordem. Para se familiarizar com a stack inteira, leia na ordem dos números (linguagem → runtime → build → networking → containers → benchmark → otimização).

---

## Linguagem e runtime

| # | Tecnologia | Em uma frase |
|---|---|---|
| [01](01-java-21.md) | **Java 21 LTS** | Linguagem e plataforma. LTS estável com Vector API, virtual threads e records. |
| [02](02-graalvm-native-image.md) | **GraalVM Native Image** | Compila Java para binário nativo (AOT) — sem JVM, sem warmup, RSS menor. |
| [03](03-vector-api.md) | **Vector API (`jdk.incubator.vector`)** | API Java para SIMD (AVX2/AVX-512). 32× paralelo no distance kernel. |

## Build e dependências

| # | Tecnologia | Em uma frase |
|---|---|---|
| [04](04-maven.md) | **Maven** | Build tool. `pom.xml`, lifecycle, plugins, dependências. |
| [05](05-sdkman.md) | **SDKMAN** | Gerenciador de versões JVM/Maven. Sem conflito com apt. |

## Networking e HTTP

| # | Tecnologia | Em uma frase |
|---|---|---|
| [06](06-nio-selector.md) | **NIO Selector** | Multiplexing I/O em Java (epoll wrapper). Reactor pattern em 1 thread. |
| [07](07-haproxy.md) | **HAProxy** | Load balancer. Modo TCP single-thread para distribuir entre 2 instâncias. |

## Containers

| # | Tecnologia | Em uma frase |
|---|---|---|
| [08](08-docker.md) | **Docker Engine + Compose** | Runtime de containers + orquestração local. Padrão da Rinha. |
| [09](09-distroless.md) | **Distroless** | Imagem base mínima (~20 MB) com glibc, sem shell. |

## Observabilidade e benchmark

| # | Tecnologia | Em uma frase |
|---|---|---|
| [10](10-k6.md) | **k6** | Load tester oficial da Rinha. Scripts em JS, rampa de RPS. |
| [11](11-jmh.md) | **JMH** | Microbenchmark harness. Mede distance kernel sem ruído. |
| [12](12-jfr.md) | **JFR (Flight Recorder)** | Profiling embutido no HotSpot. Allocation, GC pause, CPU time. |
| [13](13-perf.md) | **perf (Linux)** | Profiling kernel-level. Único caminho para Native Image. |

## Otimização

| # | Tecnologia | Em uma frase |
|---|---|---|
| [14](14-pgo.md) | **PGO (Profile-Guided Optimization)** | Compilador otimiza com profile real. +10-30% throughput em Native. |

---

## Tecnologias rejeitadas (referência rápida)

Estas aparecem nas seções "Alternativas" dos arquivos correspondentes. Razão da rejeição em `RINHA_PLAN.md` §5.

| Tecnologia | Onde está mencionada | Por que rejeitamos (resumo) |
|---|---|---|
| **Netty / Vert.x** | [06-nio-selector.md](06-nio-selector.md) | Framework — viola "by-hand" |
| **Jackson / Gson** | [01-java-21.md](01-java-21.md) | Aloca Map/String, perde para hand-roll em schema fixo |
| **Spring Boot / Quarkus** | [02-graalvm-native-image.md](02-graalvm-native-image.md) | Overhead de framework + tempo de aprender Native config |
| **Gradle / Bazel** | [04-maven.md](04-maven.md) | Sem benefício prático aqui; Maven já familiar |
| **nginx / Caddy / Traefik** | [07-haproxy.md](07-haproxy.md) | Ok mas HAProxy é mais leve em modo TCP |
| **Lucene HNSW / JVector** | `CONCEITOS.md` §3 + `RINHA_PLAN.md` §5.5 | Heavy dependency, queremos layout binário customizado |
| **com.sun.net.httpserver** | [06-nio-selector.md](06-nio-selector.md) | Aloca 50-200 µs/request, vicia código |

---

## Cross-references

- Decisão final de cada escolha: **`../RINHA_PLAN.md` §5** (tabela compacta).
- Algoritmos por trás (k-NN, HNSW, SIMD, mmap, etc.): **`../CONCEITOS.md`**.
- Trade-offs decisão × métrica: **`../IMPACTO.md`**.
- Como instalar tudo: **`../../INSTALACAO.md`**.
- Pontapé inicial: **`../../COMECE_AQUI.md`**.
