# Spec — Onda 6: `takeTop5` zero-alloc + LICENSE (otimização final, opcional)

> ✅ **Validada 2026‑05‑18.** Implementada por Claude (override pontual do
> autor à regra "usuário coda à mão", igual Ondas 4b/5). **Gate 1
> byte‑idêntico** (RecallHnsw 96,89 %/99,90 % idêntico à Onda 5, Rbh2Equiv
> 0/3.000.000, 2 oráculos byte‑exatos) + **Gate 2 zero‑alloc** (0 B/query em
> 100.000) **verdes**; **Gate 3 (k6) opcional não rodado** (projeto já
> fechado na Onda 5, comportamento byte‑idêntico, sem alvo de score).
> Commitada em `main`; `submission` **não** bumpado (rebuild nativo `:onda6`
> = opcional, behavior‑idêntico — `:onda5` segue válido). O projeto técnico
> já estava fechado na Onda 5; esta onda fecha a única "honest exception".

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o
> entregável é o doc (`docs/TUTORIAL_ZEROALLOC.md`); o usuário implementa à
> mão (Java + `LICENSE`). Não auto-implementar. Antecessor:
> `2026-05-18-onda5-native-design.md`.

> ⚠️ **Escopo (2026-05-18).** O projeto **já fechou tecnicamente na Onda 5**
> (Native + PGO validada — `final_score` 4393,85, p99 0,59 ms, 4 gates verdes).
> A Onda 6 é **OPCIONAL** (`RINHA_PLAN.md` §9.6) — **nada aqui é necessário
> para a entrega**. Esta onda fecha a *única* "honest exception" ao hot path
> zero-alloc (`ARCHITECTURE.md` §5) e adiciona o `LICENSE` faltante.
> **ZERO mudança de comportamento** (saída byte-idêntica; sem alvo de score).

## Contexto

`ARCHITECTURE.md` §5 documenta a única alocação por-request remanescente do
caminho de produção: `HnswIndex.takeTop5` (`knn/HnswIndex.java` l.97–105)
aloca **dois `int[n]` por query** (`n = HnswScratch.rSize ≤ efSearch ≈ 50`)
para drenar o max-heap de resultado em ordem crescente de distância antes de
copiar os 5 menores em `out`. A saída `out` **já** é zero-alloc (é o
`s.knn5` reusado em `ConnectionState`, `HnswIndex.search` l.109);
`top5Brute` (oráculo de recall) **não** usa `takeTop5` (tem seu próprio
`bd[5]`/`bi[5]`). Logo `takeTop5` é o **último** ponto de alocação por
requisição do hot path de produção — eliminá-lo torna o caminho
verdadeiramente zero-alloc (só resta a alocação **única** de bootstrap).

Some também o `LICENSE`: não existe arquivo de licença no repositório; os
notes de fechamento (Onda 5 spec/tutorial, `RINHA_PLAN.md`) já apontam
"LICENSE MIT antes de publicar" (MIT = norma na Rinha).

## Decisões travadas (brainstorming, aprovadas pelo usuário)

1. **Escopo = só `takeTop5` zero-alloc + `LICENSE`** (AskUserQuestion). Os
   demais itens do §9.6 (`grid-search M/ef`, `sendfile` zero-copy, prefetch
   mmap no build, auditoria NIO-100%) ficam **fora** — opcionais, não
   abordados aqui.
2. **Abordagem A — scratch reusado tamanho `CAP` em `HnswScratch`**
   (AskUserQuestion). Espelha o padrão `rN/rD/cN/cD` (buffers `int[CAP]`
   alocados 1× em `init()`). O laço de drain, `k`, o preenchimento de `out`
   e o padding `-1` ficam **inalterados** ⇒ saída **byte-idêntica por
   construção**. (B = seleção O(5·n) e C = guardar últimos-5 foram
   **rejeitados**: B arrisca divergência de ordem-de-empate contra o Gate B
   — sagrado no projeto — com ganho irrelevante a `n≈50`; C economiza
   memória irrelevante e é mais sutil de provar byte-idêntico.)
3. **`td` mantido como buffer reusado.** Hoje `td[i]` é *write-only* (escrito
   no drain, nunca lido — só `tn` alimenta `out`). Mantê-lo como scratch
   reusado deixa o diff **mecanicamente** byte-idêntico (troca só a *fonte*
   do buffer). Observação documentada; **não** agir (remover `td` seria
   mudança separada e fora do escopo mínimo — YAGNI).
4. **Rebuild da imagem nativa `:onda6` + bump da `submission` = OPCIONAL.**
   Onda 6 é polish *behavior-idêntico*; a `:onda5` continua o artefato de
   fechamento válido. Validação **mínima** = harness HotSpot (recall/oráculos
   idênticos) + prova de zero-alloc. O caminho "se quiser publicar a polish"
   (rebuild `:onda6`, oráculos pelo LB, k6, bump `submission`) fica
   documentado no tutorial como **opcional**.

## Design

### §1. `knn/HnswScratch.java` — 2 buffers reusados (`tN`/`tD`)

Acrescentar ao lado de `rN/rD` (mesmo `CAP = 1<<15`, já "folgado p/ ef≤200";
`rSize ≤ efSearch ≤ CAP` sempre ⇒ sem risco de índice; ~256 KB anônimos
**1×**, no mesmo molde de `rN/rD/cN/cD`):

```java
// resultado: MAX-heap por dist (raiz = mais distante; evict quando passa de ef)
public static int[] rN, rD; public static int rSize;
// drain do top-5 (reusado — zero-alloc por query; Onda 6)
public static int[] tN, tD;
```

```java
public static void init(int n) {
    count = n;
    visited = new int[n]; gen = 0;
    cN = new int[CAP]; cD = new int[CAP];
    rN = new int[CAP]; rD = new int[CAP];
    tN = new int[CAP]; tD = new int[CAP];   // Onda 6
    bufA = new byte[16]; bufB = new byte[16];
}
```

### §2. `knn/HnswIndex.java` — `takeTop5` usa o scratch (única linha comportamental)

```java
private static int takeTop5(int[] out) {
    // drena o max-heap; os 5 menores ficam no fim → reordena
    int n = HnswScratch.rSize;
    int[] tn = HnswScratch.tN, td = HnswScratch.tD;   // Onda 6: scratch reusado (era new int[n])
    for (int i = n-1; i >= 0; i--) { td[i]=HnswScratch.rMaxDist(); tn[i]=HnswScratch.rMaxNode(); HnswScratch.rPopMax(); }
    int k = Math.min(5, n);
    for (int i = 0; i < 5; i++) out[i] = i < k ? tn[i] : -1;
    return k;
}
```

**A linha `int[] tn = … , td = …;` é a ÚNICA mudança comportamental.** O
drain (`i = n-1 → 0`), `k = min(5,n)`, o fill de `out` e o padding `-1`
são idênticos; índices `0..n-1` são usados, o resto do buffer `CAP` é
ignorado. Saída byte-idêntica por construção.

### §3. `LICENSE` (raiz `fraudDetection/`)

Texto **MIT** padrão, `Copyright (c) 2026 arthurd3` (identidade estabelecida
do projeto — commits/`info.json`/imagem). MIT = norma na Rinha.

### §4. Reconciliação de docs (as-built, quando validada)

- `docs/ARCHITECTURE.md` §5: a "one honest exception" **deixa de existir** —
  o hot path de produção é agora **zero-alloc de verdade** (só a alocação
  única de bootstrap resta). Nota datada (histórico preservado).
- `docs/RINHA_PLAN.md` §9.6: item `takeTop5` → **feito**; nota de que a
  Onda 6 entregou só este item + `LICENSE` (resto do §9.6 segue opcional).
- `README.md`: Tech stack / badge — `LICENSE: MIT`; remover menções
  "LICENSE pending"; nota Wave 6 (opcional) zero-alloc.
- Notes de fechamento Onda 5 (spec/tutorial): "LICENSE MIT" → feito.

### §5. Gates (aceitação da Onda 6)

- **Gate 1 — comportamento byte-idêntico (BLOQUEIA).** HotSpot
  `./mvnw -q clean package`; `RecallHnsw 2000 50` → recall@5 **96,89 %** /
  approved-agree **99,90 %** (FP=1 FN=1) **idêntico à Onda 5**; `Rbh2Equiv`
  0/3.000.000 (sanity, intocado); os 2 oráculos byte-exatos pelo **jar
  HotSpot** (`tx-1329056812`→`{"approved":true,"fraud_score":0.0}`,
  `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`) — e, **se** a
  imagem `:onda6` opcional for buildada, também pelo HAProxy LB.
- **Gate 2 — zero-alloc provado (BLOQUEIA).** Harness `src/test`
  `AllocCheck`: mede `ThreadMXBean.getThreadAllocatedBytes()` Δ ao longo
  de N (ex. 100 000) chamadas `top5Hnsw` **pós-warmup**. PASS = Δ/query
  ≈ **0 B** no caminho de busca (baseline pré-Onda-6 ≈ `2·n·4` B/query,
  `n≈50` ⇒ ~400 B/query). Mede o caminho de produção, não o bootstrap 1×.
- **Gate 3 — score não regrediu (MEDE, não bloqueia).** Opcional: k6
  oficial → `final_score` ≥ faixa Onda 5 (**4393**), p99 ≈ **0,59 ms**.
  Remover alocação só pode ajudar/ser neutro (menos pressão de GC); o
  alloc já era "longe do budget" ⇒ não-bloqueante.

### §6. Não-objetivos

Mudança de algoritmo HNSW/score/quantização. Demais itens §9.6
(grid-search `M`/`ef`, `sendfile`, prefetch mmap, auditoria NIO).
Tocar `hnsw.bin`/binários baked. Remover o `td` write-only (mudança
separada; YAGNI). Rebuild nativo/`submission` (opcional — §decisão 4).
Ações outward-facing (`docker push`/`git push`/PR/submissão `rinha/test`)
= do usuário.

## Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `api/src/main/java/org/fraudDetection/knn/HnswScratch.java` | **alterado** — +campos `tN`/`tD`; +2 linhas em `init()` |
| 2 | `api/src/main/java/org/fraudDetection/knn/HnswIndex.java` | **alterado** — 1 linha em `takeTop5` (`new int[n]` → scratch) |
| 3 | `LICENSE` (raiz) | **novo** — MIT, `Copyright (c) 2026 arthurd3` |
| 4 | `api/src/test/java/org/fraudDetection/AllocCheck.java` | **novo** — harness Gate 2 (Δ alloc/query) |
| 5 | `docs/ARCHITECTURE.md`, `README.md`, `docs/RINHA_PLAN.md` | **reconciliar** as-built (quando validada) |
| — | `hnsw.bin`/`references.bin`/demais Java | **inalterado** |

## Test points do tutorial

1. `./mvnw -q clean package` (HotSpot) → exit 0.
2. `RecallHnsw 2000 50` → recall@5/approved **idênticos** à Onda 5; `Rbh2Equiv` 0/3M.
3. `AllocCheck` → Δ alloc/query ≈ 0 (vs ~400 B/query pré-Onda-6).
4. (Opcional) `docker build :onda6` + `docker compose up` → /ready 200 + 2 oráculos pelo LB; k6 `final_score` ≥ 4393.

## Riscos / mitigações

| Risco | Mitigação |
|---|---|
| `rSize > CAP` (ef futuro alto) estoura `tN/tD` | `CAP=1<<15` já cobre `ef≤200` (rSize≤ef); mesma premissa de `rN/rD`; documentar invariante `efSearch ≤ CAP` |
| Ordem de empate muda a decisão de fraude | **Não há mudança**: drain idêntico, só a fonte do buffer muda; Gate 1 prova byte-idêntico |
| Gate 2 mede o bootstrap em vez do hot path | Warmup antes de medir; medir só o laço `top5Hnsw`; reportar Δ/query e o baseline |
| Esquecer que é opcional / inflar escopo | §6 Não-objetivos; rebuild nativo/submission explicitamente opcional |

## Próximo passo

Escrever `docs/TUTORIAL_ZEROALLOC.md` (hands-on PT-BR, espelhando os
tutoriais anteriores) + commit (spec+tutorial em `main`, sem atribuição
Claude, sem push). Usuário implementa à mão (HnswScratch/HnswIndex/LICENSE/
AllocCheck — **2 linhas + 2 campos + 2 arquivos novos**); Claude valida
Gates 1/2 (e 3 opcional) e reconcilia docs as-built. **Onda 6 é a última
onda (opcional) — o projeto técnico já estava fechado na Onda 5.**
