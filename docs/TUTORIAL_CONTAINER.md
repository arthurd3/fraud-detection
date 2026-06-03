# Tutorial — Onda 4b: conteinerização + HAProxy + k6 oficial + submission

> De **Onda 4a** (RBH2 ~300 MB, `api.jar` 41 KB sem dataset, `DATA_PATH`,
> `tools.Prebuild`, fit provado 147 MiB/2 inst.) → **rodar como o Rinha
> avalia**: imagem pública com binários baked, HAProxy `mode tcp` + 2
> instâncias em `docker compose`, ≤ 1 CPU / 350 MB, k6 oficial, branch
> `submission`. **Tempo estimado**: 4–7 h. **Pré-requisito ABSOLUTO**: Onda 4a
> verde + **daemon do Docker de pé** (a 4b é intrinsecamente Docker — não há
> proxy fiel como o `systemd-run` da 4a). Spec:
> `docs/superpowers/specs/2026-05-17-onda4b-container-design.md`.

> ⚠️ **Escopo (2026-05-17).** Segunda metade da "Onda 4" (RINHA_PLAN §9.4); a
> 4a resolveu "caber em 350 MB". Esta é a última etapa antes da submissão;
> depois só **Onda 5** (Native Image). **`docker push` da imagem pública e o
> PR no upstream são ações SUAS (outward-facing)** — este tutorial dá os
> comandos exatos; a preparação e validação são feitas localmente; a publicação/PR é sua.

---

## §0. Visão geral, o que muda, critério de saída

A 4a deixou o artefato pronto. A 4b empacota e roda **exatamente como o harness
do Rinha**: o CI faz `git clone --branch submission --depth 1`, acha um
`docker-compose.yml` na raiz, `docker compose up` (≤ 300 s), espera `/ready`
(20×3 s), e roda o k6 (`test/test.js`, ramp 1→900 RPS / 120 s) contra o LB na
`:9999`, pontuando `final_score` (p99 + detecção).

**Decisão central:** imagem **pública pré-buildada com os binários RBH2
baked**. O branch `submission` só referencia `image:` — sem build, sem
binários no Git, sem rebuild em runtime. Zero mudança de Java (a 4a já entregou
`DATA_PATH`).

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `Dockerfile` (raiz) | **novo** — multi-stage: builder jar + runtime jre, binários RBH2 baked, `DATA_PATH=/data` |
| 2 | `.dockerignore` (raiz) | **novo** — exclui `api/target`, `*.json.gz`, `hnsw.rbh1.golden` (459 MB!), `.git` (mantém `*.bin`) |
| 3 | `docker/haproxy.cfg` | **novo** — `mode tcp`, roundrobin, `nbthread 1` |
| 4 | `docker-compose.yml` (raiz; e no branch `submission`) | **novo** — haproxy + api-1 + api-2, limits 1.0 CPU / 350 M |
| 5 | `info.json` (branch `submission`) | **novo** — metadados |
| 6 | `participants/arthurd3.json` | **novo** — PR no upstream |
| — | branch `submission` | orphan: só compose + `docker/haproxy.cfg` + `info.json` |
| — | `api/**` (Java) | **inalterado** |

### Critério de saída da Onda 4b

- **Gate 1 (bloqueia):** `docker compose up -d` → HAProxy+api-1+api-2
  saudáveis; `/ready` :9999 → 200; 2 oráculos exatos **pelo LB**.
- **Gate 2 (bloqueia):** `docker stats` sob carga: soma mem < 350 MB, CPU ≤
  1.0; nenhum `OOMKilled`.
- **Gate 3 (medição):** k6 oficial → `test/results.json`; `final_score`
  registrado (esperado 3000–4500 HotSpot).
- **Gate 4 (bloqueia):** `git clone --branch submission --depth 1` +
  `docker compose up` reproduz o Gate 1; `info.json`/`participants` schema OK.

---

## §1. Mapa mental

```
UMA VEZ (box de dev, ação sua):
  docker build (contexto = fraudDetection/) -> imagem c/ api.jar + /data/{references.bin,hnsw.bin}
  docker push docker.io/<user>/rinha-fraud:onda4b   (registry público)

BRANCH submission (orphan, minúsculo):
  docker-compose.yml + docker/haproxy.cfg + info.json   (referencia a imagem pública)

CI do Rinha (e seu Gate 4):
  git clone --branch submission --depth 1 -> docker compose up (só PULL, ~s)
  HAProxy :9999  --roundrobin-->  api-1:9999 | api-2:9999   (cada um mmapeia /data)
  k6 ramp 1->900 RPS/120s  ->  final_score
```

O grafo já vem pronto dentro da imagem (RBH2 da 4a). `docker compose up` **não
constrói nada** — só sobe. Cada instância mmapeia `/data` (páginas limpas
reclaimáveis; a 4a provou 2 inst. = 147 MiB).

---

## §2. Princípios

1. **Imagem pública baked.** Os ~349 MB de binários vivem **dentro da imagem**
   publicada, não no Git nem reconstruídos em runtime. `submission` fica
   minúsculo; `docker compose up` é só `pull` (cabe nos 300 s do CI).
2. **HAProxy `mode tcp`, zero lógica.** Repassa bytes crus (o app faz HTTP/1.1
   by-hand). `nbthread 1` (1 CPU total; thread extra = só context-switch).
   `mode http` custaria ~30 % de latência.
3. **`submission` sem código.** Orphan branch com 3 arquivos. O `Dockerfile`
   fica no `main` (usado p/ buildar a imagem), nunca no `submission`.
4. **Daemon Docker é pré-requisito.** Diferente da 4a (`systemd-run` fez de
   proxy fiel ao cgroup), a 4b é compose+HAProxy+registry+`docker stats` — sem
   substituto. Sem daemon, a 4b não valida.
5. **Ações outward-facing são suas.** `docker push` e o PR no upstream
   zanfranceschi: o tutorial dá os comandos; você executa. Tudo é preparado e
   validado localmente.
6. **Zero mudança de Java.** `Main` já lê `DATA_PATH` e a porta por arg (4a).

---

## §3. `Dockerfile` (raiz de `fraudDetection/`) — multi-stage

```dockerfile
# ---- builder: compila o jar (41 KB, sem dataset) ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src
COPY api/ ./api/
RUN cd api && ./mvnw -q clean package -DskipTests

# ---- runtime: jre + jar + binários RBH2 da Onda 4a baked ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api.jar /app/api.jar
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["java","-DDATA_PATH=/data", \
  "-Xmx64m","-XX:+UseSerialGC","-XX:MaxMetaspaceSize=64m","-Xss512k", \
  "-XX:ReservedCodeCacheSize=24m","--add-modules","jdk.incubator.vector", \
  "-jar","/app/api.jar","9999"]
```

- Contexto de build = `fraudDetection/` (rode `docker build` de lá).
- Os `references.bin`/`hnsw.bin` vêm do **contexto local** (gerados/validados
  na Onda 4a). Confirme antes: `xxd -l 4 api/src/main/resources/hnsw.bin` →
  `RBH2`.
- Flags JVM = spec 4a §5 (steady-state; build é offline, não no container).

🔍 **Test point 1 — imagem isolada.** `docker build -t rinha-fraud:test .` →
`docker run --rm -p 9999:9999 rinha-fraud:test` → log `hnsw pronto` +
`Listening on port 9999`; `curl :9999/ready` 200; oráculo `tx-1329056812` →
`{"approved":true,"fraud_score":0.0}` (valida binários baked + `DATA_PATH`).

---

## §4. `.dockerignore` (raiz) — OBRIGATÓRIO

Sem ele, `hnsw.rbh1.golden` (459 MB) + `.git` incham o contexto e o build
arrasta minutos enviando lixo.

```
.git
**/target
**/*.json.gz
**/hnsw.rbh1.golden
**/example-references.json
docs
*.md
.claude
```

> Mantém `api/src/main/resources/references.bin` e `hnsw.bin` (são COPYados
> para o runtime). Exclui o golden, o `.gz`, `target/`, `.git`, docs.

🔍 **Test point 2 — contexto enxuto.** `docker build` imprime `transferring
context: ~3xxMB` (≈ os 2 `.bin` + fontes), **não** ~800 MB. Se vier gigante,
o `.dockerignore` não pegou o golden.

---

## §5. `docker/haproxy.cfg` — TCP puro

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

🔍 **Test point 3 — sintaxe + modo.** `docker run --rm -v
"$PWD/docker/haproxy.cfg":/c:ro haproxy:3.0-alpine haproxy -c -f /c` →
"Configuration file is valid"; `grep -q 'mode tcp' docker/haproxy.cfg` (NÃO
`mode http`).

---

## §6. `docker-compose.yml` (raiz do `main` p/ validar local; idêntico no `submission`)

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

Soma: CPU `0.15+0.425+0.425 = 1.0`; mem `32+159+159 = 350M`. Troque `<user>`
pelo seu namespace do registry. Para validar local **antes** do push, use o
mesmo tag que vai publicar (ou `image: rinha-fraud:test` do Test point 1).

> `docker compose` v2 honra `deploy.resources.limits.{cpus,memory}` fora de
> swarm. Fallback equivalente: `mem_limit: 159m` + `cpus: 0.425` top-level.

🔍 **Test point 4 — stack local.** Com a imagem buildada: `docker compose up
-d` → `docker compose ps` 3 serviços `running`; `curl :9999/ready` 200;
oráculos pelo LB. `docker compose down` ao fim.

---

## §7. `info.json` (branch `submission`) + PR `participants/arthurd3.json`

`info.json` (ajuste social/`open_to_work`):

```json
{
  "participants": ["arthurd3"],
  "social": ["https://github.com/arthurd3"],
  "source-code-repo": "https://github.com/arthurd3/fraud-detection",
  "stack": ["java", "haproxy"],
  "open_to_work": false
}
```

PR no `zanfranceschi/rinha-de-backend-2026` adicionando **só**
`participants/arthurd3.json`:

```json
[{ "id": "arthurd3-java-hnsw", "repo": "https://github.com/arthurd3/fraud-detection" }]
```

> CI valida: array `{id,repo}`, `id` único, ≤5 repos; o repo precisa ser
> clonável, ter ≥2 branches e um branch `submission`.

---

## §8. Branch `submission` (orphan) — comandos

No repo `fraud-detection`, com `Dockerfile`/compose/haproxy.cfg/info.json
prontos no `main`:

```bash
git switch --orphan submission
git rm -rf . >/dev/null 2>&1 || true          # orphan começa vazio mesmo
mkdir -p docker
git checkout main -- docker-compose.yml docker/haproxy.cfg
# crie info.json (acima) na raiz
git add docker-compose.yml docker/haproxy.cfg info.json
git commit -m "submission: compose + haproxy + info (imagem publica onda4b)"
# git push -u origin submission   <-- AÇÃO SUA (outward-facing)
git switch main
```

`submission` fica com **exatamente 3 arquivos** (+ dir `docker/`). Sem código,
sem binários, sem `Dockerfile`.

---

## §9. Build + push da imagem pública (AÇÃO SUA)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
xxd -l 4 api/src/main/resources/hnsw.bin            # confirma RBH2
docker build -t docker.io/<user>/rinha-fraud:onda4b .
# docker login                                       <-- credenciais suas
# docker push docker.io/<user>/rinha-fraud:onda4b     <-- AÇÃO SUA
```

> A imagem (~jre + ~349 MB binários) só afeta o **pull** (cabe nos 300 s do
> CI), não os 350 MB de RAM (cgroup). Opção menor: base `gcr.io/distroless/
> java21-debian12` no runtime stage.

---

## §10. Gate 1 — stack e2e pelo LB (bloqueia)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection   # ou o clone do submission
docker compose up -d
docker compose ps                                          # 3x running
curl -s --retry 30 --retry-delay 1 --retry-connrefused --retry-all-errors \
     -o /dev/null -w '%{http_code}\n' http://localhost:9999/ready    # 200
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# => {"approved":true,"fraud_score":0.0}
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},"last_transaction":null}'
# => {"approved":false,"fraud_score":1.0}
```

**PASS** = 3 serviços `running`, `/ready` 200 (dentro de ~20×3 s), os 2
oráculos exatos **através do HAProxy**. (`docker compose logs api-1` deve
mostrar `hnsw pronto`.)

---

## §11. Gate 2 — recursos < 350 MB (bloqueia)

Com a stack no ar, gere carga (k6 do §12 ou uma rajada) e meça:

```bash
docker stats --no-stream --format \
  'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
for c in $(docker compose ps -q); do
  echo "$(docker inspect -f '{{.Name}} OOMKilled={{.State.OOMKilled}}' $c)"
done
```

**PASS** = soma de `MEM USAGE` < 350 MiB, CPU agregada ≤ ~100 %, **todos**
`OOMKilled=false`. (A 4a provou 2 inst. + mmap = 147 MiB; folga grande. Se
algum container morrer: subir o `memory` dele no compose ou baixar `-Xmx`.)

---

## §12. Gate 3 — k6 oficial + `final_score` (medição)

Com a stack no ar (LB em `:9999`):

```bash
cd ../rinha-de-backend-2026      # repo oficial, irmão de fraudDetection/
./run.sh                          # k6 test/test.js: ramp 1->900 RPS / 120s
jq '.scoring' test/results.json   # final_score, p99, FP/FN/err, cortes
```

**Registrar** `final_score` (esperado **3000–4500** com HotSpot, per
RINHA_PLAN §9.4). Sem threshold absoluto — a Onda 5 (Native) melhora p99. p99
sub-ms da 3/4a ⇒ `p99_score` alto; detecção: FP/FN baixos vs
`expected_approved` ⇒ `detection_score` alto.

---

## §13. Gate 4 — submission fiel ao CI (bloqueia)

Simula exatamente o harness do Rinha:

```bash
T=$(mktemp -d); git clone --branch submission --depth 1 \
   https://github.com/arthurd3/fraud-detection "$T"
cd "$T"
test -f docker-compose.yml && echo "compose na raiz OK"
docker compose up -d
curl -s --retry 30 --retry-delay 1 --retry-connrefused -o /dev/null \
     -w '%{http_code}\n' http://localhost:9999/ready          # 200
jq -e 'type=="array" and (.[0]|has("id") and has("repo"))' \
   ../fraudDetection/../participants-pr/arthurd3.json 2>/dev/null \
   || echo "validar participants/arthurd3.json schema manualmente"
docker compose down
```

**PASS** = clone do `submission` sobe **só com a imagem pública** (pull, sem
build), `/ready` 200, oráculos exatos; `info.json` e `participants/arthurd3.json`
no schema. (Se o `docker compose up` tentar buildar algo, o `submission` está
errado — não deve ter `Dockerfile`/`build:`.)

---

## §14. Pegadinhas (resumo)

| ⚠️ | Detalhe | § |
|---|---|---|
| daemon Docker down | 4b **exige** o daemon; sem proxy fiel (≠ 4a/systemd-run) — pré-requisito | §2 |
| `.dockerignore` ausente | `hnsw.rbh1.golden` 459 MB infla o contexto; build eterno | §4 |
| HAProxy `mode http` | +~30 % latência; `mode tcp` mandatório; `haproxy -c -f` | §5 |
| `submission` com código/Dockerfile | orphan só 3 arquivos; `docker compose up` não pode buildar | §8/§13 |
| binários no Git | NÃO — vivem na imagem pública; `submission` minúsculo | §1/§2 |
| `deploy.resources.limits` ignorado | compose v2 honra; fallback `mem_limit`/`cpus` top-level | §6 |
| limites por-serviço vs mmap | 4a provou 147 MiB/2 inst.; `docker stats` no Gate 2; subir limite se OOM | §11 |
| `image: <user>/...` não trocado | troque pelo seu namespace; senão `pull` falha no Gate 4 | §6/§9 |
| push/PR sem querer | são ações suas (outward-facing); preparado e validado localmente | §2/§9 |
| imagem ~600 MB | só afeta pull (≤300 s), não os 350 MB RAM | §9 |

---

## §15. Próximos passos

**Onda 4b fechada** = Gate 1 (stack e2e LB) + Gate 2 (`docker stats` < 350 MB,
sem OOMKilled) + Gate 3 (`final_score` registrado) + Gate 4 (clone
`--branch submission` fiel ao CI) verdes; imagem pública linux-amd64;
`participants/arthurd3.json` no upstream.

- **Onda 5 — GraalVM Native Image + PGO** (`TUTORIAL_NATIVE.md`, a criar):
  trocar o builder por `ghcr.io/graalvm/native-image-community:21` (Mandrel),
  `reflect-config.json` (≈vazio — 0 reflection), binários `.bin` ficam
  externos/baked, build `--pgo-instrument`→workload→`--pgo`, validar Vector
  API ainda gera AVX2 (`-Dgraal.PrintCompilation`), opção `--static --libc=musl`
  + `FROM scratch`. **Revalidar Gate A da 2b + gates 3/4a/4b**. Meta: RSS/inst.
  < 80 MB, sem warmup, `final_score` Native ≥ HotSpot.

---

**Cada Gate é uma vitória.** 🏁
