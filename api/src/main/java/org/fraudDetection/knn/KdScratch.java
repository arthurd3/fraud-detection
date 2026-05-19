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

    final int[] fanOutBuf = new int[KdTree.PRIME_FANOUT_COUNT];
    int fanOutCount;
    int visits;

    static final int BBF_HEAP_CAP = 256;
    static final int BBF_POOL_CAP = 256;
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
}
