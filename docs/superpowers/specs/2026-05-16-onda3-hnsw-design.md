# Spec — Onda 3: HNSW hand-rolled (grafo navegável, recall ≥95%)

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o entregável é o
> doc (`docs/TUTORIAL_HNSW.md`); o usuário implementa à mão. Não auto-implementar `.java`.
> Antecessor: `2026-05-16-onda2b-simd-design.md`.
> **Pré-requisito:** Onda 2b implementada e verde (RB2 padded-16, `sqDistI8(byte[],byte[])`
> SIMD, `queryQ[16]`/`vScratch[16]`). Estamos 2 tutoriais à frente do código (2b + 3).

## Contexto

Depois da 2a (int8 off-heap) e 2b (SIMD), a distância é rápida — mas o `HnswIndex.search`
ainda **varre os 3.000.000 vetores por request** (brute-force O(N)). É o último gargalo
algorítmico de latência. A Onda 3 troca o scan por um **grafo HNSW hand-rolled**
(Malkov-Yashunin): a busca passa a custar ~`ef_search·log N` distâncias em vez de N.

HNSW é **aproximado**: o top-5 pode diferir do brute-force. O brute-force int8 (2a/2b)
vira o **oráculo de verdade**; o HNSW tem de ficar perto o bastante para não degradar a
decisão `{approved, fraud_score}`.

## Decisões travadas (brainstorming, aprovadas pelo usuário)

1. **Self-bootstrapping no 1º boot.** Igual ao MmapDataset RB2: se `hnsw.bin` ausente/
   incompatível → constrói o grafo do dataset RB2 int8 (minutos, 1×), grava `hnsw.bin`,
   mmapeia. Boots seguintes mmapeiam. `.bin` gitignored/regenerável.
2. **Gate duplo (bloqueia):** `recall@5 ≥ 95%` médio **E** `approved-agreement ≥ 99%` vs o
   **brute-force int8** nas primeiras 2.000 do `test-data.json`. `ef_search` é ajustado
   (sobe) até ambos passarem. O brute-force int8 fica como `searchBrute` (oráculo de
   recall — análogo a manter `sqDistI8Scalar` na 2b).
3. **Parâmetros (RINHA_PLAN):** `M=16`, `M_max0=32`, `ef_construction=200`, `ef_search=50`
   (ajustável). `mL = 1/ln(M)`. SELECT-M = **closest-M simples** (sem heurística de
   diversificação — suficiente p/ ≥95% aqui; heurística é refino futuro).
4. **Scratch estático único** (reator single-thread): `HnswScratch` com **visited
   versionado** (`int[count]` + contador `gen++` por query — sem `memset`/query) + heaps.
   Não thread-safe (ok: 1 request por vez). Forward-note: multi-thread → per-thread.
5. **RNG de níveis determinístico** (seed fixa) → grafo reprodutível → gate reprodutível.

## Design

### §1. Formato `hnsw.bin` (CSR plano, sem ponteiros Java)

```
[ header 28B ]  magic 'R','B','H','1' (4) | int32 count | int32 M | int32 M0
                | int32 efC | int32 entryPoint | int32 maxLevel
[ levels   ]    count × uint8   (nível-topo de cada nó; 0..maxLevel)
[ L0 CSR   ]    int32 off0[count+1] | int32 nbr0[off0[count]]
[ Lk CSR   ]    p/ k=1..maxLevel:  int32 offk[count+1] | int32 nbrk[offk[count]]
```

- `nbrLayer(i, k)` = `nbrk[ offk[i] .. offk[i+1] )`. CSR uniforme `count+1` por camada
  (nós ausentes na camada k têm `offk[i+1]==offk[i]`) — leitor trivial, sem bookkeeping.
- Big-endian (default `ByteBuffer`/`RandomAccessFile.writeInt`), igual RB2.
- Tamanho dominado por L0 (~centenas de MB off-heap). Camadas altas: `offk` (count+1
  int32 ≈ 12 MB/camada) quase planas + `nbrk` curtas. **Budget**: compactar (int24,
  camadas altas esparsas) e o limite 350 MB são **Onda 4**; a Onda 3 roda no box de dev.
- Auto-migração: `load()` regenera se ausente OU magic≠`RBH1` OU count≠`MmapDataset.count`.

### §2. Build (HnswBuilder) — self-bootstrapping

Insere nó `0..N-1` (Malkov-Yashunin Alg.1). Adjacência **mutável em heap** durante o
build, stride fixo (`M0` em L0, `M` nas altas) + `int[] deg`:

- nível `L = (int)(-ln(rng())·mL)`; rng = xorshift seed fixa (determinístico).
- desce de `maxLevel` até `L+1` com `searchLayer(ef=1)` (greedy) atualizando `ep`.
- de `min(maxLevel,L)` até `0`: `W = searchLayer(ef=efC)`; `viz = closestM(W, Mmax(lc))`;
  liga `i↔viz` bidirecional; se `deg(e,lc) > Mmax(lc)` poda e p/ os `Mmax` mais perto.
- se `L > maxLevel`: `entryPoint=i; maxLevel=L`.
- distância = `DistanceFunctions.sqDistI8(recScratchA, recScratchB)` (2 records RB2 via
  `MmapDataset.data.get(recBase(x),buf,0,16)`).

Ao fim: achata a adjacência mutável → CSR e grava `hnsw.bin` (header placeholder →
`seek` corrige). Build é O(N·efC·logN) → **minutos** e exige **heap grande no 1º boot**
(adjacência L0 ~ `N·M0` ints). Steady-state (mmap) volta a `-Xmx256m`. Pegadinha
documentada; pré-build offline = Onda 4.

### §3. `HnswScratch` (estático, zero-alloc por request)

`int[] visited` (tam. count, 1×) + `int gen` (++ por query; visto = `visited[n]==gen`);
heaps de candidatos (min por dist) e resultado (max por dist) em arrays paralelos
dimensionados `cap = efC + M0 + 1`. `newQuery()` = `gen++; candSize=resSize=0`. Single-thread.

### §4. Search (HnswIndex v3)

`searchLayer(q, ep, ef, layer)` padrão (2 heaps + visited versionado). `search`:
greedy `ef=1` de `maxLevel`→1, depois `ef=ef_search` em L0 → top-5 → `fraudCount`
(decisão idêntica à 2a/2b: `fraud_score=fraudCount/5`, `approved=fraudCount<3`).
Mantém `searchBrute` (loop 2a/2b sobre RB2) como oráculo de recall. Helpers de teste
`top5Hnsw(q)`/`top5Brute(q)` retornam os 5 ids p/ o harness de recall.

### §5. Validação — 5 gates

- **Gate 1 — e2e (reusa, bloqueia):** build OK; 1º boot constrói `hnsw.bin`; `/ready`
  200; `tx-1329056812`→`{"approved":true,"fraud_score":0.0}`;
  `tx-3330991687`→`{"approved":false,"fraud_score":1.0}` (casos 0/5 e 5/5, robustos).
- **Gate 2 — sanity (reusa):** `Gate2Int8 2000` ≥99% vs baseline FLOAT congelado (agora
  **aproximado** — não mais o `1995` fixo da 2b; só não pode degradar).
- **Gate 3a — recall (novo, bloqueia):** `RecallHnsw` — `recall@5` médio ≥ **95%**
  (top5 HNSW ∩ top5 brute-force int8) nas 2.000.
- **Gate 3b — decisão (novo, bloqueia):** `approved_HNSW == approved_brute` ≥ **99%** nas
  2.000 (+ FP/FN; + report vs `expected_approved` oficial como info).
- **Gate 4 — perf (medição):** `BenchHnsw` p50/p99 HNSW vs brute + curva `recall×ef_search`.
  Speedup esperado enorme (sem scan 3M). Sem threshold absoluto (p99<1ms = Onda 5).

### §6. Não-objetivos

Heurística de seleção de vizinhos (closest-M basta); compactação `hnsw.bin` int24 e
budget 350 MB (Onda 4); pré-build offline / branch submission (Onda 4); multi-thread;
Native Image (Onda 5); mudança de fórmula/quantização/threshold.

## Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/HnswBuilder.java` | **novo** — constrói o grafo do RB2, grava `hnsw.bin` |
| 2 | `knn/HnswGraph.java` | **novo** — mmap do `hnsw.bin` (levels/vizinhos/entry) |
| 3 | `knn/HnswScratch.java` | **novo** — scratch estático (visited versionado + heaps) |
| 4 | `knn/HnswIndex.java` | **reescrito** — `search` HNSW + `searchBrute` (oráculo) + helpers topN |
| 5 | `Main.java` | boot: `HnswIndex.load(hnswBin)` após `MmapDataset.load` |
| 6 | `api/.gitignore` | += `src/main/resources/hnsw.bin` |
| 7 | `server/ConnectionState.java` | inalterado (`queryQ[16]` da 2b; top-5 em `knnDist/knnFraud`) |
| 8 | `src/test/RecallHnsw.java` | **novo** — Gate 3a/3b vs `searchBrute` |
| 9 | `src/test/BenchHnsw.java` | **novo** — Gate 4 p50/p99 + curva ef_search |
| — | `Gate2Int8`/`TestDataReader` | reusados (Gate 2 sanity) |

## Test points do tutorial

1. `HnswScratch.newQuery()` 2× não “lembra” visita anterior (versioned correto).
2. Build sintético pequeno (N=1000 do `example-references` quantizado) → grafo conexo,
   entryPoint válido, `recall@5` ~100% vs brute nesse N pequeno.
3. 1º boot constrói `hnsw.bin` (log "construindo HNSW…"); 2º boot mmapeia (instantâneo).
4. `searchBrute` == comportamento 2b (top-5 idêntico ao scan).
5. Gate 1/2/3a/3b/4 conforme §5.

## Riscos / mitigações

| Risco | Mitigação |
|---|---|
| `memset` do visited por query mata o p99 | **visited versionado** (`gen++`, sem clear) — §3 |
| Scratch estático + multi-thread futuro | Documentado: single-thread agora; per-thread se mudar |
| Build estoura heap no 1º boot | `-Xmx` grande só no 1º boot; steady-state mmap `-Xmx256m`; pré-build = Onda 4 |
| Grafo não-determinístico → gate instável | RNG de níveis com **seed fixa** (xorshift) |
| recall < 95% | Subir `ef_search` (curva no BenchHnsw); `M`/`efC` fixos do plano |
| `hnsw.bin` grande estoura 350 MB | Fora de escopo Onda 3 (box dev); int24/compação = Onda 4 |
| Top-5 HNSW vira a decisão | Gate 3b (approved ≥99% vs brute) além do recall |

## Próximo passo

Escrever `docs/TUTORIAL_HNSW.md` (hands-on PT-BR, §0–§15, espelhando `TUTORIAL_SIMD.md`)
+ atualizar o ponteiro `§13` do `TUTORIAL_SIMD.md`. Implementação fica para o usuário
(tutorial-driven). Onda seguinte: **Onda 4 — conteinerização + k6 + budget 350 MB**.
