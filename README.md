# Fraud Detection API — Rinha de Backend 2026

> Real‑time card‑fraud scoring through vector similarity search, built **by hand** on bare Java 21 — zero application frameworks, zero runtime dependencies, a single‑threaded NIO reactor, and a hot path engineered for sub‑millisecond latency.

![Java](https://img.shields.io/badge/Java-21%20LTS-007396)
![Build](https://img.shields.io/badge/build-Maven%20(wrapper)-C71A36)
![Dependencies](https://img.shields.io/badge/runtime%20deps-0-success)
![Status](https://img.shields.io/badge/Waves%201--3-complete%20%E2%9C%94-success)

**Status:** Waves 1–3 complete — `int8` off‑heap mmap dataset + a hand‑rolled **HNSW** index. `POST /fraud-score` validated end‑to‑end against both official oracles; recall@5 96.89 % / approved‑agreement 99.90 % vs the brute oracle; HNSW p99 ≈ 0.145 ms (≈430× vs the brute scan). Waves 4–5 (containerization/350 MB budget, GraalVM native) are on the roadmap below.

---

## Table of contents

- [The challenge](#the-challenge)
- [Approach & philosophy](#approach--philosophy)
- [Architecture at a glance](#architecture-at-a-glance)
- [Quickstart](#quickstart)
- [Verified results](#verified-results)
- [Project status & roadmap](#project-status--roadmap)
- [Repository layout](#repository-layout)
- [Documentation](#documentation)
- [Tech stack](#tech-stack)
- [Author & license](#author--license)

---

## The challenge

[Rinha de Backend](https://github.com/zanfranceschi/rinha-de-backend-2026) is a Brazilian backend performance competition. The 2026 edition (4th) is a **fraud‑detection** problem solved with **vector search**:

For every incoming card transaction, the service must:

1. Project the transaction payload into a **14‑dimensional feature vector**.
2. Find its **5 nearest neighbours** in a reference dataset of **≈3,000,000 labelled vectors**.
3. Compute `fraud_score = (fraudulent neighbours) / 5`.
4. Answer `approved = fraud_score < 0.6`.

**API** (port `9999`):

| Method | Path           | Response                                            |
| ------ | -------------- | --------------------------------------------------- |
| `GET`  | `/ready`       | `2xx` once the dataset is loaded and the server is up |
| `POST` | `/fraud-score` | `{ "approved": <bool>, "fraud_score": <float> }`     |

**Infrastructure budget (the hard part):** the *entire* solution must run within **1 CPU and 350 MB of RAM**, with **≥2 API instances** behind a round‑robin load balancer. Scoring rewards both **detection quality** (false negatives cost more than false positives; HTTP errors cost the most) and **tail latency** — a p99 ≤ 1 ms hits the score ceiling, while every 10× latency regression loses points. The reference test environment is a Late‑2014 Mac Mini (2.6 GHz, 8 GB, Ubuntu 24.04, linux‑amd64).

These constraints are the reason for every design decision in this repository.

## Approach & philosophy

| Principle | What it means here |
| --- | --- |
| **By hand** | No web framework, no JSON library, no vector‑search library. The HTTP/1.1 parser, the JSON‑to‑vector parser, the dataset loader and the k‑NN search are all hand‑rolled. The only runtime dependency is the JDK. |
| **Performance‑first** | A single‑threaded NIO reactor, off‑heap direct buffers, an allocation‑free request path (no `String`, no boxing, no intermediate objects), and pre‑computed canned responses. |
| **Measure, then optimize** | Wave 1 shipped the *correct* baseline (brute‑force float32); every later wave is measured against it. Waves 2a/2b (int8 + off‑heap mmap; SIMD evaluated and **rejected for the hot path** — 3.8× slower for this shape) and Wave 3 (HNSW) are done; native image is Wave 5. |
| **Stable interfaces, evolving internals** | `MmapDataset` and `HnswIndex` were named for their *target* design; as of Wave 3 the names **match reality** (off‑heap `int8` mmap; hand‑rolled HNSW) and the public signatures never changed across waves. Documented in [ARCHITECTURE.md](docs/ARCHITECTURE.md). |

## Architecture at a glance

A single thread owns a `Selector` and drives every connection through a non‑blocking, resumable state machine. Nothing blocks; nothing is allocated on the hot path.

```mermaid
flowchart LR
    C[Client] -->|TCP| S{{NioServer<br/>single-thread reactor}}
    S -->|GET /ready| HC[HealthController]
    S -->|POST /fraud-score| FC[FraudController]
    HC --> W[HttpResponseWriter<br/>canned bytes]
    FC --> P[FraudRequestParser<br/>JSON bytes - 14-D vector]
    P --> K[HnswIndex<br/>top-5 nearest]
    K --> D[(MmapDataset<br/>3M reference vectors)]
    K --> W
    W -->|keep-alive| C
```

**Component map** (`api/src/main/java/org/fraudDetection/`):

| Package | Classes | Responsibility |
| --- | --- | --- |
| `server` | `NioServer`, `ConnectionState`, `HttpParser`, `HttpResponseWriter` | Non‑blocking I/O reactor, per‑connection state, resumable byte‑level HTTP/1.1 parsing, pre‑built responses |
| `json` | `FraudRequestParser` | Walks the POST body byte‑by‑byte and fills the 14‑D query vector — no `String`, no `Map`, no regex |
| `dataset` | `MmapDataset` | ≈3M reference vectors as an `int8` RB2 file, off‑heap `MappedByteBuffer` (self‑bootstrapping from the `.gz` on first boot) |
| `knn` | `Quantizer`, `DistanceFunctions`, `HnswScratch`, `HnswBuilder`, `HnswGraph`, `HnswIndex` | int8 quantization, squared‑Euclidean distance, and a hand‑rolled **HNSW** graph (flat CSR mmap) top‑5 search |
| `controllers` | `FraudController`, `HealthController` | Wire a parsed request through quantize → HNSW search → response writer |
| (root) | `Main` | Maps the dataset and the HNSW graph, then starts the reactor |

A deep, component‑by‑component description with rationale and the p99 cycle budget lives in **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Quickstart

### Prerequisites

- A JDK that can target Java 21 (Java 21 LTS recommended; Java 25 also works for the HotSpot build).
- The reference dataset `references.json.gz` (≈3M vectors). It is **not versioned** (see `api/.gitignore`); obtain it from the [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026) resources and place it at `api/src/main/resources/references.json.gz`. A 100‑entry `example-references.json` is committed for quick sanity checks.

### Build

```bash
cd api
./mvnw clean package -DskipTests
```

This produces `target/api.jar` (zero dependencies, main class `org.fraudDetection.Main`).

### Run

The dataset path is resolved relative to the working directory, so start the server from `api/`:

```bash
cd api
# first boot only — builds hnsw.bin (and references.bin if absent); minutes:
java -Xmx2g  --add-modules jdk.incubator.vector -jar target/api.jar 9999
# steady state — dataset + graph are mmapped:
java -Xmx256m --add-modules jdk.incubator.vector -jar target/api.jar 9999
```

- The dataset and HNSW graph are off‑heap `MappedByteBuffer`s, so steady state runs in **`-Xmx256m`** with no OOM. Only the **first boot** needs `-Xmx2g`, to build `hnsw.bin` (and `references.bin` if absent) once; both are gitignored/regenerable.
- `--add-modules jdk.incubator.vector` is the project convention (the SIMD distance is compiled in, but the production/build path is scalar — see [ARCHITECTURE.md §4](docs/ARCHITECTURE.md#4-component-reference)).
- The port argument is optional (defaults to `9999`). On startup the server prints `dataset int8 RB2 mmap: <n> ...`, `HNSW mmap: <n> nós ...`, `hnsw pronto`, then `api: Listening on port 9999`.

### Try it

```bash
# Liveness/readiness
curl -i http://localhost:9999/ready

# Score a transaction (this is the official "legitimate" oracle, tx-1329056812)
curl -s -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "tx-1329056812",
    "transaction":      { "amount": 41.12, "installments": 2, "requested_at": "2026-03-11T18:45:53Z" },
    "customer":         { "avg_amount": 82.24, "tx_count_24h": 3, "known_merchants": ["MERC-003","MERC-016"] },
    "merchant":         { "id": "MERC-016", "mcc": "5411", "avg_amount": 60.25 },
    "terminal":         { "is_online": false, "card_present": true, "km_from_home": 29.2331036248 },
    "last_transaction": null
  }'
# => {"approved":true,"fraud_score":0.0}
```

## Verified results

Validated at the close of Wave 3 on a real server with the full 3M dataset at `-Xmx256m` — the five gates (see [ARCHITECTURE.md §9](docs/ARCHITECTURE.md#9-validation-methodology)):

| Gate | Check | Result |
| --- | --- | --- |
| 1 | `GET /ready` + both official oracles, byte‑identical | ✅ `{"approved":true,"fraud_score":0.0}` / `{"approved":false,"fraud_score":1.0}` |
| 2 | sanity vs the frozen Wave‑1 `float` baseline (2000) | ✅ 99.65 % (≥99 %; now *approximate* — HNSW is not exact) |
| 3a | recall@5 vs the brute‑force int8 oracle (2000) | ✅ **96.89 %** (≥95 %) @ `ef_search=50` |
| 3b | approved‑agreement vs the brute oracle (2000) | ✅ **99.90 %** (FP=1, FN=1; ≥99 %) |
| 4 | HNSW vs brute latency | ✅ HNSW p50 0.084 ms / **p99 0.145 ms** vs brute p99 43.8 ms — **≈430×** |
| — | `./mvnw clean package` | ✅ exit 0; no test harness leaks into the jar |

> **Latency:** the HNSW search is already **sub‑millisecond on HotSpot** (p99 ≈ 0.145 ms), so the *search* line of the p99 < 1 ms target is met before Native Image. Waves 4–5 harden the rest of the envelope (containerization + 350 MB budget; native image + PGO). See the performance budget in [ARCHITECTURE.md](docs/ARCHITECTURE.md#6-performance-budget).

## Project status & roadmap

| Wave | Goal | Status |
| --- | --- | --- |
| **1** | End‑to‑end skeleton, brute‑force float32 k‑NN — correctness baseline | ✅ **Complete** |
| **2a** | int8 quantization + memory‑mapped off‑heap binary dataset | ✅ **Complete** |
| **2b** | Vector API (SIMD) distance — *evaluated; 3.8× slower for this shape, scalar kept* | ✅ **Complete** |
| **3** | Hand‑rolled HNSW index — recall@5 96.89 %, p99 ≈ 0.145 ms (≈430× vs brute) | ✅ **Complete** |
| 4 | Containerization (HotSpot) + 350 MB budget + official k6 + ≥2 instances behind HAProxy | ⏳ Planned |
| 5 | GraalVM Native Image + PGO | ⏳ Planned |

The full roadmap, with the per‑stage performance reasoning, is in [`docs/RINHA_PLAN.md`](docs/RINHA_PLAN.md) (PT‑BR).

## Repository layout

```
fraudDetection/
├── README.md                  # you are here
├── COMECE_AQUI.md             # hands-on getting-started guide (PT-BR)
├── INSTALACAO.md              # toolchain installation (PT-BR)
├── docs/
│   ├── ARCHITECTURE.md        # engineering deep-dive of the as-built system (EN)
│   ├── RINHA_PLAN.md          # 5-wave implementation plan (PT-BR)
│   ├── CONCEITOS.md           # underlying concepts (PT-BR)
│   ├── IMPACTO.md             # design-impact analysis (PT-BR)
│   ├── TUTORIAL_SERVER_NIO.md # build-it-yourself: the NIO server (PT-BR)
│   ├── TUTORIAL_JSON_KNN.md   # build-it-yourself: JSON parser + k-NN (PT-BR)
│   └── tecnologias/           # 14 technology reference notes (PT-BR)
└── api/                       # the Maven project
    ├── pom.xml                # zero dependencies; native profile for GraalVM
    └── src/main/
        ├── java/org/fraudDetection/
        │   ├── Main.java
        │   ├── server/        # NioServer, ConnectionState, HttpParser, HttpResponseWriter
        │   ├── json/          # FraudRequestParser
        │   ├── dataset/       # MmapDataset
        │   ├── knn/           # DistanceFunctions, HnswIndex
        │   └── controllers/   # FraudController, HealthController
        └── resources/
            ├── example-references.json   # 100-entry sanity dataset (versioned)
            └── references.json.gz        # full 3M dataset (NOT versioned)
```

## Documentation

| Document | Language | Purpose |
| --- | --- | --- |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | EN | The as‑built system in depth: request lifecycle, every component, the p99 budget, design trade‑offs |
| [docs/RINHA_PLAN.md](docs/RINHA_PLAN.md) | PT‑BR | The 5‑wave implementation plan and locked technology stack |
| [docs/CONCEITOS.md](docs/CONCEITOS.md) | PT‑BR | The concepts behind the approach (NIO, HNSW, quantization, native image) |
| [docs/IMPACTO.md](docs/IMPACTO.md) | PT‑BR | Why each decision matters for the score |
| [docs/TUTORIAL_SERVER_NIO.md](docs/TUTORIAL_SERVER_NIO.md) · [docs/TUTORIAL_JSON_KNN.md](docs/TUTORIAL_JSON_KNN.md) | PT‑BR | Hands‑on, line‑by‑line build tutorials |
| [COMECE_AQUI.md](COMECE_AQUI.md) · [INSTALACAO.md](INSTALACAO.md) | PT‑BR | Getting started and toolchain setup |

> The PT‑BR documents are learning/build material written while implementing the project. **ARCHITECTURE.md** is the canonical, language‑neutral reference for *what was built*.

## Tech stack

- **Language/runtime:** Java 21 LTS (HotSpot today; GraalVM Native Image planned for Wave 5)
- **I/O:** `java.nio` `Selector` — single‑threaded non‑blocking reactor
- **SIMD:** `jdk.incubator.vector` (Vector API) — on the module path, used from Wave 2
- **Build:** Maven via the project wrapper (`./mvnw`); `native` profile uses `native-maven-plugin`
- **Runtime dependencies:** none

## Author & license

- **Author:** [@arthurd3](https://github.com/arthurd3) — repository: [`arthurd3/fraud-detection`](https://github.com/arthurd3/fraud-detection)
- **Challenge:** [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026) by [@zanfranceschi](https://github.com/zanfranceschi)
- **License:** not yet declared. Add a `LICENSE` file before publishing if you intend to allow reuse (MIT is the common choice for Rinha submissions).
