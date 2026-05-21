# TUTORIAL — Reduzir `references.kdt` <159 MiB (PERFORMANCE_LEDGER §6.2)

> Hands-on PT-BR. Ataca a **hipótese 2 do diagnóstico Onda 20** (mmap eviction).
> Próximo lever após Onda 19 (`cfs_period=20ms`, já pushada em `submission`
> commit `74b0ccd`, aguardando prévia oficial). Pode ser implementado em
> paralelo — se Onda 19 resolver sozinha, esta vira "no longer needed"; se
> não, fica pronto pra disparar.
>
> **Invariantes**: E=0 strict (`ExactAgree` 0-div/54.100), Java/GraalVM
> native-image only (sem FFM/Unsafe/Vector API), HAProxy+2 backends ≤350 MB /
> ≤1.0 CPU, sem `sysctls:` em submission compose.

## §0. Diagnóstico (footprint atual quebrado por componente)

Tamanho real `api/src/main/resources/references.kdt` = **163.254.620 bytes (~155.7 MiB)**, formato **RKD5** (Onda 8 Fase 2b).

| Componente | Tamanho | Onde reside |
|---|---|---|
| Header (`RKD5` + ver+n+dims+stride+root) | 24 B | leitura única no boot |
| `pts` = n × STRIDE × 2B = 3M × 20 × 2 | **120 MB** | **mmap residente** (`MappedByteBuffer`) |
| `origId` = n × 3B (uint24 LE) | **9 MB** | **mmap residente** |
| `meta` (topNodeCount i32) | 4 B | leitura única |
| `topBbox` = topNodeCount × 32 × 2B | **~22 MB** | **on-heap** (lido no boot) |
| `topSlot` = n × 4B | **12 MB** | **on-heap** (lido no boot) |

**Footprint estimado por contêiner api** (cgroup limit por api = **159 MiB = 166.7 MB**):

- mmap residente: pts (120) + origId (9) = **129 MB**
- on-heap reserved (`-R:MaxHeapSize=64m`): **64 MB** (mesmo zero-alloc, é o "ceiling")
- topBbox + topSlot on-heap: **34 MB** (inicial; conta dentro do `-Xmx`)
- GraalVM native runtime + NIO buffers + stack: ~25–35 MB
- **Total realista ≈ 200+ MB**, > 159 MiB

⇒ Kernel **é forçado a evictar páginas mmap sob carga**. Sob k6 ramp 1→900/120s no Mac Mini, requests caem em página fria → page-fault 5–10ms cada → contribui pra cauda do p99 35ms (`ExactAgree` mostra zero variação, então não é algoritmo — só HW + memory pressure).

> ⚠️ A Onda 20 lista DUAS hipóteses para o p99 35ms persistente: (1) CFS
> throttling [Onda 19 ataca], (2) mmap eviction [esta tutorial ataca]. A
> prévia oficial Onda 19 vai dizer quanto da causa é cada uma. Esta lever
> é E=0-safe **em qualquer cenário** (pura compactação lossless + heap shrink).

## §1. Estratégia — TIER 1 lossless conservador

**Decisões travadas** (mais simples primeiro, parar se já bastar):

1. **STRIDE 20 → 19**: drop da lane 19 (PAD, `// pad[19]` em `KdLayout.java:9`). Hoje é desperdiçada — escrita por `KdTreeBuilder` mas nunca lida pelo hot path. Economia: 2 B/node × 3M = **−6 MB no mmap**.

2. **`-R:MaxHeapSize=64m → 48m`**: heap reservado cai 16 MB. Steady-state é zero-alloc (Onda 6 `AllocCheck` 0 B/100k); pico real medido < 16 MB em boot (load do .kdt + topBbox/topSlot). **48 MB tem >2× headroom**; Onda 14 falsificou 32m (OOM no boot) mas 48m fica seguro.

3. Bump formato **RKD5 → RKD6** (magic + version). `isValid` rejeita RKD5 antigos ⇒ `Prebuild` regenera `.kdt` automaticamente no próximo build.

**Reservado pra TIER 2** (só se TIER 1 + Onda 19 ainda deixarem p99 > 20ms):

4. Pack fraud em LANE_RIGHT bit 31, drop LANE_FRAUD ⇒ STRIDE 19→18 ⇒ −6 MB extra (12 total).
5. Mover `topBbox`+`topSlot` pra mmap (sai do heap, vira eviction-eligible mas reduz peak heap).

**Não fazer** (vide ledger §3.x FALSIFIED): FFM/Unsafe pra `mlockall` (sem binding em GraalVM 21), `madvise(HUGEPAGE)` (quebra link), Vector API.

## §2. Implementação

### §2.1 `api/src/main/java/org/fraudDetection/knn/KdLayout.java`

Trocar **uma linha** + comentário:

```java
/** short[] stride per node: 14 dims + leftAndDim(2) + right(2) + fraud(1).
 * RKD6 (Onda 21): drop lane 19 (was unused PAD). STRIDE 20→19; -6 MB mmap. */
public static final int STRIDE = 19;
```

`LANE_LEFT_DIM=14`, `LANE_RIGHT=16`, `LANE_FRAUD=18` permanecem (todos < 19). `STRIDE_BBOX=32` inalterado (topBbox não muda).

### §2.2 `api/src/main/java/org/fraudDetection/knn/KdTreeIO.java`

Bump magic + version:

```java
static final byte[] MAGIC = {'R', 'K', 'D', '6'};
static final int VERSION = 6;
```

Atualizar mensagem de erro em `readAndCheckHeader`: `"bad magic (expected RKD6)"`. Atualizar Javadoc de classe (`RKD6`, comentário do stride). Mais nada — `writeShorts(ch, tree.pts, tree.n * KdLayout.STRIDE)` já reflete o novo STRIDE automaticamente.

### §2.3 `api/src/main/java/org/fraudDetection/knn/KdTreeBuilder.java`

**Sem mudança lógica**. O builder já usa `STRIDE` em todos os offsets — a redução é transparente. Verificar que **nenhum loop assume stride 20 hard-coded** (grep `* 20\b\|* KdLayout.STRIDE`):

```bash
grep -nE '\* 20\b|\bSTRIDE\b' api/src/main/java/org/fraudDetection/knn/*.java
```

Esperado: todas referências via `KdLayout.STRIDE`. Se alguma constante literal 20 aparecer, trocar pra `STRIDE`.

### §2.4 `api/pom.xml` (linha ~95, profile `native`)

```xml
<buildArg>-R:MaxHeapSize=48m</buildArg>
```

(O profile `native-instr` em ~linha 128 NÃO muda — instrumentação precisa de heap mais folgado pra PGO training.)

### §2.5 `api/src/main/resources/references.kdt`

**NÃO commitar o `.kdt` regenerado manualmente.** `Prebuild` detecta RKD5 antigo via `isValid` em `KdTreeIO.java:134-140` e regenera automaticamente no próximo `mvn package` (offline). Custo: ~30–60s no host (3M nodes, deterministic seed 42).

Se quiser forçar regeneração local antes do build:

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
rm api/src/main/resources/references.kdt api/target/classes/references.kdt
cd api && ./mvnw -q -DskipTests -pl . exec:java \
  -Dexec.mainClass=org.fraudDetection.tools.Prebuild \
  -Dexec.args="src/main/resources"
ls -lh src/main/resources/references.kdt   # esperado: ~150 MB (vs 156 MB RKD5)
```

Tamanho esperado novo: header (24) + pts (3M×19×2 = 114 MB) + origId (9 MB) + meta (4) + topBbox (~22 MB) + topSlot (12 MB) = **~157 MB → ~150 MiB** (vs **155.7 MiB** RKD5).

## §3. Build & Gates

### G1 — Build limpo + Prebuild + native image

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
./api/mvnw -q -DskipTests -f api/pom.xml clean   # zera target/, força Prebuild
./api/mvnw -q -DskipTests -f api/pom.xml package  # gera .kdt RKD6 + jar
./api/mvnw -q -DskipTests -f api/pom.xml -Pnative package 2>&1 | tee /tmp/build-onda21.log
grep -E "fallback|Error|FAIL" /tmp/build-onda21.log   # esperado: nada
ls -lh api/target/api                            # ELF, ~12 MB stripped
```

Build do native deve **manter ~12 MB binário** (heap shrink não muda code size).

### G2 — ExactAgree (E=0 invariante, BLOQUEIA)

**O gate mais crítico**: bump RKD5→RKD6 + STRIDE mudança = qualquer off-by-one no I/O quebra a árvore. ExactAgree compara TopK do tree mmap vs brute-force vs `expected_*` (ground truth).

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection/api
./mvnw -q -Dtest=ExactAgree -Dargs="54100 5000" test
# esperado: 0 mismatches; tree==brute==expected; maxHeap ≤ 1024, maxPool ≤ 1024
```

Qualquer mismatch ⇒ **revert imediato** (regra ledger §7).

### G3 — AllocCheckKd (zero-alloc, BLOQUEIA)

```bash
./mvnw -q -Dtest=AllocCheckKd -Dargs="100000" test
# esperado: <16 B/query (ideal 0)
```

Heap shrink não deve mexer (zero-alloc steady é compile-time + escape analysis); se quebrar é sinal de regressão.

### G4 — VisitsReplay (algoritmo inalterado)

```bash
./mvnw -q -Dtest=VisitsReplay -Dargs="54100" test
# esperado: prime/bbf/descend visits IDÊNTICOS ao baseline (RKD5)
```

Drop de pad lane não afeta nenhuma decisão de split/visit/descend.

### G5 — Build da imagem Docker + cgroup compliance

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
docker build -t arthurd3/rinha-fraud:onda21 . 2>&1 | tee /tmp/dbuild-onda21.log
docker images arthurd3/rinha-fraud:onda21      # tamanho ~12 MB (binário) + 150 MB (.kdt) = ~166 MB
```

### G6 — Calib 3 trials sob host quiet

```bash
# Host quiet check
uptime                                          # load avg < 1.0
free -h                                         # swap used < 1 GB

# Editar docker-compose.yml local (main) para usar :onda21 nas 2 apis
# (NÃO mexer em submission compose ainda)

cd /home/arthurd3/Desktop/RINHA-BECK-END/rinha-de-backend-2026
for t in 1 2 3; do
  docker compose --compatibility up -d
  for i in $(seq 1 20); do c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 \
    http://localhost:9999/ready); [ "$c" = 200 ] && break; sleep 3; done
  echo "trial $t ready=$c"
  ./run.sh
  cp test/results.json /tmp/rig-onda21-t${t}.json
  docker compose --compatibility down
  sleep 10
done

# Análise
for t in 1 2 3; do
  echo "--- trial $t ---"
  jq '.final_score, .p99_score, .checks.detection.percent, .checks.requests.values.p99' \
    /tmp/rig-onda21-t${t}.json
done
```

**Critério de win (ledger §7)**:
- **Win**: mediana `final ≥ baseline +3%` E `p99 ≤ baseline` (baseline calib = ~4500/27ms).
- **No-op**: dentro do noise. Revert + ledger §3.9 "FALSIFIED isolated".
- **Regress**: mediana pior. Revert + capturar dados + ledger §3.9.

Sob calib quiet, esperar `final ≥ 4640` e `p99 ≤ 25ms`. A magnitude REAL do ganho só será visível no Mac Mini (calib não satura mmap eviction pq host tem RAM sobrando — limite cgroup é simulado mas page cache do host inteiro está disponível pra read; Mac Mini 8 GB físicos com 4 contêineres + OS opera mais perto do limite real).

## §4. Decisão & ship Mac Mini

Se G6 = **Win** ou **No-op-com-confiança** (p99 estável + footprint comprovadamente menor + RSS < 159 MiB no `docker stats`):

```bash
# 1. Commit code change em main
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
git add api/src/main/java/org/fraudDetection/knn/KdLayout.java \
        api/src/main/java/org/fraudDetection/knn/KdTreeIO.java \
        api/pom.xml
git -c user.name=arthurd3 -c user.email=arthurcamp.ssl@gmail.com \
  commit -m "feat: Onda 21 — STRIDE 20→19 + heap 48m (RKD6, -22MB cgroup pressure)"

# 2. (Opcional) commit do .kdt regenerado se quiser cachear na image build
git add api/src/main/resources/references.kdt
git -c user.name=arthurd3 -c user.email=arthurcamp.ssl@gmail.com \
  commit -m "chore: regenerate references.kdt RKD6 (~150 MiB, -6 MiB vs RKD5)"

# 3. Push image
docker push arthurd3/rinha-fraud:onda21        # outward-facing — usuário decide

# 4. Bump submission compose
git checkout submission
# Editar docker-compose.yml: trocar :onda15 (api-1 + api-2) → :onda21
git add docker-compose.yml
git -c user.name=arthurd3 -c user.email=arthurcamp.ssl@gmail.com \
  commit -m "submission: bump :onda15 → :onda21 (RKD6 footprint -22MB cgroup pressure)"
git push origin submission                     # outward-facing

# 5. Abrir/atualizar issue rinha/test arthurd3-java-hnsw  — outward-facing
```

Aguardar prévia Mac Mini. Predição (se hipótese 2 valer):
- p99 cai de 35ms → 20–28ms (eviction reduz, page-fault tail diminui)
- final_score sobe de 4456 → ~5000–5400
- E=0 inalterado (mudança puramente lossless)

## §5. TIER 2 (reservado)

**Aplicar SOMENTE se TIER 1 + Onda 19 juntos ainda deixarem p99 > 20ms.** São mudanças mais frágeis (mais código tocado, mais risco de regressão E=0).

### Pack fraud em LANE_RIGHT bit 31

`KdLayout`: STRIDE 19→18, REMOVER LANE_FRAUD, novas funções:

```java
public static int packRightAndFraud(int right, int fraudBit) {
    // right indices < 3M < 2^24; sentinel -1 → 0xFFFFFFFF colide com fraudBit.
    // Usar bit 30 (right cabe em 22 bits = 4M; sobram 8 bits livres).
    return (right & 0x3FFFFFFF) | ((fraudBit & 1) << 30);
}
public static int unpackRight(int rf) { return (rf << 2) >> 2; }  // sign-extend bit 29
public static int unpackFraud(int rf) { return (rf >>> 30) & 1; }
```

`KdTreeBuilder.buildRecursive`: substituir gravação separada de `LANE_FRAUD` + `LANE_RIGHT` por:

```java
int rf = KdLayout.packRightAndFraud(rightIdx, srcFraud[srcId] ? 1 : 0);
pts[treeIdx * STRIDE + KdLayout.LANE_RIGHT] = (short) (rf & 0xFFFF);
pts[treeIdx * STRIDE + KdLayout.LANE_RIGHT + 1] = (short) ((rf >>> 16) & 0xFFFF);
```

`KdTree.fraudBit`: ler do LANE_RIGHT em vez de LANE_FRAUD:

```java
public int fraudBit(int treeIdx) {
    int rf = (pts != null)
        ? ((pts[treeIdx*STRIDE + LANE_RIGHT] & 0xFFFF) | ((pts[treeIdx*STRIDE + LANE_RIGHT + 1] & 0xFFFF) << 16))
        : ptsBuf.getInt((treeIdx*STRIDE + LANE_RIGHT) * 2);
    return KdLayout.unpackFraud(rf);
}
```

`KdTree.rightAt`: usar `unpackRight` em vez de raw int:

```java
private int rightAt(int treeIdx) {
    int rf = ...; // same as above
    return KdLayout.unpackRight(rf);
}
```

`KdTreeBuilder.relayoutBlocked`: idem (renumeração — substituir cópia de LANE_FRAUD por re-empacote).

Bump RKD6 → **RKD7**. `ExactAgree` é o único guarda — qualquer off-by-one mata.

Economia adicional: -6 MB mmap (STRIDE 18 → 36B/node × 3M = 108 MB pts).

## §6. Pegadinhas (do histórico do projeto)

- **Build LIMPO**: `./mvnw -f api/pom.xml clean package` (incremental pode mascarar; ledger lição da Onda 4b).
- **ExactAgree / AllocCheckKd / VisitsReplay** rodam de `fraudDetection/api` (paths relativos a `src/main/resources/references.kdt`).
- **`docker compose --compatibility`** obrigatório p/ aplicar 350M/1CPU reais (sem isso, cpus/memory são hints).
- **snap docker**: `--format … | cat` (tabela engole em não-TTY); rodar build/compose sob `$HOME` (confinamento snap); `docker build … 2>&1 | cat` (redirecionar em fg) ou `… 2>&1 | cat > log` (bg longo).
- **Capturar baseline ANTES** do rebuild (golden oracle); sem `set -u`; matar :9999 por PID da porta.
- **Sem atribuição Claude nos commits**; identidade `arthurd3`; `docker push`/`git push`/issue Rinha = outward-facing do usuário.
- **PGO**: a redução de STRIDE muda layout de `pts[]` em memória ⇒ PGO `default.iprof` continua válido (branch frequencies dependem da ordem de visita, não do tamanho do node). Lição Onda 16: PGO regen ISOLADO não compensou — não rebuild PGO só por isso. Se TIER 2 entrar (mudanças em `fraudBit`/`rightAt`), aí sim considerar regen.
- **Format bump**: `KdTreeIO.isValid` é o portão — sem MAGIC novo, Prebuild não regenera automaticamente, e a app falha no boot com "bad magic".

## §7. Reconciliação as-built

Ao fechar (Mac Mini preview confirmado win):

1. **`docs/PERFORMANCE_LEDGER.md`**:
   - §0: bump "Production: image `:onda21`", linha "Last preview ... `:onda21`".
   - §2: nova linha tabela com Onda 21 (commit, image, mudança, calib final/p99, Mac Mini, ship).
   - §3.8 e §3.9: mover ".kdt footprint reduction RKD6" de OPEN → WINNERS.
   - §6: riscar `~~§6.2~~`; promover próximo lever (provavelmente §6.3 `tune.maxaccept=64` ou §6.5 `cfs_period=10ms` se §6.1 já tiver fechado).
   - §8: nova edição datada documentando resultado + lição.
2. **`docs/ARCHITECTURE.md`** + **`README.md`** + **`docs/RINHA_PLAN.md`** + **`docs/IMPACTO.md`**: linha as-built atualizada (`.kdt` ~150 MiB, RKD6, heap 48m).
3. **Auto-memory**: nova entry com p99 final, ganho atribuído, lição reusable.

**Branch `submission`**: bump compose image tag para `:onda21`. Não mudar `cpu_period`/`cpu_quota` (deixar Onda 19 em vigor a menos que tenha sido falsificada).

---

**Onda 21 = corrida pelo p99 continua; sem "fim" enquanto houver gap p/ top-10 e ganho claro.**
