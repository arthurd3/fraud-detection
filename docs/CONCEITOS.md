# Conceitos — Tutorial completo do zero

> Material de apoio para `RINHA_PLAN.md`. Cada seção é autocontida e ensina o conceito sem assumir conhecimento prévio. Leia na ordem se está começando; consulte por tópico quando precisar revisitar.

## Sumário

1. [Vector Search e k-NN](#1-vector-search-e-k-nn)
2. [ANN — Approximate Nearest Neighbor](#2-ann--approximate-nearest-neighbor)
3. [HNSW from scratch](#3-hnsw-from-scratch)
4. [Quantização int8](#4-quantização-int8)
5. [SIMD e Vector API](#5-simd-e-vector-api)
6. [NIO Selector vs blocking I/O](#6-nio-selector-vs-blocking-io)
7. [Native Image vs HotSpot](#7-native-image-vs-hotspot)
8. [mmap e Page Cache](#8-mmap-e-page-cache)
9. [Zero-allocation hot path](#9-zero-allocation-hot-path)
10. [HTTP/1.1 by-hand](#10-http11-by-hand)
11. [PGO — Profile-Guided Optimization](#11-pgo--profile-guided-optimization)

---

## 1. Vector Search e k-NN

**Em uma frase**: representar cada coisa (transação, foto, texto) como um ponto em N dimensões e perguntar "quais N pontos estão mais perto deste ponto novo?".

### O problema que resolve

Você tem 3 milhões de transações já rotuladas como `fraud` ou `legit`. Chega uma transação nova. Quer responder: "ela parece com fraudes ou com legítimas?". Não dá para ter uma regra do tipo `if amount > X then fraud` — fraude moderna se mistura com comportamento real. **Solução**: representar cada transação como um vetor numérico que captura o "perfil" dela, depois medir parecença geometricamente.

### O que é vetor

Um vetor é só uma lista ordenada de números. Na Rinha, cada transação vira um vetor de **14 números** entre 0 e 1 (mais o sentinel -1):

```
[0.05, 0.08, 0.12, 0.62, 0.83, 0.10, 0.04, 0.20, 0.15, 1, 1, 0, 0.15, 0.07]
 ↑amount ↑hour       ↑km_last  ↑is_online ↑mcc_risk
```

Cada posição (dimensão) representa um aspecto: valor da transação normalizado, hora do dia, distância da última compra, etc. O vetor inteiro é "uma transação no espaço de 14D".

### Distância — como medir parecença

Vetores parecidos ficam **perto** num espaço métrico. As três distâncias principais:

- **Euclidiana** (L2): `sqrt(Σ(a[i] - b[i])²)`. É a distância "real" — como você mediria com régua. Resposta natural quando dimensões têm a mesma escala (caso da Rinha: tudo está em [0,1]).
- **Manhattan** (L1): `Σ|a[i] - b[i]|`. Soma das diferenças absolutas. Mais robusta a outliers.
- **Cosseno**: `1 - (a·b)/(|a||b|)`. Mede ângulo, ignora magnitude. Bom para texto (TF-IDF, embeddings) onde "frequência relativa" importa mais que tamanho.

Na Rinha usamos **Euclidiana** porque as dimensões já vêm normalizadas para [0,1].

**Exemplo 2D**:
```
A = (0.1, 0.1)   # transação legítima típica
B = (0.2, 0.2)   # outra legítima
C = (0.9, 0.8)   # fraude

d(A, B) = sqrt(0.01 + 0.01) = 0.14  ← perto
d(A, C) = sqrt(0.64 + 0.49) = 1.06  ← longe
```

### k-NN (k-Nearest Neighbors)

Algoritmo de classificação trivial: dado um ponto novo `q`, ache os `k` pontos do dataset mais próximos, olhe os labels deles, vote por maioria.

```
def knn_classify(q, dataset, k=5):
    distances = [(dist(q, p.vector), p.label) for p in dataset]
    distances.sort()
    top_k = distances[:k]
    fraud_count = sum(1 for _, label in top_k if label == "fraud")
    return fraud_count / k  # fraud_score
```

Na Rinha, `k=5` e o threshold é `0.6`: se 3 ou mais dos 5 vizinhos forem fraude, bloqueia.

### Brute force como ponto de partida

Para 3M vetores × 14 dimensões: 3M × 14 mults + 3M × 14 subs + 1 sort. Em CPU moderna, isso é ~30-200 ms por query. **Nada disso fecha em p99 ≤ 1 ms**, mas é o ponto de partida correto na Onda 1: você valida a corretude antes de otimizar.

### Por que importa para a Rinha

A tarefa inteira da Rinha 2026 se reduz a "implementar k-NN com k=5 sobre 3M vetores em 14 dims, em < 1ms". Tudo no `RINHA_PLAN.md` (HNSW, SIMD, quantização) é otimização do mesmo algoritmo conceitual. Entender k-NN é entender o problema.

---

## 2. ANN — Approximate Nearest Neighbor

**Em uma frase**: aceitar **achar quase os top-k** em troca de buscar **muito mais rápido**.

### O problema que resolve

Brute force é exato mas é O(N×D). Com N=3M e D=14, mesmo otimizando para SIMD em int8 (32 bytes/instrução) conseguimos descer para ~3-26 ms — **ainda 3× a 26× acima do orçamento p99=1ms da Rinha**. Para fechar precisamos de O(log N) ou similar. Mas ANN sacrifica algo: **recall**.

### Recall@k — definição matemática

Para uma query `q`, seja `T_q^k` o conjunto exato dos top-k vizinhos (verdade absoluta), e `R_q^k` o conjunto que o algoritmo retornou. Então:

```
recall@k(q) = |T_q^k ∩ R_q^k| / k
```

Recall = 1.0 → algoritmo encontrou exatamente os top-k. Recall = 0.8 → 4 dos 5 retornados estão entre os 5 corretos. **Na Rinha o que importa é o impacto no `fraud_score` final**: se os 5 retornados têm a mesma proporção de fraudes que os 5 verdadeiros, o `score` é igual mesmo com recall < 1.

### Trade-off recall × latência

```
brute force         recall=1.00  lat=30ms
HNSW ef_search=200  recall=0.99  lat=400µs
HNSW ef_search=50   recall=0.97  lat=200µs   ← Rinha
HNSW ef_search=10   recall=0.85  lat=80µs    ← arriscado
LSH                 recall=0.70  lat=50µs
```

Quanto menos parâmetro de busca, mais rápido e menos recall. Você ajusta até o `score` parar de melhorar.

### Famílias de ANN

**Tree-based** (kd-tree, ball-tree, vp-tree, R-tree). Particionam espaço com hiperplanos. Funcionam bem em dimensões baixas (<10), degradam (`curse of dimensionality`) acima de 20D. Em 14D ainda dá, mas HNSW geralmente vence.

**LSH (Locality-Sensitive Hashing)**. Funções hash que mapeiam vetores próximos para o mesmo bucket com alta probabilidade. Rápido para dimensões altíssimas (texto), mas recall mediano. Não brilha em 14D.

**IVF (Inverted File Index)**. Faz k-means para criar K clusters (K=√N tipicamente), na query escolhe os `nprobe` clusters mais próximos do centroide e brute-force dentro. Muito usado em produção (FAISS). Em 14D performa ok mas precisa varrer milhares de pontos.

**HNSW (Hierarchical Navigable Small World)**. Grafo multi-camadas. Estado da arte para 95-99% recall com latência baixíssima em qualquer dimensão. **Escolha da Rinha**.

**PQ (Product Quantization)** e variantes (IVFPQ, OPQ, ScaNN). Dividem vetor em sub-vetores e quantizam cada um com codebook. Brilha em D ≥ 128. Em 14D o overhead do codebook supera o ganho.

### Tabela comparativa (rough)

| Família | Recall típico | Latência relativa | Memória extra | Build time | Bom para |
|---|---|---|---|---|---|
| Brute force SIMD | 1.00 | 1× | 0 | 0 | < 100k vetores |
| Tree-based | 0.95-1.00 | 0.3× | ~N×log N | rápido | D < 20 |
| LSH | 0.6-0.85 | 0.05× | ~N×L | rápido | D >> 100 |
| IVF | 0.9-0.97 | 0.1× | ~N×4 | médio | qualquer D |
| HNSW | 0.95-0.99 | 0.01-0.05× | ~N×M×4 | lento | qualquer D |
| IVFPQ | 0.85-0.95 | 0.01× | ~N×8 | lento | D ≥ 128 |

### Por que importa para a Rinha

3M × 14 dims com brute force não fecha 1ms. ANN é o desbloqueio. Entre as famílias, **HNSW vence em 14D** porque (a) recall alto facilmente, (b) latência sub-millisegundo, (c) sem custo de codebook (que sai bem em D pequeno).

---

## 3. HNSW from scratch

**Em uma frase**: um grafo onde cada vetor é um nó conectado aos seus vizinhos próximos, organizado em camadas como um Skip List, em que você "desce" navegando pelo vizinho mais próximo até achar os top-k.

### O problema que resolve

Como buscar em O(log N) num espaço métrico arbitrário sem usar partições rígidas (kd-tree degrada em alta-D)?

### Construção: grafo de proximidade

Para cada vetor do dataset:
1. Conecte-o aos seus `M` vizinhos mais próximos (M=16 na Rinha).
2. Garanta que vizinhos formem um grafo onde "saltos" curtos cubrem o espaço.

**Problema**: se você insere vetores um por um e só conecta os M mais próximos do que já existe, fica míope — o grafo perde a propriedade global. **Solução**: durante inserção, busca beam (parâmetro `ef_construction=200`) candidatos largos e seleciona os M melhores com heurística que mantém diversidade angular. Isso é o "magic" do paper Malkov-Yashunin.

### Multi-camadas: por que e como

Buscar num grafo plano de 3M nós a partir de um nó qualquer leva muitos saltos (O(√N) ou pior). HNSW resolve criando **camadas hierárquicas**:

- Cada nó tem um nível máximo `level_i`, sorteado de uma distribuição exponencial decaindo com fator `mL = 1/ln(M)` (≈ 0.36).
- No **nível 0** (base) **TODOS** os nós existem e estão conectados.
- No nível 1, ~N/M nós (sorteados); no nível 2, ~N/M² ; e assim por diante.
- Camadas altas formam um "esqueleto rarefeito" do espaço inteiro.

**Intuição**: nível alto = autoestrada (poucos pontos, distâncias grandes); nível 0 = ruas (todos os pontos, vizinhança fina).

```
nível 3:  o ─────────── o ─────────── o            (esparso)
          ↓             ↓             ↓
nível 2:  o ── o ────── o ────── o ── o            
          ↓    ↓        ↓        ↓    ↓
nível 1:  o─o──o──o──o──o──o──o──o──o─o            
          ↓ ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓  ↓ ↓
nível 0:  o─o─o─o─o─o─o─o─o─o─o─o─o─o─o            (denso, todos)
```

### Search algorithm

```
def search(q, ef=50):
    ep = entry_point          # nó fixo no nível mais alto
    # Fase 1: greedy descent nas camadas superiores
    for L in range(max_level, 0, -1):
        ep = greedy_descent(q, ep, layer=L)  # pula para o vizinho mais próximo até estabilizar
    
    # Fase 2: beam search no nível 0
    candidates = beam_search(q, ep, layer=0, ef=ef)  # mantém top-ef
    return top_k(candidates, k=5)

def greedy_descent(q, ep, layer):
    cur = ep
    while True:
        best = min(neighbors(cur, layer), key=lambda n: dist(q, n))
        if dist(q, best) >= dist(q, cur):
            return cur          # parou de melhorar
        cur = best

def beam_search(q, ep, layer, ef):
    visited = {ep}
    candidates = MinHeap([(dist(q, ep), ep)])     # mais próximos primeiro
    results = MaxHeap([(dist(q, ep), ep)])        # piores no topo
    while candidates:
        cur_d, cur = candidates.pop()
        if cur_d > results.peek().d:
            break                                  # não há como melhorar
        for n in neighbors(cur, layer):
            if n in visited: continue
            visited.add(n)
            d = dist(q, n)
            if d < results.peek().d or len(results) < ef:
                candidates.push((d, n))
                results.push((d, n))
                if len(results) > ef: results.pop()
    return results
```

Cada chamada `dist()` lê 14 bytes do mmap e roda SIMD. O número total de `dist()` calls com `ef=50` é tipicamente **300-2000** — vs 3M do brute force. Daí vem o ganho de 1000-10000×.

### Parâmetros e seus efeitos

| Parâmetro | Default Rinha | Efeito ao aumentar |
|---|---|---|
| `M` | 16 | Mais vizinhos por nó → recall ↑, RAM ↑, build ↑ |
| `M_max0` | 32 | Vizinhos no nível 0 → recall ↑↑ no que mais importa |
| `ef_construction` | 200 | Qualidade do grafo (build) → recall ↑, build ↑↑ |
| `ef_search` | 50 | Largura do beam (query) → recall ↑, latência ↑ |

**Tunar**: ajustar `ef_search` é a maneira barata de trocar latência por recall em runtime — sem rebuild.

### Por que importa para a Rinha

HNSW é o coração da Onda 3. Sem ele, p99 fica em 5-30 ms (brute force int8 SIMD). Com ele, ~200-500 µs. É o que tira a Rinha de "+3000" para "+5000".

---

## 4. Quantização int8

**Em uma frase**: trocar cada `float` (4 bytes) por um `byte` (1 byte) com perda controlada de precisão para **caber 4× mais no cache da CPU**.

### O problema que resolve

3M vetores × 14 dims × 4 bytes (float32) = **168 MB**. Duas instâncias = 336 MB. Já estouramos os 350 MB sozinhos. Mais grave: cada lookup HNSW lê ~1500 vetores aleatórios. 1500 × 56 bytes = 84 KB — **não cabe em L1 (32 KB) e mal cabe em L2 (256 KB)**. Cache miss = stall de 100+ ciclos.

Com int8: 3M × 14 × 1 = **42 MB**. 1500 × 14 = 21 KB — **cabe folgadinho em L1**. Velocidade explode.

### A aritmética da quantização

Para mapear float em [−1, 1] para int8 em [−128, 127]:

```
scale = 127.0
int8 = round(float * scale)
float_recovered = int8 / scale
erro_max = 1 / (2 * scale) ≈ 0.004
```

Para floats em [0, 1] (caso Rinha): use `scale = 255` em uint8, ou `scale = 127` em int8 (perdendo metade da resolução para 1 bit de sinal).

A distância euclidiana quadrada nos quantizados é uma aproximação da distância nos floats:

```
sum (a_int8[i] - b_int8[i])² ≈ scale² × sum (a_float[i] - b_float[i])²
```

Como só comparamos distâncias relativamente, **o fator `scale²` constante não importa**.

### Escala global vs per-dimension

**Global**: uma única `scale` para todas as dimensões. Vantagem: 1 multiplicação na quantização da query, código simples. Desvantagem: dimensão com range muito menor que outras gasta poucos níveis de quantização (perde resolução).

**Per-dimension**: 14 scales, uma por dimensão. Cada quantização da query: 14 multiplicações + 14 saturates. Mais informação preservada, mas distância vira `Σ (a[i] − b[i])² × (1/scale[i]²)` — não dá mais para comparar relativamente sem multiplicar — perde a propriedade "constante some". Solução: quantizar de forma que cada dimensão vá para o range int8 cheio (max(|x_i|) → 127), depois distância simples em int8.

Na Rinha começamos com **global** (mais rápido). Se recall < 95%, migra para per-dim.

### Signed vs unsigned

`byte` em Java é **signed** (−128..127). Manipulações exigem `b & 0xFF` para tratar como uint. A Vector API suporta ambos. Na Rinha, escolhemos **signed** porque o sentinela −1 (last_transaction null) precisa ficar fora do range positivo dos vetores normais.

### Por que isso ajuda L1/L2

Cache L1 é ~32-48 KB por core. Cada miss custa 10-100+ ciclos (depende do nível). Se o working set de uma query cabe em L1, distance kernel roda em pipeline cheio. Se sangra para L2 (256 KB) ou L3 (≥ 4 MB), latência por dist() vai de 5ns para 20-100ns. Multiplique por 1500 distâncias → milissegundos extras.

### Por que importa para a Rinha

Quantização libera 4× memória **e** acelera 2-4× o distance kernel (cache hit + AVX2 32-lane int8 vs 8-lane float32). Sem ela, dataset não cabe em 350 MB e p99 não chega em 1 ms. **Onda 2** do roadmap.

---

## 5. SIMD e Vector API

**Em uma frase**: uma instrução do processador que opera em vários números ao mesmo tempo. Se você consegue paralelizar um loop, **SIMD entrega 4-32× sem custo de threads**.

### O problema que resolve

Loop escalar para distância euclidiana de 14 ints:

```java
int sum = 0;
for (int i = 0; i < 14; i++) {
    int diff = a[i] - b[i];
    sum += diff * diff;
}
```

Cada iteração: 1 load a, 1 load b, 1 sub, 1 mul, 1 add. ~14 × 5 = 70 micro-ops. Mesmo com pipelining, ~7-14 ciclos. Para 3M dists em brute force = 30-50 ms. SIMD reduz isso para ~7 ciclos no total da função.

### O que é SIMD

**Single Instruction, Multiple Data**. O processador tem registradores grandes (128, 256, 512 bits) e instruções que fazem a **mesma operação** em todos os "lanes" de uma vez:

```
AVX2 (256 bits, 32 bytes):
  Registrador a = [a0, a1, a2, a3, ..., a31]  (32 int8)
  Registrador b = [b0, b1, b2, b3, ..., b31]
  vpsubb  zmm = a - b   (32 subtrações em paralelo, 1 ciclo)
```

Famílias de instruções x86: SSE (128b), **AVX2 (256b)**, AVX-512 (512b). Haswell (CPU da Rinha) tem AVX2.

**Lanes por tipo de dado** em AVX2 (256 bits):
- 32 lanes de int8/uint8
- 16 lanes de int16
- 8 lanes de int32 / float32
- 4 lanes de int64 / float64

### Vector API do JDK

Java 16+ ganhou `jdk.incubator.vector`. Permite escrever código portátil que o HotSpot/GraalVM compila para SIMD nativo:

```java
import jdk.incubator.vector.*;

static final VectorSpecies<Byte> SP = ByteVector.SPECIES_256;

int euclideanInt8(byte[] a, byte[] b) {
    ByteVector va = ByteVector.fromArray(SP, a, 0);
    ByteVector vb = ByteVector.fromArray(SP, b, 0);
    
    // a - b em int16 (para evitar overflow signed em int8)
    ShortVector diff = va.sub(vb).reinterpretAsShorts();
    
    // diff² em int32 (somatório de 14 quadrados pode estourar int16)
    Vector<Integer> sq = diff.mul(diff).convert(VectorOperators.S2I, 0);
    
    return sq.reduceLanes(VectorOperators.ADD);
}
```

Em 14 dimensões cabemos em 1 registrador AVX2 (32 lanes). Distance vira ~5-10 ns vs 50-100 ns escalar.

### Pegadinhas

- **Overflow signed**. `(int8)127 - (int8)(-128) = 255`, não cabe em int8. Sempre **promover para int16** antes de subtrair.
- **Soma de quadrados** em 14 lanes int16: max é `14 × (255)² = 910k`, não cabe em int16 (max 32767). Promover para **int32**.
- **Fallback escalar silencioso**. Se o compilador (HotSpot ou GraalVM) não consegue intrínsecos, gera código escalar — sem aviso. Validar com `-XX:+PrintAssembly` ou `-Dgraal.PrintCompilation=true`.
- **Native Image**. Vector API em Native Image teve regressões em versões 22/23 — fica escalar. Mandrel 21 LTS é o sweet spot.
- **Tail handling**. Se `length` não é múltiplo de `SP.length()`, precisa de loop residual. Em 14 dims não preocupa (cabe em 1 vetor 32-lane com mask), mas é gotcha geral.

### Por que importa para a Rinha

SIMD transforma distance kernel de gargalo em commodity. Sem ele, mesmo HNSW (1500 dists) custaria milissegundos. **Onda 2** introduz, **Onda 3** amplifica, **Onda 5** (Native Image) precisa preservar — testar que intrínsecos não regrediram.

---

## 6. NIO Selector vs blocking I/O

**Em uma frase**: 1 thread atende N conexões registrando interesse no kernel ("avise quando algo acontecer"), em vez de bloquear 1 thread por conexão.

### O problema que resolve

Modelo blocking thread-per-connection: cada `socket.read()` parece síncrono mas o thread fica parado esperando bytes. Para 1000 conexões = 1000 threads = ~8 GB de stacks + scheduling caos. Para a Rinha (1 CPU, 350 MB), isso é proibitivo.

### Multiplexing — a primitiva do kernel

Linux oferece `epoll`, BSD oferece `kqueue`, Windows oferece IOCP. Todos resolvem o mesmo: "monitorar N file descriptors, me devolva quais estão prontos para read/write/accept agora".

```
epoll_create()              → cria epoll fd
epoll_ctl(efd, ADD, fd_n)   → "monitore fd_n"
events = epoll_wait(efd)    → bloqueia até algum estar pronto, retorna lista
```

1 thread, N conexões, 0 desperdício. É o que servidores high-perf usam (nginx, Redis, HAProxy).

### Selector — wrapper Java

`java.nio.channels.Selector` é a abstração do JDK sobre epoll/kqueue:

```java
Selector sel = Selector.open();
ServerSocketChannel srv = ServerSocketChannel.open();
srv.bind(new InetSocketAddress(9999));
srv.configureBlocking(false);
srv.register(sel, SelectionKey.OP_ACCEPT);

while (running) {
    sel.select();                              // bloqueia até evento
    Set<SelectionKey> keys = sel.selectedKeys();
    for (SelectionKey k : keys) {
        if (k.isAcceptable())  acceptNew(srv, sel);
        if (k.isReadable())    handleRead(k);
        if (k.isWritable())    handleWrite(k);
    }
    keys.clear();
}
```

A iteração inteira roda em 1 thread. Read/write nunca bloqueiam (`SocketChannel.configureBlocking(false)`).

### Modelo single-thread reactor

Padrão "Reactor": 1 thread evento-driven que processa cada I/O em sequência. Adicione CPU work (KNN search) na mesma thread → zero context switch. Em 1 CPU, isso vence multi-thread porque qualquer outra thread acordando no mesmo CPU é puro overhead.

**Quando multi-thread vale**: se KNN é tão pesado que I/O não consegue rodar (raro com 1ms total), spawn 1 thread KNN com SPSC queue. Não é o caso da Rinha.

### Virtual threads como alternativa

Java 21 GA virtual threads (Project Loom). Você escreve **código blocking** mas o runtime "estaciona" o thread quando bloqueia em I/O e libera o carrier (OS thread). Resultado: ergonomia de blocking + escalabilidade de NIO.

**Por que não na Rinha**: virtual threads adicionam ~2-5 µs por fork/park. Para tarefa CPU-bound (KNN domina), isso é puro overhead. NIO single-thread vence em workload assim.

### Pegadinhas do Selector

- `selectedKeys()` precisa de `clear()` no fim do loop — fácil esquecer e reprocessar evento.
- `register()` na mesma thread do `select()` — chamar de outra thread sem `wakeup()` causa deadlock.
- Buffer pool por conexão — alocar a cada read polui GC.
- Spurious wakeups raros mas existem — sempre testar `key.isReadable()` antes de ler.

### Por que importa para a Rinha

`com.sun.net.httpserver` (que está hoje no `ServerHTTP.java`) usa thread pool blocking + parsing automático de headers. Aloca 50-200 µs **por request**. Com p99=1ms de orçamento, isso come 5-20% antes de qualquer trabalho real. NIO Selector raw zera esse overhead. **Onda 1** já entra com NIO.

---

## 7. Native Image vs HotSpot

**Em uma frase**: HotSpot interpreta + JIT-compila enquanto roda (warmup); Native Image AOT-compila tudo para um binário nativo (zero warmup, RSS menor, mas perde profile dinâmico — PGO recupera).

### O problema que resolve

Java tradicional (HotSpot) tem dois aceleradores:
- **C1 (client)**: JIT rápido, otimizações modestas. Ataca cedo.
- **C2 (server)**: JIT lento, otimizações agressivas. Ataca depois (~10-100k execuções).

**Warmup**: até C2 compilar os hot paths, código roda 5-20× mais devagar. Em benchmark Rinha curto (~120s), uma fração das requests sofre warmup.

Native Image (GraalVM/Mandrel) compila tudo **antes** (AOT — Ahead Of Time) gerando um binário Linux nativo. Sem warmup. Sem JVM. RSS menor.

### O que cada um vence

| Aspecto | HotSpot | Native Image |
|---|---|---|
| Startup | 1-3s | 5-50 ms |
| RSS estado estacionário | 80-200 MB | 30-80 MB |
| Throughput steady-state | ⭐⭐⭐⭐ (C2 agressivo) | ⭐⭐⭐ (sem profile) |
| Throughput primeiros 30s | ⭐⭐ (warmup) | ⭐⭐⭐⭐ (já no pico) |
| Build time | segundos | 1-5 min |
| Debugging | gdb, JFR, async-profiler | gdb, perf (mais cru) |
| Reflection | livre | precisa `reflect-config.json` |

### PGO — recuperando o gap

Native Image perde C2 que faz inlining/branch prediction baseados em profile dinâmico. **Profile-Guided Optimization (PGO)** faz:

1. Build com `--pgo-instrument` → binário escreve `default.iprof` quando roda.
2. Rodar binário contra workload representativo (k6 oficial).
3. Build final com `--pgo` → compilador usa o profile coletado para inlining/branch hints.

Ganho típico: 10-30% throughput. Fecha grande parte do gap com C2.

### Quando NOT usar Native Image

- App long-running com workload muito variável (warmup é só 0.1% do tempo total, mas C2 vai otimizar continuamente o que aparecer).
- Heavy reflection (Spring antigo, Hibernate sem hints) — exigiria muito config.
- Build time crítico no dev loop (Native demora minutos).

A Rinha **não** tem nenhum desses problemas: workload é fechado, código é zero-reflection, build só roda no Docker.

### Mandrel — variante Red Hat

Mandrel é fork do GraalVM Community focado só em Native Image, sem o que não interessa (Truffle, polyglot). Mais estável para Java puro. Versões LTS (21) são as mais robustas. Versões 22/23 tiveram regressões com Vector API.

### Pegadinhas

- `reflect-config.json` ausente → `ClassNotFoundException` em runtime. Mitigar: zero-reflection no código.
- `resource-config.json` ausente → recursos do classpath inacessíveis. Mitigar: `.bin` files **fora** do JAR (mmap externo).
- `MappedByteBuffer` em Native: precisa de cuidado com `Unsafe`. JDK 21 está OK.
- Vector API regressão: `-Dgraal.PrintCompilation=true | grep euclideanInt8` precisa mostrar AVX2.
- `--static --libc=musl` para `FROM scratch`: evita dependência de glibc. Se preferir, use distroless (~20 MB) com glibc.

### Por que importa para a Rinha

A Rinha bench dura ~120s. Warmup do C2 corrói os primeiros 10-30s. Native Image elimina esse buraco — todas as requests já no pico. RSS menor abre folga para mmap. **Onda 5** do roadmap. Antes disso, Onda 4 mede HotSpot como baseline para comparar.

---

## 8. mmap e Page Cache

**Em uma frase**: peça ao kernel para mapear um arquivo direto na memória virtual; o page cache traz páginas sob demanda — **você não "carrega" 42MB, você acessa**.

### O problema que resolve

Carregar 42 MB de `references.bin` para `byte[]` no heap:
- 42 MB de RSS já contado de cara.
- Tudo na geração velha do GC, dura toda a vida.
- Cada instância paga 42 MB. Duas instâncias = 84 MB do orçamento de 350 MB.

mmap troca isso por um modelo lazy: o kernel mapeia o arquivo no espaço virtual, mas **só carrega páginas que você toca**. Páginas dormentes não contam RSS.

### Virtual memory crash course

CPU enxerga endereços virtuais. MMU traduz para físico via tabela de páginas (4 KB cada). Sistema operacional mantém páginas em RAM ou swap. Page fault = página pedida não está em RAM → kernel busca do disco.

`mmap(fd, size, PROT_READ, MAP_PRIVATE)` → kernel marca regiões virtuais como "se acessar, faça fault para esse arquivo". Não move byte algum até o primeiro acesso.

### Page cache do kernel

O Linux mantém todo arquivo já lido em **page cache** — espaço de RAM gerenciado pelo kernel, separado do heap do processo. Próximos acessos ao mesmo arquivo (qualquer processo) servem de RAM, sem disco.

**Implicação Docker**: 2 instâncias mmap o mesmo `references.bin` → o kernel mantém **uma cópia** das páginas no cache. Cada cgroup que toca aquelas páginas as conta no seu RSS, mas a memória física é compartilhada.

### `madvise()` — dicas para o kernel

```c
madvise(ptr, len, MADV_RANDOM);    // "vou acessar aleatoriamente, não faça readahead"
madvise(ptr, len, MADV_SEQUENTIAL);// "vou ler em ordem, prefetch agressivo"
madvise(ptr, len, MADV_WILLNEED);  // "preciso disso já, traga"
madvise(ptr, len, MADV_DONTNEED);  // "esqueça (descarte página)"
```

HNSW = acesso aleatório (sigo arestas do grafo). `MADV_RANDOM` evita readahead que polui cache. Em Java, acessível via `sun.misc.Unsafe` ou `java.lang.foreign` (JDK 22+) ou JNA.

### RSS vs VSZ — métricas que confundem

- **VSZ (Virtual Size)**: tamanho do espaço virtual mapeado. Inclui mmap inteiro mesmo sem tocar. Pode ser absurdo.
- **RSS (Resident Set Size)**: páginas que **estão na RAM física agora**. É o que importa para limites de cgroup.
- **PSS (Proportional)**: RSS dividido entre processos que compartilham. Mais justo para mmap shared.

Docker mede RSS por cgroup. Para a Rinha, importa RSS de cada container ≤ 175 MB (350/2).

### Pegadinhas

- `MappedByteBuffer.force()` em arquivo readonly → `ReadOnlyBufferException`.
- `MappedByteBuffer` mantém a referência ao file channel — fechar channel sem munmap explícito (`Cleaner.cleanable.clean()`) deixa mapa órfão.
- mmap em arquivo **modificado por outro processo** entrega bytes inconsistentes se não usa `MAP_PRIVATE` corretamente.
- TLB miss em working set grande (>2 MB com páginas 4KB) → considerar huge pages (`madvise(HUGEPAGE)` ou `transparent_hugepage`).

### Por que importa para a Rinha

Sem mmap, dataset comeria 84 MB (2× 42 MB) só de heap. Com mmap, RSS efetivo é só as páginas tocadas durante a busca HNSW (~21 KB working set por query). Sobra folga generosa nos 350 MB. **Onda 2** introduz o `MappedByteBuffer`.

---

## 9. Zero-allocation hot path

**Em uma frase**: nunca alocar objeto novo durante o atendimento de uma request — cada `new` chama o GC eventualmente, e GC pause = tail latency.

### O problema que resolve

GC modernos (G1, ZGC, Shenandoah, Native Image Serial) são bons mas não são gratuitos:
- **Stop-the-world pauses**: ZGC/Shenandoah ~1ms; G1 5-50ms; Native Serial 5-200ms.
- **Allocation pressure**: cada `new` adiciona pressão na nursery (G1) ou eden (parallel). Eventualmente trigger.
- **Tail latency**: pause acontece em uma request específica. Se p99=1ms é meta, **uma pausa de 5ms de GC é morte**.

### Como GC funciona (resumido)

- Heap dividido em gerações (Young, Old) ou regiões (G1, ZGC).
- `new` aloca em Young/Eden.
- Quando enche → minor GC → vivos copiados para survivor/old.
- Eventualmente major GC limpa Old (mais caro).
- Native Image padrão: Serial GC (compacta tudo, simples, pausas longas).

### Native Image GC

Native Image usa **Serial GC** por padrão. Sem concurrent collector → pausas proporcionais ao heap. Em workload com pouca alocação, pausas são raras e curtas (heap pequeno). Em workload alocador, vira problema.

**Configurações disponíveis**:
- `-H:+UseSerialGC` (default).
- `-H:+UseParallelGC` (paralelo, menor pause, throughput melhor).
- `-H:+UseEpsilonGC` (no-op GC, OOM em allocação alta — só para benchmarks "perfeitos").

Estratégia Rinha: zero-allocation hot path → Serial nunca trigger. Heap pequeno (~16 MB) → mesmo se trigger, pause ínfima.

### Técnicas para zero-allocation

**Object pooling**:
```java
final ByteBuffer readBuffer  = ByteBuffer.allocateDirect(4096);  // 1× ao criar conexão
final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(512);
final float[]    queryVector = new float[14];
final byte[]     queryQuant  = new byte[14];
final int[]      candidates  = new int[1024];
```

Tudo no `ConnectionState`, criado 1× por conexão, reutilizado por request.

**Primitive arrays no lugar de Lists**:
```java
// RUIM
List<Integer> ids = new ArrayList<>();   // boxing + ArrayList alloc

// BOM
int[] ids = new int[64];
int idsLen = 0;
```

**Evitar APIs alocadoras**:
- `String.split()`, regex → `byte[]` + offsets.
- `Double.parseDouble(String)` → parser manual em `byte[]`.
- `Instant.parse()` → parser ISO8601 manual.
- `Map<K,V>` → tabelas de lookup primitivas.
- Logging em hot path → não logar.

**DirectByteBuffer**: aloca off-heap, fora do GC do heap. Bom para I/O (zero-copy ao kernel). `allocateDirect()` aloca via `Unsafe`.

### Detectar alocação acidental

- **JFR**: `jdk.ObjectAllocationInNewTLAB` events.
- **async-profiler**: `--alloc 1k` flamegraph de alocação.
- **JMH**: anotação `@Warmup`, plugar `gc` profiler.

### Por que importa para a Rinha

p99=1ms não tolera pausa de 5ms. Cada request precisa rodar em CPU pura, sem visitar GC. Implica: parser, KNN, response writer **tudo sem `new`** após startup. **Onda 1** já planta esse princípio (ConnectionState reutilizado), Onda 5 verifica que Native Image preservou.

---

## 10. HTTP/1.1 by-hand

**Em uma frase**: parsear o protocolo HTTP/1.1 direto do `byte[]` ao invés de usar uma biblioteca; viável quando o schema é fechado e o payload é pequeno.

### O problema que resolve

Bibliotecas HTTP (Netty, Jetty, Helidon) são genéricas: parseiam qualquer header, qualquer encoding, qualquer transfer-encoding. Cada feature custa CPU, alocação, branches. Para a Rinha que tem **2 endpoints** com schema rígido, 90% disso é peso morto.

### Estrutura de uma request HTTP/1.1

```
POST /fraud-score HTTP/1.1\r\n          ← request line
Content-Type: application/json\r\n       ← headers
Content-Length: 287\r\n
Host: localhost:9999\r\n
\r\n                                     ← linha vazia separadora
{"transaction": {"amount": 1234.5, ...   ← body (opcional)
```

Tudo é ASCII com `\r\n` (CRLF) terminando linhas.

### Parser stateful em byte[]

```java
enum State { METHOD, PATH, VERSION, HEADERS, BODY }

class HttpParser {
    State state = State.METHOD;
    int pathStart, pathEnd;
    int contentLength = -1;
    int bodyStart;
    
    boolean parse(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            byte b = buf.get();
            switch (state) {
                case METHOD:
                    if (b == ' ') state = State.PATH;
                    break;
                case PATH:
                    if (pathStart == 0) pathStart = buf.position() - 1;
                    if (b == ' ') { pathEnd = buf.position() - 1; state = State.VERSION; }
                    break;
                // ... etc
            }
        }
    }
    
    boolean matchPath(byte[] expected) {
        if (pathEnd - pathStart != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (buf.get(pathStart + i) != expected[i]) return false;
        }
        return true;
    }
}
```

Sem `String`, sem `Map`. Apenas offsets e comparação byte-a-byte.

### Content-Length vs Transfer-Encoding

- **Content-Length**: header obrigatório em POST normais. Diz tamanho exato do body. Parser fácil: lê N bytes após `\r\n\r\n`.
- **Transfer-Encoding: chunked**: body em chunks `<size>\r\n<bytes>\r\n0\r\n\r\n`. Mais complexo. **Na Rinha, k6 manda Content-Length** — chunked não acontece. Pode ignorar.

### Keep-alive e pipeline

HTTP/1.1 default = keep-alive. Mesma TCP connection serve múltiplas requests. **Crítico** para perf — evita handshake TCP por request.

```
client: POST /fraud-score ... ←  request 1
server: HTTP/1.1 200 OK ...   ←  response 1
client: POST /fraud-score ... ←  request 2 (mesma conexão)
server: HTTP/1.1 200 OK ...   ←  response 2
```

Implementação: depois de `write()`, **não fechar** o channel — voltar a `OP_READ`. Reutilizar buffers. ConnectionState persiste.

### Response canned

Para `/ready`, gerar resposta fixa em build-time:
```java
static final byte[] READY_OK = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(US_ASCII);
```

`channel.write(ByteBuffer.wrap(READY_OK))` — zero formatação.

Para `/fraud-score`, há 12 combinações possíveis de `(approved, fraud_score)` (2 × 6 valores discretos). Pré-gerar todas:
```java
static final byte[][] RESPONSES = {
    "{\"approved\":true,\"fraud_score\":0.0}".getBytes(),
    "{\"approved\":true,\"fraud_score\":0.2}".getBytes(),
    "{\"approved\":true,\"fraud_score\":0.4}".getBytes(),
    "{\"approved\":false,\"fraud_score\":0.6}".getBytes(),
    "{\"approved\":false,\"fraud_score\":0.8}".getBytes(),
    "{\"approved\":false,\"fraud_score\":1.0}".getBytes(),
};
```

Lookup: `RESPONSES[fraudCount]`.

### Pegadinhas

- **TCP fragmentation**: read pode trazer request parcial. Parser precisa ser **resumível** (state preservado entre reads).
- **Multiple requests num read**: pipeline pode entregar 2+ requests num único `read()`. Loop até buffer drenar.
- **Buffer overflow**: limitar tamanho da request line e headers (DoS protection).
- **Case-insensitive headers**: HTTP/1.1 spec diz case-insensitive. Em prática, k6 manda canonical case — pode hardcodar `b"Content-Length:"`.

### Por que importa para a Rinha

`com.sun.net.httpserver` é ~50-200 µs por request. Netty/Jetty similares ou piores. Hand-roll é ~5-20 µs. Em p99=1ms, isso é a diferença entre +3000 e +5000 pontos. **Onda 1** entra com NIO + parser hand-roll desde o começo.

---

## 11. PGO — Profile-Guided Optimization

**Em uma frase**: o compilador ganha 10-30% de throughput ao saber **quais branches são quentes** e **quais funções inlinar**, e PGO entrega esse profile via execução prévia.

### O problema que resolve

Compiladores otimizam baseados em heurísticas (ex: "loops geralmente iteram muito"). Algumas decisões só são boas com profile real:

- **Branch prediction hints**: `if (raro) ... else (comum) ...` deve ter o branch comum como fall-through.
- **Inlining**: inlinar função quente vale; função fria não.
- **Code layout**: blocos quentes contíguos minimizam I-cache miss.
- **Loop unrolling**: depende da contagem típica de iterações.
- **Devirtualization**: se o profile mostra que sempre é a mesma classe.

HotSpot resolve isso com **profile dinâmico em runtime** — C2 espera o app rodar, observa, recompila. Native Image AOT-compila uma vez sem profile → perde estas otimizações por default.

### Como PGO funciona em GraalVM Native Image

3 passos:

1. **Build instrumentado**:
   ```bash
   native-image --pgo-instrument -jar fraud-api.jar
   ```
   Gera binário pesado que coleta profile. Ao rodar, escreve `default.iprof` com contagens de branches e edges.

2. **Treino**: rodar o binário instrumentado contra workload representativo. Quanto mais parecido com produção, melhor.
   ```bash
   ./fraud-api &
   k6 run rinha-de-backend-2026/test/test.js   # gera profile real
   kill -SIGINT $!                              # escreve default.iprof
   ```

3. **Build final**:
   ```bash
   native-image --pgo=default.iprof -jar fraud-api.jar
   ```
   Compilador usa o profile para reordenar branches, inlinar funções quentes, etc.

### Ganho típico

- Throughput: +10 a +30%.
- Latência tail (p99): -5 a -15%.
- Tamanho do binário: +5 a +15% (mais inlining = mais código).
- Build time: ~2× (instrumentado roda mais lento).

Em código com hot path bem definido (a Rinha), o ganho é maior.

### Pegadinhas

- **Profile precisa parecer com produção**. Workload sintético com poucos casos não cobre branches reais → PGO inverte heurísticas. Treinar com k6 oficial é o ideal.
- **Versão do compilador**: profile gerado com Mandrel 21 não é portável para Mandrel 23. Sempre regerar.
- **Build determinístico**: mesma input + mesmo profile deve dar mesmo binário. Ajuda CI cache.
- **CI lento**: 2 builds + 1 run de treino dentro do Dockerfile multi-stage. Considerar separar.

### HotSpot tem PGO?

HotSpot faz "PGO online" via C2 — sem build separado, perde no startup. Há `AOTCompiler` experimental + Class Data Sharing (CDS), mas Native Image PGO é mais maduro.

### Quando NOT usar PGO

- Projeto cedo (não vale o ciclo de build duplo).
- Workload muito variável (profile fica obsoleto).
- Build pipeline já apertado.

A Rinha não tem nenhum desses problemas — workload é fixo (k6 oficial), build roda no Docker (não bloqueia dev).

### Por que importa para a Rinha

Todo o resto fica em ~95-97% do potencial sem PGO. PGO espreme o último 3-5% — diferença entre ficar em +5500 e bater +5800. **Onda 5** depois de validar Native Image básico.

---

## Próximos passos

Voltar para `RINHA_PLAN.md`. Cada onda do roadmap referencia subseções específicas daqui:

| Onda | Conceitos requisitos |
|---|---|
| Onda 0 | (nenhum — só setup) |
| Onda 1 | 1, 6, 9, 10 |
| Onda 2 | 4, 5, 8 |
| Onda 3 | 2, 3 |
| Onda 4 | (operacional) |
| Onda 5 | 7, 9, 11 |
