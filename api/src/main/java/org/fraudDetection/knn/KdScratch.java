package org.fraudDetection.knn;

/**
 * Single-threaded mutable scratch for one {@link KdTree} query (NIO reactor:
 * exactly one request at a time). Ported from jvmoonshot KdTreeScratch, minus
 * the ThreadLocal and the profiling fields; EXACT-MODE only.
 *
 * <p>All buffers are preallocated once (here) so {@link KdTree#search} is
 * zero-alloc steady-state. Sizing keeps the BBF heap/pool L1D-resident
 * (256 heap + 256×14 pool ≈ 18 KB) exactly as in jvmoonshot.
 */
final class KdScratch {

    final KdTopK results = new KdTopK();
    final int[] slab = new int[KdLayout.DIMS];
    /** Permuted query in i16 units; lanes 14..19 stay zero. */
    final short[] permutedQueryI16 = new short[KdLayout.STRIDE];

    // Onda 11 removed fanOutBuf/fanOutCount: the beam-of-2 prime tracks state in
    // local variables (best far child + delta²), so no per-query staging buffer
    // is needed. Saves 128 B per scratch and one less field to clear.
    int visits;
    int vPrime, vBBF, vDescend; // Onda 9: breakdown de visits (prime / BBF / descend-fallback)

    // Onda 11 Phase B v2 (2026-05-20): grow caps 256→1024 to support
    // BBF_MAX_DEPTH 18→22, which routes the ~167 deep visits (formerly the
    // recursive `descend` fallback) through the BBF best-first heap. Caps were
    // sized for depth ≤18 (max observed 165 heap / 96 pool); pushing depths
    // 19..22 onto the heap raises peak occupancy, so caps must grow.
    //
    // Memory: heap 4×int×1024 = 16 KB (was 4 KB); pool 1024×DIMS×int = 56 KB
    // (was 14 KB). Total scratch ≈ 80 KB (was 30 KB) — still well within L2.
    //
    // Onda 9 finding (now superseded for the caps but kept for context): the
    // 256 caps were never hit because the depth gate at BBF_MAX_DEPTH=18 cut
    // first; Phase B v2 lifts that gate.
    static final int BBF_HEAP_CAP = 1024;
    static final int BBF_POOL_CAP = 1024;
    final int[] bbfTreeIdx = new int[BBF_HEAP_CAP];
    final int[] bbfSlabSum = new int[BBF_HEAP_CAP];
    final int[] bbfDepth = new int[BBF_HEAP_CAP];
    final int[] bbfSlabIdx = new int[BBF_HEAP_CAP];
    final int[] bbfSlabPool = new int[BBF_POOL_CAP * KdLayout.DIMS];
    int bbfSize;
    int bbfSlabNext;

    /**
     * Candidate pool for the EXACT double rerank: every node whose i16 squared
     * sum was ≤ the running TopK-5th sum (or while TopK not yet full). Proven
     * superset of C's double-distance top-5 (the i16-5th double bound argument).
     * Capacity is generous; overflow is impossible in practice for n=3M (tracked
     * by {@link #maxPool} for the gate) but guarded defensively.
     */
    static final int POOL_CAP = 1 << 16;
    final int[] poolTreeIdx = new int[POOL_CAP];
    final int[] poolOrig = new int[POOL_CAP]; // parallel: origId of poolTreeIdx[i]
    int poolSize;

    // Exact double rerank scratch (preallocated → zero-alloc).
    final short[] refSemantic = new short[KdLayout.DIMS]; // one candidate, semantic order
    final double[] rrDist = new double[KdTopK.MAX_K];      // C dists[KNN_K]
    final int[] rrIdx = new int[KdTopK.MAX_K];             // C idxs[KNN_K] (origId)
    boolean[] rrFraud = new boolean[KdTopK.MAX_K];          // fraud flag per final slot

    // Watermarks (for ExactAgree gate: heap/pool stay within caps).
    int maxHeap;
    int maxPool;

    void resetWatermarks() { maxHeap = 0; maxPool = 0; }

    // ── Onda 8 instrumentation (used ONLY when KdTree.INSTR; lazily allocated;
    //    zero state/cost in production where INSTR stays false). Records the
    //    treeIdx touched per query so the offline replay can measure the
    //    HW-independent memory-locality predictor (distinct 4 KB pages / 64 B
    //    cache lines of `pts` per query) — the metric the vEB/BFS relayout
    //    targets (s.visits is unchanged by a relayout; locality is not). ──────
    int[] accessLog;     // treeIdx per visited node (cap; truncates if exceeded)
    int   accessCount;
    boolean accessTrunc;
    int[] pageGen;       // generation-stamp per 4 KB page of pts
    int[] lineGen;       // generation-stamp per 64 B line of pts
    int   pageStamp;
    int   lineStamp;
}
