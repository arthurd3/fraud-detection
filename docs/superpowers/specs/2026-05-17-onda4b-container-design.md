# Spec — Onda 4b: conteinerização + HAProxy + k6 oficial + submission

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o
> entregável é o doc (`docs/TUTORIAL_CONTAINER.md`); o usuário implementa à mão
> (Dockerfile/compose/haproxy). Não auto-implementar. Antecessor:
> `2026-05-17-onda4a-fit-350mb-design.md`.
> **Pré-requisito:** Onda 4a verde (RBH2 lossless `hnsw.bin` ~300 MB,
> `references.bin` 49 MB, `api.jar` 41 KB sem dataset, `DATA_PATH`,
> `tools.Prebuild`; 2 instâncias + mmap = pico 147 MiB sob cgroup 350 MiB).
> **Pré-requisito de validação:** daemon do Docker de pé (a 4b é
> intrinsecamente Docker — não há proxy fiel como o `systemd-run` da 4a).

> ⚠️ **Escopo (2026-05-17).** Segunda metade da "Onda 4" do `RINHA_PLAN.md`
> §9.4 (a 4a resolveu "caber em 350 MB"). A 4b **conteineriza e roda como o
> Rinha avalia**. É a última etapa antes da submissão; depois só Onda 5
> (Native Image).

## Contexto

A Onda 4a deixou o artefato pronto para caber: binários RBH2 compactos +
`DATA_PATH` + jar magro, com a prova `systemd-run --memory=350M` (2 instâncias
+ mmap = 147 MiB). Falta empacotar e rodar **exatamente como o harness do Rinha
faz**: branch `submission` com `docker-compose.yml`, 2 instâncias atrás de um
load balancer round-robin, tudo somando ≤ 1 CPU / 350 MB, k6 oficial (ramp
1→900 RPS/120 s) contra o LB, `final_score` capturado.

## Decisões travadas (brainstorming, aprovadas pelo usuário)

1. **Imagem pública pré-buildada com binários baked.** Buildo a imagem
   localmente com `references.bin`+`hnsw.bin` RBH2 (da 4a) embutidos e
   empurro para um registry público (Docker Hub/GHCR). O `docker-compose.yml`
   no branch `submission` só referencia `image: <user>/rinha-fraud:onda4b` —
   **sem build, sem binários no Git, sem rebuild em runtime**; `docker compose
   up` só faz pull. Idiomático do Rinha; branch `submission` minúsculo; sem
   Git-LFS; `compose up` rápido (cabe nos 300 s do CI). Push da imagem + PR
   upstream = **ações outward-facing do usuário** (preparadas/validadas localmente).
2. **LB = HAProxy `mode tcp`** (RINHA_PLAN §5.10): `balance roundrobin`,
   `nbthread 1`, zero lógica de negócio, repassa bytes crus (o app já faz seu
   HTTP/1.1 by-hand). Imagem pública `haproxy:3-alpine`.
3. **Runtime HotSpot** (`eclipse-temurin:21-jre` — o runtime exato validado no
   Gate 3 da 4a). Native Image = Onda 5. Multi-stage Dockerfile no `main`.
4. **2 instâncias** (`api-1`/`api-2`), `deploy.resources.limits` somando
   **1.0 CPU / 350 MB** (a 4a provou folga: pico 147 MiB p/ 2 instâncias).
5. **Sem mudança de Java.** A 4a já entregou `DATA_PATH`; `Main` escuta a porta
   por arg. A 4b é 100% ops/infra.

## Design

### §1. Dockerfile multi-stage (no `main`)

```dockerfile
# ---- builder: compila o jar ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src
COPY api/ ./api/
RUN cd api && ./mvnw -q clean package -DskipTests

# ---- runtime: jar + binários RBH2 baked ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api.jar /app/api.jar
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["java","-DDATA_PATH=/data",\
  "-Xmx64m","-XX:+UseSerialGC","-XX:MaxMetaspaceSize=64m","-Xss512k",\
  "-XX:ReservedCodeCacheSize=24m","--add-modules","jdk.incubator.vector",\
  "-jar","/app/api.jar","9999"]
```

- Contexto de build = raiz do `fraudDetection/`. **`.dockerignore`
  OBRIGATÓRIO** excluindo `api/target/`, `**/references.json.gz`,
  `**/hnsw.rbh1.golden` (459 MB! infla o contexto), `.git/` — mas **NÃO**
  `references.bin`/`hnsw.bin` (são COPYados para o runtime).
- Flags JVM = as do spec 4a §5 (steady-state, build é offline).
- Alternativa reprodutível-do-zero (documentada, não default): o builder roda
  `tools.Prebuild` a partir de `references.json.gz` (~25 min, 1×, no box de
  dev/CI de imagem — **nunca** no `docker compose up` do Rinha).

### §2. `docker/haproxy.cfg`

```
global
    nbthread 1
    maxconn 4096
defaults
    mode tcp
    timeout connect 5s
    timeout client  30s
    timeout server  30s
frontend ft_fraud
    bind *:9999
    default_backend bk_api
backend bk_api
    balance roundrobin
    option tcp-check
    server api1 api-1:9999 check inter 2s
    server api2 api-2:9999 check inter 2s
```

Validar com `haproxy -c -f docker/haproxy.cfg` (sintaxe + garante `mode tcp`).

### §3. `docker-compose.yml` (raiz do branch `submission`; cópia no `main` p/ validar local)

```yaml
name: rinha-fraud
services:
  haproxy:
    image: haproxy:3.0-alpine
    volumes:
      - ./docker/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro
    ports:
      - "9999:9999"
    depends_on: [api-1, api-2]
    networks: [app]
    deploy:
      resources:
        limits: { cpus: "0.15", memory: "32M" }
  api-1:
    image: docker.io/<user>/rinha-fraud:onda4b
    networks: [app]
    deploy:
      resources:
        limits: { cpus: "0.425", memory: "159M" }
  api-2:
    image: docker.io/<user>/rinha-fraud:onda4b
    networks: [app]
    deploy:
      resources:
        limits: { cpus: "0.425", memory: "159M" }
networks:
  app:
    driver: bridge
```

Soma: CPU `0.15+0.425+0.425 = 1.0`; mem `32+159+159 = 350M`. `docker compose`
v2 honra `deploy.resources.limits.{cpus,memory}` (não-swarm). Alternativa
equivalente: `mem_limit`/`cpus` top-level.

### §4. `info.json` (branch `submission`) + `participants/arthurd3.json` (PR upstream)

```json
{
  "participants": ["arthurd3"],
  "social": ["https://github.com/arthurd3"],
  "source-code-repo": "https://github.com/arthurd3/fraud-detection",
  "stack": ["java", "haproxy"],
  "open_to_work": false
}
```

PR em `zanfranceschi/rinha-de-backend-2026` adicionando **um único arquivo**
`participants/arthurd3.json`:

```json
[{ "id": "arthurd3-java-hnsw", "repo": "https://github.com/arthurd3/fraud-detection" }]
```

(Schema validado pelo CI: array de `{id,repo}`, `id` único, ≤5 repos; o repo
precisa ser clonável, ter ≥2 branches e um branch `submission`.)

### §5. Branch `submission` (orphan)

Contém **apenas**: `docker-compose.yml` (raiz) + `docker/haproxy.cfg` +
`info.json`. **Sem código, sem binários** (estão na imagem pública). O
`Dockerfile`/`.dockerignore` ficam no `main` (usados p/ buildar+publicar a
imagem). O CI do Rinha: `git clone --branch submission --depth 1` → acha o
compose na raiz → `docker compose up` (≤300 s) → espera `/ready` (20×3 s).

### §6. Validação — 4 gates (no tutorial; aceitação da onda)

- **Gate 1 — stack e2e (bloqueia):** `docker compose up -d` (do branch
  `submission`, imagem pública) → HAProxy+api-1+api-2 saudáveis; `GET
  /ready` :9999 → 200; 2 oráculos exatos **pelo LB** (`tx-1329056812`→
  `{approved:true,0.0}`, `tx-3330991687`→`{approved:false,1.0}`).
- **Gate 2 — recursos (bloqueia):** `docker stats --no-stream` sob carga: soma
  de mem < 350 MB, CPU ≤ 1.0; nenhum container `OOMKilled`
  (`docker inspect -f '{{.State.OOMKilled}}'`).
- **Gate 3 — k6 oficial (medição):** rodar `./run.sh` no repo
  `rinha-de-backend-2026` (irmão de `fraudDetection/`) — k6 `test/test.js`,
  ramp 1→900 RPS/120 s, hits `localhost:9999` (o LB) → `test/results.json`;
  capturar `final_score` (esperado 3000–4500 HotSpot;
  p99 sub-ms da 3/4a + FP/FN baixos vs `expected_approved`). Baseline
  registrado (sem threshold absoluto — Onda 5 melhora).
- **Gate 4 — submission fiel ao CI (bloqueia):** num diretório limpo,
  `git clone --branch submission --depth 1 <repo>` + `docker compose up -d`
  reproduz o Gate 1 (imagem pública pull-able linux-amd64); `info.json` e
  `participants/arthurd3.json` validam no schema.

### §7. Não-objetivos

GraalVM Native Image / PGO / `--static`+`scratch` (Onda 5). Tuning fino de
`final_score`. HAProxy HTTP-mode/ACLs. Mudança de fórmula/quantização/HNSW/
formato. Abrir efetivamente o PR upstream e dar `docker push` (ações
outward-facing do usuário; o tutorial documenta os comandos exatos).

## Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `Dockerfile` (raiz) | **novo** — multi-stage: builder jar + runtime jre com binários RBH2 baked + `DATA_PATH=/data` |
| 2 | `.dockerignore` (raiz) | **novo** — exclui `api/target`, `*.json.gz`, `hnsw.rbh1.golden`, `.git` (mantém `*.bin`) |
| 3 | `docker/haproxy.cfg` | **novo** — `mode tcp`, roundrobin, `nbthread 1`, backend api-1/api-2:9999 |
| 4 | `docker-compose.yml` (raiz; e cópia no branch `submission`) | **novo** — haproxy + api-1 + api-2, `deploy.resources.limits` 1.0/350M |
| 5 | `info.json` (branch `submission`) | **novo** — metadados de submissão |
| 6 | `participants/arthurd3.json` | **novo** — PR no upstream (`[{id,repo}]`) |
| — | branch `submission` | orphan: só compose + `docker/haproxy.cfg` + `info.json` |
| — | Java / `api/**` | **inalterado** (4a já entregou `DATA_PATH`) |

## Test points do tutorial

1. `haproxy -c -f docker/haproxy.cfg` → "Configuration file is valid"; grep
   garante `mode tcp` (não `http`).
2. `docker build` local → imagem criada; `docker run --rm -p 9999:9999 <img>`
   sobe, `/ready` 200, 1 oráculo exato (valida binários baked + `DATA_PATH`).
3. `.dockerignore` funciona: contexto de build não inclui `hnsw.rbh1.golden`
   (build não demora minutos enviando 459 MB).
4. `docker compose up` local (cópia no `main`) → 3 serviços; `docker stats`
   soma < 350 MB; oráculos pelo LB (`:9999`).
5. Gates 1–4 conforme §6.

## Riscos / mitigações

| Risco | Mitigação |
|---|---|
| Daemon Docker indisponível | Pré-requisito explícito; a 4b é intrinsecamente Docker (sem proxy fiel — diferente da 4a/`systemd-run`) |
| HAProxy `mode http` por engano (+30% latência) | `mode tcp` mandatório; `haproxy -c -f` no Test point 1 |
| `hnsw.rbh1.golden` (459 MB) infla o contexto de build | `.dockerignore` exclui golden/`.gz`/`target`/`.git` — Test point 3 |
| cgroup por-serviço vs mmap compartilhado | 4a provou 147 MiB/2 inst.; limites generosos (159M/api); `docker stats` no Gate 2; subir limite/baixar `-Xmx` se OOM |
| Imagem ~600 MB demora no pull (>300 s do CI) | Só afeta pull, não os 350 MB RAM; base jre enxuta; registry rápido; (opção: distroless/java21) |
| `docker compose` ignorar `deploy.resources.limits` | Compose v2 honra; fallback `mem_limit`/`cpus` top-level documentado |
| Branch `submission` com código/binário por engano | Orphan com só 3 arquivos; Gate 4 clona `--depth 1` e confere |
| Push imagem / PR upstream sem autorização | São ações do usuário (outward-facing); apenas preparadas/validadas localmente |

## Próximo passo

Escrever `docs/TUTORIAL_CONTAINER.md` (hands-on PT-BR §0–§N, espelhando
`TUTORIAL_FIT_350MB.md`) + atualizar ponteiros (`§14` do `TUTORIAL_FIT_350MB.md`,
`§15` do `TUTORIAL_HNSW.md`) + reconciliar `ARCHITECTURE.md`/`README.md`
as-built (pendente da 4a + moldura 4b). Implementação à mão fica para o usuário;
os 4 gates são validados quando o daemon Docker estiver de pé. Onda seguinte:
**Onda 5 — GraalVM Native Image + PGO** (revalida Gate A da 2b + gates 3/4a/4b).
