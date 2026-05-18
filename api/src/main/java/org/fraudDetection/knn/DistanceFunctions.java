package org.fraudDetection.knn;

// Onda 5 (2026-05-18): o Vector API (jdk.incubator.vector) foi REMOVIDO daqui.
// Era código morto desde a Onda 2b (SIMD medido 3,8x mais lento que o escalar
// → produção sempre usou sqDistI8Scalar; sqDistI8 SIMD tinha 0 callers). Sob
// GraalVM Native Image (Oracle 21.0.11) os campos static VectorSpecies puxavam
// VectorSupport.getMaxLaneCount → falha de LINK (undefined reference). Remover
// o dead code desbloqueia o build nativo sem mudar o comportamento de produção.
public final class DistanceFunctions {

    private DistanceFunctions() {}

    public static float sqDist(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < 14; i++) { float d = a[i] - b[i]; s += d * d; }
        return s;
    }

    public static int sqDistI8Scalar(byte[] q, byte[] v) {
        int acc = 0;
        for (int k = 0; k < 16; k++) { int d = q[k] - v[k]; acc += d * d; }
        return acc;
    }
}
