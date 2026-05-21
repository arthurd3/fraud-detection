# Performance Ledger — fraudDetection (Rinha de Backend 2026)

> **Living document.** Updated after every wave with measured outcomes.
> Last update: 2026-05-21 (post-Onda 14 revert).
> Single source of truth for what has been tried, what the cost of each
> component is, and what to attack next.

---

## 0. TL;DR

- **Production**: image `arthurd3/rinha-fraud:onda12`, compose at commit `6e6d766` (Onda 13 cpuset+sysctls).
- **Last Mac Mini preview** (`:onda12`, pre-Onda 13 compose): **final 4463.64 / p99 34.38 ms / E=0**.
- **Calib median 2026-05-20** (`:onda12` + Onda 13 compose, host quiet): **4836 / 14.59 ms**.
- **Top-of-leaderboard target**: 6000. Gap remaining = ~1500 pts, **100% in `p99_score`** (`detection_score` already 3000/3000).
- **E=0 strict** is the inviolable gate (ExactAgree 0-div over 54 100 entries).

---

## 1. Hot-path component cost map

Verified per-request cost. Numbers in microseconds (µs) unless noted. Sources:
- Code reading (Explore agent, 2026-05-20)
- HotSpot harness measurements (G3 AllocCheckKd 0.00 B/query, G4 VisitsReplay 310 visits/query)
- Standalone k6 baseline (final 6000 / p99 0.37 ms when binary has full CPU)

| Componente | Cost/req | % do total | Status | Owner file |
|---|---|---|---|---|
| HTTP method + path + header state machine | 0.5-0.8 µs | 1-2% | Optimized | `server/HttpParser.java`, `NioServer.java` |
| JSON `findKeyExact` × ~17 calls (linear scan body for every key) | **8-12 µs** | **25-30%** | **OPEN — main lever** | `json/FraudRequestParser.java:144-152` |
| JSON `valPos` (`:` scan after each key) | 3-5 µs | 8-12% | OPEN | `json/FraudRequestParser.java:155-161` |
| Numeric `num()` × 14 fields (decimal walk) | 12-17 µs | 30-40% | OPEN (small per-field, large total) | `json/FraudRequestParser.java:212-224` |
| Date `civilToDays` × 2 (Howard Hinnant) | 2-3 µs | 5-7% | Optimized | `json/FraudRequestParser.java:272-279` |
| Quantize `r4()` × 14 (Math.round + cast) | ~1 µs | 2-3% | Optimized (JIT inlined) | `json/FraudRequestParser.java:114-116` |
| **KD-tree search (310 visits × distSumI16 + heap ops)** | **15-20 µs** | **35-45%** | **SATURATED** (Onda 11 floor ~270 visits) | `knn/KdTree.java` |
| Distance kernel `distSumI16` (AVX2 auto-vectorized) | ~50 ns/visit | (embedded in KD-tree) | Optimized — compiler emits SIMD | `knn/KdTree.java:249-287` |
| HTTP response write (canned bytes, single flip) | 0.2-0.3 µs | <1% | Optimized | `server/HttpResponseWriter.java` |
| ConnectionState `reset()` | ~0.1 µs | <1% | Optimized | `server/ConnectionState.java:59-72` |
| **Total estimado** | **~40-60 µs/req** | 100% | — | — |

**Note on calib vs standalone**: standalone (binary, full CPU) runs at ~0.37 ms p99. Under cgroup throttle (cpus=0.51 calib / 1.0 Mac Mini) p99 expands due to queuing under burst; per-request cost reduction reduces queue depth, which reduces tail.

---

## 2. Wave history & measured outcomes

Sorted oldest → newest. *Calib columns are MEDIAN of 3 trials.*

| Wave | Commit | Image | Mudança principal | Calib final | Calib p99 | Mac Mini preview | Ship |
|---|---|---|---|---|---|---|---|
| Onda 5 (anchor) | (pre-tracking) | `:onda5` | GraalVM AOT + PGO baseline | — | — | 4393.85 / ~32ms | ✓ |
| Onda 7 v2 | 129821c | `:onda7` | Exact KD-tree + BBF (replace HNSW) | — | — | ~4490 / 32.31ms | ✓ |
| Onda 9 Passo 2 | eb098bf | `:onda9` | `PRIME_PLUNGE_CAP` 4→0 (-10% visits) | — | — | — | ✓ |
| Onda 10 | 8572a5a | `:onda10` | `KDTREE_MAX_VISITS=1500` cap | 4378 | 41.9ms | — | ✓ |
| Onda 11 | 2030b8f | `:onda11` (local) | Beam-of-2 prime + `BBF_MAX_DEPTH=22` | 4451 | 35.4ms | — | ✓ |
| Onda 12 | 7573baa | `:onda12` | PGO regen + NIO `TCP_NODELAY` + mmap `load()` + HAProxy `splice-auto` + `tcp-smart-{accept,connect}` | 4557 | 27.7ms | **4463.64 / 34.38ms** | ✓ |
| Onda 13 | **6e6d766** | `:onda12` | `cpuset` (0/1/2) + sysctls `somaxconn=1024` + `tcp_fastopen=3` | **4836** | **14.59ms** | (pending) | ✓ |
| Onda 14 | (revert) | `:onda14` (deleted) | BUNDLE: parser `indexKeys` + `-H:+RemoveUnusedSymbols` + HAProxy `bufsize=4096`/`maxaccept=32`/`rcvbuf` + timeouts 1s/5s/5s + PGO regen | 4573 | 26.73ms | — | ✗ REGRESS, revert |
| Onda 15a | (no commit) | `:onda12` | HAProxy `tune.bufsize=8192` only | 4505 | 31.3ms | — | ✗ no-op/regress in noisy calib |
| Onda 15 | (revert) | `:onda15` (deleted) | Parser `indexKeys` single-pass ISOLATED (G2 PASS, G3 PASS, G4 unchanged) — first 1-lever discipline test | 4584 | 26.05ms | — | ✗ FALSIFIED isolated (noisy host; t2 outlier 5819/1.52 ms inconclusive) |
| Onda 16 | (revert) | `:onda16` (deleted) | PGO regen ISOLADO contra Onda 13 source (zero Java change, only `api/default.iprof` replaced); 2nd disciplined 1-lever test | 4554 | 27.87ms | — | ✗ FALSIFIED isolated (noisy host load 4.96 / swap 13 GB; t1 outlier 4946/11.31 ms positive signal but t3 outlier 4170/67.47 ms drags median down) |
| Onda 17 | TBD | `:onda15` | SHIP parser `indexKeys` (Onda 15 code re-applied) despite calib falsification, per user decision to validate on Mac Mini; stale PGO from Onda 12; G1+G2 PASS (0 div / E=0) | (deferred, host busy) | (deferred) | (pending push) | ⏳ SHIPPED for preview |

---

## 3. Levers per component

For each component: **WINNERS** (kept), **FALSIFIED** (do not repeat), **OPEN** (untried but feasible).

### 3.1 KD-tree search (15-20 µs, SATURATED)

**WINNERS**:
- (Onda 7v2) Replace HNSW with exact KD-tree + BBF — algorithmic correctness baseline.
- (Onda 9 Passo 2) `PRIME_PLUNGE_CAP` 4→0 — −10% visits, E=0.
- (Onda 10) Visit cap 1500 — tail-cut, +120 mean, E=0 in calib.
- (Onda 11 Phase A) Beam-of-2 prime — prime visits 63→36.
- (Onda 11 Phase B v2) `BBF_MAX_DEPTH` 18→22 + caps 1024 — descend 163→39, structure reorder.

**FALSIFIED** (do not repeat):
- (Onda 10 Step 1) Bulk-read mmap `getLong`+shifts — −327 pts even with matched PGO. C2 auto-vec is better than manual unroll.
- (Onda 11 candidate) Plunge variants — sweep `PLUNGE_CAP` 0..32 produced identical bbf/descend visits.
- (Onda 8 Fase 2c) Layout pre-order DFS — `distinctPages` predictor misleading; no Mac Mini p99 gain.
- Manual SIMD via `jdk.incubator.vector` — broke GraalVM native-image link (Onda 5 removed).
- FFM / `Unsafe` — broke native-image link.

**OPEN** (none worth pursuing): floor at ~270 bbf+descend visits is structural for 14-D KD-tree + BBF; further reduction requires either E>0 or architectural change (both blocked by constraints).

### 3.2 JSON parser (~30-40 µs, SHIPPED for preview)

**WINNERS (calib-falsified, Mac-Mini-pending)**:
- **Single-pass `indexKeys` per scope** (Onda 15 + Onda 17 ship, 2026-05-21). G2 PASS over 54 100 (E=0), G3 zero-alloc, G4 visits 310 unchanged. Calib 3 trials sob host degradado: median **4584/26.05 ms** = FALSIFIED por critério estrito. **MAS** Onda 17 ship per user decision: t1 outlier 5819/1.52 ms (best of life) sugere ganho real perdido em ruído; o Mac Mini limpo é o arbiter. Re-applied via `:onda15` image build, submission compose bumped, awaiting Mac Mini preview ground-truth. Hypothesis-of-falsification preserved: inner-loop overhead of trying every key at each `"` may offset early-terminate wins when keys cluster near body top.

**OPEN** (de-prioritized vs §6):
- Pre-permute `queryQ16` in parser — KdTree.prepareSearch currently re-permutes; ~50 ns saved (marginal).
- Inline `strEnd` / `bool` / `nextNonWs` — micro-opts; GraalVM likely already inlines.
- Perfect-hash dispatch on first byte of key — `{'a','c','i','k','m','t'}` buckets; speculative; complex.

### 3.3 HAProxy

**WINNERS**:
- (Onda 12) `option splice-auto` (kernel zero-copy TCP forward).
- (Onda 12) `option tcp-smart-accept` + `option tcp-smart-connect` (Nagle-disable for TCP mode).

**FALSIFIED**:
- `option tcp-nodelay` — not valid in mode tcp (HAProxy 3.0 syntax error).
- (Onda 14 bundle) `tune.bufsize=4096` + `tune.maxaccept=32` + timeouts 1s/5s/5s — regression.
- (Onda 15a isolated) `tune.bufsize=8192` — no-op / slight regression in noisy calib.

**OPEN**:
- `tune.maxaccept=64` (less aggressive than 32).
- `nbthread=2` (haproxy budget 0.05 cpu → marginal).
- TCP-mode-only knobs not yet probed.

### 3.4 NIO server (~500 ns hot-path overhead)

**WINNERS**:
- (Onda 12) Explicit `TCP_NODELAY` on accepted sockets.

**FALSIFIED**: none specifically.

**OPEN**:
- `selector.select()` blocking → `selectNow()` / `select(1)` to reduce idle-wakeup tail.
- Multi-selector pattern — NOT useful at cpus=0.425 single-thread budget.

### 3.5 Container / cgroup

**WINNERS**:
- (Onda 13) `cpuset` pinning: haproxy=0, api-1=1, api-2=2 — measured −47 % p99 in calib (with sysctls also active; isolated cpuset gain unmeasured).

**FALSIFIED (by Rinha rules, not by perf)**:
- (Onda 13) sysctls `net.core.somaxconn=1024` + `net.ipv4.tcp_fastopen=3` — **BANNED by Rinha rules**, rejection #5854 on 2026-05-21 ("using 'sysctls' not allowed (services: api-1, api-2)"). Removed from submission compose in commit `3d95346`. Kept on `main` `docker-compose.yml` only for local calib measurements (does not ship). Their contribution to the Onda 13 calib measurement (4836/14.59ms median) is now confounded with the cpuset gain.

**OPEN**:
- `cfs_period_us` shrink (100 ms → 20 ms) — burst window reduction; complex due to calib overlay using `cpus:` short-form.
- `--ulimit memlock=-1` + `mlockall()` via JNI — locks mmap pages, but JNI adds native dep.

### 3.6 Native image build (PGO + AOT flags)

**WINNERS**:
- (Onda 5) `-O3` + `-march=x86-64-v3` + `--gc=serial` + `-R:MaxHeapSize=64m` baseline.
- (Onda 5) `--pgo=default.iprof` (profile-guided optimization).
- (Onda 12) PGO regen against current source — recovers ~1.5-2% on Mac Mini when stale.

**FALSIFIED**:
- (Onda 14) `-R:MaxHeapSize=32m` — OOM at boot; 64m is the safe anchor.
- (Onda 14) `-H:Optimize=Performance` — not a valid GraalVM 21 keyword (expects 'b' or number; `-O3` already covers this).
- Manual unroll (see §3.1).
- **PGO regen vs Onda 13 compose-only changes** (Onda 16, 2026-05-21, ISOLATED). PGO regenerated using `Dockerfile.train` + host run + k6 training + SIGTERM — recipe valid (k6 instr training = 6000/0.39ms confirming correct binary), new `api/default.iprof` 4.1 MB captured. Native build `:onda16` succeeded. Calib 3 trials sob host degradado (load 4.96, swap 13 GB): t1=4946.70/11.31ms (positive signal), t2=4554.86/27.87ms, t3=4170.89/67.47ms → median **4554.86/27.87ms** = REGRESS branch (4554 < 4730). Honest: data inconclusive due to noise (spread 775 final / 5× p99); same outcome as Onda 15. **Reusable lesson**: compose-only changes (cpuset/sysctls without Java code change) do not retrain PGO into a meaningfully different profile — Onda 13's "different runtime path" hypothesis was likely overstated; PGO branch frequencies are determined by INPUT data (k6 seed=4242 deterministic), not by CPU placement or socket tunings. Don't repeat PGO regen for compose-only future waves.

**OPEN**:
- `-H:+RemoveUnusedSymbols` (in Onda 14 bundle, isolated test pending).
- `-H:+InlineEverything` (moderate risk — can bloat i-cache).
- `-H:CompilerBackend=llvm` (experimental).
- `-H:+UseTransparentHugePages` (host setting; Mac Mini we don't control).

### 3.7 Distance kernel `sqDistI16` (AVX2 auto-vec)

**WINNERS**: GraalVM auto-vectorization to AVX2 (proven by Onda 10 Step 1 falsification).
**OPEN**: nothing — compiler-emitted SIMD is optimal for this loop.
**FALSIFIED**: manual unroll, `Vector API`, `getLong`+shifts.

### 3.8 Mmap dataset (`references.kdt`, 163 MB)

**WINNERS**:
- (Onda 12) `KdMmap.loadIntoMemory()` calls `MappedByteBuffer.load()` — MADV_WILLNEED + force page-in.
- (Pre-Onda 12) `KdMmap.prewarm()` — touch 1 byte per 4 KB page at boot.

**OPEN**:
- `mlockall(MCL_CURRENT)` via JNI — would guarantee residency even under cgroup pressure.
- Pre-fault on a separate boot thread to overlap with HTTP server bring-up.

**FALSIFIED**:
- `madvise(HUGEPAGE)` via FFM — broke native link.

### 3.9 Mid-request data layout

**WINNERS**:
- (Onda 7v2) RKD3/RKD4 packed 20×i16 stride (14 dims + nav + flag).

**FALSIFIED**:
- (Onda 8) Pre-order DFS layout (`distinctPages` improved but p99 unchanged).
- (Onda 11) BBF heap shrink — already at watermark headroom (max 248/1024).

---

## 4. Calib rig noise characteristics

Calib rig is a **proxy** for Mac Mini, not a precise simulator. Variance changes with host state.

| Sessão | Host load avg | Host swap used | Onda 13 trials range | Onda 13 median p99 | Spread |
|---|---|---|---|---|---|
| 2026-05-20 (limpo) | <1.0 | <1 GB | 4767–4909 | 14.59 ms | 142 |
| 2026-05-21 (sobrecarregado) | 2.35 | 13 GB | 4471–5663 | 24.24 ms | **1192** |

**Lesson**: spread varies 8× between sessions. Run 3+ trials; prefer host with `load < 1.0` and `swap < 1 GB`. Best-of-3 single trial proves capability (5663/2.17 ms seen on 2026-05-21).

---

## 5. Constraints invioláveis

- **E=0 strict** — `ExactAgree` 0-div over 54 100 entries (FP=0, FN=0).
- **Java / GraalVM native-image only** — no FFM, Unsafe, Vector API (all broke native link historically).
- **Topology** — HAProxy + 2 backends, ≤ 350 MB RAM total, ≤ 1.0 CPU total (Rinha rules).
- **`sysctls:` PROHIBITED** in submission compose services (Rinha rejection #5854, 2026-05-21: "using 'sysctls' not allowed (services: api-1, api-2)"). Discovered after Onda 13 already used it. Removed from submission (commit `3d95346`); kept on `main` `docker-compose.yml` for local calib only.
- **detection_score = 3000 / 3000 MAX** — accuracy is saturated; all remaining gap is in `p99_score`.
- **No pre-baking test answers** — the schema is fixed but bakings would be cheating.

---

## 6. Levers ABERTOS, em ordem de prioridade

> Each must be **ISOLATED** (1 change per wave), **measured 3+ trials** on a QUIET host, then validated by Mac Mini preview.

| Pri | Lever | Hipótese | Custo cycle | Risco | Como medir |
|-----|-------|---------|---|---|---|
| **1** | **Mac Mini preview do Onda 13 atual** (`:onda12` + cpuset+sysctls compose) | Mede a vitória já garantida pelo Onda 13 antes de qualquer mudança nova | 0 (só abrir issue rinha/test) | nulo | upstream issue |
| ~~2~~ | ~~Parser `indexKeys` ISOLADO~~ | ~~~~ | — | — | FALSIFIED 2026-05-21 (Onda 15, ver §3.2) |
| ~~3~~ | ~~PGO regen ISOLADO~~ | ~~~~ | — | — | FALSIFIED 2026-05-21 (Onda 16, ver §3.6) |
| **2** | HAProxy `tune.maxaccept=64` ISOLADO | Lighter throttle de accept burst (menos que 32, mais que 100) | compose-only, sem rebuild | baixo | calib 3 trials |
| **3** | `-H:+RemoveUnusedSymbols` ISOLADO | I-cache tighter — tested in Onda 14 bundle, isolated unclear | rebuild 5-10 min | nulo | calib 3 trials |
| **4** | `cfs_period_us=20000` ISOLADO (override calib `cpus:`) | Burst window 20 ms → smoother throttling | compose-only, complex override | médio | calib 3 trials |
| **5** | `mlockall()` via JNI (NÃO FFM) | Lock mmap pages contra eviction sob cgroup | JNI 1 file, rebuild | baixo | calib 3 trials |
| **6** | Profiling `perf stat` (`paranoid=1` 1 sudo) | Confirma qual componente domina cycles sob calib | 20 min cycle | nulo | perf stat -d output |
| **7** | Reboot host + retest Onda 13 baseline + retry Onda 15/16 sob host limpo | Maybe the falsifications were noise, not real (t1 in both showed promise) | 30 min cycle + ledger update | médio | clean calib 3 trials |

---

## 7. Protocolo de ataque (going forward)

1. **Quiet the host first**: aguardar `load avg < 1.0` E `swap < 1 GB`. (Reboot é OK se urgente.)
2. **Pick ONE lever** do §6.
3. **Isolar**: `git stash` de qualquer mudança em curso; aplica APENAS aquela lever.
4. **Run gates** (G1 build, G2 ExactAgree se mudou Java code, G3 AllocCheckKd, G4 VisitsReplay).
5. **Calib 3 trials** mínimo; capture results.json para `/tmp/rig-onda<N>-t<t>.json`.
6. **Decisão**:
   - **Win**: median final ≥ +3 % E p99 ≤ baseline. Commit + update §2 + §3.
   - **No-op**: dentro de noise. Revert + update §3.3 with "FALSIFIED isolated test".
   - **Regress**: median worse. Revert + capture data + update §3.3.
7. **Atualizar este ledger** após CADA wave (§2 row, §3.x bullet).
8. **Ship to Mac Mini preview** apenas após win consistente em calib limpo.

---

## 8. Histórico de mudanças deste documento

- **2026-05-21**: criação. Documenta estado pós-Onda 14 revert. Próximo ataque sugerido = §6 lever #1 (Mac Mini preview do Onda 13).
- **2026-05-21** (segunda edição, post-Onda 15 falsification): Onda 15 (parser `indexKeys` ISOLATED) testada disciplinadamente. G1-G4 todos PASS (semântica correta, E=0 preserved). G5 calib sob host degradado (load 12.15, swap 13 GB) deu median 4584/26.05 ms vs noisy-baseline 4615/24.24 — essentially no-op (strict acceptance criterion 4730 violado). Revert per protocolo. Lever §6.2 (parser indexKeys) movido para FALSIFIED em §3.2; PGO regen ISOLADO promovido para §6.2. Hipótese da falsification: inner-loop overhead em quote position offset early-terminate de findKeyExact original (most keys found near top of body). t2 outlier 5819/1.52 ms registrado mas inconclusive em 3-trial sample sob host ruim.
- **2026-05-21** (terceira edição, post-Onda 16 falsification): Onda 16 (PGO regen ISOLATED) testada disciplinadamente. Recipe Dockerfile.train + host run + k6 train + SIGTERM funcionou (k6 instr = 6000/0.39ms; iprof 4.1 MB capturada). Native build `:onda16` OK. G5 calib sob host degradado (load 4.96, swap 13 GB): t1=4946/11.31ms (positive signal!) t2=4554/27.87ms t3=4170/67.47ms → median **4554/27.87ms** — REGRESS branch acionado (4554 < 4730). Revert. Lever §6.3 (PGO regen) movido para FALSIFIED em §3.6. Reusable lesson: PGO branch frequencies são determined by INPUT data (k6 seed 4242), não por CPU placement / socket tunings — compose-only changes não motivam PGO regen. NOVA lever §6.7 adicionada: reboot host + retest baseline + retry Onda 15/16 — t1 de ambas as ondas mostrou positive signal que pode ser perdido na noise; vale revalidar sob host limpo. Próximo lever atual: §6.2 = HAProxy `tune.maxaccept=64` ISOLADO (compose-only, sem rebuild, baixo risco).
- **2026-05-21** (quarta edição, post-Rinha rejection #5854): Submission `351bd71` rejeitada pelo Rinha CI ("using 'sysctls' not allowed (services: api-1, api-2)"). §5 ganhou constraint explícita: `sysctls:` proibido em submission compose. §3.5 atualizada: as Onda 13 sysctls (`somaxconn=1024` + `tcp_fastopen=3`) movidas de WINNERS para "FALSIFIED by Rinha rules" — perf era real, mas não pode shipar. Importante: a medição calib Onda 13 (4836/14.59ms) estava INFLADA pelo sysctls. Submission re-pushed sem sysctls (commit `3d95346`). Próximo preview vai medir o que realmente ship: cpuset puro + KDTREE_MAX_VISITS env + HAProxy splice/tcp-smart-*.
