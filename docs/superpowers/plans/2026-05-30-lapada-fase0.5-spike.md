# lapada Fase 0.5 — Spike (FD-receive + reactor decision) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** De-riscar a arquitetura lapada provando, no GraalVM native-image, que (a) `recvmsg(SCM_RIGHTS)` funciona via `@CFunction` e (b) decidir se dá pra embrulhar um fd cru em `SocketChannel` por reflection (→ **Plano A**, reusa o NioServer) ou se precisamos de I/O raw via `@CFunction` (→ **Plano B**).

**Architecture:** Estende a fundação da Fase 0 (crate `rust-engine` + binding `@CFunction` já provados). Spike LOCAL (native-image CE 21.0.2, ~20s/build) — sem Docker, sem lapada ainda, sem mexer na busca (E=0 intacto).

**Tech Stack:** Rust (libc recvmsg/sendmsg), GraalVM native-image C-interface (`@CFunction`), reflection em `sun.nio.ch` (Plano A), JDK 21.

> **Spike honesto:** a pergunta (b) — reflection no NIO interno do native-image — é genuinamente incerta (closed-world + substituições SVM). Este plano dá o teste isolado; **o resultado decide A vs B.** (a) é baixo risco (estende a Fase 0).

---

## File Structure

- `rust-engine/src/lib.rs` — +`fd_recv`, +`fd_send`, +`fd_socketpair` (helpers de teste/spike) (modify)
- `rust-engine/src/lib.rs` cargo test — round-trip recvmsg (a)
- `rust-engine/include/fdsearch.h` — +declarações fd_recv/fd_send/fd_socketpair (modify)
- `api/src/main/java/org/fraudDetection/rust/RustSearch.java` — +`@CFunction` fdRecv/fdSend/fdSocketpair (modify)
- `api/src/main/java/org/fraudDetection/rust/FdWrap.java` — reflection-wrap fd→SocketChannel (Plano A) (create, spike)
- `api/src/main/java/org/fraudDetection/rust/SpikeReactor.java` — main do spike (loopback → wrap → read/write; e recvmsg) (create, spike/temporário)
- `api/src/main/resources/META-INF/native-image/reflect-config.json` — registra os internals do NIO (create)

---

## Task 0.5.1: Rust `fd_recv`/`fd_send`/`fd_socketpair` + cargo test (pergunta (a))

**Files:**
- Modify: `rust-engine/src/lib.rs`
- Modify: `rust-engine/include/fdsearch.h`
- Modify: `rust-engine/Cargo.toml` (dep `libc`)

- [ ] **Step 1: Adicionar a dep `libc` no `rust-engine/Cargo.toml`**

Após `[package]...edition`, inserir/garantir:
```toml
[dependencies]
libc = "0.2"
```

- [ ] **Step 2: Adicionar os helpers no `rust-engine/src/lib.rs`** (mantendo `fd_ping`)

```rust
use std::os::raw::{c_int, c_void};

/// recvmsg(SCM_RIGHTS): recebe 1 fd pelo socket de controle. Retorna o fd ou -1.
#[no_mangle]
pub extern "C" fn fd_recv(ctrl_fd: i32) -> i32 {
    unsafe {
        let mut data: u8 = 0;
        let mut iov = libc::iovec { iov_base: &mut data as *mut u8 as *mut c_void, iov_len: 1 };
        let mut cmsg_buf = [0u8; 64];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cmsg_buf.as_mut_ptr() as *mut c_void;
        msg.msg_controllen = cmsg_buf.len() as _;
        loop {
            let n = libc::recvmsg(ctrl_fd, &mut msg, 0);
            if n < 0 {
                if *libc::__errno_location() == libc::EINTR { continue; }
                return -1;
            }
            if n == 0 { return -1; }
            let cmsg = libc::CMSG_FIRSTHDR(&msg);
            if cmsg.is_null() { return -1; }
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let p = libc::CMSG_DATA(cmsg) as *const c_int;
                return std::ptr::read_unaligned(p);
            }
            return -1;
        }
    }
}

/// sendmsg(SCM_RIGHTS): manda 1 fd. Só p/ o spike/teste (em produção o lapada manda). Retorna 0/-1.
#[no_mangle]
pub extern "C" fn fd_send(ctrl_fd: i32, fd_to_send: i32) -> i32 {
    unsafe {
        let mut data: u8 = 0;
        let mut iov = libc::iovec { iov_base: &mut data as *mut u8 as *mut c_void, iov_len: 1 };
        let mut cmsg_buf = [0u8; 64];
        let cmsg = cmsg_buf.as_mut_ptr() as *mut libc::cmsghdr;
        (*cmsg).cmsg_len = libc::CMSG_LEN(4) as _;
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        std::ptr::write_unaligned(libc::CMSG_DATA(cmsg) as *mut c_int, fd_to_send);
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cmsg_buf.as_mut_ptr() as *mut c_void;
        msg.msg_controllen = libc::CMSG_SPACE(4) as _;
        let n = libc::sendmsg(ctrl_fd, &msg, libc::MSG_NOSIGNAL);
        if n == 1 { 0 } else { -1 }
    }
}

/// socketpair(AF_UNIX): preenche out[0],out[1]. Só p/ o spike/teste. Retorna 0/-1.
#[no_mangle]
pub extern "C" fn fd_socketpair(out: *mut i32) -> i32 {
    unsafe {
        let mut sv = [0i32; 2];
        if libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, sv.as_mut_ptr()) != 0 { return -1; }
        *out.add(0) = sv[0];
        *out.add(1) = sv[1];
        0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn recv_roundtrip() {
        unsafe {
            let mut pair = [0i32; 2];
            assert_eq!(fd_socketpair(pair.as_mut_ptr()), 0);
            // cria um pipe; manda a ponta de escrita pelo socketpair.
            let mut pipefd = [0i32; 2];
            assert_eq!(libc::pipe(pipefd.as_mut_ptr()), 0);
            assert_eq!(fd_send(pair[0], pipefd[1]), 0);
            let got = fd_recv(pair[1]);
            assert!(got >= 0, "fd_recv falhou");
            // escreve no fd recebido, lê na ponta original do pipe → mesmo arquivo.
            let b: u8 = 42;
            assert_eq!(libc::write(got, &b as *const u8 as *const c_void, 1), 1);
            let mut r: u8 = 0;
            assert_eq!(libc::read(pipefd[0], &mut r as *mut u8 as *mut c_void, 1), 1);
            assert_eq!(r, 42, "fd recebido não aponta pro mesmo pipe");
        }
    }
}
```

- [ ] **Step 3: Atualizar `rust-engine/include/fdsearch.h`**

```c
#ifndef FDSEARCH_H
#define FDSEARCH_H
#include <stdint.h>
int32_t fd_ping(int32_t a, int32_t b);
int32_t fd_recv(int32_t ctrl_fd);
int32_t fd_send(int32_t ctrl_fd, int32_t fd_to_send);
int32_t fd_socketpair(int32_t* out);
#endif
```

- [ ] **Step 4: Rodar o cargo test (prova a lógica do recvmsg)**

Run: `cd rust-engine && cargo test --release 2>&1 | tail -15`
Expected: `test tests::recv_roundtrip ... ok` (1 passed).

- [ ] **Step 5: Commit**

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
git add rust-engine/src/lib.rs rust-engine/include/fdsearch.h rust-engine/Cargo.toml rust-engine/Cargo.lock
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "feat(rust): fd_recv/fd_send/fd_socketpair + cargo test recvmsg roundtrip (spike a)"
```

---

## Task 0.5.2: Reflection-wrap fd→SocketChannel no native-image (pergunta (b) — CENTERPIECE)

**Files:**
- Create: `api/src/main/java/org/fraudDetection/rust/FdWrap.java`
- Create: `api/src/main/java/org/fraudDetection/rust/SpikeReactor.java`
- Create: `api/src/main/resources/META-INF/native-image/reflect-config.json`
- Modify: `api/src/main/java/org/fraudDetection/rust/RustSearch.java` (+bindings)

- [ ] **Step 1: Descobrir a assinatura do ctor do JDK 21** (a do jvmoonshot é JDK 25, difere)

Run:
```bash
JH="$HOME/.sdkman/candidates/java/current"
"$JH/bin/javap" -p -c -classpath "$JH/lib/src.zip" sun.nio.ch.SocketChannelImpl 2>/dev/null \
  || "$JH/bin/javap" -p sun.nio.ch.SocketChannelImpl 2>&1 | grep -iE 'SocketChannelImpl\(' | head
"$JH/bin/javap" -p sun.nio.ch.SelChImpl 2>&1 | grep -i getFDVal
```
Expected: lista os construtores de `SocketChannelImpl`. **Anotar a assinatura exata** (provável JDK 21: `SocketChannelImpl(SelectorProvider, ProtocolFamily, FileDescriptor, SocketAddress)` ou `(SelectorProvider, FileDescriptor, boolean)`). O `FdWrap` do Step 2 usa essa assinatura — **ajustar conforme a saída**.

- [ ] **Step 2: Criar `FdWrap.java`** (reflection: extrair fd de um channel + embrulhar fd cru)

```java
package org.fraudDetection.rust;

import java.io.FileDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

/** Spike Plano A: embrulha um fd cru em SocketChannel via internals do NIO. */
public final class FdWrap {
    private static final MethodHandle CTOR;
    private static final VarHandle FD_FIELD;
    private static final MethodHandle GET_FD_VAL;

    static {
        try {
            Class<?> impl = Class.forName("sun.nio.ch.SocketChannelImpl");
            MethodHandles.Lookup il = MethodHandles.privateLookupIn(impl, MethodHandles.lookup());
            // ⚠️ ASSINATURA DO JDK 21 — ajustar conforme o javap do Step 1.
            CTOR = il.findConstructor(impl, MethodType.methodType(
                    void.class, SelectorProvider.class, ProtocolFamily.class,
                    FileDescriptor.class, SocketAddress.class));
            FD_FIELD = MethodHandles.privateLookupIn(FileDescriptor.class, MethodHandles.lookup())
                    .findVarHandle(FileDescriptor.class, "fd", int.class);
            Class<?> selch = Class.forName("sun.nio.ch.SelChImpl");
            GET_FD_VAL = MethodHandles.privateLookupIn(selch, MethodHandles.lookup())
                    .findVirtual(selch, "getFDVal", MethodType.methodType(int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int extractRawFd(SocketChannel ch) throws Throwable {
        return (int) GET_FD_VAL.invoke(ch);
    }

    public static SocketChannel wrapFd(int rawFd) throws Throwable {
        FileDescriptor jfd = new FileDescriptor();
        FD_FIELD.set(jfd, rawFd);
        return (SocketChannel) CTOR.invoke(SelectorProvider.provider(),
                StandardProtocolFamily.INET, jfd, (SocketAddress) null);
    }

    private FdWrap() {}
}
```

- [ ] **Step 3: Criar `reflect-config.json`** (native-image precisa registrar os internals)

`api/src/main/resources/META-INF/native-image/reflect-config.json`:
```json
[
  { "name": "sun.nio.ch.SocketChannelImpl",
    "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "sun.nio.ch.SelChImpl", "allDeclaredMethods": true },
  { "name": "java.io.FileDescriptor",
    "fields": [ { "name": "fd" } ], "allDeclaredConstructors": true }
]
```

- [ ] **Step 4: Criar `SpikeReactor.java`** (main isolado: loopback → extrai fd → embrulha → read/write)

```java
package org.fraudDetection.rust;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/** Spike Fase 0.5: prova reflection-wrap no native-image. TEMPORÁRIO. */
public final class SpikeReactor {
    public static void main(String[] args) throws Throwable {
        ServerSocketChannel srv = ServerSocketChannel.open();
        srv.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ((InetSocketAddress) srv.getLocalAddress()).getPort();

        SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        SocketChannel accepted = srv.accept();

        // extrai o fd cru do accepted, embrulha num NOVO channel (o teste do Plano A).
        int rawFd = FdWrap.extractRawFd(accepted);
        SocketChannel wrapped = FdWrap.wrapFd(rawFd);
        wrapped.configureBlocking(true);

        client.write(ByteBuffer.wrap("PING".getBytes()));
        ByteBuffer buf = ByteBuffer.allocate(4);
        while (buf.hasRemaining()) {
            if (wrapped.read(buf) < 0) break;
        }
        String got = new String(buf.array(), 0, buf.position());
        System.out.println("SPIKE wrapped.read=" + got);
        if (!"PING".equals(got)) { System.err.println("SPIKE-A FAIL"); System.exit(1); }
        System.out.println("SPIKE-A OK (reflection-wrap funciona no native-image -> Plano A)");
    }
}
```

- [ ] **Step 5: Build native-image local do spike** (CE 21.0.2, ~20s)

Run:
```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
JH="$HOME/.sdkman/candidates/java/current"; OUT=/tmp/spike05; rm -rf "$OUT"; mkdir -p "$OUT"
"$JH/bin/javac" --add-modules org.graalvm.nativeimage \
  --add-exports java.base/sun.nio.ch=ALL-UNNAMED -d "$OUT" \
  api/src/main/java/org/fraudDetection/rust/FdWrap.java \
  api/src/main/java/org/fraudDetection/rust/SpikeReactor.java 2>&1 | tail
"$JH/bin/native-image" -cp "$OUT" \
  -H:+UnlockExperimentalVMOptions \
  -H:ReflectionConfigurationFiles=api/src/main/resources/META-INF/native-image/reflect-config.json \
  --no-fallback -o "$OUT/spike" org.fraudDetection.rust.SpikeReactor 2>&1 | tail -25
echo "ni exit: ${PIPESTATUS[0]}"
```
Expected: build OK (ou erro de reflection/SVM-substituição → informa o resultado de (b)).

- [ ] **Step 6: Rodar + DECISION GATE G0.5**

Run: `/tmp/spike05/spike; echo "exit: $?"`
- **`SPIKE-A OK`** → **Plano A viável** (reflection-wrap funciona no native-image; reusamos o NioServer). 
- **build falha / runtime exception / read errado** → **Plano A inviável → Plano B** (raw I/O via `@CFunction fd_read`/`fd_write` + epoll; reescreve a camada de I/O do reactor, mas é certo).

- [ ] **Step 7: Commit do resultado (spike + decisão)**

```bash
git add api/src/main/java/org/fraudDetection/rust/FdWrap.java \
        api/src/main/java/org/fraudDetection/rust/SpikeReactor.java \
        api/src/main/resources/META-INF/native-image/reflect-config.json
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "spike(fase0.5): reflection-wrap fd->SocketChannel no native-image — G0.5 = <Plano A|Plano B>"
```

---

## Task 0.5.3: Documentar G0.5 + fechar o spike

- [ ] **Step 1:** Anotar no spec/ledger: G0.5 resultado (Plano A ou B), a assinatura do ctor JDK 21 descoberta, e se `fd_recv` cargo test passou.
- [ ] **Step 2:** Decidir limpeza: `SpikeReactor.java` é temporário (removível na Fase 2); `FdWrap.java` fica SE Plano A (vira parte do receiver); some SE Plano B. `fd_recv`/`fd_send`/`fd_socketpair` ficam (o receiver da Fase 2 usa `fd_recv`).
- [ ] **Step 3: Commit** do doc.

---

## Fases seguintes (planejar SÓ após G0.5)

- **Fase 1 — lapada + compose:** crate `lapada/` (adaptar o paste do usuário: FD_UPSTREAMS, SCM_RIGHTS send, round-robin, cooldown, preconnect, accept4); `docker-compose.yml` 3-services (troca haproxy por lapada; Unix sockets via volume; redistribui mem 159→167M; cpuset 0/1/2; /ready forwarda); Dockerfile builda lapada+api. Smoke: sobe, /ready 200, request passa.
- **Fase 2 — receiver + reactor:** `fd_listener_init`/`fd_next_client` (Rust @CFunction) + thread receiver no `Main` (remove o probe Fase 0); integração no `NioServer` conforme **Plano A** (inject SocketChannel embrulhado no Selector) ou **Plano B** (epoll + read/write raw via @CFunction).
- **Fase 3 — gates + medição:** G2 E=0 (`ExactAgree` 0/54100 — busca inalterada) + 2 oráculos pelo caminho lapada→api; G3 footprint (cgroup anon api; HAProxy eliminado; ≤350M); G4 prévia Mac Mini (árbitro do p99).

Cada fase = seu próprio plano após a anterior fechar.

---

## Self-Review

- **Spec coverage:** Fase 0.5 do spec (§11) = spike das perguntas (a)+(b) e decisão A/B → Tasks 0.5.1 (a, cargo test) + 0.5.2 (b, reflection no native-image, decision gate G0.5) + 0.5.3 (doc). Fases 1-3 (§11) → outline (planejadas após G0.5, por design). E=0 (§10) não é tocado pelo spike (correto). Gates: G0.5 explícito; G1-G4 nas fases seguintes.
- **Placeholder scan:** sem TBD/TODO; código real em cada step. A assinatura do ctor JDK 21 é DESCOBERTA no Step 1 (javap) e o `FdWrap` tem a assinatura mais-provável com nota explícita de ajuste — é o objeto do spike, não placeholder.
- **Type/símbolo consistency:** `fd_recv`/`fd_send`/`fd_socketpair` (rust) ↔ `.h` ↔ (bindings RustSearch na Fase 2). `FdWrap.extractRawFd`/`wrapFd` ↔ uso no `SpikeReactor`. reflect-config nomeia `sun.nio.ch.SocketChannelImpl`/`SelChImpl`/`FileDescriptor.fd` usados no `FdWrap`. Consistente.
