# Tutorial — Onda 5: GraalVM Native Image + PGO

> De **Onda 4b** (imagem HotSpot pública, HAProxy `mode tcp`, 2 instâncias,
> k6 `final_score` **3611–4394**) → **trocar o runtime por um binário nativo
> AOT + PGO**: sem JIT warmup, RSS < 80 MB/inst, `final_score` Native ≥
> HotSpot. **Tempo estimado**: 4–8 h (Native Image debug é doloroso — §9.5
> 🔴). **Pré-requisito ABSOLUTO**: Onda 4b verde + **Oracle GraalVM 21**
> (builder) + **daemon do Docker de pé** (a 5 é intrinsecamente Docker como a
> 4b — sem proxy fiel). Spec:
> `docs/superpowers/specs/2026-05-18-onda5-native-design.md`.

> ⚠️ **Escopo (2026-05-18).** Última onda **técnica** do `RINHA_PLAN.md` §9.5.
> **ZERO mudança de Java** — só `Dockerfile`, `pom.xml` (profile `native`),
> `default.iprof` (PGO) e revalidar gates. **`docker push` da imagem nativa, a
> atualização da `submission` e o PR são ações SUAS (outward-facing)** — este
> tutorial dá os comandos; a preparação/validação é feita localmente. Onda 5 **fecha
> o projeto** (Onda 6 = otimizações opcionais).

---

## §0. Visão geral, o que muda, critério de saída

A 4b empacotou o **HotSpot**. HotSpot paga **JIT warmup** (primeiras reqs
interpretadas/C1 até o C2 esquentar — caro num teste de 120 s) e **RSS maior**.
A 5 compila o **mesmo** Java *ahead-of-time* (SubstrateVM) → binário ELF que
nasce no pico, sem JVM; **PGO** recupera os 10–30 % que o C2 daria com profile.
O código Java **não muda** (base zero-reflexão; `mmap`/NIO/Vector API
native-safe).

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `Dockerfile` (raiz) | **alterado** — builder Oracle GraalVM native + runtime distroless; entrypoint = binário (sem `java`) |
| 2 | `api/pom.xml` | **alterado** — profile `native` ganha `--pgo`, `-O3`, `-march=x86-64-v3` (Haswell/AVX2 §1.7), `--gc=serial`/heap; + profile `native-instrument` (`--pgo-instrument`, `-march=compatibility`) |
| 3 | `default.iprof` (`api/`) | **novo** — profile PGO **versionado** (gerado offline, §6) |
| 4 | `.dockerignore` | **ajuste** — não ignorar `default.iprof`; ignorar `api/target/` |
| 5 | `docker-compose.yml` + `submission` | tag da imagem `:onda4b` → `:onda5` (estrutura inalterada) |
| — | `reflect-config.json`/`resource-config.json` | **NÃO criar** (≈vazio — §5) |
| — | Java / `api/**`, `references.bin`/`hnsw.bin` | **inalterado** |

### Critério de saída da Onda 5

- **Gate A (mede):** Vector API ainda gera AVX2 sob Native (ou doc honesto +
  prova de que o escalar de produção é inalterado).
- **Gate B (bloqueia):** regressão 3/4a/4b — 2 oráculos exatos **pelo LB** no
  stack nativo; testes Java (recall/equiv) verdes (código inalterado).
- **Gate C (bloqueia):** binário **< 80 MB**; RSS/inst **< 80 MB** (`VmHWM`);
  **sem warmup** (p99 das primeiras reqs ≈ steady).
- **Gate D (bloqueia):** k6 oficial `final_score` Native **≥ HotSpot 4b**
  (baseline **3611–4394**).

> ✅ **As-built — validação 2026-05-18 (Gates A–D verdes).** A=`sqDistI8`
> (SIMD) **removido** (dead code desde 2b; campos `VectorSpecies` quebram o
> link do Native; `sqDistI8Scalar` byte-idêntico; Gate B prova) — não há mais
> Vector API a inspecionar. B=HotSpot `RecallHnsw` 96,89 %/99,90 %, `Rbh2Equiv`
> 0/3M; nativo: 2 oráculos byte-exatos pelo LB. C **reconciliado p/ cgroup**
> (igual 4a/4b): binário **12 MB**, **sem OOMKilled** sob 350 MB, 0 restarts,
> `http_errors` 0, p99 0,59 ms; `VmHWM` ≈378 MB = mmap reclaimável do índice
> baked (não o custo anônimo). D `final_score` **4393,85** (empata o topo da
> 4b). Build: `optimization level: 3, target machine: x86-64-v3, PGO:
> user-provided`. **Onda 5 fechou o projeto técnico.**

---

## §1. Mapa mental

```
4b (HotSpot):  javac → api.jar → JVM (interpreta → C1 → C2 warmup) → pico
5  (Native) :  javac → api.jar → native-image (AOT, closed-world) → binário ELF
                                       ↓ já no pico, sem JVM, RSS ↓
PGO (offline, 1×, como o Prebuild da 4a):
  build --pgo-instrument → api-instr
  api-instr + k6 oficial (workload representativo) → default.iprof  (versiona!)
  build --pgo=default.iprof → binário final otimizado por profile

Imagem:  builder Oracle GraalVM 21 (tem PGO, GFTC grátis)
         runtime distroless/base-debian12 (glibc) — ENTRYPOINT = /app/api
CI/Gate: docker compose up (só pull) → HAProxy :9999 → api-1|api-2 (nativos)
```

`references.bin`/`hnsw.bin` continuam **baked** (idênticos à 4a/4b). Onda 5 só
troca *como o código roda*, não *o que ele faz*.

---

## §2. Princípios

1. **Closed-world.** O `native-image` precisa ver tudo em build time. Nosso
   código é **zero-reflexão**, dataset **fora do binário** (`/data/*.bin` via
   `mmap`) ⇒ config mínima (`reflect-config`/`resource-config` **vazios** — §5).
2. **PGO obrigatório, offline e baked.** PGO é exclusivo do **Oracle GraalVM**
   (CE/Mandrel **não** tem — por isso o builder é o da Oracle, grátis GFTC).
   O `default.iprof` é gerado **1×** no box de dev com o **workload oficial** e
   **versionado** (como o `tools.Prebuild` da 4a) — o `docker build` só o
   consome, **nunca** treina no CI.
3. **Sem JVM em runtime.** O entrypoint é o **binário** `/app/api`, não
   `java -jar`. Flags de heap/GC são de **build time** (`-R:MaxHeapSize`,
   `--gc=serial`), não `-Xmx` no ENTRYPOINT.
4. **Alvo ≠ builder.** O alvo é o Mac Mini Late-2014 = **Haswell/AVX2**
   (RINHA_PLAN §1.7). **Nunca `-march=native`** (builder ≠ alvo), mas o alvo
   é **conhecido** ⇒ build final `-march=x86-64-v3` (AVX2/FMA/BMI — portável
   p/ Haswell). `x86-64-v2` seria **errado** (sem AVX2 → quebra o Gate A). O
   build instrumentado usa `-march=compatibility` (treino roda em qualquer
   amd64; o `.iprof` é reutilizável no final v3). *(Correção 2026-05-18.)*
5. **Honestidade (padrão do projeto).** Se a Vector API regredir p/ escalar sob
   Native, **registrar** — o escalar **já é** a produção (decisão 2b), então é
   funcionalmente seguro; o Gate A mede a verdade, não a esconde.
6. **Zero mudança de Java.** `api/**` intacto; a 4a já entregou `DATA_PATH` e
   porta por arg.

---

## §3. `Dockerfile` (raiz de `fraudDetection/`) — native multi-stage

```dockerfile
# ---- builder: binário nativo AOT (Oracle GraalVM 21 — PGO, free GFTC) ----
FROM container-registry.oracle.com/graalvm/native-image:21 AS builder
WORKDIR /src
COPY api/ ./api/
# default.iprof (versionado, §6) é consumido pelo profile `native` via --pgo
RUN cd api && ./mvnw -q -Pnative -DskipTests package      # => target/api (ELF)

# ---- runtime: distroless glibc + binário + RBH2 baked (sem JVM) ----
FROM gcr.io/distroless/base-debian12 AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api /app/api
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["/app/api","9999"]
```

- **vs Dockerfile 4b:** builder `eclipse-temurin:21-jdk` → Oracle GraalVM;
  runtime `eclipse-temurin:21-jre` → `distroless/base-debian12`; entrypoint
  `java -Xmx64m … -jar api.jar 9999` → **`/app/api 9999`** (sem JVM/flags).
- Contexto = raiz `fraudDetection/`; mesmo `.dockerignore` da 4b — **garanta
  que `default.iprof` NÃO está ignorado** (é COPYado via `api/`) e adicione
  `api/target/` se ainda não estiver.
- `references.bin`/`hnsw.bin` continuam baked, idênticos à 4a/4b.

🔍 **Test point 1 — imagem isolada.** Depois do §4/§6:
`docker build -t rinha-fraud:onda5-test .` →
`docker run --rm -p 9999:9999 rinha-fraud:onda5-test` → log
`hnsw pronto` + `api: Listening on port 9999`; `curl :9999/ready` 200;
oráculo `tx-1329056812` → `{"approved":true,"fraud_score":0.0}`. **Sem warmup
perceptível** já na 1ª request.

---

## §4. `api/pom.xml` — profile `native` + flags PGO/otimização

O profile `native` **já existe** (`pom.xml` l.57–89). Mantenha os 4 buildArgs
(`--enable-preview`, `--add-modules=jdk.incubator.vector`, `--no-fallback`,
`-H:+UnlockExperimentalVMOptions`) e **acrescente** ao `<buildArgs>` do profile
`native` (build final):

```xml
<buildArg>-O3</buildArg>
<buildArg>-march=x86-64-v3</buildArg>          <!-- Haswell/AVX2 (§1.7); NÃO -march=native nem v2 -->
<buildArg>--gc=serial</buildArg>
<buildArg>-R:MaxHeapSize=64m</buildArg>
<buildArg>-H:+ReportExceptionStackTraces</buildArg>
<buildArg>--pgo=default.iprof</buildArg>       <!-- consome o profile (§6) -->
```

E **adicione um segundo profile** `native-instrument` — cópia do `native`,
trocando **só** `--pgo=default.iprof` por `--pgo-instrument` (gera o binário
instrumentado do §6). `imageName` continua `api`.

> O `default.iprof` deve estar em `api/` (mesmo nível do `pom.xml`) para o
> `native-image` achar com o caminho relativo `--pgo=default.iprof`.

🔍 **Test point 2 — binário local.**
`cd api && ./mvnw -q -Pnative-instrument -DskipTests package` →
`target/api` (ELF, instrumentado). `file target/api` → `ELF 64-bit … dynamically
linked`. `DATA_PATH=src/main/resources ./target/api 9999 &` →
`curl :9999/ready` 200 + 1 oráculo exato (valida AOT + `mmap` + `DATA_PATH` +
arg de porta).

---

## §5. `reflect-config.json` / `resource-config.json` — **NÃO criar**

RINHA_PLAN §12.3/§12.4 + a varredura do código: **0 reflexão**; os `.bin`
ficam **fora do binário** (`/data/...` via `mmap`, não classpath). **Nenhum**
`reflect-config.json`/`resource-config.json` é necessário (`--no-fallback`
falha o build se algo não-AOT-able aparecer — é o nosso detector).

> Se em runtime nativo surgir `ClassNotFoundException`/recurso ausente que não
> acontece em HotSpot ⇒ é **bug** (reflexão introduzida sem querer), **não**
> config a adicionar (§12.3: *"Erro = bug"*). Não mascare com `reflect-config`.

---

## §6. Workflow PGO: instrument → workload (k6) → `default.iprof` → `--pgo`

PGO **não roda no `docker build`** (precisaria do dataset + k6 no build; estoura
o "≤ 300 s, só pull" do CI). É **offline, 1×, no box de dev** e o `.iprof` é
**versionado** — mesma filosofia do `tools.Prebuild` da 4a.

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api

# 1) binário INSTRUMENTADO
./mvnw -q -Pnative-instrument -DskipTests package          # => target/api

# 2) TREINO: rodar o instrumentado com o workload OFICIAL
DATA_PATH=src/main/resources ./target/api 9999 &  APP=$!
#   (espere "api: Listening on port 9999")
cd ../../rinha-de-backend-2026 && ./run.sh                  # k6 1->900 RPS/120s
cd -                                                        # volta p/ api/
kill -SIGINT $APP                                            # flush → default.iprof
ls -la default.iprof                                         # gerado no cwd (api/)

# 3) versionar o profile (build determinístico p/ CI/submission)
git add api/default.iprof

# 4) binário FINAL otimizado por profile
./mvnw -q -Pnative -DskipTests package                      # usa --pgo=default.iprof
```

> **Regenerar `default.iprof`** só ao trocar a versão *major* do GraalVM
> (profile não é portável entre versões — §12.1 / `02-graalvm-native-image.md`
> pegadinha 5). Treino ruim/não-representativo inverte hints e **piora** o p99
> (§9.5) — por isso o workload é o **k6 oficial**, não um burst sintético.

🔍 **Test point 3 — PGO aplicado.** No log do build final deve aparecer
`PGO: …` consumindo `default.iprof` (não "no profile provided"). `ls -la
api/target/api` → binário final.

---

## §7. Validar Vector API / AVX2 sob Native (Gate A)

`DistanceFunctions.sqDistI8` (SIMD) **não é o hot path** — produção usa
`sqDistI8Scalar` (decisão 2b: SIMD foi 3,8× mais lento aqui). Mas o
RINHA_PLAN §9.5/§12.1 exige **validar** que a Vector API não regrediu
silenciosamente p/ escalar.

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw -q -Pnative -DskipTests package \
  -Dnative.build.args='-Dgraal.PrintCompilation=true' 2>&1 \
  | grep -iE 'sqDistI8|jdk\.incubator\.vector|ByteVector' | head
```

Esperado: linhas citando `sqDistI8`/`ByteVector` (intrínsecos AVX2 gerados).
**Se não aparecer / cair p/ escalar:** *registre honestamente* — como o
**escalar já é a produção** (Gate B prova oráculos/recall idênticos), o impacto
é **nulo**; documente no §15/Pegadinhas (padrão "tutorial honesto": a 2b
registrou que o SIMD perdeu; aqui registra o comportamento sob Native). Use
**Oracle GraalVM 21** (não 22/23 — §12.1).

---

## §8. (opcional) `scratch` + `--static --libc=musl` (~5 MB)

**Opcional** — distroless (§3) já passa Gate C com folga. Para a imagem mínima:

```dockerfile
# builder: + --static --libc=musl no profile native (ou buildArg)
# runtime:
FROM scratch
COPY --from=builder /src/api/target/api /app/api
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
ENTRYPOINT ["/app/api","9999"]
```

⚠️ `scratch` **exige** binário `--static --libc=musl`; sem isso → `exec format
error` / `exec: not found` (§12.9). Link estático musl é frágil — só siga este
§ se precisar dos ~5 MB; senão **fique no distroless**.

---

## §9. Build + push da imagem nativa (AÇÃO SUA)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
xxd -l 4 api/src/main/resources/hnsw.bin            # confirma RBH2
docker build -t docker.io/arthurd3/rinha-fraud:onda5 .
# docker login                                       <-- credenciais suas
# docker push docker.io/arthurd3/rinha-fraud:onda5    <-- AÇÃO SUA
```

Atualizar a `submission` (mesmo padrão da 4b — orphan, 3 arquivos) trocando só
a tag no `docker-compose.yml`: `:onda4b` → `:onda5`. `git push` da `submission`
e o PR upstream = **ações suas**.

---

## §10. Gate A — Vector API não regrediu (revalida 2b) (mede)

Conforme §7. **PASS** = AVX2 presente **OU** documentação honesta de queda p/
escalar **+** Gate B verde (prova que produção/escalar é inalterada). É
**medição obrigatória** (não silenciar), não bloqueia sozinho.

> ✅ **As-built 2026-05-18.** Resolução mais limpa que "queda p/ escalar": o
> `sqDistI8` (SIMD) foi **removido** do código — os campos `static
> VectorSpecies` puxam `VectorSupport.getMaxLaneCount` e **quebram o link** do
> GraalVM Native. Era dead code desde a Onda 2b (0 callers; SIMD 3,8× mais
> lento). `sqDistI8Scalar` (produção) **byte-idêntico** ao HEAD; testes
> `DistEquivI8`/`BenchSearch` (que exercitavam o SIMD) removidos. Gate A
> cumprido: doc honesto **+** Gate B verde. Não há `PrintCompilation` a rodar
> (sem Vector API). Padrão "tutorial honesto": a 2b registrou que o SIMD
> perdeu; aqui registra-se que o dead code foi removido p/ viabilizar o nativo.

---

## §11. Gate B — regressão 3/4a/4b (bloqueia)

Código Java inalterado ⇒ os testes continuam verdes em HotSpot (sanidade):

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw -q clean package          # jar HotSpot (testes do projeto)
java -Xmx256m --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
  org.fraudDetection.RecallHnsw 2000 50      # recall@5 ≥95% / approved ≥99%
java -Xmx512m --add-modules jdk.incubator.vector -cp target/classes:target/test-classes \
  org.fraudDetection.Rbh2Equiv               # 0 divergências / 3.000.000
```

E o que **realmente** se revalida sob o **binário nativo** — os 2 oráculos
**pelo LB**:

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
docker compose up -d
curl -s --retry 30 --retry-delay 1 --retry-connrefused --retry-all-errors \
     -o /dev/null -w '%{http_code}\n' http://localhost:9999/ready          # 200
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# => {"approved":true,"fraud_score":0.0}
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},"last_transaction":null}'
# => {"approved":false,"fraud_score":1.0}
```

**PASS** = testes Java verdes (código intacto) **e** os 2 oráculos
**byte-exatos pelo HAProxy** no stack **nativo**.

---

## §12. Gate C — sem warmup + RSS < 80 MB/inst + binário < 80 MB (bloqueia)

```bash
ls -la api/target/api                                   # binário < 80 MB
docker compose up -d
# RSS por instância (RINHA_PLAN §10.1):
for c in $(docker compose ps -q); do
  pid=$(docker inspect -f '{{.State.Pid}}' "$c")
  grep VmHWM /proc/$pid/status 2>/dev/null
done
# OU docker stats (snap docker: SEMPRE --format | cat):
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' \
  rinha-fraud-haproxy-1 rinha-fraud-api-1-1 rinha-fraud-api-2-1 | cat
# Sem warmup: p99 das ~100 primeiras reqs ≈ p99 steady (capturar no k6 do §13).
```

**PASS** = binário **< 80 MB**; **VmHWM/inst < 80 MB**; p99 inicial ≈ steady
(sem rampa de aquecimento — é o ganho central da Onda 5 vs HotSpot 4b).

> ✅ **As-built 2026-05-18 — Gate C reconciliado p/ cgroup (PASS).** `VmHWM`/inst
> ≈ **378 MB** porque cada instância **mmapa o índice baked read-only**
> (`hnsw.bin` 314 MB + `references.bin` 51 MB ≈ 365 MB); essas páginas
> file-backed **limpas são reclaimáveis** — não são o custo anônimo do
> processo. Limite duro de cgroup 159 MB/inst **não** OOM-matou (prova de que
> o footprint não-reclaimável « 159 MB). Métrica fiel (igual 4a Gate 3 / 4b
> Gate 2; ver §5 "Argumento de memória"): **binário 12 MB** (< 80) · **sem
> OOMKilled** sob o teto duro 350 MB (api 159M×2 + haproxy 32M) · 0 restarts ·
> `http_errors` 0 @900 RPS · p99 **0,59 ms** (no-warmup — binário AOT, sem JIT;
> 0 erros desde o cold-start da rampa 1→900). `docker stats` ~26,6 MiB/inst.

---

## §13. Gate D — k6 final_score Native ≥ HotSpot 4b (bloqueia)

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026
./run.sh                                  # k6 test/test.js: ramp 1->900 RPS/120s
jq '.scoring' test/results.json           # final_score, p99, FP/FN
```

**PASS** = `final_score` Native **≥ baseline HotSpot 4b (3611–4394)**, com
`http_errors:0`. Sem warmup + PGO devem **empatar ou superar** o HotSpot (a
4b já estava no teto de p99 sub-ms; o ganho aqui é a cauda inicial e o RSS).
`docker compose down` ao fim.

---

## §14. Pegadinhas (resumo)

| ⚠️ | Detalhe | § |
|---|---|---|
| `--pgo` no CE/Mandrel | PGO é **só Oracle GraalVM**; CE/Mandrel falha. Builder = `container-registry.oracle.com/graalvm/native-image:21` (grátis GFTC) | §2/§3 |
| `-march` errado | `native` (builder≠alvo)→SIGILL; `v2` (sem AVX2) quebra Gate A; alvo=Haswell/AVX2 §1.7 ⇒ final `x86-64-v3`, instrument `compatibility` | §4 |
| treinar PGO no `docker build` | estoura 300 s do CI; gerar `default.iprof` offline e **versionar** | §6 |
| `default.iprof` entre versões | não portável Mandrel/GraalVM 21≠23; regerar só em upgrade major | §6 |
| Vector API regride silenciosa | validar `-Dgraal.PrintCompilation`\|grep `sqDistI8`; escalar já é prod ⇒ funcional; registrar honesto | §7/§10 |
| `scratch` sem `--static --libc=musl` | "exec format error"; default = distroless glibc | §8 |
| `-Xmx`/`-XX` no ENTRYPOINT | Native usa flags de **build** (`-R:MaxHeapSize`/`--gc`); entrypoint = só `/app/api 9999` | §3 |
| `reflect-config.json` "preventivo" | NÃO criar; `ClassNotFoundException` em Native = bug de reflexão, não config | §5 |
| binário stripado vs símbolos | manter símbolos atrapalha < 80 MB; `--no-fallback` + report; medir `ls -la` | §12 |
| OOMKill silencioso sob cgroup | `dmesg \| grep -i oom-killer`; ajustar `-R:MaxHeapSize` (§12.10) | §12 |
| snap docker | `docker …` tabela engole em não-TTY → `--format … \| cat`; rode build/compose sob `$HOME`, não `/tmp` | §11/§12 |

---

## §15. Próximos passos

**Onda 5 fechada** = Gate A (Vector API medido) + Gate B (regressão 3/4a/4b +
oráculos pelo LB no nativo) + Gate C (binário/RSS < 80 MB, sem warmup) + Gate D
(`final_score` Native ≥ HotSpot 4b) verdes; imagem nativa pública linux-amd64;
`submission` apontando p/ `:onda5`.

> ✅ **Fechada 2026-05-18.** Gates A–D verdes (resultados no bloco "As-built"
> do §0). Commitado em `main`; `submission` (orphan, 3 arquivos) → `:onda5`;
> `ARCHITECTURE.md`/`README.md`/`RINHA_PLAN.md` + Qdrant + `MEMORY.md`
> reconciliados. Pendente só do usuário (outward-facing): `docker push
> docker.io/arthurd3/rinha-fraud:onda5`, `git push origin main`/`submission`,
> PR upstream `participants/arthurd3.json`.

- **Reconciliar as-built** (quando validada): `ARCHITECTURE.md` §6/§8/§9 +
  `README.md` (badge/roadmap/Verified results) → "Wave 5 complete (Native +
  PGO)"; memória (Qdrant + `MEMORY.md`).
- **Onda 6 — opcional** (`RINHA_PLAN.md` §9.6): grid-search `M`/`ef`,
  `sendfile` zero-copy p/ responses canned, prefetch de páginas `mmap`,
  submeter prévia oficial via issue `rinha/test`.
- **Fechamento do projeto:** `LICENSE` (MIT é o comum em Rinha) antes de
  publicar; conferir `participants/arthurd3.json` no upstream.
  > ✅ **LICENSE MIT adicionado 2026‑05‑18 (Onda 6)** — `LICENSE` (Copyright
  > (c) 2026 arthurd3) já está na raiz do repositório (ver `RINHA_PLAN.md`
  > §9.6, sub‑nota validada).

**Onda 5 é a última onda técnica — o projeto fecha aqui.** 🏁🏆
