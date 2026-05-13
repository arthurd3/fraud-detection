# HAProxy

**Categoria**: Load balancer / TCP proxy
**Versão usada na Rinha**: 2.8 LTS ou 3.0
**Decisão rápida**: ver `../RINHA_PLAN.md` §5.9

---

## O que é

HAProxy (High Availability Proxy) é um software open source de **load balancing** TCP/HTTP de alta performance. Atua como **proxy reverso** distribuindo requests entre múltiplas instâncias de backend. Famoso por **single-threaded event-driven** (igual nginx, Redis) — muito leve e rápido.

Escrito em C, mantido desde 2002 por Willy Tarreau. Usado em produção por Twitter, GitHub, Stack Overflow, Reddit, Tumblr, etc.

## Objetivo geral

Resolver dois problemas:

1. **Distribuir carga**: aplicações têm 1 endpoint público mas N réplicas backend. LB roteia.
2. **Alta disponibilidade**: se um backend morre, LB detecta (health check) e tira do pool.

Hoje LB é commodity em qualquer arquitetura distribuída.

## Pra que vamos usar no projeto

`docker/haproxy.cfg` configura HAProxy em **modo TCP** distribuindo conexões round-robin entre `api1:9000` e `api2:9000`. **Único caminho** entre k6 (porta 9999) e instâncias da API — restrição da Rinha.

Por que TCP (não HTTP):
- TCP só faz round-robin de conexão, sem parsing de header (overhead ~10-30%).
- Atende requisito Rinha ("LB sem lógica de negócio") sem inspecionar payload.
- ~10 MB de RSS no container HAProxy.

## Como funciona (em profundidade)

### Arquivo de configuração

```
global
    nbthread 1                  # 1 thread = sem locks, ideal pra Rinha
    maxconn 2000

defaults
    mode tcp                    # TCP mode = sem header parsing
    timeout connect 100ms
    timeout client  2500ms      # k6 tem timeout 2001ms
    timeout server  2500ms

frontend front
    bind *:9999
    default_backend api

backend api
    balance roundrobin
    server api1 api1:9000 check
    server api2 api2:9000 check
```

`check` ativa health check passivo (HAProxy tenta conectar periodicamente).

### Modos: TCP vs HTTP

| Aspecto | mode tcp | mode http |
|---|---|---|
| Latência adicional | ~50-100 µs | ~150-300 µs (header parse) |
| RAM | ~10 MB | ~20 MB |
| Funcionalidades | Round-robin, health check, ACL básica | Path routing, header rewrite, sticky session |
| Quando usar | LB transparente | API gateway, load balancing por path |

Na Rinha: TCP por causa da latência.

### Algoritmos de balanceamento

```
balance roundrobin    # default, alterna sequencialmente
balance leastconn     # menos conexões ativas
balance source        # hash do IP (sticky)
balance random        # aleatório
balance hdr(X-User)   # hash de header (só HTTP mode)
```

Round-robin é o mais simples e atende.

### Single-thread event-driven

HAProxy `nbthread 1` usa epoll multiplexing — 1 thread, milhares de conexões. Mesmo modelo do nosso NIO Selector. Sem locks, sem context switch.

`nbthread 4`+ paraleliza em CPUs separadas (irrelevante na Rinha — só 1 CPU).

### Health checks

```
server api1 api1:9000 check inter 2s rise 2 fall 3
```

- `check`: ativa health check.
- `inter 2s`: a cada 2 segundos.
- `rise 2`: 2 sucessos consecutivos para considerar "up".
- `fall 3`: 3 falhas consecutivas para considerar "down".

Em TCP mode, check = TCP connect. Em HTTP, pode-se especificar request HTTP (`option httpchk GET /ready`).

### Stats (opcional)

```
listen stats
    bind *:8080
    mode http
    stats enable
    stats uri /
    stats refresh 5s
```

UI HTML mostrando RPS, conexões, status backend. Útil em debug.

### Validação de config

```bash
haproxy -c -f haproxy.cfg
# ou em container
docker run --rm -v $(pwd)/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg haproxy:2.8 -c -f /usr/local/etc/haproxy/haproxy.cfg
```

## Exemplo de uso

`docker-compose.yml` típico da Rinha:

```yaml
services:
  haproxy:
    image: haproxy:2.8-alpine
    ports:
      - "9999:9999"
    volumes:
      - ./haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro
    deploy:
      resources:
        limits:
          cpus: "0.10"
          memory: "30MB"
    depends_on: [api1, api2]
  
  api1:
    build: .
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "160MB"
  
  api2:
    build: .
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "160MB"
```

Total: 0.10 + 0.45 + 0.45 = **1.0 CPU** ✓ e 30 + 160 + 160 = **350 MB** ✓.

## Tecnologias parecidas (alternativas)

| Tecnologia | Vantagem | Desvantagem | Quando preferir |
|---|---|---|---|
| **nginx (mode stream)** | Familiar, multi-uso (HTTP server + LB) | RSS ~3× maior em Rinha-scale | Quando já está usando nginx pra outra coisa |
| **nginx (HTTP mode)** | Configuração intuitiva | Slower que HAProxy mode tcp em LB puro | API gateway com path routing |
| **Caddy** | Auto-HTTPS, config simples | RSS maior, perf menor | HTTPS automático em produção |
| **Traefik** | Service discovery (k8s, Docker labels) | Overhead de descoberta | Microsserviços orquestrados |
| **Envoy** | Proxy moderno (HTTP/2, gRPC nativo) | Configuração complexa, RSS grande | Service mesh (Istio) |
| **AWS ELB / GCP LB** | Managed, auto-scaling | Cloud-vendor lock-in | Produção cloud |
| **socat / iptables DNAT** | Zero overhead | Não é "LB de verdade", viola requisito Rinha | Hacking, port forwarding simples |

Na Rinha: HAProxy é o mais leve e rápido para o caso de uso (round-robin TCP).

## Pegadinhas conhecidas

1. **`mode tcp` é mandatório**: `mode http` adiciona ~30% de latência e RSS dobrado. Validar com `haproxy -c -f haproxy.cfg`.
2. **Timeouts apertados**: `timeout server 2500ms` é o limite. Se backend demora mais (não deveria em Rinha), erros HTTP.
3. **`nbthread 1`**: para 1 CPU, evita lock contention. Se tiver 4 CPUs, `nbthread 4` é melhor.
4. **`maxconn` global**: default 2000 é suficiente para Rinha. Se ficar lotado em testes maiores, subir.
5. **Health check failure cascade**: se backend lento, todos os health checks falham, HAProxy marca todos "down", retorna 503. Tunar `inter` e `fall`.
6. **`stats`**: NUNCA expor em produção. Vaza info sensível.
7. **Versão**: 2.8 LTS é stable. 3.x tem features novas (HTTP/3) mas ainda em maturação.

## Referências

- **Site oficial**: https://www.haproxy.org/
- **Documentação 2.8 LTS**: https://docs.haproxy.org/2.8/configuration.html
- **Configuração quick start**: https://www.haproxy.com/documentation/haproxy-configuration-tutorials/
- **Health checks reference**: https://www.haproxy.com/documentation/haproxy-configuration-tutorials/load-balancing/health-checks/
- **Docker image**: https://hub.docker.com/_/haproxy
- **Cheatsheet**: https://github.com/jhooq/haproxy-cheat-sheet
- **HAProxy vs nginx vs Envoy** (comparação): https://www.haproxy.com/blog/haproxy-vs-nginx-vs-envoy

## Veredito final na Rinha

HAProxy 2.8 LTS em modo TCP. Configuração em ~20 linhas. Onda 4 do roadmap.
