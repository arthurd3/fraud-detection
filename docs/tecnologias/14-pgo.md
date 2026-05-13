# PGO — Profile-Guided Optimization

**Categoria**: Otimização de compilador
**Versão usada na Rinha**: PGO do GraalVM Native Image 21
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.2 (parte de Native Image)

---

## O que é

PGO (Profile-Guided Optimization) é uma técnica de compilação onde o **compilador usa profile real de execução** para tomar decisões de otimização. Em vez de heurísticas estáticas ("loops geralmente iteram muito"), usa dados reais do app rodando.

Existe em vários compiladores:
- **GraalVM Native Image** (`--pgo` / `--pgo-instrument`).
- **GCC** (`-fprofile-generate` / `-fprofile-use`).
- **Clang/LLVM** (`-fprofile-instr-generate` / `-fprofile-instr-use`).
- **Go** (`-pgo` em 1.21+).
- **MSVC** (`/GENPROFILE` / `/USEPROFILE`).

## Objetivo geral

Compiladores AOT tomam decisões sem profile dinâmico:
- **Branch prediction hints**: qual branch é mais comum?
- **Inlining cirúrgico**: inlinar função quente vale, fria não.
- **Code layout**: blocos quentes contíguos minimizam I-cache miss.
- **Loop unrolling**: depende da contagem típica.
- **Devirtualization**: se profile mostra sempre mesma classe, devirtualiza.

JIT (HotSpot C2) faz isso em runtime — observa, otimiza. AOT (Native Image) compila uma vez sem essa info → perde otimizações. **PGO restaura**.

## Pra que vamos usar no projeto

**Onda 5 do roadmap**, depois de validar Native Image básico funciona.

Workflow:
1. Build instrumentado: `native-image --pgo-instrument -jar app.jar`.
2. Treinar: rodar k6 oficial contra o binário instrumentado. Coleta profile real.
3. Build final: `native-image --pgo=default.iprof -jar app.jar`.

Ganho: **+10 a +30% throughput**, **-5 a -15% p99 tail**. Em código com hot path bem definido (a Rinha), o ganho é maior.

Alvo: tirar Native Image de "+5500" para "+5800" no score final.

## Como funciona (em profundidade)

### 3 fases

```
[1] BUILD INSTRUMENTADO
    native-image --pgo-instrument -jar app.jar -o app-instr
    
    Binário pesado: cada branch/edge/method tem contador.
    Roda mais lento (~30%) por causa da instrumentação.

[2] TREINO (run com workload representativo)
    ./app-instr &
    k6 run rinha-de-backend-2026/test/test.js
    kill -SIGINT $!  # SIGINT escreve default.iprof ao sair
    
    default.iprof contém: contagem por branch, por edge, por método.

[3] BUILD FINAL
    native-image --pgo=default.iprof -jar app.jar -o app-pgo
    
    Compilador lê o profile e:
    - Reordena blocos (quentes primeiro).
    - Inlina apenas métodos chamados muito.
    - Coloca branch comum como fall-through (sem jump).
    - Aplica hints na branch prediction.
```

### Decisões que PGO melhora

**Branch hints**:
```c
if (request.contentLength > 0) {       // QUASE SEMPRE TRUE
    parse_body();
} else {
    return;                             // raro
}
```

Sem PGO: 50/50 hint, igual chance de mispredict.
Com PGO: compilador aprende que `> 0` é 99%, gera código fall-through nesse caminho.

**Inlining**:
```c
double clamp(double x) { return Math.max(0, Math.min(1, x)); }

queryVector[0] = clamp(amount / 10000);
queryVector[1] = clamp(installments / 12);
// ... 14 vezes
```

PGO vê `clamp` chamado 14× por request → inlina. Sem PGO, decisão estática (talvez não inlina).

**Code layout**:
```
[hot] FraudController.handle()         ← quente
[hot] FraudRequestParser.parse()       ← quente
[hot] HnswIndex.search()               ← quente
[cold] error handler                    ← raro
```

PGO coloca os hot juntos no binário. I-cache hit rate sobe.

### Workload representativo

**Crítico**: profile gerado com workload errado pode **piorar** decisões.

Ex: se você treinar com requests só de `/ready`, PGO vai otimizar para isso e não para `/fraud-score`. Sempre treinar com **workload de produção** (k6 oficial na Rinha).

### Tamanho de profile

`default.iprof` típico: 1-10 MB. Cabe em git (mas não é hábito commitar — gerar no CI).

### Versionamento

Profile é **specifico da versão do compilador**. Profile gerado com Mandrel 21.0.4 não funciona em 21.0.5. Sempre regerar quando atualizar.

## Exemplo de uso

```bash
# === Onda 5 com PGO ===

# Step 1: build instrumentado
./mvnw -Pnative -DskipTests package
native-image --pgo-instrument \
  --enable-preview --add-modules jdk.incubator.vector \
  -jar target/fraudAPI.jar -o fraud-instr

# Step 2: treino
./fraud-instr 9000 &
INSTR_PID=$!
sleep 2
k6 run rinha-de-backend-2026/test/test.js
kill -SIGINT $INSTR_PID
wait $INSTR_PID
ls default.iprof   # confirma profile gerado

# Step 3: build final com PGO
native-image --pgo=default.iprof \
  --enable-preview --add-modules jdk.incubator.vector \
  -jar target/fraudAPI.jar -o fraud-pgo

# Step 4: rodar e medir
./fraud-pgo 9000 &
k6 run rinha-de-backend-2026/test/test.js
# Comparar p99, throughput vs build sem PGO
```

Em Dockerfile, PGO em multi-stage:
```Dockerfile
FROM ghcr.io/graalvm/native-image-community:21 AS pgo-instr
COPY . .
RUN ./mvnw -Pnative package -Dnative.image.args="--pgo-instrument"

FROM eclipse-temurin:21 AS pgo-train
COPY --from=pgo-instr /app/target/fraud-api-instr /app
RUN /app & sleep 2 && k6 run test.js && kill %1

FROM ghcr.io/graalvm/native-image-community:21 AS pgo-final
COPY --from=pgo-train /default.iprof .
COPY . .
RUN ./mvnw -Pnative package -Dnative.image.args="--pgo=default.iprof"
```

## Tecnologias parecidas (alternativas)

| Tecnologia | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **JIT do HotSpot (C2)** | Profile dinâmico contínuo (AppCDS, OSR) | Custo de warmup | App long-running com workload variável |
| **AppCDS / CDS** | Acelera startup pré-carregando classes | Não otimiza código | Reduzir startup HotSpot |
| **AOT compilation sem PGO** | Sem ciclo de build duplo | Pior throughput (~10-30%) | Quando build pipeline já é apertado |
| **LLVM PGO** | Funciona em C/C++/Rust | Não é Java | Apps nativas non-Java |
| **GCC PGO** | Familiar, bem documentado | Não é Java | Apps C/C++ |
| **Go PGO** (1.21+) | Recente, integrado ao toolchain | Apenas Go | Apps Go |

PGO é **complementar** a outras técnicas, não substitui. Combina com `--gc=serial`, `--enable-preview`, etc.

## Pegadinhas conhecidas

1. **Workload de treino não-representativo** = PGO **piora** o app. Sempre treinar com cenário real (k6 oficial).
2. **Build duplo demora**: 2 builds Native (~2-10 min cada) + treino. CI lento.
3. **Profile size**: profile pode crescer com workload longo. Treine 30-120s, não horas.
4. **Versão do compilador**: profile não é portável entre versões.
5. **Cache de build**: docker layer cache não trivial com PGO (3 stages diferentes). Considerar pre-built image.
6. **`SIGINT` para flush**: `default.iprof` só é escrito em SIGINT/SIGTERM. `kill -9` perde tudo.
7. **Mais binário, mais I-cache**: PGO inlina mais → binário cresce ~5-15%. Em sistemas memory-constrained, balancear.
8. **Determinismo**: dois treinos com mesmo workload geram profiles ligeiramente diferentes (ordem de threads, timing). Builds não-determinísticos.

## Referências

- **GraalVM PGO docs**: https://www.graalvm.org/jdk21/reference-manual/native-image/optimizations-and-performance/PGO/
- **PGO blog post (Oracle)**: https://www.graalvm.org/22.3/reference-manual/native-image/optimizations-and-performance/PGO/
- **GCC PGO**: https://gcc.gnu.org/onlinedocs/gcc/Instrumentation-Options.html
- **LLVM PGO**: https://llvm.org/docs/HowToBuildWithPGO.html
- **Go PGO**: https://go.dev/doc/pgo
- **Microsoft Profile-Guided Optimization**: https://learn.microsoft.com/en-us/cpp/build/profile-guided-optimizations
- **Caso de uso PGO em Quarkus**: https://quarkus.io/guides/building-native-image (seção PGO)
- **Performance comparison sem/com PGO** (Mandrel team): https://github.com/graalvm/mandrel/discussions

## Veredito final na Rinha

Onda 5 obrigatória se for atrás de "+5800". Treinar com `k6 run test/test.js` direto. Build final em CI Docker.
