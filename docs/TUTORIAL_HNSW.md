# Tutorial — Onda 3: HNSW hand-rolled (grafo navegável, recall ≥95%)

> De **Onda 2b** (`sqDistI8` SIMD, RB2 int8 off-heap, brute-force 3M por request)
> → **mesma resposta (aproximada)** com **grafo HNSW**: busca ~`ef_search·log N` em vez de N.
> **Tempo estimado**: 8-14h (a onda mais densa). **Pré-requisito ABSOLUTO**: Onda 2b
> implementada e verde (RB2 padded-16, `DistanceFunctions.sqDistI8Scalar(byte[],byte[])`,
> `ConnectionState.queryQ[16]`/`vScratch[16]`). Spec:
> `docs/superpowers/specs/2026-05-16-onda3-hnsw-design.md`.

> ⚠️ **CORREÇÃO — 2026-05-16 (consistência c/ o achado do Gate 3 da Onda 2b).**
> Este tutorial foi escrito ANTES de medir que o `sqDistI8` **SIMD** ficou **3.8× mais
> lento** que o escalar. **O HNSW usa `DistanceFunctions.sqDistI8Scalar`** em TODAS as
> distâncias (build nó↔nó, search query↔nó, `top5Brute`) — nunca o `sqDistI8` SIMD. Importa
> ainda mais no **build**: O(N·efC·logN) ≈ **bilhões** de distâncias no 1º boot; o SIMD
> deixaria o build ~4× mais lento. O `sqDistI8` SIMD existe só como referência do Gate A
> da 2b. Os snippets abaixo já estão corrigidos.

---

## §0. Visão geral, o que muda, critério de saída

A 2a/2b deixaram a distância barata, mas `HnswIndex.search` ainda **varre os 3.000.000
vetores por request**. A Onda 3 troca isso por um **grafo HNSW** (Malkov-Yashunin,
hand-rolled): camadas hierárquicas, descida gulosa, busca local com `ef`. Custo por query
cai de `N` para ~`ef_search·log N` distâncias.

HNSW é **aproximado** — o top-5 pode diferir do brute-force. Logo o brute-force int8 (da
2a/2b) vira o **oráculo de verdade**, mantido como `searchBrute`. O HNSW precisa ficar
perto o bastante: **recall@5 ≥ 95%** e **approved-agreement ≥ 99%** vs o brute-force.

### O que muda (inventário)

| # | Arquivo | Ação |
|---|---|---|
| 1 | `knn/HnswScratch.java` | **novo** — scratch estático (visited versionado + 2 heaps) |
| 2 | `knn/HnswBuilder.java` | **novo** — constrói o grafo do RB2, grava `hnsw.bin` |
| 3 | `knn/HnswGraph.java` | **novo** — mmap RO do `hnsw.bin` (CSR) |
| 4 | `knn/HnswIndex.java` | **reescrito** — `search` HNSW + `searchBrute` (oráculo) + `top5*` |
| 5 | `Main.java` | boot: `HnswIndex.load(...)` após `MmapDataset.load(...)` |
| 6 | `api/.gitignore` | += `src/main/resources/hnsw.bin` |
| 7 | `src/test/RecallHnsw.java` | **novo** — Gate 3a recall@5 + Gate 3b approved |
| 8 | `src/test/BenchHnsw.java` | **novo** — Gate 4 p50/p99 + curva ef_search |
| — | `Gate2Int8`/`TestDataReader` | reusados (Gate 2 sanity) |

### Critério de saída da Onda 3

- **Gate 1 (bloqueia):** §9 e2e — `/ready` 200; `tx-1329056812`→`{"approved":true,
  "fraud_score":0.0}`; `tx-3330991687`→`{"approved":false,"fraud_score":1.0}`.
- **Gate 2 (sanity):** `Gate2Int8 2000` ≥99% vs baseline float (agora **aproximado** —
  não mais `1995` fixo da 2b; só não pode degradar).
- **Gate 3a (bloqueia):** `recall@5` médio ≥ **95%** vs brute-force int8 (2.000).
- **Gate 3b (bloqueia):** `approved` igual ao brute-force int8 ≥ **99%** (2.000) + FP/FN.
- **Gate 4 (medição):** p50/p99 HNSW vs brute + curva `recall × ef_search`.
- 1º boot constrói `hnsw.bin`; boots seguintes mmapeiam; steady-state `-Xmx256m`.

---

## §1. Mapa mental

```
BOOT 1º:  references.bin RB2 (já existe da 2b) -> constrói HNSW
          (insere nó a nó, ef_construction=200) -> grava hnsw.bin -> mmap
BOOT 2º+: references.bin + hnsw.bin --mmap--> pronto (instantâneo)

POST /fraud-score:
  parse -> queryQ[16] (igual 2b)
  HnswIndex.search:  ep=entry; desce camadas maxLevel..1 (greedy ef=1)
                     camada 0: searchLayer(ef=ef_search) -> top-5
  fraudCount -> resposta canned (igual 2a/2b)
```

Só a **estrutura de busca** muda. Parser, fórmula 14-D, `sqDistI8Scalar`, RB2, respostas
canned, servidor — **intactos**. A resposta é a mesma *quando o recall é alto*.

---

## §2. Princípios (lembrete + HNSW)

1. **HNSW em 1 parágrafo:** cada nó recebe um nível aleatório (geométrico). A busca começa
   no `entryPoint` (nível mais alto), desce **gulosa** (ef=1) até a camada 0, e na camada 0
   faz uma busca local com lista de tamanho `ef` (heaps). Mais `ef` = mais recall, mais
   custo. Camada 0 tem todos os nós (grau ≤ `M0`); camadas altas são esparsas (grau ≤ `M`).
2. **Visited versionado.** `boolean[] + clear()` por query mata o p99 (memset de 3M/req).
   Use `int[] visited` + contador `gen`: visto ⇔ `visited[n]==gen`; `newQuery()` faz
   `gen++`. Zero memset por request.
3. **Scratch estático.** Reator single-thread → 1 request por vez → um `HnswScratch`
   estático (heaps + visited) basta. **Não** é thread-safe (ok hoje; se a Onda futura for
   multi-thread, vira per-thread).
4. **Build NÃO é hot path** (1×, 1º boot). Pode usar `java.util.*` à vontade. A regra
   zero-alloc/by-hand vale só pro **search** (caminho do request). Build exige **heap
   grande no 1º boot** (`-Xmx2g`); steady-state (mmap) volta a `-Xmx256m`.
5. **Determinismo.** O nível de cada nó vem de um RNG **com seed fixa** (xorshift) → grafo
   reprodutível → Gate reprodutível.
6. **Distância = `sqDistI8Scalar`** (escalar — decisão 2b Gate 3; o `sqDistI8` SIMD ficou
   3.8× mais lento e o build faz bilhões de distâncias). Nó↔nó (build) e query↔nó (search)
   carregam os 16 bytes do RB2 via `MmapDataset.data.get(recBase(x), buf, 0, 16)`.

---

## §3. Formato `hnsw.bin` (CSR plano)

```
[ header 28B ]  magic 'R','B','H','1'(4) | int32 count | int32 M | int32 M0
                | int32 efC | int32 entryPoint | int32 maxLevel
[ levels   ]    count × uint8   (nível-topo do nó; 0..maxLevel)
[ L0 CSR   ]    int32 off0[count+1] | int32 nbr0[ off0[count] ]
[ Lk CSR   ]    p/ k=1..maxLevel: int32 offk[count+1] | int32 nbrk[ offk[count] ]
```

- `vizinhos(node,k)` = `nbrk[ offk[node] .. offk[node+1] )`. CSR uniforme `count+1` por
  camada (nó ausente na camada k → `offk[node+1]==offk[node]`). Leitor trivial.
- Big-endian (default `ByteBuffer`/`writeInt`), igual RB2.
- Tamanho: dominado por L0 (~centenas de MB off-heap mmap). **Budget 350 MB e
  compactação (int24, camadas altas esparsas) = Onda 4.** Onda 3 roda no box de dev.
- Auto-migração: regenera se ausente OU magic≠`RBH1` OU count≠`MmapDataset.count`.

---

## §4. `knn/HnswScratch.java` (novo)

Scratch estático: visited versionado + 2 heaps (candidatos = **min-heap** por dist;
resultado = **max-heap** por dist, podado a `ef`). Arrays de record p/ a distância.

```java
package org.fraudDetection.knn;

/** Scratch HNSW único — reator single-thread (NÃO thread-safe; 1 request por vez). */
public final class HnswScratch {
    private HnswScratch() {}

    public static int   count;
    public static int[] visited;          // visited[n]==gen => visto NESTA query
    public static int   gen;

    // candidatos: MIN-heap por dist (explora o mais perto primeiro)
    public static int[] cN, cD; public static int cSize;
    // resultado: MAX-heap por dist (raiz = mais distante; evict quando passa de ef)
    public static int[] rN, rD; public static int rSize;

    // buffers de record RB2 (16 bytes) p/ a distância
    public static byte[] bufA, bufB;

    private static final int CAP = 1 << 15;   // folgado p/ ef<=200 neste dataset

    public static void init(int n) {
        count = n;
        visited = new int[n]; gen = 0;
        cN = new int[CAP]; cD = new int[CAP];
        rN = new int[CAP]; rD = new int[CAP];
        bufA = new byte[16]; bufB = new byte[16];
    }
    public static void newQuery() { gen++; cSize = 0; rSize = 0; }
    public static boolean seen(int n) { return visited[n] == gen; }
    public static void mark(int n)   { visited[n] = gen; }

    // ---- MIN-heap candidatos ----
    public static void cPush(int node, int dist) {
        int i = cSize++; cN[i] = node; cD[i] = dist;
        while (i > 0) { int p = (i-1) >> 1; if (cD[p] <= cD[i]) break; sw(cN,cD,p,i); i = p; }
    }
    public static int cMinDist() { return cD[0]; }
    public static int cPopNode() {
        int top = cN[0]; int last = --cSize;
        cN[0] = cN[last]; cD[0] = cD[last];
        int i = 0;
        while (true) { int l=2*i+1, r=l+1, mn=i;
            if (l<cSize && cD[l]<cD[mn]) mn=l;
            if (r<cSize && cD[r]<cD[mn]) mn=r;
            if (mn==i) break; sw(cN,cD,mn,i); i=mn; }
        return top;
    }
    // ---- MAX-heap resultado ----
    public static void rPush(int node, int dist) {
        int i = rSize++; rN[i]=node; rD[i]=dist;
        while (i>0) { int p=(i-1)>>1; if (rD[p]>=rD[i]) break; sw(rN,rD,p,i); i=p; }
    }
    public static int rMaxDist() { return rD[0]; }
    public static int rMaxNode() { return rN[0]; }
    public static void rPopMax() {
        int last = --rSize; rN[0]=rN[last]; rD[0]=rD[last];
        int i=0;
        while (true){ int l=2*i+1,r=l+1,mx=i;
            if (l<rSize && rD[l]>rD[mx]) mx=l;
            if (r<rSize && rD[r]>rD[mx]) mx=r;
            if (mx==i) break; sw(rN,rD,mx,i); i=mx; }
    }
    private static void sw(int[] a, int[] b, int x, int y) {
        int t=a[x]; a[x]=a[y]; a[y]=t; t=b[x]; b[x]=b[y]; b[y]=t;
    }
}
```

🔍 **Test point 1 — visited versionado**. `init(10)`; `newQuery(); mark(3); seen(3)==true`;
`newQuery(); seen(3)==false` (sem nenhum `clear()`).

---

## §5. `knn/HnswBuilder.java` (novo)

Constrói o grafo (Malkov-Yashunin Alg.1) e grava `hnsw.bin`. **Build = JDK liberado**
(não é hot path). Adjacência **L0 densa** (`int[count*M0]` + `int[] deg0`); camadas altas
**esparsas** via `HashMap<Integer,int[]>` (poucos nós). RNG xorshift seed fixa.

```java
package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

public final class HnswBuilder {
    private HnswBuilder() {}

    static final int M = 16, M0 = 32, EF_C = 200;
    static final double ML = 1.0 / Math.log(M);

    // ---- RNG determinístico (xorshift64, seed fixa) ----
    private static long rng = 0x9E3779B97F4A7C15L;
    private static double next01() {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return ((rng >>> 11) & ((1L<<53)-1)) / (double)(1L<<53);
    }
    private static int randomLevel() { return (int)(-Math.log(next01()) * ML); }

    static int N;
    static int[] adj0;            // L0 denso: node*M0 .. +deg0[node]
    static int[] deg0;
    static int[] level;          // nível de cada nó
    static Map<Integer,int[]> up;// node -> upper neighbors: bloco l em [(l-1)*M, l*M), -1 pad
    static int entry, maxLevel;

    public static void build(String binPath) throws IOException {
        N = MmapDataset.count;
        adj0 = new int[N * M0]; deg0 = new int[N];
        level = new int[N];
        up = new HashMap<>();
        entry = 0; maxLevel = 0;
        HnswScratch.init(N);

        for (int i = 0; i < N; i++) {
            insert(i);
            if ((i % 200_000) == 0) System.out.println("  HNSW " + i + "/" + N + "...");
        }
        write(binPath);
        System.out.println("HNSW construído: " + N + " nós, maxLevel=" + maxLevel
                + ", entry=" + entry);
    }

    // ---- distância nó↔nó (RB2) ----
    private static int dist(int a, int b) {
        MmapDataset.data.get(MmapDataset.recBase(a), HnswScratch.bufA, 0, 16);
        MmapDataset.data.get(MmapDataset.recBase(b), HnswScratch.bufB, 0, 16);
        return DistanceFunctions.sqDistI8Scalar(HnswScratch.bufA, HnswScratch.bufB);
    }

    private static int Mmax(int lc) { return lc == 0 ? M0 : M; }

    // vizinhos mutáveis de `node` na camada lc (durante o build)
    private static int degOf(int node, int lc) {
        if (lc == 0) return deg0[node];
        int[] b = up.get(node); if (b == null) return 0;
        int base = (lc-1)*M, d = 0;
        while (d < M && b[base+d] != -1) d++;
        return d;
    }
    private static int nbrOf(int node, int lc, int idx) {
        return lc == 0 ? adj0[node*M0 + idx] : up.get(node)[(lc-1)*M + idx];
    }
    private static void setNbrs(int node, int lc, int[] ids, int len) {
        if (lc == 0) {
            System.arraycopy(ids, 0, adj0, node*M0, len);
            deg0[node] = len;
        } else {
            int[] b = up.get(node);
            int base = (lc-1)*M;
            for (int k = 0; k < M; k++) b[base+k] = k < len ? ids[k] : -1;
        }
    }
    private static void ensureUp(int node, int lvl) {
        if (lvl >= 1 && !up.containsKey(node)) {
            int[] b = new int[lvl * M]; java.util.Arrays.fill(b, -1); up.put(node, b);
        }
    }

    // searchLayer sobre a adjacência MUTÁVEL do build (Alg.2)
    private static void searchLayer(int q, int ep, int ef, int lc) {
        HnswScratch.newQuery();
        int de = dist(q, ep);
        HnswScratch.mark(ep);
        HnswScratch.cPush(ep, de);
        HnswScratch.rPush(ep, de);
        while (HnswScratch.cSize > 0) {
            int cd = HnswScratch.cMinDist();
            if (HnswScratch.rSize >= ef && cd > HnswScratch.rMaxDist()) break;
            int c = HnswScratch.cPopNode();
            int deg = degOf(c, lc);
            for (int t = 0; t < deg; t++) {
                int e = nbrOf(c, lc, t);
                if (HnswScratch.seen(e)) continue;
                HnswScratch.mark(e);
                int d = dist(q, e);
                if (HnswScratch.rSize < ef || d < HnswScratch.rMaxDist()) {
                    HnswScratch.cPush(e, d);
                    HnswScratch.rPush(e, d);
                    if (HnswScratch.rSize > ef) HnswScratch.rPopMax();
                }
            }
        }
    }

    // drena o max-heap de resultado em ordem CRESCENTE de dist
    private static int drainSorted(int[] outN, int[] outD) {
        int n = HnswScratch.rSize;
        for (int i = n - 1; i >= 0; i--) {
            outD[i] = HnswScratch.rMaxDist();
            outN[i] = HnswScratch.rMaxNode();
            HnswScratch.rPopMax();
        }
        return n;
    }

    private static final int[] WN = new int[EF_C + 8], WD = new int[EF_C + 8];
    private static final int[] TMP = new int[M0 + 8];

    private static void insert(int q) {
        int L = randomLevel();
        level[q] = L;
        ensureUp(q, L);
        if (N == 1 || (q == 0)) { entry = 0; maxLevel = level[0]; return; }

        int ep = entry, top = maxLevel;
        // desce gulosa até L+1
        for (int lc = top; lc > L; lc--) {
            searchLayer(q, ep, 1, lc);
            int n = drainSorted(WN, WD);
            ep = WN[0];                       // mais perto
        }
        // conecta de min(top,L) até 0
        for (int lc = Math.min(top, L); lc >= 0; lc--) {
            searchLayer(q, ep, EF_C, lc);
            int n = drainSorted(WN, WD);      // crescente
            int mmax = Mmax(lc);
            int take = Math.min(mmax, n);
            // q -> take mais próximos
            System.arraycopy(WN, 0, TMP, 0, take);
            setNbrs(q, lc, TMP, take);
            // back-links + poda
            for (int t = 0; t < take; t++) connect(WN[t], q, lc);
            ep = WN[0];
        }
        if (L > maxLevel) { entry = q; maxLevel = L; }
    }

    // adiciona e->q na camada lc; se estourar Mmax, poda mantendo os Mmax mais perto
    private static void connect(int e, int q, int lc) {
        int d = degOf(e, lc);
        int mmax = Mmax(lc);
        if (d < mmax) {
            if (lc == 0) { adj0[e*M0 + d] = q; deg0[e] = d + 1; }
            else { int[] b = up.get(e); b[(lc-1)*M + d] = q; }
            return;
        }
        // cheio: junta os d atuais + q, mantém os mmax mais perto de e
        int[] cand = new int[mmax + 1];
        for (int t = 0; t < mmax; t++) cand[t] = nbrOf(e, lc, t);
        cand[mmax] = q;
        // insertion sort por dist(e, cand)
        int[] cd = new int[mmax + 1];
        for (int t = 0; t <= mmax; t++) cd[t] = dist(e, cand[t]);
        for (int a = 1; a <= mmax; a++) {
            int vn = cand[a], vd = cd[a], b = a - 1;
            while (b >= 0 && cd[b] > vd) { cand[b+1]=cand[b]; cd[b+1]=cd[b]; b--; }
            cand[b+1]=vn; cd[b+1]=vd;
        }
        setNbrs(e, lc, cand, mmax);            // descarta o mais distante
    }

    // ---- achata p/ CSR e grava hnsw.bin ----
    private static void write(String binPath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(binPath, "rw")) {
            raf.setLength(0);
            raf.write(new byte[]{'R','B','H','1'});
            raf.writeInt(N); raf.writeInt(M); raf.writeInt(M0);
            raf.writeInt(EF_C); raf.writeInt(entry); raf.writeInt(maxLevel);
            for (int i = 0; i < N; i++) raf.writeByte(level[i]);   // levels[]
            for (int lc = 0; lc <= maxLevel; lc++) {
                // off[count+1]
                int acc = 0;
                int[] off = new int[N + 1];
                for (int i = 0; i < N; i++) { off[i] = acc; acc += degOf(i, lc); }
                off[N] = acc;
                for (int i = 0; i <= N; i++) raf.writeInt(off[i]);
                // nbr[]
                for (int i = 0; i < N; i++) {
                    int dd = degOf(i, lc);
                    for (int t = 0; t < dd; t++) raf.writeInt(nbrOf(i, lc, t));
                }
            }
            raf.getFD().sync();
        }
    }
}
```

> `degOf` p/ camada `lc>maxLevel` nunca é chamado (loops respeitam `maxLevel`). Nó 0 é o
> seed (entry inicial). `WN/WD/TMP` são reusados (build single-thread).

🔍 **Test point 2 — build pequeno**. Rode o builder com um RB2 de N≈1000 (gerado do
`example-references.json` quantizado): termina sem exceção, `entry` válido, `maxLevel`
pequeno (≥0), `recall@5` ~100% vs brute nesse N (grafo pequeno = quase exato).

---

## §6. `knn/HnswGraph.java` (novo) — mmap RO do `hnsw.bin`

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
    private static int levelsBase;
    private static int[] offBase, nbrBase;     // offset (bytes) por camada

    public static boolean isValid(File bin, int expectCount) {
        try (RandomAccessFile r = new RandomAccessFile(bin, "r")) {
            if (r.length() < 28) return false;
            byte[] m = new byte[4]; r.readFully(m);
            if (!(m[0]=='R'&&m[1]=='B'&&m[2]=='H'&&m[3]=='1')) return false;
            return r.readInt() == expectCount;          // count
        } catch (IOException e) { return false; }
    }

    public static void mmap(File bin) throws IOException {
        try (FileChannel ch = FileChannel.open(bin.toPath())) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            if (!(m.get(0)=='R'&&m.get(1)=='B'&&m.get(2)=='H'&&m.get(3)=='1'))
                throw new IOException("magic != RBH1");
            count    = m.getInt(4);
            M        = m.getInt(8);
            M0       = m.getInt(12);
            efC      = m.getInt(16);
            entry    = m.getInt(20);
            maxLevel = m.getInt(24);
            levelsBase = 28;
            int p = levelsBase + count;                 // após levels[count] (1B)
            offBase = new int[maxLevel + 1];
            nbrBase = new int[maxLevel + 1];
            for (int k = 0; k <= maxLevel; k++) {
                offBase[k] = p;
                int edges  = m.getInt(p + count * 4);   // off[count]
                nbrBase[k] = p + (count + 1) * 4;
                p = nbrBase[k] + edges * 4;
            }
            g = m;
        }
    }

    public static int level (int node)        { return g.get(levelsBase + node) & 0xFF; }
    public static int nbrLo  (int node, int k) { return g.getInt(offBase[k] + node*4); }
    public static int nbrHi  (int node, int k) { return g.getInt(offBase[k] + node*4 + 4); }
    public static int nbrAt  (int k, int idx)  { return g.getInt(nbrBase[k] + idx*4); }
}
```

🔍 **Test point 3 — boot**. 1º boot: log "construindo HNSW…" + "HNSW construído: 3000000
nós…" → grava `hnsw.bin`. 2º boot: **sem** "construindo", mmap instantâneo.

---

## §7. `knn/HnswIndex.java` (reescrito) — search HNSW + searchBrute

```java
package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

import java.io.File;
import java.io.IOException;

public final class HnswIndex {
    private HnswIndex() {}

    public static int efSearch = 50;          // ajustável (Gate 3a)

    public static void load(String hnswBin) throws IOException {
        File bin = new File(hnswBin);
        if (!HnswGraph.isValid(bin, MmapDataset.count)) {
            System.out.println("hnsw.bin ausente/incompativel — construindo HNSW (1x, minutos)...");
            HnswBuilder.build(hnswBin);
        }
        HnswGraph.mmap(bin);
        if (HnswScratch.visited == null) HnswScratch.init(MmapDataset.count);
        System.out.println("HNSW mmap: " + HnswGraph.count + " nós, maxLevel="
                + HnswGraph.maxLevel + ", entry=" + HnswGraph.entry);
    }

    // distância query(byte[16]) -> nó do RB2
    private static int distQ(byte[] q, int node) {
        MmapDataset.data.get(MmapDataset.recBase(node), HnswScratch.bufA, 0, 16);
        return DistanceFunctions.sqDistI8Scalar(q, HnswScratch.bufA);
    }

    // searchLayer sobre o GRAFO mmap (Alg.2)
    private static void searchLayer(byte[] q, int ep, int ef, int lc) {
        HnswScratch.newQuery();
        int de = distQ(q, ep);
        HnswScratch.mark(ep);
        HnswScratch.cPush(ep, de);
        HnswScratch.rPush(ep, de);
        while (HnswScratch.cSize > 0) {
            int cd = HnswScratch.cMinDist();
            if (HnswScratch.rSize >= ef && cd > HnswScratch.rMaxDist()) break;
            int c = HnswScratch.cPopNode();
            int lo = HnswGraph.nbrLo(c, lc), hi = HnswGraph.nbrHi(c, lc);
            for (int idx = lo; idx < hi; idx++) {
                int e = HnswGraph.nbrAt(lc, idx);
                if (HnswScratch.seen(e)) continue;
                HnswScratch.mark(e);
                int d = distQ(q, e);
                if (HnswScratch.rSize < ef || d < HnswScratch.rMaxDist()) {
                    HnswScratch.cPush(e, d);
                    HnswScratch.rPush(e, d);
                    if (HnswScratch.rSize > ef) HnswScratch.rPopMax();
                }
            }
        }
    }

    /** top-5 HNSW (ids) em out[0..4], crescente por dist. */
    public static int top5Hnsw(byte[] q, int[] out) {
        int ep = HnswGraph.entry;
        for (int lc = HnswGraph.maxLevel; lc >= 1; lc--) {
            searchLayer(q, ep, 1, lc);
            ep = nearestInResult();
        }
        searchLayer(q, ep, efSearch, 0);
        return takeTop5(out);
    }

    /** top-5 brute-force int8 (oráculo de recall — varre os N, igual 2a/2b). */
    public static int top5Brute(byte[] q, int[] out) {
        int n = MmapDataset.count;
        int[] bd = {Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,
                    Integer.MAX_VALUE,Integer.MAX_VALUE};
        int[] bi = {-1,-1,-1,-1,-1};
        byte[] vs = HnswScratch.bufB;
        for (int i = 0; i < n; i++) {
            MmapDataset.data.get(MmapDataset.recBase(i), vs, 0, 16);
            int d = DistanceFunctions.sqDistI8Scalar(q, vs);
            if (d < bd[4]) {
                int p = 4;
                while (p > 0 && bd[p-1] > d) { bd[p]=bd[p-1]; bi[p]=bi[p-1]; p--; }
                bd[p]=d; bi[p]=i;
            }
        }
        System.arraycopy(bi, 0, out, 0, 5);
        return 5;
    }

    private static int nearestInResult() {
        // o resultado é max-heap; o mais perto está numa folha → varre rN[0..rSize)
        int best = HnswScratch.rN[0], bd = HnswScratch.rD[0];
        for (int i = 1; i < HnswScratch.rSize; i++)
            if (HnswScratch.rD[i] < bd) { bd = HnswScratch.rD[i]; best = HnswScratch.rN[i]; }
        return best;
    }
    private static int takeTop5(int[] out) {
        // drena o max-heap; os 5 menores ficam no fim → reordena
        int n = HnswScratch.rSize;
        int[] tn = new int[n], td = new int[n];
        for (int i = n-1; i >= 0; i--) { td[i]=HnswScratch.rMaxDist(); tn[i]=HnswScratch.rMaxNode(); HnswScratch.rPopMax(); }
        int k = Math.min(5, n);
        for (int i = 0; i < 5; i++) out[i] = i < k ? tn[i] : -1;
        return k;
    }

    /** Produção: HNSW → fraudCount (decisão idêntica a 2a/2b). */
    public static void search(ConnectionState s) {
        int[] ids = s.knn5 != null ? s.knn5 : (s.knn5 = new int[5]);
        top5Hnsw(s.queryQ, ids);
        int fraud = 0;
        for (int i = 0; i < 5; i++) {
            int id = ids[i];
            boolean f = id >= 0 && MmapDataset.fraud(id);
            s.knnFraud[i] = f; if (f) fraud++;
        }
        s.fraudCount = fraud;
    }
}
```

`ConnectionState`: adicione `public int[] knn5;` (lazy; top-5 ids; zero-alloc após 1ª
request). `knnDist`/`knnFraud` da 2a continuam; `knnDist` não é mais usado pelo HNSW
(ranking vem do heap) — pode manter.

> `top5Brute` reusa o loop exato da 2b (oráculo de recall, análogo ao `sqDistI8Scalar`).
> **Não delete** — o Gate 3 depende dele.

🔍 **Test point 4 — searchBrute == 2b**. `top5Brute(queryQ_oráculo, out)` → os mesmos
top-5 que o `HnswIndex.search` da 2b produzia (decisão idêntica).

---

## §8. `Main.java` + `.gitignore`

`Main` (após `MmapDataset.load(...)` da 2b):

```java
        HnswIndex.load("src/main/resources/hnsw.bin");
        System.out.println("hnsw pronto");
```

`api/.gitignore` (junto das outras regras de dataset):

```
### Rinha HNSW (local, regenerável) ###
src/main/resources/hnsw.bin
```

🔍 **Test point 5 — 2 boots**. 1º: "construindo HNSW…" → `ls -l hnsw.bin` (centenas de
MB). 2º: mmap instantâneo, `-Xmx256m` sem OOM (grafo off-heap).

---

## §9. Gate 1 — §10 e2e (idêntico)

`./mvnw clean package` (exit 0). 1º boot **precisa de heap grande p/ construir**:

```bash
java -Xmx2g --add-modules jdk.incubator.vector -jar target/api.jar 9999
# "construindo HNSW (1x, minutos)..." -> "HNSW construído..." -> "Listening on port 9999"
```

Depois de `hnsw.bin` existir, steady-state volta a `-Xmx256m`:

```bash
java -Xmx256m --add-modules jdk.incubator.vector -jar target/api.jar 9999

curl -s http://localhost:9999/ready -i | head -1     # HTTP/1.1 200 OK
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
 -d '{"id":"tx-1329056812","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}'
# esperado: {"approved":true,"fraud_score":0.0}
curl -s -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
 -d '{"id":"tx-3330991687","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.27},"last_transaction":null}'
# esperado: {"approved":false,"fraud_score":1.0}
```

Os 2 são 0/5 e 5/5 (longe do 0.6) → robustos a recall. Bateu + `/ready` 200 → **Gate 1**.

---

## §10. Gate 2 — sanity vs baseline float (agora aproximado)

```bash
./mvnw -q test-compile
java -Xmx256m -cp target/classes:target/test-classes org.fraudDetection.Gate2Int8 2000
# esperado: Gate 2: ~19XX/2000 = ~9X% -> PASS  (>=99%; NÃO mais o 1995 fixo da 2b)
```

> HNSW é aproximado: o número **pode** sair de 1995. O gate aqui é só "≥99% vs baseline
> float" (não degradou). O oráculo forte da onda é o **Gate 3** (vs brute-force int8).

---

## §11. Gate 3a — recall@5 ≥ 95% · §12. Gate 3b — approved ≥ 99%

Crie `api/src/test/java/org/fraudDetection/RecallHnsw.java`. Para cada uma das 2.000
queries: `top5Brute` (verdade) vs `top5Hnsw`; recall@5 = |interseção|/5; `approved` de
cada via labels dos 5 ids; agreement + FP/FN. Varia `ef_search` se preciso.

```java
package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RecallHnsw {
    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        if (args.length > 1) HnswIndex.efSearch = Integer.parseInt(args[1]);
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        HnswIndex.load("src/main/resources/hnsw.bin");

        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");
        int[] bh = new int[5], hh = new int[5];
        int n = 0, agree = 0, fp = 0, fn = 0; double recSum = 0;

        TestDataReader.Entry e;
        while (n < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b); s.bodyOffset = 0; s.contentLength = b.length;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) { n++; continue; }
            Quantizer.quantize(s.queryVector, s.queryQ);

            HnswIndex.top5Brute(s.queryQ, bh);
            HnswIndex.top5Hnsw (s.queryQ, hh);

            int inter = 0;
            for (int x : hh) if (x >= 0) for (int y : bh) if (x == y) { inter++; break; }
            recSum += inter / 5.0;

            boolean apH = approved(hh), apB = approved(bh);
            if (apH == apB) agree++;
            else if (apH && !apB) fp++; else fn++;
            n++;
        }
        double recall = 100.0 * recSum / n, ag = 100.0 * agree / n;
        System.out.printf("ef_search=%d  recall@5=%.2f%%  approved-agree=%.2f%% (FP=%d FN=%d) -> %s%n",
                HnswIndex.efSearch, recall, ag, fp, fn,
                (recall >= 95.0 && ag >= 99.0) ? "PASS" : "FAIL");
        if (!(recall >= 95.0 && ag >= 99.0)) System.exit(1);
    }
    static boolean approved(int[] ids) {
        int fr = 0; for (int id : ids) if (id >= 0 && MmapDataset.fraud(id)) fr++;
        return fr < 3;                       // fraud_score < 0.6
    }
}
```

Rodar (subindo `ef_search` até passar):

```bash
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.RecallHnsw 2000 50
# ef_search=50  recall@5=9X.XX%  approved-agree=9X.XX% (FP=.. FN=..) -> PASS|FAIL
# se FAIL por recall: tente 64, 100, 128...  (anote a curva p/ o Gate 4)
```

🔍 **Gate 3a/3b verdes** = `recall@5 ≥ 95%` **E** `approved-agree ≥ 99%`. Fixe o
`ef_search` mínimo que passa (default no `HnswIndex.efSearch`).

---

## §13. Gate 4 — p99 HNSW vs brute (medição) + curva ef_search

Crie `api/src/test/java/org/fraudDetection/BenchHnsw.java` (mesmo molde do `BenchSearch`
da 2b: pré-quantiza N queries, warmup, mede `nanoTime` por busca **HNSW** e **brute**).

```java
// ... idêntico ao BenchSearch da 2b, trocando o corpo da busca por:
//   HNSW : HnswIndex.top5Hnsw(q, out5)
//   BRUTE: HnswIndex.top5Brute(q, out5)
// reporta p50/p99/média dos dois + speedup; opcional: loop variando ef_search
```

```bash
java -Xmx256m --add-modules jdk.incubator.vector \
     -cp target/classes:target/test-classes org.fraudDetection.BenchHnsw 2000
# BRUTE  p50=.. p99=..   HNSW  p50=.. p99=..   speedup p50 = XXx
```

> Sem threshold absoluto (p99<1ms = Onda 5 Native Image). O aprendizado: HNSW **ordens
> de grandeza** mais rápido que o scan 3M, mantendo recall ≥95% / approved ≥99%.

---

## §14. Pegadinhas (resumo)

| ⚠️ | Detalhe | § |
|---|---|---|
| visited memset | `boolean[]+clear()`/query mata o p99 → **versioned** `int gen` | §2/§4 |
| scratch estático | só p/ reator single-thread; multi-thread → per-thread | §2/§4 |
| build heap | 1º boot exige `-Xmx2g` (adjacência L0 densa); steady-state `-Xmx256m` | §5/§9 |
| distância escalar | HNSW usa `sqDistI8Scalar` (NÃO o `sqDistI8` SIMD — 3.8× mais lento, achado Gate 3 da 2b); crítico no build (bilhões de distâncias) | §2/§5/§7 |
| RNG níveis | seed fixa (xorshift) → grafo/Gate reprodutíveis | §5 |
| `searchLayer` quebra | `cd > rMaxDist()` **só** quando `rSize>=ef` | §5/§7 |
| nó 0 = seed | 1º insert define entry; sem vizinhos ainda (guarda no `insert`) | §5 |
| oráculo recall | NÃO deletar `top5Brute` (é o ground-truth da onda) | §7 |
| Gate 2 aproximado | pode sair de `1995` (HNSW≠exato); só `≥99%` vs float | §10 |
| recall baixo | subir `ef_search` (curva no Gate 4); `M/efC` fixos do plano | §11 |
| budget hnsw.bin | centenas de MB; int24/compactação + 350 MB = **Onda 4** | §3 |
| CSR uniforme | `offk` tem `count+1` por camada (nó ausente: `offk[i+1]==offk[i]`) | §3/§6 |
| big-endian | `writeInt`/`getInt` (default); não setar LITTLE_ENDIAN | §3/§6 |

---

## §15. Próximos passos

**Onda 3 fechada** = Gate 1 + Gate 2 (≥99%) + Gate 3a (recall@5 ≥95%) + Gate 3b
(approved ≥99%) + Gate 4 (p99 medido) verdes; `hnsw.bin` off-heap; steady-state `-Xmx256m`.

- **Onda 4 — conteinerização + k6 + budget 350 MB** (`TUTORIAL_CONTAINER.md`, a criar):
  Docker distroless, HAProxy TCP, 2 instâncias, **resolver memória** (jar não empacota
  `.gz`; `hnsw.bin` int24/compactado; mmap compartilhado entre instâncias), k6 oficial.
  Pré-build offline do `references.bin`/`hnsw.bin` (branch `submission`).
- **Onda 5 — GraalVM Native Image + PGO**: **revalidar Gate 3 + Gate A da 2b** (regressão
  silenciosa do Vector API → escalar em Native Image; `-Dgraal.PrintCompilation`).

---

**Cada Gate é uma vitória.** 🏁
