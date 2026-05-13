# GraalVM Native Image

**Categoria**: Runtime + AOT compiler
**Versão usada na Rinha**: Mandrel 21 LTS (fork Red Hat) ou GraalVM CE 21
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.2

---

## O que é

GraalVM é uma alternativa à JVM tradicional (HotSpot) desenvolvida pela Oracle Labs. Tem dois modos principais:

1. **GraalVM JIT**: substitui o C2 do HotSpot por um compilador escrito em Java (Graal). Em alguns workloads, supera C2 em throughput.
2. **GraalVM Native Image**: compila Java **antes de rodar** (AOT — Ahead-Of-Time) gerando um **binário nativo executável** sem precisar de JVM.

**Mandrel** é um fork do GraalVM Community Edition mantido pela Red Hat, focado **exclusivamente em Native Image** para Java. Sem Truffle (polyglot), sem JIT — só o builder. Mais estável para nosso uso.

## Objetivo geral

Native Image ataca dois problemas históricos da JVM:

1. **Startup lento**: JVM tradicional carrega classes, interpreta, JIT-compila — leva segundos.
2. **Warmup**: até C2 otimizar, código roda devagar. Custa em benchmarks curtos e em serverless (cold start).

AOT compila tudo no momento do build → binário pronto pra rodar no pico desde o primeiro request. RSS também cai (~30-80 MB vs ~80-200 MB HotSpot).

## Pra que vamos usar no projeto

**Onda 5 do roadmap**: trocar HotSpot por Native Image binary. Razões:

1. **Sem warmup** — primeiras requests do k6 já no pico de perf. Em workload de 120s, isso é diferença sensível.
2. **RSS menor** — 30-80 MB por instância vs 80-200 MB HotSpot. Cabe folgado no orçamento de 350 MB total.
3. **Startup em ms** — irrelevante para a Rinha (testa app rodando), mas bom para health check.
4. **Footprint determinístico** — sem GC concurrent surpresa.

Local: `Dockerfile` muda builder image para `ghcr.io/graalvm/native-image-community:21` ou `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21`.

## Como funciona (em profundidade)

### Build em duas fases

```
fontes .java → javac → bytecode .class → native-image → binário ELF Linux
                                            ↑
                              análise estática (closed-world)
                              detecta TUDO que pode ser chamado
                              gera código + GC + runtime mínimo
```

### Closed-world assumption

Native Image precisa saber **em tempo de build** todas as classes que podem ser carregadas. Isso conflita com:
- **Reflection** (`Class.forName(...)`).
- **Dynamic class loading**.
- **Resource loading via classpath** (`getResource(...)`).
- **JNI / Unsafe** em alguns casos.

Soluções:
- `reflect-config.json` lista classes acessadas via reflection.
- `resource-config.json` lista recursos a embarcar.
- Build agent automatiza a detecção rodando o app antes.

Na Rinha: nosso código é **zero-reflection**, dataset fica **fora do JAR** (mmap externo). Configuração mínima.

### GC em Native Image

Native Image usa **Serial GC** por default. Sem concurrent collector. Pauses proporcionais ao heap. Em workload zero-allocation hot path (Rinha), Serial nunca trigger durante uma request — pause-free na prática.

Configurações:
- `-H:+UseSerialGC` (default).
- `-H:+UseParallelGC` (paralelo).
- `-H:+UseEpsilonGC` (no-op, OOM em alta alocação — só pra benchmark "perfeito").

### PGO recupera o que falta

Sem JIT dinâmico, AOT perde otimizações baseadas em profile (branch hints, inlining cirúrgico). PGO compensa: build instrumentado → roda treino → build final com profile. Detalhes em [14-pgo.md](14-pgo.md). Ganho: 10-30% throughput.

### `--static --libc=musl`

Permite gerar binário 100% estático (sem dependência de glibc). Usado com `FROM scratch` em Docker pra imagem mínima. Trade-off: build mais frágil (problemas de link), tamanho final menor.

## Exemplo de uso

```bash
# Build padrão
native-image -jar fraud-api.jar fraud-api

# Com flags da Rinha
native-image \
  --enable-preview \
  --add-modules jdk.incubator.vector \
  -H:+ReportExceptionStackTraces \
  --no-fallback \
  -jar fraud-api.jar \
  fraud-api

# Build com PGO instrument
native-image --pgo-instrument -jar fraud-api.jar fraud-api-instr

# Treino + build final
./fraud-api-instr &
k6 run test/test.js
kill -SIGINT $!
native-image --pgo=default.iprof -jar fraud-api.jar fraud-api-pgo

# Em Docker
FROM ghcr.io/graalvm/native-image-community:21 AS builder
COPY pom.xml src/ /app/
RUN cd /app && mvn -Pnative -DskipTests package
```

Tempo de build típico: 1-5 minutos (vs `mvn package` em segundos).

## Tecnologias parecidas (alternativas)

| Tecnologia | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **HotSpot puro (C1+C2)** | Steady-state às vezes mais rápido (C2 agressivo) | Warmup, RSS maior | App long-running com workload variável |
| **HotSpot + AppCDS** | Acelera startup (carrega cache de classes) | Não elimina warmup do C2 | App que precisa só startup rápido |
| **HotSpot + JLink** | Distribui só os módulos usados (imagem ~50 MB) | Ainda tem JVM, ainda warmup | Reduzir tamanho de runtime |
| **OpenJ9 (IBM)** | RSS menor que HotSpot, perfil enterprise | Throughput menor, ecossistema menor | App memory-constrained (containers k8s) |
| **Quarkus / Micronaut** | Frameworks GraalVM-friendly | Overhead de framework | App com >5 endpoints, dev velocity importa |
| **Spring Boot Native** | Familiar, Spring ecosystem | Reflection-heavy, config Native pesada | Spring app legacy migrando para AOT |

Na Rinha, **GraalVM Native (Mandrel)** é o melhor encaixe: zero-warmup + RSS pequeno + zero-reflection + perf alta com PGO.

## Pegadinhas conhecidas

1. **Vector API regrediu silenciosamente em Mandrel 22/23** — fica escalar sem aviso. Sempre validar com `-Dgraal.PrintCompilation=true | grep -i vector`.
2. **`reflect-config.json` ausente** → `ClassNotFoundException` em runtime que não acontece em HotSpot. Solução: zero-reflection.
3. **Recursos no classpath** (`getResourceAsStream`) precisam de `resource-config.json`. Solução: arquivos externos via mmap.
4. **`MappedByteBuffer.force()` em arquivo RO** → `ReadOnlyBufferException`. Não chamar em arquivos read-only.
5. **Build determinístico**: profile gerado com Mandrel 21 não funciona em Mandrel 23. Sempre regerar.
6. **Static linking** (`--static --libc=musl`) tem erros de link sutis. Use distroless com glibc se não precisa de scratch.

## Referências

- **Site oficial**: https://www.graalvm.org/
- **Native Image docs**: https://www.graalvm.org/jdk21/reference-manual/native-image/
- **Mandrel (Red Hat)**: https://github.com/graalvm/mandrel
- **PGO docs**: https://www.graalvm.org/jdk21/reference-manual/native-image/optimizations-and-performance/PGO/
- **Reflection config**: https://www.graalvm.org/jdk21/reference-manual/native-image/dynamic-features/Reflection/
- **Build report (HTML)**: https://www.graalvm.org/jdk21/reference-manual/native-image/overview/build-report/
- **Container images**: https://github.com/graalvm/container
- **Native Image issues** (busca por bugs): https://github.com/oracle/graal/issues
- **Awesome GraalVM**: https://github.com/graalvm/awesome-graal

## Veredito final na Rinha

Onda 5 troca HotSpot por Mandrel 21 LTS. Validar Vector API gerou intrínsecos AVX2 (não regrediu). PGO obrigatório para fechar gap de C2.
