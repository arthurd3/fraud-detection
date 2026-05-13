# JMH — Java Microbenchmark Harness

**Categoria**: Microbenchmark
**Versão usada na Rinha**: 1.37
**Decisão rápida**: ferramenta de medição interna

---

## O que é

JMH é uma framework para escrever **microbenchmarks** em Java/JVM. Mantida pelo time OpenJDK (mesmos que fazem HotSpot/C2) para garantir que mede com precisão **apesar das otimizações dinâmicas da JVM**.

JMH resolve dois problemas que `System.nanoTime()` ingênuo não resolve:
1. **Dead code elimination**: se você não usa o resultado, C2 elimina o cálculo.
2. **Loop unrolling / inlining**: medições mudam baseadas em fatores externos.

## Objetivo geral

Medir performance de **código pequeno** (funções, loops, kernels) com confiança estatística. Ideal para:

- Comparar duas implementações de uma função (ex: brute force vs SIMD).
- Otimizações de hot path (parsing, hash, distance).
- Avaliar impacto de flag JVM (`-XX:+UseG1GC` vs ZGC).

Diferente de k6 (que mede **app inteiro** sob carga), JMH mede **um método** isoladamente.

## Pra que vamos usar no projeto

Onda 2 tem JMH benchmark obrigatório:

```java
@Benchmark
public int distanceFloat32Scalar(BenchmarkState state) {
    return DistanceFunctions.euclideanFloat32Scalar(state.a, state.b);
}

@Benchmark
public int distanceInt8Simd(BenchmarkState state) {
    return DistanceFunctions.euclideanInt8(state.aInt8, state.bInt8);
}
```

Critério de saída da Onda 2: speedup SIMD ≥ 4× vs scalar float32.

Outros usos:
- Parser HTTP byte-array vs `com.sun.net.httpserver` parsing (Onda 1).
- HNSW search vs brute force (Onda 3).
- Comparar HotSpot vs Native Image em mesmo método.

## Como funciona (em profundidade)

### Anatomia de um benchmark

```java
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)        // mede tempo médio por op
@OutputTimeUnit(TimeUnit.NANOSECONDS)   // unidade
@State(Scope.Benchmark)                 // estado compartilhado entre iterações
@Warmup(iterations = 5, time = 1)       // 5 rodadas de warmup
@Measurement(iterations = 10, time = 2) // 10 rodadas de medição
@Fork(1)                                 // 1 processo JVM separado
public class DistanceBenchmark {
    
    @Param({"14"})
    public int dim;
    
    public byte[] a, b;
    
    @Setup
    public void setup() {
        a = new byte[dim];
        b = new byte[dim];
        new Random(42).nextBytes(a);
        new Random(43).nextBytes(b);
    }
    
    @Benchmark
    public int scalar() {
        return DistanceFunctions.euclideanInt8Scalar(a, b);
    }
    
    @Benchmark
    public int simd() {
        return DistanceFunctions.euclideanInt8(a, b);
    }
}
```

### Modes

| Mode | O que mede | Unit típico |
|---|---|---|
| `Throughput` | Ops/segundo | ops/s |
| `AverageTime` | Tempo médio por op | ns ou µs |
| `SampleTime` | Distribuição (p50, p99) | ns |
| `SingleShotTime` | Uma execução só (cold) | ms |
| `All` | Todos os modes |

### Forks

Cada `@Fork(N)` cria N JVMs separadas (sequenciais). Isolamento entre iterações. `@Fork(0)` no IDE para debug.

### Warmup vs Measurement

- **Warmup**: rodadas que NÃO entram no resultado. Deixa C2 compilar, JIT estabilizar, page cache esquentar.
- **Measurement**: rodadas que entram no resultado.

### Blackhole — anti-DCE

```java
@Benchmark
public void noBlackhole() {
    DistanceFunctions.euclideanInt8(a, b);  // C2 pode eliminar (resultado não usado)
}

@Benchmark
public int withReturn() {
    return DistanceFunctions.euclideanInt8(a, b);  // resultado consumido por JMH
}

@Benchmark
public void withBlackhole(Blackhole bh) {
    bh.consume(DistanceFunctions.euclideanInt8(a, b));  // explícito
}
```

### Resultado típico

```
Benchmark                    (dim)  Mode  Cnt    Score    Error  Units
DistanceBenchmark.scalar        14  avgt   10   65.234 ±  1.234  ns/op
DistanceBenchmark.simd          14  avgt   10    8.123 ±  0.234  ns/op
```

Speedup: 65.2 / 8.1 ≈ **8×**. Aprovado!

### Profilers integrados

```bash
java -jar benchmarks.jar -prof gc          # GC pause analysis
java -jar benchmarks.jar -prof stack       # CPU time per method
java -jar benchmarks.jar -prof perfasm     # assembly do método (Linux)
```

## Exemplo de uso

`pom.xml`:
```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>provided</scope>
</dependency>
```

```bash
# Build
./mvnw clean package -Pjmh

# Run all benchmarks
java -jar target/benchmarks.jar

# Run específico
java -jar target/benchmarks.jar DistanceBenchmark

# Output JSON
java -jar target/benchmarks.jar -rf json -rff results.json
```

## Tecnologias parecidas (alternativas)

| Ferramenta | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **Manual `System.nanoTime()`** | Sem dependência | Falha em DCE, sem warmup, sem stats | Sanity check rápido (não confiar em números) |
| **Caliper** (Google) | Similar a JMH | Manutenção parou (~2014) | — |
| **Async-profiler** | Profile, não benchmark | Não dá tempo médio comparativo | Identificar gargalo, não comparar |
| **JFR (Flight Recorder)** | Profile detalhado | Não automatiza N rodadas | Investigação ad-hoc |
| **gradle-bench** (Gradle) | Integra com Gradle | Wrapper sobre JMH | Projetos Gradle |
| **JUnit `@Test` com timing** | Simples | Sem rigor estatístico | Smoke test |

Para microbench em Java, **JMH é o padrão da indústria**. Sem alternativa próxima.

Para outras linguagens:
- C/C++: Google Benchmark.
- Rust: Criterion.
- Python: pytest-benchmark.
- Go: built-in `testing.B`.

## Pegadinhas conhecidas

1. **Sem warmup**: número incluiria interpretação Java + C1, totalmente diferente do steady-state.
2. **Estado compartilhado entre iterações**: thread safety, cache hot. `@State(Scope.Thread)` para per-thread.
3. **`-Xms` e `-Xmx`** baixos podem causar GC durante medição. Setar grande no benchmark (`@Fork(jvmArgs = "-Xmx2g")`).
4. **Resultado escalado errado**: 8 ns/op em método inline pode estar reportando 0 (C2 eliminou). Sempre validar com `-prof perfasm`.
5. **Variação ambiental**: outras CPUs ocupadas, `cpu_freq` (turbo boost), thermal throttling. Rodar em máquina dedicada se possível.
6. **Native Image não suporta JMH**: bench só roda em HotSpot. Para validar Native, comparar k6 antes/depois.
7. **Benchmark não-deterministic**: cache state, branch predictor. Múltiplos forks ajudam.
8. **JMH `-prof perfasm` requer `perf`** (Linux). Em Mac, alternativa é `dtrace`.

## Referências

- **Site oficial**: https://github.com/openjdk/jmh
- **Tutorial OpenJDK**: https://github.com/openjdk/jmh/tree/master/jmh-samples
- **Paper "Accuracy in JMH"** (Aleksey Shipilëv): https://shipilev.net/blog/2014/nanotrusting-nanotime/
- **Avoiding Pitfalls**: https://shipilev.net/blog/2014/jmh-the-art-of-benchmarking/
- **Vector API benchmarks** (Richard Startin): https://richardstartin.github.io/posts/
- **Maven plugin**: https://github.com/melix/jmh-gradle-plugin
- **JMH Visualizer**: https://jmh.morethan.io/

## Veredito final na Rinha

Padrão para validar SIMD speedup (Onda 2). Roda em `./mvnw test -Pjmh`. Resultado vai em `docs/profiles/`.
