package org.fraudDetection.knn;

public final class Quantizer {

    private Quantizer() {}

    static float clamp(float v) { return v < -1f ? -1f : (v > 1f ? 1f : v); }

    public static byte q(float v) {
        return (byte) Math.round(clamp(v) * 127f);
    }

    public static void quantize(float[] src, byte[] dst) {
        for (int i = 0; i < 14; i++) dst[i] = q(src[i]);
    }
}