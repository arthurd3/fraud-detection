# Rust KdTree Search Port — Fase 0 (link-proof) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provar (ou refutar) que um staticlib Rust `extern "C"` linka e roda dentro do binário GraalVM native-image via a C-interface (`@CFunction`) — ANTES de portar a busca real.

**Architecture:** Crate Rust (`rust-engine/`, `crate-type=["staticlib"]`) expõe `fd_ping(a,b)->a+b`. Uma classe Java de binding (`@CContext`+`@CFunction`) declara `fdPing`. O boot do `Main` chama `fdPing(2,3)` e loga/asserta `5`. O Dockerfile ganha um passo `cargo build` e o `pom.xml` ganha `-H:CLibraryPath`. Se o `docker build` + run imprimir `fd_ping(2,3)=5`, **G0 passa**; se o link falhar (como FFM falhou na Onda 5), **PARA**.

**Tech Stack:** Rust (staticlib), GraalVM native-image C-interface (`org.graalvm.nativeimage.c`), Maven native-maven-plugin, Docker (Oracle GraalVM 21 builder).

> **Spike honesto:** os incantamentos exatos da C-interface (coordenadas do SDK, resolução de header/lib) são version-dependent. Este plano dá a config best-known + fallbacks; **tunar os flags É o trabalho da Fase 0.** Espere 1-3 rebuilds (cada `docker build` da imagem cheia leva minutos).

---

## File Structure

- `rust-engine/Cargo.toml` — manifesto do crate staticlib (novo)
- `rust-engine/src/lib.rs` — `fd_ping` (novo; vira o motor na Fase 1)
- `rust-engine/include/fdsearch.h` — header C declarando as funções (novo)
- `api/src/main/java/org/fraudDetection/rust/FdDirectives.java` — Directives da C-interface (novo)
- `api/src/main/java/org/fraudDetection/rust/RustSearch.java` — binding `@CFunction` (novo; ganha `fdSearch` na Fase 1)
- `api/src/main/java/org/fraudDetection/Main.java` — +probe de boot Fase 0 (modificar; revertido na Fase 1)
- `api/pom.xml` — +dep `graal-sdk` (provided) +buildArg `-H:CLibraryPath` (modificar)
- `Dockerfile` — +passo cargo build + stage do `.a`/`.h` antes do `mvnw` (modificar)

---

## Task 0.1: Crate Rust staticlib com `fd_ping`

**Files:**
- Create: `rust-engine/Cargo.toml`
- Create: `rust-engine/src/lib.rs`
- Create: `rust-engine/include/fdsearch.h`

- [ ] **Step 1: Criar `rust-engine/Cargo.toml`**

```toml
[package]
name = "fdsearch"
version = "0.1.0"
edition = "2021"

[lib]
name = "fdsearch"
crate-type = ["staticlib"]   # => libfdsearch.a

[profile.release]
opt-level = 3
lto = true
panic = "abort"              # evita unwinding/symbols extras no link
codegen-units = 1
```

- [ ] **Step 2: Criar `rust-engine/src/lib.rs`**

```rust
//! Fase 0: link-proof. Prova que um staticlib Rust extern "C" linka no
//! binário GraalVM native-image via @CFunction. `fd_ping` vira `fd_init`/
//! `fd_search` (o motor de busca) na Fase 1.
#[no_mangle]
pub extern "C" fn fd_ping(a: i32, b: i32) -> i32 {
    a + b
}
```

- [ ] **Step 3: Criar `rust-engine/include/fdsearch.h`**

```c
#ifndef FDSEARCH_H
#define FDSEARCH_H
#include <stdint.h>
int32_t fd_ping(int32_t a, int32_t b);
#endif
```

- [ ] **Step 4: Build local de sanidade (se houver cargo na máquina; senão pular p/ Docker na 0.6)**

Run: `cd rust-engine && cargo build --release && ls -la target/release/libfdsearch.a`
Expected: `libfdsearch.a` existe. (Se não houver cargo local, ok — o build real é no Docker, Task 0.6.)

- [ ] **Step 5: Commit**

```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
git add rust-engine/Cargo.toml rust-engine/src/lib.rs rust-engine/include/fdsearch.h
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "feat(rust): crate fdsearch staticlib + fd_ping (Fase 0 link-proof)"
```

---

## Task 0.2: Dependência GraalVM SDK + CLibraryPath no pom

**Files:**
- Modify: `api/pom.xml`

- [ ] **Step 1: Adicionar a dependência `graal-sdk` (provided) — necessária p/ compilar o binding `@CContext`/`@CFunction`**

Inserir um bloco `<dependencies>` logo após `</properties>` (linha 18). A versão DEVE casar com o GraalVM do builder (`native-image:21` → graal-sdk 21.0.x; ajustar se o link reclamar de versão):

```xml
    <dependencies>
        <dependency>
            <groupId>org.graalvm.sdk</groupId>
            <artifactId>graal-sdk</artifactId>
            <version>21.0.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
```

> Fallback se `org.graalvm.nativeimage.c.*` não resolver: trocar por `org.graalvm.sdk:nativeimage:24.1.1` (SDK novo, API desmembrada). Alinhar com a saída de `native-image --version` no builder.

- [ ] **Step 2: Adicionar `-H:CLibraryPath` ao profile `native` (apontando p/ onde o Dockerfile vai stage o `.a`+`.h`)**

No `api/pom.xml`, dentro de `<buildArgs>` do profile `native` (após a linha 88 `-H:+StaticExecutableWithDynamicLibC`), inserir:

```xml
                                <buildArg>-H:CLibraryPath=/src/api/clib</buildArg>
```

(`/src/api/clib` = diretório no builder Docker onde o `.a` e o `.h` serão copiados na Task 0.5.)

- [ ] **Step 3: Commit**

```bash
git add api/pom.xml
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "build(native): graal-sdk provided + -H:CLibraryPath (Fase 0)"
```

---

## Task 0.3: Binding Java da C-interface

**Files:**
- Create: `api/src/main/java/org/fraudDetection/rust/FdDirectives.java`
- Create: `api/src/main/java/org/fraudDetection/rust/RustSearch.java`

- [ ] **Step 1: Criar `FdDirectives.java`**

```java
package org.fraudDetection.rust;

import org.graalvm.nativeimage.c.CContext;
import java.util.Collections;
import java.util.List;

/** Directives da C-interface: header + lib estática linkados no native-image. */
public final class FdDirectives implements CContext.Directives {
    @Override public List<String> getHeaderFiles() {
        return Collections.singletonList("\"fdsearch.h\"");
    }
    @Override public List<String> getLibraries() {
        return Collections.singletonList("fdsearch"); // => libfdsearch.a via CLibraryPath
    }
}
```

- [ ] **Step 2: Criar `RustSearch.java`**

```java
package org.fraudDetection.rust;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;

/** Binding p/ o motor Rust (Fase 0: só fd_ping; Fase 1 ganha fd_init/fd_search). */
@CContext(FdDirectives.class)
public final class RustSearch {
    @CFunction("fd_ping")
    public static native int fdPing(int a, int b);

    private RustSearch() {}
}
```

- [ ] **Step 3: Compilar (sanidade, se houver JDK 21 + graal-sdk local; senão validado no Docker)**

Run: `cd api && ./mvnw -q -DskipTests compile`
Expected: compila sem erro (resolve `org.graalvm.nativeimage.c.*`). Se falhar por classe não encontrada → ajustar a versão do `graal-sdk` (Task 0.2 fallback).

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/org/fraudDetection/rust/
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "feat(rust): binding Java @CFunction (FdDirectives + RustSearch.fdPing)"
```

---

## Task 0.4: Probe de boot no `Main` (temporário, revertido na Fase 1)

**Files:**
- Modify: `api/src/main/java/org/fraudDetection/Main.java`

- [ ] **Step 1: Adicionar a chamada de prova no início do `main`**

Em `Main.java`, logo após `String d = dataPath();` (linha 13), inserir:

```java
        // FASE 0 (link-proof, temporário): prova que o staticlib Rust linka
        // e roda no binário native-image. Removido na Fase 1.
        int ping = org.fraudDetection.rust.RustSearch.fdPing(2, 3);
        System.out.println("FASE0 fd_ping(2,3)=" + ping);
        if (ping != 5) { System.err.println("FASE0 FAIL"); System.exit(3); }
        System.out.println("FASE0 OK");
```

- [ ] **Step 2: Commit**

```bash
git add api/src/main/java/org/fraudDetection/Main.java
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "test(rust): Main boot probe fd_ping (Fase 0, temporário)"
```

---

## Task 0.5: Dockerfile — cargo build + stage do `.a`/`.h`

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1: Instalar Rust + buildar o staticlib + stage antes do `mvnw`**

Substituir o bloco builder (linhas 1-7) por:

```dockerfile
# ---- builder: binário nativo AOT (Oracle GraalVM 21) + motor Rust (staticlib) ----
FROM container-registry.oracle.com/graalvm/native-image:21 AS builder
WORKDIR /src
# Rust toolchain (p/ libfdsearch.a). microdnf é o gerenciador da imagem.
RUN microdnf install -y gcc tar gzip && microdnf clean all \
 && curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal \
 && /root/.cargo/bin/rustc --version
COPY rust-engine/ ./rust-engine/
RUN cd rust-engine && /root/.cargo/bin/cargo build --release \
 && mkdir -p /src/api/clib \
 && cp target/release/libfdsearch.a /src/api/clib/ \
 && cp include/fdsearch.h /src/api/clib/
COPY api/ ./api/
# api/default.iprof versionado (PGO offline). O profile `native` consome via --pgo;
# linka libfdsearch.a via -H:CLibraryPath=/src/api/clib (ver pom.xml).
RUN cd api && ./mvnw -q -Pnative -DskipTests package      # => target/api (ELF)
```

> Nota: `COPY rust-engine/` ANTES de `COPY api/` (cache: o Rust muda menos). Se `microdnf` não tiver `curl`, usar `microdnf install -y curl`. Se rustup falhar offline, fallback: `microdnf install -y rust cargo` (versão da distro).

- [ ] **Step 2: Commit**

```bash
git add Dockerfile
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "build(docker): cargo build libfdsearch.a + stage p/ CLibraryPath (Fase 0)"
```

---

## Task 0.6: Build + run + verificar G0 (DECISION GATE)

**Files:** nenhum (validação)

- [ ] **Step 1: Build da imagem (sob $HOME, confinamento snap; saída via | cat)**

Run:
```bash
cd /home/arthurd3/Desktop/RINHA-BECK-END/fraudDetection
docker build --progress=plain -t rinha-fraud:fase0 . 2>&1 | cat | tail -40
```
Expected: build conclui sem erro de link. **Falhas a observar:** `undefined reference to fd_ping` (lib não linkada → checar CLibraryPath/nome), `fdsearch.h not found` (header não no include path → checar stage/Directives), erro de versão `org.graalvm.nativeimage.c` (ajustar graal-sdk, Task 0.2).

- [ ] **Step 2: Rodar o binário e verificar o probe**

Run:
```bash
docker run --rm rinha-fraud:fase0 2>&1 | cat | grep -E 'FASE0|kdtree' | head
```
Expected: `FASE0 fd_ping(2,3)=5` seguido de `FASE0 OK`.

- [ ] **Step 3: DECISION GATE (G0)**

- **G0 PASS** (imprimiu `FASE0 OK`): a C-interface linka. ✅ Seguir p/ planejar Fase 1 (port da busca). Atualizar o ledger (nova edição: G0 verde).
- **G0 FAIL** (erro de link/compilação irreparável com os fallbacks): **PARAR.** A C-interface não serve (como FFM na Onda 5). Documentar o erro exato no ledger; reavaliar (sidecar process? abortar o port?). NÃO prosseguir p/ Fase 1.

- [ ] **Step 4: Commit do resultado (ledger)**

```bash
# adicionar entrada no docs/PERFORMANCE_LEDGER.md (G0 PASS/FAIL + erro se houver)
git add docs/PERFORMANCE_LEDGER.md
git -c user.name=arthurd3 -c user.email=arthur_camposl@yahoo.com commit -m "docs(ledger): Fase 0 link-proof G0 = <PASS|FAIL>"
```

---

## Fases seguintes (planejar SÓ após G0 PASS)

- **Fase 1 — port da busca:** `fd_init(path)` (mmap RKD6 + parse header) + `fd_search(query14)->fraudCount` em Rust (prime+BBF+rerank, replicando `KdTree.java`/`KdLayout`/`DistanceFunctions`); `RustSearch` ganha os bindings; `KdTree.search` delega via flag `fd.engine=rust|java`; remover o probe Fase 0 do `Main` e chamar `fd_init` no boot.
- **Fase 2 — cravar E=0:** `RustEquiv`/`ExactAgree` cruzando Rust vs `expected_*` vs oráculo Java `:k2-test` nas 54.100 → 0 mismatches (atenção ao arredondamento `floor(x+0.5)` do `main.c`).
- **Fase 3 — medir:** `BenchSearch` (Rust vs Java), footprint (cgroup anon — esperado ~inalterado), prévia opcional (p99 ~inalterado).

Cada fase = seu próprio plano detalhado (escrito após a anterior passar).

---

## Self-Review

- **Spec coverage:** Fase 0 do spec (§8) = link-proof BLOQUEANTE → coberto pelas Tasks 0.1–0.6 + decision gate. Fases 1-3 do spec → outline (planejadas após G0, por design — de-risca antes de detalhar). Interface FFI (§4) `fd_ping` agora, `fd_init`/`fd_search` na Fase 1. Build/integração (§7) coberto (0.2/0.5). E=0 (§6) → Fase 2 (não toca a Fase 0, correto). Gates: G0 explícito (0.6); G1-G6 nas fases seguintes.
- **Placeholder scan:** sem TBD/TODO; todo passo tem código/comando reais. As incertezas (versão graal-sdk, microdnf curl, resolução header/lib) estão com fallbacks explícitos — não placeholders, são o objeto do spike.
- **Type/símbolo consistency:** `fd_ping`(rust) ↔ `"fd_ping"`(@CFunction) ↔ `fdPing`(Java) ↔ `fdsearch.h`. `libfdsearch.a` ↔ `getLibraries()="fdsearch"` ↔ `-H:CLibraryPath=/src/api/clib`. Consistente.
