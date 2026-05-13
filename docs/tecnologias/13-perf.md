# perf (Linux)

**Categoria**: Profiling kernel-level
**Versão usada na Rinha**: `linux-perf` (Ubuntu)
**Decisão rápida**: ferramenta de medição interna (Native Image)

---

## O que é

`perf` é a ferramenta nativa de profiling do Linux. Acessa **performance counters** da CPU (PMU — Performance Monitoring Unit) e **eventos do kernel** via `perf_events` interface. Funciona em **qualquer binário** — Java HotSpot, Native Image, C, Rust, Go, Python.

Distribuído com o kernel. Em Ubuntu, pacote `linux-tools-generic` ou `linux-tools-$(uname -r)`.

## Objetivo geral

Profiling **agnóstico de linguagem**, **kernel-aware**:

1. **CPU sampling**: qual função consome mais CPU? (com call stacks).
2. **Hardware counters**: cache misses, branch mispredictions, instructions per cycle.
3. **Kernel events**: syscall, page fault, context switch.
4. **Tracing**: histórico exato de execução.

Indispensável para Native Image porque JFR não funciona lá (até Java 22).

## Pra que vamos usar no projeto

**Onda 5 (Native Image)**: validar que SIMD intrinsics rodam, identificar gargalos sem JFR.

```bash
# Hot methods em Native Image
sudo perf record -F 99 -p $(pgrep fraud-api) -g -- sleep 30
sudo perf report

# Cache misses por método
sudo perf stat -e cache-misses,cache-references -p $(pgrep fraud-api) -- sleep 10

# Instructions per cycle (ideal: ~3 em CPU moderna)
sudo perf stat -e instructions,cycles -p $(pgrep fraud-api) -- sleep 10
```

Também útil em Onda 4 (HotSpot) para visão complementar a JFR.

## Como funciona (em profundidade)

### Subcomandos principais

```
perf record    Grava amostras (sampling) ou eventos
perf report    Análise interativa do record
perf top       Live profiling (similar a 'top' por método)
perf stat      Conta eventos durante execução
perf trace     Trace de syscalls
perf script    Pretty-print do record (input para flamegraph)
perf list      Lista eventos disponíveis
perf annotate  Mostra assembly anotado por hot
```

### `perf record`

```bash
# 99 Hz sampling, com call graph (DWARF), no PID alvo, por 30s
sudo perf record -F 99 -g --call-graph dwarf -p <PID> -- sleep 30

# Output: perf.data
sudo perf report   # interativo
```

Opções importantes:
- `-F 99`: frequência (Hz). 99 (não 100) para evitar sincronia com cron.
- `-g`: capturar call graph.
- `--call-graph dwarf`: usar DWARF para stack walking (preciso, pesado). Alternativa: `fp` (frame pointer, leve mas exige `-fno-omit-frame-pointer`).
- `-p PID` ou `-a` (system-wide).

### `perf stat`

Conta eventos hardware/software:

```bash
sudo perf stat -e cycles,instructions,cache-misses,branch-misses \
     -p $(pgrep fraud-api) -- sleep 10
```

Output:
```
Performance counter stats for process id '12345':

      26,000,000,000      cycles
      78,000,000,000      instructions   #  3.00  insn per cycle
       1,200,000,000      cache-misses
         300,000,000      branch-misses
```

**IPC (instructions per cycle)** é métrica chave: ideal ~3-4 em CPU moderna. <1 indica gargalo (cache miss, branch mispredict).

### Hardware events

`perf list` mostra dezenas. Críticos:
- `cycles` — ciclos de CPU.
- `instructions` — instruções retiradas.
- `cache-references` / `cache-misses` — L3 cache.
- `L1-dcache-load-misses` — L1 data cache.
- `branch-misses` — branch predictor falhou.
- `LLC-load-misses` — Last Level Cache (L3 geralmente).
- `dTLB-load-misses` — TLB miss (huge pages podem ajudar).

### Flamegraph

Padrão visual para CPU profiling. Combo perf + Brendan Gregg's FlameGraph:

```bash
git clone https://github.com/brendangregg/FlameGraph
sudo perf record -F 99 -g -p $(pgrep fraud-api) -- sleep 30
sudo perf script | ./FlameGraph/stackcollapse-perf.pl | ./FlameGraph/flamegraph.pl > flame.svg
firefox flame.svg
```

Width = tempo na função (incluindo callees). Cor = arbitrária. Procurar **plateaus largos** (gargalos).

### Native Image symbols

GraalVM por default produz binário sem debug symbols → perf mostra `[unknown]`. Para perf ler stack:

```bash
native-image -H:+SourceLevelDebug -H:+IncludeDebugSymbols -jar app.jar
# ou
native-image -g -jar app.jar  # debug info
```

Trade-off: binário ~2-3× maior. Pode-se manter symbols separados.

### `perf top` (live)

Similar a `top` mas por método:

```bash
sudo perf top -p $(pgrep fraud-api)
```

Atualiza em tempo real. Útil para sanity check rápido.

## Exemplo de uso

```bash
# Setup (Ubuntu 24)
sudo apt install -y linux-tools-generic linux-tools-$(uname -r)

# Permitir perf sem sudo (opcional, dev only)
echo -1 | sudo tee /proc/sys/kernel/perf_event_paranoid

# Profiling Native Image rodando
./fraud-api 9000 &
PID=$!
k6 run test.js &

sudo perf record -F 99 -g -p $PID -- sleep 60
sudo perf report --stdio | head -40

# IPC overall
sudo perf stat -p $PID -- sleep 10

# Validar Vector API gerou AVX2
sudo perf record -F 999 -g -p $PID -- sleep 30
sudo perf annotate --stdio | grep -E "vpsubb|vpmullw|vphaddd"
# se aparecer, AVX2 OK

# Cleanup
kill $PID
```

## Tecnologias parecidas (alternativas)

| Ferramenta | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **async-profiler** | Java-aware (HotSpot e Native), flamegraph builtin | Native suporte ainda limitado em algumas combos | HotSpot ou Native com flamegraph automático |
| **JFR** | Eventos Java internos (GC, alocação, JIT) | Só HotSpot até 22 | Rich Java profiling em HotSpot |
| **eBPF / bpftrace** | Programável, kernel-level | Curva de aprendizado | Análise customizada (ex: latência de syscall específico) |
| **Intel VTune** | UI rica, hardware counters detalhados | Pago, Linux/Windows | Otimização avançada Intel-específica |
| **AMD uProf** | Para CPUs AMD | AMD-only | CPUs AMD |
| **gprof** | Built-in com gcc/clang | Profiling em compile time | Apps C/C++ pequenos |
| **strace** | Trace de syscalls | Não dá CPU profile | Investigar I/O |
| **ltrace** | Trace de calls de libs | Idem | Idem strace para libs |
| **callgrind / cachegrind** (Valgrind) | Instrumentação detalhada | ~50× mais lento | Análise offline detalhada |

Para **Native Image**: perf + FlameGraph é o padrão. async-profiler funciona em algumas versões.

## Pegadinhas conhecidas

1. **Permissões**: `perf` requer `CAP_SYS_ADMIN` ou kernel param permissivo. Default Ubuntu paranóico.
   ```bash
   sudo sysctl -w kernel.perf_event_paranoid=1   # ou -1 dev only
   ```
2. **Native Image sem symbols**: stacks vêm como `[unknown]` ou endereços. Compilar com `-H:+IncludeDebugSymbols`.
3. **Frame pointer omitido**: GraalVM por default omite. Use `--call-graph dwarf` que não depende.
4. **DWARF é pesado**: `perf record --call-graph dwarf` aumenta tamanho do `perf.data` 5-10×.
5. **Sampling vs counting**: `perf record` é sampling (estatístico), `perf stat` é counting (exato). Sampling pode missar funções rápidas.
6. **JIT methods em HotSpot**: HotSpot precisa de `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+PreserveFramePointer` + perf-map-agent.
7. **Containers**: perf dentro de Docker requer `--privileged` ou capabilities específicas (`--cap-add SYS_ADMIN`).
8. **Kernel version**: features novas (`-e kvm:*`) só em kernels recentes.

## Referências

- **Site oficial**: https://perf.wiki.kernel.org/index.php/Main_Page
- **Tutorial Brendan Gregg**: https://www.brendangregg.com/perf.html
- **FlameGraph repo**: https://github.com/brendangregg/FlameGraph
- **Brendan Gregg's blog**: https://www.brendangregg.com/blog.html (referência mundial em perf)
- **perf examples** (Brendan Gregg): https://www.brendangregg.com/perf.html#OneLiners
- **Native Image perf debugging**: https://www.graalvm.org/latest/reference-manual/native-image/debugging-and-diagnostics/perf-profiling/
- **async-profiler vs perf**: https://github.com/async-profiler/async-profiler
- **eBPF tutorial**: https://github.com/iovisor/bcc/blob/master/docs/tutorial.md

## Veredito final na Rinha

Onda 5+ — quando entrar Native Image, JFR para de funcionar e perf é o caminho. Combinar com FlameGraph para visual.
