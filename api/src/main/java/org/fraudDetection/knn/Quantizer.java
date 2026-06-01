package org.fraudDetection.knn;

public final class Quantizer {

    private Quantizer() {}

    static float clamp(float v) { return v < -1f ? -1f : (v > 1f ? 1f : v); }

    /**
     * Onda 7 v2 — i16 quantization for the EXACT KD-tree:
     * {@code round(clamp(v) * 10000)}. Lossless for the contest's 4-decimal
     * floats (references.json.gz values are exact multiples of 1e-4). The
     * EXACT decision is settled by the double rerank, so this only governs
     * which candidates are collected; rounding-half-up is used consistently
     * for query and refs so the i16 metric is self-consistent.
     */
    public static short q16(float v) {
        return (short) Math.round(clamp(v) * 10000f);
    }
}