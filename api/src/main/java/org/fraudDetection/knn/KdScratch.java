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
    /**
     * H1 (2026-05-21) — query in SEMANTIC order, already {@code round4}'d in double,
     * pre-computed ONCE per query at {@link KdTree#prepareSearch}. The legacy
     * {@code sqDistDoubleLikeC(float[], short[])} re-did {@code Math.round(q*1e4)/1e4}
     * INSIDE the per-candidate loop (14 rounds × ~35 candidates ≈ 490 rounds/query);
     * H1 hoists that to 14 rounds/query. Bit-identity holds because
     * {@code ConnectionState.queryVector} is already round4'd at parse time
     * ({@code FraudRequestParser#r4}) and {@code float→double→round4} is idempotent
     * (Onda 7 v2 argument). Used by the new {@code sqDistDoubleLikeC(double[], short[])}
     * overload via {@link KdTree#search}.
     */
    final double[] queryRound4 = new double[KdLayout.DIMS];

    // Onda 11 removed fanOutBuf/fanOutCount: the beam-of-2 prime tracks state in
    // local variables (best far child + delta²), so no per-query staging buffer
    // is needed. Saves 128 B per scratch and one less field to clear.
    int visits;
    int vPrime, vBBF, vDescend; // Onda 9: breakdown de visits (prime / BBF / descend-fallback)

    // Onda 22 Fase 1 — rerank instrumentation. Unconditional ints (mesmo padrão de
    // {@code visits}/{@code vBBF}): custo zero em produção e nenhum risco de alloc.
    //   rerankCandidates  = iterações do for em {@link KdTree#search} (cobre dedup-skip + H2-skip)
    //   rerankDoubleCalls = chamadas a {@code sqDistDoubleLikeC} (pós-dedup, pós-H2)
    //   rerankInsertions  = vezes que o ramo {@code d < dists[j]} foi tomado
    //   rerankH2Skipped   = (Onda 22 H2) candidatos podados por i16-bound antes do double kernel
    //   peekSumFinalI16   = {@code results.peekSum()} pós-descendBBF (bound i16 final)
    int rerankCandidates, rerankDoubleCalls, rerankInsertions, rerankH2Skipped;
    int peekSumFinalI16;
    /**
     * H2 (2026-05-21) — parallel array storing the i16 squared sum at admission for
     * each pool entry (written in {@link KdTree#poolRecord} before the {@code poolSize++}).
     * UNCONDITIONAL in production: {@link KdTree#search}'s rerank loop reads it to skip
     * candidates whose {@code i16Sum > peekSumFinalI16} BEFORE calling the expensive
     * double kernel ({@code sqDistDoubleLikeC}).
     *
     * <p><b>Soundness</b> (the i16-5th double bound argument, documented in
     * {@link KdTree#poolRecord}): any node with {@code i16Sum > final-5th's i16Sum}
     * has {@code doubleSum > final-5th's doubleSum} by ≥ ~1e-8 ({@code 1/1e8} integer-gap
     * dominates the ~1e-14 round-off slack), so it cannot displace the exact double
     * top-5. Empirically proven by {@code ExactAgree} 0 / 54 100 mismatches.
     *
     * <p><b>Footprint</b>: {@code int[POOL_CAP] = 4 KB} per scratch (Onda 22 K2,
     * 2026-05-22; was 256 KB pre-shrink — see {@link #POOL_CAP} doc). One instance
     * per JVM (single-threaded NIO reactor). Only the first {@code poolSize} entries
     * (mean 40, max 76 observed Onda 22 Fase 1, re-confirmed 76/cap-1024 pre-K2)
     * are written/read per query, so the warm working set is L1-resident.
     */
    final int[] poolI16Sum = new int[POOL_CAP];

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
     * Overflow is impossible in practice for n=3M (tracked by {@link #maxPool}
     * for the {@code ExactAgree} gate) but guarded defensively in
     * {@code KdTree#poolRecord}.
     *
     * <p>Onda 22 K2 (2026-05-22): cap shrunk {@code 1<<16} → {@code 1<<10} = 1024
     * (13× headroom over observed max 76 across 54.100 entries, ExactAgree
     * re-run pre-shrink confirmed). Drops the three parallel arrays
     * ({@link #poolI16Sum} / {@link #poolTreeIdx} / {@link #poolOrig})
     * from 3×256 = 768 KB down to 3×4 = 12 KB total — fits L1d on Haswell
     * (32 KB). Pre-K2 {@code poolI16Sum} alone (256 KB) coincided exactly with
     * the Haswell L2 (256 KiB), suspected of conflict miss in the hot rerank
     * loop (Mac Mini p99 35–40 ms cauda).
     */
    static final int POOL_CAP = 1 << 10;
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
