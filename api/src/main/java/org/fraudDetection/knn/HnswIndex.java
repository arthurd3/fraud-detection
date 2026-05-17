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
        // valida contra o N efetivo (default = MmapDataset.count; -Dhnsw.maxNodes p/ smoke)
        if (!HnswGraph.isValid(bin, HnswBuilder.effectiveN())) {
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

    /** top-5 brute-force int8 (oráculo de recall — varre o conjunto indexado pelo grafo). */
    public static int top5Brute(byte[] q, int[] out) {
        int n = HnswGraph.count;                 // = MmapDataset.count em produção
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
