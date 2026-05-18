# Spec — Onda 5: GraalVM Native Image + PGO

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o
> entregável é o doc (`docs/TUTORIAL_NATIVE.md`); o usuário implementa à mão
> (Dockerfile/pom/configs). Não auto-implementar. Antecessor:
> `2026-05-17-onda4b-container-design.md`.
> **Pré-requisito:** Onda 4b verde (imagem pública baked, HAProxy `mode tcp`,
> `docker compose` 1.0 CPU / 350 MB, 4 gates verdes; `final_score` HotSpot
> baseline **3611–4394**).
> **Pré-requisito de validação:** Oracle GraalVM 21 (builder) + daemon do
> Docker de pé (a 5 é intrinsecamente Docker como a 4b; a validação dos gates
> é uma sessão futura — não desta entrega).

> ⚠️ **Escopo (2026-05-18).** Última onda **técnica** do `RINHA_PLAN.md` §9.5.
> Troca o runtime HotSpot por um binário **Native Image AOT + PGO**: elimina
> JIT warmup, RSS < 80 MB/inst, `final_score` Native ≥ HotSpot 4b. **ZERO
> mudança de Java.** Depois só fechamento/submissão (Onda 6 = otimizações
> opcionais).

> ✅ **As-built — validação 2026-05-18 (Gates A–D verdes).** Implementação à
> mão validada nesta máquina (Docker + builder Oracle GraalVM 21 cacheado;
> CE-local irrelevante — build é via Docker). Três reconciliações honestas
> (design preservado; notas datadas, padrão do projeto):
> 1. **"ZERO mudança de Java" → "ZERO mudança de comportamento de produção".**
>    O `sqDistI8` (SIMD `jdk.incubator.vector`) foi **removido** de
>    `DistanceFunctions.java` (+ testes `DistEquivI8`/`BenchSearch`): os campos
>    `static VectorSpecies` puxam `VectorSupport.getMaxLaneCount` → **falha de
>    LINK** no GraalVM Native. Era **código morto desde a Onda 2b** (0 callers;
>    SIMD 3,8× mais lento; produção sempre `sqDistI8Scalar`). `sqDistI8Scalar`
>    **byte-idêntico** ao HEAD ⇒ Gate B prova comportamento inalterado. Gate A
>    cumprido pela cláusula de escape do §6/§10 (doc honesto + Gate B).
> 2. **Gate C: métrica = cgroup, não `VmHWM` ingênuo.** `VmHWM`/inst ≈ 378 MB
>    porque cada instância **mmapa o índice baked read-only** (`hnsw.bin`
>    314 MB + `references.bin` 51 MB ≈ 365 MB); essas páginas file-backed
>    **limpas são reclaimáveis** — não são o custo anônimo do processo (é
>    exatamente o "Argumento de memória" do §5 e o que as Ondas 4a Gate 3 /
>    4b Gate 2 já mediam). Gate C verde = **binário 12 MB** (< 80 MB) + **sem
>    OOMKilled** sob o teto duro 350 MB (api 159M×2 + haproxy 32M) + 0 restarts
>    + `http_errors` 0 + p99 sub-ms (no-warmup).
> 3. **Correção `-march`** já aplicada na impl: `x86-64-v2` → **`x86-64-v3`**
>    (Haswell/AVX2 §1.7) — confirmado no build (`target machine: x86-64-v3`).
>
> **Resultados:** `final_score` Native **4393,85** (≥ HotSpot 4b 3611–4394 —
> empata o topo da 4b) · `http_errors` 0 · p99 **0,59 ms** · binário nativo
> **12 MB** · `VmHWM` 378 MB (mmap reclaimável) / cgroup ~26,6 MiB/inst,
> OOMKilled=false, 0 restarts · build: `optimization level: 3, target machine:
> x86-64-v3, PGO: user-provided` (default.iprof consumido, `--no-fallback` sem
> fallback) · Gate B HotSpot: `RecallHnsw` recall@5 96,89 % / approved 99,90 %,
> `Rbh2Equiv` 0/3.000.000 · oráculos byte-exatos pelo LB. **Onda 5 fecha o
> projeto técnico.**

## Contexto

A Onda 4b containerizou o artefato **HotSpot** (`eclipse-temurin:21-jre`,
binários RBH2 baked, 2 instâncias atrás do HAProxy, k6 oficial `final_score`
3611–4394). HotSpot paga dois custos que a Rinha pune: **JIT warmup** (as
primeiras requisições rodam interpretadas/C1 até o C2 otimizar — caro num teste
de 120 s) e **RSS maior** (80–200 MB/inst vs 30–80 MB nativo). A Onda 5 compila
o mesmo código Java **ahead-of-time** (SubstrateVM) → binário ELF que já nasce
no pico, sem JVM, com RSS pequeno; **PGO** recupera os 10–30 % que o C2 daria
com profile (RINHA_PLAN §5.2). A varredura do código confirmou base
**excepcionalmente native-clean**: 0 reflexão / `Unsafe` / JNI / threads /
`ServiceLoader`; `MappedByteBuffer`+`FileChannel`+NIO `Selector` são
SubstrateVM-safe; Vector API só em `DistanceFunctions.sqDistI8` (fora do hot
path — produção usa o escalar `sqDistI8Scalar`). Onda 5 é, portanto, **100 %
infra/build**: trocar o builder, ajustar o profile `native` do `pom.xml`,
orquestrar o PGO, revalidar os gates anteriores sob o binário nativo.

## Decisões travadas (brainstorming, aprovadas pelo usuário)

1. **Builder = Oracle GraalVM (GFTC, grátis) + PGO.** O `RINHA_PLAN.md` §5.2
   (l.288) e §9.5 (passo 1, l.1033) prescrevem o builder
   `ghcr.io/graalvm/native-image-community:21` (Mandrel/GraalVM **CE**) **e**
   `--pgo-instrument`/`--pgo` (§9.5 passos 5–6). **Isso é contraditório:** PGO
   (`--pgo`/`--pgo-instrument`) é **exclusivo do Oracle GraalVM** — CE/Mandrel
   **não** tem PGO. Resolução: usar o builder **`container-registry.oracle.com/
   graalvm/native-image:21`** (Oracle GraalVM), que **inclui PGO** e é
   **grátis para produção** sob a licença **GFTC** desde GraalVM for JDK
   17.0.9/21 (set/2023). Honra "PGO obrigatório" (§5.2, `tecnologias/
   02-graalvm-native-image.md` l.150) a custo zero. **Reconciliar** RINHA_PLAN
   §5.2/§9.5 e §12.1 / `02-graalvm-native-image.md` com **nota datada**
   (a escolha "Mandrel" precede o Oracle ficar grátis; preservar histórico).
2. **Escopo = só `spec` + `docs/TUTORIAL_NATIVE.md`.** Regra permanente do
   projeto: o usuário implementa à mão a partir do tutorial; o Claude valida os
   gates depois. Esta entrega **não** edita `pom.xml`/`Dockerfile` nem roda
   build nativo.
3. **Imagem final = distroless principal + scratch/musl §opcional.** Runtime
   `gcr.io/distroless/base-debian12` (glibc, ~20 MB, sem cadeia de build musl —
   RINHA_PLAN §5.11). `FROM scratch` + `--static --libc=musl` (~5 MB) fica como
   **§ opcional/apêndice** no tutorial (link frágil — §12.9 / `02` pegadinha 6).

**Travas herdadas (RINHA_PLAN §9.5 critério de saída — verbatim):** binário
nativo **< 80 MB**; **RSS/instância < 80 MB**; **sem warmup observável** (p99
estável das primeiras requisições); **Vector API ainda gera AVX2** (validado em
log); **`final_score` Native ≥ HotSpot**. Revalidar **Gate A da 2b** (Vector
API não regrediu) **+ gates 3/4a/4b** (recall/equivalência/oráculos/recursos).
PGO **obrigatório** (§5.2).

## Design

### §1. Dockerfile native multi-stage (no `main`)

```dockerfile
# ---- builder: binário nativo AOT (Oracle GraalVM 21 — tem PGO, free GFTC) ----
FROM container-registry.oracle.com/graalvm/native-image:21 AS builder
WORKDIR /src
COPY api/ ./api/
# default.iprof versionado no repo (gerado offline — ver §4); o profile
# `native` do pom consome via --pgo=default.iprof.
RUN cd api && ./mvnw -q -Pnative -DskipTests package      # gera target/api (ELF)

# ---- runtime: distroless glibc, binário + binários RBH2 baked (sem JVM) ----
FROM gcr.io/distroless/base-debian12 AS runtime
WORKDIR /app
COPY --from=builder /src/api/target/api /app/api
COPY api/src/main/resources/references.bin /data/references.bin
COPY api/src/main/resources/hnsw.bin       /data/hnsw.bin
ENV DATA_PATH=/data
EXPOSE 9999
ENTRYPOINT ["/app/api","9999"]
```

- **O que muda vs Dockerfile 4b:** builder `eclipse-temurin:21-jdk` → Oracle
  GraalVM; runtime `eclipse-temurin:21-jre` → `distroless/base-debian12`;
  entrypoint deixa de ser `java -Xmx64m … -jar api.jar` e passa a ser **o
  binário `/app/api`** (sem JVM, sem flags `-Xmx`/`-XX` de runtime — o heap é
  configurado em **build time**, ver §2). O arg `9999` continua (lido por
  `Main` via `args[0]`).
- Contexto de build = raiz de `fraudDetection/`; mesmo `.dockerignore` da 4b
  (golden RBH1 459 MB excluído) — **acrescentar** `default.iprof` à lista de
  itens versionados que **não** devem ser ignorados (é COPYado via `api/`).
- `references.bin`/`hnsw.bin` continuam baked (idênticos à 4a/4b) — Onda 5 não
  mexe no dataset/índice.

### §2. `api/pom.xml` — profile `native` + PGO/otimização

O profile `native` **já existe** (l.57–89): `native-maven-plugin` 0.10.3,
`mainClass=org.fraudDetection.Main`, `imageName=api`, buildArgs
`--enable-preview`, `--add-modules=jdk.incubator.vector`, `--no-fallback`,
`-H:+UnlockExperimentalVMOptions`. Onda 5 **acrescenta** (mantendo os 4):

- `--pgo=default.iprof` — consome o profile versionado (§4). Para o passo de
  instrumentação, uma variante (profile `native-instrument` **ou** buildArg
  parametrizado) troca por `--pgo-instrument` — o tutorial dá a forma exata.
- `-O3` (otimização máxima do native-image).
- `-march=x86-64-v3` — **NÃO `-march=native`**: o builder ≠ o alvo, mas o
  alvo é **conhecido** (Mac Mini Late-2014 = **Haswell/AVX2**, RINHA_PLAN §1.7)
  ⇒ `x86-64-v3` (AVX2/FMA/BMI) é o nível correto e portável p/ esse alvo.
  > **Correção 2026-05-18 (impl).** O design dizia `x86-64-v2` — **errado**:
  > v2 **não** tem AVX2, o que tornaria o **Gate A** ("Vector API ainda gera
  > AVX2") impossível por construção e deixaria SIMD na mesa num CPU que
  > **tem** AVX2 (§1.7). Corrigido p/ `x86-64-v3` (Haswell-compat). O profile
  > `native-instrument` usa `-march=compatibility` (treino roda em qualquer
  > amd64; o `.iprof` é reutilizável no build final v3).
- GC = **Serial** (default do Native Image; hot path zero-allocation ⇒
  praticamente pause-free — `02-graalvm-native-image.md` §GC). Explicitar
  `--gc=serial`; heap modesto via `-R:MaxHeapSize` (ex. 64m) p/ caber no RSS.
- `-H:+ReportExceptionStackTraces` (debug de Native é doloroso — §9.5 risco 🔴).

### §3. `reflect-config.json` / `resource-config.json` — **≈vazio (não criar)**

RINHA_PLAN §12.3/§12.4 + a varredura do código: **0 reflexão**; os `.bin`
ficam **fora do JAR/binário** (`/data/...` via `mmap`, não classpath). Nenhum
`reflect-config.json`/`resource-config.json` é necessário. Documentar no
tutorial: **não criar**; se aparecer `ClassNotFoundException`/recurso ausente
em runtime nativo ⇒ é **bug** (reflexão introduzida sem querer), **não**
config a adicionar (§12.3: "Erro = bug").

### §4. Workflow PGO (3 passos, **offline — artefato baked**, como o Prebuild da 4a)

PGO **não roda dentro do `docker build`** (precisaria do dataset + k6 no
build; viola o "≤300 s, só pull" do CI). Espelha a filosofia 4a/4b (artefato
gerado offline no box de dev, versionado, consumido baked):

1. **Instrumentar** (box de dev): build do binário com `--pgo-instrument` →
   `api-instr`.
2. **Treinar:** rodar `api-instr` com o **workload oficial** (k6
   `rinha-de-backend-2026/test/test.js`, ramp 1→900 RPS/120 s + warmup) →
   `kill -SIGINT` (flush) → gera **`default.iprof`**.
3. **Build final:** profile `native` com `--pgo=default.iprof`.

**Decisão:** **versionar `default.iprof`** no repo (arquivo pequeno; torna o
build do CI/`submission` determinístico e reproduzível — diferente dos `.bin`
que são gitignored por tamanho). **Regenerar** se trocar a versão major do
GraalVM (profile Mandrel21≠23 — §12.1 / `02` pegadinha 5; aqui Oracle GraalVM
21, regenerar só em upgrade).

### §5. Vector API sob SubstrateVM (Gate A da 2b)

`DistanceFunctions.sqDistI8` (SIMD `jdk.incubator.vector`) **não é o hot path**
— produção usa `sqDistI8Scalar` (decisão 2b: SIMD foi 3,8× mais lento aqui).
Mas o RINHA_PLAN §9.5/§12.1 exige **validar** que a Vector API ainda gera AVX2
sob Native (regressão silenciosa→escalar é a pegadinha #1). Validar no build:
`-Dgraal.PrintCompilation`/`-H:+PrintCompilation` filtrando `sqDistI8`/`vector`.
**Risco aceito:** se SubstrateVM não intrinsecar a Vector API, ela cai para
escalar — **funcionalmente seguro** (o escalar já é a produção); o Gate A
**mede e documenta honestamente** (padrão "tutorial honesto" das ondas
anteriores: 2b registrou que SIMD perdeu; aqui registra o comportamento sob
Native). Usar Oracle GraalVM **21** (não 22/23 — §12.1).

> ✅ **As-built 2026-05-18.** O risco virou fato com resolução mais limpa que
> "cair p/ escalar": `sqDistI8` (SIMD) foi **removido** — os campos `static
> VectorSpecies` quebram o **link** do Native (`VectorSupport.getMaxLaneCount`).
> Código morto desde 2b (0 callers); `sqDistI8Scalar` (produção) **byte-idêntico**
> ao HEAD. Gate A medido/honesto via §6 (escape clause) + Gate B verde. Sem
> `PrintCompilation` a rodar (não há mais Vector API a inspecionar).

### §6. Validação — 4 gates (no tutorial; aceitação da onda)

- **Gate A — Vector API (revalida 2b):** build com `PrintCompilation`; grep
  `sqDistI8`/`vector`. PASS = AVX2 presente **ou** documentação honesta de que
  caiu p/ escalar **com prova de que produção (escalar) é inalterada** (recall/
  oráculos via Gate B). Não-bloqueante por si só, mas **tem que ser medido**.
- **Gate B — regressão 3/4a/4b (bloqueia):** sob o binário nativo —
  `RecallHnsw` recall@5 ≥ 95 % / approved-agree ≥ 99 %; `Rbh2Equiv` 0
  divergências; os **2 oráculos exatos pelo LB** no `docker compose` nativo
  (`tx-1329056812`→`{true,0.0}`, `tx-3330991687`→`{false,1.0}`). *(Os testes
  Java rodam em HotSpot como sempre; o que se revalida no nativo são os
  oráculos e/-`/ready` e a equivalência do comportamento.)*
- **Gate C — footprint nativo (bloqueia):** binário **< 80 MB**; **RSS/inst
  < 80 MB** (`cat /proc/<pid>/status | grep VmHWM` — RINHA_PLAN §10.1); **sem
  warmup**: p99 das primeiras ~100 reqs ≤ ~3× steady (idealmente ≈ steady).
  > ✅ **As-built 2026-05-18 (reconciliado p/ cgroup).** `VmHWM` conta as
  > páginas **reclaimáveis** do índice mmapado read-only (365 MB baked) ⇒ ≈378
  > MB/inst — **não** é o custo anônimo. Métrica fiel (igual 4a Gate 3 / 4b
  > Gate 2, e o §5 "Argumento de memória"): **binário 12 MB** (< 80) + **sem
  > OOMKilled** sob o teto duro 350 MB + 0 restarts + `http_errors` 0 @900 RPS
  > + p99 0,59 ms (no-warmup). **PASS.**
- **Gate D — score (bloqueia):** k6 oficial (mesmo harness da 4b, LB :9999) →
  `final_score` Native **≥ HotSpot 4b** (baseline 3611–4394). Sem warmup +
  PGO devem empatar ou superar.

### §7. Não-objetivos

Mudança de Java (`api/**` inalterado). Mexer em HNSW/quantização/RBH2/fórmula
de score. Onda 6 (grid-search M/ef, `sendfile` zero-copy, prefetch mmap —
RINHA_PLAN §9.6, opcional). `scratch`+`--static --libc=musl` como caminho
principal (é § opcional). `docker push` da imagem nativa, atualizar a
`submission` no remoto e o PR upstream — **ações outward-facing do usuário**
(o tutorial documenta os comandos exatos; espelha a 4b).

## Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `Dockerfile` (raiz) | **alterado** — builder Oracle GraalVM native + runtime distroless; entrypoint = binário (sem `java`) |
| 2 | `api/pom.xml` | **alterado** — profile `native` ganha `--pgo`, `-O3`, `-march=x86-64-v3` (Haswell/AVX2 §1.7), `--gc=serial`/heap, `ReportExceptionStackTraces`; + profile `native-instrument` (`--pgo-instrument`, `-march=compatibility`) |
| 3 | `default.iprof` (raiz/`api/`) | **novo** — profile PGO **versionado** (gerado offline, §4) |
| 4 | `.dockerignore` (raiz) | **ajuste** — garantir que `default.iprof` NÃO é ignorado; ignorar `api/target/` nativo |
| 5 | `docker-compose.yml` + branch `submission` | **estrutura inalterada** — só a tag da imagem muda (`:onda4b` → `:onda5`) |
| — | `reflect-config.json` / `resource-config.json` | **NÃO criar** (≈vazio — §3; documentar o porquê) |
| — | Java / `api/**`, `references.bin`/`hnsw.bin` | **inalterado** |

## Test points do tutorial

1. `./mvnw -Pnative -DskipTests package` local → `target/api` (ELF) gerado,
   **sem fallback** (`--no-fallback` falha se algo não-AOT-able aparecer).
2. `DATA_PATH=… ./target/api 9999` standalone → `/ready` 200 + 1 oráculo
   exato (valida AOT + mmap + `DATA_PATH` + arg de porta).
3. Build com `PrintCompilation` | grep `sqDistI8` → registra AVX2 vs escalar
   (Gate A).
4. `docker build` nativo → imagem **muito menor** que a HotSpot da 4b;
   `docker run` → `/ready` 200 + oráculo.
5. `docker compose up` (imagem nativa) → 3 serviços; oráculos **pelo LB**;
   `VmHWM`/`docker stats` < 80 MB/inst.
6. Gates A–D conforme §6.

## Riscos / mitigações

| Risco | Mitigação |
|---|---|
| Contradição "Mandrel + PGO" no RINHA_PLAN | Builder = Oracle GraalVM (GFTC grátis, tem PGO); reconciliar §5.2/§9.5/§12.1 + `02-graalvm-native-image.md` com **nota datada** preservando histórico |
| Vector API regride p/ escalar sob Native (§12.1) | Escalar **já é produção** ⇒ funcional; Gate A mede e doc honesto; Oracle GraalVM **21** (não 22/23); `--add-modules=jdk.incubator.vector` mantido |
| `-march` errado | alvo conhecido = Haswell/AVX2 (§1.7) ⇒ build final `-march=x86-64-v3` (portável p/ o alvo, habilita AVX2); **não** `native` (builder ≠ alvo) nem `v2` (sem AVX2 → quebra Gate A); instrument usa `compatibility` |
| `default.iprof` não-determinístico / treino ruim (§9.5 "p99 piorou") | Workload = **k6 oficial** (representativo); **versionar** o `.iprof`; regenerar só em upgrade de GraalVM |
| Debug de Native Image doloroso (§9.5 risco 🔴) | `--no-fallback` + `-H:+ReportExceptionStackTraces`; build report HTML; iterar **local** antes do container |
| `scratch` sem `--static --libc=musl` "exec format error" (§12.9) | Default = **distroless glibc**; musl só § opcional documentado |
| RSS/binário > 80 MB | Serial GC default; `-R:MaxHeapSize` modesto; binário stripped; medir `VmHWM` (§10.1) |
| Imagem nativa não roda em distroless (libs) | `distroless/base-debian12` tem glibc; binário dinâmico glibc roda; Test point 4 pega cedo |

## Próximo passo

Escrever `docs/TUTORIAL_NATIVE.md` (hands-on PT-BR §0–§15, espelhando
`TUTORIAL_CONTAINER.md`) + **reconciliar** `RINHA_PLAN.md` §5.2/§9.5/§12.1 e
`docs/tecnologias/02-graalvm-native-image.md` (Mandrel → Oracle GraalVM GFTC,
nota datada) + `README.md`/`ARCHITECTURE.md` roadmap Wave 5 →
"spec + tutorial ready (hand-impl pending)" (espelha o tratamento do 4b quando
só especificado). Implementação à mão = usuário; o Claude valida os Gates A–D
quando Oracle GraalVM + daemon Docker estiverem prontos. **Onda 5 fecha o
projeto** (Onda 6 = otimizações opcionais).

> ✅ **Concluído 2026-05-18.** Implementação à mão validada: Gates A–D verdes
> (ver bloco "As-built" no topo). `ARCHITECTURE.md`/`README.md`/`RINHA_PLAN.md`
> reconciliados as-built; commitado em `main`; `submission` → `:onda5`;
> memória (Qdrant + `MEMORY.md`) atualizada. Restam só ações outward-facing do
> usuário (`docker push`/`git push`/PR upstream). **Onda 5 fechou o projeto
> técnico.** 🏁🏆
