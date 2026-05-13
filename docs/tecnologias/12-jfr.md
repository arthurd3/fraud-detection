# JFR — Java Flight Recorder

**Categoria**: Profiling / observability (HotSpot)
**Versão usada na Rinha**: built-in do Java 21
**Decisão rápida**: ferramenta de medição interna

---

## O que é

JFR (Java Flight Recorder) é o profiler **embutido no HotSpot JVM**. Coleta eventos detalhados (allocation, GC, lock contention, CPU time, I/O) com **overhead minúsculo** (~1-2%). Originalmente proprietário (Oracle JDK), open-sourced em Java 11 (2018).

Coleta dados via **eventos** internos da JVM — não amostragem externa. Salva em arquivo `.jfr` que pode ser analisado com **JDK Mission Control (JMC)** ou via CLI (`jfr print`).

## Objetivo geral

Permitir **profiling em produção** sem impacto significativo. Antes do JFR, profiling em produção era arriscado (overhead alto, risco de crash). JFR é seguro o suficiente para rodar 24/7 em apps live.

Use cases:
1. **Identificar gargalos**: qual método está consumindo CPU?
2. **GC behavior**: quanta pausa? qual geração?
3. **Allocation profiling**: onde está alocando memória?
4. **Lock contention**: thread está bloqueada esperando lock?
5. **I/O**: qual operação está bloqueando?

## Pra que vamos usar no projeto

Validar zero-allocation hot path (Onda 1+):

```bash
java -XX:StartFlightRecording=duration=30s,filename=fraud.jfr -jar fraudAPI.jar 9999

# k6 roda contra... 30s depois...
jfr print --events jdk.ObjectAllocationInNewTLAB fraud.jfr | head -20
```

Se aparecerem alocações em `FraudController.handle()`, há `new` no hot path — **bug**.

Outros usos:
- GC pause durante load test → `jdk.GarbageCollection`.
- CPU hotspots → `jdk.ExecutionSample`.
- Thread blocked → `jdk.JavaMonitorWait`.

JFR só funciona em **HotSpot**. Em Native Image, usar `perf` (ver [13-perf.md](13-perf.md)).

## Como funciona (em profundidade)

### Eventos JFR

JFR define ~150 tipos de eventos. Categorias:
- **JVM internals**: GC, JIT compilation, class loading.
- **OS events**: CPU usage, network, disk I/O.
- **Java app**: object allocation, exceptions, locks.
- **Custom events**: você pode definir os seus.

### Iniciar gravação

3 maneiras:

**A) Linha de comando (-XX flag)**:
```bash
java -XX:StartFlightRecording=duration=30s,filename=app.jfr,settings=profile -jar app.jar
```

**B) `jcmd` (durante execução)**:
```bash
jcmd <PID> JFR.start name=test duration=30s filename=app.jfr settings=profile
jcmd <PID> JFR.stop name=test
```

**C) JMC (UI)**: clica e arrasta.

### Settings

- `default` (overhead ~0.5%): subset básico, OK pra produção.
- `profile` (overhead ~1-2%): mais eventos, ideal para debug.
- Custom `.jfc` XML: você define.

### Análise

**JMC (JDK Mission Control)** é a UI oficial. Download: https://www.oracle.com/java/technologies/jdk-mission-control.html

Tabs:
- **Java Application**: heap, threads, exceptions.
- **Memory**: GC overhead, pauses, allocations por classe.
- **Code**: hot methods (CPU sampling).
- **Threads**: lock contention, blocking.

**CLI**:
```bash
# Listar eventos
jfr metadata app.jfr

# Print eventos
jfr print --events jdk.GarbageCollection app.jfr
jfr print --events jdk.ExecutionSample --json app.jfr

# Resumo
jfr summary app.jfr
```

### Eventos críticos para a Rinha

| Evento | O que mostra | Quando consultar |
|---|---|---|
| `jdk.ExecutionSample` | CPU time per method | Hot path identification |
| `jdk.ObjectAllocationInNewTLAB` | Alocações grandes | Validar zero-allocation |
| `jdk.ObjectAllocationOutsideTLAB` | Alocações enormes | Idem |
| `jdk.GarbageCollection` | GC pauses (start, duration, type) | Tail latency |
| `jdk.GCPhasePause` | Cada fase do GC | Detalhe GC |
| `jdk.JavaMonitorWait` | Thread esperando lock | Concurrency issues |
| `jdk.SocketRead`/`SocketWrite` | I/O blocking | Latência de rede |
| `jdk.CPULoad` | Uso de CPU | Saturação |
| `jdk.JITCompilation` | Compilação C1/C2 | Warmup |

### Custom event

```java
@Name("FraudScoreCompute")
@Label("Fraud score computation")
@Category({"Application", "Custom"})
public class FraudScoreEvent extends Event {
    @Label("Fraud Score") public float score;
    @Label("Approved") public boolean approved;
}

// uso:
FraudScoreEvent e = new FraudScoreEvent();
e.begin();
// ... cálculo
e.score = fraudScore;
e.approved = approved;
e.commit();
```

## Exemplo de uso

```bash
# Gravar 30s
java -XX:StartFlightRecording=duration=30s,filename=fraud.jfr,settings=profile \
     -jar target/fraudAPI.jar 9999

# (em outro terminal) gerar carga
k6 run rinha-de-backend-2026/test/test.js

# Espera os 30s, encerra. Abre fraud.jfr em JMC:
jmc fraud.jfr

# Ou via CLI
jfr summary fraud.jfr
jfr print --events jdk.GarbageCollection fraud.jfr | head -10
```

Para profile contínuo (rolling logs):
```bash
java -XX:StartFlightRecording=settings=default,maxsize=100MB,maxage=1h,filename=fraud.jfr -jar app.jar
```

## Tecnologias parecidas (alternativas)

| Ferramenta | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **async-profiler** | Suporta Native Image, flamegraph nativo | Requer install, não tem GC events ricos | Quando precisa de Native ou flamegraph |
| **perf** (Linux) | Funciona em qualquer binário | Sem visibilidade Java específica | Native Image, kernel events |
| **VisualVM** | UI livre, funciona com JMX | Sampling-based (overhead maior) | Dev local |
| **YourKit** | UI poderosa, allocation tracking detalhado | Pago | Profiling corporativo |
| **JProfiler** | Análise visual rica | Pago | Idem YourKit |
| **eBPF / bpftrace** | Kernel-level, observabilidade total | Aprendizado, requer Linux moderno | Performance engineering profundo |
| **Honest Profiler** | Sem safepoint bias | Maintenance limited | Quando JFR sample é viesado |
| **`-Xlog:gc`** | Built-in, simples | Só GC, formato textual | Análise GC rápida |

Para produção: **JFR** ou **async-profiler** (mais completo). Para Rinha (~120s): JFR é suficiente em HotSpot, perf em Native.

## Pegadinhas conhecidas

1. **Native Image não suporta JFR** (até 22; em 23+ tem suporte parcial). Use perf em Native.
2. **Safepoint bias em sampling**: `jdk.ExecutionSample` amostra em safepoints, não em qualquer instante. Pode mascarar hot loops sem safepoint check. async-profiler é mais preciso.
3. **`settings=profile` adiciona ~1-2% overhead**: aceitável em prod, mas em benchmarks de microsegundos pode mascarar.
4. **Arquivos crescem**: `default` ~10 MB/min, `profile` ~30 MB/min. Use `maxsize=100MB`.
5. **JMC requer Java 11+**: instalar separado se sistema só tem JDK 8.
6. **`jcmd` precisa do mesmo user/JVM**: rodando em Docker container, `docker exec` antes.
7. **Eventos custom**: classe precisa estar no classpath. Em Native Image, registrar no `reflect-config.json`.

## Referências

- **JEP 328 (JFR open source)**: https://openjdk.org/jeps/328
- **JFR docs Oracle**: https://docs.oracle.com/en/java/javase/21/jfapi/index.html
- **Tutorial JFR + JMC**: https://docs.oracle.com/javacomponents/jmc-5-4/jfr-runtime-guide/about.htm
- **JDK Mission Control**: https://www.oracle.com/java/technologies/jdk-mission-control.html
- **Awesome JFR**: https://github.com/amaembo/awesome-jfr
- **JFR talk (Marcus Hirt)**: https://www.youtube.com/results?search_query=marcus+hirt+jfr
- **`jfr` CLI cheatsheet**: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html

## Veredito final na Rinha

Validar zero-allocation hot path em Onda 1-3 (HotSpot). Quando passar para Native (Onda 5), trocar para perf.
