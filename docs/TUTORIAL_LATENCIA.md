# TUTORIAL — Onda 8: Otimização de latência (exato, Java/GraalVM, foco memória)

> Hands-on PT-BR. Spec: `docs/superpowers/specs/2026-05-19-onda8-latencia-design.md`.
> **Continua** a Onda 7 v2 (acurácia fechada — E=0/det 3000 validado no preview
> #5524). Esta onda ataca **só o p99** (32,31 ms no Mac Mini → ≤~1,1 ms top-10,
> mirando ≤1 ms/6000), mantendo **E=0 invariante absoluto**.

## §0. Diagnóstico (fundamentado na leitura do código, 2026-05-19)

- Kernel local ~50–250 µs; preview oficial **p99 32,31 ms** (≈60×). Não é
  CPU/algoritmo — é **localidade de memória**: custo = `s.visits` × latência
  de acesso. KD-tree 14-D exata visita muitos nós; `KdTreeBuilder` numera em
  **pré-ordem DFS** ⇒ filho-direito = `i + |subárvore_esq|` (saltos enormes),
  BBF best-first espalha leituras por 159 MB. L3 3 MB + page-cache sob 350 MB
  no Mac Mini 2014 ⇒ cada salto = cache/TLB/page-miss caro.
- **Descartado:** `KdScratch.POOL_CAP=1<<16` é folga; `ExactAgree` mediu
  `maxPool=104` nas 54.100 — pool não explode, não é a causa.
- `s.visits` **já é contado** em `KdScratch` (instrumentação trivial).

## §1. Loop oficial local sob limites (a régua relativa)

Não emular HW; rodar o teste oficial nos limites reais. Determinístico (seed 4242).

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
# build nativo + imagem (mesmo Dockerfile da :onda7; tag de trabalho :onda8)
docker build -t arthurd3/rinha-fraud:onda8 . 2>&1 | cat        # cache-hit prova fiel
cd ../rinha-de-backend-2026
docker compose --compatibility up -d                            # 1CPU/350M reais
for i in $(seq 1 20); do c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 \
  http://localhost:9999/ready); [ "$c" = 200 ] && break; sleep 3; done; echo "ready=$c"
./run.sh                                                         # k6 ramp 1→900/120s
cat test/results.json                                            # final_score/p99/E
docker compose --compatibility down
```

> ⚠️ p99 absoluto local ≠ Mac Mini (CPU mais rápida). Use **ganho relativo** +
> contadores HW-independentes (§2). Veredito de p99 = **preview oficial**
> (issue `rinha/test arthurd3-java-hnsw`, ilimitada).

## §2. Fase 0 — Instrumentação + BASELINE (pré-requisito)

1. Expor `s.visits` (e `maxPool`/`maxHeap`) por request sob **flag de sistema**
   (`-Dfd.instr=1`), agregando p50/p99/p999 + `minflt/majflt` de
   `/proc/self/stat`. **Removível do build de submissão** (zero custo quando off).
2. `src/test`: harness de **replay determinístico** das 54.100 (reusa
   `TestDataReader`/`ExactAgree`) que roda o kernel e reporta a distribuição de
   `s.visits` (média/p99/máx) — métrica **HW-independente** que prediz o custo
   memory-bound; ranqueia levers **sem queimar preview**.
3. **Capturar BASELINE ANTES de qualquer mudança**: `ExactAgree` (G2 0-div),
   `run.sh` local (final_score/p99 relativo), replay (visits dist), preview
   (registrar — é o 4490,66/p99 32,31 ms já conhecido). Congelar números.

## §3. Fase 2 — Relayout vEB/BFS (MAIOR ROI — fazer 1º) + footprint

A maior alavanca. **Renumeração pura ⇒ E=0 por construção.**

1. `KdTreeBuilder`: após `buildRecursive` montar a árvore lógica, **renumerar**
   os nós em ordem **van-Emde-Boas / BFS-blocked** (topo contíguo; subárvores
   em blocos ~página). Reescrever `pts[]` na nova ordem, re-empacotar
   `leftAndDim`/`right` com os novos índices, reindexar `origId[]`/`topSlot[]`.
   Mesma árvore/splits/fraud/origId — só muda `treeIdx` e a ordem física.
2. `KdTreeIO`: formato **`RKD4`** (bump magic/ver); `Prebuild` regenera
   `references.kdt`. `isValid` aceita só RKD4 em produção (RKD3 vira legado de
   teste, se mantido).
3. **Footprint (lossless, E=0-safe):** `origId` int32→**int24** (ids<3M<2²⁴,
   −3 MB; espelha RBH2 da Onda 4a), `topSlot` esparso/compacto (−~10 MB).
   `Dockerfile`/`.dockerignore` reconciliar se o tamanho do `.kdt` mudar.
4. mmap residente: manter/forçar `KdMmap.prewarm`; `MAP_POPULATE`/`mlock` só
   se viável em Java 21 sem FFM (senão prewarm + footprint menor já reduz faults).

**Gate a cada passo:** `ExactAgree` 0-div/54.100 (bloqueia — qualquer quebra =
revert) → `run.sh` local + replay (visits/relativo cai) → preview (p99 real).

## §4. Fase 1 — Reduzir `s.visits` (exatidão-neutro)

Grid-search dos constants **exatidão-neutros** via replay determinístico,
minimizando `s.visits` mantendo `ExactAgree` 0-div: `PRIME_FANOUT_DEPTH`,
`PRIME_PLUNGE_CAP`, `TOP_BBOX_DEPTH`, `BBF_HEAP_CAP` (256→512/1024). Bound
inicial mais apertado (melhor `prime`/`plunge`) corta nós sem mudar resultado.
Pré-filtro grosseiro (bucket/IVF) **só** com prova de superset suficiente.

## §5. Fase 3 — Micro-opt (menor ROI, por último, com evidência)

Unroll escalar `distSumI16`/`sqDistDoubleLikeC`, binary-search na inserção do
rerank, JSON single-pass em `FraudRequestParser`, NIO/`sendfile` p/ respostas
canned. Só se o replay/preview mostrar ganho — não especular.

## §6. Pegadinhas (do histórico do projeto)

- Build LIMPO: `./mvnw clean package` (incremental mascara omissões).
- `ExactAgree`/`AllocCheckKd` rodam de `fraudDetection/api` (paths relativos).
- `docker compose` precisa **`--compatibility`** p/ aplicar 350M/1CPU reais.
- snap docker: `… --format … | cat`; operar sob `$HOME`; `docker build … 2>&1 | cat`.
- Capturar BASELINE/golden **antes** do rebuild; sem `set -u`; matar :9999 por PID da porta.
- Relayout: provar `ExactAgree` 0-div ANTES de confiar — renumeração tem que ser exata.
- Commits sob a identidade `arthurd3`; push = usuário.

## §7. Gates (= a métrica do ranking)

- **G1 (preview oficial, A MÉTRICA):** `final_score` sobe; meta dura top-10
  (~p99 ≲ 1,1 ms / ~5.960+), mirando 6000/≤1 ms.
- **G2 (E=0, BLOQUEIA):** `ExactAgree` 0-div/54.100 + tree==brute==expected.
- **G3 (memória, BLOQUEIA):** `--compatibility` ≤350 MB, OOMKilled=false.
- **G4 (relativo local, orienta):** `run.sh`/replay determinístico melhora vs baseline.
- **G5 (anti-overfit):** casar o algoritmo, não a amostra 54.100.

## §8. Fechamento

Reconciliar as-built (ARCHITECTURE/README/RINHA_PLAN, nota datada — padrão do
projeto) quando os gates fecharem. Commits `main` (sob a identidade
`arthurd3`): spec → tutorial → feat (por fase). `submission` bump `:onda7`→
`:onda8` quando o preview melhorar. `docker push`/`git push`/issue `rinha/test`
= ações outward-facing do usuário. **Onda 8 = corrida pelo p99; sem "fim"
enquanto houver gap p/ top-1 e ganho claro.**
