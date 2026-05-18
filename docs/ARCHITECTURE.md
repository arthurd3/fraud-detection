# Architecture — Fraud Detection API

> Engineering reference for the **as‑built** system, current at the close of **Wave 4a** (fit‑in‑350 MB / RBH2). This document describes what the code *does today*, why it is shaped this way, and where the deliberate simplifications are. The forward‑looking plan lives in [`RINHA_PLAN.md`](RINHA_PLAN.md) (PT‑BR); this document is the canonical description of the current implementation. When a wave changes the implementation, update the affected component sections and the §1 naming table in the same change.

## Contents

1. [Scope & conventions](#1-scope--conventions)
2. [The 14‑dimensional feature vector](#2-the-14-dimensional-feature-vector)
3. [Request lifecycle](#3-request-lifecycle)
4. [Component reference](#4-component-reference)
5. [The zero‑allocation hot path](#5-the-zero-allocation-hot-path)
6. [Performance budget](#6-performance-budget)
7. [Key design decisions & trade‑offs](#7-key-design-decisions--trade-offs)
8. [Roadmap status: what each wave delivered](#8-roadmap-status-what-each-wave-delivered)
9. [Validation methodology](#9-validation-methodology)
10. [References](#10-references)

---

## 1. Scope & conventions

The service exposes two endpoints on a single port (default `9999`):

- `GET /ready` — returns `200 OK` once the process is up (the reference dataset **and** the HNSW graph are mapped *before* the listener starts, so a successful bind already implies readiness).
- `POST /fraud-score` — accepts a transaction JSON and returns `{"approved":<bool>,"fraud_score":<float>}`.

**Naming convention.** Two classes were named for their *target* design from Wave 1. As of Wave 3 **the names now match the implementation**:

| Class | Name implies | As‑built today | Realized in |
| --- | --- | --- | --- |
| `dataset.MmapDataset` | memory‑mapped binary dataset | `MappedByteBuffer` over a pre‑quantized `int8` RB2 file, off‑heap | Wave 2a |
| `knn.HnswIndex` | HNSW graph index | hand‑rolled HNSW (Malkov‑Yashunin) over a flat CSR graph mmap | Wave 3 |

The **public method signatures stayed stable** across all waves (`MmapDataset.load`, `HnswIndex.search(ConnectionState)`), so callers never changed while the internals were upgraded wave by wave.

There are **no runtime dependencies** — only the JDK (the `jdk.incubator.vector` module is on the path but the production distance is scalar; see §4 `DistanceFunctions`). There is **one thread**. The request path is allocation‑free except for two small per‑query scratch arrays in the HNSW result drain (see §5).

## 2. The 14‑dimensional feature vector

Both the dataset vectors and the per‑request query vector live in the same 14‑D space. `json.FraudRequestParser` derives the query vector from the POST body using these formulas and constants (all hard‑coded as `static final`; the normalization/MCC tables are *not* read from disk at runtime).

| Idx | Feature | Formula | Notes |
| --- | --- | --- | --- |
| 0 | amount | `amount / 10000` | clamped to [0, 1] |
| 1 | installments | `installments / 12` | clamped |
| 2 | amount vs customer avg | `(amount / customer.avg_amount) / 10` | clamped |
| 3 | hour of day | `hour(requested_at) / 23` | UTC, from ISO‑8601 |
| 4 | day of week | `dow(requested_at) / 6` | Mon = 0 … Sun = 6 |
| 5 | minutes since last tx | `minutes / 1440` | **`-1` if `last_transaction` is `null`** |
| 6 | km from last tx | `last_transaction.km_from_current / 1000` | **`-1` if `last_transaction` is `null`** |
| 7 | km from home | `terminal.km_from_home / 1000` | clamped |
| 8 | tx count 24h | `customer.tx_count_24h / 20` | clamped |
| 9 | is online | `1` if `terminal.is_online` else `0` | |
| 10 | card present | `1` if `terminal.card_present` else `0` | |
| 11 | unknown merchant | `0` if `merchant.id ∈ known_merchants` else `1` | **inverted** |
| 12 | MCC risk | `mcc_risk[merchant.mcc]` | default `0.5` |
| 13 | merchant avg amount | `merchant.avg_amount / 10000` | clamped |

**Normalization constants:** `MAX_AMOUNT=10000`, `MAX_INSTALLMENTS=12`, `AMOUNT_VS_AVG_RATIO=10`, `MAX_MINUTES=1440`, `MAX_KM=1000`, `MAX_TX_24H=20`, `MAX_MERCHANT_AVG=10000`.

**MCC risk table** (default `0.5`): `5411→0.15`, `5812→0.30`, `5912→0.20`, `5944→0.45`, `7801→0.80`, `7802→0.75`, `7995→0.85`, `4511→0.35`, `5311→0.25`, `5999→0.50`.

**The `-1` sentinel.** When `last_transaction` is `null`, indices 5 and 6 are set to `-1` (not `0`, not omitted). The same convention is used in the reference dataset, so the distance function compares like with like — sentinels are *never* filtered or substituted.

**Quantization (Wave 2a).** Before the k‑NN search the 14 floats are quantized to `int8` by `knn.Quantizer`: `q = round(clamp(v, -1, 1) · 127)`, global symmetric scale, no per‑dimension offset. The query is quantized into a 16‑byte buffer (`queryQ`, 14 real + 2 zero pad) so it has the exact layout of an RB2 record; the distance is then a pure integer operation.

**Date handling.** `requested_at` / `timestamp` are ISO‑8601 (`YYYY-MM-DDTHH:MM:SSZ`). The parser converts them with **integer arithmetic only** — no `java.time`:

- Civil‑days since 1970‑01‑01 via Howard Hinnant's `civil_to_days` algorithm.
- Day of week: `Math.floorMod(days + 3, 7)` (1970‑01‑01 was a Thursday) remapped to Mon = 0 … Sun = 6.
- Epoch seconds: `days·86400 + hh·3600 + mm·60 + ss`; "minutes since last tx" is `(reqEpoch − lastEpoch)/60`.

This keeps date math allocation‑free and free of the `java.time` parsing machinery.

## 3. Request lifecycle

```mermaid
sequenceDiagram
    autonumber
    participant Cl as Client
    participant N as NioServer (reactor)
    participant St as ConnectionState
    participant Pa as HttpParser
    participant Ct as FraudController
    participant Fp as FraudRequestParser
    participant Qz as Quantizer
    participant Hx as HnswIndex
    participant Hg as HnswGraph (mmap)
    participant Ds as MmapDataset (mmap)
    participant Wr as HttpResponseWriter

    Cl->>N: TCP connect
    N->>St: new ConnectionState (direct buffers)
    N-->>Cl: register OP_READ
    Cl->>N: POST /fraud-score bytes
    N->>Pa: parse(state)
    Pa-->>N: PARSE_DONE / INCOMPLETE / ERROR
    Note over N,Pa: INCOMPLETE keeps the connection; ERROR closes it
    N->>Ct: dispatch (method + path match)
    Ct->>Fp: parse body to queryVector 14-D
    alt parse OK
        Ct->>Qz: quantize queryVector -> queryQ[16] int8
        Ct->>Hx: search(state)
        Hx->>Hg: greedy descend maxLevel..1, ef-search on L0
        Hx->>Ds: int8 distance to ~hundreds of records
        Hx-->>Ct: fraudCount in 0..5
    else parse failed
        Note over Ct: fraudCount = 0 (fail-open)
    end
    Ct->>Wr: writeFraudScore(state, fraudCount)
    Ct-->>N: interestOps = OP_WRITE
    N->>Cl: canned HTTP response
    N->>St: reset() then back to OP_READ (keep-alive)
```

Step by step:

1. **Accept.** `NioServer.accept` accepts the channel (guarding against spurious selector wake‑ups), sets it non‑blocking, attaches a fresh `ConnectionState`, and registers it for `OP_READ`.
2. **Read.** `NioServer.read` reads available bytes into the connection's direct `readBuffer`. `-1` ⇒ peer closed ⇒ cancel + close. `0` ⇒ nothing yet ⇒ return.
3. **Parse.** `HttpParser.parse` advances a resumable byte‑level state machine. `PARSE_INCOMPLETE` returns and waits for more bytes (TCP fragmentation is handled by persisting parser position/state in `ConnectionState`); `PARSE_ERROR` closes the connection; `PARSE_DONE` falls through to dispatch.
4. **Dispatch.** `NioServer.dispatch` matches the method code and the path bytes (`/ready`, `/fraud-score`) directly against pre‑declared `byte[]` constants — no `String`. `GET /ready` → `HealthController`; `POST /fraud-score` → `FraudController`; anything else → cancel + close.
5. **Score.** `FraudController` calls `FraudRequestParser.parse` to fill `queryVector[14]`. On success it calls `Quantizer.quantize` (→ `queryQ[16]` int8) then `HnswIndex.search`, which walks the HNSW graph (greedy descent through the upper layers, then an `ef_search` local search on layer 0), keeps the 5 nearest, and writes `fraudCount` (0–5) into the state. On parse failure it sets `fraudCount = 0` (fail‑open — see §7).
6. **Respond.** `HttpResponseWriter.writeFraudScore` copies one of six pre‑built responses into the `writeBuffer`; the controller flips the key to `OP_WRITE`.
7. **Write & keep‑alive.** `NioServer.write` drains the `writeBuffer`. Once fully written, `ConnectionState.reset()` rewinds the buffers and parser indices (no reallocation) and the key returns to `OP_READ`. The connection is **never closed on success** — it is reused.

## 4. Component reference

For each unit: **responsibility**, **interface**, **rationale**.

### `server.NioServer` (134 LOC)

- **Responsibility:** own the `Selector`, the listening socket and the reactor loop; route accept/read/write events; dispatch to controllers.
- **Interface:** `new NioServer(port).start()` (blocks forever in the reactor loop).
- **Rationale:** a single‑threaded reactor removes all locking, context‑switching and memory‑visibility concerns — the right model for a CPU‑bound, 1‑core budget. The selected‑keys iterator is explicitly `remove()`d each pass (a classic NIO pitfall: forgetting this spins the CPU at 100 %). `accept` null‑checks the channel to survive spurious wake‑ups.

### `server.ConnectionState` (68 LOC)

- **Responsibility:** hold everything one connection needs, for its whole lifetime, with (near‑)zero per‑request allocation.
- **Interface:** public fields (intentional — a data‑oriented hot‑path struct) plus `reset()`.
- **Holds:** one direct `readBuffer` (4096 B) and one direct `writeBuffer` (512 B); resumable parser fields; the reusable `queryVector[14]`; the Wave‑2 `queryQ[16]` and `vScratch[16]` int8 buffers (14 real + 2 zero pad); the Wave‑3 `knn5` int[5] of top‑5 ids (**lazy**, allocated once on first request, **not** cleared by `reset()`); `knnDist[5]`, `knnFraud[5]`, `fraudCount`.
- **Rationale:** buffers are `allocateDirect` (off‑heap, not moved by GC, zero‑copy to the socket), allocated **once per connection**. `reset()` rewinds indices for keep‑alive instead of allocating; it deliberately does **not** touch `queryQ`/`vScratch`/`knn5` (they are fully overwritten each request, so clearing would be wasted work).

### `server.HttpParser` (142 LOC)

- **Responsibility:** turn raw request bytes into the few facts the router needs — method, path range, `Content-Length`, body offset.
- **Interface:** `static int parse(ConnectionState) → {PARSE_INCOMPLETE|PARSE_DONE|PARSE_ERROR}`.
- **Rationale:** an 8‑state machine driven one byte at a time. It is **resumable**: progress is stored in `ConnectionState`, so a request split across multiple TCP reads resumes exactly where it stopped. Method matching is byte comparison; header‑name matching is case‑insensitive via `b | 0x20`; `Content-Length` is parsed by hand. **Known limitations (by design):** no chunked transfer‑encoding, no request pipelining, request+headers+body must fit the 4096‑byte read buffer.

### `server.HttpResponseWriter` (52 LOC)

- **Responsibility:** provide ready‑to‑send response bytes.
- **Interface:** `static writeReady(ConnectionState)`, `static writeFraudScore(ConnectionState, int fraudCount)`.
- **Rationale:** every possible response is pre‑computed **once** in a `static {}` block. There are exactly **six** `/fraud-score` bodies — `fraud_score` can only be `k/5` for `k ∈ 0..5`. `approved` is derived (`score < 0.6` ⇔ `fraudCount < 3`). `Content-Length` is computed at class‑init. At request time the writer only does a buffer copy + flip.

### `json.FraudRequestParser` (272 LOC)

- **Responsibility:** project the POST body directly into `ConnectionState.queryVector[14]`.
- **Interface:** `static int parse(ConnectionState) → {PARSE_OK|PARSE_BAD}`.
- **Rationale:** a *schema‑specific* walker, not a general JSON parser. It locates the four first‑level objects (`transaction`, `customer`, `merchant`, `terminal`) by exact quoted‑key match plus brace matching, then reads each leaf value *within that object's byte range* — this avoids key collisions (`amount` vs `avg_amount` vs `max_amount`). Numbers parsed by hand into `double`; booleans by first byte; `known_merchants` membership by scanning quoted tokens. `last_transaction: null` yields the `-1` sentinels for indices 5 and 6.

### `knn.Quantizer` (16 LOC)

- **Responsibility:** map a 14‑D `float` vector to `int8`.
- **Interface:** `static byte q(float)`, `static void quantize(float[] src, byte[] dst)` (writes `dst[0..13]`, leaving `dst[14..15]` as the zero pad).
- **Rationale:** global symmetric scale `round(clamp(v,−1,1)·127)`. `int8` is ~4× smaller than `float` (cache‑friendly) and the squared‑distance fits in `int32`. The pad‑zero invariant lets the same buffer be compared against RB2 records without masking.

### `dataset.MmapDataset` (148 LOC)

- **Responsibility:** make the ≈3,000,000 reference vectors and fraud labels available off‑heap.
- **Interface:** `static load(String gzPath, String binPath)`; then `static MappedByteBuffer data`, `static int count`, `static int recBase(int i)`, `static boolean fraud(int i)`.
- **As‑built:** the RB2 binary (`magic 'R','B','2',0` + `int32 count` + `int32 dims=14`, then `count × 16` int8 records of 14 real + 2 zero pad, then `count` label bytes) is `MappedByteBuffer`‑mapped read‑only. **Self‑bootstrapping:** if `references.bin` is missing or its magic/dims don't match, `load` streams `references.json.gz` once (gzip‑magic auto‑detect `0x1F 0x8B`, hand byte‑parser, no `String`/`Float.parseFloat`), quantizes, writes the RB2 file (`getFD().sync()`), then maps it. `references.bin` is gitignored/regenerable. STRIDE = 16 ⇒ `recBase(i) = 12 + i·16`.
- **Cost:** ≈51 MB on disk, off‑heap. The server runs in `-Xmx256m` with no OOM — proof the dataset is genuinely off‑heap (a `float[3M][14]` heap copy would be ≈220 MB and would not fit).

### `knn.DistanceFunctions` (40 LOC)

- **Responsibility:** the distance metric.
- **Interface:** `static float sqDist(float[],float[])`; `static int sqDistI8(byte[],byte[])` (SIMD); `static int sqDistI8Scalar(byte[],byte[])` (scalar, **production**).
- **Rationale:** **squared** Euclidean, no `sqrt` (monotonic ⇒ identical ranking). `sqDistI8Scalar` is a 16‑iteration integer loop over the padded record. `sqDistI8` is the Wave‑2b Vector API (`jdk.incubator.vector`) implementation; it is **bit‑identical** to the scalar one (Wave‑2b Gate A) but measured **≈3.8× slower** on HotSpot/AVX2 for this tiny fixed 14/16‑lane shape (`convertShape` B2I cross‑shape not well intrinsified). It is therefore kept **only** as a correctness reference; **all production and build distances use `sqDistI8Scalar`**. This matters most in the build, which evaluates billions of distances.

### `knn.HnswScratch` (66 LOC) — Wave 3

- **Responsibility:** per‑search working memory for the single‑threaded reactor.
- **Holds:** a **versioned visited** array (`int[count]` + a `gen` counter incremented per query — "seen" ⇔ `visited[n]==gen`, so there is **zero `memset` per request**), a candidate **min‑heap** and a result **max‑heap** (parallel `int[]` arrays, cap `1<<15`), and two 16‑byte record buffers for the distance.
- **Rationale:** the static reactor serves one request at a time, so one static scratch suffices. **Not** thread‑safe by design (forward note: a multi‑threaded wave would make it per‑thread). The versioned‑visited trick is what keeps the search off the GC and avoids an O(3M) clear per query.

### `knn.HnswBuilder` (≈230 LOC) — Wave 3, build‑time only

- **Responsibility:** build the navigable graph from the RB2 dataset and serialize it to `hnsw.bin`.
- **Interface:** `static void build(String binPath)`; `static int effectiveN()`.
- **As‑built:** Malkov‑Yashunin insertion. Layer‑0 adjacency is a dense `int[count·M0]` + `int[] deg0`; upper layers are sparse (`HashMap<Integer,int[]>`, block per layer). Level RNG is a fixed‑seed xorshift64 (`0x9E3779B97F4A7C15`) ⇒ **reproducible graph**. Neighbor selection uses the **Malkov‑Yashunin Algorithm 4 heuristic with `keepPrunedConnections` backfill** (`selectHeuristic`) — it keeps a candidate only if it is closer to the base than to any already‑selected neighbor, preserving long‑range edges and graph navigability (this replaced the originally‑specified "closest‑M simple" and is what gives recall@5 96.89 % at the default `ef_search=50`). Parameters are locked: `M=16`, `M0=32`, `ef_construction=200`, `mL=1/ln M`. `degOf` carries the guard `if (lc > level[node]) return 0;` — the flatten step iterates *every node × every layer*, and without the guard a node with `1 ≤ level < lc` (its `up` block is only `level·M` long) throws `ArrayIndexOutOfBounds`. `-Dhnsw.maxNodes=K` caps `effectiveN()` for cheap smoke builds (default = full count; zero production impact). Build is build‑time only — JDK collections are fair game; it is **not** the hot path.
- **Cost:** O(N·efC·log N) ⇒ minutes and a large heap on the **first boot only** (`-Xmx2g`). Steady state mmaps and runs in `-Xmx256m`. Offline pre‑building is **delivered in Wave 4a** (`tools.Prebuild` writes the binaries from the `.gz` on the dev box; the container only mmaps — it never builds).

### `knn.HnswGraph` (56 LOC) — Wave 3

- **Responsibility:** read‑only `MappedByteBuffer` view of `hnsw.bin`.
- **Interface:** `isValid(File, expectCount)`, `mmap(File)`, then `level(node)`, `nbrLo/nbrHi(node,k)`, `nbrAt(k,idx)`.
- **`hnsw.bin` format** (big‑endian, like RB2): a 28‑byte header `magic 'R','B','H','2' | int32 count | int32 M | int32 M0 | int32 efC | int32 entryPoint | int32 maxLevel`, then `count` `uint8` levels, then **L0 dense** (`int32 off0[count+1]` + **`int24 nbr0[]`**) and, per upper layer `k = 1..maxLevel`, a **sparse** block (`int32 Pk` + sorted `int24 node_k[Pk]` + `int32 off_k[Pk+1]` + `int24 nbr_k[]`). Neighbour ids are 3 bytes (< 2²⁴); upper‑layer lookup is a binary search on `node_k` (memoised, single‑thread). **Wave 4a** shrank this **losslessly** from ≈460 MB (RBH1, uniform `int32` CSR) to **≈300 MB** — `Rbh2Equiv` proved identical neighbour sets across all 3M nodes, and recall@5/approved are unchanged. Gitignored/regenerable; built offline by `tools.Prebuild`.

### `knn.HnswIndex` (≈120 LOC) — Wave 3 (rewritten)

- **Responsibility:** find the 5 nearest reference vectors to the query and count how many are fraudulent.
- **Interface:** `static void load(String hnswBin)`, `static void search(ConnectionState)`, `static int top5Hnsw(byte[],int[])`, `static int top5Brute(byte[],int[])`, `static int efSearch` (default 50).
- **As‑built:** `load` is **self‑bootstrapping** — if `hnsw.bin` is missing/incompatible (`HnswGraph.isValid` checks magic + count vs `effectiveN`), it calls `HnswBuilder.build` once, then mmaps. `search` quantization is done by the caller; it greedily descends `maxLevel → 1` with `ef=1`, then runs `searchLayer(ef=efSearch)` on layer 0 over the mmap'd graph, takes the top 5, and counts fraud labels → `fraudCount`. `top5Brute` is the **retained** exact O(n) int8 scan kept as the recall oracle (Gate 3). The `search(ConnectionState)` signature is unchanged from Wave 1.
- **Rationale & cost:** the graph walk visits ~hundreds of records instead of 3,000,000. Measured (Wave 3, HotSpot, `-Xmx256m`): **p50 ≈ 0.084 ms, p99 ≈ 0.145 ms** vs the brute scan's ≈36 ms — a ≈430× speedup while keeping recall@5 = 96.89 % and approved‑agreement = 99.90 %. This is the algorithmic change that puts p99 under the 1 ms target *before* Native Image.

### `controllers.FraudController` (24 LOC)

- **Responsibility:** orchestrate parse → quantize → search → respond for `POST /fraud-score`.
- **Interface:** `static handle(ConnectionState, SelectionKey)`.
- **Behaviour:** `FraudRequestParser.parse`; on `PARSE_BAD` set `fraudCount = 0` (**fail‑open**), otherwise `Quantizer.quantize(queryVector, queryQ)` then `HnswIndex.search`; then `HttpResponseWriter.writeFraudScore` and flip the key to `OP_WRITE`. See §7.

### `controllers.HealthController` (14 LOC)

- **Responsibility:** answer `GET /ready` → `writeReady` + `OP_WRITE`.

### `Main` (21 LOC)

- **Responsibility:** process entry point. `MmapDataset.load(gz, bin)` (timing/printing the count), then `HnswIndex.load(hnsw.bin)`, then start the reactor on the requested port (default `9999`).
- **Rationale:** the dataset and the graph are mapped *before* the listener binds, so by the time `/ready` can answer, the service is genuinely ready. The first boot additionally builds `hnsw.bin` (minutes, `-Xmx2g`); every later boot mmaps it instantly.

## 5. The zero‑allocation hot path

Once a connection is accepted, serving a request allocates **almost nothing** on the heap:

- I/O buffers are `ByteBuffer.allocateDirect`, created once per connection, reused via `reset()`.
- The HTTP parser works over buffer indices; it never materializes a `String`.
- The JSON parser writes straight into the pre‑allocated `queryVector[14]`; quantization into the pre‑allocated `queryQ[16]`; no nodes, no maps.
- The HNSW search reuses the **static** `HnswScratch` (versioned visited + heaps + record buffers) — no per‑request `memset`, no graph‑node objects (the graph is a flat mmap of `int`s).
- Responses are pre‑built `byte[]`; the writer only copies and flips.

**The one honest exception:** `HnswIndex.takeTop5` allocates two small `int[rSize]` arrays (`rSize ≤ ef`, ≈50–200 ints) per query to drain the result max‑heap into ascending order. This is a few hundred bytes of short‑lived garbage per request — orders of magnitude below a young‑gen threshold and far from the per‑request tail‑latency budget — but it is *not* literally zero‑alloc. Eliminating it (drain into a reused scratch on `HnswScratch`) is a candidate micro‑optimization for Wave 5; it was left as‑is in Wave 3 because Gate 4 already shows p99 ≈ 0.145 ms with it. The only other allocation is the **one‑time** dataset/graph load at startup.

## 6. Performance budget

The target (from [`RINHA_PLAN.md`](RINHA_PLAN.md)) is **p99 < 1 ms**, i.e. ≈2.6 M CPU cycles at 2.6 GHz on the reference Mac Mini. Measured end‑to‑end search latency at the close of Wave 3 (HotSpot, `-Xmx256m`, 3M dataset, `ef_search=50`):

| Path | p50 | p99 |
| --- | --- | --- |
| HNSW search (Wave 3, production) | ≈ 0.084 ms | ≈ 0.145 ms |
| Brute‑force int8 scan (Wave 2b, retained as oracle) | ≈ 36.1 ms | ≈ 43.8 ms |

The HNSW search is already **sub‑millisecond on HotSpot** — the search line of the budget is met before Native Image. The remaining waves harden the rest of the envelope rather than the search:

- **Wave 1** — correctness baseline (full O(3M) `float` scan).
- **Wave 2a/2b** — `int8` quantization + off‑heap mmap shrank each vector ~4×; SIMD was explored and **rejected for the hot path** (3.8× slower here — see §7).
- **Wave 3** — HNSW turned the O(n) scan into a graph walk visiting ~hundreds of vectors. **This is the latency lever.**
- **Wave 4a** — *done*: `hnsw.bin` RBH2 (int24 + sparse upper, lossless ≈300 MB), `api.jar` 41 KB (no bundled dataset), `DATA_PATH`, offline `tools.Prebuild`; **proven** 2 instances + shared reclaimable mmap peak **147 MiB** under a 350 MiB cgroup (`systemd-run` faithful proxy).
- **Wave 4b** — containerization (multi‑stage HotSpot image, RBH2 binaries baked), HAProxy `mode tcp` + 2 instances in `docker compose` (≤1 CPU / 350 MB), official k6 `final_score`, public image + `submission` branch (spec + tutorial ready; hand‑impl pending).
- **Wave 5** — GraalVM Native Image + PGO removes JIT warm‑up and trims constant factors; **must re‑validate** Wave‑2b Gate A and the Wave‑3 gates (silent Vector‑API→scalar regression risk).

## 7. Key design decisions & trade‑offs

| Decision | Why | Cost / risk accepted |
| --- | --- | --- |
| Single‑threaded NIO reactor | 1‑CPU budget; no locks, no context switches, deterministic | One slow request head‑of‑lines the loop (acceptable while the path is bounded) |
| Zero runtime dependencies, everything by hand | Smallest footprint, full control of the hot path | More code to own and test; no library safety net |
| Direct buffers, (near‑)allocation‑free path | GC pauses are tail latency; the score punishes tail latency | Manual buffer/index bookkeeping; fixed 4096‑byte request ceiling; one small heap drain in `takeTop5` (§5) |
| Pre‑computed canned responses (6) | `fraud_score` has only 6 possible values | `approved`/`Content-Length` fixed at class‑init; must stay in sync with `score < 0.6` |
| Squared Euclidean (no `sqrt`) | Monotonic ⇒ identical ranking, cheaper | Distances are not true magnitudes (irrelevant for k‑NN ranking) |
| `int8` global‑symmetric quantization (Wave 2a) | ~4× smaller, cache‑friendly, integer distance, off‑heap mmap | Tiny quantization error; sanity gate is now *approximate* vs the `float` baseline (≥99 %, not exact) |
| **Scalar distance, SIMD rejected for the hot path** (Wave 2b) | The Vector API impl is bit‑identical but ≈3.8× slower for this tiny fixed shape on HotSpot/AVX2; build does billions of distances | `sqDistI8` kept only as a correctness reference; revisit under Native Image (Wave 5) |
| **HNSW with the Alg.4 heuristic** (Wave 3) | The O(3M) scan was the latency wall; the heuristic preserves navigability ⇒ recall@5 96.89 % at the default `ef=50` | HNSW is *approximate* — guarded by Gate 3a (recall ≥95 %) and Gate 3b (approved‑agreement ≥99 %) vs the retained brute oracle |
| Self‑bootstrapping mmap files (RB2, `hnsw.bin`) | First boot derives the binaries; later boots map instantly; binaries gitignored/regenerable | First boot is minutes + `-Xmx2g`; offline pre‑build **delivered in Wave 4a** (`tools.Prebuild`) |
| Fixed‑seed level RNG | Reproducible graph ⇒ reproducible gates | The graph is one deterministic sample of the HNSW distribution |
| **Fail‑open on unparseable body** (`fraudCount = 0` ⇒ `approved:true`) | The score penalizes an HTTP error (weight 5) more than a missed fraud (weight 3) | A malformed payload is silently approved (a scoring‑driven choice, not a security stance) |
| Schema‑specific JSON walker / hard‑coded tables / integer date math | Avoids object graph, key collisions, file I/O and `java.time` on the hot path | Brittle to schema/spec changes; hand‑verified algorithms |

## 8. Roadmap status: what each wave delivered

| Concern | Status | Wave |
| --- | --- | --- |
| Correct end‑to‑end baseline vs the official oracles | **done** | 1 |
| `float[][]` heap residency (needed `-Xmx768m`) | **done** — `int8` RB2 off‑heap mmap, runs in `-Xmx256m` | 2a |
| Scalar distance leaving SIMD idle | **evaluated & closed** — SIMD measured slower here; scalar kept (see §7) | 2b |
| Brute‑force O(n) k‑NN latency | **done** — HNSW graph walk, p99 ≈ 0.145 ms (≈430× vs brute) | 3 |
| 350 MB budget — jar bundled the `.gz`; `hnsw.bin` ≈460 MB > 350 MB alone | **done (4a)** — RBH2 lossless ≈300 MB, jar 41 KB, offline prebuild, `DATA_PATH`; proven 147 MiB / 2 inst. under a 350 MiB cgroup | 4a |
| Containerization / HAProxy / 2 instances / official k6 / `submission` | spec + tutorial ready; hand‑impl + validation pending (needs Docker daemon) | 4b |
| JIT warm‑up, no PGO | open | 5 (GraalVM Native Image + PGO; re‑validate Gate A + Wave‑3/4a/4b gates) |
| No chunked encoding / pipelining / 4 KB request cap | out of scope by design (the Rinha payload is small and fixed) | — |
| Readiness implied by a successful bind | acceptable (dataset+graph mapped before bind); proper gating | 4 |

None of the open items are accidental — each is a planned successor.

## 9. Validation methodology

Each wave keeps the prior gates and adds its own. Wave 3 is accepted only when **all five gates** are green (commands run from `api/`, `--add-modules jdk.incubator.vector`, `-Xmx256m` steady state; the first boot needs `-Xmx2g` to build `hnsw.bin`):

| Gate | What it checks | Threshold | Wave‑3 result |
| --- | --- | --- | --- |
| 1 — e2e | real server, the two official oracles | exact | `tx-1329056812`→`{"approved":true,"fraud_score":0.0}`, `tx-3330991687`→`{"approved":false,"fraud_score":1.0}` ✓ |
| 2 — sanity | `Gate2Int8 2000` vs the frozen Wave‑1 `float` baseline | ≥ 99 % (now *approximate* — HNSW is not exact) | 1993/2000 = **99.65 %** ✓ |
| 3a — recall | `RecallHnsw` recall@5 vs the brute‑force int8 oracle | ≥ 95 % | **96.89 %** @ `ef_search=50` ✓ |
| 3b — decision | approved‑agreement vs the brute oracle | ≥ 99 % | **99.90 %** (FP=1 FN=1) ✓ |
| 4 — perf | `BenchHnsw` p50/p99 HNSW vs brute | measurement (no fixed threshold; p99<1 ms is Wave 5's goal) | HNSW p99 **0.145 ms** vs brute 43.8 ms, **≈430×** ✓ |

`ef_search` stays at the default **50** (both Gate 3a and 3b pass with margin — no tuning needed). The brute‑force int8 scan is **retained** in `HnswIndex.top5Brute` as the recall oracle and must not be deleted.

Reproduce:

```bash
cd api
./mvnw -q clean package
# first boot only — builds hnsw.bin (minutes):
java -Xmx2g  --add-modules jdk.incubator.vector -jar target/api.jar 9999
# steady state:
java -Xmx256m --add-modules jdk.incubator.vector -jar target/api.jar 9999
curl -s http://localhost:9999/ready -o /dev/null -w '%{http_code}\n'                       # => 200
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# => {"approved":true,"fraud_score":0.0}
# offline gates:
java -Xmx256m --add-modules jdk.incubator.vector -cp target/classes:target/test-classes org.fraudDetection.Gate2Int8  2000
java -Xmx256m --add-modules jdk.incubator.vector -cp target/classes:target/test-classes org.fraudDetection.RecallHnsw 2000 50
java -Xmx256m --add-modules jdk.incubator.vector -cp target/classes:target/test-classes org.fraudDetection.BenchHnsw  2000
```

The build tutorials ([`TUTORIAL_JSON_KNN.md`](TUTORIAL_JSON_KNN.md), [`TUTORIAL_INT8_QUANT.md`](TUTORIAL_INT8_QUANT.md), [`TUTORIAL_SIMD.md`](TUTORIAL_SIMD.md), [`TUTORIAL_HNSW.md`](TUTORIAL_HNSW.md)) define per‑component test points that gate each stage before the end‑to‑end oracles. The frozen `float` baseline (`docs/baselines/onda1-approved-2000.txt`) and a 100‑entry `example-references.json` are committed for fast checks without the 3M file. `references.bin` and `hnsw.bin` are gitignored and regenerated on first boot.

## 10. References

- [`README.md`](../README.md) — project overview, quickstart, status
- [`RINHA_PLAN.md`](RINHA_PLAN.md) — the 5‑wave plan and locked stack (PT‑BR)
- [`CONCEITOS.md`](CONCEITOS.md) — concepts: NIO, HNSW, quantization, native image (PT‑BR)
- [`IMPACTO.md`](IMPACTO.md) — score‑impact analysis of each decision (PT‑BR)
- [`TUTORIAL_JSON_KNN.md`](TUTORIAL_JSON_KNN.md) / [`TUTORIAL_INT8_QUANT.md`](TUTORIAL_INT8_QUANT.md) / [`TUTORIAL_SIMD.md`](TUTORIAL_SIMD.md) / [`TUTORIAL_HNSW.md`](TUTORIAL_HNSW.md) — hands‑on build tutorials (PT‑BR)
- [`tecnologias/`](tecnologias/) — technology reference notes (PT‑BR)
- Rinha de Backend 2026 specification: <https://github.com/zanfranceschi/rinha-de-backend-2026>
- HNSW: Malkov & Yashunin, *Efficient and robust approximate nearest neighbor search using Hierarchical Navigable Small World graphs*, <https://arxiv.org/abs/1603.09320>

---

*This document describes the system as built at the close of Wave 4a (fit‑in‑350 MB / RBH2). When a wave changes the implementation, update the affected component sections, the §1 naming table, and §8/§9 in the same change.*
