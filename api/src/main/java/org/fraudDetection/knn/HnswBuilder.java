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

    /** N efetivo: tudo por default; -Dhnsw.maxNodes=<K> limita p/ smoke barato (zero impacto prod). */
    public static int effectiveN() {
        return Math.min(MmapDataset.count,
                Integer.getInteger("hnsw.maxNodes", MmapDataset.count));
    }

    public static void build(String binPath) throws IOException {
        N = effectiveN();
        adj0 = new int[N * M0]; deg0 = new int[N];
        level = new int[N];
        up = new HashMap<>();
        entry = 0; maxLevel = 0;
        HnswScratch.init(MmapDataset.count);

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
        if (lc > level[node]) return 0;        // nó não existe nesta camada (up sized level*M)
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
            int n = drainSorted(WN, WD);      // crescente por dist(q,·)
            int mmax = Mmax(lc);
            // seleção de vizinhos COM heurística (Malkov-Yashunin Alg.4)
            int take = selectHeuristic(WN, WD, n, mmax, TMP);
            setNbrs(q, lc, TMP, take);
            // back-links + poda
            for (int t = 0; t < take; t++) connect(TMP[t], q, lc);
            if (n > 0) ep = WN[0];
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
        // poda COM heurística (não só os mmax mais perto) → preserva navegabilidade
        int[] sel = new int[mmax];
        int sl = selectHeuristic(cand, cd, mmax + 1, mmax, sel);
        setNbrs(e, lc, sel, sl);
    }

    /**
     * Malkov-Yashunin Alg.4 — seleção de vizinhos COM heurística.
     * cand[0..n) / bd[0..n) ASCENDENTES por bd (= dist a `base`). Aceita um candidato e
     * só se ele estiver mais perto de `base` do que de qualquer já-selecionado → preserva
     * arestas de longo alcance (grafo navegável). Backfill (keepPrunedConnections) até Mret.
     */
    private static int selectHeuristic(int[] cand, int[] bd, int n, int Mret, int[] out) {
        int r = 0;
        for (int i = 0; i < n && r < Mret; i++) {
            int e = cand[i];
            int de = bd[i];                       // dist(e, base) (precomputado)
            boolean good = true;
            for (int j = 0; j < r; j++)
                if (dist(e, out[j]) < de) { good = false; break; }
            if (good) out[r++] = e;
        }
        for (int i = 0; i < n && r < Mret; i++) {  // backfill: completa c/ mais próximos
            int e = cand[i], in = 0;
            for (int j = 0; j < r; j++) if (out[j] == e) { in = 1; break; }
            if (in == 0) out[r++] = e;
        }
        return r;
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
