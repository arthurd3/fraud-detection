# Spec — lapada: forwarder L4 em Rust (FD-passing) substituindo o HAProxy

- **Data:** 2026-05-30
- **Branch:** `feat/rust-engine` (código); `main` tem só docs/spec; `submission` image-only.
- **Tipo:** mudança de arquitetura na camada de entrada (HAProxy → forwarder Rust FD-passing). API continua GraalVM native-image.
- **Status:** design aprovado em brainstorming; pendente review do spec → writing-plans. **1ª fase = spike Fase 0.5 (de-risca).**

---

## 1. Contexto & motivação

Pivô do port-da-busca (score-neutral, compute <0,1% do tail) para um lever mais perto do gargalo real: **trocar o HAProxy por um forwarder L4 em Rust (`lapada`) que passa o fd do cliente via `SCM_RIGHTS`** pras 2 APIs e sai do data path. Ataca:
- **Footprint:** dropa os 32MB do HAProxy → binário Rust ~5-8MB; sobra memória pras APIs → menos mmap eviction (a hipótese de footprint da sessão).
- **I/O off-data-path:** API fala direto com o cliente (sem o proxy copiando) → menos hop/CPU/queuing.

**Referência:** `jvmoonshot-xxvi-main` (de onde a KD-tree foi portada na Onda 7v2) usa exatamente esse padrão — MAS em **JDK 25 + Panama FFM + reflection em `sun.nio.ch`**, NÃO em GraalVM native-image. No nosso native-image, **FFM quebra o link (Onda 5)**. Então adaptamos via **a C-interface (`@CFunction`)**, cuja viabilidade a **Fase 0 já provou** (libfdsearch.a linkou; `fd_ping` rodou local + Docker).

**Por que ficar no native-image** (e não adotar o stack JDK do jvmoonshot): o nosso native-image usa **49 MiB** anônimos vs **~150 MiB** de regiões JVM do JDK do jvmoonshot — o nosso já é bem mais enxuto, o que serve o objetivo de footprint. O JDK seria mais fácil (copiar o FFM deles) mas pioraria o footprint e abandonaria todo o investimento em native-image.

**Expectativa honesta (gravada):** ataca footprint + I/O; o **throttle CFS (0.425/api) e os 49MB de runtime Java da API CONTINUAM** — é lever **parcial**, não bala de prata. O padrão FD-passing é provado pelo jvmoonshot (top-performer), mas o ganho de p99 no nosso stack só a prévia Mac Mini crava.

## 2. Goal & Non-goals

**Goal:** substituir o HAProxy por um forwarder Rust (`lapada`) que passa o fd via SCM_RIGHTS; a API (native-image) recebe o fd via `@CFunction` e serve a request nele — preservando **E=0** e o footprint enxuto.

**Non-goals:**
- NÃO trocar o runtime da API (continua GraalVM native-image; sem JDK/FFM).
- NÃO mudar a busca KD-tree (E=0 intacto; `:k2-test` lógico inalterado).
- NÃO esperar que sozinho resolva o p99 (CFS/eviction da API permanecem).
- NÃO usar FFM (quebra o link) nem depender de reflection no caminho do Unix socket (só talvez no wrap do client fd — Plano A, testado por spike).

## 3. Arquitetura

```
cliente TCP :9999
   │
   ▼  accept4 + round-robin
[lapada — Rust]  ──SCM_RIGHTS(fd cliente)──►  Unix socket /tmp/api{1,2}.sock
   │ (sai do data path)                              │
   │                                          [API — native-image]
   │                                          fd_next_client() @CFunction (Rust recvmsg)
   │                                                 │ injeta o fd no reactor
   │                                          NioServer → HttpParser → FraudRequestParser
   │                                                 → KdTree.search (Java, INALTERADA, E=0)
   │                                                 → HttpResponseWriter
   ◄─────────────── resposta direto no fd do cliente ◄┘
```

- **lapada (Rust)** é dono da aceitação TCP + round-robin + FD-passing. Fora do data path.
- **API (native-image)** recebe o fd e serve. A parte Unix-socket/recvmsg fica **toda em Rust (@CFunction)** — sem reflection. O ÚNICO ponto de reflection possível é o wrap do client fd em `SocketChannel` (Plano A), que o spike testa.

## 4. lapada — o forwarder (Rust)

Base = o `lapada.rs` que o usuário forneceu (emissor maduro). Adaptar/confirmar:
- `FD_UPSTREAMS="path1,path2"` (Unix sockets das 2 APIs); `LAPADA_LISTEN=0.0.0.0:9999`.
- `accept4(SOCK_CLOEXEC|SOCK_NONBLOCK)` (sem epoll no hot path); round-robin `idx ^= 1`; **backend cooldown** 1s em falha; fallback pro outro backend.
- `sendmsg(SCM_RIGHTS, MSG_NOSIGNAL)` do fd cru numa **conexão Unix persistente** por backend (`pass_fd_to_api`); reconecta só em erro fatal (EPIPE/ECONNRESET/...); transient (EAGAIN/ENOBUFS) não derruba.
- `preconnect_fd_upstreams()` antes de aceitar (clientes esperam no backlog do kernel) + `LAPADA_READY_FILE`.
- sockopts: `TCP_DEFER_ACCEPT`, `TCP_FASTOPEN`, `TCP_NODELAY` (preservado pelo SCM_RIGHTS).
- `current_thread` tokio (1 core via cpuset).
- crate `lapada/` próprio (binário), separado do `rust-engine/` (lib do motor/`fd_recv`).

## 5. API-side — receber o fd (Rust @CFunction, sem reflection no Unix)

Helpers Rust novos (no `rust-engine/`, `extern "C"`, linkados via a mesma C-interface da Fase 0):
```c
int32_t fd_listener_init(const char* sock_path);  // cria+bind+listen Unix socket; 0=ok
int32_t fd_next_client(void);                      // aceita ctrl persistente (reconecta) +
                                                   // recvmsg 1 fd; retorna client_fd ou -1
```
- `fd_listener_init` no boot (do `Main`), com o path de `FD_SOCKET` (env). Remove o stale, bind, listen, chmod 666.
- Uma **thread Java dedicada** roda o loop: `int fd = RustSearch.fdNextClient(); inject(fd);` — `recvmsg(SCM_RIGHTS)` é todo em Rust (espelha os layouts `msghdr`/`iovec`/`cmsghdr` Linux x86-64 que o jvmoonshot documentou: msghdr 56B, cmsghdr 16B, CMSG_SPACE(4)=24B). Sem reflection, sem FFM.
- Setar `TCP_NODELAY` no client fd (ou o lapada já fez).

## 6. Reactor — servir no fd (Plano A vs B; **spike Fase 0.5 decide**)

**Plano A (barato — reusa o NioServer):** embrulhar o client fd cru num `java.nio.channels.SocketChannel` via reflection no `sun.nio.ch.SocketChannelImpl` (ctor privado) + `FileDescriptor.fd` (VarHandle) — estilo jvmoonshot, **adaptado p/ a assinatura do ctor do JDK 21** (a do jvmoonshot é JDK 25). O fd embrulhado entra no `Selector` existente via fila concorrente (`injectChannel`); o `NioServer` segue igual (parse/search/response/keep-alive inalterados). **RISCO:** reflection em classes internas do NIO no native-image (closed-world + substituições SVM) pode não funcionar — precisa de reflect-config + pode falhar como o FFM falhou. **É o que o spike testa.**

**Plano B (certo — fallback):** I/O raw via `@CFunction` (`fd_read`/`fd_write`) + epoll nativo (`epoll_create1`/`epoll_ctl`/`epoll_wait` via @CFunction OU em Rust). Sem reflection, native-image-safe. O reactor do `NioServer` é reescrito p/ operar em fds crus (epoll + read/write nativos), mantendo parse/search/response em Java sobre `byte[]`. Mais trabalho, mas garantido.

A busca (`KdTree.search`) e a resposta canned **não mudam** em nenhum dos planos.

## 7. Keep-alive

lapada passa o fd **1× por conexão**; a API serve **N requests** nele (HTTP/1.1 keep-alive) até o cliente fechar. No Plano A isso vem de graça (o `NioServer` já faz keep-alive por `SelectionKey`/`HttpConnection`). No Plano B, o reactor raw mantém o fd no epoll e re-parseia por request (estado por-fd).

## 8. Topologia / compose

- Services: **`lapada`** (entrypoint :9999) + **`api-1`** + **`api-2`**. Sem HAProxy.
- Volume compartilhado (Unix sockets): um `tmpfs`/volume entre lapada e cada api p/ os `FD_SOCKET` (ex: `/sockets/api1.sock`, `/sockets/api2.sock`). lapada `FD_UPSTREAMS=/sockets/api1.sock,/sockets/api2.sock`.
- **Limites (validador exige `deploy.resources.limits.cpus` por service):** ex. lapada `cpus 0.15 / mem 16M` (sobra mem!), api `cpus 0.425 / mem 167M` ×2 → total 1.0 CPU / 350M. **Redistribui os ~16-24MB liberados do HAProxy pras APIs** (159→167M) → menos eviction.
- cpuset 0/1/2 mantido (lapada no 0, api no 1/2). Sem `sysctls` na submission (Rinha #5854).
- **`/ready`:** o lapada forwarda `GET /ready` pra um api (que responde 200) — o health check da Rinha funciona end-to-end. lapada só aceita após preconnect das 2 APIs.

## 9. Build

- `lapada/` crate (binário) + `rust-engine/` (lib `fd_recv`/`fd_listener_init`/`fd_next_client` + `fd_search` futuro). Dockerfile: estágio Rust builda os dois (`cargo build --release`), stage do staticlib (já feito na Fase 0) + o binário lapada.
- Imagem `lapada` (binário Rust minúsculo) + imagem `api` (native-image, agora com os helpers fd_* linkados via a C-interface da Fase 0).
- compose roda as 3.

## 10. E=0

**Intacto.** Nem a busca nem a quantização mudam — só a CAMADA de I/O/entrada. Gate: `ExactAgree` 0/54100 continua valendo (a request chega à mesma `KdTree.search`). Smoke confirma 2 oráculos pelo caminho lapada→api.

## 11. Fasamento

- **Fase 0.5 — spike (BLOQUEANTE):** (a) `fd_recv` @CFunction recvmsg roda no native-image (extensão direta da Fase 0, baixo risco); (b) **reflection-wrap fd→SocketChannel funciona no native-image (JDK 21)?** → decide **Plano A** (barato) vs **B** (certo). Teste mínimo isolado (loopback fd → wrap → read/write), sem lapada ainda.
- **Fase 1 — lapada + compose:** crate lapada (adaptar paste), Dockerfile, compose (troca haproxy, 3 services, Unix sockets), `/ready` forwarda. Smoke: sobe, /ready 200, request passa.
- **Fase 2 — receiver + reactor:** `fd_listener_init`/`fd_next_client` (Rust) + thread receiver no `Main`/`NioServer` + integração conforme A/B do spike.
- **Fase 3 — gates + medição:** E=0 (ExactAgree 0/54100), smoke (oráculos), footprint (cgroup anon das APIs), prévia Mac Mini (árbitro do p99).

## 12. Gates

| Gate | O quê | Critério |
|---|---|---|
| **G0.5** | spike: fd_recv @CFunction + reflection-wrap no native-image | recvmsg ok + wrap serve 1 request (→ Plano A) OU decide Plano B |
| **G1** | lapada + compose sobem | 3 services up, /ready 200, request end-to-end pelo lapada |
| **G2** | **E=0** | ExactAgree 0/54100 + 2 oráculos corretos pelo caminho lapada→api |
| **G3** | footprint | cgroup anon api ≈ igual; HAProxy 32MB eliminado; total ≤350M |
| **G4** | prévia Mac Mini | p99 vs baseline K2 (árbitro; esperado melhora parcial ou neutro) |

## 13. Riscos & mitigações

1. **reflection-wrap falha no native-image** (como FFM) → spike Fase 0.5 detecta cedo; fallback Plano B (@CFunction raw I/O, certo).
2. **recvmsg layout errado** (msghdr/cmsghdr) → replicar exatamente os offsets Linux x86-64 (jvmoonshot documentou); testar no spike.
3. **lapada↔api Unix socket no compose** (paths, permissões, ordem de boot) → preconnect + ready file + retries; G1 valida.
4. **Ganho de p99 incerto** (CFS/eviction da API continuam) → honesto no spec; G4 é o árbitro; rollback trivial.
5. **Não move o p99 / regride** → rollback pro HAProxy (compose anterior); zero risco pra submission até a prévia confirmar.

## 14. Arquivos

- **Criar:** `lapada/` crate (Cargo.toml + src/main.rs adaptado do paste); `rust-engine/src/` ganha `fd_recv`/`fd_listener_init`/`fd_next_client` (+ no `fdsearch.h` e no binding `RustSearch`); spike harness (Fase 0.5, temporário).
- **Modificar:** `Main.java` (fd_listener_init no boot + thread receiver; remover o probe Fase 0), `NioServer`/`FdReceiver` (inject conforme A/B), `docker-compose.yml` (troca haproxy por lapada, 3 services, Unix sockets, redistribui mem), `Dockerfile` (builda lapada + api), `api/pom.xml` (já tem a C-interface da Fase 0; +bindings fd_*).
- **Inalterado:** `KdTree`/busca/quantização (E=0), `HttpParser`/`FraudRequestParser`/`HttpResponseWriter` (lógica), formato `references.kdt`.

## 15. Verificação

1. **G0.5:** spike local native-image → "SPIKE OK" (wrap serve request) ou decide B.
2. **G1/G2:** `docker compose up` (lapada+2api) → `/ready` 200 → 2 POST `/fraud-score` corretos pelo LB; `ExactAgree` 0/54100 (a busca não mudou, mas confirmar o caminho).
3. **G3:** `docker stats` / cgroup anon das APIs; confirmar HAProxy ausente, total ≤350M.
4. **G4 (usuário):** push submission + issue `rinha/test` → p99 vs K2.

## 16. Rollback

`docker-compose.yml` volta ao HAProxy + `:k2-test` (estado atual da submission, `c3b0a72`) — zero risco pra submission até a prévia confirmar o lapada. O trabalho fica na branch `feat/rust-engine` até G4 validar.
