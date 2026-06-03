# Spec — Onda 7 v2: KD-tree + BBF EXATO → mirar ~6000

> Projeto **tutorial-driven**; entregável = doc (`docs/TUTORIAL_KDTREE.md`).
> Nesta entrega o usuário fez **override pontual** ("pode aplicar" /
> "vamos prosseguir o plano") ⇒ **implementação feita diretamente** (igual Ondas 5/6).
> **Supersede:** [`2026-05-18-onda7-exact-accuracy-design.md`](2026-05-18-onda7-exact-accuracy-design.md)
> (B3 = HNSW aprox + escalonamento; histórico preservado lá).

> 🎯 **Objetivo (2026-05-19).** `final_score` atual **4393** (p99 já no teto:
> `score_p99`=3000; gap 100 % = **ACURÁCIA**, E=370 FP61/FN103). Líderes
> **6000.00** ⇒ `score_det`≈3000 ⇒ **E≈0**. Esta onda torna a decisão
> **byte-idêntica ao ground truth** via busca **EXATA** ⇒ **E=0 estrutural**
> (não tunado), mantendo p99 ≤ 1 ms. Referência mineirada:
> `jvmoonshot-xxvi-main` (entry Rinha-2026 que **prova** KD-tree+BBF exato
> sub-ms no mesmo HW: Mac Mini 2014, 1 CPU / 350 MB).

## Contexto

`B3` patcheava uma estrutura **aproximada** (HNSW recall 96,89 %) com rerank +
escalonamento heurístico ⇒ **não garante E=0**. `jvmoonshot-xxvi` prova que um
**KD-tree balanceado + branch-and-bound (BBF)** retorna o **k=5 verdadeiro**
visitando ~1700 de 3M nós, **sub-ms**, usando exatamente o truque **int16
×10000 lossless** que a spec B3 já provara. Pivotar p/ KD-tree+BBF torna o
acerto **exato por construção**.

**Ground truth (`rinha-de-backend-2026/data-generator/main.c`) — bater byte-a-byte:**
`VDIM=14`, `KNN_K=5`, `THRESHOLD=0.6`. `normalize()` com constantes **idênticas
à nossa `FraudRequestParser`** (já validado ondas 1–5). `round4(x) =
round(x·10000)/10000` aplicado a query **e** refs. `euclidean_dist = Σ(a−b)²`
**sem `sqrt`** (double). `knn_classify`: varre `i=0..N-1`, insertion-sort de 5
com `if (d < dists[j])` **estrito** + `break` ⇒ **menor índice original vence
empate**; `fraud_n` = nº de "fraud" no top-5; `approved = (fraud_n/5) < 0.6`
(fraude se ≥3/5). Nosso threshold já idêntico.

**Prova reaproveitada (do spec B3):** `round4(v)·10000 ∈ ℤ ∩ [-10000,10000] ⊂
int16` ⇒ int16 ×10000 guarda o valor round4 **sem perda**; ordenação por
distância quadrática inteira (acum. **`long`**) = ordenação `double` do C p/
distâncias **distintas**. Empates exatos: resolvidos pelo **rerank final em
`double` idêntico ao `euclidean_dist`** + menor índice (§4).

## Decisões travadas (brainstorming + AskUserQuestion, aprovadas)

1. **Pivotar p/ KD-tree + BBF exato** (vs manter B3-HNSW vs híbrido). E=0
   estrutural; sem HNSW e sem escalonamento no hot path.
2. **Modo EXATO only:** remover toda a superfície de tuning do jvmoonshot
   (`KdTreeTuning`/epsilon/`MAX_VISITS`/relax — `thresholdSum` retorna
   `peekSum`). Constantes mantidas (perf-only, exatidão-neutras):
   `PRIME_FANOUT_DEPTH=5`, `PRIME_PLUNGE_CAP=4`, `TOP_BBOX_DEPTH=18`,
   `BBF_HEAP_CAP=BBF_POOL_CAP=256`.
3. **Leitura mmap = `MappedByteBuffer` LE absoluto** (sem `Unsafe`/reflexão —
   alinha com `MmapDataset`; mantém projeto zero-reflexão sob GraalVM nativo).
   Fallback `Unsafe`+`reflect-config` só se o Gate p99 exigir.
4. **Manter `origId[]`** mapeado (~12 MB; o jvmoonshot descarta — nós
   precisamos p/ o tie-break "menor índice original" do C).
5. **HNSW vira oráculo legado** (testes/Prebuild), sai do hot path e do
   runtime do container. Java puro, Java 21, single-thread, **sem Vector
   API** (já removido na Onda 6 — alinha com o jvmoonshot de propósito).

## Design

### §1. `q16` int16 ×10000 lossless
`Quantizer.q16(float)= (short) Math.round(clamp(v)·10000f)` (sentinela `-1`→
`-10000`). Mantém `q`/`quantize` int8 (oráculo legado).

### §2. KD-tree: formato `RKD3` + builder (port jvmoonshot, determinístico)
- **Builder** (port `KdTreeBuilder`/`KdTreeLayout`): permutação de dims por
  variância (`DIM_PERMUTATION`), split por max-range amostrado, sliding-midpoint
  com clamp de desbalanço, **quickselect 3-way `Random(42L)`** (seed 42 fixo —
  reprodutível). Stride-20 packing por nó: 14 dims i16 (ordem permutada) +
  `leftAndDim` (int32 LE em 2 lanes) + `right` (int32) + `fraud` (1) + pad.
  `topBbox` (STRIDE 32: 16 lo+16 hi) p/ nós até profundidade 18; `topSlot`
  mapeia nó→bbox. **Offline** (`tools.Prebuild`, `-Xmx4g`); container só
  consome.
- **Formato `RKD3`** (little-endian): magic `"RKD3"`+ver, `n`, `dims=14`,
  `stride=20`, `root=0`; `short[n·20]` pts; `int32[n]` origId; `int32`
  topNodeCount; `short[topNodeCount·32]` topBbox; `int32[n]` topSlot.
  (= `KdTreeIO.save` + seção `origId` mantida.)

### §3. Busca BBF exata
Port de `KdTree.prepareSearch`/`prime`/`descendBBF`/`descend`. `prime` =
fan-out depth-5 + plunge `PRIME_PLUNGE_CAP` (apenas **seeding** de um bound
apertado — nunca exclui nós). `descendBBF` = best-first com min-heap por
`slabSum`; poda **slab** (`slabSum > peekSum`) e **bbox** (`bboxMinDist ≥
peekSum`) — ambas **lower bounds sólidos** ⇒ só descarta subárvore que
**provadamente** não contém um top-5. `TopK` k=5 (insertion backward-shift).
**Modo exato** ⇒ branch-and-bound clássico ⇒ os 5 retornados são os **5
i16-exatos**. Fallback recursivo `descend` se heap/pool estourar (também
exato; jvmoonshot mediu máx 256 em 270k queries — re-verificar nos 54.100).

### §4. Rerank `double` = `knn_classify` (a chave do E=0)
A BBF dá os 5 i16-exatos; **tie-break do C é por índice original**. Coletar um
**pool de candidatos** = todo nó **avaliado** com soma-i16 ≤ soma-i16 do 5º.
Sobre o pool, **transcrição literal de `knn_classify`**: iterar por **origId
ascendente**, `dists[5]/idx[5]`, inserir com `<` **estrito** + `break` usando
`DistanceFunctions.sqDistDoubleLikeC` (= `Σ(a−b)²` double, **ordem semântica**,
`a=round4(query)`, `b=R(ref)/10000.0`). `fraud_n` = fraudes nos 5 finais;
`s.fraudCount=fraud_n`; `approved = fraud_n/5 < 0.6`. **Byte-idêntico**
inclusive empates.

### §5. Query `round4` (sentinel-safe)
`FraudRequestParser`: normalização **inalterada** (já idêntica). Aplicar
`round4(v)=(float)(Math.round(v·10000.0)/10000.0)` aos 12 dims; **dims 5/6
sentinela `-1` ⇒ literal `-1f`** (NÃO round4 — `(long)(-1·10000+0.5)/1e4`
≠ -1 é armadilha; o C dá `round(-10000)=-10000` ⇒ `-1.0`). G3(b) prova 0-div.

### §6. Swap do hot path + legado
`ConnectionState` += `short[] queryQ16` (20 lanes permutadas).
`FraudController`: trocar `Quantizer.quantize`+`HnswIndex.search` por
**`KdTree.search(state)`** (mesma assinatura/efeito ⇒ `HttpResponseWriter`
inalterado; fail-open no parse idêntico). `Main`: carregar `references.kdt`
(mmap + madvise/prewarm best-effort) — **sem** HNSW/RB2 em produção.
`Prebuild`: + build `references.kdt` (mantém RB2/HNSW legado p/ testes).
`.gitignore` + `pom.xml` jar-`<excludes>` += `references.kdt`. `Dockerfile`:
`COPY references.kdt` (dropar `hnsw.bin`/`references.bin` do runtime ⇒ imagem
e cgroup **menores**: ~150–180 MB mmap vs 365 MB antes).

### §7. Gates (aceitação = a métrica do ranking)
- **G1 (k6 oficial, BLOQUEIA — A MÉTRICA):** `run.sh` → `final_score` **≥
  ~5900 (alvo 6000)**, p99 ≤ 1 ms, `http_errors` 0, sem corte p99/det.
- **G2 (BLOQUEIA):** `ExactAgree` vs `expected_approved` **e**
  `expected_fraud_score` nos **54.100** → **0 divergências**.
- **G3 (BLOQUEIA):** (a) tree+rerank == `bruteDouble` (transcrição
  `knn_classify` sobre 3M) em amostra → 0-div; (b) `bruteDouble` ==
  `expected_*` nos 54.100 → 0-div (prova lossless+normalização+round4);
  (c) BBF heap/pool **sem overflow** nos 54.100.
- **G4 (BLOQUEIA):** p99 ≤ 1 ms + cgroup ≤ 350 MB sem OOMKilled (nativo,
  compose, 2 inst.; footprint **menor** que Onda 5 — re-validar, não assumir).
- **G5 (regra anti-overfit):** E=0 é **estrutural** (zero parâmetro de
  acurácia tunável; modo exato). Mesmo algoritmo ⇒ mesmas respostas no teste
  FINAL oficial (script diferente/mais pesado). Não ajustar nada aos 54.100.

### §8. Inventário

| Arquivo | Ação |
|---|---|
| `knn/KdTree,KdTreeBuilder,KdLayout,KdScratch,KdTopK,KdTreeIO,KdMmap` | **novos** — port jvmoonshot (exato; MBB LE; origId; `search(ConnectionState)`) |
| `knn/Quantizer.java` | +`q16` (mantém int8) |
| `knn/DistanceFunctions.java` | +`sqDistI16`(long) +`sqDistDoubleLikeC`(double, =main.c) |
| `json/FraudRequestParser.java` | +`round4` (12 dims; 5/6 sentinela literal `-1f`) |
| `server/ConnectionState.java` | +`short[] queryQ16` |
| `controllers/FraudController.java`,`Main.java`,`tools/Prebuild.java` | swap p/ KD-tree; Main carrega `references.kdt`; Prebuild build `.kdt` |
| `.gitignore`,`api/pom.xml`,`Dockerfile`,`docker-compose.yml` | `references.kdt` ignored/excluded/COPY; tag `:onda5`→`:onda7` |
| `src/test/.../ExactAgree.java`(novo),`AllocCheckKd.java`(novo),`TestDataReader.java`(+expected_fraud_score) | G2/G3 + zero-alloc |
| `knn/HnswIndex,HnswGraph,HnswBuilder,HnswScratch`,`dataset/MmapDataset`,`RecallHnsw`,`Rbh2Equiv`,`AllocCheck` | **inalterados** — oráculo legado |
| normalização, formato de resposta, infra nativa (Onda 5/4b) | **inalterado** |

### §9. Riscos / mitigações

| Risco | Mitigação |
|---|---|
| Tie-break (jvmoonshot quebra por ordem de traversal, C por menor índice) | §4: pool + rerank double iterando **origId ascendente** com `<` estrito = `knn_classify` literal; G3a prova vs bruteDouble |
| `round4` sentinela dims 5/6 | §5: 5/6 = literal `-1f`; `Math.round` nos 12; G3b pega off-by-ulp |
| int32 overflow | `sqDistI16` em **`long`**; kernel quente i16 mantém int32 pelo invariante do contest (documentar); rerank em `double` |
| GraalVM nativo: `Unsafe`/FFM | default **MappedByteBuffer** (sem reflexão); `madvise` best-effort/no-op; fallback Unsafe+reflect-config só se p99 exigir |
| p99 nativo (sem C2; GraalVM -O3+PGO) | loop escalar auto-vetorizável (alinha Onda 6); stride-packing = 1 DRAM/nó; **regerar `default.iprof`** no novo hot-path (obrigatório — perfil atual perfila HNSW morto); fallbacks: Unsafe, PRIME_PLUNGE_CAP↑ (exatidão-neutro) |
| Memória | ~150–180 MB < 365 MB anterior (melhora); re-validar G4 cgroup 2 inst. ≤ 350 MB (não assumir) |
| BBF cap 256 overflow nos 54.100 | G3c instrumenta/asserta; fallback `descend` recursivo também exato; subir cap se preciso (perf-only) |
| Overfit (teste final difere) | E=0 estrutural, zero param de acurácia; G5 |
| Mudança grande supera int8/HNSW | HNSW=oráculo legado; G2/G3 provam equivalência ao exato |

### §10. Próximo passo
`docs/TUTORIAL_KDTREE.md` (hands-on PT-BR) + reconciliação as-built
(ARCHITECTURE/README/RINHA_PLAN; nota datada) quando os gates fecharem.
Commits `main` (commits sob a identidade `arthurd3`, sem push): spec → tutorial+reconcil →
`feat`. Branch `submission` bump `:onda5`→`:onda7` (Onda 7 **muda
comportamento = melhor score**; a submission deve apontar p/ a imagem
vencedora; `docker push`/`git push`/PR/issue `rinha/test` = ações do usuário).
**Onda 7 = corrida pelo topo do ranking; sem "fim" enquanto E>0 e houver gap
p/ 6000.**
