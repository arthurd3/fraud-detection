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
| Onda 17 | 7a9f994 / f84424e | `:onda15` | SHIP parser `indexKeys` (Onda 15 code re-applied) + cpuset + cap env + haproxy splice/tcp-smart; G1+G2 PASS | (calib deferred) | (calib deferred) | **4006.75 / 35.14ms / E=30** | ⚠️ regressed −456 vs Onda 12 due to cap |
| Onda 18 | bb499cb (submission) | `:onda15` | Remove `KDTREE_MAX_VISITS` env (Onda 10 cap) from submission; binary unchanged (cap branch dead-code-eliminated when env absent); keeps cpuset + haproxy splice + parser | n/a (no rebuild) | n/a | **4455.83 / 35.01ms / E=0** (rank #109/272) | ✅ recovered baseline; cap removal worked exactly as predicted |
| Onda 21 | (this wave) | `:onda21` (local) | RKD6 STRIDE 20→19 + heap 64m→48m (TIER 1 lossless; −22 MB cgroup pressure attacking §6.2 mmap eviction hypothesis) | (deferred — host load 2.56 / swap 13 GB) | n/a | (preview pending) | ⏸️ G1-G4 green local; G5+G6 deferred to quiet host; ship pending |

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

### 3.5 Container / cgroup / runtime env

**WINNERS**:
- (Onda 13) `cpuset` pinning: haproxy=0, api-1=1, api-2=2 — measured −47 % p99 in calib (with sysctls + cap also active; isolated cpuset gain on Mac Mini unmeasured yet).

**FALSIFIED (by Rinha rules)**:
- (Onda 13) sysctls `net.core.somaxconn=1024` + `net.ipv4.tcp_fastopen=3` — **BANNED by Rinha rules**, rejection #5854 on 2026-05-21. Removed from submission (commit `3d95346`).

**FALSIFIED by Mac Mini (Onda 17 preview, 2026-05-21 10:52 BRT)**:
- **`KDTREE_MAX_VISITS=1500` env var (Onda 10 visit cap)** — calib measured cap=1500 as +120 mean (Onda 10 sweep) but Mac Mini real run shows it costs **−447 detection_score** for **30 weighted errors** (FP=12, FN=6) while delivering essentially **0 ms p99 reduction** (35.14ms vs 34.38ms Onda 12 baseline = ruído). **NET: −456 final on Mac Mini.** Removed from submission compose in Onda 18 (commit `bb499cb`). Kept on `main` compose for local calib reference. **Reusable lesson**: calib rig measures latency-under-throttle; the cap helps when the system is SATURATED (queueing dominates p99). Mac Mini contest quota=1.0 is NOT saturated for this workload → cap's p99 benefit collapses but the accuracy cost stays. **Never ship an accuracy-trading lever on calib evidence alone; require Mac Mini preview confirmation.**

**OPEN**:
- `cfs_period_us` shrink (100 ms → 20 ms) — burst window reduction; complex due to calib overlay using `cpus:` short-form. **Now even less attractive** since Mac Mini isn't saturated.
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

### 3.8 Mmap dataset (`references.kdt`, 157 MB after Onda 21; was 163 MB pre-Onda 21)

**WINNERS**:
- (Onda 12) `KdMmap.loadIntoMemory()` calls `MappedByteBuffer.load()` — MADV_WILLNEED + force page-in.
- (Pre-Onda 12) `KdMmap.prewarm()` — touch 1 byte per 4 KB page at boot.

**IN FLIGHT (Mac Mini preview pending)**:
- (Onda 21 TIER 1) RKD6 STRIDE 20→19 + heap 64m→48m: −22 MB cgroup pressure attacking §6.2 mmap eviction hypothesis. Lossless (drop unused pad lane). G1-G4 green local; G5+G6 deferred to quiet host.

**OPEN**:
- Pre-fault on a separate boot thread to overlap with HTTP server bring-up.
- (Onda 21 TIER 2 reserved) `packRightAndFraud` STRIDE 19→18: another −6 MB if TIER 1 + Onda 19 still leave p99 > 20ms (tutorial §5).

**FALSIFIED**:
- `madvise(HUGEPAGE)` via FFM — broke native link.
- `mlockall(MCL_CURRENT)` via JNI — Onda 20 research found no GraalVM native-image JNI binding documented; FFM blocked.

### 3.9 Mid-request data layout

**WINNERS**:
- (Onda 7v2) RKD3/RKD4 packed 20×i16 stride (14 dims + nav + flag).

**IN FLIGHT (Mac Mini preview pending)**:
- (Onda 21 TIER 1) **RKD6** — STRIDE 20→19, dropped unused pad lane 19. Lossless permutation of `pts` (no algorithm change). G3 visits IDENTICAL (310 mean), distinctLines mean 241→237 (−1.7%), distinctPages mean 24→23 (−4.2%). −6 MB mmap footprint.

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
- **`sysctls:` PROHIBITED** in submission compose services (Rinha rejection #5854, 2026-05-21). Removed from submission (commit `3d95346`); kept on `main` for local calib only.
- **Calib latency wins must be VALIDATED by Mac Mini preview before shipping accuracy-trading levers** (Onda 17 preview lesson, 2026-05-21). The Onda 10 `KDTREE_MAX_VISITS=1500` cap was calib-validated (+120 mean) but Mac Mini-falsified (−456 final from −447 detection). Calib rig saturates under throttle; Mac Mini at quota=1.0 doesn't saturate. Any future cap / approximation lever must clear Mac Mini preview before shipping.
- **detection_score = 3000 / 3000 MAX** — accuracy is saturated; all remaining gap is in `p99_score`.
- **No pre-baking test answers** — the schema is fixed but bakings would be cheating.

---

## 6. Levers ABERTOS, em ordem de prioridade

> Each must be **ISOLATED** (1 change per wave), **measured 3+ trials** on a QUIET host, then validated by Mac Mini preview.

| Pri | Lever | Hipótese | Custo cycle | Risco | Como medir |
|-----|-------|---------|---|---|---|
| ~~Mac Mini preview do Onda 13~~ | — | done in Onda 12 preview = 4463; Onda 17 added everything = 4006 (regress from cap); Onda 18 retries without cap (pending) | — | — | — |
| ~~Parser `indexKeys` ISOLADO~~ | ~~~~ | — | — | calib falsified Onda 15 §3.2; shipped Onda 17 — pending Mac Mini ground truth |
| ~~PGO regen ISOLADO~~ | ~~~~ | — | — | calib falsified Onda 16 §3.6 |
| ~~`KDTREE_MAX_VISITS=1500` cap~~ | ~~~~ | — | — | **FALSIFIED by Mac Mini Onda 17 §3.5** — −447 detection, removed Onda 18 |
| ~~Onda 18 Mac Mini preview~~ | — | ✅ done: 4455.83 / 35.01ms / E=0 (rank #109/272). Recovered baseline; cpuset+haproxy+parser net no-op on Mac Mini. | — | — | — |
| **1** | **Onda 19 Mac Mini preview** (cfs_period=20ms, cpu_quota=8500us, same 0.425 ratio) | Math says default 100ms period leaves up to 57.5ms throttle wait = p99 35ms tail. 20ms period caps wait at 11.5ms → p99 → 12-25ms predicted | 0 (push pending) | baixo (compose-only, cpus:0.425 mantido para Rinha CI) | upstream preview |
| ~~**2 (research-promoted)**~~ | ~~**Reduce references.kdt below 159MB cgroup limit**~~ | **IN FLIGHT — Onda 21 TIER 1 IMPLEMENTED LOCAL**: STRIDE 20→19 (drop pad lane) + heap 64m→48m ⇒ −6 MB mmap (157 MB vs 163 MB .kdt confirmed; 191 MB vs 197 MB image) + −16 MB heap reservation = −22 MB cgroup pressure. G1 ExactAgree E=0/54100; G2 AllocCheckKd 0 B/q; G3 VisitsReplay distinctLines mean 241→237 (-1.7%), p99 898→878 (-2.2%), visits IDENT 310; G4 docker stats 50 MiB/api (31% of 159 MiB), 0 OOM, 0 restart. **G5 + G6 calib DEFERRED** (host noisy 2.56 load / 13 GB swap); Mac Mini preview is the verdict. TIER 2 (`packRightAndFraud`, STRIDE 19→18, −6 MB further) reserved per tutorial §5 if TIER 1 + Onda 19 still leave p99 > 20ms. | done in this wave | médio (validated) | preview pending |
| 3 | HAProxy `tune.maxaccept=64` ISOLADO | Lighter throttle de accept burst | compose-only, sem rebuild | baixo | preview |
| 4 | `-H:+RemoveUnusedSymbols` ISOLADO | I-cache tighter | rebuild | nulo | preview |
| 5 | cfs_period=10ms (after Onda 19 if win) | Further compression if Onda 19 helps but not enough | compose-only | médio (scheduler overhead trade) | preview |
| 6 | Profiling `perf stat` (`paranoid=1` 1 sudo) | Final fallback if hypotheses 1-2 falsify on Mac Mini | 20 min cycle | nulo | perf stat output |
| ~~`mlockall()` via JNI~~ | — | research found: GraalVM native-image has NO documented JNI mlockall binding; FFM blocked. Falsified pre-attempt. | — | — | — |

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
- **2026-05-21** (quinta edição, post-Onda 17 Mac Mini preview): Onda 17 preview (commit `f84424e`, image `:onda15`) deu final **4006.75 / p99 35.14ms / E=30** vs Onda 12 baseline 4463.64/34.38/E=0 — **regress de −456 final**. 100 % do regress atribuível ao Onda 10 `KDTREE_MAX_VISITS=1500` env var (cap): −447 detection score por 30 weighted errors (FP=12, FN=6), zero p99 ganho. **CRITICAL LESSON**: calib mediu cap como +120 mean (Onda 10 sweep) mas Mac Mini real falsifica. Calib rig satura sob throttle; Mac Mini a quota=1.0 não satura, então benefício de cap (cortar fila de p99) colapsa enquanto custo de accuracy permanece. §3.5: KDTREE_MAX_VISITS=1500 movido para "FALSIFIED by Mac Mini". §5: nova constraint "Calib latency wins must be Mac Mini-validated before shipping accuracy-trading levers". Onda 18 (compose-only, commit `bb499cb` em submission) remove o env var; binary `:onda15` inalterado (cap branch dead-code-eliminada quando env ausente). Próximo preview vai medir o bundle "limpo" [parser + cpuset + haproxy splice + tcp-smart] sem cap — expectativa final ≥ 4463 (baseline recovery) com E=0.
- **2026-05-21** (sexta edição, post-Onda 18 Mac Mini preview = position #109/272): Onda 18 preview (commit `bb499cb`, image `:onda15`, modo exato) deu **final 4455.83 / p99 35.01ms / E=0 / detection 3000 MAX** — recuperou Onda 12 baseline (4463) perfeitamente, confirmou que o cap era 100 % do regress da Onda 17, e que o bundle [parser indexKeys + cpuset 0/1/2 + haproxy splice/tcp-smart] é **estatisticamente NO-OP** no Mac Mini (4455 vs 4463 = −8 = noise). p99 35ms persistente em TODAS as 3 previews (34.38 / 35.14 / 35.01) — não respondeu a NENHUMA lever testada até agora. **Hipótese forte do bottleneck**: cfs_period_us=100ms default + cgroup quota 0.425 = burst window de 42.5ms / sleep window de 57.5ms a cada 100ms. Requests caindo durante o sleep esperam até 57ms = p99 inerente ~35-50ms. Toda lever testada (parser/cpuset/haproxy/cap) é ortogonal a essa janela de scheduling. **Novo lever §6.1 emergente**: shrink `cpu_period`/`cpu_quota` em compose (não-rules-violation; é docker, não sysctls). period 20ms / quota 8500us = mesmo ratio 0.425 mas burst de 8.5ms / sleep 11.5ms → p99 tail máximo cai para ~12ms. Esta é a primeira lever que ataca o gargalo REAL do Mac Mini (cfs scheduling) ao invés do que calib mostrava (cap-related).
- **2026-05-21** (sétima edição, post-Onda 20 web research): Pesquisa diagnóstica online identificou **DUAS hipóteses concorrentes** para o p99 35ms estrutural, ambas plausíveis com a evidência: (1) **CFS throttling** [já em teste Onda 19, ataque pela janela de cfs_period]; (2) **mmap eviction** — references.kdt = 163MB > cgroup memory limit 159MB por api ⇒ kernel FORÇADO a evictar páginas sob carga ⇒ page faults = 5-10ms cada = explica os ~25-30ms de p99 que CFS sozinho não cobre. Critical reality check do Rinha 2025: **Java/GraalVM NÃO entrou no top 10** — vencedores foram Node.js (#1 = 2.55ms p99) e Go (#3 = 2.18ms p99), arquiteturas event-loop nativas. Java/native-image **sem mlockall disponível** (no JNI binding documentado para GraalVM native, FFM blocked). **Verdict honesto**: sub-10ms é IMPROVÁVEL nessa hardware (Mac Mini 2014 Haswell 2.6GHz, 0.425 CPU/api) — o teto realista do nosso stack é **15-20ms p99** (após Onda 19 + reduzir references.kdt). §6 reshufflado: §6.2 promovido para "reduce references.kdt below 159MB" (próximo grande lever data-driven). §6 ganhou ~~mlockall~~ riscado (não disponível). Sources documentados: Rinha 2025 final results, GraalVM memory docs, Kubernetes throttling research, mmap+cgroup interaction posts.
- **2026-05-21** (oitava edição, post-Onda 21 TIER 1 local validation): **Onda 21 TIER 1 IMPLEMENTADA + VALIDADA local**; G5 (k6 host score) e G6 (calib 3 trials) **DEFERIDOS** p/ host quieto (atualmente load 2.56 / swap 13 GB; ledger §4 documenta noise 8× sob host loaded). Mudanças: (a) RKD6 STRIDE 20→19 — drop pad lane 19 (unused, escrita por KdTreeBuilder mas nunca lida no hot path); magic+ver bump RKD5→RKD6 ⇒ KdTreeIO.isValid rejeita .kdt antigo ⇒ Prebuild auto-regen no boot/test; (b) `-R:MaxHeapSize` 64m→48m (steady-state zero-alloc, pico boot <16 MB ⇒ 48m tem >2× headroom; Onda 14 falsificou 32m por OOM). Combined: −6 MB mmap pts (114 MB vs 120 MB) + −16 MB heap reservation = **−22 MB cgroup pressure** direta sobre limite 159 MiB/api. Predição footprint **confirmada empiricamente**: `references.kdt` 163.254.620 B → **157.254.620 B** = exatamente −6 MB (3M × 2 B × 1 lane); imagem `:onda21` **191 MB** vs `:onda15` 197 MB. Gates (Fase 1 baseline preservada como golden oracle): **G1 ExactAgree** 0 mismatches/54100 + brute oracle 0 div em 500+500 + maxHeap 248/1024 + maxPool 76/1024 (**E=0 strict preservado**; Prebuild auto-regen em 6s); **G2 AllocCheckKd** 0.00 B/100k queries (**zero-alloc preservado**); **G3 VisitsReplay** visits 310/237/1148/2179 IDÊNTICOS ao baseline (algoritmo unchanged por construção — drop pad não toca decisão de split/visit), distinctPages mean 24→23 (−4.2%), distinctLines mean 241→237 (−1.7%) / p99 898→878 (−2.2%) / max 1715→1686 — **melhoras marginais** porque L1d miss não era o gargalo real (working set mean 15.4 KiB já cabia L1d); **G4 docker stats × 3** — api-1/api-2 ~50 MiB cada (31% de 159 MiB), haproxy 13 MiB pico, OOMKilled=false × 3, Restart=0 × 3 (total cgroup usage 114 MiB / 350 MiB = 32%, folgado). **Honest caveat para G4**: host 32 GB tem 10 GB de page cache global absorvendo o mmap; o mecanismo `−22 MB cgroup pressure` só vai gerar efeito real onde page cache é constrained (Mac Mini 8 GB, 4 contêineres, OS). Próximo: opção (a) usuário pode push `arthurd3/rinha-fraud:onda21` + push main + bump submission compose `:onda15`→`:onda21` agora pra disparar prévia Mac Mini (custa pouco — Onda 19 cfs_period é compose-only ortogonal, podem coexistir); opção (b) sob host quieto, rodar G5/G6 calib pra confirmar no-regression em score antes de shipar. Lever §6.2 marcada como "IN FLIGHT" pendendo prévia.
