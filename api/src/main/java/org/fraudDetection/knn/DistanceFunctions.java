package org.fraudDetection.knn;
import java.nio.MappedByteBuffer;

public final class DistanceFunctions {

    private DistanceFunctions() {}

    public static float sqDist(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < 14; i++) {
            float d = a[i] - b[i];
            s += d * d;
        }
        return s;
    }

    public static int sqDistI8(byte[] q, MappedByteBuffer V, int base) {
    int acc = 0;
    for (int k = 0; k < 14; k++) {
        int d = q[k] - V.get(base + k);
        acc += d * d;
    }
    return acc;
}
}