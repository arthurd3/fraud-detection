# Spec — Onda 2b: distância int8 vetorizada (SIMD, Vector API)

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o entregável é o
> doc (`docs/TUTORIAL_SIMD.md`); o usuário implementa à mão. Não auto-implementar `.java`.
> Antecessor: `2026-05-16-onda2a-int8-quant-design.md` (Onda 2a fechada, HEAD `27bcab1`).

## Contexto

A Onda 2a deixou o dataset int8 off-heap (`MappedByteBuffer`, RB1, 45 MB) e a resposta
`{approved, fraud_score}` correta (Gate 1 oráculos exatos; Gate 2 `1995/2000 = 99.75%`).
O custo de latência está concentrado em `DistanceFunctions.sqDistI8` — distância euclidiana²
escalar de 14 dims, chamada **3.000.000× por request** no brute-force de `HnswIndex.search`.

A Onda 2b vetoriza essa distância com a **Vector API** (`jdk.incubator.vector`, JEP 448,
incubator — já habilitada no `pom.xml` via `--add-modules`). **Nenhuma decisão de negócio
muda**: a matemática é inteira (exata), então o resultado tem de ser **bit-idêntico** ao da
Onda 2a. O ganho é exclusivamente de latência (p99). SIMD é a única mudança algorítmica;
HNSW continua sendo Onda 3.

## Decisões travadas (brainstorming, aprovadas pelo usuário)

1. **Layout RB2 padded-16.** Cada vetor passa a ocupar **16 bytes** (14 int8 reais + 2 bytes
   zero de pad), para casar com `ByteVector.SPECIES_*` sem máscara. Magic novo `'R','B','2',0`.
2. **Load path sem preview.** SIMD lê o vetor do mmap via `MappedByteBuffer.get(base, scratch,
   0, 16)` (get absoluto, estável desde Java 13) para um `byte[16]` reutilizável; depois
   `ByteVector.fromArray`. **Sem `java.lang.foreign`/`--enable-preview`** — `pom.xml` intacto.
3. **Bit-idêntico, não “≥99%”.** A Onda 2b mantém `sqDistI8Scalar` como oráculo de referência;
   o SIMD tem de empatar **exatamente** com ele em todos os 3M registros (Gate A).
4. **Escopo fechado.** Só vetorização da distância + harness de p99. Sem HNSW, sem container,
   sem Native Image (só ponteiros para Ondas 3/4/5).

## Design

### §1. Formato `references.bin` v2 (RB2)

```
[ header 12B ]  magic 'R','B','2',0 (4B) · int32 count (big-endian) · int32 dims (=14)
[ vetores    ]  count × 16  int8   (14 reais + 2 zero, row-major, contíguo)
[ labels     ]  count × 1   byte   (0 = legit, 1 = fraud)
```

- `STRIDE = 16`; `recBase(i) = 12 + i*16`; `lblBase = 12 + count*16`.
- `dims` no header continua **14** (dimensão lógica); o pad de 2 bytes é detalhe de storage.
- Tamanho p/ 3M: `12 + 3_000_000*16 + 3_000_000 = 51_000_012` bytes (RB1 era 45_000_012).
- **Auto-migração:** `load()` checa o `.bin`; se ausente **ou** magic ≠ `RB2` **ou** dims ≠ 14
  → `build()` regenera. Assim o RB1 da Onda 2a é trocado por RB2 sozinho no 1º boot da 2b.
- `.bin` segue gitignored/regenerável. O baseline float (`docs/baselines/onda1-approved-2000.txt`)
  **não muda** (Gate 2 continua válido e tem de bater o **mesmo** número da 2a).

### §2. Distância SIMD (inteira, exata)

`byte` é signed; `q-v ∈ [-254,254]`; `(q-v)² ≤ 64.516` → **estoura `short`** (máx 32.767).
Regra: **alargar `byte`→`int` ANTES de subtrair**; subtrair, quadrar e acumular em `int`.

Padrão portável (AVX2, sem assumir AVX-512): `ByteVector.SPECIES_64` (8 lanes) em **2 chunks**
(offsets 0 e 8) cobrindo os 16 bytes; cada chunk `convertShape(B2I, IntVector.SPECIES_256, 0)`
→ `IntVector` de 8 lanes; `d = qi.sub(vi)`; `acc = acc.add(d.mul(d))`; ao fim
`acc.reduceLanes(ADD)`. As 2 lanes de pad (zero no dataset e no `queryQ`) somam
`(0-0)² = 0` → resultado idêntico à soma escalar de 14 dims.

Interface (unidades pequenas, testáveis em isolamento):

- `DistanceFunctions.sqDistI8(byte[] q16, byte[] v16) -> int` — SIMD, produção.
- `DistanceFunctions.sqDistI8Scalar(byte[] q16, byte[] v16) -> int` — referência (Gate A);
  itera `0..15` (pads 0 → mesma soma). Análogo a manter `sqDist` float na 2a.
- `HnswIndex.search` faz o I/O: `V.get(recBase(i), s.vScratch, 0, 16)` e chama
  `sqDistI8(s.queryQ, s.vScratch)`. `knnDist` continua `float[5]` (dist int < 2²⁴ cabe exata).

### §3. `MmapDataset` v2→RB2

`build()` grava 16 bytes/registro (`byte[16] rec`, `rec[0..13]=Quantizer.q(f[k])`,
`rec[14]=rec[15]=0` — nunca escritos, ficam 0). `mmap()` valida magic `RB2`. `recBase`/`lblBase`
usam `STRIDE=16`. `load()` faz a auto-migração de §1. Streaming do `.gz` reusa
`skipTo/nextNonWs/readFloat` (inalterados).

### §4. Query path

`ConnectionState.queryQ` passa de `byte[14]` para **`byte[16]`** (pads [14],[15]=0, nunca
escritos por `Quantizer.quantize`, que só escreve [0..13]). Novo `byte[16] vScratch`
(zero-alloc, reutilizado por candidato, **não** limpo no `reset()`). `FraudController`
inalterado. `Quantizer` sem mudança de lógica (documentar a invariante do pad-zero).

### §5. Validação — 4 gates

- **Gate A — bit-exato (novo, bloqueia):** `DistEquivI8` (`src/test`) — p/ os 2 oráculos
  quantizados, `sqDistI8(q,v) == sqDistI8Scalar(q,v)` em **todos** os 3M registros; zero
  divergências (SIMD inteiro é exato).
- **Gate 1 — e2e (reusa, bloqueia):** `./mvnw clean package` exit 0; boot `-Xmx256m`; 1º boot
  2b regenera `references.bin` RB2 = **51_000_012** bytes; `/ready`→200; `tx-1329056812`→
  `{"approved":true,"fraud_score":0.0}`; `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`.
- **Gate 2 — regressão-exata (reusa, bloqueia):** `Gate2Int8 2000` → **exatamente**
  `1995/2000 = 99.75% (FP=2 FN=3) PASS` (RB2 = RB1 + pad zero ⇒ mesmos int8 ⇒ mesmas
  decisões; qualquer desvio = bug no SIMD).
- **Gate 3 — perf (o ponto da 2b; medição, sem threshold):** `BenchSearch` hand-rolled (sem
  dep JMH): mmap RB2, warmup, mede p50/p99/média por-query nas 2000 entradas do
  `test-data.json` para **escalar vs SIMD** no mesmo JVM; reporta speedup. Sem pass/fail
  absoluto (p99<1ms é Onda 4/5 com Native Image; brute-force 3M não fica sub-ms).

### §5b. RESULTADO DA VALIDAÇÃO (2026-05-16) — correção honesta

Implementado pelo usuário e validado. **Correto**: Gate A (SIMD≡escalar
bit-a-bit em 3M, 2 oráculos), Gate 1 (oráculos exatos, RB2 51.000.012 B, off-heap
`-Xmx256m`), Gate 2 (exatamente 1995/2000 = 99.75% FP=2 FN=3). **Gate 3 NEGATIVO**:
HotSpot 21/AVX2 — escalar p50≈37 ms vs **SIMD p50≈142 ms** (≈0.26×, **3.8× mais lento**);
escalar fica ≈37 ms até com `-XX:-UseSuperWord` (não depende de auto-vetorização — é só
um laço barato). Causa-raiz: `convertShape` cross-shape (64→256) não intrinsificado
eficiente p/ 14-dim. **Decisão do usuário: `HnswIndex.search` usa `sqDistI8Scalar`**;
`sqDistI8` SIMD mantido só p/ Gate A + aprendizado. Lição estratégica: o fator-constante
da distância é secundário num scan O(3M) (3M × qqer = dezenas de ms); o lever de latência
é **arquitetural (Onda 3 HNSW)**. Decisão #3 (SIMD) deste spec **revisada**: SIMD é
groundwork/ref de corretude, **não** o caminho de p99. Defeito de tutorial (como o gzip
da Onda 1) — `TUTORIAL_SIMD.md` corrigido (banner + §6/§7/§11/§12/§13).

### §6. Não-objetivos

HNSW (Onda 3); container/k6 (Onda 4); Native Image/PGO (Onda 5); mudança de quantização ou
de fórmula 14-D; `MemorySegment`/FFM; SIMD cross-vetor blocado (descartado: over-engineering
p/ esta onda).

## Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/DistanceFunctions.java` | +`sqDistI8(byte[],byte[])` SIMD, +`sqDistI8Scalar(byte[],byte[])`; mantém `sqDist` float |
| 2 | `dataset/MmapDataset.java` | RB2: magic `'R','B','2',0`, `STRIDE=16`, build 16/rec, recBase/lblBase, auto-migração |
| 3 | `server/ConnectionState.java` | `queryQ`→`byte[16]`; +`byte[] vScratch=new byte[16]` (não limpa no reset) |
| 4 | `knn/HnswIndex.java` | loop: `V.get(recBase(i),vScratch,0,16)` + `sqDistI8(queryQ,vScratch)` |
| 5 | `knn/Quantizer.java` | sem mudança; documentar invariante pad-zero (escreve só [0..13]) |
| 6 | `controllers/FraudController.java` | inalterado |
| 7 | `api/.gitignore` | inalterado (`references.bin` já ignorado) |
| 8 | `docs/baselines/onda1-approved-2000.txt` | **inalterado** (oráculo congelado) |
| 9 | `src/test/.../DistEquivI8.java` | **novo** (Gate A) |
| 10 | `src/test/.../BenchSearch.java` | **novo** (Gate 3) |
| — | `src/test/.../{Gate2Int8,TestDataReader}.java` | reusados (Gate 2) |

## Test points do tutorial

1. **Quantizer pad-zero:** `queryQ[14]==0 && queryQ[15]==0` após `quantize`.
2. **RB2 build:** 1º boot 2b regenera; `ls -l references.bin` → `51000012`.
3. **RB2 mmap:** 2º boot mmapeia (sem regen), `-Xmx128m` sem OOM.
4. **sqDistI8 == sqDistI8Scalar** num par sintético (ex.: q=todos 10, v=todos -3 → `13²*14=2366`).
5. **Gate A:** 0 divergências em 3.000.000 (cada oráculo).
6. **Gate 1/2/3** conforme §5.

## Riscos / mitigações

| Risco | Mitigação |
|---|---|
| `byte` signed estoura ao subtrair/quadrar | Alargar B2I **antes** de `sub`; acumular em `int` (§2) |
| SIMD ≠ escalar (bug de shape/part) | Gate A bit-exato em 3M + Test point 4 sintético |
| RB1 antigo no disco quebra o boot | Auto-migração por magic em `load()` (§1) |
| Regressão silenciosa p/ escalar em Native Image (Onda 5) | Pegadinha documentada; validar com `-Dgraal.PrintCompilation` na Onda 5 |
| `SPECIES_64`/`I256` indisponível (hardware sem AVX2) | Espécies fixas conservadoras; nota de portabilidade no §12 do tutorial |
| Custo do `memcpy` 16B/candidato anula o ganho | Gate 3 mede escalar vs SIMD lado a lado (decide empiricamente) |

## Próximo passo

~~Escrever `docs/TUTORIAL_SIMD.md`…~~ **FEITO** (commit 6d6a87a) e **implementado/validado
2026-05-16** (ver §5b). Estado: RB2 + off-heap + corretude **fechados**; SIMD **não** é o
caminho de p99 (produção = escalar). **Onda seguinte = Onda 3 — HNSW**
(`docs/TUTORIAL_HNSW.md`, commit b1c94bb): recall ≥95% vs brute-force baseline — é aí que
a latência cai de verdade (O(3M) → ~centenas de distâncias).
