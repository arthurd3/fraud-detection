# Spec — Portar `KdTree.search` para um motor em Rust via GraalVM `@CFunction`

- **Data:** 2026-05-29 · **revisado 2026-06-01** (pós-lapada; ver §0).
- **Branch:** `feat/rust-kdtree-search` (a partir de `main` @ `8743bf7`); `submission` permanece image-only.
- **Tipo:** port pontual (1 módulo) Java → Rust, dentro do binário GraalVM native-image.
- **Status:** design reconfirmado em brainstorming 2026-06-01 (4 decisões via AskUserQuestion); pendente review do spec → writing-plans → implementação (implementação feita diretamente, usuário valida).

---

## 0. Revisão 2026-06-01 (pós-lapada) — AUTORITATIVO onde divergir do corpo

O corpo (§1–§13) foi escrito em 2026-05-29, ANTES de a arquitetura **lapada (FD-passing)** virar produção. Esta revisão reconfirma o design com 4 decisões do usuário e corrige 6 pontos. **Onde §0 divergir do corpo abaixo, §0 manda.**

**Decisões (brainstorming 2026-06-01):** (1) alvo = a busca KD-tree inteira; (2) estratégia = **port direto** (sem spike de medição), valida E=0 e mede na prévia; (3) implementador = **implementação feita diretamente, usuário valida**; (4) fronteira = **Approach A** (Java passa só `float[14]`; Rust faz `q16`+`round4` também).

**Deltas vs o corpo:**
1. **Baseline atual = lapada #36** (`aa7fde8`, imagem `arthurd3/rinha-fraud:lapada-api-mlock`): **final_score 5592.63 / p99 2.554905 ms / E=0**. NÃO mais o K2 4875/13 ms citado no §1/§15. `detection_score` segue 3000 (E=0); 100% do gap p/ 6000 é p99_score. A conclusão **host-bound** do §1 **permanece** (compute ~18 µs de ~2,55 ms).
2. **G0 (link-proof) JÁ ESTÁ FEITO.** O lapada já linka e roda `@CFunction`+staticlib `libfdsearch.a` no native-image em produção (`fd_listener_init`/`fd_next_client`/`fd_mlock_region`). **Pula-se a Fase 0**; vai direto pra Fase 1 (port). O risco #1 do §11 (não-linkar) está RETIRADO.
3. **Approach A — fronteira:** `fd_search` recebe ponteiro p/ **`float[14]`** (o `s.queryVector` semântico, já `r4`'d no parse), NÃO `double[14]`. O Rust replica `Quantizer.q16(v)=(short)Math.round(clamp(v)*10000f)` (math **f32**) E `round4(v)=Math.round((double)v*1e4)/1e4` (math **f64**), exatamente como `KdTree.prepareSearch`. Resolve a ambiguidade §4(`double[14]`) vs §5(Rust faz q16).
4. **Arredondamento (precisão do risco #1, §6):** os refs no `.kdt` foram quantizados com **Java `Math.round` (half-up)** no build (`Quantizer.q16` via `KdTreeBuilder`); o parse (`r4`) usa o mesmo. Como TODO input de `r4`/`q16` é clampado a ≥0 (parser `clamp`: `x<0?0:…`) ou é o literal −1 (sentinela v[5]/v[6]; `round4(−1)=−1` em qualquer convenção), Java `Math.round` **coincide** com o `round()` do `main.c` (half-away) p/ este dataset. **Ação:** Rust replica o **`Math.round` EXATO do OpenJDK** (bit-manip de `to_bits`) — o invariante com que o índice foi assado — versão float (`q16`, `SIGNIFICAND_WIDTH=24`/`EXP_BIAS=127`) e double (`round4`, `53`/`1023`). NÃO usar `f32::round()`/`f64::round()` (half-away) nem `(x+0.5).floor()` (tem o bug de double-round).
5. **Validação (corrige §6/§9/§12): `@CFunction` NÃO roda no HotSpot** (só no native-image) ⇒ "ExactAgree engine=rust" no JVM é impossível. Gate E=0 (SAGRADO):
   - **G2a — agreement em Rust (`cargo test`):** o crate lê `references.kdt` (RKD6) + `rinha-de-backend-2026/…/test-data.json` (54.100), roda `fd_search` em cada entrada e compara `approved`+`fraud_score` com `expected_*` (= ground truth `main.c`). **0/54.100** ou NÃO shipa. Prova Rust==ground-truth direto.
   - **G2b — path Java intacto:** o `ExactAgree` Java existente segue **0/54.100** (o caminho Java não foi tocado; vira oráculo atrás da flag).
   - **G2c — e2e:** k6 oficial sobre o binário nativo (stack lapada) → E=0 (fp0 fn0 http_errors0), como o gate do lapada.
6. **Posse do índice + mlock + footprint:** mantém "Rust é dono do mmap" (`fd_init(path)`). Consolida o lever **Onda 32 `mlock(pts)`** dentro do Rust (já tem `fd_mlock_region`/`fd_raise_memlock_rlimit`). Sob flag=rust, o binário nativo **pula `KdTree.load`** → Java não aloca `topSlot` (`int[n]`≈12 MB on-heap) nem `topBbox`/scratch → pequena REDUÇÃO de heap Java (bônus, não é o objetivo). `ExactAgree`/oráculo Java (HotSpot) seguem usando `KdTree.load`.

**Caveat honesto (achado no código 2026-06-01):** `KdTree.distSumI16:341-357` afirma que o **GraalVM Native já auto-vetoriza** o loop escalar i16 p/ AVX2. Se procede, o ganho de SIMD do Rust é **menor** que o naïve; a vantagem real do Rust passa a ser o codegen LLVM `-O3` no CONJUNTO da busca (heap/branch/prefetch/rerank), não só o kernel. Combinado com o p99 host-bound, **o resultado na prévia pode ser NEUTRO** — desfecho aceitável e informativo (foi a escolha "port direto + medir"). O kernel Rust usará **AVX2 explícito** (`_mm256_madd_epi16(diff,diff)` com lanes 14–15 mascaradas a 0), não só confiar no auto-vec.

---

## 1. Contexto & motivação

Sessão 2026-05-29 estabeleceu (com número, 3×) que no fraudDetection da Rinha:
- `final_score = p99_score + detection_score`; detection saturado em **3000** (E=0); 100% do gap em p99.
- **Compute é <0,1% do p99 tail** (parser 1,6µs + KdTree ~15µs + rerank <1µs ≈ 18µs vs p99 13.000–37.000µs).
- O p99 é **dominado por variância/contenção do host** (mesmo binário K2 deu 35,81ms no #6234 e **13,33ms / final 4875,15** numa run mais quieta — prévia G6).
- O "imposto" de runtime é **49 MiB anônimos (heap GraalVM)**; aliviar isso exigiria reescrita COMPLETA (descartada pelo usuário).

**Decisão do usuário (brainstorming):** portar **apenas um ponto específico** — a busca KD-tree inteira — para **Rust**, integrada via a C-interface do GraalVM (`@CFunction`).

**Expectativa honesta gravada no design:** isto é **exploração / aprendizado** + um motor de busca Rust reaproveitável. **NÃO** sobe o score (compute não é o gargalo) e **NÃO** alivia footprint (o runtime Java de 49 MiB permanece). O valor é: (a) aprender interop Rust↔GraalVM-native, (b) **medir empiricamente** o impacto real (esperado ≈0 no p99), (c) um kernel de busca mais limpo/rápido.

## 2. Goal & Non-goals

**Goal:** substituir o corpo de `KdTree.search(ConnectionState)` por **1 chamada nativa por request** a um motor de busca escrito em Rust (`extern "C"`), preservando **E=0 estrito** e o footprint atual.

**Non-goals:**
- NÃO é reescrita completa do serviço (server/parser/response continuam em Java).
- NÃO se espera ganho de p99/score nem redução de footprint (documentado; será medido).
- NÃO portar o kernel de distância isolado (chamado ~300×/query → overhead de FFI por chamada regrediria). O boundary é a busca inteira (1 call/request).
- NÃO portar o parser (1,6µs, já exit-fast).
- NÃO mexer no `docker-compose.yml` da submission (validador exige `cpus:`; nada disso muda).
- NÃO regenerar o índice — reusa o `references.kdt` RKD6 baked.

## 3. Arquitetura

Fronteira limpa: **Java = casca de I/O; Rust = motor de busca + índice.**

```
HTTP request
   │  (Java, inalterado)
   ▼
NioServer (epoll) → HttpParser → FraudRequestParser → query[14] f64 (round4'd)
   │
   ▼  1 chamada @CFunction por request
fd_search(query_ptr) ──────────────► [RUST]  prime + BBF + rerank double
                                       │       sobre mmap(references.kdt RKD6)
   ◄───────── i32 fraudCount (0..5) ───┘
   │
   ▼  (Java, inalterado)
HttpResponseWriter (resposta canned por fraudCount) → NioServer write
```

- **Rust é dono do índice:** `fd_init(path)` faz `mmap` read-only + parseia o header RKD6. Java não precisa mais mmapear em produção (mantém `KdTreeIO`/`KdTree` Java apenas como **oráculo de validação**, atrás de flag).
- **Footprint:** o índice continua mmap (mesmas páginas, só que owned pelo mmap do Rust) → footprint inalterado, como esperado. Heap Java de 49 MiB permanece.

## 4. Interface FFI (mínima)

```c
// Rust (extern "C"), linkado estaticamente no binário native-image
int32_t fd_init(const char* kdt_path);          // boot: mmap + parse RKD6; 0=ok, <0=erro
int32_t fd_search(const double* query14);        // por request: retorna fraudCount 0..5
```

- Java declara via a C-interface do GraalVM: `@CFunction` + `@CContext(FdDirectives.class)` (já existe). Query passada como ponteiro para **`float[14]`** (Approach A, §0): Java copia `s.queryVector` num staging buffer **direct** (process-wide, single-thread; ~56 B) e passa o endereço via `BufferAddr.addressOf` (mesmo padrão já provado do `fd_mlock_region`). Rust faz `q16`+`round4` a partir desses floats. Fraude: `fd_search` retorna o `fraudCount` (i32); opcionalmente escreve os 5 bits de fraude no staging p/ paridade de `s.knnFraud`.
- `fraudCount` → Java mapeia para resposta canned (mesma tabela do `HttpResponseWriter` atual). Superfície FFI minúscula, sem structs complexas.
- `fd_init` chamado 1× no boot (`Main`/inicialização), recebendo o path resolvido de `DATA_PATH/references.kdt`.

## 5. O motor de busca em Rust (o que portar)

Replicar, do `KdTree.java` + `KdLayout.java` + `DistanceFunctions.java` + `KdScratch.java`:
- **Formato RKD6** (`KdTreeIO.java`): header `"RKD6"(4) + ver i32 + n i32 + dims i32(=14) + stride i32(=19) + root i32` ; depois `pts` (n·stride·2 bytes, i16 LE) + `origId` (n·3 bytes, uint24 LE). Layout do nó (STRIDE=19 shorts): 14 dims permutadas + leftAndDim + right + fraud (ler `KdLayout` para offsets exatos).
- **`prepareSearch`:** permutar query → `permutedQueryI16[19]` (via `Quantizer.q16` + `DIM_PERMUTATION`), pré-computar `queryRound4[14]`.
- **`prime`** (beam-of-2 greedy descent ×2), **`descendBBF`** (heap best-first, prune por slab-sum vs i16-sum do 5º, early-exit H2), coleta no pool.
- **`heapsortByOrig`** (3-arg: ordena `poolTreeIdx`/`poolOrig`/`poolI16Sum` por origId asc).
- **rerank:** dedup por origId + skip H2 (`poolI16Sum[p] > peekSumFinalI16`) + `sqDistDoubleLikeC(queryRound4, refI16)` em f64 + insertion-sort top-5 (`<` estrito).
- **decisão:** `fraudCount` = nº de fraudes no top-5; `approved = fraudCount/5 < 0.6` (≥3 → negado); tie-break **menor origId** (já garantido pela ordenação).
- Scratch pré-alocada (uma vez), **zero-alloc por query**.
- `-C target-cpu=x86-64-v3` (casar Haswell/AVX2 do native-image) para a auto-vetorização do loop de distância.

## 6. E=0 — fidelidade (invariante SAGRADO)

Ground truth = `data-generator/main.c` (kNN k=5 sq-euclid brute sobre refs **round4** f64; `approved = fraud_n/5 < 0.6`; tie-break menor índice). Java `:k2-test` já é E=0 contra ele.

- **f64 IEEE-754 é idêntico C↔Java↔Rust** → o grosso casa por construção.
- ⚠️ **Arredondamento (risco #1) — ver §0-delta-4:** replicar o **`Math.round` EXATO do OpenJDK** (bit-manip via `to_bits`, half-up COM a correção do bug de double-rounding) — NÃO `(x+0.5).floor()` (tem o bug) nem `f32/f64::round()` (half-away). É o invariante com que o `.kdt` foi assado (`Quantizer.q16`) e o parse (`r4`) operam. Duas versões: float (`q16`, `SIGNIFICAND_WIDTH=24`/`EXP_BIAS=127`) e double (`round4`, `53`/`1023`). Como todo input é clampado ≥0 ou é o literal −1, isto coincide com o `round()` half-away do `main.c` — mas o alvo é casar com o ÍNDICE, não com o `.c`.
- **Tie-break / ordenação estável:** replicar `heapsortByOrig` (menor origId vence) exatamente.
- **Gate G2 (bloqueante) — ver §0-delta-5:** `@CFunction` não roda no HotSpot ⇒ a agreement do Rust é via **`cargo test`** (lê `.kdt`+`test-data.json`, roda `fd_search` nas 54.100, compara `approved`+`fraud_score` vs `expected_*` = ground truth). **0/54.100** ou não shipa. + `ExactAgree` Java segue **0/54.100** (path Java intacto) + e2e k6 nativo **E=0**.

## 7. Build & integração

- Adicionar **toolchain Rust** ao estágio builder do `Dockerfile` (cargo + target x86-64). Crate novo `rust-engine/` (ou `api/rust-engine/`) → `cargo build --release` → `libfdsearch.a` (staticlib).
- GraalVM native-image linka o `.a`: `-H:CLibraryPath=<dir>` + `@CLibrary("fdsearch")` (ou `-H:+UnlockExperimentalVMOptions` + flags de linker). Compatível com `-H:+StaticExecutableWithDynamicLibC` (link estático do .a Rust dentro do binário).
- `pom.xml` (profile native): adicionar os buildArgs da C-interface; ordem do build = cargo ANTES do native-image.
- Manter `-O3 / -march=x86-64-v3 / --gc=serial / -R:MaxHeapSize=48m / --pgo` atuais.

## 8. Fasamento

- **Fase 0 — link-proof (BLOQUEANTE, ~1h):** `fd_ping(a,b)->a+b` em Rust `extern "C"`, linkado no native-image via `@CFunction`, chamado do Java no boot, imprime o resultado. **Prova que o toolchain + C-interface buildam e rodam.** Se falhar (como FFM falhou na Onda 5), **PARA** e reavalia — antes de qualquer port real.
- **Fase 1 — port da busca:** implementar o motor Rust (mmap RKD6 + prime/BBF/rerank) + `fd_init`/`fd_search` + delegar `KdTree.search` para ele (flag p/ alternar Java/Rust).
- **Fase 2 — cravar E=0:** ExactAgree 0/54100 + diff vs oráculo Java; nail arredondamento/tie-break.
- **Fase 3 — medir:** BenchSearch (Rust vs Java, ns/search), footprint (mostrar ~inalterado), prévia opcional (mostrar p99 ~inalterado).

## 9. Gates

| Gate | O quê | Critério |
|---|---|---|
| **G0** | Fase 0 link-proof | `@CFunction` Rust builda + roda no native-image |
| **G1** | build | `mvnw -Pnative` + cargo OK; binário gerado |
| **G2** | **E=0** | `ExactAgree` 0/54100 + diff top-5/fraudCount vs oráculo Java `:k2-test` = 0 |
| **G3** | BenchSearch | ns/search Rust vs Java (o número de "aplicar o conhecimento") |
| **G4** | footprint | anon RSS / cgroup ≈ inalterado (~49 MiB) — confirma footprint-neutro |
| **G5** | smoke | compose up + `/ready` 200 + 2 oráculos corretos pelo LB |
| **G6** | prévia (opcional) | p99 ≈ inalterado (mostra empiricamente que compute não move o tail) |

## 10. Arquivos a criar/modificar

- **Criar:** `rust-engine/` (crate: `Cargo.toml`, `src/lib.rs` com `fd_init`/`fd_search`/`fd_ping`, módulos kdtree/dist/io); build → `libfdsearch.a`.
- **Criar (Java C-interface):** classe `RustSearch` com `@CFunction`/`@CLibrary` declarando `fd_init`/`fd_search`.
- **Modificar:** `KdTree.java` — `search()` delega para `RustSearch.fdSearch(...)` (flag `fd.engine=rust|java`; Java vira oráculo); `Main`/init chama `fd_init` no boot.
- **Modificar:** `api/pom.xml` (buildArgs C-interface + CLibraryPath), `Dockerfile` (estágio cargo + copiar `.a`).
- **Criar (teste):** `RustEquiv` (ou estender `ExactAgree`) cruzando Rust vs Java vs `expected_*` nas 54.100.
- **Inalterado:** `docker-compose.yml` (submission e main), `NioServer`/`HttpParser`/`FraudRequestParser`/`HttpResponseWriter`, formato `references.kdt`.

## 11. Riscos & mitigações

1. **C-interface não linka no native-image** (como FFM/Vector na Onda 5) → **Fase 0 detecta em ~1h** antes de qualquer port; se falhar, fallback = sidecar (processo Rust separado, mas custa budget/IPC) ou abortar.
2. **E≠0 por arredondamento/tie-break** → ler `main.c`, replicar bit-a-bit; G2 bloqueante com oráculo Java.
3. **Overhead de FFI por request** maior que o esperado → BenchSearch mede; mas 1 call/request (não por-nó) dilui.
4. **Regressão de p99/score** improvável (compute-neutro) mas possível por overhead de transição → prévia opcional G6; rollback trivial (flag `fd.engine=java`).
5. **Manutenção:** mantém DUAS implementações (Java oráculo + Rust prod). Aceitável (Java vira test-only).

## 12. Verificação (end-to-end)

1. **G0:** build com `fd_ping` → rodar binário → log do ping correto.
2. **G2:** `mvnw test` rodando `RustEquiv`/`ExactAgree` (engine=rust) → 0 mismatches/54100 + 0 div vs oráculo Java.
3. **G3/G4:** `BenchSearch` (rust vs java) + medir cgroup `memory.stat anon` do container.
4. **G5:** `docker compose up -d` → `/ready` 200 → 2 POST `/fraud-score` conhecidos pelo LB → fraudCount/approved corretos.
5. **G6 (usuário, opcional):** push submission + issue `rinha/test` → comparar p99 vs baseline K2 (esperado ≈igual; variância domina).

## 13. Rollback

Flag `fd.engine=java` (ou reverter o delegate em `KdTree.search`) volta 100% ao caminho Java atual (`:k2-test`), zero risco. O `docker-compose.yml`/submission nunca mudam.
