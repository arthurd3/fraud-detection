# Distroless

**Categoria**: Imagem base de container
**Versão usada na Rinha**: `gcr.io/distroless/base-debian12`
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.11

---

## O que é

Distroless é uma família de **imagens Docker minimalistas** mantida pelo Google. Contêm **apenas** o runtime necessário para a aplicação — sem shell (`bash`/`sh`), sem package manager (`apt`, `apk`), sem utilitários (`ls`, `cat`, `vim`).

Comparação de tamanhos:
- `ubuntu:24.04`: ~80 MB
- `debian:12-slim`: ~75 MB
- `alpine:3.19`: ~7 MB
- `gcr.io/distroless/base-debian12`: ~20 MB
- `gcr.io/distroless/static-debian12`: ~2 MB
- `scratch`: 0 bytes (vazio total)

## Objetivo geral

Reduzir **superfície de ataque** e **footprint**:

1. Sem shell = sem como exfiltrar via `bash` se o app for comprometido.
2. Sem package manager = sem instalação acidental de software vulnerável.
3. Imagem menor = pull rápido, deploy ágil, menos espaço em registry.

Originalmente desenvolvido pelo Google interno, open-sourceado em 2017.

## Pra que vamos usar no projeto

Última stage do `Dockerfile` (após builder GraalVM Native):

```Dockerfile
FROM gcr.io/distroless/base-debian12
COPY --from=builder /build/target/fraud-api /app/fraud-api
COPY --from=builder /build/references.bin /app/references.bin
COPY --from=builder /build/labels.bin /app/labels.bin
COPY --from=builder /build/hnsw.bin /app/hnsw.bin
ENTRYPOINT ["/app/fraud-api"]
```

Imagem final: ~20 MB (distroless) + ~80 MB (Native binary) + ~42 MB (.bin files) = **~140 MB**. Cabe no orçamento de RAM (mmap não conta totalmente).

## Como funciona (em profundidade)

### Variantes

| Imagem | Contém | Tamanho | Quando usar |
|---|---|---|---|
| `static` | só CA certs, /etc/passwd, tzdata | ~2 MB | Binários estáticos (Go, Rust musl) |
| `base` | static + glibc | ~20 MB | Apps glibc-dependentes (C, C++, Native Image default) |
| `cc` | base + libgcc + libstdc++ | ~25 MB | C++ apps |
| `java21` | base + JRE 21 (HotSpot) | ~250 MB | App Java sem Native Image |
| `nodejs20` | base + Node.js 20 | ~140 MB | Node app |
| `python3` | base + Python 3 | ~90 MB | Python app |

Para Native Image **glibc** (default GraalVM): `base-debian12`.
Para Native Image **musl static** (com `--static --libc=musl`): `static-debian12` ou até `scratch`.

### Por que sem shell?

Shell é vetor de ataque clássico:
- Reverse shell em RCE (`bash -i >& /dev/tcp/…`).
- Exec arbitrário em vulnerabilidades de injection.
- Debugging com privilégio elevado.

Distroless **força** debug via:
- `kubectl exec --debug` com sidecar (não funciona em distroless puro).
- Builder + Distroless duo: dev usa imagem com shell, prod usa distroless.
- `gcr.io/distroless/<lang>:debug` versão com `busybox` para debug.

### Multi-stage com distroless

Padrão em Java Native:

```Dockerfile
# Stage 1: builder com tudo
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY . .
RUN ./mvnw -Pnative -DskipTests package
# saída: target/fraud-api (binário ELF Linux ~80 MB)

# Stage 2: dataset preprocessor
FROM eclipse-temurin:21-jdk AS dataset-builder
COPY --from=builder /app/target/preprocessor.jar .
COPY rinha-de-backend-2026/resources/ ./resources/
RUN java -jar preprocessor.jar resources/references.json.gz references.bin labels.bin

# Stage 3: runtime mínimo
FROM gcr.io/distroless/base-debian12
COPY --from=builder /app/target/fraud-api /app/fraud-api
COPY --from=dataset-builder /references.bin /hnsw.bin /labels.bin /app/
USER nonroot:nonroot
ENTRYPOINT ["/app/fraud-api"]
```

### USER nonroot

Distroless tem usuário `nonroot:nonroot` (UID 65532) pré-configurado. Boas práticas: não rodar como root.

```Dockerfile
USER nonroot:nonroot
```

### Tags e versionamento

Tags principais:
- `latest` — sempre a versão mais recente (não recomendado para produção).
- `nonroot` — versão com USER nonroot default.
- `debug` — inclui busybox.
- `<sha256>` — pin imutável (recomendado em prod).

```Dockerfile
FROM gcr.io/distroless/base-debian12:nonroot
```

## Exemplo de uso

```Dockerfile
# Aplicação Native Image simples
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY pom.xml .
COPY src/ ./src/
RUN ./mvnw -Pnative -DskipTests package

FROM gcr.io/distroless/base-debian12:nonroot
COPY --from=builder /app/target/myapp /myapp
ENTRYPOINT ["/myapp"]
```

```bash
docker build -t myapp .
docker run --rm -p 8080:8080 myapp

# Tamanho final
docker images myapp
# myapp   latest   abc123   2 minutes ago   95MB
```

## Tecnologias parecidas (alternativas)

| Imagem | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **`scratch`** | 0 bytes, mínimo absoluto | Sem CA certs, tzdata, /etc/passwd — você fornece TUDO | Binário 100% estático (Go, Rust musl) |
| **`alpine`** | ~7 MB, package manager | musl libc tem quirks (DNS, threading) | Apps que cabem em musl |
| **`busybox`** | ~5 MB, comandos básicos | Linker quirks com glibc apps | Scripts shell containerizados |
| **`ubuntu:24.04`** | Familiar, full apt | ~80 MB | Dev/debug, apps que precisam de muitas libs |
| **`debian:12-slim`** | Estável, glibc | ~75 MB | Idem Ubuntu |
| **`chainguard/static`** | Hardened, atualizações automáticas | Comunidade Chainguard menor | Quando segurança extra importa |
| **Wolfi (Chainguard)** | Distro container-native | Aprendizado | Greenfield, sem amarras com Debian |

Na Rinha:
- Default: `gcr.io/distroless/base-debian12` (~20 MB) — glibc, simples.
- Otimização Onda 5: `--static --libc=musl` + `FROM scratch` se quiser última gota.

## Pegadinhas conhecidas

1. **Sem shell = sem `docker exec -it bash`**: debug vira sidecar pattern ou imagem `:debug` separada.
2. **Sem `tini`**: signal handling — Docker sinaliza `SIGTERM` para PID 1. Native Image binary precisa lidar (não trivial em alguns runtimes).
3. **Sem `apt`**: instalações de runtime impossíveis. Coloque tudo na stage de build.
4. **Logs**: stdout/stderr ainda funcionam (Docker captura). Mas se app só loga em arquivo, sem volume mount, perde.
5. **scratch + glibc não funciona**: glibc precisa do dynamic linker. `scratch` só com static binaries.
6. **CA certificates**: distroless `base` inclui `/etc/ssl/certs/ca-certificates.crt`. `static` também. `scratch` não — você precisa copiar.
7. **DNS em scratch**: precisa de `/etc/resolv.conf` (Docker injeta) e libnss (glibc). Impossível em scratch sem static.

## Referências

- **GitHub oficial**: https://github.com/GoogleContainerTools/distroless
- **Imagens disponíveis**: https://github.com/GoogleContainerTools/distroless/blob/main/README.md
- **Why distroless** (palestra Google): https://www.youtube.com/watch?v=lviLZFciDv4
- **Distroless com Java**: https://github.com/GoogleContainerTools/distroless/blob/main/java/README.md
- **Comparação com alpine**: https://martinheinz.dev/blog/92
- **Hardened images alternativas**: https://www.chainguard.dev/

## Veredito final na Rinha

Distroless `base-debian12` na última stage. ~20 MB. Combina com Native Image binary (~80 MB) e .bin files (~42 MB) = ~140 MB final. Onda 4-5.
