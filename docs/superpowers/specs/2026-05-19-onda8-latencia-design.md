# Spec — Onda 8: Otimização de latência (exato, Java/GraalVM, foco memória) → top-10/top-1

> Projeto **tutorial-driven**; entregável = doc (spec + `docs/TUTORIAL_LATENCIA.md`).
> **Continua** [`2026-05-19-onda7v2-kdtree-bbf-design.md`](2026-05-19-onda7v2-kdtree-bbf-design.md)
> (Onda 7 v2 fechou a acurácia; histórico preservado — esta onda NÃO a reabre).
> Não supersede: a Onda 7 v2 segue válida; a Onda 8 só ataca o `p99`.

> 🎯 **Objetivo (2026-05-19).** Preview oficial #5524 (Mac Mini Late 2014):
> `detection_score`=**3000 TETO** (FP=0 FN=0 0% erro — acurácia exata validada
> no HW oficial), mas `p99`=**32,31 ms** ⇒ `p99_score`=1490,66 ⇒
> `final_score`=**4490,66 (~#111/258)**. **100 % do gap p/ 6000 é latência no
> HW fraco.** Meta: p99 ≤ ~1,1 ms (**top-10 firme**) mirando ≤1 ms / 6000
> (**top-1**), mantendo **E=0 invariante absoluto**, otimizando a própria app
> Java/GraalVM. Os 5 em 6000 são todos C/Rust/io_uring — sub-ms exato em 3M no
> 2014 é território C; escala p/ kernel nativo só se o preview **estagnar
> acima da faixa top-10 (~1,1 ms)** apesar de todas as fases.

## Contexto

O kernel local roda ~50–250 µs; o p99 oficial 32,31 ms (≈60× o local 0,54 ms)
**não é CPU/algoritmo** — é **localidade de memória**. Diagnóstico fundamentado
(leitura do código, 2026-05-19): o custo = **nº de nós visitados (`s.visits`)
× latência de acesso à memória**. KD-tree exata em 14-D visita muitos nós por
query (poda slab/bbox é fraca em 14-D); pior, **`KdTreeBuilder` numera os nós em
pré-ordem DFS** ⇒ filho-direito = `i + |subárvore_esq|` (saltos enormes) e o BBF
best-first estoura nós espalhados pelos 159 MB. Com L3 de 3 MB e page-cache sob
350 MB (2 instâncias nativas no Mac Mini 2014), cada salto custa cache/TLB/
page-miss caríssimo — daí o 60×. Native AOT já é zero-alloc (não é GC).
**Descartado:** `KdScratch.POOL_CAP=1<<16` é só folga defensiva — `ExactAgree`
mediu **maxPool=104** sobre as 54.100; o pool **não** explode, **não** é a causa.

Validação on-device sob cgroup (`--compatibility` 1CPU/350M) fixa **núcleos e
memória** fielmente, mas **não a velocidade do núcleo nem a hierarquia de cache**
do 2014 ⇒ p99 absoluto local é otimista. Só a **prévia oficial** (issue
`rinha/test`, ilimitada, determinística seed 4242) é autoritativa para p99.

## Decisões travadas (brainstorming + AskUserQuestion, aprovadas)

1. **Estratégia = Java/GraalVM exato, foco em localidade de memória.** Otimizar
   a própria app; escalar p/ kernel C/Rust **só se** o preview travar >1 ms
   apesar das fases (gatilho explícito, decisão futura do usuário).
2. **Alvo = top-10 firme, mirar top-1.** Iterar previews enquanto houver ganho
   claro; parar quando top-10 estável + ganho marginal.
3. **Não emular HW.** Loop: otimizar → medir local sob limites reais (ganho
   **relativo**) → preview oficial = única verdade de p99.

### Invariantes (não-negociáveis)

- **E=0 absoluto.** Pré-filtro aproximado SÓ entra com **prova de soundness**
  (rerank exato sobre superset que comprovadamente contém o top-5 verdadeiro —
  régua "lower bounds sólidos" do spec v2 §3). Sem prova → fora.
- **Determinismo** (test-data seed 4242). **Sem regressão de memória**
  (≤350 MB cgroup, OOMKilled=false). Reconciliação as-built ao fechar.

## Design

### §1. Fase 0 — Loop fiel + instrumentação (pré-requisito)
- Harness oficial local: `rinha-de-backend-2026` → `docker compose
  --compatibility up -d` (haproxy 32M/0.15 + api×2 159M/0.425 = 350M/1CPU) →
  `./run.sh` (k6 ramp 1→900/120 s) → `test/results.json`.
- Instrumentação **sob flag de sistema, removível do build de submissão**:
  contadores por request — nós visitados (prime/BBF/descend), tamanho do pool,
  `minflt/majflt` (`/proc/self/stat`), p50/p99/p999 internos. + **replay
  offline determinístico** das 54.100 (`src/test`) que conta nós/pool/faults
  (**métricas HW-independentes** que predizem o custo memory-bound) — ranqueia
  levers **sem queimar preview**. Não é emulação; o p99 segue no preview.

### §2. Fase 1 — Reduzir `s.visits` (exatidão-neutro, E=0-safe)
- Pool cap **já auditado** (maxPool 104 « cap): não mexer; não é a causa.
- **Bound inicial mais apertado**: melhorar `prime`/`plunge` (mais seeds bons
  antes do BBF) corta nós visitados sem mudar o resultado. Grid-search dos
  constants **exatidão-neutros** (`PRIME_FANOUT_DEPTH`, `PRIME_PLUNGE_CAP`,
  `TOP_BBOX_DEPTH`, `BBF_HEAP_CAP` 256→512/1024) via replay determinístico,
  minimizando `s.visits` mantendo `ExactAgree` 0-div.
- Pré-filtro grosseiro **opcional** (bucket/IVF barato) **só com prova de
  superset suficiente** + rerank exato — alavanca grande p/ cortar nós.

### §3. Fase 2 — Localidade de memória (o gargalo real — **MAIOR ROI, fazer 1º**)
- **Relayout vEB/BFS dos nós** (a maior alavanca): hoje `KdTreeBuilder` numera
  em pré-ordem DFS. Renumerar em **van-Emde-Boas / BFS-blocked** (topo contíguo
  sempre-quente; subárvores em blocos do tamanho de página) ⇒ caminho
  raiz→folha toca `O(log_B n)` linhas em vez de `O(log n)` espalhadas. **E=0
  por construção**: é renumeração pura — mesma árvore/splits/origId, só muda
  `treeIdx` e a ordem em `pts[]` (re-empacotar `leftAndDim`/`right`, reindexar
  `topSlot`/`origId`); `ExactAgree` 0-div prova. Offline em `KdTreeBuilder`/
  `KdTreeIO`/`Prebuild` (formato `RKD4`); determinismo intacto (`Random(42L)`
  dirige splits, não layout).
- mmap **residente** sob cgroup: prewarm agressivo (já existe `KdMmap.prewarm`);
  `MAP_POPULATE`/`mlock` se possível em Java 21 sem FFM (senão prewarm + footprint).
- **Reduzir footprint** (compete no page-cache sob 350 MB ⇒ menos faults,
  lossless E=0-safe): `origId` int32→**int24** (ids<3M<2²⁴; −3 MB; truque RBH2
  Onda 4a), `topSlot` int32 esparso/compacto (−~10 MB). (Footprint muda ⇒
  reconciliar `Dockerfile`.)
- Opcional: `pts` cache-line align / hot-cold split (28 B dims | nav) se medir ganho.

### §4. Fase 3 — Micro-opt (menor ROI, E=0-safe, por último com evidência)
Unroll `distSumI16`/`sqDistDoubleLikeC`, binary-search na inserção do rerank,
JSON single-pass, NIO/`sendfile` p/ respostas canned, grid-search final.

### §5. Gates (aceitação = a métrica do ranking)
- **G1 (preview oficial, A MÉTRICA):** `final_score` sobe; meta dura **top-10**
  (~p99 ≲ 1,1 ms / ~5.960+), mirando 6000/p99≤1 ms. Cada preview registrado.
- **G2 (E=0 invariante, BLOQUEIA):** `ExactAgree` 0-div/54.100 +
  tree==brute==expected. Qualquer quebra = **revert** imediato.
- **G3 (memória, BLOQUEIA):** `docker compose --compatibility` ≤350 MB,
  OOMKilled=false, 0 restart.
- **G4 (relativo local, orienta — não é veredito):** `run.sh` local
  determinístico melhora vs baseline da iteração; contadores caem.
- **G5 (anti-overfit):** casar o algoritmo, não a amostra 54.100.

**Parar:** top-10 estável em preview + ganho marginal. Estagnou **acima da
faixa top-10 (~1,1 ms)** apesar de **todas** as fases → gatilho p/ decisão
futura de escalar p/ kernel C/Rust (top-1 ≤1 ms segue aspiracional contínuo).

### §6. Inventário
| Arquivo | Ação |
|---|---|
| `knn/KdTree,KdScratch,KdLayout,KdMmap,KdTopK,KdTreeBuilder,KdTreeIO` | layout/visita, pool-cap, BBF-cap, grid-search, mmap residente |
| `knn/DistanceFunctions.java` | unroll escalar (E=0-safe) |
| `server/*`, `json/FraudRequestParser.java` | NIO/sendfile, JSON single-pass |
| `Main.java` | mmap `MAP_POPULATE`/`mlock`/prewarm |
| `src/test/ExactAgree.java` | G2 (inalterado na lógica) + nova instrumentação/replay em `src/test` (removível) |
| `docs/TUTORIAL_LATENCIA.md` (novo) + ARCHITECTURE/README/RINHA_PLAN | tutorial hands-on + reconciliação as-built |
| `docker-compose.yml`/`Dockerfile` | só se footprint do índice mudar |
| `rinha-de-backend-2026/{run.sh,test/test.js,config.json}` | harness oficial — **NÃO modificar** |

### §7. Riscos / mitigações
| Risco | Mitigação |
|---|---|
| Pré-filtro quebra E=0 | Só entra com prova de superset suficiente; G2 bloqueia; revert |
| p99 local não prediz Mac Mini | Loop usa **relativo** + contadores HW-independentes; preview = veredito |
| Reordenar árvore quebra exatidão | Permutação/IDs preservados; G2 0-div prova; build offline determinístico |
| Footprint↓ regride memória/exatidão | G3 cgroup + G2; só compactar lossless |
| Java/GraalVM não chega a sub-ms no 2014 | Gatilho explícito → escalar p/ kernel C/Rust (decisão futura do usuário) |
| Overfit à amostra 54.100 | G5; nada tunado que não generalize (constants são perf-only) |

### §8. Próximo passo
`docs/TUTORIAL_LATENCIA.md` (hands-on PT-BR) + reconciliação as-built quando os
gates fecharem. Commits `main` (sem atribuição Claude, identidade `arthurd3`):
spec → tutorial+reconcil → feat (por fase). Branch `submission` bump
`:onda7`→`:onda8` quando o preview melhorar. `docker push`/`git push`/issue
`rinha/test` = ações outward-facing do usuário. **Onda 8 = corrida pelo p99;
sem "fim" enquanto houver gap p/ top-1 e ganho claro.**
