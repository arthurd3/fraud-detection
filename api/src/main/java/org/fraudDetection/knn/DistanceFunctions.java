package org.fraudDetection.knn;

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
}