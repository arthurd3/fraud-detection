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

    // ─────────────────────────── Onda 7 v2 (EXACT KD-tree) ───────────────────────────

    /**
     * Squared L2 in i16 units with a LONG accumulator. int32 overflows here:
     * (Δ ≤ 20000)² · 14 ≈ 5.6e9 &gt; 2^31. The KD-tree's internal i16 kernel may
     * keep int32 (contest sum &lt; 2^31 by the dims-5/6-only-sentinel invariant),
     * but this standalone helper used by tests/brute MUST be long.
     *
     * @param q permuted-or-semantic i16 query (first 14 lanes used)
     * @param v permuted-or-semantic i16 ref   (first 14 lanes used; same order as q)
     */
    public static long sqDistI16(short[] q, short[] v) {
        long acc = 0;
        for (int i = 0; i < 14; i++) {
            long d = q[i] - v[i];
            acc += d * d;
        }
        return acc;
    }

    /** C round4: round(x*10000)/10000, round-half-away-from-zero == Math.round for the
     *  contest's non-negative dims and exact for the -1 sentinel
     *  (Math.round(-10000.0) = -10000). */
    public static double round4(double x) {
        return Math.round(x * 10000.0) / 10000.0;
    }

    /**
     * IDENTICAL to main.c {@code euclidean_dist(vec, refs[i].v)}:
     * Σ over SEMANTIC dims 0..13 of {@code (a-b)²} in double, where
     * {@code a = round4(query_semantic[i])} and {@code b = refI16Semantic[i] / 10000.0}.
     *
     * <p>C's {@code vec[]} and {@code refs[].v[]} are both {@code round4(orig)} =
     * {@code round(orig*10000.0)/10000.0}, i.e. exactly {@code k/10000.0} for integer
     * k. Our ref i16 store is that integer k (lossless); {@code round4((double)queryFloat)}
     * recovers the query's k (queryVector is already round4'd, float→double→round4 is
     * idempotent and the *0.5e-3 error is far below 0.5). Both sides are therefore the
     * exact same {@code (double)long / 10000.0} as C, and the per-term double rounding /
     * accumulation order (semantic 0..13) match C's loop bit-for-bit.
     *
     * <p>Kept for tests / oracles (RecallHnsw, Rbh2Equiv, ExactAgree bruteDouble). The
     * production rerank path uses the H1 overload {@link #sqDistDoubleLikeC(double[], short[])}
     * which hoists the per-call round4 out to the caller (pre-computed once per query).
     *
     * @param querySemantic14 the parsed query in SEMANTIC order (round4'd floats)
     * @param refI16Semantic   the candidate ref's i16 features in SEMANTIC order
     */
    public static double sqDistDoubleLikeC(float[] querySemantic14, short[] refI16Semantic) {
        double sum = 0.0;
        for (int i = 0; i < 14; i++) {
            double a = round4((double) querySemantic14[i]);
            double b = refI16Semantic[i] / 10000.0;
            double d = a - b;
            sum += d * d;
        }
        return sum;
    }

    /**
     * H1 (2026-05-21) — same kernel as
     * {@link #sqDistDoubleLikeC(float[], short[])} but with {@code round4(query)}
     * already done by the caller (pre-computed once per query in
     * {@link org.fraudDetection.knn.KdScratch#queryRound4}). Removes 14
     * {@code Math.round} + 14 mults + 14 divs per call (~3 ops/lane × 14 ≈ 37 % of
     * the ~112 fp ops of the inner kernel; HotSpot pre-H1 ≈ 29 ns/call).
     *
     * <p>Bit-identical to the legacy overload for queries where the caller passes
     * {@code queryRound4[i] = Math.round((double) queryFloat * 10000.0) / 10000.0}.
     * Soundness: the caller's pre-compute is the SAME expression as the legacy
     * per-call line; floating-point determinism guarantees the same {@code double}
     * value, so {@code a − b} and {@code d * d} are byte-identical. Proven over
     * 54 100 queries by {@code ExactAgree} (E = 0).
     */
    public static double sqDistDoubleLikeC(double[] queryRound4_14, short[] refI16Semantic) {
        double sum = 0.0;
        for (int i = 0; i < 14; i++) {
            double a = queryRound4_14[i];
            double b = refI16Semantic[i] / 10000.0;
            double d = a - b;
            sum += d * d;
        }
        return sum;
    }
}
