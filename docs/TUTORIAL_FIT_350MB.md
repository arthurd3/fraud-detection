# Tutorial — Onda 4a: caber em 350 MB (hnsw.bin RBH2 lossless + pré-build offline)

> De **Onda 3** (HNSW RBH1, `hnsw.bin` ≈439 MB, 5 gates verdes) → **mesmo grafo,
> mesmo recall**, mas o `hnsw.bin` encolhe para ~300 MB, sai do `.jar`, é
> construído **offline** e o ajuste em **350 MB** é provado sob o cgroup real.
> **Tempo estimado**: 5–8 h. **Pré-requisito ABSOLUTO**: Onda 3 implementada e
> verde (recall@5 96.89% / approved 99.90% @ `ef_search=50`, p99 0.145 ms;
> `hnsw.bin` RBH1 presente em `src/main/resources/`). Spec:
> `docs/superpowers/specs/2026-05-17-onda4a-fit-350mb-design.md`.

> ⚠️ **Escopo (2026-05-17).** A "Onda 4" do `RINHA_PLAN.md` §9.4 foi **dividida**
> (aprovado): esta é a **Onda 4a — caber em 350 MB** (formato + tooling, Java). A
> **Onda 4b** (Dockerfile de produção, `docker-compose`, HAProxy TCP, k6 oficial,
> branch `submission`, `info.json`, PR em `participants/`) virá num tutorial
> próprio e **consome** os binários RBH2 desta etapa. A 4a **bloqueia** a 4b: o
> `hnsw.bin` RBH1 (~439 MB) sozinho já estoura os 350 MB totais do Rinha.

---

## §0. Visão geral, o que muda, critério de saída

O Rinha impõe **1 CPU + 350 MB para TODOS os serviços** (cgroup do Docker).
Hoje: `references.bin` 51 MB + **`hnsw.bin` RBH1 ≈439 MB** ⇒ não cabe. A Onda 4a
ataca isso em 4 frentes, **sem mudar o grafo** (logo recall idêntico):

1. **Formato RBH2**: vizinhos em `int24` (3 B em vez de 4) + camadas altas
   **esparsas** (sem os offset arrays uniformes `count+1`). ≈439 MB → ~300 MB.
   **Lossless** — mesmas arestas ⇒ recall/approved idênticos à Onda 3.
2. **Build offline** (`tools.Prebuild`): o container nunca constrói (build pede
   `-Xmx2g`/minutos — impossível em 350 MB/1 CPU); só **mmapeia** binários
   prontos.
3. **`api.jar` sem dataset**: `maven-jar-plugin` exclui `.gz`/`.bin` ⇒ jar de
   ~KB (era ~387 MB).
4. **`DATA_PATH`** configurável: dev continua igual; o container aponta para um
   diretório read-only montado.

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/HnswBuilder.java` | `write()` grava **RBH2** (L0 denso int24; camadas altas esparsas) + helper `w24` |
| 2 | `knn/HnswGraph.java` | `isValid`/`mmap` magic `RBH2`; `get24`, bases por camada, L0 denso O(1) + upper esparso (busca binária em `node_k`) |
| 3 | `knn/HnswIndex.java` | **inalterado** (`load()` é agnóstico de formato; opcional: 1 string de log) |
| 4 | `Main.java` | `DATA_PATH` (`-D`→env→`src/main/resources`); monta os 3 caminhos |
| 5 | `api/pom.xml` | `maven-jar-plugin` `<excludes>` `references.json.gz`/`references.bin`/`hnsw.bin`/`hnsw.rbh1.golden` |
| 6 | `tools/Prebuild.java` | **novo** — pré-build offline (`-Xmx2g`): gera `references.bin` (RB2) + `hnsw.bin` (RBH2) |
| 7 | `src/test/Rbh2Equiv.java` | **novo** — Gate 1: leitor RBH1 mínimo (só no test) vs RBH2; igualdade p/ os 3M |
| — | `RecallHnsw`/`Gate2Int8`/`BenchHnsw`/`TestDataReader` | reusados (regressão Onda 3 + Gate 4) |

### Critério de saída da Onda 4a

- **Gate 1 (bloqueia):** `Rbh2Equiv` = **0 divergências / 3.000.000**; depois
  Onda 3 re-verde — 2 oráculos exatos, `Gate2Int8 2000` = **99.65%**,
  `RecallHnsw 2000 50` recall@5 **96.89%** / approved **99.90%** (idênticos —
  prova empírica do lossless).
- **Gate 2 (bloqueia):** `hnsw.bin` ~300 MB (medido), `references.bin` 51 MB;
  `jar tf target/api.jar` sem `.gz`/`.bin`; `api.jar` < 1 MB.
- **Gate 3 (bloqueia) — A PROVA:** `docker run --memory=350m --cpus=1` com **2
  instâncias** + mmap compartilhado, servindo os oráculos + rajada, **sem
  OOMKill**, cgroup `memory.peak` < 350 MB.
- **Gate 4 (medição):** `BenchHnsw 2000` — `int24` no hot path não regride o p99
  de forma relevante vs Onda 3 (0.145 ms). Sem threshold absoluto.

---

## §1. Mapa mental

```
ANTES (Onda 3):
  1º boot do container: lê .gz → quantiza → references.bin → constrói HNSW
                        (-Xmx2g, minutos) → hnsw.bin RBH1 (~439 MB)   ✗ não cabe

DEPOIS (Onda 4a):
  BOX DE DEV / CI (offline, 1×):
    tools.Prebuild  -Xmx2g  →  references.bin (RB2, 51 MB)
                               hnsw.bin (RBH2, ~300 MB)        ← lossless
  CONTAINER (steady-state, -Xmx64m, 2 instâncias):
    mmap read-only dos 2 arquivos (1 cópia no page-cache, compartilhada)
    POST /fraud-score: idêntico à Onda 3 (mesmo grafo, mesmas respostas)
```

Só a **serialização** e o **empacotamento/onde-roda-o-build** mudam. Parser,
fórmula 14-D, quantização, `sqDistI8Scalar`, RB2, `searchLayer`, HNSW, respostas
canned — **intactos**. A resposta é byte-a-byte a mesma da Onda 3.

---

## §2. Princípios

1. **Lossless é inegociável.** O grafo em memória não muda; só como ele é
   gravado/lido. Gate 1 prova arestas idênticas (RBH1≡RBH2 nos 3M) **e** recall
   numericamente idêntico. Se o recall mudou, o formato está com bug.
2. **`int24` sem sinal.** Todo id de nó ∈ [0, `count`) ⊂ [0, 2²⁴ = 16.777.216).
   3 bytes **big-endian** (igual aos `int32` do arquivo). Sempre positivo —
   nada de `byte` com sinal vazando.
3. **mmap = páginas limpas reclaimáveis.** Arquivo read-only mmapeado não causa
   OOM: sob pressão do cgroup o kernel **despeja** as páginas (sem writeback) e
   relê sob demanda. OOM-kill só vem da memória **anônima** (heap/metaspace/
   stacks/code-cache das JVMs). Logo: heaps minúsculas + grafo compacto.
4. **Build é offline.** A regra by-hand/zero-alloc vale pro **search**. O build
   (não é hot path, 1× no box de dev) pode usar `-Xmx2g` e `java.util.*`. O
   container só mmapeia.
5. **`DATA_PATH` default preserva o dev.** Default = `src/main/resources` ⇒
   `cd api && java -jar target/api.jar` continua idêntico. Só o container seta
   `-DDATA_PATH=/data`.
6. **Camada 0 é o caminho quente.** L0 fica **denso** (id == índice, O(1)). A
   busca binária só aparece nas camadas altas (descida `ef=1`, `Pk` minúsculo).

---

## §3. Formato `hnsw.bin` v2 — RBH2

Big-endian (igual RBH1). Diferenças vs RBH1: magic `RBH2`; `nbr` em `int24`;
camadas altas **esparsas** (não têm mais `off` uniforme `count+1`).

```
[ header 28B ]  'R','B','H','2'(4) | i32 count | i32 M | i32 M0
                | i32 efC | i32 entryPoint | i32 maxLevel
[ levels     ]  count × u8                         (nível-topo de cada nó)
[ L0 (denso) ]  i32 off0[count+1]                   (offsets até ~90M → i32 fica)
                i24 nbr0[ off0[count] ]             (ids < 3M → 3 bytes)
[ Lk, k=1..maxLevel, em ordem ]:
                i32 Pk                              (nº de nós presentes na camada k)
                i24 node_k[Pk]                      (ids presentes, ORDENADO asc)
                i32 off_k[Pk+1]                     (offsets locais p/ nbr_k)
                i24 nbr_k[ off_k[Pk] ]
```

- `neighbors(n,0)` = `nbr0[ off0[n] .. off0[n+1] )` (id == índice).
- `neighbors(n,k≥1)`: busca binária de `n` em `node_k` → índice `j` →
  `nbr_k[ off_k[j] .. off_k[j+1] )`. Nó ausente na camada ⇒ intervalo vazio.
- **Helpers** (3 bytes, big-endian, sempre positivo):

```java
// escrita (HnswBuilder)
static void w24(java.io.RandomAccessFile raf, int v) throws java.io.IOException {
    raf.write((v >>> 16) & 0xFF);
    raf.write((v >>>  8) & 0xFF);
    raf.write( v         & 0xFF);
}
// leitura (HnswGraph, sobre o MappedByteBuffer g)
static int get24(java.nio.MappedByteBuffer g, int p) {
    return ((g.get(p) & 0xFF) << 16) | ((g.get(p+1) & 0xFF) << 8) | (g.get(p+2) & 0xFF);
}
```

🔍 **Test point 1 — `get24` round-trip.** Grave e releia `0`, `1`,
`count-1`, `16_777_215` (2²⁴−1) com `w24`/`get24` num arquivo de 3 B: todos
voltam exatos e ≥ 0.

---

## §4. `knn/HnswBuilder.java` — `write()` grava RBH2

Só o `write()` muda (o grafo em memória e o `insert`/heurística da Onda 3
**não** mudam). `degOf` já tem o guard `lc > level[node]` da Onda 3. `node[]` é
construído por `i` crescente ⇒ **já ordenado** (pré-condição da busca binária).
Adicione o helper `w24` (§3) e substitua o `write()` por:

```java
private static void write(String binPath) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(binPath, "rw")) {
        raf.setLength(0);
        raf.write(new byte[]{'R','B','H','2'});
        raf.writeInt(N); raf.writeInt(M); raf.writeInt(M0);
        raf.writeInt(EF_C); raf.writeInt(entry); raf.writeInt(maxLevel);
        for (int i = 0; i < N; i++) raf.writeByte(level[i]);          // levels[]

        // ---- L0 denso ----
        int acc = 0;
        int[] off0 = new int[N + 1];
        for (int i = 0; i < N; i++) { off0[i] = acc; acc += degOf(i, 0); }
        off0[N] = acc;
        for (int i = 0; i <= N; i++) raf.writeInt(off0[i]);
        for (int i = 0; i < N; i++) {
            int dd = degOf(i, 0);
            for (int t = 0; t < dd; t++) w24(raf, nbrOf(i, 0, t));
        }

        // ---- camadas altas esparsas ----
        for (int lc = 1; lc <= maxLevel; lc++) {
            int Pk = 0;
            for (int i = 0; i < N; i++) if (level[i] >= lc) Pk++;
            int[] node = new int[Pk];
            int p = 0;
            for (int i = 0; i < N; i++) if (level[i] >= lc) node[p++] = i; // i↑ ⇒ node[] ↑
            int[] offk = new int[Pk + 1];
            int a = 0;
            for (int j = 0; j < Pk; j++) { offk[j] = a; a += degOf(node[j], lc); }
            offk[Pk] = a;

            raf.writeInt(Pk);
            for (int j = 0; j < Pk; j++)   w24(raf, node[j]);
            for (int j = 0; j <= Pk; j++)  raf.writeInt(offk[j]);
            for (int j = 0; j < Pk; j++) {
                int nd = node[j], dd = degOf(nd, lc);
                for (int t = 0; t < dd; t++) w24(raf, nbrOf(nd, lc, t));
            }
        }
        raf.getFD().sync();
    }
}
```

> `level[i] >= lc` = "nó participa da camada `lc`" (nó de nível L existe em
> 0..L). `RandomAccessFile.write(int)` grava 1 byte (8 bits baixos) — correto
> para `w24`.

🔍 **Test point 2 — RBH2 pequeno.** Com `-Dhnsw.maxNodes=1000` (knob da Onda 3)
rode o `Prebuild` (§8): `hnsw.bin` gerado; `Rbh2Equiv` (§9) vs o RBH1 do mesmo N
= 0 divergências; `recall@5` ~100% nesse N pequeno.

---

## §5. `knn/HnswGraph.java` — leitor RBH2 (mmap RO)

Reescreve o parser e os acessores. Mantém as **assinaturas** que o
`HnswIndex.searchLayer` já usa (`level`, `nbrLo`, `nbrHi`, `nbrAt`) ⇒
`HnswIndex.java` não muda. Memo de 1 entrada evita 2 buscas binárias por nó
(reator single-thread, igual ao `HnswScratch`).

```java
package org.fraudDetection.knn;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public final class HnswGraph {
    private HnswGraph() {}

    public static MappedByteBuffer g;
    public static int count, M, M0, efC, entry, maxLevel;
    private static int levelsBase, l0OffBase, l0NbrBase;
    private static int[] pk, nodeBase, offBase, nbrBase;   // índices 1..maxLevel

    public static boolean isValid(File bin, int expectCount) {
        try (RandomAccessFile r = new RandomAccessFile(bin, "r")) {
            if (r.length() < 28) return false;
            byte[] m = new byte[4]; r.readFully(m);
            if (!(m[0]=='R'&&m[1]=='B'&&m[2]=='H'&&m[3]=='2')) return false;
            return r.readInt() == expectCount;                 // count
        } catch (IOException e) { return false; }
    }

    public static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            if (!(m.get(0)=='R'&&m.get(1)=='B'&&m.get(2)=='H'&&m.get(3)=='2'))
                throw new IOException("magic != RBH2");
            count    = m.getInt(4);
            M        = m.getInt(8);
            M0       = m.getInt(12);
            efC      = m.getInt(16);
            entry    = m.getInt(20);
            maxLevel = m.getInt(24);
            levelsBase = 28;

            l0OffBase = levelsBase + count;                    // após levels[count] (1B)
            l0NbrBase = l0OffBase + (count + 1) * 4;
            int e0    = m.getInt(l0OffBase + count * 4);        // off0[count]
            int p     = l0NbrBase + e0 * 3;

            pk       = new int[maxLevel + 1];
            nodeBase = new int[maxLevel + 1];
            offBase  = new int[maxLevel + 1];
            nbrBase  = new int[maxLevel + 1];
            for (int k = 1; k <= maxLevel; k++) {
                int P = m.getInt(p);
                pk[k]       = P;
                nodeBase[k] = p + 4;
                offBase[k]  = nodeBase[k] + P * 3;
                nbrBase[k]  = offBase[k] + (P + 1) * 4;
                int ek      = m.getInt(offBase[k] + P * 4);     // off_k[P]
                p           = nbrBase[k] + ek * 3;
            }
            g = m;
            mC = -1; mK = -1; mJ = -1;
        }
    }

    private static int get24(int pos) {
        return ((g.get(pos) & 0xFF) << 16) | ((g.get(pos+1) & 0xFF) << 8) | (g.get(pos+2) & 0xFF);
    }

    // memo 1-entrada (single-thread): nbrLo e nbrHi do mesmo (node,k) consecutivos
    private static int mC = -1, mK = -1, mJ = -1;
    private static int idxOf(int node, int k) {
        if (node == mC && k == mK) return mJ;
        int lo = 0, hi = pk[k] - 1, base = nodeBase[k], res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = get24(base + mid * 3);
            if (v == node) { res = mid; break; }
            if (v < node) lo = mid + 1; else hi = mid - 1;
        }
        mC = node; mK = k; mJ = res;
        return res;
    }

    public static int level(int node) { return g.get(levelsBase + node) & 0xFF; }

    public static int nbrLo(int node, int k) {
        if (k == 0) return g.getInt(l0OffBase + node * 4);
        int j = idxOf(node, k);
        return j < 0 ? 0 : g.getInt(offBase[k] + j * 4);
    }
    public static int nbrHi(int node, int k) {
        if (k == 0) return g.getInt(l0OffBase + (node + 1) * 4);
        int j = idxOf(node, k);
        return j < 0 ? 0 : g.getInt(offBase[k] + (j + 1) * 4);
    }
    public static int nbrAt(int k, int idx) {
        return get24((k == 0 ? l0NbrBase : nbrBase[k]) + idx * 3);
    }
}
```

> Posições passadas a `MappedByteBuffer.get/getInt` são `int`; o arquivo RBH2
> (~300 MB) < 2³¹ ⇒ sem overflow. Para um nó c visitado na camada `lc≥1` durante
> a busca, `level[c] >= lc` (você só chega nele por arestas dessa camada) ⇒
> `idxOf` acha; o `-1` é só defensivo.

🔍 **Test point 3 — bases.** Logo após `mmap`, para o `entry`: `nbrLo(entry,
maxLevel) < nbrHi(entry, maxLevel)` (o entry tem vizinhos no topo) e
`nbrLo(0,0) == 0`.

---

## §6. `knn/HnswIndex.java` (inalterado) + `Main.java` (`DATA_PATH`)

`HnswIndex.java` **não muda**: `load()` chama `HnswGraph.isValid`/`mmap` +
`HnswBuilder.build` (agnóstico de formato). Opcional: trocar a string de log
"… construindo HNSW…" não é necessário.

`Main.java` — caminho dos dados configurável (default preserva o dev):

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.server.NioServer;

public class Main {
    static String dataPath() {
        String p = System.getProperty("DATA_PATH");
        if (p == null) p = System.getenv("DATA_PATH");
        return (p == null || p.isEmpty()) ? "src/main/resources" : p;
    }
    public static void main(String[] args) throws Exception {
        String d = dataPath();
        long t0 = System.currentTimeMillis();
        MmapDataset.load(d + "/references.json.gz", d + "/references.bin");
        System.out.println("dataset loaded: " + MmapDataset.count
                + " vectors (" + (System.currentTimeMillis() - t0) + " ms)");
        HnswIndex.load(d + "/hnsw.bin");
        System.out.println("hnsw pronto");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        new NioServer(port).start();
    }
}
```

---

## §7. `api/pom.xml` — `api.jar` sem o dataset

No `maven-jar-plugin` (já existente), adicione `<excludes>` na `<configuration>`
(ao lado do `<archive>`):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <version>3.4.1</version>
    <configuration>
        <archive>
            <manifest><mainClass>${main.class}</mainClass></manifest>
        </archive>
        <excludes>
            <exclude>references.json.gz</exclude>
            <exclude>references.bin</exclude>
            <exclude>hnsw.bin</exclude>
            <exclude>hnsw.rbh1.golden</exclude>
        </excludes>
    </configuration>
</plugin>
```

> Os recursos vão para `target/classes/` e daí para o jar; os `<excludes>`
> (padrões relativos à raiz do jar) tiram os binários grandes. `example-
> references.json` (32 KB) **fica**.

---

## §8. `tools/Prebuild.java` — build offline (novo)

Novo pacote `org.fraudDetection.tools`. Reaproveita o self-bootstrap: `load`
constrói se ausente/incompatível (agora gravando RBH2).

```java
package org.fraudDetection.tools;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswIndex;

public final class Prebuild {
    private Prebuild() {}
    public static void main(String[] args) throws Exception {
        String d = args.length > 0 ? args[0]
                 : System.getProperty("DATA_PATH",
                     System.getenv().getOrDefault("DATA_PATH", "src/main/resources"));
        long t0 = System.currentTimeMillis();
        MmapDataset.load(d + "/references.json.gz", d + "/references.bin");
        System.out.println("references.bin pronto: " + MmapDataset.count + " vetores");
        HnswIndex.load(d + "/hnsw.bin");                 // self-bootstrap → grava RBH2
        System.out.println("hnsw.bin (RBH2) pronto em "
                + ((System.currentTimeMillis() - t0) / 1000) + "s");
    }
}
```

Rodar (box de dev, 1×):

```bash
cd api
./mvnw -q clean package
# guarde o RBH1 da Onda 3 ANTES de migrar (golden do Gate 1):
cp src/main/resources/hnsw.bin src/main/resources/hnsw.rbh1.golden   # ainda RBH1
rm -f src/main/resources/hnsw.bin                                    # força rebuild RBH2
java -Xmx2g --add-modules jdk.incubator.vector \
     -cp target/classes org.fraudDetection.tools.Prebuild src/main/resources
# "references.bin pronto: 3000000 vetores" → "hnsw.bin (RBH2) pronto em NNNs"
```

🔍 **Test point 4 — offline + jar magro.** 2º `Prebuild` só mmapeia (sem
"construindo"); `jar tf target/api.jar | grep -E '\.(gz|bin|golden)$'` vazio;
`java -DDATA_PATH=/caminho/x -jar target/api.jar` carrega de `/caminho/x`.

---

## §9. Gate 1 — lossless (`src/test/Rbh2Equiv.java`) + regressão Onda 3

`Rbh2Equiv` tem um **leitor RBH1 mínimo embutido (só no test)** e compara, para
todos os nós × todas as camadas, o conjunto de vizinhos do golden RBH1 vs o
`HnswGraph` RBH2.

```java
package org.fraudDetection;

import org.fraudDetection.knn.HnswGraph;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/** Gate 1 — RBH1(golden) ≡ RBH2 (mesmas arestas p/ todos os nós/camadas). */
public final class Rbh2Equiv {
    // ---- leitor RBH1 mínimo (uniforme count+1, nbr int32) ----
    static MappedByteBuffer r1; static int c1, maxL1, lvlBase1;
    static int[] offBase1, nbrBase1;
    static void openRBH1(String path) throws IOException {
        FileChannel ch = FileChannel.open(new File(path).toPath());
        MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        if (!(m.get(0)=='R'&&m.get(1)=='B'&&m.get(2)=='H'&&m.get(3)=='1'))
            throw new IOException("golden não é RBH1");
        c1 = m.getInt(4); maxL1 = m.getInt(24);
        lvlBase1 = 28;
        int p = lvlBase1 + c1;
        offBase1 = new int[maxL1 + 1]; nbrBase1 = new int[maxL1 + 1];
        for (int k = 0; k <= maxL1; k++) {
            offBase1[k] = p;
            int e = m.getInt(p + c1 * 4);                 // off_k[count]
            nbrBase1[k] = p + (c1 + 1) * 4;
            p = nbrBase1[k] + e * 4;
        }
        r1 = m;
    }
    static int[] nbrsRBH1(int n, int k) {
        int lo = r1.getInt(offBase1[k] + n*4), hi = r1.getInt(offBase1[k] + (n+1)*4);
        int[] a = new int[hi - lo];
        for (int i = lo; i < hi; i++) a[i-lo] = r1.getInt(nbrBase1[k] + i*4);
        Arrays.sort(a); return a;
    }
    static int[] nbrsRBH2(int n, int k) {
        int lo = HnswGraph.nbrLo(n, k), hi = HnswGraph.nbrHi(n, k);
        int[] a = new int[hi - lo];
        for (int i = lo; i < hi; i++) a[i-lo] = HnswGraph.nbrAt(k, i);
        Arrays.sort(a); return a;
    }

    public static void main(String[] args) throws IOException {
        String golden = args.length > 0 ? args[0] : "src/main/resources/hnsw.rbh1.golden";
        String rbh2   = args.length > 1 ? args[1] : "src/main/resources/hnsw.bin";
        openRBH1(golden);
        HnswGraph.mmap(new File(rbh2));
        if (HnswGraph.count != c1 || HnswGraph.maxLevel != maxL1)
            { System.out.println("header divergente RBH1≠RBH2 -> FAIL"); System.exit(1); }

        long div = 0;
        for (int n = 0; n < c1; n++)
            for (int k = 0; k <= maxL1; k++)
                if (!Arrays.equals(nbrsRBH1(n,k), nbrsRBH2(n,k))) div++;
        System.out.printf("Rbh2Equiv: %d divergencias / %d nos -> %s%n",
                div, c1, div == 0 ? "PASS" : "FAIL");
        if (div != 0) System.exit(1);
    }
}
```

Rodar (após o `Prebuild` da §8):

```bash
cd api
./mvnw -q test-compile
java -Xmx512m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.Rbh2Equiv
# Rbh2Equiv: 0 divergencias / 3000000 nos -> PASS

# regressão Onda 3 (têm de bater EXATO — lossless):
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.Gate2Int8  2000
# Gate 2: 1993/2000 agreement = 99.65% (FP=3 FN=4) -> PASS
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.RecallHnsw 2000 50
# ef_search=50  recall@5=96.89%  approved-agree=99.90% (FP=1 FN=1) -> PASS
```

E os 2 oráculos no servidor real (`-DDATA_PATH` opcional; default = dev):

```bash
java -Xmx256m --add-modules jdk.incubator.vector -jar target/api.jar 9999 &
# /ready 200; tx-1329056812 → {"approved":true,"fraud_score":0.0};
#             tx-3330991687 → {"approved":false,"fraud_score":1.0}
# (encerre pelo PID da porta: ss -ltnpH 'sport = :9999')
```

---

## §10. Gate 2 — tamanho

```bash
cd api
ls -l src/main/resources/hnsw.bin src/main/resources/references.bin
#  ~300 MB  hnsw.bin   (vs ~439 MB RBH1)   ·   51000012  references.bin
ls -l target/api.jar                       # < 1 MB
jar tf target/api.jar | grep -E '\.(gz|bin|golden)$' || echo "JAR LIMPO"
```

Anote o tamanho real do `hnsw.bin` RBH2 (o spec previu ~300 MB; o número exato é
este). Se ainda > ~330 MB, revisar (encoding agressivo é o fallback do spec §7).

---

## §11. Gate 3 — A PROVA: cabe em 350 MB (cgroup real)

Balança de medição (NÃO é o artefato de submissão — Dockerfile/compose/HAProxy
são da Onda 4b). **Um** container com `--memory=350m --cpus=1`, dados read-only
montados, **2 instâncias** dentro (modela "todos os serviços em 350 MB" deixando
folga p/ o HAProxy futuro), servindo os oráculos.

```bash
cd api
DATA=$(pwd)/src/main/resources
JAR=$(pwd)/target/api.jar
JVM="-DDATA_PATH=/data -Xmx64m -Xms64m -XX:+UseSerialGC -XX:MaxMetaspaceSize=48m \
 -Xss512k -XX:ReservedCodeCacheSize=24m --add-modules jdk.incubator.vector"

docker run --rm --name fit350 --memory=350m --memory-swap=350m --cpus=1 \
  -v "$DATA":/data:ro -v "$JAR":/app/api.jar:ro \
  eclipse-temurin:21-jre bash -lc '
    java '"$JVM"' -jar /app/api.jar 9991 & P1=$!
    java '"$JVM"' -jar /app/api.jar 9992 & P2=$!
    for p in 9991 9992; do
      for i in $(seq 1 40); do
        curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://localhost:$p/ready \
          | grep -q 200 && break; done
    done
    for p in 9991 9992; do
      curl -s -X POST http://localhost:$p/fraud-score -H "Content-Type: application/json" \
       -d "{\"id\":\"tx-1329056812\",\"transaction\":{\"amount\":41.12,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:53Z\"},\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-003\",\"MERC-016\"]},\"merchant\":{\"id\":\"MERC-016\",\"mcc\":\"5411\",\"avg_amount\":60.25},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":29.23},\"last_transaction\":null}"; echo
    done
    # rajada curta nas 2 portas
    for r in $(seq 1 300); do curl -s -o /dev/null http://localhost:9991/ready; \
                              curl -s -o /dev/null http://localhost:9992/ready; done
    echo "memory.peak=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null) \
          memory.current=$(cat /sys/fs/cgroup/memory.current 2>/dev/null)"
    kill $P1 $P2 2>/dev/null
  '
docker inspect fit350 --format "OOMKilled={{.State.OOMKilled}}" 2>/dev/null || true
```

**PASS** = sem `OOMKilled` (container não morreu por memória), `memory.peak`
**< 367001600** (350 MiB), os 2 oráculos exatos nas 2 portas. Ajuste o
`-Xmx`/metaspace se faltar folga; o ganho da compactação (§10) é o que dá essa
folga.

> Cgroup v2: `memory.peak` em bytes; 350 MiB = 367.001.600 B. Se a sua engine
> usar cgroup v1, leia `memory.max_usage_in_bytes`. `--memory-swap=--memory`
> desliga swap (fiel ao Rinha).

---

## §12. Gate 4 — latência (medição)

```bash
cd api
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.BenchHnsw 2000
# HNSW p50≈0.0XXms p99≈0.1XXms  ·  comparar com Onda 3 (p99 0.145 ms)
```

`int24` adiciona ~3 leituras+shifts por aresta no `searchLayer`. Espera-se p99
ainda **sub-ms** e na mesma ordem da Onda 3. Opcional/realista: medir `BenchHnsw`
**dentro** do container do Gate 3 (sob o teto de 350 MB) — é o número honesto;
se o p99 disparar, é sinal de thrashing de page-cache → revisar compactação
(fallback: encoding agressivo, spec §7). Sem threshold absoluto (p99<1ms = Onda
5 Native Image).

---

## §13. Pegadinhas (resumo)

| ⚠️ | Detalhe | § |
|---|---|---|
| `int24` com sinal/endianness | `get24`/`w24` mascaram `&0xFF`, big-endian igual aos `int32`; id < 2²⁴ (sempre ≥0) | §3 |
| recall mudou | NÃO é lossless — bug no `write`/reader; Gate 1 (`Rbh2Equiv` 0-div **e** recall idêntico 96.89%) | §4/§9 |
| `node_k` não ordenado | busca binária quebra; `node[]` é montado por `i` crescente ⇒ já ordenado | §4 |
| guardar golden DEPOIS de migrar | `cp hnsw.bin hnsw.rbh1.golden` **antes** de gerar o RBH2 (senão o golden já é RBH2) | §8 |
| container constrói o grafo | impossível em 350 MB/1 CPU; `tools.Prebuild` offline, container só mmapeia | §8 |
| `DATA_PATH` quebrando o dev | default = `src/main/resources` (idêntico ao atual); só o container seta `-D` | §6 |
| cgroup despeja grafo → p99 thrash | compactar reduz arquivo (mais working set residente); Gate 4 mede sob o teto | §11/§12 |
| leitor RBH1 no código de produção | RBH1 vive **só** em `src/test/Rbh2Equiv`; produção é só RBH2 | §9 |
| `api.jar` ainda gigante | Gate 2 falha se `jar tf` listar `.gz`/`.bin`; cheque os `<excludes>` | §7/§10 |
| busca binária no hot path | só camadas altas (descida `ef=1`, `Pk` minúsculo); L0 (quente) é denso O(1) + memo 1-entrada | §5 |
| posições > 2³¹ no mmap | RBH2 (~300 MB) < 2³¹ ⇒ ok; foi o que viabilizou compactar | §5 |

---

## §14. Próximos passos

**Onda 4a fechada** = Gate 1 (`Rbh2Equiv` 0-div + Onda 3 re-verde) + Gate 2
(tamanho, jar limpo) + Gate 3 (`docker run --memory=350m` sem OOMKill) + Gate 4
(p99 medido) verdes.

- **Onda 4b — conteinerização + HAProxy + k6 + submission**
  (`TUTORIAL_CONTAINER.md`, ✅ **criado** — 2026-05-17): imagem pública
  pré-buildada com os binários RBH2 baked; `Dockerfile` multi-stage
  (temurin jdk→jre); `docker/haproxy.cfg` **`mode tcp`/`nbthread 1`**;
  `docker-compose.yml` (HAProxy + 2 instâncias, `deploy.resources.limits`
  1.0 CPU / 350 M); branch `submission` orphan + `info.json` + PR
  `participants/arthurd3.json`; k6 oficial → `final_score`. 4 gates: stack
  e2e pelo LB, `docker stats` < 350 MB sem OOMKilled, k6 oficial, clone
  `--branch submission` fiel ao CI. Spec:
  `docs/superpowers/specs/2026-05-17-onda4b-container-design.md`.
- **Onda 5 — GraalVM Native Image + PGO**: revalidar Gate A da 2b + os gates da
  3/4a (regressão silenciosa Vector API → escalar; `-Dgraal.PrintCompilation`).

---

**Cada Gate é uma vitória.** 🏁
