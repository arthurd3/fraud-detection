package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswGraph;
import org.fraudDetection.knn.HnswIndex;

import java.io.IOException;

/** Diagnóstico (systematic-debugging): conectividade/grau do grafo HNSW. NÃO é produção. */
public final class HnswStats {
    public static void main(String[] args) throws IOException {
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        HnswIndex.load("src/main/resources/hnsw.bin");
        int N = HnswGraph.count;
        System.out.printf("N=%d maxLevel=%d entry=%d%n", N, HnswGraph.maxLevel, HnswGraph.entry);

        // grau camada 0
        long sum = 0; int zero = 0, min = Integer.MAX_VALUE, max = 0;
        for (int i = 0; i < N; i++) {
            int d = HnswGraph.nbrHi(i, 0) - HnswGraph.nbrLo(i, 0);
            sum += d; if (d == 0) zero++; if (d < min) min = d; if (d > max) max = d;
        }
        System.out.printf("L0 grau: min=%d avg=%.2f max=%d  nós-grau-0=%d (%.1f%%)%n",
                min, sum / (double) N, max, zero, 100.0 * zero / N);

        // BFS camada 0 a partir do entry → tamanho do componente alcançável
        boolean[] seen = new boolean[N];
        int[] q = new int[N]; int head = 0, tail = 0;
        seen[HnswGraph.entry] = true; q[tail++] = HnswGraph.entry;
        while (head < tail) {
            int c = q[head++];
            int lo = HnswGraph.nbrLo(c, 0), hi = HnswGraph.nbrHi(c, 0);
            for (int idx = lo; idx < hi; idx++) {
                int e = HnswGraph.nbrAt(0, idx);
                if (e >= 0 && e < N && !seen[e]) { seen[e] = true; q[tail++] = e; }
            }
        }
        System.out.printf("L0 alcançável de entry: %d / %d (%.1f%%)%n",
                tail, N, 100.0 * tail / N);

        // grau camada 0 também NÃO-direcionado? checa simetria de amostra
        int asym = 0, sample = Math.min(N, 2000);
        for (int i = 0; i < sample; i++) {
            int lo = HnswGraph.nbrLo(i, 0), hi = HnswGraph.nbrHi(i, 0);
            for (int idx = lo; idx < hi; idx++) {
                int e = HnswGraph.nbrAt(0, idx);
                boolean back = false;
                int lo2 = HnswGraph.nbrLo(e, 0), hi2 = HnswGraph.nbrHi(e, 0);
                for (int j = lo2; j < hi2; j++) if (HnswGraph.nbrAt(0, j) == i) { back = true; break; }
                if (!back) asym++;
            }
        }
        System.out.printf("L0 arestas sem back-link (amostra %d nós): %d%n", sample, asym);
    }
}
