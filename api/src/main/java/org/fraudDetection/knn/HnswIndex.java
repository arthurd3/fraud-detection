package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

import java.nio.MappedByteBuffer;

public final class HnswIndex {

    private HnswIndex() {}

    public static void search(ConnectionState s) {
        final byte[] q  = s.queryQ;
        final byte[] vs = s.vScratch;
        final int n = MmapDataset.count;
        final MappedByteBuffer V = MmapDataset.data;
        final float[]   bd = s.knnDist;
        final boolean[] bf = s.knnFraud;

        for (int k = 0; k < 5; k++) { bd[k] = Float.MAX_VALUE; bf[k] = false; }

        for (int i = 0; i < n; i++) {
            V.get(MmapDataset.recBase(i), vs, 0, 16);
            // Onda 2b validação 2026-05-16: sqDistI8 SIMD mediu 3.8x MAIS LENTO que o
            // escalar (Vector API convertShape mal-intrinsificado p/ 14-dim int8; o laço
            // escalar de 16 ints já é ótimo). Produção usa o escalar (mais rápido).
            // O sqDistI8 SIMD fica como ref do Gate A (DistEquivI8) + aprendizado.
            int d = DistanceFunctions.sqDistI8Scalar(q, vs);
            if (d < bd[4]) {
                int p = 4;
                while (p > 0 && bd[p - 1] > d) { bd[p] = bd[p - 1]; bf[p] = bf[p - 1]; p--; }
                bd[p] = d;
                bf[p] = MmapDataset.fraud(i);
            }
        }

        int fraud = 0;
        for (int k = 0; k < 5; k++) if (bf[k]) fraud++;
        s.fraudCount = fraud;
    }
}