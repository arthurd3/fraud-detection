package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

import java.nio.MappedByteBuffer;

public final class HnswIndex {

    private HnswIndex() {}

    public static void search(ConnectionState s) {
        final byte[] q = s.queryQ;
        final int n = MmapDataset.count;
        final MappedByteBuffer V = MmapDataset.data;
        final float[]   bd = s.knnDist;      
        final boolean[] bf = s.knnFraud;

        for (int k = 0; k < 5; k++) { bd[k] = Float.MAX_VALUE; bf[k] = false; }

        for (int i = 0; i < n; i++) {
            int d = DistanceFunctions.sqDistI8(q, V, MmapDataset.recBase(i));
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