# Spec — Onda 4a: caber em 350 MB (hnsw.bin RBH2 lossless + pré-build offline)

> Brainstorming → design travado → tutorial. Projeto **tutorial-driven**: o
> entregável é o doc (`docs/TUTORIAL_FIT_350MB.md`); o usuário implementa à mão.
> Não auto-implementar `.java`. Antecessor: `2026-05-16-onda3-hnsw-design.md`.
> **Pré-requisito:** Onda 3 implementada e verde (HNSW RBH1, 5 gates;
> recall@5 96.89% / approved 99.90% @ `ef_search=50`, p99 0.145 ms).
>
> ⚠️ **Decisão de escopo 2026-05-17.** A "Onda 4" do `RINHA_PLAN.md` §9.4 junta
> conteinerização + 350 MB + k6 + branch `submission`. Foi **dividida** (aprovado
> pelo usuário): esta é a **Onda 4a — caber em 350 MB** (formato + tooling, quase
> tudo Java). A **Onda 4b** (Dockerfile de produção, docker-compose, HAProxy TCP,
> k6 oficial, branch `submission`, `info.json`) virá depois, num spec próprio. A
> 4a **bloqueia** a 4b: não adianta subir 2 containers se o grafo não cabe.

## Contexto

O Rinha impõe **1 CPU + 350 MB de RAM para TODOS os serviços somados**, aplicado
via cgroup do Docker. A realidade as-built da Onda 3: `references.bin` (RB2) = 51
MB e **`hnsw.bin` (RBH1) ≈ 439 MB sozinho — já estoura os 350 MB totais**, antes
de qualquer JVM. Sem reduzir o grafo, a conteinerização é impossível.

A Onda 4a reduz o `hnsw.bin` com um formato **lossless** (mesmas arestas ⇒
recall/approved IDÊNTICOS à Onda 3), tira o dataset do `.jar`, move o build caro
para **offline** (o container só mmapeia, nunca constrói), torna o caminho dos
dados configurável, e **prova** o ajuste sob o cgroup real de 350 MB.

## Decisões travadas (brainstorming, aprovadas pelo usuário)

1. **Dividir Onda 4 → 4a (caber) agora, 4b (conteinerizar) depois.** Cada uma =
   1 spec + 1 tutorial, no grão das ondas anteriores, testável isolada.
2. **`hnsw.bin` v2 = RBH2: `int24` nas listas de vizinhos + camadas altas
   esparsas.** **Lossless** (mesmo conjunto de arestas, só reorganização +
   ids em 3 bytes). ≈439 MB → estimado **~300 MB** (número exato medido no
   Gate 2).
3. **Gate de memória = `docker run --memory=350m --cpus=1` só como balança.**
   Mede sob o cgroup real (fiel ao Rinha). O Dockerfile de produção / compose /
   HAProxy / k6 / submission ficam **100% na Onda 4b**.
4. **Build é offline.** Ferramenta `tools.Prebuild` gera `references.bin` +
   `hnsw.bin` no box de dev (`-Xmx2g`, minutos). O container nunca constrói.
5. **`api.jar` não empacota o dataset.** `maven-jar-plugin` exclui
   `references.json.gz`/`*.bin` ⇒ jar de ~tens de KB.
6. **Mmap compartilhado.** As 2 instâncias mmapeiam os MESMOS arquivos
   read-only (1 inode ⇒ 1 cópia no page-cache do host; páginas limpas =
   reclaimáveis, não causam OOM).

## Design

### §1. Formato `hnsw.bin` v2 — RBH2 (big-endian, lossless)

```
[ header 28B ]  magic 'R','B','H','2' (4) | int32 count | int32 M | int32 M0
                | int32 efC | int32 entryPoint | int32 maxLevel
[ levels    ]   count × uint8                       (nível-topo; inalterado vs RBH1)
[ L0 denso  ]   int32 off0[count+1]                 (offsets até ~90M > 2^24 → int32 fica)
                int24 nbr0[ off0[count] ]            (ids < 3M < 2^24 → 3 bytes)
[ Lk esparso ]  p/ k = 1..maxLevel, em ordem:
                int32 Pk                             (nº de nós presentes na camada k)
                int24 node_k[Pk]                     (ids presentes, ORDENADO ascendente)
                int32 off_k[Pk+1]                    (offsets locais; arestas/camada < 2^31)
                int24 nbr_k[ off_k[Pk] ]
```

- **`int24`**: 3 bytes big-endian, sempre **positivo** (id ∈ [0, count) ⊂
  [0, 2²⁴=16 777 216)). `get24(buf,p) = ((buf.get(p)&0xFF)<<16) |
  ((buf.get(p+1)&0xFF)<<8) | (buf.get(p+2)&0xFF)`. Sem sinal, sem ambiguidade.
- **L0 denso**: id == índice (todos os nós existem em L0); leitor trivial,
  igual RBH1 mas vizinhos em 3B.
- **Camadas altas esparsas**: elimina os offset arrays uniformes `count+1` das
  ~4 camadas altas (RBH1: 4 × (count+1)×4 ≈ 48 MB de quase-zeros). `Pk` é
  minúsculo (geométrico: ~count/M, ~count/M² …). `neighbors(n,k)` = busca
  binária de `n` em `node_k` (ordenado) → índice `i` → `nbr_k[off_k[i] ..
  off_k[i+1])`. A descida greedy usa `ef=1` (poucas sondas/query) ⇒ custo do
  binary search irrelevante.
- **Lossless**: os conjuntos de vizinhos são exatamente os da RBH1 ⇒ a busca
  HNSW visita os mesmos nós ⇒ **recall@5 e approved idênticos** à Onda 3
  (Gate 1 = regressão exata).
- Ganho estimado: `nbr0` 4→3 B (~−90 MB) + offsets uniformes altos eliminados
  (~−48 MB) ⇒ ≈439 → ~300 MB. Número exato medido no Gate 2.

### §2. `HnswBuilder` / `HnswGraph` / `HnswIndex`

- `HnswBuilder.write()`: emite **RBH2** (magic `RBH2`; L0 denso int24; por
  camada alta grava `Pk`, `node_k` ordenado, `off_k`, `nbr_k` int24). O grafo
  em memória **não muda** — só a serialização.
- `HnswGraph.mmap()`: parseia o header, precomputa as bases por camada
  (levelsBase → L0 off/nbr → para k≥1: lê `Pk`, base de `node_k`, `off_k`,
  `nbr_k`). `get24()` helper. `neighbors(n,0)` direto; `neighbors(n,k≥1)` via
  busca binária em `node_k`. Assinaturas públicas usadas pelo `searchLayer`
  **inalteradas** (nbrLo/nbrHi/nbrAt equivalentes) — `HnswIndex` não muda de
  lógica, só lê o novo layout.
- `HnswGraph.isValid()` / `HnswGraph.mmap()`: magic passa a **`RBH2`**.
  `hnsw.bin` ausente ou RBH1 ou count≠ ⇒ rebuild (self-bootstrap, agora
  gravando RBH2). **`HnswIndex.java` fica inalterado** — `load()` é agnóstico
  de formato (chama `HnswGraph.isValid`/`mmap` + `HnswBuilder.build`); no
  máximo uma string de log. Em produção o `.bin` vem pré-construído (§3) ⇒ só
  mmapeia.

### §3. Pré-build offline — `tools.Prebuild`

Novo `org.fraudDetection.tools.Prebuild` (`src/main`, pacote `tools`).
`main(args)` recebe `<gz> <refBin> <hnswBin>` (ou usa `DATA_PATH`), chama
`MmapDataset.load(gz, refBin)` (constrói RB2 se ausente) e `HnswIndex.load(hnswBin)`
(self-bootstrap → `HnswBuilder.build` grava RBH2). Roda no **box de dev / CI**:
`java -Xmx2g --add-modules jdk.incubator.vector -cp target/classes
org.fraudDetection.tools.Prebuild …`. Saída: os 2 binários prontos para a 4b
levar (imagem/branch — fora de escopo aqui). **Por quê:** o container tem
350 MB/1 CPU e não roda o build de minutos com `-Xmx2g`; build offline desacopla
build de runtime (container só mmapeia).

### §4. `Main.java` + `pom.xml` (jar magro)

- `Main`: lê `DATA_PATH` (`-DDATA_PATH` → env → default
  `"src/main/resources"`). Monta os 3 caminhos a partir dele. **Default
  preserva o fluxo dev** (`cd api; java -jar …`) inalterado; o container usa
  `-DDATA_PATH=/data` (dir read-only montado). `tools.Prebuild` honra o mesmo.
- `pom.xml`: `maven-jar-plugin` += `<excludes>` `references.json.gz`,
  `references.bin`, `hnsw.bin` ⇒ `api.jar` cai de ~387 MB para ~tens de KB
  (classes + manifest + `example-references.json` 32 KB). `example-references`
  fica.

### §5. Estratégia de memória (o argumento central)

As 2 instâncias mmapeiam os **mesmos** arquivos read-only (mesmo inode no host
⇒ **uma** cópia no page-cache). Essas páginas são **file-backed, limpas,
read-only ⇒ reclaimáveis**: sob pressão de `memory.max` o kernel **despeja**
(sem writeback) em vez de OOM-kill. O OOM-kill só dispara quando a memória
**não-reclaimável** (anônima: heap/metaspace/stacks/code-cache/direct das JVMs)
não cabe. Logo o alvo de projeto é: **anon total (2 JVMs + folga p/ o HAProxy da
4b) « 350 MB**, com o grafo mmapeado paginando sob demanda (working set por
query ≈ centenas de nós). A compactação RBH2 reduz o arquivo ⇒ mais do working
set fica residente sob o teto ⇒ menos major-faults ⇒ p99 estável. **É por isso
que a 4a (compactar) é pré-requisito da 4b (conteinerizar).** Flags JVM
iniciais por instância (afinadas no Gate 3): `-Xmx64m -Xms64m
-XX:+UseSerialGC -XX:MaxMetaspaceSize=48m -Xss512k
-XX:ReservedCodeCacheSize=24m` (build é offline ⇒ heap steady é mínimo;
SerialGC = menor footprint em 1 CPU/heap pequeno).

### §6. Validação — 4 gates

- **Gate 1 — lossless / regressão (bloqueia):** `src/test/Rbh2Equiv` (golden:
  copiar o `hnsw.bin` RBH1 da Onda 3 para `hnsw.rbh1.golden` ANTES de migrar;
  o harness tem um leitor RBH1 mínimo **só no test**). Para os `count` nós ×
  todas as camadas: `neighbors_RBH1(n,k)` == `neighbors_RBH2(n,k)` (como
  conjunto). **0 divergências/3M.** Depois, Onda 3 re-verde: 2 oráculos exatos,
  `Gate2Int8 2000` = **99.65%**, `RecallHnsw 2000 50` recall@5 = **96.89%** /
  approved = **99.90%** (idênticos — prova empírica do lossless).
- **Gate 2 — tamanho:** `ls -l` → `hnsw.bin` ~300 MB (medido vs §1),
  `references.bin` 51 MB; `jar tf target/api.jar | grep -E '\.(gz|bin)$'`
  vazio; `api.jar` < 1 MB.
- **Gate 3 — A PROVA (bloqueia):** `docker run --rm --memory=350m
  --memory-swap=350m --cpus=1 -v <data>:/data:ro …` rodando **2 instâncias**
  (mmap compartilhado de `/data`) + folga p/ o HAProxy futuro; servir os 2
  oráculos + uma rajada de curl; **sem OOMKill** (`docker inspect
  .State.OOMKilled == false`), `memory.peak`/`docker stats` **< 350 MB**,
  oráculos ainda exatos.
- **Gate 4 — latência (medição):** `BenchHnsw 2000` (idealmente sob o cap do
  Gate 3) — `int24` no hot path + paginação sob o teto não devem regredir o p99
  significativamente vs Onda 3 (0.145 ms). Sem threshold absoluto (p99<1ms =
  Onda 5); regressão grande = sinal de thrashing → revisitar compactação.

### §7. Não-objetivos (são da Onda 4b ou depois)

Dockerfile de produção, `docker-compose.yml`, HAProxy TCP, 2 serviços com
`deploy.resources.limits`, k6 oficial, `final_score`, branch `submission`,
`info.json`, PR em `participants/`. Encoding agressivo (delta+varint) — só se o
Gate 3/4 mostrar que `int24`+esparso não basta (YAGNI). Native Image (Onda 5).
Mudança de fórmula/quantização/threshold/parametros HNSW (M/M0/efC/ef_search).

## Inventário de arquivos

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/HnswBuilder.java` | `write()` → emite RBH2 (L0 int24 denso; camadas altas esparsas `Pk`/`node_k`/`off_k`/`nbr_k`) |
| 2 | `knn/HnswGraph.java` | `isValid`/`mmap` magic `RBH2`; bases por camada, `get24()`, L0 denso + upper esparso (binary search em `node_k`) |
| 3 | `knn/HnswIndex.java` | **inalterado** — `load()` é agnóstico de formato (só string de log, se quiser) |
| 4 | `Main.java` | `DATA_PATH` (`-D`→env→`src/main/resources`); monta os 3 paths |
| 5 | `api/pom.xml` | `maven-jar-plugin` `<excludes>` `references.json.gz`/`references.bin`/`hnsw.bin` |
| 6 | `tools/Prebuild.java` | **novo** — pré-build offline (`-Xmx2g`) de `references.bin` (RB2) + `hnsw.bin` (RBH2) |
| 7 | `src/test/Rbh2Equiv.java` | **novo** — Gate 1: leitor RBH1 mínimo (só no test) vs RBH2; `neighbors(n,k)` ≡ p/ os 3M |
| — | `RecallHnsw`/`Gate2Int8`/`BenchHnsw`/`TestDataReader` | reusados (regressão Onda 3 + Gate 4) |

## Test points do tutorial

1. `get24()` round-trip: grava/ler ids `0`, `1`, `count-1`, `2²⁴-1` → exatos,
   sempre positivos.
2. RBH2 pequeno (`example-references` quantizado, N≈100): build → `Rbh2Equiv`
   vs RBH1 → 0 divergências; `recall@5` ~100% nesse N.
3. `Prebuild` offline gera `references.bin` + `hnsw.bin` (RBH2); 2º boot só
   mmapeia (sem "construindo").
4. `jar tf target/api.jar` não lista `.gz`/`.bin`; `Main` com `-DDATA_PATH=/tmp/x`
   carrega de lá.
5. Gates 1–4 conforme §6.

## Riscos / mitigações

| Risco | Mitigação |
|---|---|
| `int24` com sinal/endianness errados | `get24` mascara `&0xFF`, big-endian igual aos int32; id sempre < 2²⁴ (positivo) — Test point 1 |
| Recall mudar (não-lossless) | Mesmo grafo em memória; Gate 1 = igualdade de conjuntos RBH1≡RBH2/3M + recall idêntico (96.89%) |
| cgroup despeja páginas do grafo → p99 thrash sob 350 MB | Compactação reduz arquivo (mais working set residente); Gate 4 mede p99 sob o cap; encoding agressivo = fallback (não-objetivo §7) |
| Leitor RBH1 morto no código de produção | Leitor RBH1 vive **só** em `src/test/Rbh2Equiv` (golden `hnsw.rbh1.golden`); produção só RBH2 |
| Container constrói o grafo (-Xmx2g, impossível em 350 MB) | `tools.Prebuild` offline; container só mmapeia binários prontos |
| `DATA_PATH` quebra o fluxo dev | Default = `src/main/resources` (comportamento atual idêntico); só o container seta `-DDATA_PATH` |
| Busca binária em `node_k` no hot path | Só nas camadas altas (descida `ef=1`, `Pk` minúsculo); L0 (o quente) é denso O(1) |
| `api.jar` ainda grande | Gate 2 falha o build se `jar tf` listar `.gz`/`.bin` |

## Próximo passo

Escrever `docs/TUTORIAL_FIT_350MB.md` (hands-on PT-BR, §0–§N, espelhando
`TUTORIAL_HNSW.md`) + atualizar o ponteiro `§15` do `TUTORIAL_HNSW.md`.
Implementação fica para o usuário (tutorial-driven); Claude valida os 4 gates.
Onda seguinte: **Onda 4b — conteinerização + HAProxy TCP + 2 instâncias + k6
oficial + branch `submission`** (consome os binários RBH2 da 4a).
