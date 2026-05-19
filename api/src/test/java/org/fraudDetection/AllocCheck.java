package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswIndex;

import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

/** Gate 2 (Onda 6): prova que top5Hnsw é zero-alloc por query (HotSpot).
 *  Baseline pré-Onda-6: takeTop5 fazia new int[n] x2 (n=rSize≈50) ≈ ~400 B/query. */
public final class AllocCheck {
    public static void main(String[] args) throws Exception {
        // mesmo load dos demais harnesses (RecallHnsw/Rbh2Equiv)
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        HnswIndex.load("src/main/resources/hnsw.bin");

        int Q = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;

        // queries = primeiros Qv records do dataset (byte[16] já quantizado no .bin);
        // a alocação do takeTop5 independe da query — depende só do drain.
        int Qv = 256;
        byte[][] qs = new byte[Qv][16];
        for (int i = 0; i < Qv; i++)
            MmapDataset.data.get(MmapDataset.recBase(i), qs[i], 0, 16);

        int[] out = new int[5];

        // warmup (JIT + bootstrap do scratch fora da medição)
        for (int i = 0; i < 20_000; i++) HnswIndex.top5Hnsw(qs[i % Qv], out);

        ThreadMXBean tb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long a0 = tb.getThreadAllocatedBytes(tid);
        for (int i = 0; i < Q; i++) HnswIndex.top5Hnsw(qs[i % Qv], out);
        long a1 = tb.getThreadAllocatedBytes(tid);

        double perQ = (a1 - a0) / (double) Q;
        System.out.printf("alloc: %d bytes em %d queries -> %.2f B/query%n",
                          (a1 - a0), Q, perQ);
        // PASS = ~0 B/query (pré-Onda-6: ~2*rSize*4 ≈ 400 B/query)
        boolean pass = perQ < 16.0;            // folga p/ ruído de medição
        System.out.println(pass ? "Gate 2: zero-alloc -> PASS"
                                 : "Gate 2: AINDA ALOCA -> FAIL");
        if (!pass) System.exit(1);
    }
}
