package org.fraudDetection.knn;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.ConnectionState;

public final class HnswIndex {

    private HnswIndex() {}

    public static void search(ConnectionState s) {
        final float[]   q  = s.queryVector;
        final float[][] V  = MmapDataset.vectors;
        final boolean[] F  = MmapDataset.isFraud;
        final int       n  = MmapDataset.count;
        final float[]   bd = s.knnDist;     
        final boolean[] bf = s.knnFraud;

        for (int k = 0; k < 5; k++) { bd[k] = Float.MAX_VALUE; bf[k] = false; }

        for (int i = 0; i < n; i++) {
            float d = DistanceFunctions.sqDist(q, V[i]);
            if (d < bd[4]) {                          
                int p = 4;
                while (p > 0 && bd[p - 1] > d) {      
                    bd[p] = bd[p - 1]; bf[p] = bf[p - 1]; p--;
                }
                bd[p] = d; bf[p] = F[i];
            }
        }

        int fraud = 0;
        for (int k = 0; k < 5; k++) if (bf[k]) fraud++;
        s.fraudCount = fraud;
    }
}