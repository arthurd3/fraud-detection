# Rinha de Backend 2026 — Guia pedagógico de implementação

> **Stack**: Java 21 LTS · GraalVM Native Image · NIO Selector raw · HNSW hand-rolled · Vector API SIMD · int8 quantization · HAProxy TCP
>
> **Filosofia**: by-hand, perf-first, zero-frameworks. Cada microsegundo conta.
>
> **Última revisão**: 2026-05-04 — versão pedagógica, pré-Onda 0.

---

## Como usar este guia

Este documento é **estrutural** — diz na ordem o que fazer, qual decisão tomar, com que critério, e onde validar. Para conceitos teóricos profundos (k-NN, HNSW, SIMD, etc.), o material está em `CONCEITOS.md`. Para análise de impacto cruzado entre decisões e métricas, ver `IMPACTO.md`.

**Mapa dos três arquivos**:

| Arquivo | Conteúdo | Quando ler |
|---|---|---|
| `RINHA_PLAN.md` (este) | Decisões, ondas, comandos, armadilhas | Linear, do começo ao fim |
| `CONCEITOS.md` | Tutorial completo de cada conceito | Antes de cada onda (pré-requisitos listados) |
| `IMPACTO.md` | Tabela cruzada decisão × métrica | Quando uma métrica estoura ou ao priorizar otimização |

**Trilhas de leitura recomendadas**:

- **Primeira vez (não conhece HNSW/SIMD/Native Image)**: ler `CONCEITOS.md` 1→11 → voltar aqui → seção 1 → seção 9 (roadmap) na ordem.
- **Já entende os conceitos**: pular `CONCEITOS.md`, ler aqui linear, consultar `IMPACTO.md` conforme necessário.
- **Bug em uma onda**: seção 12 (armadilhas) com índice por sintoma.

**Etiquetas**:
- `[DECISÃO]` — escolha técnica feita, justificada, travada para a onda.
- `[CÓDIGO]` — instrução de implementação concreta.
- `[REFERÊNCIA]` — comando, recurso externo, layout binário.

---

## Sumário

1. [O que é a Rinha 2026](#1-o-que-é-a-rinha-2026) — `[REFERÊNCIA]`
2. [Pré-leitura obrigatória](#2-pré-leitura-obrigatória) — `[CONCEITO]`
3. [Orçamento de performance](#3-orçamento-de-performance) — `[REFERÊNCIA]`
4. [Mapa de impacto](#4-mapa-de-impacto) — `[REFERÊNCIA]`
5. [Stack escolhida — 11 camadas com alternativas](#5-stack-escolhida) — `[DECISÃO]`
6. [Arquitetura interna](#6-arquitetura-interna) — `[DECISÃO]`
7. [Estrutura do repositório](#7-estrutura-do-repositório) — `[CÓDIGO]`
8. [Pipeline da request — byte a byte](#8-pipeline-da-request) — `[CÓDIGO]`
9. [Roadmap por etapas](#9-roadmap-por-etapas) — `[CÓDIGO]`
10. [Métricas e checkpoints](#10-métricas-e-checkpoints) — `[REFERÊNCIA]`
11. [Decisões em aberto](#11-decisões-em-aberto) — `[DECISÃO]`
12. [Armadilhas conhecidas](#12-armadilhas-conhecidas) — `[REFERÊNCIA]`
13. [Comandos úteis](#13-comandos-úteis) — `[REFERÊNCIA]`
14. [Glossário](#14-glossário) — `[REFERÊNCIA]`
15. [Recursos externos](#15-recursos-externos) — `[REFERÊNCIA]`
16. [Apêndices](#16-apêndices) — `[REFERÊNCIA]`

---

## 1. O que é a Rinha 2026

A **Rinha de Backend 2026 (4ª edição)** é uma competição amistosa em que se constrói uma API de detecção de fraude usando busca vetorial. Para cada transação recebida, a API normaliza o payload em um vetor de 14 dimensões, busca no dataset de referência os 5 vetores mais próximos, calcula `fraud_score = num_fraudes / 5` e responde `approved = score < 0.6`.

### 1.1 Contrato da API (porta 9999)

| Endpoint | Comportamento |
|---|---|
| `GET /ready` | `2xx` quando pronto |
| `POST /fraud-score` | recebe payload de transação, retorna `{approved: bool, fraud_score: float}` |

### 1.2 Restrições críticas

| Restrição | Valor |
|---|---|
| CPU + RAM totais (todos os serviços somados) | **1 CPU + 350 MB** |
| Instâncias mínimas da API | 2 |
| Load balancer | Round-robin, sem lógica de negócio |
| Network mode | `bridge` (sem `host` / `privileged`) |
| Imagem Docker | linux-amd64, pública |
| Branches no repo | `main` (código) + `submission` (compose + binários) |

### 1.3 Dataset

- `references.json.gz` — 16 MB gzipado / **284 MB descomprimido** / 3 milhões de registros `{vector: float[14], label: "fraud" | "legit"}`.
- `mcc_risk.json` — score por MCC (default 0.5).
- `normalization.json` — constantes (max_amount=10000, max_installments=12, …).

### 1.4 Vetorização — 14 dimensões

| Idx | Dimensão | Fórmula | Range |
|---|---|---|---|
| 0 | `amount` | `clamp(tx.amount / 10000)` | [0,1] |
| 1 | `installments` | `clamp(tx.installments / 12)` | [0,1] |
| 2 | `amount_vs_avg` | `clamp((tx.amount / cust.avg_amount) / 10)` | [0,1] |
| 3 | `hour_of_day` | `hora_utc / 23` | [0,1] |
| 4 | `day_of_week` | `dia_semana / 6` (seg=0, dom=6) | [0,1] |
| 5 | `minutes_since_last_tx` | `clamp(min / 1440)` ou `-1` se null | [0,1] ∪ {-1} |
| 6 | `km_from_last_tx` | `clamp(km / 1000)` ou `-1` se null | [0,1] ∪ {-1} |
| 7 | `km_from_home` | `clamp(terminal.km_from_home / 1000)` | [0,1] |
| 8 | `tx_count_24h` | `clamp(cust.tx_count_24h / 20)` | [0,1] |
| 9 | `is_online` | `1` ou `0` | {0,1} |
| 10 | `card_present` | `1` ou `0` | {0,1} |
| 11 | `unknown_merchant` | `1` se merchant.id ∉ cust.known_merchants | {0,1} |
| 12 | `mcc_risk` | `mcc_risk.json[merchant.mcc]` (default 0.5) | [0,1] |
| 13 | `merchant_avg_amount` | `clamp(merchant.avg_amount / 10000)` | [0,1] |

### 1.5 Pontuação (-6000 a +6000)

```
Score final = score_p99 + score_det
```

**Latência (`score_p99`)**:
```
Se p99 > 2000ms:  score_p99 = -3000      ← corte
Senão:            score_p99 = 1000 × log10(1000 / max(p99_ms, 1))
                  Teto: +3000 (p99 ≤ 1ms)
```

Cada **10× de melhoria em p99 vale +1000 pts**.

**Detecção (`score_det`)**:
```
E   = 1×FP + 3×FN + 5×Err   (erros ponderados)
ε   = E / N                   (taxa)

Se (FP+FN+Err)/N > 15%:  score_det = -3000   ← corte
Senão:                   score_det = 1000 × log10(1/max(ε, 0.001)) - 300 × log10(1+E)
```

Pesos: FP=1 (legit bloqueado), FN=3 (fraude passou), Err=5 (HTTP 5xx).

### 1.6 Como o k6 mede

- Cenário `ramping-arrival-rate`.
- Rampa linear: 1 RPS → 900 RPS em 120s.
- 54.100 payloads pré-rotulados em `test/test-data.json` (seed 4242, 44.47% fraude).
- Timeout por request: 2001ms.

### 1.7 Hardware do teste

Mac Mini Late 2014 — 2.6 GHz Intel Core i5 (Haswell, 2 cores, 4 threads, AVX2), 8 GB RAM, Ubuntu 24.04, linux-amd64.

### 1.8 Pontuação-alvo

| Faixa | p99 | erros | Score |
|---|---|---|---|
| Mínimo aceitável | ~10 ms | poucos | +3000 |
| Top tier | ~1-3 ms | mínimos | +5000 |
| Maximizado | < 1 ms | quase zero | +5800 a +6000 |

### 1.9 Exemplos numéricos de pontuação

| FP | FN | Err | falhas/N | p99 | score_p99 | score_det | total |
|---|---|---|---|---|---|---|---|
| 0 | 0 | 0 | 0% | 1ms | +3000 | +3000 | **+6000** ✓ |
| 5 | 5 | 0 | 0.18% | 3ms | +2523 | +2001 | **+4524** |
| 0 | 0 | 0 | 0% | 100ms | +1000 | +3000 | **+4000** |
| 500 | 300 | 0 | 16% | 10ms | +2000 | -3000 | **-1000** ✗ corte |
| 0 | 0 | 5000 | 100% | 60s | -3000 | -3000 | **-6000** ✗ piso |

---

## 2. Pré-leitura obrigatória

Antes de tocar código, leia em `CONCEITOS.md` os tópicos abaixo. Cada onda do roadmap (seção 9) lista quais conceitos são pré-requisitos.

| Conceito | Quando aplica | Onda |
|---|---|---|
| 1. Vector Search e k-NN | sempre | 1+ |
| 2. ANN | escolha do algoritmo | 3 |
| 3. HNSW from scratch | implementar índice | 3 |
| 4. Quantização int8 | reduzir RAM, acelerar dist | 2 |
| 5. SIMD e Vector API | acelerar distance kernel | 2 |
| 6. NIO Selector | servir HTTP em 1 thread | 1 |
| 7. Native Image vs HotSpot | eliminar warmup | 5 |
| 8. mmap e Page Cache | dataset não-allocador | 2 |
| 9. Zero-allocation hot path | evitar GC pause | 1+ |
| 10. HTTP/1.1 by-hand | parsing eficiente | 1 |
| 11. PGO | última espremida | 5 |

---

## 3. Orçamento de performance

A meta é p99 ≤ 1 ms. Em **2.6 GHz**, 1 ms = **2.6 milhões de ciclos de CPU**. 1 ciclo ≈ 0.385 ns. Mesmo um cache miss em L3 (~20 ns) "queima" 50 ciclos. **Cada microsegundo conta**.

### 3.1 Distribuição por etapa do hot path

| Etapa | Latência alvo | Ciclos | Onde gastar |
|---|---|---|---|
| Aceitar conexão + parse HTTP | ~80 µs | ~210k | NIO read, parse method/path/content-length |
| Parse JSON + normalização (vetor 14D) | ~60 µs | ~155k | Walk no buffer com offsets fixos, clamp inline |
| Quantize query → int8 | ~5 µs | ~13k | Multiplicação por escala global |
| HNSW search (ef=50, ~1500 dists int8 SIMD) | ~230 µs | ~600k | Maior consumidor; AVX2 obrigatório |
| Contar labels + score | ~5 µs | ~13k | Bitset lookup |
| Serializar response + write | ~60 µs | ~155k | `approved` + `fraud_score` ASCII pré-canned |
| **Soma ideal** | **~440 µs** | **~1.15M ciclos** | sobra ~560 µs / ~1.45M ciclos para tail e GC |

### 3.2 Por que brute force NÃO fecha

- 3M × 14 dims × ~5 ns/op (SIMD perfeito) = **~210 ms** por request.
- AVX2 com int8 (32 lanes) acelera ~8×: **~26 ms**. Ainda 26× acima do budget. **HNSW é obrigatório**.

### 3.3 Memory bandwidth

- Dataset int8: 3M × 14 = 42 MB sequencial.
- RAM bandwidth Haswell @ 2.6 GHz: ~10-15 GB/s.
- Tocar todo o dataset = **3-4 ms** só de leitura.
- HNSW toca ~1500 vetores × 14 B = **21 KB** que cabe em L1/L2 depois do warmup.

### 3.4 Memória estimada (350 MB total)

| Serviço | RSS alvo | Razão |
|---|---|---|
| HAProxy | ~10 MB | Modo TCP, single-thread |
| API instância 1 | ~80 MB | Native binary + heap pequeno + dataset mmap |
| API instância 2 | ~80 MB | Idem |
| Páginas mmap shared (kernel) | ~42 MB | int8 + HNSW edges, contado por instância tocando |
| **Total** | **~210-260 MB** | Folga ~90-140 MB para spike de GC/tail |

> **cgroups** contam páginas RSS por container. mmap shared é contado em cada cgroup que toca.

---

## 4. Mapa de impacto

Tabela curta consolidada das 12 decisões mais importantes. Para a tabela completa com 17 decisões + notas detalhadas + diagnóstico "se métrica X estoura, investigue Y", ver **`IMPACTO.md`**.

| Decisão | p99 | RAM | Recall | Throughput |
|---|---|---|---|---|
| HNSW vs brute force | 🟢🟢🟢 | 🟡 | 🟡 | 🟢🟢 |
| int8 vs float32 | 🟢 | 🟢🟢🟢 | 🟡 | 🟢 |
| Native Image vs HotSpot | 🟢🟢 (warmup) | 🟢🟢 | - | 🟡 |
| NIO raw vs httpserver | 🟢🟢 | 🟢 | - | 🟢🟢 |
| JSON hand-roll vs Jackson | 🟢🟢 | 🟢 | - | 🟢 |
| SIMD vs escalar | 🟢🟢 | - | - | 🟢🟢 |
| mmap vs heap | 🟢 | 🟢🟢 | - | - |
| HAProxy TCP vs HTTP | 🟢 | 🟢 | - | 🟢 |
| Single-thread vs multi | 🟢 | 🟢 | - | 🟡 |
| Zero-allocation hot path | 🟢🟢 (tail) | 🟢 | - | 🟢 |
| PGO em Native Image | 🟢🟢 | - | - | 🟢 |
| ef_search trade-off | 🔴🔴 | - | 🟢🟢 | 🔴 |

🟢 ganho · 🔴 custo · 🟡 trade-off · `-` neutro · magnitude = quantidade.

---

## 5. Stack escolhida

Cada decisão abaixo é **travada para a Onda 1** com formato uniforme: alternativas, veredito, código alvo, pegadinhas. Pontos sinalizados com 🔄 podem ser revisitados após medições.

### 5.1 [DECISÃO] Linguagem: Java 21 LTS

**Conceito-chave**: ver `CONCEITOS.md` §7 (Native Image vs HotSpot), §5 (SIMD).

| Opção | Latência | RAM | Esforço | Veredito |
|---|---|---|---|---|
| **Java 21 LTS + GraalVM** | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ✓ ESCOLHIDO |
| Java 23 + GraalVM | ⭐⭐⭐ | ⭐⭐⭐ | ⭐ | ✗ Vector API instável em Native Image |
| HotSpot puro Java 21 | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ warmup mata os 30s iniciais |
| Kotlin / Scala | ⭐⭐ | ⭐⭐ | ⭐⭐ | ✗ runtime extra, sem benefício |
| Rust | ⭐⭐⭐ | ⭐⭐⭐ | ⭐ | ✗ tempo de aprender ANN+SIMD em Rust |
| Go | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ GC + sem Vector API nativa |
| C / Zig | ⭐⭐⭐ | ⭐⭐⭐ | ⭐ | ✗ dev velocity baixa |

**Por que Java 21 vence**: Vector API estável (incubator GA esperado em 24+), Native Image maduro, virtual threads disponíveis (não usado mas opção), familiaridade alta.

**Quando você escolheria diferente**: se já é fluente em Rust, Rust vence (sem GC, sem warmup, SIMD trivial). Se time tem expertise em C/Zig, vencem em controle absoluto.

**Onde aparece**: `pom.xml` (`<source>21</source>`, `<target>21</target>`), todos os arquivos `.java`.

**Pegadinhas**: hoje o `pom.xml` está em **Java 23** — corrigir na Onda 0 (ver §9.0).

### 5.2 [DECISÃO] Runtime: GraalVM Native Image (Oracle GraalVM, GFTC) + PGO

> **Reconciliação 2026-05-18 (Onda 5).** A trava original dizia "Mandrel +
> PGO" — **contraditória**: PGO (`--pgo`/`--pgo-instrument`) é **exclusivo do
> Oracle GraalVM**; Mandrel / GraalVM CE **não** têm PGO. Resolução: builder =
> **Oracle GraalVM 21** (`container-registry.oracle.com/graalvm/native-image:21`),
> **grátis para produção** sob a licença **GFTC** desde GraalVM for JDK
> 17.0.9/21 (set/2023) — "PGO obrigatório" honrado a custo zero. Histórico
> "Mandrel" preservado abaixo. Ver
> `docs/superpowers/specs/2026-05-18-onda5-native-design.md` §Decisão 1.

**Conceito-chave**: `CONCEITOS.md` §7, §11 (PGO).

| Opção | Latência | RAM | Esforço | Veredito |
|---|---|---|---|---|
| **GraalVM Native + PGO** | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ✓ ESCOLHIDO |
| GraalVM Native sem PGO | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | 🔄 baseline antes da Onda 5 |
| HotSpot puro | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ warmup |
| HotSpot + AppCDS | ⭐⭐ | ⭐⭐ | ⭐⭐ | ✗ ajuda startup, não warmup |
| OpenJ9 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ✗ pouco usado, suporte de tooling pior |

**Por que vence**: sem JIT warmup → primeiras requests já no pico. RSS 30-80 MB vs 80-200 MB HotSpot. PGO recupera os 10-30% que C2 daria com profile.

**Quando você escolheria diferente**: se workload muda dinamicamente em runtime (não é o caso da Rinha), C2 do HotSpot otimiza continuamente.

**Onde aparece**: `Dockerfile` (builder image `container-registry.oracle.com/graalvm/native-image:21` — Oracle GraalVM, GFTC; ver nota 2026-05-18 acima), `pom.xml` profile `native`, `--pgo-instrument` e `--pgo` flags.

**Pegadinhas**: Vector API regrediu silenciosamente em GraalVM/Mandrel 22/23 — usar **Oracle GraalVM 21** (não 22/23). Sempre validar com `-Dgraal.PrintCompilation=true` (ver §12.1).

### 5.3 [DECISÃO] HTTP server: NIO Selector single-threaded raw

**Conceito-chave**: `CONCEITOS.md` §6 (NIO Selector).

| Opção | Latência | RAM | Esforço | Veredito |
|---|---|---|---|---|
| **NIO Selector raw** | ⭐⭐⭐ | ⭐⭐⭐ | ⭐ | ✓ ESCOLHIDO |
| `com.sun.net.httpserver` | ⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ aloca 50-200 µs/req (atual no projeto) |
| Netty | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ✗ framework, viola "by-hand" |
| Vert.x | ⭐⭐ | ⭐⭐ | ⭐⭐ | ✗ event bus overhead |
| Helidon Nima (virtual threads) | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ fork-join scheduling em CPU-bound |
| Virtual threads + blocking I/O | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ ~5 µs por park/unpark |

**Por que vence**: 1 CPU compartilhada → multi-thread só adiciona scheduling. Hand-roll dá controle: `byte[]` com offsets, zero String, zero Map, zero header parsing supérfluo.

**Quando você escolheria diferente**: app multi-endpoint, schema variável, dev velocity > performance — Netty/Helidon ganham.

**Onde aparece**: `server/NioServer.java`, `server/HttpParser.java`, `server/ConnectionState.java`. **Substitui** `server/ServerHTTP.java` atual (`com.sun.net.httpserver`).

**Pegadinhas**: keep-alive obrigatório (ver §12.2); buffer pool por conexão; spurious wakeups.

### 5.4 [DECISÃO] Parser JSON: hand-rolled byte-array com SIMD byte-search

**Conceito-chave**: `CONCEITOS.md` §10 (HTTP/1.1 by-hand), §9 (zero-allocation).

| Opção | Latência | Alocação | Esforço | Veredito |
|---|---|---|---|---|
| **Hand-roll byte-array** | ⭐⭐⭐ | zero | ⭐ | ✓ ESCOLHIDO |
| Jackson tree model | ⭐ | alta | ⭐⭐⭐ | ✗ Map+String alloc |
| Jackson streaming | ⭐⭐ | média | ⭐⭐ | ✗ ainda aloca tokens |
| Gson | ⭐ | alta | ⭐⭐⭐ | ✗ similar a Jackson |
| DSL-JSON | ⭐⭐ | média | ⭐⭐ | ✗ codegen, mas alloc ainda existe |
| simdjson-java | ⭐⭐⭐ | baixa | ⭐⭐ | 🔄 boa alternativa, mas dependência grande |
| jsoniter | ⭐⭐ | média | ⭐⭐ | ✗ similar |

**Por que vence**: payload tem ~300 bytes, schema fixo, 14 campos conhecidos. Walker stateful em offsets esperados é 5-10 µs vs 50-100 µs Jackson.

**Quando você escolheria diferente**: schema dinâmico, projeto novo onde produtividade vence.

**Onde aparece**: `json/FraudRequestParser.java`, `json/FraudResponseSerializer.java`.

**Pegadinhas**: tail handling em SIMD byte-search; case sensitivity; suporte a sentinel `null`.

### 5.5 [DECISÃO] Index ANN: HNSW hand-rolled

**Conceito-chave**: `CONCEITOS.md` §2 (ANN), §3 (HNSW).

| Opção | Recall | Latência | RAM | Esforço | Veredito |
|---|---|---|---|---|---|
| **HNSW hand-rolled** | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐ | ✓ ESCOLHIDO |
| HNSW (Lucene) | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ heavy dependency |
| HNSW (JVector) | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | 🔄 segunda opção viável |
| IVF flat | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ✗ ainda toca milhares de vetores |
| IVFPQ | ⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐ | ✗ codebook overhead em D=14 |
| LSH | ⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ✗ recall ruim em D=14 |
| Brute force float32 | ⭐⭐⭐ | ⭐ | ⭐ | ⭐⭐⭐ | ✗ não fecha 1ms |
| Brute force int8 SIMD | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | 🔄 fallback se HNSW falhar |
| ScaNN | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐ | ✗ Google C++ lib, integração custosa |
| kd-tree / ball-tree | ⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐ | ✗ degrada em D > 10 |

**Por que vence**: HNSW dá `O(log N)` com 95-99% recall em qualquer D. JVector seria viável mas hand-roll permite layout binário customizado e zero-allocation no search.

**Quando você escolheria diferente**: brute force int8 SIMD se conseguir < 5 ms (ainda dá +2000 em score_p99). JVector se você quiser produção com manutenção.

**Onde aparece**: `knn/HnswIndex.java` (search), `knn/HnswBuilder.java` (build offline), `knn/PriorityQueueMin.java`, arquivo binário `hnsw.bin`.

**Parâmetros iniciais**:

| Param | Valor | Significado |
|---|---|---|
| `M` | 16 | Vizinhos por nó nas camadas superiores |
| `M_max0` | 32 | Vizinhos no nível 0 (camada base) |
| `ef_construction` | 200 | Candidatos durante build |
| `ef_search` | 50 | Candidatos durante query |
| `mL` | 1/ln(M) ≈ 0.36 | Fator de probabilidade de nível |

**Pegadinhas**: random seed fixo no builder (ver §12.7); validar recall ≥ 95% (Onda 3); ef_search é runtime-tunable.

### 5.6 [DECISÃO] Distance kernel: Vector API SIMD (`jdk.incubator.vector`)

**Conceito-chave**: `CONCEITOS.md` §5 (SIMD).

| Opção | Latência | Portabilidade | Esforço | Veredito |
|---|---|---|---|---|
| **Vector API SIMD** | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✓ ESCOLHIDO |
| JNI/C com AVX2 intrinsics | ⭐⭐⭐ | ⭐ | ⭐ | ✗ JNI overhead em distance, complexidade |
| Project Panama foreign | ⭐⭐ | ⭐⭐ | ⭐⭐ | 🔄 promissor mas overhead similar |
| `Unsafe` byte-by-byte | ⭐⭐ | ⭐⭐ | ⭐ | ✗ unrollable mas escalar |
| Escalar puro | ⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✗ baseline para comparar |

**Por que vence**: 14 dimensões cabem em 1 registrador AVX2 (32 lanes int8). Vector API gera intrinsics nativos, sem JNI overhead.

**Quando você escolheria diferente**: se Native Image regredir Vector API e PGO não recuperar — JNI/C como último recurso.

**Onde aparece**: `knn/DistanceFunctions.java#euclideanInt8`.

**Pegadinhas**: overflow signed (promover int8→int16→int32 na soma de quadrados, ver §12.11); fallback escalar silencioso (ver §12.1).

### 5.7 [DECISÃO] Quantização: int8 com escala global

**Conceito-chave**: `CONCEITOS.md` §4 (Quantização int8).

| Opção | RAM | Recall | Latência kernel | Esforço | Veredito |
|---|---|---|---|---|---|
| **int8 escala global** | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✓ ESCOLHIDO |
| int8 per-dimension | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | 🔄 fallback se recall global < 95% |
| int4 | ⭐⭐⭐ | ⭐ | ⭐⭐ | ⭐ | ✗ recall ruim em 14 dims |
| float16 (bf16) | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ✗ duplica RAM vs int8 sem ganho proporcional |
| float32 | ⭐ | ⭐⭐⭐ | ⭐ | ⭐⭐⭐ | ✗ 168 MB > orçamento |
| Product Quantization | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐ | ✗ codebook overhead em D=14 |
| Sem quantizar (float32 + SIMD) | ⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ RAM e cache miss |

**Por que vence**: 4× menos RAM (42 MB vs 168 MB), cache fit (21 KB working set), AVX2 32-lane. Global é mais simples (1 mult na quantize da query) e tipicamente atende em datasets normalizados.

**Quando você escolheria diferente**: se uma dimensão dominar magnitude (ex: amount nunca usar 100% do range), per-dim recupera resolução nas pequenas.

**Onde aparece**: `knn/Quantizer.java`, `tools/PreprocessDataset.java` (calcula `quant_scale`).

**Pegadinhas**: sentinela `-1` precisa ficar fora do range positivo (ver §12.12).

### 5.8 [DECISÃO] Storage do dataset: mmap binário

**Conceito-chave**: `CONCEITOS.md` §8 (mmap e Page Cache).

| Opção | RAM (RSS) | Latência cold | Latência warm | Veredito |
|---|---|---|---|---|
| **mmap binário** | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✓ ESCOLHIDO |
| Heap `byte[]` | ⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✗ 42 MB no GC, count em RSS |
| `DirectByteBuffer` (heap) | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✗ off-heap mas count RSS |
| Off-heap `Unsafe` | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✗ similar a Direct, sem benefício |
| SHM (`/dev/shm`) | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | 🔄 alternativa se cache compartilhado virar problema |

**Por que vence**: kernel só carrega páginas tocadas, page cache compartilhado entre instâncias (1 cópia física), zero alocação no Java.

**Quando você escolheria diferente**: dataset pequeno (cabe em L2), heap byte[] pode ser mais simples.

**Onde aparece**: `dataset/MmapDataset.java`, `dataset/BinaryFormat.java`, `references.bin`, `hnsw.bin`, `labels.bin`.

**Layout `references.bin`**:

```
| 4 B  | num_vectors (= 3_000_000)             |
| 4 B  | dimension   (= 14)                    |
| 4 B  | quant_scale (float32)                 |
| 4 B  | reserved                              |
| Nx14 B | int8 quantized vectors (sequential)  |
| ceil(N/8) B | label bitset (1=fraud, 0=legit) |
```

Total: 16 + 3M × 14 + 375k = **42 MB**. `madvise(MADV_RANDOM)` (HNSW = random access).

**Layout `hnsw.bin`** (CSR — Compressed Sparse Row):

```
| header: num_nodes, max_level, entry_point_id |
| level_offsets[]: para cada nível, posição inicial em adjacency_data |
| node_levels[num_nodes]: level máximo de cada nó |
| adjacency_data[]: array plano de int32 com vizinhos |
```

Sem ponteiros Java. Tudo em offsets de bytes.

**Pegadinhas**: `force()` em RO buffer (ver §12.5); cleanup do buffer após uso.

### 5.9 [DECISÃO] Load Balancer: HAProxy modo TCP

**Conceito-chave**: round-robin TCP simples (sem parsing HTTP).

| Opção | Latência | RAM | Esforço | Veredito |
|---|---|---|---|---|
| **HAProxy TCP** | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✓ ESCOLHIDO |
| HAProxy HTTP | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✗ +30% latência por header parse |
| nginx stream | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | 🔄 alternativa válida |
| nginx HTTP | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ similar a HAProxy HTTP |
| Caddy | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ✗ sem benefício específico |
| Traefik | ⭐⭐ | ⭐ | ⭐⭐⭐ | ✗ overhead de descoberta |
| Envoy | ⭐⭐⭐ | ⭐ | ⭐ | ✗ complexo demais |
| socat / iptables DNAT | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ✗ viola requisito (LB precisa ser real) |

**Por que vence**: ~10 MB RSS, single-thread, modo TCP zero header parsing, round-robin nativo.

**Quando você escolheria diferente**: precisar path-based routing (não na Rinha).

**Onde aparece**: `docker/haproxy.cfg`.

**Configuração**:
```
global
    nbthread 1

defaults
    mode tcp
    timeout connect 100ms
    timeout client  2500ms
    timeout server  2500ms

frontend front
    bind *:9999
    default_backend api

backend api
    balance roundrobin
    server api1 api1:9000 check
    server api2 api2:9000 check
```

**Pegadinhas**: validar com `haproxy -c -f haproxy.cfg` antes de subir (ver §12.8).

### 5.10 [DECISÃO] Build tool: Maven + native-maven-plugin

**Conceito-chave**: build reproducível com profile `native`.

| Opção | Build time | Familiaridade | Veredito |
|---|---|---|---|
| **Maven + native-maven-plugin** | ⭐⭐ | ⭐⭐⭐ | ✓ ESCOLHIDO |
| Gradle (Groovy DSL) | ⭐⭐⭐ | ⭐⭐ | 🔄 alternativa razoável |
| Gradle Kotlin DSL | ⭐⭐⭐ | ⭐⭐ | 🔄 melhor experiência mas curva |
| Bazel | ⭐⭐⭐ | ⭐ | ✗ complexidade demais |

**Por que vence**: já existe esqueleto em Maven no projeto, plugin oficial GraalVM (`org.graalvm.buildtools:native-maven-plugin`).

**Onde aparece**: `pom.xml`, `.mvn/` wrapper.

**Profile `native`**:
- `-H:+ReportExceptionStackTraces`
- `--no-fallback`
- `--enable-preview`
- `--add-modules jdk.incubator.vector`

### 5.11 [DECISÃO] Container runtime: distroless

**Conceito-chave**: imagem mínima com glibc, sem shell.

| Opção | Tamanho | Esforço | Veredito |
|---|---|---|---|
| **distroless/base-debian12** | ~20 MB | ⭐⭐⭐ | ✓ ESCOLHIDO |
| scratch + `--static --libc=musl` | ~5 MB | ⭐ | 🔄 Onda 5 opcional |
| alpine | ~10 MB | ⭐⭐ | ✗ musl quirks |
| ubuntu-slim | ~30 MB | ⭐⭐⭐ | ✗ sem benefício |

**Por que vence**: 20 MB é folgado nos 350 MB; glibc nativo dispensa hassle de build static.

**Onde aparece**: `Dockerfile` última stage.

**Pegadinhas**: scratch sem `--static --libc=musl` falha com "exec format error" (ver §12.9).

---

## 6. Arquitetura interna

### 6.1 Diagrama

```mermaid
flowchart LR
    Client -->|HTTP :9999| HAProxy[HAProxy TCP RR]
    HAProxy --> API1[API instância 1]
    HAProxy --> API2[API instância 2]
    
    subgraph "API instância (single thread)"
        Selector[NIO Selector] -->|read| HttpParser[Parser HTTP/1.1]
        HttpParser -->|/fraud-score| JsonParser[Parser JSON inline]
        JsonParser -->|float[14]| Quantizer[Quantize int8]
        Quantizer --> HNSW[HNSW search]
        HNSW -->|top-5 ids| Score[Score fraudes/5]
        Score --> Writer[Response writer]
        Writer -->|write| Selector
        
        DatasetMmap[(references.bin<br/>+ labels.bin<br/>+ hnsw.bin<br/>mmap RO)] -.-> HNSW
    end
```

### 6.2 Modelo de threading

**Single-threaded reactor**. Loop principal:

```
while (running) {
    int n = selector.select();   // bloqueia até I/O event
    for (key in selectedKeys()) {
        if (key.isAcceptable()) acceptNewClient();
        if (key.isReadable())   handleRead(key);
        if (key.isWritable())   handleWrite(key);
    }
}
```

CPU-bound (KNN search) acontece dentro do `handleRead` na **mesma thread**. Zero context switch no hot path.

🔄 Decisão revisitável: se profiling mostrar bloqueio em `Selector.select()` enquanto KNN poderia rodar (raro com 1 CPU), avaliar 2 threads + SPSC queue.

### 6.3 Layout de memória — zero-allocation

`ConnectionState` por conexão:

```java
final ByteBuffer readBuffer  = ByteBuffer.allocateDirect(4096);
final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(512);
final float[]    queryVector = new float[14];
final byte[]     queryQuant  = new byte[14];
final int[]      hnswCandidates = new int[1024];
final float[]    hnswDistances  = new float[1024];
```

Tudo alocado **uma vez por conexão**, reutilizado por request. Zero `new` no hot path. JSON parser escreve direto em `queryVector[i]`. KNN escreve em `hnswCandidates`.

`ThreadLocal` desnecessário (single-thread).

### 6.4 Dados memory-mapped

`MmapDataset.open(Path bin)`:

1. Abre `FileChannel` em READ_ONLY.
2. `MappedByteBuffer buf = ch.map(MapMode.READ_ONLY, 0, size)`.
3. `madvise(buf, MADV_RANDOM)` via `Unsafe` ou `Foreign Memory API`.
4. Expõe métodos `getInt8Vector(long offset, byte[] dst)` e `getLabel(int id)`.

Read-only → safe para single-thread sem locks.

---

## 7. Estrutura do repositório

```
fraud-detection/
├── RINHA_PLAN.md                        ← guia principal (você está aqui)
├── docs/
│   ├── CONCEITOS.md                     ← conceitos do zero (Onda 0+)
│   └── IMPACTO.md                       ← mapa de impacto
├── fraudAPI/
│   ├── pom.xml                          ← Java 21 + native-maven-plugin   (Onda 0)
│   ├── Dockerfile                       ← multi-stage HotSpot/Native      (Onda 4-5)
│   ├── .mvn/
│   ├── src/
│   │   └── main/java/org/fraudDetection/
│   │       ├── Main.java                ← entry point                     (Onda 1)
│   │       ├── server/
│   │       │   ├── NioServer.java       ← Selector loop                   (Onda 1)
│   │       │   ├── ConnectionState.java ← buffers reutilizáveis           (Onda 1)
│   │       │   ├── HttpParser.java      ← parser HTTP/1.1 byte-array      (Onda 1)
│   │       │   └── HttpResponseWriter.java                                (Onda 1)
│   │       ├── json/
│   │       │   ├── FraudRequestParser.java   ← parse + vetorização inline (Onda 1)
│   │       │   └── FraudResponseSerializer.java ← respostas canned        (Onda 1)
│   │       ├── knn/
│   │       │   ├── HnswIndex.java       ← search runtime                  (Onda 3)
│   │       │   ├── HnswBuilder.java     ← build offline                   (Onda 3)
│   │       │   ├── PriorityQueueMin.java                                  (Onda 3)
│   │       │   ├── Quantizer.java       ← int8 escala global              (Onda 2)
│   │       │   └── DistanceFunctions.java ← Vector API SIMD               (Onda 2)
│   │       ├── dataset/
│   │       │   ├── BinaryFormat.java    ← layout dos .bin                 (Onda 2)
│   │       │   ├── MmapDataset.java     ← MappedByteBuffer wrapper        (Onda 2)
│   │       │   └── LabelBitset.java     ← bitset de fraud/legit           (Onda 2)
│   │       └── controllers/
│   │           ├── FraudController.java                                   (Onda 1)
│   │           └── HealthController.java                                  (Onda 1)
│   └── test/
│       ├── correctness/                 ← testes contra example-payloads  (Onda 1)
│       ├── KnnRecallTest.java           ← HNSW recall vs brute force      (Onda 3)
│       └── HttpParserTest.java                                            (Onda 1)
├── tools/
│   ├── PreprocessDataset.java           ← gz → references.bin + labels.bin (Onda 2)
│   └── BuildHnsw.java                   ← references.bin → hnsw.bin       (Onda 3)
├── docker/
│   ├── haproxy.cfg                                                        (Onda 4)
│   └── docker-compose.yml               ← branch submission               (Onda 4)
└── docs/profiles/                       ← flamegraphs, gc.log por onda    (Onda 4+)
```

### 7.1 Branches

- **`main`**: tudo acima, com código-fonte.
- **`submission`**: apenas `docker-compose.yml`, `haproxy.cfg`, `info.json`, `Dockerfile` e `references.bin` + `labels.bin` + `hnsw.bin`. Sem código-fonte.

### 7.2 `info.json` (na branch submission)

```json
{
  "participants": ["Arthur Damasceno"],
  "social": ["https://github.com/arthurd3"],
  "source-code-repo": "https://github.com/arthurd3/rinha-de-backend-2026-fraud-java",
  "stack": ["java", "graalvm", "haproxy"],
  "open_to_work": true
}
```

---

## 8. Pipeline da request

Trace completo, byte que entra → byte que sai. Anotações de orçamento (`τ`) por etapa. Conceitos referenciados em cada parte.

### 8.1 Aceitação (τ ~10 µs)

`CONCEITOS.md` §6 (NIO Selector).

1. `Selector.select()` retorna com `OP_ACCEPT` em `serverChannel`.
2. `serverChannel.accept()` → novo `SocketChannel`.
3. `socket.configureBlocking(false)` + `register(selector, OP_READ, new ConnectionState())`.
4. `TCP_NODELAY=true`, `SO_RCVBUF=8192`, `SO_SNDBUF=2048`.

### 8.2 Read + parse HTTP (τ ~70 µs)

`CONCEITOS.md` §10 (HTTP/1.1 by-hand).

1. `Selector.select()` retorna com `OP_READ`.
2. `channel.read(state.readBuffer)` — geralmente 1 syscall traz request inteira.
3. `HttpParser.parse(state.readBuffer)`:
   - Procurar bytes `'P','O','S','T',' '` ou `'G','E','T',' '` no início.
   - Cursor pula para path: comparar com `b"/fraud-score"` ou `b"/ready"`.
   - Pular até `Content-Length:` (apenas se POST).
   - Pular linha em branco `\r\n\r\n`.
   - Cursor agora aponta para corpo (JSON).

### 8.3 Branch /ready (τ ~50 µs)

Resposta canned: `b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"` em 31 bytes pré-construídos. Write direto.

### 8.4 Parse JSON + vetorização inline (τ ~60 µs)

`CONCEITOS.md` §9 (zero-allocation), §10 (HTTP/1.1).

Único pass pelo buffer JSON:

```
cursor = json_start
expect('{')

skip_to_field("transaction")
{
  skip_to_field("amount");        amount = parse_double()
  skip_to_field("installments");  installments = parse_int()
  skip_to_field("requested_at");  requested_at = parse_iso8601()
}

queryVector[0] = clamp(amount / 10000.0)
queryVector[1] = clamp(installments / 12.0)
queryVector[3] = (hour_of(requested_at) / 23.0)
queryVector[4] = (day_of_week_of(requested_at) / 6.0)

skip_to_field("customer")
{
  skip_to_field("avg_amount");    avg = parse_double()
  skip_to_field("tx_count_24h");  count = parse_int()
  skip_to_field("known_merchants"); known = parse_string_array_into(state.knownMerchants)
}
queryVector[2] = clamp((amount / avg) / 10.0)
queryVector[8] = clamp(count / 20.0)

skip_to_field("merchant")
{
  skip_to_field("id");          merchant_id = parse_string_ref()  // só offsets
  skip_to_field("mcc");         mcc = parse_string_ref()
  skip_to_field("avg_amount");  m_avg = parse_double()
}
queryVector[11] = is_unknown(merchant_id, known) ? 1 : 0
queryVector[12] = mcc_risk_lookup(mcc)            // table-lookup ou default 0.5
queryVector[13] = clamp(m_avg / 10000.0)

skip_to_field("terminal")
{
  skip_to_field("is_online");     queryVector[9]  = parse_bool() ? 1 : 0
  skip_to_field("card_present");  queryVector[10] = parse_bool() ? 1 : 0
  skip_to_field("km_from_home");  queryVector[7]  = clamp(parse_double() / 1000.0)
}

skip_to_field("last_transaction")
if (peek_null()) {
  queryVector[5] = -1
  queryVector[6] = -1
} else {
  skip_to_field("timestamp");        last_ts = parse_iso8601()
  skip_to_field("km_from_current");  km     = parse_double()
  queryVector[5] = clamp(minutes_between(last_ts, requested_at) / 1440.0)
  queryVector[6] = clamp(km / 1000.0)
}
```

### 8.5 Quantize query (τ ~5 µs)

`CONCEITOS.md` §4 (Quantização).

```java
for (int i = 0; i < 14; i++) {
    queryQuantized[i] = (byte) Math.round(queryVector[i] * quantScale);  // sentinel -1 tratado especialmente
}
```

Sentinela `-1`: o quantizado fica fora do range `[0, scale]` dos vetores normais — distância naturalmente alta para vetores que têm `last_transaction` válido.

### 8.6 HNSW search (τ ~230 µs)

`CONCEITOS.md` §3 (HNSW).

Algoritmo padrão (Malkov & Yashunin):

1. Começa do `entry_point`, busca greedy nos níveis superiores até `level=1`.
2. No nível 0, busca beam search com `ef_search=50`.
3. Mantém `topK` heap (mín de tamanho 5).

`DistanceFunctions.euclideanInt8(queryQuantized, refOffset)` — lê 14 bytes do mmap, computa via Vector API.

### 8.7 Score (τ ~5 µs)

Para cada um dos 5 IDs retornados, lookup no `LabelBitset`. Soma `numFraud`. `score = numFraud / 5.0`. `approved = score < 0.6`.

### 8.8 Serializar response + write (τ ~60 µs)

`CONCEITOS.md` §10.

`fraud_score` tem só 6 valores possíveis (`0.0`, `0.2`, `0.4`, `0.6`, `0.8`, `1.0`) × 2 booleans = **12 strings constantes**. Lookup direto.

```java
static final byte[][] RESPONSES = new byte[12][];   // pré-formatados
// ...
buf.put(RESPONSES[approved ? fraudCount : fraudCount + 6]);
```

### 8.9 O que NÃO alocar no hot path

`CONCEITOS.md` §9.

- ❌ `new String(...)` no parser
- ❌ `Map<String,Object>`
- ❌ `Double.parseDouble(String)` — fazer manual em `byte[]`
- ❌ `Instant.parse(String)` — parser ISO8601 manual em `byte[]`
- ❌ regex
- ❌ logs no hot path (só erros 5xx)

---

## 9. Roadmap por etapas

Cada onda é uma **mini-aula**. Pré-requisitos linkam para `CONCEITOS.md`. Risco: 🟢 baixo / 🟡 médio / 🔴 alto.

### 9.0 Onda 0 — Setup e correções de fundação 🟢

**Em uma frase**: deixar o projeto pronto para a Onda 1 (Java certo, plugins, build verde).

**Pré-requisitos**:
- Conceitos: nenhum (operacional).
- Ferramentas: Java 21 LTS instalado (`sdkman install java 21-graal`), Maven 3.9+.

**Estado atual** (descoberto na exploração):
- `pom.xml` em **Java 23** (incorreto).
- Sem dependências.
- `server/ServerHTTP.java` usando `com.sun.net.httpserver` (descartar).
- `controllers/FraudController.java` e `HealthController.java` vazios.

**Passo 1: Corrigir `pom.xml` para Java 21**
- Trocar `<source>23</source>` e `<target>23</target>` por `21`.
- Adicionar `<maven.compiler.source>21</maven.compiler.source>` e `<maven.compiler.target>21</maven.compiler.target>`.
- Adicionar profile `native` com `org.graalvm.buildtools:native-maven-plugin`.
- Configurar `--enable-preview --add-modules jdk.incubator.vector` em `maven-compiler-plugin`.
- **Validar**: `./mvnw compile` deve passar.

**Passo 2: Configurar Maven wrapper**
- Diretório `.mvn/` está vazio. Rodar `mvn -N io.takari:maven:wrapper -Dmaven=3.9.6` para gerar `mvnw` + `.mvn/wrapper/`.
- **Validar**: `./mvnw -version` mostra Maven 3.9.6 e Java 21.

**Passo 3: Marcar `ServerHTTP.java` como obsoleto**
- Não apagar ainda — ficar como referência para Onda 1.
- Adicionar comentário: `// DEPRECATED: substituído por NioServer.java na Onda 1`.

**Métricas de saída**:
- ✅ `./mvnw compile` passa sem warning de versão.
- ✅ `./mvnw -version` mostra Java 21.
- ✅ `pom.xml` tem profile `native` configurado.

**Estimativa**: 2-4h.

**Risco**: 🟢. Próxima onda: 9.1.

---

### 9.1 Onda 1 — Esqueleto fim a fim (correctness baseline) 🟡

**Em uma frase**: pipeline funciona ponta a ponta com brute force float32. Validar correctness contra `example-payloads.json`.

**Pré-requisitos**:
- Conceitos: `CONCEITOS.md` §1 (k-NN), §6 (NIO), §9 (zero-allocation), §10 (HTTP/1.1).
- Onda 0 concluída.

**Mapa do que você vai fazer**:
1. `Main.java` — entry point.
2. `server/NioServer.java` — Selector loop.
3. `server/ConnectionState.java` — buffers por conexão.
4. `server/HttpParser.java` — parser HTTP/1.1 mínimo.
5. `server/HttpResponseWriter.java` — respostas canned.
6. `json/FraudRequestParser.java` — walker no buffer JSON, popular `queryVector` direto.
7. `dataset/MmapDataset.java` v1 — lê `references.json.gz` cru (float32) na memória.
8. `knn/DistanceFunctions.java` v1 — euclidiana float32 escalar.
9. `knn/HnswIndex.java` v1 — stub que faz brute force linear.
10. `controllers/FraudController.java` e `HealthController.java` — amarram tudo.
11. Teste: `curl` com `example-payloads.json` → conferir contra `example-references.json`.

**Conceitos que você aprende fazendo**:
- NIO Selector reactor pattern em `NioServer.java`.
- Parsing HTTP/1.1 byte-array em `HttpParser.java`.
- Vetorização inline (sem objeto intermediário) em `FraudRequestParser.java`.
- Brute force k-NN em `HnswIndex.java` v1.

**Critério de saída**:
- ✅ `curl POST /fraud-score` com cada exemplo retorna `{approved, fraud_score}` corretos.
- ✅ `curl GET /ready` retorna 200.
- ✅ Roda local, sem container.
- ✅ Latência irrelevante.

**Se algo der errado**:
- Sintoma "request fica pendurada" → keep-alive não está fechando read corretamente. Ver §12.2.
- Sintoma "JSON parse erro em null" → tratar `last_transaction: null` (sentinela -1).
- Sintoma "OOM ao carregar references.json.gz" → JSON é 284 MB descomprimido; reduzir heap não ajuda. Carregar em chunks ou já partir para Onda 2.

**Estimativa**: 1-2 dias. **Risco**: 🟡 (parser HTTP de mão tem detalhes).

---

### 9.2 Onda 2 — Quantização int8 + SIMD 🟡

**Em uma frase**: dataset cabe em ~42 MB via mmap, distance kernel usa AVX2 (32 lanes int8).

**Pré-requisitos**:
- Conceitos: `CONCEITOS.md` §4 (Quantização), §5 (SIMD), §8 (mmap).
- Onda 1 concluída e validada com correctness.

**Mapa**:
1. `tools/PreprocessDataset.java` — lê `references.json.gz`, calcula escala global, quantiza int8, escreve `references.bin` + `labels.bin`.
2. `dataset/BinaryFormat.java` — layout do `references.bin`.
3. `dataset/MmapDataset.java` v2 — substitui carregamento JSON por mmap do `references.bin`.
4. `dataset/LabelBitset.java` — bitset 1bit/registro.
5. `knn/Quantizer.java` — quantize da query com mesma escala.
6. `knn/DistanceFunctions.java` v2 — versão SIMD com `ByteVector.SPECIES_256`.
7. `knn/HnswIndex.java` v2 — ainda brute force, mas agora int8 SIMD.
8. JMH benchmark: medir distance isolada, comparar com float32 escalar.
9. Validar recall: 100% (brute force ainda exato), mas pode ter diferenças marginais em score por causa da quantização — investigar.

**Conceitos aplicados**:
- `Quantizer.java` materializa quantização escala global.
- `DistanceFunctions.euclideanInt8` é o SIMD do `CONCEITOS.md` §5.
- `MmapDataset` é o padrão de `CONCEITOS.md` §8.

**Critério de saída**:
- ✅ Dataset cabe em ~42 MB.
- ✅ JMH mostra speedup ≥ 4× vs float32 escalar.
- ✅ Respostas batem com Onda 1 ou diferem por ≤ 0.2 em `fraud_score` em casos de borda.
- ✅ p99 brute force int8 SIMD: ~3-5 ms (medir).

**Se algo der errado**:
- Latência idêntica ao float32 → SIMD caiu para escalar (ver §12.1).
- Recall < 100% → bug na quantização ou conversão signed/unsigned (§12.11).
- RSS sobe acima de 100 MB → mmap não foi feito, dados em heap (§12.5).

**Estimativa**: 2 dias. **Risco**: 🟡 (Vector API tem gotchas).

---

### 9.3 Onda 3 — HNSW hand-rolled 🔴

**Em uma frase**: substituir brute force por HNSW. Search em `O(log N)`.

**Pré-requisitos**:
- Conceitos: `CONCEITOS.md` §2 (ANN), §3 (HNSW from scratch). **Leitura obrigatória do paper Malkov-Yashunin 2016**.
- Onda 2 concluída.

**Mapa**:
1. `tools/BuildHnsw.java` — lê `references.bin`, constrói grafo HNSW (M=16, ef_c=200), serializa CSR plana em `hnsw.bin`.
2. `knn/HnswIndex.java` v3 — substituir brute force por search HNSW real (greedy nos topos, beam no nível 0).
3. `knn/PriorityQueueMin.java` — heap mínimo dedicado para top-k, baseado em arrays primitivos.
4. Validar recall vs brute force: ≥ 95% top-5 igual.
5. JMH: comparar latência HNSW vs brute force.
6. Tunar `ef_search` se recall insuficiente.

**Sub-passos do HnswBuilder** (parte mais complexa do projeto):
- 3a: gerar levels para cada nó (distribuição exponencial com `mL`).
- 3b: para cada nó, `searchLayer` para encontrar candidatos.
- 3c: heurística de seleção dos M vizinhos (manter diversidade).
- 3d: bidirectional links — nó conectado a vizinho deve aparecer no inverso.
- 3e: serializar para CSR plana.

**Conceitos aplicados**:
- `HnswIndex.search()` é o pseudocódigo do `CONCEITOS.md` §3.
- `PriorityQueueMin` evita `PriorityQueue` boxado.

**Critério de saída**:
- ✅ `hnsw.bin` gerado em < 30 min no build.
- ✅ HNSW search em ~200-500 µs / query (medir).
- ✅ Recall ≥ 95% top-5 vs brute force.
- ✅ Score final em test set local ≥ 95% do score com brute force.

**Se algo der errado**:
- Recall ~50% → bug na heurística de seleção (não está mantendo diversidade) ou no greedy descent.
- Latência ~5ms → ef_search muito alto, ou cache miss (working set fora de L2).
- Build determinístico falha → seed do Random (§12.7).

**Estimativa**: 3-5 dias (HNSW from scratch é a parte mais complexa).
**Risco**: 🔴.

---

### 9.4 Onda 4 — Conteinerização + benchmark oficial 🟡

**Em uma frase**: rodar end-to-end no docker compose com 2 instâncias + HAProxy. Medir score Rinha real.

**Pré-requisitos**:
- Onda 3 concluída.
- Docker Engine 24+, k6 0.50+.

**Mapa**:
1. `Dockerfile` multi-stage com **HotSpot** (mais rápido iterar — Native fica para Onda 5).
2. `docker/haproxy.cfg`.
3. `docker/docker-compose.yml`: HAProxy + 2 instâncias + limites CPU/memória.
4. Configurar branch `submission`.
5. Criar `info.json`.
6. Rodar k6 oficial localmente, capturar `final_score`.
7. Validar limites de recursos (`docker stats`).

**Critério de saída**:
- ✅ `docker compose up` sobe HAProxy + 2 APIs saudáveis.
- ✅ k6 oficial roda contra LB e gera `results.json`.
- ✅ `final_score` baseline registrado (3000-4500 esperado com HotSpot).
- ✅ Memória total < 350 MB no `docker stats`.
- ✅ Branch `submission` configurada e testada.

**Se algo der errado**:
- OOMKill silencioso (§12.10) → reduzir heap.
- HAProxy modo HTTP (§12.8) → garantir `mode tcp`.
- Latência LB alta → checar `nbthread 1` e timeouts.

**Estimativa**: 2 dias. **Risco**: 🟡.

---

### 9.5 Onda 5 — GraalVM Native Image + PGO ✅ (CONCLUÍDA + VALIDADA 2026‑05‑18)

> ✅ **As‑built 2026‑05‑18 (Onda 5 — CONCLUÍDA, 4 gates verdes).** Validada
> on‑device (Docker + builder **Oracle GraalVM 21** já em cache local; a
> reconciliação Mandrel→Oracle/GFTC está em §5.2 nota 2026‑05‑18 e
> `docs/tecnologias/02-graalvm-native-image.md` — **não repetir aqui**). Binário
> nativo AOT **12 MB** (`distroless/base-debian12`, entrypoint `/app/api 9999`,
> imagem ≈399 MB = 12 MB binário + 365 MB índice RO embutido + glibc
> distroless). Evidência de build: `Graal compiler: optimization level: 3,
> target machine: x86-64-v3, PGO: user-provided` (consome `default.iprof`
> offline; `--no-fallback`). **`-march` corrigido v2→v3** (Haswell/AVX2, §1.7).
>
> **Gate A — nota honesta (Vector API removida).** O `sqDistI8` SIMD
> (`jdk.incubator.vector`) foi **REMOVIDO** de `DistanceFunctions.java` (e os
> testes `DistEquivI8`/`BenchSearch`): seus campos `static VectorSpecies`
> puxam `VectorSupport.getMaxLaneCount` e **quebram o LINK do Native Image**.
> Era **código morto desde a Onda 2b** (0 callers; SIMD 3,8× mais lento;
> produção sempre usou `sqDistI8Scalar`). `sqDistI8Scalar` é
> **byte‑idêntico ao HEAD HotSpot** ⇒ o Gate B prova comportamento de
> produção inalterado. O invariante "ZERO mudança Java" passa a ser
> honestamente **"ZERO mudança de comportamento de produção"** (o passo 7 do
> Mapa abaixo — validar intrinsics Vector API — deixa de se aplicar: não há
> mais SIMD a validar).
>
> **Gate C — reconciliado à semântica de cgroup** (mesma reconciliação da
> Onda 4a Gate 3 / 4b Gate 2): `VmHWM ≈ 378 MB/inst` é o **mmap
> file‑backed reclamável** do índice RO embutido (`hnsw.bin` 314 MB +
> `references.bin` 51 MB ≈ 365 MB), **não** o custo anônimo do processo —
> `docker stats` mostra ~26,6 MiB/inst e **não há `OOMKilled`** sob o
> cgroup duro de 350 MB (api 159 M×2 + haproxy 32 M), 0 restarts. Isto é
> exatamente o "Argumento de memória" do spec §5. O critério literal "RSS
> por instância < 80 MB" abaixo lê‑se, as‑built, como **`docker stats`/RSS
> anônimo < 80 MB** (≈26,6 MiB ✅) — `VmHWM` cru não é o número relevante.
>
> **Resultados (verbatim):** Gate B — HotSpot `RecallHnsw` recall@5
> **96,89 %** / approved‑agree **99,90 %** (FP=1 FN=1); `Rbh2Equiv`
> **0 / 3.000.000**; nativo `/ready`→200 + 2 oráculos byte‑exatos pelo
> HAProxy LB (`tx-1329056812`→`{"approved":true,"fraud_score":0.0}`,
> `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`). Gate C — binário
> **12 MB** (<80 MB), sem `OOMKilled`, `http_errors` **0** @900 RPS, p99
> **0,59 ms** (sem warmup — AOT, sem JIT). Gate D — k6 oficial ramp 1→900
> RPS/120 s via LB → `final_score` **4393,85** (≥ baseline HotSpot 4b
> 3611–4394; iguala a melhor run da 4b), `http_errors` **0**, p99
> **0,59 ms**, FP=61 FN=103 TP=23934 TN=29960, `failure_rate` **0,3 %**,
> `p99_score` 3000 (sem corte). Veredito por critério de saída: binário
> <80 MB ✅; RSS anônimo <80 MB ✅ (cgroup, ver acima); sem warmup ✅;
> Vector API → **N/A** (removida, código morto); `final_score` Native ≥
> HotSpot ✅. **Onda 5 é a ÚLTIMA onda técnica — o projeto fecha aqui**
> (Onda 6 = otimizações opcionais). Pendências outward‑facing (ações do
> autor, não feitas): `docker push docker.io/arthurd3/rinha-fraud:onda5`;
> `git push origin main` & `git push origin submission`; PR upstream
> adicionando `participants/arthurd3.json`. Detalhe completo dos gates em
> `docs/ARCHITECTURE.md` §9 (subseção "Wave 5"). *Texto original do plano
> preservado abaixo como histórico de design.*

**Em uma frase**: eliminar warmup, reduzir RSS, maximizar score.

**Pré-requisitos**:
- Conceitos: `CONCEITOS.md` §7 (Native Image), §9 (zero-allocation), §11 (PGO).
- Onda 4 concluída.

**Mapa**:
1. Trocar builder do Dockerfile para `container-registry.oracle.com/graalvm/native-image:21` (**Oracle GraalVM 21**, GFTC grátis — tem PGO; ver §5.2 nota 2026-05-18).
2. Criar `reflect-config.json` (provavelmente vazio — temos 0 reflection).
3. Criar `resource-config.json` se algum recurso precisar embarcar (.bin ficam externos).
4. Build inicial Native Image, rodar k6, capturar perf.
5. Build com `--pgo-instrument`, rodar workload (k6 + warmup), gerar `default.iprof`.
6. Build final com `--pgo`.
7. Validar Vector API gerou intrinsics: `-Dgraal.PrintCompilation=true | grep euclideanInt8`.
8. (Opcional) Switch para `--static --libc=musl` + `FROM scratch`.
9. Rodar k6 novamente, comparar `final_score` com Onda 4.
10. Profile final: flamegraph + JFR ou `perf` para últimos gargalos.

**Critério de saída**:
- ✅ Native binary < 80 MB.
- ✅ RSS por instância < 80 MB.
- ✅ Sem warmup observável (p99 estável das primeiras requisições).
- ✅ Vector API ainda gera AVX2 (validado em logs).
- ✅ `final_score` Native ≥ HotSpot.

**Se algo der errado**:
- ClassNotFoundException → reflection não documentada (§12.3).
- Vector API regrediu → fallback para Mandrel 21 LTS (§12.1).
- p99 piorou → PGO inverteu hint (workload de treino não-representativo).

**Estimativa**: 3-5 dias (Native Image debug é doloroso). **Risco**: 🔴.

---

### 9.6 Onda 6 — Otimizações finais (opcional) 🟢

> ✅ **Nota 2026‑05‑18.** O projeto **fechou tecnicamente na Onda 5** (Native +
> PGO, validada — ver §9.5). A Onda 6 permanece **opcional** e **não
> implementada**: nada aqui é necessário para a entrega. Itens migrados/úteis:
> eliminar a alocação `takeTop5` (drenar para scratch reusado em
> `HnswScratch`) — era listado como candidato "Onda 5" no `ARCHITECTURE.md`
> §5, mas a Onda 5 foi puramente AOT+PGO (preservou comportamento, Gate B),
> então passa a ser item de Onda 6.
>
> ✅ **Sub‑nota 2026‑05‑18 — spec + tutorial prontos (impl. à mão pendente).**
> Os itens **`takeTop5` zero‑alloc** (eliminar o `int[n]` por query drenando
> para `HnswScratch.tN`/`tD` reusados — `int[CAP]`, abordagem A,
> byte‑idêntico por construção) e **`LICENSE` MIT** (Copyright (c) 2026
> arthurd3) agora têm **spec** committado
> (`docs/superpowers/specs/2026-05-18-onda6-zeroalloc-license-design.md`,
> commit `a6e330a`) e **tutorial** (`docs/TUTORIAL_ZEROALLOC.md`). Status =
> **spec + tutorial prontos; implementação à mão pendente** (driven‑by‑tutorial,
> igual às ondas anteriores: o autor implementa, Claude valida Gates 1
> [byte‑idêntico: `RecallHnsw` 96,89 %/99,90 % idêntico, `Rbh2Equiv` 0/3 M, 2
> oráculos] + 2 [zero‑alloc provado via `ThreadMXBean`] + 3 [k6 opcional]).
> Comportamento byte‑idêntico (sem mudança de score). Os demais itens da §9.6
> (grid‑search M/ef, `sendfile`, prefetch mmap, auditoria NIO) **permanecem
> opcionais e não especificados**.

- Substituir `com.sun.net.httpserver` paths residuais por NIO 100%.
- Tunar HNSW (M, ef_construction, ef_search) por grid search.
- Implementar prefetch de páginas mmap durante build do HNSW.
- TCP zero-copy com `sendfile` para responses canned.
- Submeter prévia oficial via issue `rinha/test`.

---

## 10. Métricas e checkpoints

### 10.1 Por onda — métricas obrigatórias

| Métrica | Como medir | Onde guardar |
|---|---|---|
| `final_score` k6 | `k6 run rinha-de-backend-2026/test/test.js`, ler `results.json` | `docs/scores.md` (apêndice) |
| p50 / p95 / p99 / p999 | Mesmo k6 result | Idem |
| RSS por container | `docker stats --no-stream` | Idem |
| GC pauses (HotSpot) | JFR ou `-Xlog:gc` | `docs/profiles/onda-N/` |
| Peak RSS (Native) | `cat /proc/<pid>/status \| grep VmHWM` | Idem |
| Recall HNSW | Test custom comparando top-5 HNSW vs brute force | `docs/recall.md` |

### 10.2 Ferramentas

- **k6**: benchmark oficial, fonte da verdade do score.
- **JMH**: microbenchmarks dentro do projeto Java.
- **`perf stat`**: análise de cycles/instructions/cache-misses.
- **`async-profiler`** ou **JFR**: flamegraphs (HotSpot only).
- **`docker stats`**: memória e CPU em tempo real.
- **`/usr/bin/time -v`**: peak RSS de execução única.

### 10.3 Diagnóstico — "se métrica X estoura, investigue Y"

Resumo (versão completa em `IMPACTO.md`):

| Sintoma | Investigar |
|---|---|
| p99 > 5 ms | Brute force ainda ativo · Vector API caiu para escalar · Parser alocando · GC pause · Warmup C2 incompleto |
| RAM > 175 MB/instância | Dataset float32 · Heap mal configurado · Adjacency HNSW grande · DirectBuffer leak |
| Recall < 95% | ef_search baixo · Quantização global perdendo · M baixo · Sentinela mal tratado |
| Throughput < 800 RPS | Multi-thread em 1 CPU · Syscall por request · Logging em hot path |
| Erro 5xx | OOMKill · Bind falhou · Timeout HAProxy · Reflection sem config |

### 10.4 Diretório `docs/profiles/`

A cada onda significativa, salvar:
- `flamegraph.svg`
- `gc.log` (HotSpot)
- `k6-result.json`
- `notes.md` com observações qualitativas

---

## 11. Decisões em aberto

| ID | Decisão | Status | Trigger numérico para revisitar | Custo de mudar tarde |
|---|---|---|---|---|
| 11.1 | Single-thread vs 2-thread | single | profiling mostra `select()` blocking enquanto KNN poderia rodar | médio |
| 11.2 | mmap shared via tmpfs | bind-mount mesmo arquivo | RSS total > 320 MB | baixo |
| 11.3 | HNSW vs IVFPQ vs híbrido | HNSW | recall < 90% mesmo com `ef_search=200` | alto |
| 11.4 | SO_REUSEPORT em 2 processos | porta interna por instância | HAProxy vira gargalo | baixo |
| 11.5 | Quantização global vs per-dim | global | recall < 95% | médio |
| 11.6 | Pré-warmup do Selector/heap | sem | tail das primeiras 100 reqs > 3× steady | baixo |
| 11.7 | Compactação `hnsw.bin` (int24/varint) | int32 | RSS apertar | médio |

---

## 12. Armadilhas conhecidas

### Índice por sintoma

| Sintoma | Vá em |
|---|---|
| Vector API regrediu em Native Image | §12.1 |
| Latência alta com `com.sun.net.httpserver` | §12.2 |
| `ClassNotFoundException` em Native | §12.3 |
| Recurso embarcado não acessível em Native | §12.4 |
| `ReadOnlyBufferException` em mmap | §12.5 |
| Recall ruim em outliers | §12.6 |
| `hnsw.bin` muda a cada build | §12.7 |
| Latência LB sobe ~30% | §12.8 |
| `FROM scratch` falha "exec format error" | §12.9 |
| Container morre sem log | §12.10 |
| Distância int8 com valores 128-255 vira negativo | §12.11 |
| Soma de quadrados estoura int16 | §12.12 |

### 12.1 Native Image + Vector API

**Sintoma**: distância euclidiana cai para escalar silenciosamente após Native Image build, p99 piora 5×.

**Mitigação**:
- Sempre buildar com `-Dgraal.PrintCompilation=true | grep -i vector`.
- Manter teste JMH que falha se distância isolada > X µs.
- Usar **Oracle GraalVM 21** (não bleeding edge 22/23; Mandrel/CE não tem PGO — ver §5.2 nota 2026-05-18).

> ✅ **As‑built 2026‑05‑18 (Onda 5) — resolvido por REMOÇÃO, não por
> fallback.** Esta armadilha não se materializou como "regressão silenciosa":
> na verdade o `sqDistI8` SIMD **quebrava o LINK do Native Image** (campos
> `static VectorSpecies` → `VectorSupport.getMaxLaneCount`). Como era
> **código morto desde a Onda 2b** (0 callers; produção e build sempre
> usaram `sqDistI8Scalar`, que era 3,8× mais rápido para este shape
> 14/16‑lane no HotSpot/AVX2), a Onda 5 simplesmente **deletou** `sqDistI8`
> (+ testes `DistEquivI8`/`BenchSearch`). Não há mais SIMD no projeto ⇒
> esta seção é histórica; `sqDistI8Scalar` byte‑idêntico ao HEAD HotSpot
> garante comportamento inalterado (Gate B). O fallback "Mandrel 21"
> citado em §9.5 ("Se algo der errado") **não foi necessário**.

### 12.2 `com.sun.net.httpserver` não é descartável

**Sintoma**: começamos "só para validar" e depois é quase impossível trocar sem reescrever metade.

**Mitigação**: vamos direto NIO Selector na Onda 1. Não passar pela tentação. **Hoje no projeto isso já está em uso (`server/ServerHTTP.java`) — Onda 0 marca como deprecated, Onda 1 substitui completamente.**

### 12.3 Reflection no Native Image

**Sintoma**: `ClassNotFoundException` ou `NoSuchMethodException` em runtime que não acontece em HotSpot.

**Mitigação**: criar `reflect-config.json` listando classes acessadas via reflection. Como nosso código é zero-reflection, deve estar vazio. Erro = bug.

### 12.4 Recursos embarcados

**Sintoma**: `.bin` files dentro do JAR não são acessíveis em Native sem `resource-config.json`.

**Mitigação**: nossos `.bin` ficam **fora do JAR** (`/app/references.bin`), acessados via `Files.newByteChannel(Path)`. Sem problema.

### 12.5 `MappedByteBuffer.force()` em RO

**Sintoma**: `ReadOnlyBufferException` ao chamar `force()`.

**Mitigação**: nunca chamar `force()` em arquivos read-only. Acesso é só leitura.

### 12.6 Quantização escala global perde recall em outliers

**Sintoma**: vetores com `amount` ou `km_from_home` extremos ficam saturados em `127`, perdendo discriminação.

**Mitigação**: medir recall por percentil de magnitude. Se aparecer, ir para per-dimension (§5.7).

### 12.7 HNSW não-determinístico

**Sintoma**: `hnsw.bin` muda a cada build → testes instáveis.

**Mitigação**: `Random rng = new Random(42L)` no `HnswBuilder`. Seed fixa.

### 12.8 HAProxy modo HTTP

**Sintoma**: latência de LB cresce ~30% por request.

**Mitigação**: `mode tcp` é mandatório. Validar com `haproxy -c -f haproxy.cfg`.

### 12.9 Docker `scratch` sem `--static`

**Sintoma**: container sobe e o binário "não existe" (`exec: not found`).

**Mitigação**: `scratch` requer Native compilado com `--static --libc=musl`. Caso contrário, `distroless`.

### 12.10 cgroup OOMKill silencioso

**Sintoma**: instância morre sem log, k6 reporta erros HTTP.

**Mitigação**: `dmesg | grep -i "oom-killer"` no host. Se aparecer, baixar `-Xmx` ou simplificar dataset.

### 12.11 `byte` em Java é signed

**Sintoma**: quantizado int8 com valores 128-255 vem negativo. `byte b = (byte) 200; int i = b;` retorna `-56`.

**Mitigação**: sempre usar `b & 0xFF` ao converter para int. Vector API tem APIs unsigned (`VectorOperators.UNSIGNED_*`).

### 12.12 Soma de quadrados em int16 estoura

**Sintoma**: distância Euclidiana entre `-100` e um vetor positivo pode estourar `int16`.

**Mitigação**: validar que `int16` cobre soma de quadrados de 14 dims com diff máxima `(127 - (-128))² = 65025`. Soma de 14: `910k`, cabe em `int32` mas não em `int16`. **Promover para `int32` na soma.**

---

## 13. Comandos úteis

### 13.1 Build local

```bash
# JAR (HotSpot)
cd fraudAPI
./mvnw package

# Native Image
./mvnw -Pnative -DskipTests package

# Pré-processar dataset (one-shot)
java -cp target/fraudAPI-1.0-SNAPSHOT.jar org.fraudDetection.tools.PreprocessDataset \
  ../rinha-de-backend-2026/resources/references.json.gz \
  ../rinha-de-backend-2026/resources/mcc_risk.json \
  ../rinha-de-backend-2026/resources/normalization.json \
  out/references.bin out/labels.bin out/quant.json

# Build HNSW
java -cp target/fraudAPI-1.0-SNAPSHOT.jar org.fraudDetection.tools.BuildHnsw \
  out/references.bin out/hnsw.bin
```

### 13.2 Run

```bash
# Single instância local (sem LB)
java -jar target/fraudAPI-1.0-SNAPSHOT.jar 9999

# docker compose
cd docker
docker compose up --build

# Logs
docker compose logs -f api1
```

### 13.3 Test

```bash
# Curl manual
curl http://localhost:9999/ready
curl -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d @../rinha-de-backend-2026/resources/example-payloads.json

# k6 oficial
k6 run ../rinha-de-backend-2026/test/test.js \
  -e BASE_URL=http://localhost:9999

# Resultado
cat ../rinha-de-backend-2026/test/results.json | jq '.scoring.final_score'
```

### 13.4 Profile

```bash
# JMH (microbench)
./mvnw test -Pjmh

# JFR (HotSpot)
java -XX:StartFlightRecording=duration=30s,filename=fraud.jfr -jar fraudAPI.jar 9999
jfr print --events jdk.CPULoad fraud.jfr

# perf (Native)
perf record -g -p $(pgrep fraud-api)
perf report

# Memory peak
/usr/bin/time -v java -jar fraudAPI.jar 9999

# Container memory
docker stats --no-stream
```

### 13.5 Branch submission workflow

```bash
git checkout -b submission
git rm -rf fraudAPI/src tools/ .mvn docs/
git add docker-compose.yml haproxy.cfg info.json Dockerfile *.bin
git commit -m "submission"
git push origin submission
```

### 13.6 Submeter prévia

1. Push branches `main` e `submission`.
2. PR adicionando `participants/arthurd3.json` no repo da Rinha.
3. Issue no próprio repo da Rinha com texto contendo `rinha/test`.
4. Aguardar Engine da Rinha rodar e comentar resultado.

---

## 14. Glossário

Termos técnicos usados no plano. Para tutorial completo de cada conceito, ver `CONCEITOS.md`.

| Termo | Definição |
|---|---|
| **AOT** | Ahead-Of-Time compilation. Compila tudo antes de rodar. Native Image. (`CONCEITOS.md` §7) |
| **ANN** | Approximate Nearest Neighbor. Família de algoritmos que sacrifica recall por velocidade. (§2) |
| **AVX2** | Extensão x86 SIMD com registradores 256-bit. CPU Haswell tem. (§5) |
| **beam search** | Busca que mantém os top-N candidatos a cada passo. Usado no nível 0 do HNSW. (§3) |
| **cgroup** | Linux Control Group. Limite de RSS/CPU por container. |
| **CDS / AppCDS** | Class Data Sharing. Pré-carrega classes para acelerar startup do HotSpot. |
| **content-length** | Header HTTP indicando bytes do body. Alternativa: chunked. (§10) |
| **C10K** | Problema de servir 10k conexões em uma máquina. Solução: NIO multiplexing. (§6) |
| **distroless** | Imagem Docker mínima (~20MB) com glibc, sem shell. |
| **ef_construction** | Parâmetro HNSW: largura do beam durante build. (§3) |
| **ef_search** | Parâmetro HNSW: largura do beam durante query. Tunável em runtime. (§3) |
| **G1 / ZGC / Shenandoah** | GCs concurrent do HotSpot. Pauses 1-50ms. |
| **HNSW** | Hierarchical Navigable Small World. Índice ANN multi-camada. (§3) |
| **IVF** | Inverted File Index. Clustering + brute force dentro do cluster. (§2) |
| **JIT** | Just-In-Time compilation. C1/C2 do HotSpot. (§7) |
| **k-NN** | k Nearest Neighbors. Algoritmo de classificação por vizinhos. (§1) |
| **keep-alive** | HTTP/1.1 default: reusar TCP connection para múltiplas requests. (§10) |
| **LSH** | Locality-Sensitive Hashing. Hash que preserva proximidade. (§2) |
| **M (HNSW)** | Vizinhos por nó nas camadas superiores. (§3) |
| **MADV_RANDOM** | Hint para `madvise()` indicando acesso aleatório (sem readahead). (§8) |
| **mL (HNSW)** | Fator de probabilidade de nível: `1/ln(M)`. (§3) |
| **mmap** | Memory-mapped file. Kernel mapeia arquivo no espaço virtual. (§8) |
| **MCC** | Merchant Category Code. Identificador de tipo de comércio (4 dígitos). |
| **NIO Selector** | Java wrapper sobre epoll/kqueue. Multiplexing de FDs. (§6) |
| **OOMKill** | Linux kernel mata processo que estoura cgroup memory. |
| **Page Cache** | RAM gerenciada pelo kernel cacheando páginas de disco. (§8) |
| **PGO** | Profile-Guided Optimization. Compilador usa profile real para otimizar. (§11) |
| **PQ** | Product Quantization. Divide vetor em sub-vetores e quantiza com codebook. (§2) |
| **recall@k** | `|top-k retornados ∩ top-k corretos| / k`. (§2) |
| **reactor pattern** | 1 thread evento-driven processando I/O em sequência. (§6) |
| **RSS** | Resident Set Size. Páginas físicas residentes em RAM. cgroup mede isso. (§8) |
| **SIMD** | Single Instruction, Multiple Data. Operações paralelas em registrador grande. (§5) |
| **Vector API** | `jdk.incubator.vector`. Wrapper Java para SIMD. (§5) |
| **virtual threads** | Project Loom. Threads gerenciadas pelo runtime, "estacionam" em I/O. (§6) |
| **VSZ** | Virtual Size. Espaço virtual mapeado (inclui mmap não tocado). (§8) |
| **warmup (C2)** | Tempo para HotSpot detectar hot paths e compilar com C2. ~10-30s. (§7) |
| **zero-allocation** | Hot path que não chama `new`. Evita GC pause. (§9) |

---

## 15. Recursos externos

### HNSW

- **Paper**: Malkov & Yashunin 2016 — "Efficient and robust approximate nearest neighbor search using HNSW" — https://arxiv.org/abs/1603.09320
- **Reference impl** (C++): https://github.com/nmslib/hnswlib — leitura recomendada do `hnswalg.h`, ~600 linhas legíveis
- **Apache Lucene HNSW** (Java): https://github.com/apache/lucene/tree/main/lucene/core/src/java/org/apache/lucene/util/hnsw — referência idiomática Java
- **JVector** (DataStax): https://github.com/jbellis/jvector — alternativa Java pura

### HTTP parsing

- **picohttpparser**: https://github.com/h2o/picohttpparser — parser HTTP/1.1 em ~300 linhas C, padrão de referência para hand-roll
- **Helidon Nima HTTP**: https://github.com/helidon-io/helidon/tree/main/nima — para inspirar layout

### Vector API

- **JEP 448**: https://openjdk.org/jeps/448
- **Richard Startin's blog**: https://richardstartin.github.io/ — posts sobre Vector API, distance, dot product
- **Tutorial oficial**: https://docs.oracle.com/en/java/javase/21/core/vector-api.html

### GraalVM Native Image

- **Docs**: https://www.graalvm.org/jdk21/reference-manual/native-image/
- **PGO**: https://www.graalvm.org/jdk21/reference-manual/native-image/optimizations-and-performance/PGO/
- **Mandrel** (Red Hat): https://github.com/graalvm/mandrel
- **Vector API + Native issues**: https://github.com/graalvm/mandrel/issues — buscar "vectorapi"

### NIO

- **Java NIO Tutorial**: https://docs.oracle.com/javase/tutorial/essential/io/index.html (datado mas válido)
- **Reactor pattern**: https://medium.com/coderscorner/java-nio-and-the-reactor-pattern-9e2cbab5a945

### Rinha

- **Repo oficial 2026**: https://github.com/zanfranceschi/rinha-de-backend-2026
- **Edição 2025**: https://github.com/zanfranceschi/rinha-de-backend-2025
- **Discord**: https://discord.gg/Eca6gJba8R (canal #rinha-2026)
- **Resultados em tempo real**: https://rinhadebackend.com.br/

### k6

- **Docs**: https://grafana.com/docs/k6/latest/
- **Script da Rinha**: `rinha-de-backend-2026/test/test.js`

---

## 16. Apêndices

### A. Decision log

| Data | Decisão | Por quê | Status |
|---|---|---|---|
| 2026-05-04 | Stack inicial conforme §5 | Plan agent + análise de budget | Travado para Onda 1 |
| 2026-05-04 | Estrutura 3 arquivos (RINHA_PLAN + CONCEITOS + IMPACTO) | Pedagogia separada de execução | Vigente |
| 2026-05-18 | Builder = **Oracle GraalVM 21** (GFTC, tem PGO) — não Mandrel/CE | "Mandrel + PGO" era contraditório (PGO é Oracle‑only); GFTC é grátis em produção | ✅ Aplicado e validado (Onda 5) — ver §5.2 nota |
| 2026-05-18 | **Remover** `sqDistI8` SIMD (+ testes `DistEquivI8`/`BenchSearch`) | Campos `static VectorSpecies` quebram o link do Native Image; código morto desde Onda 2b (0 callers, 3,8× mais lento) | ✅ Removido (Onda 5); `sqDistI8Scalar` byte‑idêntico ⇒ comportamento inalterado (Gate B) |
| 2026-05-18 | **Onda 5 CONCLUÍDA + VALIDADA** — 4 gates verdes on‑device | Native AOT + PGO: sem warmup, `final_score` 4393,85 @ p99 0,59 ms, `http_errors` 0, sem `OOMKilled` | ✅ **Projeto fecha tecnicamente na Onda 5** (Onda 6 = opcional). `docker push`/`git push`/PR = ações pendentes do autor |

### B. Scores por onda

| Onda | Data | p50 | p95 | p99 | RSS total | final_score | Notas |
|---|---|---|---|---|---|---|---|
| 4b | 2026-05-18 | — | — | — | pico 103 MiB / 350 | 3611–4394 | HotSpot conteinerizado, HAProxy `mode tcp` + 2 inst.; live‑daemon |
| **5** | **2026-05-18** | — | — | **0,59 ms** | sem `OOMKilled` (cgroup 350 MB; `docker stats` ~26,6 MiB/inst) | **4393,85** | ✅ Native AOT 12 MB + PGO, **sem warmup**, `http_errors` 0; iguala melhor run 4b. `sqDistI8` SIMD removido (código morto, quebrava o link). **Fecha o projeto** |

### C. Notas qualitativas

(a ser preenchido com surpresas e descobertas durante a empreitada)

---

**Próxima ação**: começar a Onda 0.

1. Corrigir `pom.xml` para Java 21.
2. Configurar Maven wrapper.
3. Marcar `ServerHTTP.java` como deprecated.
4. Validar `./mvnw compile` passa.

Tempo estimado da Onda 0: 2-4h.
