# Architecture — Fraud Detection API

> Engineering reference for the **as‑built** system (Wave 1). This document describes what the code *does today*, why it is shaped this way, and where the deliberate simplifications are. The forward‑looking plan lives in [`RINHA_PLAN.md`](RINHA_PLAN.md) (PT‑BR); this document is the canonical description of the current implementation.

## Contents

1. [Scope & conventions](#1-scope--conventions)
2. [The 14‑dimensional feature vector](#2-the-14-dimensional-feature-vector)
3. [Request lifecycle](#3-request-lifecycle)
4. [Component reference](#4-component-reference)
5. [The zero‑allocation hot path](#5-the-zero-allocation-hot-path)
6. [Performance budget](#6-performance-budget)
7. [Key design decisions & trade‑offs](#7-key-design-decisions--trade-offs)
8. [Wave 1 limitations and how the roadmap addresses them](#8-wave-1-limitations-and-how-the-roadmap-addresses-them)
9. [Validation methodology](#9-validation-methodology)
10. [References](#10-references)

---

## 1. Scope & conventions

The service exposes two endpoints on a single port (default `9999`):

- `GET /ready` — returns `200 OK` once the process is up (the reference dataset is loaded *before* the listener starts, so a successful bind already implies readiness in Wave 1).
- `POST /fraud-score` — accepts a transaction JSON and returns `{"approved":<bool>,"fraud_score":<float>}`.

**Naming convention.** Two classes are named for their *target* design rather than their Wave 1 implementation:

| Class | Name implies | Wave 1 actually does | Becomes the name in |
| --- | --- | --- | --- |
| `dataset.MmapDataset` | memory‑mapped binary dataset | streams `references.json.gz` into a heap `float[][]` | Wave 2 |
| `knn.HnswIndex` | HNSW graph index | brute‑force linear scan, top‑5 by insertion | Wave 3 |

This is intentional: the **public method signatures are stable** (`MmapDataset.load`, `HnswIndex.search(ConnectionState)`), so callers never change while the internals are upgraded wave by wave. Every place this matters is called out explicitly below.

There are **no runtime dependencies** — only the JDK. There is **one thread**. Nothing on the request path allocates (see §5).

## 2. The 14‑dimensional feature vector

Both the dataset vectors and the per‑request query vector live in the same 14‑D space. `json.FraudRequestParser` derives the query vector from the POST body using these formulas and constants (all hard‑coded as `static final`; the normalization/MCC tables are *not* read from disk at runtime in Wave 1).

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
    participant Hx as HnswIndex
    participant Ds as MmapDataset
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
        Ct->>Hx: search(state)
        Hx->>Ds: scan 3M reference vectors
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
5. **Score.** `FraudController` calls `FraudRequestParser.parse` to fill `queryVector[14]`. On success it calls `HnswIndex.search`, which scans the dataset, keeps the 5 nearest, and writes `fraudCount` (0–5) into the state. On parse failure it sets `fraudCount = 0` (fail‑open — see §7).
6. **Respond.** `HttpResponseWriter.writeFraudScore` copies one of six pre‑built responses into the `writeBuffer`; the controller flips the key to `OP_WRITE`.
7. **Write & keep‑alive.** `NioServer.write` drains the `writeBuffer`. Once fully written, `ConnectionState.reset()` rewinds the buffers and parser indices (no reallocation) and the key returns to `OP_READ`. The connection is **never closed on success** — it is reused.

## 4. Component reference

For each unit: **responsibility**, **interface**, **dependencies**, **rationale**.

### `server.NioServer` (134 LOC)

- **Responsibility:** own the `Selector`, the listening socket and the reactor loop; route accept/read/write events; dispatch to controllers.
- **Interface:** `new NioServer(port).start()` (blocks forever in the reactor loop).
- **Depends on:** `ConnectionState`, `HttpParser`, the two controllers.
- **Rationale:** a single‑threaded reactor removes all locking, context‑switching and memory‑visibility concerns — the right model for a CPU‑bound, 1‑core budget. The selected‑keys iterator is explicitly `remove()`d each pass (a classic NIO pitfall: forgetting this spins the CPU at 100 %). `accept` null‑checks the channel to survive spurious wake‑ups.

### `server.ConnectionState` (62 LOC)

- **Responsibility:** hold everything one connection needs, for its whole lifetime, with zero per‑request allocation.
- **Interface:** public fields (intentional — this is a data‑oriented hot‑path struct, not an encapsulated object) plus `reset()`.
- **Holds:** one direct `readBuffer` (4096 B) and one direct `writeBuffer` (512 B); resumable parser fields (`parserState`, `parserPosition`, `methodCode`, `pathStart/End`, `contentLength`, `bodyOffset`, `headerNameStart/End`); the reusable `queryVector[14]`, `knnDist[5]`, `knnFraud[5]`, and `fraudCount`.
- **Rationale:** buffers are `allocateDirect` (off‑heap, not moved by GC, zero‑copy to the socket) and allocated **once per connection**, never per request. `reset()` rewinds indices for keep‑alive instead of allocating new state. The 4096‑byte read buffer comfortably fits the Rinha payload (≈0.5 KB); it is a deliberate fixed ceiling, not a growable buffer.

### `server.HttpParser` (142 LOC)

- **Responsibility:** turn raw request bytes into the few facts the router needs — method, path range, `Content-Length`, body offset.
- **Interface:** `static int parse(ConnectionState) → {PARSE_INCOMPLETE|PARSE_DONE|PARSE_ERROR}`.
- **Rationale:** an 8‑state machine driven one byte at a time. It is **resumable**: progress is stored in `ConnectionState`, so a request split across multiple TCP reads resumes exactly where it stopped. Method matching is byte comparison (`GET`/`POST`); header‑name matching is case‑insensitive via `b | 0x20`; `Content-Length` is parsed by hand (`parseDecimal`, no `Integer.parseInt`). Only the headers the router needs are interpreted. **Known limitations (by design for Wave 1):** no chunked transfer‑encoding, no request pipelining, request+headers+body must fit the 4096‑byte read buffer.

### `server.HttpResponseWriter` (52 LOC)

- **Responsibility:** provide ready‑to‑send response bytes.
- **Interface:** `static writeReady(ConnectionState)`, `static writeFraudScore(ConnectionState, int fraudCount)`.
- **Rationale:** every possible response is pre‑computed **once** in a `static {}` block. There are exactly **six** `/fraud-score` bodies — `fraud_score` can only be `k/5` for `k ∈ 0..5`, i.e. `{0.0, 0.2, 0.4, 0.6, 0.8, 1.0}`. `approved` is derived (`score < 0.6` ⇔ `fraudCount < 3`). `Content-Length` is computed from the body length at class‑init (the `"false"` body is one byte longer than `"true"`). At request time the writer only does a buffer copy + flip — no formatting, no allocation.

### `json.FraudRequestParser` (272 LOC)

- **Responsibility:** project the POST body directly into `ConnectionState.queryVector[14]`.
- **Interface:** `static int parse(ConnectionState) → {PARSE_OK|PARSE_BAD}`.
- **Rationale:** a *schema‑specific* walker, not a general JSON parser. It locates the four first‑level objects (`transaction`, `customer`, `merchant`, `terminal`) by exact quoted‑key match plus brace matching, then reads each leaf value *within that object's byte range* — this avoids key collisions (`amount` vs `avg_amount` vs `max_amount`). Numbers are parsed by hand into `double` (sign, integer part, fraction; no exponent); booleans by first byte; `known_merchants` membership by scanning the array's quoted tokens. No `String`, no `Map`, no regex, no intermediate objects — the body bytes go straight to floats. `last_transaction: null` yields the `-1` sentinels for indices 5 and 6.

### `dataset.MmapDataset` (118 LOC)

- **Responsibility:** load the ≈3,000,000 reference vectors and their fraud labels into memory.
- **Interface:** `static load(String gzPath)`; results in `static float[][] vectors`, `static boolean[] isFraud`, `static int count`.
- **Wave 1 reality:** **not memory‑mapped.** It streams the file through a `GZIPInputStream` (with gzip‑magic auto‑detection `0x1F 0x8B`, falling back to plain input) and parses floats **byte‑by‑byte by hand** — no `readLine`, no `Float.parseFloat`, no `String`. The 284 MB JSON array is one single line; a `String`‑based read would OOM, and hand parsing keeps the 3M‑vector load allocation‑light. Storage is a growable `float[][]` (starts at 2²⁰, doubles) plus a parallel `boolean[]`.
- **Rationale & cost:** correctness‑first and dependency‑free. The cost is heap residency (3M × `float[14]` plus sub‑array headers) which is why the server needs `-Xmx768m`. Wave 2 replaces this with a pre‑quantized `int8` binary file consumed via `MappedByteBuffer`, behind the same `load`/field interface.

### `knn.DistanceFunctions` (14 LOC)

- **Responsibility:** the distance metric.
- **Interface:** `static float sqDist(float[] a, float[] b)`.
- **Rationale:** **squared** Euclidean over the 14 dimensions, scalar loop, no `sqrt`. Squaring is monotonic, so neighbour ranking is identical to true Euclidean while saving the `sqrt`. The `-1` sentinels participate normally — query and reference share the convention, so the contribution is consistent. **No SIMD yet** (Wave 2 vectorizes this with the Vector API; the module is already on the path).

### `knn.HnswIndex` (34 LOC)

- **Responsibility:** find the 5 nearest reference vectors to the query and count how many are fraudulent.
- **Interface:** `static void search(ConnectionState)` — reads `state.queryVector`, writes `state.knnDist`, `state.knnFraud`, `state.fraudCount`.
- **Wave 1 reality:** **brute force.** A single linear scan over all `count` vectors, maintaining a size‑5 top list by insertion (`bd[4]` is the current worst; better candidates shift in). Then it counts fraudulent entries among the 5 → `fraudCount`.
- **Rationale & cost:** O(n) per request (≈3M distance evaluations × 14 multiply‑adds) — the exact, unambiguous baseline that later waves are measured against. It is the dominant latency cost in Wave 1 and the reason Waves 2–3 exist. Wave 3 replaces the body with a real HNSW graph traversal behind the same `search(ConnectionState)` signature.

### `controllers.FraudController` (22 LOC)

- **Responsibility:** orchestrate parse → search → respond for `POST /fraud-score`.
- **Interface:** `static handle(ConnectionState, SelectionKey)`.
- **Behaviour:** `FraudRequestParser.parse`; on `PARSE_BAD` set `fraudCount = 0` (**fail‑open**), otherwise `HnswIndex.search`; then `HttpResponseWriter.writeFraudScore` and flip the key to `OP_WRITE`. See §7 for the fail‑open trade‑off.

### `controllers.HealthController` (14 LOC)

- **Responsibility:** answer `GET /ready`.
- **Interface:** `static handle(ConnectionState, SelectionKey)` → `writeReady` + `OP_WRITE`.

### `Main` (16 LOC)

- **Responsibility:** process entry point. Load the dataset (timing it and printing the count), then start the reactor on the requested port (default `9999`).
- **Rationale:** the dataset is loaded *before* the listener binds, so by the time `/ready` can answer, the service is genuinely ready. Readiness *gating* (loading concurrently and flipping a ready flag) is a Wave 4 concern.

## 5. The zero‑allocation hot path

Once a connection is accepted, serving a request allocates **nothing** on the heap:

- I/O buffers are `ByteBuffer.allocateDirect`, created once per connection, reused across requests via `reset()`.
- The HTTP parser works over buffer indices; it never materializes a `String`.
- The JSON parser writes straight into the pre‑allocated `queryVector[14]`; no nodes, no maps.
- k‑NN uses the pre‑allocated `knnDist[5]` / `knnFraud[5]` scratch arrays on the state.
- Responses are pre‑built `byte[]`; the writer only copies and flips.

The only large allocation is the **one‑time** dataset load at startup. This matters because the scoring function punishes tail latency, and GC pauses are tail latency. A request path that never allocates cannot trigger a young‑GC mid‑request.

## 6. Performance budget

The target (from [`RINHA_PLAN.md`](RINHA_PLAN.md)) is **p99 < 1 ms**, i.e. ≈2.6 M CPU cycles at 2.6 GHz on the reference Mac Mini. The intended steady‑state breakdown — a **roadmap target, not a Wave 1 measurement** — is roughly:

| Stage | Target |
| --- | --- |
| HTTP parse | ≈ 80 µs |
| JSON → vector | ≈ 60 µs |
| Quantize (Wave 2) | ≈ 5 µs |
| k‑NN search (HNSW, ef≈50, Wave 3) | ≈ 230 µs |
| Score | ≈ 5 µs |
| Response + write | ≈ 60 µs |
| **Total (ideal)** | **≈ 440 µs**, leaving headroom for tail |

**Wave 1 vs. this budget.** Wave 1's k‑NN is a full O(≈3 M) scan per request, which is *orders of magnitude* over the 230 µs search line — Wave 1 is intentionally **not** on budget. It exists to lock down correctness. The gap is closed by:

- **Wave 2** — `int8` quantization shrinks each vector ~4× (cache‑friendly) and the Vector API does the distance in SIMD lanes.
- **Wave 3** — HNSW turns the O(n) scan into an O(log n)‑ish graph walk visiting ~thousands of vectors instead of millions.
- **Wave 5** — GraalVM Native Image + PGO removes JIT warm‑up and trims the constant factors.

## 7. Key design decisions & trade‑offs

| Decision | Why | Cost / risk accepted |
| --- | --- | --- |
| Single‑threaded NIO reactor | 1‑CPU budget; no locks, no context switches, deterministic | Cannot use multiple cores; one slow request head‑of‑lines the loop (acceptable while the path is bounded and allocation‑free) |
| Zero runtime dependencies, everything by hand | Smallest footprint, full control of the hot path, no framework overhead or reflection | More code to own and test; no library safety net |
| Direct buffers, allocation‑free path | GC pauses are tail latency; the score punishes tail latency | Manual buffer/index bookkeeping; fixed 4096‑byte request ceiling |
| Pre‑computed canned responses (6) | `fraud_score` has only 6 possible values; formatting at request time is wasted work | Logic (`approved`, `Content-Length`) is fixed at class‑init and must stay in sync with the rule `score < 0.6` |
| Squared Euclidean (no `sqrt`) | Monotonic ⇒ identical ranking, cheaper | Distances are not true magnitudes (irrelevant for k‑NN ranking) |
| Schema‑specific JSON walker | Avoids object graph + key‑collision (`amount`/`avg_amount`); fastest possible | Brittle to schema changes; not reusable for other payloads |
| Hard‑coded normalization/MCC tables | No file I/O or parsing on the hot path in Wave 1 | Constants are duplicated from the spec; must be updated if the spec changes |
| Integer‑only date math (Hinnant) | Avoids `java.time` allocation/parsing | Hand‑verified algorithm; less obvious than `LocalDateTime` |
| **Fail‑open on unparseable body** (`fraudCount = 0` ⇒ `approved:true`) | The scoring function penalizes an HTTP error (weight 5) more than a missed fraud (weight 3); returning a well‑formed "approved" avoids the larger penalty | A malformed/edge payload is silently approved (a potential false negative). This is a scoring‑driven choice, not a security stance, and is revisited if real inputs prove it wrong |
| `MmapDataset` heap loader / `HnswIndex` brute force in Wave 1 | Ship a correct, measurable baseline before optimizing | Names temporarily over‑promise; mitigated by the explicit convention in §1 and a stable interface |

## 8. Wave 1 limitations and how the roadmap addresses them

| Limitation today | Impact | Addressed by |
| --- | --- | --- |
| Brute‑force O(n) k‑NN | Latency far above the p99 target | Wave 3 (HNSW) — and Wave 2 (SIMD) for the constant factor |
| `float[][]` dataset on heap, needs `-Xmx768m` | High memory residency vs. the 350 MB infra budget | Wave 2 (`int8` mmap binary ⇒ ~4× smaller, off‑heap, shareable between instances) |
| Scalar distance | Leaves CPU SIMD lanes idle | Wave 2 (Vector API) |
| No containerization / load balancer / 2 instances | Not yet runnable under the official harness | Wave 4 (HotSpot container + HAProxy + k6 baseline) |
| JIT warm‑up, no PGO | Cold‑start and constant‑factor overhead | Wave 5 (GraalVM Native Image + PGO) |
| No chunked encoding, no pipelining, 4 KB request cap | Fine for the Rinha payload; not general‑purpose | Out of scope by design (the spec's payload is small and fixed) |
| Readiness is implied by a successful bind | No graceful "loading" window | Wave 4 (proper readiness gating) |

None of these are accidental — each is a Wave 1 simplification with a planned successor.

## 9. Validation methodology

Wave 1's exit criterion is **byte‑identical** agreement with the official oracles in the Rinha specification (`rinha-de-backend-2026/docs/br/REGRAS_DE_DETECCAO.md`), exercised against a real server with the full 3M dataset:

| Oracle | Transaction | Expected response |
| --- | --- | --- |
| Legitimate | `tx-1329056812` | `{"approved":true,"fraud_score":0.0}` |
| Fraud | `tx-3330991687` | `{"approved":false,"fraud_score":1.0}` |

Reproduce:

```bash
cd api
./mvnw clean package -DskipTests
# dataset at src/main/resources/references.json.gz
java -Xmx768m --add-modules jdk.incubator.vector -jar target/api.jar 9999
# in another shell — readiness, then the legitimate oracle (full payload in README "Try it"):
curl -s http://localhost:9999/ready -o /dev/null -w '%{http_code}\n'        # => 200
curl -s -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.2331036248},"last_transaction":null}'
# => {"approved":true,"fraud_score":0.0}
```

The build‑tutorials ([`TUTORIAL_SERVER_NIO.md`](TUTORIAL_SERVER_NIO.md), [`TUTORIAL_JSON_KNN.md`](TUTORIAL_JSON_KNN.md)) define intermediate test points (per‑component checks) that gate each stage before the end‑to‑end oracle test:

- TP1 — `ConnectionState` buffer/array sizes (14 / 5 / 5)
- TP2 — `MmapDataset` loads 100 (sanity) then 3M vectors
- TP3 — `DistanceFunctions.sqDist` known values
- TP4 — `FraudRequestParser` produces the oracle's 14‑D vector
- TP5 — `HnswIndex` finds the exact match (distance 0.0) for a dataset vector
- TP6 — the six canned responses compile and serialize correctly
- §10 — the two end‑to‑end oracles above

A 100‑entry `example-references.json` (the first 100 rows of the full dataset, same `{"vector":[14 floats],"label":"legit|fraud"}` shape) is committed for fast sanity checks without the 3M file.

## 10. References

- [`README.md`](../README.md) — project overview, quickstart, status
- [`RINHA_PLAN.md`](RINHA_PLAN.md) — the 5‑wave plan and locked stack (PT‑BR)
- [`CONCEITOS.md`](CONCEITOS.md) — concepts: NIO, HNSW, quantization, native image (PT‑BR)
- [`IMPACTO.md`](IMPACTO.md) — score‑impact analysis of each decision (PT‑BR)
- [`TUTORIAL_SERVER_NIO.md`](TUTORIAL_SERVER_NIO.md) / [`TUTORIAL_JSON_KNN.md`](TUTORIAL_JSON_KNN.md) — hands‑on build tutorials (PT‑BR)
- [`tecnologias/`](tecnologias/) — technology reference notes (PT‑BR)
- Rinha de Backend 2026 specification: <https://github.com/zanfranceschi/rinha-de-backend-2026>
- HNSW (Wave 3 reference): Malkov & Yashunin, *Efficient and robust approximate nearest neighbor search using HNSW graphs*, <https://arxiv.org/abs/1603.09320>

---

*This document describes the system as built at the close of Wave 1. When a wave changes the implementation, update the affected component sections and the §1 naming table in the same change.*
