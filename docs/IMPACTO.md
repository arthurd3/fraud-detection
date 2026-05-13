# Mapa de Impacto — quem afeta quem

> Material de apoio para `RINHA_PLAN.md`. Cada decisão técnica afeta múltiplas métricas. Esta tabela cruza decisões × métricas e diz **quanto** + **onde no código** + **quando vira ruim**. Use para priorizar otimizações: olhe a métrica que está apertando, encontre a decisão que mais influencia, ataque por ali.

## Tabela cruzada

Legenda: 🟢 ganho / 🔴 custo / 🟡 trade-off / `-` neutro. Quantidade de emojis = magnitude (1 = pequeno, 2 = médio, 3 = grande).

| Decisão | p99 | RAM | Recall | Throughput | Build time | Complexidade |
|---|---|---|---|---|---|---|
| **HNSW vs brute force** | 🟢🟢🟢 | 🟡 | 🟡 | 🟢🟢 | 🔴🔴 | 🔴🔴 |
| **int8 vs float32** | 🟢 | 🟢🟢🟢 | 🟡 | 🟢 | - | 🟡 |
| **Native Image vs HotSpot** | 🟢🟢 (warmup) | 🟢🟢 | - | 🟡 | 🔴🔴 | 🔴 |
| **PGO em Native Image** | 🟢🟢 | - | - | 🟢 | 🔴🔴 | 🟡 |
| **NIO Selector raw vs httpserver** | 🟢🟢 | 🟢 | - | 🟢🟢 | - | 🔴🔴 |
| **JSON hand-roll vs Jackson** | 🟢🟢 | 🟢 | - | 🟢 | - | 🔴🔴 |
| **SIMD (Vector API) vs escalar** | 🟢🟢 | - | - | 🟢🟢 | - | 🟡 |
| **mmap vs heap byte[]** | 🟢 (cold) | 🟢🟢 | - | - | - | 🟡 |
| **HAProxy TCP vs HTTP mode** | 🟢 | 🟢 | - | 🟢 | - | - |
| **Single-thread reactor vs 2-thread** | 🟢 (1 CPU) | 🟢 | - | 🟡 | - | 🟢 |
| **Quantização escala global vs per-dim** | 🟢 | - | 🔴 | - | - | 🟢 |
| **Maven vs Gradle** | - | - | - | - | 🟡 | 🟢 |
| **distroless vs scratch+musl** | - | 🟢 | - | - | 🟡 | 🟡 |
| **Zero-allocation hot path** | 🟢🟢 (tail) | 🟢 | - | 🟢 | - | 🔴 |
| **Response canned vs runtime format** | 🟢 | - | - | 🟢 | - | 🟢 |
| **HNSW M=16 vs M=32** | 🔴 | 🔴 | 🟢 | 🔴 | 🔴 | - |
| **ef_search=50 vs 200** | 🔴🔴 | - | 🟢🟢 | 🔴 | - | - |

---

## Notas por decisão

### HNSW vs brute force

- **Quanto**: HNSW search ~200-500 µs vs brute force float32 ~30 ms (60-150× mais rápido). Brute force int8 SIMD: ~3-5 ms (ainda 10-20× pior que HNSW).
- **Onde aparece**: `knn/HnswIndex.java`, `knn/HnswBuilder.java`, `knn/PriorityQueueMin.java`, arquivo `hnsw.bin`.
- **Quando vira ruim**: se recall HNSW < 90%, brute force int8 SIMD pode ser viável (3-5 ms ainda dá +2000 em score_p99).
- **Métrica primária afetada**: `score_p99` (a Rinha — única forma de chegar a +3000 nessa métrica).

### int8 vs float32

- **Quanto**: 4× menos bytes (3M × 14 × 4B = 168 MB → 42 MB). Distance kernel 2-4× mais rápido (32 lanes int8 vs 8 lanes float32 em AVX2).
- **Onde aparece**: `knn/Quantizer.java`, `knn/DistanceFunctions.java`, `dataset/MmapDataset.java`, `references.bin`.
- **Quando vira ruim**: se recall < 95% por causa da quantização (outliers saturados em 127), migrar para per-dimension scale ou float16.
- **Métrica primária afetada**: RAM (passa de "estoura" para "cabe folgado") e p99 (cache hit em L1).

### Native Image vs HotSpot

- **Quanto**: warmup eliminado (primeiros 10-30s já no pico vs C2 demorando ~10k requests). RSS: 30-80 MB Native vs 80-200 MB HotSpot. Steady-state throughput **HotSpot vence levemente** sem PGO; com PGO, Native equivale.
- **Onde aparece**: `Dockerfile` (builder GraalVM), `pom.xml` profile `native`, `reflect-config.json`, `resource-config.json`.
- **Quando vira ruim**: se Vector API regredir para escalar em Native (validar com `-Dgraal.PrintCompilation=true`), ou se reflection for inevitável.
- **Métrica primária afetada**: p99 dos primeiros 30s (sem warmup) e RAM.

### PGO em Native Image

- **Quanto**: +10 a 30% throughput, -5 a 15% p99 tail.
- **Onde aparece**: 2 builds no Dockerfile (instrument + final), `default.iprof`.
- **Quando vira ruim**: profile gerado com workload não-representativo pode **piorar** decisões (branch hints invertidos).
- **Métrica primária afetada**: p99 e throughput steady-state. Mais relevante para sair de "+5500" e bater "+5800".

### NIO Selector raw vs com.sun.net.httpserver

- **Quanto**: ~5-20 µs hand-roll vs ~50-200 µs httpserver (overhead que come 5-20% do orçamento p99=1ms).
- **Onde aparece**: `server/NioServer.java`, `server/HttpParser.java`, `server/ConnectionState.java`. Atualmente projeto usa `com.sun.net.httpserver` em `server/ServerHTTP.java` — **descartar na Onda 1**.
- **Quando vira ruim**: nunca, em Rinha. Em produção real (multi-endpoint, headers complexos) httpserver pode ser aceitável.
- **Métrica primária afetada**: p99 e throughput (latência por request).

### JSON hand-roll vs Jackson

- **Quanto**: ~5-10 µs hand-roll byte-array vs ~50-100 µs Jackson (com tree model). Schema fixo de 14 campos torna hand-roll viável.
- **Onde aparece**: `json/FraudRequestParser.java`, `json/FraudResponseSerializer.java`.
- **Quando vira ruim**: se schema mudar muito durante dev, hand-roll vira manutenção alta. Mitigar: testes contra `example-payloads.json`.
- **Métrica primária afetada**: p99 e alocação (Jackson aloca Maps/Strings).

### SIMD (Vector API) vs escalar

- **Quanto**: distance kernel ~5-10 ns SIMD vs ~50-100 ns escalar (10× em 14 dims; mais em D maior). Para 1500 dists/query: ~10 µs vs ~150 µs.
- **Onde aparece**: `knn/DistanceFunctions.java` (`euclideanInt8`).
- **Quando vira ruim**: se Native Image não inlinar Vector API (regressão silenciosa). Sempre validar logs de compilação.
- **Métrica primária afetada**: p99 (kernel é o dominante do hot path).

### mmap vs heap byte[]

- **Quanto**: heap byte[] = 42 MB RSS imediato; mmap = só páginas tocadas (~21 KB durante busca HNSW + page cache do kernel compartilhado entre instâncias).
- **Onde aparece**: `dataset/MmapDataset.java`, `dataset/BinaryFormat.java`.
- **Quando vira ruim**: se padrão de acesso for sequencial maciço, heap pode ser melhor (page cache vence). HNSW = random access, mmap vence.
- **Métrica primária afetada**: RAM (e RSS reportado em cgroup).

### HAProxy TCP vs HTTP mode

- **Quanto**: ~10-30% latência em cada hop. TCP só faz round-robin de conexão; HTTP parseia headers (custo de CPU + memória).
- **Onde aparece**: `docker/haproxy.cfg`.
- **Quando vira ruim**: nunca para Rinha (LB não pode ter lógica). Em produção real, HTTP mode permite path-based routing.
- **Métrica primária afetada**: p99 (latência adicionada no LB).

### Single-thread reactor vs 2-thread (NIO + KNN worker)

- **Quanto**: em 1 CPU compartilhada, qualquer thread extra adiciona context switch (~2-5 µs por troca). Ganho de paralelismo é zero.
- **Onde aparece**: `server/NioServer.java` (loop principal).
- **Quando vira ruim**: se profiling mostrar `Selector.select()` bloqueando enquanto KNN poderia estar rodando (improvável com 1ms total).
- **Métrica primária afetada**: throughput e p99.

### Quantização escala global vs per-dimension

- **Quanto**: global = 1 multiplicação na quantize da query; per-dim = 14 multiplicações + 14 saturates.
- **Onde aparece**: `knn/Quantizer.java`, geração de `references.bin` em `tools/PreprocessDataset.java`.
- **Quando vira ruim**: se uma ou duas dimensões dominam o range (ex: amount), global perde resolução nas outras → recall cai.
- **Métrica primária afetada**: recall (e indiretamente `score_det`).

### Maven vs Gradle

- **Quanto**: Maven é XML, configuração mais verbosa, mas familiaridade maior. Gradle Kotlin DSL mais conciso, build incremental melhor (irrelevante em CI/Docker).
- **Onde aparece**: `pom.xml` vs `build.gradle.kts`.
- **Quando vira ruim**: nunca neta Rinha. Em projetos grandes, Gradle escala melhor.
- **Métrica primária afetada**: dev velocity (não pontua).

### distroless vs scratch+musl

- **Quanto**: distroless ~20 MB final (com glibc); scratch+musl ~5-10 MB. Para Rinha (350 MB total), 15 MB de diferença é irrelevante.
- **Onde aparece**: `Dockerfile` última stage.
- **Quando vira ruim**: scratch precisa de `--static --libc=musl` no Native Image (não trivial, mais erros de link).
- **Métrica primária afetada**: tamanho da imagem (não pontua, mas afeta deploy time).

### Zero-allocation hot path

- **Quanto**: cada `new` em hot path = pressão GC eventual. GC pause de 5-50ms (G1) ou 5-200ms (Native Serial) destrói p99=1ms.
- **Onde aparece**: `server/ConnectionState.java` (buffers reutilizados), parser, distance kernel — em todos os arquivos do hot path.
- **Quando vira ruim**: se profile mostrar GC pause em qualquer onda > 4. Detectar com JFR (`jdk.GarbageCollection`) ou `-Xlog:gc`.
- **Métrica primária afetada**: p99 tail (não p50 — GC é raro mas catastrófico quando bate).

### Response canned vs runtime format

- **Quanto**: `String.format("%.1f", x)` ou `Double.toString()` aloca + custa ~1-5 µs. Lookup em array de bytes pré-formatados: ~10 ns.
- **Onde aparece**: `json/FraudResponseSerializer.java`.
- **Quando vira ruim**: nunca em Rinha (só 12 combinações possíveis: 2 booleans × 6 fraud_scores).
- **Métrica primária afetada**: p99.

### HNSW M=16 vs M=32

- **Quanto**: M=32 dobra adjacency RAM (~80 MB extra), aumenta build time ~2×, melhora recall ~1-3%, aumenta `dist()` calls por query.
- **Onde aparece**: parâmetro em `tools/BuildHnsw.java` e arquivo `hnsw.bin`.
- **Quando vira ruim**: M=16 produz recall < 95% mesmo com `ef_search` alto.
- **Métrica primária afetada**: trade-off recall × p99 × RAM.

### ef_search=50 vs ef_search=200

- **Quanto**: ef_search=200 → ~4× mais `dist()` calls (~6000 vs ~1500), recall sobe de ~97% para ~99%. Latência sobe ~3×.
- **Onde aparece**: `knn/HnswIndex.java` (constante runtime).
- **Quando vira ruim**: ef_search=50 produz recall < 95% que prejudica `score_det`. Tunar empiricamente.
- **Métrica primária afetada**: trade-off `score_p99` × `score_det`. Pode ser tunado em runtime sem rebuild.

---

## "Se métrica X estoura, investigue Y"

Lista de diagnóstico. Use após primeira medida em cada onda.

### p99 estoura (> 5 ms)

Em ordem de probabilidade:

1. **Brute force ainda ativo** (Onda 1) → migrar para HNSW (Onda 3).
2. **Vector API caiu para escalar** → checar `-Dgraal.PrintCompilation=true | grep euclideanInt8` ou logs JIT.
3. **Parser JSON alocando** → checar JFR `jdk.ObjectAllocationInNewTLAB` no hot path.
4. **GC pause** (HotSpot) → checar `-Xlog:gc*` para pauses > 1ms; reduzir alocação.
5. **Warmup C2 incompleto** (HotSpot) → considerar Native Image (Onda 5) ou warmup explícito no startup.
6. **HAProxy modo HTTP** → mudar para `mode tcp`.
7. **Cache miss em HNSW** → verificar `MADV_RANDOM`, working set > L2 implica vetores não-int8 ainda.
8. **Selector com múltiplas threads** → reduzir para 1.

### RAM estoura (> 175 MB por instância)

1. **Dataset em float32** → migrar para int8 (Onda 2).
2. **Heap configurado errado** → setar `-Xmx32m` ou `-XX:MaxRAMPercentage=15` para HotSpot; Native Image limita por default.
3. **Adjacency HNSW grande** (M=32 + N=3M) → reduzir M ou empacotar com int24/varint.
4. **Heap grow durante load** → carregar dataset via mmap, não ler para `byte[]`.
5. **DirectByteBuffer não fechado** → cada conexão acumula direct memory; pool e reutilizar.
6. **Cache do kernel duplicado** (mmap em volumes separados) → bind-mount o mesmo arquivo nas 2 instâncias.

### Recall < 95%

1. **ef_search baixo** (< 50) → aumentar até 100 ou 200.
2. **Quantização global perdendo dimensões pequenas** → migrar para per-dimension.
3. **M baixo** (< 16) → rebuilder com M=32.
4. **ef_construction baixo** (< 200) → rebuilder.
5. **Random seed instável no builder** → fixar seed para builds determinísticos.
6. **Sentinela -1 mal tratado** → conferir `last_transaction: null` no parser.

### Throughput baixo (RPS < 800 sustentado)

1. **Multi-thread em 1 CPU** → context switch caro; consolidar em single-thread reactor.
2. **Syscall por request** (read/write múltiplos) → aumentar `SO_RCVBUF`, batch writes.
3. **Logging em hot path** → mover para erro 5xx apenas.
4. **Connection close por request** (HTTP/1.0 mode) → habilitar keep-alive, reusar buffers.
5. **HAProxy thread único saturando** → checar `nbthread` (default 1 está OK para Rinha, mas se LB virar gargalo, subir).
6. **TCP_NODELAY off** → habilitar para evitar Nagle delay em respostas curtas.

### Erro 5xx em qualquer porcentagem

1. **OOMKill silencioso** → `dmesg | grep oom-killer` no host; baixar `-Xmx` ou simplificar dataset.
2. **Bind falhou** → outra instância já na porta 9000, ou `SO_REUSEADDR` faltando.
3. **Timeout HAProxy** → `timeout server 2500ms` muito apertado se requests reais demoram > 2s (não deveria).
4. **NullPointer no parser** → payload com campo faltando, validação no parser não tratou.
5. **Native Image classloading** → `ClassNotFoundException` por reflection; popular `reflect-config.json`.

---

## Como usar este mapa na prática

1. **Antes de cada onda**: olhe a tabela e identifique quais métricas a onda vai mexer. Esperar mudança nelas.
2. **Após benchmark**: se métrica X piorou em vez de melhorar, use a seção "Se X estoura, investigue Y" para diagnosticar.
3. **Para priorizar otimização**: ordene linhas pela métrica mais crítica no momento. Atacar a decisão de maior impacto que ainda não foi feita.
4. **Em decisão arquitetural**: cruzar todas as colunas. Se uma decisão é 🟢🟢 em uma e 🔴🔴 em outra, decidir qual métrica está sob pressão.

---

## Cross-references

- Conceitos teóricos: `CONCEITOS.md`.
- Roadmap das ondas: `RINHA_PLAN.md` seção 9.
- Armadilhas detalhadas: `RINHA_PLAN.md` seção 12.
