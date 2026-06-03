> ⚠️ **SUPERSEDED 2026-05-19 → "Onda 7 v2: KD-tree + BBF exato".** A
> abordagem **B3** abaixo (int16 lossless + **HNSW aproximado** + rerank de
> pool + **escalonamento** heurístico) foi **substituída** por uma busca
> **EXATA por construção**: **KD-tree balanceado + branch-and-bound (BBF)**,
> portada de `jvmoonshot-xxvi-main` (entry Rinha-2026 que prova busca exata
> sub-ms no mesmo HW). Motivo: B3 **não garante E=0** (escalonamento é
> heurístico → provável ~5900). Em v2 o E=0 vira **estrutural** (KD-tree
> exato + rerank `double` idêntico ao `data-generator/main.c` + tie-break
> por menor índice original), **sem HNSW e sem escalonamento**. A **prova
> int16 ×10000 lossless vs round4** e a análise de `round4`/empate-do-C
> deste documento **permanecem válidas e são reaproveitadas**. Histórico B3
> preservado abaixo (padrão de nota datada do projeto). **Spec vigente:**
> [`2026-05-19-onda7v2-kdtree-bbf-design.md`](2026-05-19-onda7v2-kdtree-bbf-design.md).

# Spec — Onda 7: acurácia exata (int16 lossless + rerank exato + escalonamento) → mirar ~6000

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o
> entregável é o doc (`docs/TUTORIAL_EXACT.md`); o usuário implementa à mão
> **ou** a implementação é feita diretamente (override pontual, como Ondas 5/6). Antecessor:
> `2026-05-18-onda6-zeroalloc-license-design.md`.

> 🎯 **Objetivo (2026-05-18).** O `final_score` atual é **4393** (p99 já no
> teto: `score_p99`=3000; `score_det`=1393). Os líderes do ranking fazem
> **6000.00** (`score_det`≈3000 ⇒ **E≈0**, detecção quase perfeita). **100 %
> do gap é ACURÁCIA de detecção** (FP=61, FN=103 ⇒ E=370). Esta onda reduz E
> a ~0 reproduzindo o **ground truth exato** sem estourar o p99 ≤ 1 ms.
> **Reabre o projeto** (a "conclusão" da Onda 5 valia p/ submissão válida, não
> p/ topo do ranking).

## Contexto — o ground truth é reproduzível EXATO

`rinha-de-backend-2026/data-generator/main.c` define a verdade
(`expected_approved`/`expected_fraud_score`) verbatim:

- `VDIM=14`, `KNN_K=5`, `THRESHOLD=0.6`.
- `normalize()` → 14 dims com **as mesmas constantes da nossa
  `FraudRequestParser`** (`max_amount=10000`, `max_installments=12`,
  `amount_vs_avg_ratio=10`, `max_minutes=1440`, `max_km=1000`,
  `max_tx_count_24h=20`, `max_merchant_avg=10000`; mesmo algoritmo
  hora/dia-da-semana). **Já validado byte-idêntico** (Ondas 1–5, Gate 1).
- O gerador faz **`round4`** no vetor normalizado (query *e* refs):
  `round4(x) = round(x·10000)/10000`. As refs em `references.json.gz` são
  esses floats round4 (a MESMA base que rotula o test-data).
- `euclidean_dist` = `Σ (a[i]-b[i])²` **sem `sqrt`** (squared).
- `knn_classify`: varre refs `i=0..N-1`, insertion-sort de 5 com `<`
  **estrito** ⇒ em empate **o menor índice `i` vence**; `fraud_n` = qtd de
  refs "fraud" no top-5; `fraud_score = fraud_n/5`;
  **`approved = fraud_score < 0.6`** (fraude se `fraud_n ≥ 3`).

Nosso threshold já é idêntico. **Divergências** vs esse exato: (1) **int8
×127** (≈2 decimais) vs **round4** (4 decimais) — *lossy*; (2) **HNSW
aproximado** (recall@5 96,89 %) vs **brute exato**; (3) tie-break do
**max-heap** do HNSW vs **ordem de índice**.

## Decisões travadas (brainstorming, aprovadas pelo usuário via AskUserQuestion)

1. **Estratégia = B (híbrido exato)** — caminho comum rápido + verificação
   exata onde a decisão é frágil, p/ E→~0 mantendo p99 sub-ms. (A-pure brute
   int16 em toda query: descartada — memory-bandwidth-bound no Mac Mini 2014,
   p99≫1 ms. C incremental: descartada — não chega ao topo.)
2. **Abordagem = B3** — **int16 ×10000 lossless** + rerank exato sobre pool
   HNSW de ef alto + **escalonamento por ambiguidade de decisão**.

## Prova: int16 ×10000 é lossless vs o ground truth round4

`round4(v) = round(v·10000)/10000`. Logo `R(v) := round(v·10000)` é
**inteiro**; como `normalize` produz `v ∈ [-1,1]` (ou sentinela `-1`),
`R(v) ∈ [-10000, 10000] ⊂ int16` (±32767). Armazenar `R(v)` em int16 é
**exato** (zero perda) — o int16 **é** o valor round4 × 10000.

Ordenação: a distância do gerador é `Σ((a-b))²` em `double` sobre
`a=R(a)/10000.0`. A distância inteira `Σ(R(a)-R(b))²` (em **int64**) é
`10000²·Σ((R(a)-R(b))/10000)²` — **mesma ordenação** das distâncias double
para todos os pares de distâncias *distintas* (granularidade round4 ⇒
distâncias distintas diferem por ≫ erro de arredondamento double ~1e-12).
**Empates exatos** (mesma distância inteira) podem divergir do `<` estrito
do C (cujos `double` somam com arredondamento ≠ 0) — daí o §2 fazer o
**rerank final em `double` replicando a expressão do C** (não em int),
casando inclusive empates. (⚠️ acumulador int: `(Δ≤20000)²·14 ≈ 5,6e9 >
2³¹` ⇒ **acumular em `long`/int64**, nunca int32.)

## Design

### §1. Representação int16 ×10000 lossless (zera o erro de quantização)

- `Quantizer.q16(float v)` = `(short) Math.round(clamp(v,-1,1) * 10000f)`
  (clamp idêntico ao atual; `-1` sentinela → `-10000`).
- Novo `references.bin` **`RB3`**: header magic `'R','B','3',0` + count +
  dims=14; `count × (14 × int16 **big-endian**, consistente com RB2/RBH2 do
  projeto)` + `count × 1 B` labels. 3.000.000 × 28 B ≈ **84 MB** + 3 MB
  labels (vs 51 MB do RB2). Gerado **offline** por `tools.Prebuild` lendo os
  floats round4 do `references.json.gz` → `q16`. (Sem pad; 14 dims. SIMD
  opcional pad p/ 16 com lanes 0 — neutro.)
- `DistanceFunctions.sqDistI16(short[] q, short[] v)` → **`long`**
  acumulador, `Σ (q[k]-v[k])²`, k=0..13. (int16 hot path do HNSW.)
- `MmapDataset` RB3: stride 28, `recBase`, leitura de `short[14]` via
  `ByteBuffer` **big-endian** (consistente com RB2/RBH2; validado no G3).

### §2. HNSW int16 (rebuild offline) + **rerank final exato em `double` (igual ao C)**

- `HnswBuilder`/`HnswGraph`: distâncias passam a `sqDistI16` sobre RB3. O
  **formato do grafo (RBH2)** é independente da precisão do vetor (guarda só
  ids) ⇒ inalterado; só muda a função de distância e o stride. **Rebuild
  offline** de `hnsw.bin` (topologia muda pois as distâncias mudam).
  Re-tunar `M`/`ef_construction` (grid-search) p/ recall de **pool** ~100 %.
- `HnswIndex`: a busca por camadas usa int16 (rápido) e **coleta o pool**
  (resultado + visitados de L0; `efSearch` alto). O **top-5 final** é
  selecionado sobre o pool computando a distância em **`double` exatamente
  como o C**: `a_i = R(norm_i)/10000.0; d = Σ(a_i-b_i)²`, com
  insertion-sort `<` estrito por **`(dist, id)` ascendente** (menor id vence
  empate) — **byte-idêntico** ao `knn_classify`, inclusive empates. (Pool =
  centenas de candidatos ⇒ double é trivial, sub-µs.)

### §3. Escalonamento por ambiguidade de decisão (passe exato limitado, sub-ms)

A decisão flipa em `fraud_n ≥ 3`. Escalona **só** quando frágil:
`fraud_n ∈ {2,3}` (1 vizinho flipa) **ou** margem `dist[4]` vs próximos
candidatos < ε **ou** pool com sinais de baixa cobertura. Escalonamento =
**re-busca HNSW de ef muito alto** (quase-exata, ms-limitada) + rerank §2 —
**NUNCA** scan de 3M (mataria o p99: p99 = 99º percentil; >1 % lento ⇒ p99
= tempo lento). ε e o conjunto-fronteira são **parâmetros tunados** pelo
harness p/ maximizar `final_score` com p99 ≤ 1 ms. Caminho comum (≈99 %+) =
HNSW + rerank de pool pequeno (sub-ms).

### §4. Query replica `round4` exatamente

`FraudRequestParser` (normalização já idêntica) passa a aplicar
`R(v)=Math.round(v*10000)` → int16 (= o próprio valor round4×10000). O
rerank §2 reconstrói `a_i = R/10000.0` p/ casar a expressão `double` do C.
Confirmar que `float` não desloca a 4ª decimal (Gate 3 cobre nos 54.100).

### §5. Gates (aceitação = a métrica do ranking; honesto, anti-overfit)

- **G1 — k6 oficial (A MÉTRICA, bloqueia):** `rinha-de-backend-2026/run.sh`
  → `E` em unidades baixas/0, `http_errors` 0, **`final_score` ≥ ~5900**
  (alvo 6000), **p99 ≤ 1 ms** (score_p99 3000, sem corte p99/det).
- **G2 — concordância vs ground truth (bloqueia):** harness offline compara
  nosso `approved` **e** `fraud_score` vs `expected_*` nos **54.100** →
  **0 divergências** (ou contagem mínima documentada).
- **G3 — prova de exatidão (bloqueia):** (a) rerank §2 == brute int16/double
  exato (id-tiebreak) em amostra ⇒ 0-div; (b) brute exato == `expected_*` ⇒
  valida lossless+normalização+round4 (pega off-by-ulp).
- **G4 — p99/footprint (bloqueia):** p99 ≤ 1 ms sob a rampa; cgroup ≤ 350 MB
  sem OOMKilled (dataset 51→84 MB + grafo rebuild — **re-validar** Gate
  C/4b/5; mmap reclaimável, argumento de memória da 4a vale); binário nativo.
- **G5 — anti-overfit (regra):** o teste FINAL oficial usa script mais
  pesado/diferente ⇒ casar o **algoritmo** (não tunar ε aos mismatches
  específicos dos 54.100). Documentar.

### §6. Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/Quantizer.java` | **alterado** — `q16` (int16 ×10000) ao lado do `q` int8 |
| 2 | `dataset/MmapDataset.java` | **alterado** — formato `RB3` int16 + build do `.gz` |
| 3 | `knn/DistanceFunctions.java` | **alterado** — `sqDistI16` (acum. `long`) + helper `double` estilo-C p/ rerank |
| 4 | `knn/HnswBuilder.java` / `HnswGraph.java` | **alterado** — distância int16; rebuild offline; RBH2 inalterado |
| 5 | `knn/HnswIndex.java` | **alterado** — pool ef-alto + rerank `double` id-tiebreak + escalonamento |
| 6 | `json/FraudRequestParser.java` | **alterado** — `round4` na saída (→ int16) |
| 7 | `tools/Prebuild.java` | **alterado** — regenera `references.bin` RB3 + `hnsw.bin` |
| 8 | `src/test/.../ExactAgree.java` | **novo** — G2/G3 (vs `expected_*` dos 54.100; rerank vs brute) |
| 9 | `RecallHnsw`/`Rbh2Equiv`/`AllocCheck` | **adaptar** ao RB3/int16 |
| — | normalização, formato de resposta, infra container/nativa (Onda 4b/5/6) | **inalterado** |

### §7. Riscos / mitigações

| Risco | Mitigação |
|---|---|
| Dataset 84 MB + grafo maior → p99/memória | mmap reclaimável (arg. mem. 4a); re-validar G4; 14 dims sem pad; medir cgroup peak |
| Fração de escalonamento alta → p99 > 1 ms | ε tunado; escalona via ef-profundo (não 3M); G1/G4 medem; cap de escalonamento |
| Empates exatos divergem do `<`/`double` do C | rerank final em **`double` replicando a expressão do C** (§2), não int |
| `round4`/normalização off-by-ulp | G3(b) compara brute exato vs `expected_*` nos 54.100 — pega desvio sistemático |
| int32 overflow no acumulador int16 | acumular em **`long`** (Δ²·14 ≈ 5,6e9 > 2³¹) — explícito no §1 |
| Overfit ao sample de 54.100 (teste final difere) | casar o algoritmo; G5; ε robusto, não ajustado a mismatches específicos |
| HNSW perde true top-5 mesmo com ef alto → E residual | escalonamento; alvo **honesto ~5900–6000**, não 6000.00 garantido |
| Mudança grande (supera hot path int8 2a/2b) | int8 vira oráculo legado/teste; Gates G2/G3 provam equivalência ao exato |

### §8. Próximo passo

Escrever `docs/TUTORIAL_EXACT.md` (hands-on PT-BR, espelhando os anteriores)
+ commit (spec+tutorial em `main`, commits sob a identidade `arthurd3`, sem push) +
reconciliação **light** de status (RINHA_PLAN §9.6 + nova nota Onda 7,
README roadmap, ARCHITECTURE §8 — "Onda 7 spec+tutorial ready, hand-impl
pending; reabre o projeto p/ topo do ranking"). Implementação: usuário à mão **ou** feita diretamente
(override pontual, como 5/6) → os gates G1–G4 são validados. **Onda 7 é a corrida
pelo topo do ranking** (não há "fim" enquanto E>0 e houver gap p/ 6000).
