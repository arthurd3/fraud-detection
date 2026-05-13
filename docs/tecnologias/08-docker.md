# Docker Engine + Compose

**Categoria**: Container runtime + orquestração local
**Versão usada na Rinha**: Docker Engine 24+, Compose v2
**Decisão rápida**: requisito da Rinha

---

## O que é

Docker é uma plataforma para empacotar aplicações em **containers**: ambientes isolados que rodam sobre o mesmo kernel do host mas têm seu próprio filesystem, network, processos. Containers são mais leves que VMs — startup em ms, RSS reduzido.

**Docker Engine** é o daemon que cria/roda containers. **Docker Compose** orquestra múltiplos containers via `docker-compose.yml`.

Sob o capô: **cgroups** (limites de CPU/memória), **namespaces** (isolamento), **OverlayFS** (filesystem layers), **OCI** (Open Container Initiative — padrão).

## Objetivo geral

Resolver "funciona na minha máquina" — empacotar tudo (binário + libs + config) numa imagem que roda igual em qualquer host com Docker.

Hoje é commodity em CI/CD, microsserviços, deploy serverless. Kubernetes é construído sobre containers OCI (não obriga Docker, mas Docker foi a referência).

## Pra que vamos usar no projeto

`Dockerfile` multi-stage para build:
1. **Stage 1 (builder)**: GraalVM 21, compila Java + Native Image.
2. **Stage 2 (dataset-builder)**: roda preprocessor para gerar `references.bin` e `hnsw.bin`.
3. **Stage 3 (runtime)**: imagem distroless mínima, copia só o binário Native + .bin files.

`docker/docker-compose.yml` orquestra:
- HAProxy (porta 9999, balanceador).
- 2 instâncias da API.
- Limites de recursos (1 CPU + 350 MB total).

Comando: `docker compose up --build` sobe tudo.

## Como funciona (em profundidade)

### Imagens vs Containers

- **Imagem**: snapshot read-only (camadas/layers) — análogo a uma classe.
- **Container**: instância em execução de uma imagem — análogo a um objeto.

```bash
docker pull nginx:alpine            # baixa imagem
docker run -d -p 80:80 nginx:alpine # cria container e roda
docker ps                           # lista containers ativos
docker images                       # lista imagens locais
```

### Layers e cache

Cada `RUN`/`COPY`/`ADD` no Dockerfile cria uma layer. Layers são cacheadas — se nada mudou, reusa. **Ordem importa**: coloque o que muda menos no topo.

```Dockerfile
COPY pom.xml .             ← muda raramente, layer cacheada
RUN mvn dependency:go-offline   ← cacheada se pom.xml não mudou
COPY src/ ./src/           ← muda toda hora, invalida só desse pra baixo
RUN mvn package
```

### Multi-stage builds

```Dockerfile
# Stage 1: build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY . .
RUN ./mvnw -DskipTests package

# Stage 2: runtime
FROM gcr.io/distroless/java21-debian12
COPY --from=builder /app/target/fraudAPI.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

Stage 1 tem JDK + Maven (~600 MB). Stage 2 só JRE (~100 MB) ou distroless (~20 MB). Imagem final pequena.

### Networking

Por default, containers em mesmo docker-compose.yml estão na **mesma bridge network**. Hostnames são os service names: `api1`, `api2`, `haproxy`. DNS interno do Docker resolve.

```yaml
services:
  api1:
    build: .
  api2:
    build: .
  haproxy:
    # api1 e api2 acessíveis como api1:9000 e api2:9000
```

### Resource limits (cgroups)

Crítico na Rinha:

```yaml
services:
  api1:
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "160MB"
```

`cpus: 0.45` = 45% de 1 CPU. `memory: 160MB` = limite hard, OOMKill se ultrapassar.

`docker stats` mostra uso real-time.

### Network mode

- **bridge** (default, requisito Rinha): container tem IP próprio na rede docker0.
- **host**: container compartilha rede do host (sem isolamento de rede).
- **none**: sem rede.

A Rinha **proíbe `host`** — todos em bridge.

### `entrypoint` vs `cmd`

```Dockerfile
ENTRYPOINT ["java", "-jar", "/app.jar"]   # comando fixo
CMD ["9999"]                              # argumentos default, override com docker run
```

`docker run app1 8080` → executa `java -jar /app.jar 8080`.

## Exemplo de uso

```bash
# Build
docker build -t fraudapi:latest .

# Run single
docker run -p 9999:9000 fraudapi:latest

# Compose up
docker compose up --build

# Logs
docker compose logs -f api1

# Stats em tempo real
docker stats

# Cleanup
docker compose down
docker system prune -a   # remove imagens não usadas
```

## Tecnologias parecidas (alternativas)

| Tecnologia | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **Podman** | Rootless, sem daemon, compat OCI | Comunidade menor que Docker | Servidores onde rootless importa |
| **containerd direto** | Daemon Docker usa containerd, lower-level | Menos amigável, sem CLI ergonômica | Kubernetes nodes |
| **Buildah** | Build sem daemon | Só build, não run | CI/CD focado |
| **LXC / LXD** | Containers sistema (não app) | Não OCI, ecossistema diferente | VPS-like |
| **VMs (KVM, VirtualBox)** | Isolamento absoluto, kernel próprio | Pesado, startup em segundos | Workload que precisa isolation real |
| **Firecracker (AWS)** | microVMs ultra-rápidas | Mais complexo de operar | Serverless backend |
| **Nix / NixOS** | Reprodutibilidade absoluta sem container | Curva alta | Reproducible builds |

Na Rinha: Docker é mandatório (compose padrão da competição).

## Pegadinhas conhecidas

1. **`docker run` sem `-p`**: porta não publicada, container inacessível do host.
2. **`docker stats` mede RSS**: cgroup v1 vs v2 reportam diferente. cgroup v2 (Ubuntu 24 default) é mais preciso.
3. **OOMKill silencioso**: se RSS estoura, kernel mata processo. `dmesg | grep oom-killer` no host.
4. **cgroups duplicados**: 2 instâncias mmap mesmo arquivo → kernel mantém 1 cópia, mas cada cgroup conta. Normal, não vazamento.
5. **Build cache cresce sem limite**: `docker system prune -a` periodicamente.
6. **`COPY .` puxa tudo**: use `.dockerignore` para excluir `.git`, `target/`, `node_modules/`.
7. **Permissões dentro do container**: arquivos copiados podem ter UID errado. Use `USER` no Dockerfile.
8. **Network bridge não é `host`**: latência de bridge é minúscula (~10 µs) mas existe. Para benchmark micro, considerar.
9. **Logs**: `docker logs` pega stdout/stderr. Se app loga em arquivo, precisa volume mount.
10. **Health check em compose**:
    ```yaml
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/ready"]
      interval: 5s
    ```
    Boa prática mas adiciona ruído em prof.

## Referências

- **Site oficial**: https://www.docker.com/
- **Docker docs**: https://docs.docker.com/
- **Dockerfile reference**: https://docs.docker.com/engine/reference/builder/
- **Compose specification**: https://docs.docker.com/compose/compose-file/
- **Docker hub** (imagens públicas): https://hub.docker.com/
- **Awesome Docker**: https://github.com/veggiemonk/awesome-docker
- **Best practices**: https://docs.docker.com/develop/dev-best-practices/
- **Multi-stage builds**: https://docs.docker.com/build/building/multi-stage/
- **Slim images** (analyzer): https://github.com/slimtoolkit/slim

## Veredito final na Rinha

Mandatório (compose é padrão). Foco no Dockerfile multi-stage com Native Image — Onda 4 e Onda 5.
