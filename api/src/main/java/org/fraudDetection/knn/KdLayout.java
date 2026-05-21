package org.fraudDetection.knn;

/**
 * Data-geometry constants and packed-nav encode/decode for the EXACT KD-tree
 * (Onda 7 v2). Ported near-verbatim from jvmoonshot KdTreeLayout, minus the
 * Vector API species (Java 21, scalar only). No mutable state, no env reads.
 *
 * <p>STRIDE=19 short slots per node: dims[0..13] + leftAndDim[14..15] +
 * right[16..17] + fraud[18]. RKD6 (Onda 21): pad lane 19 removed (was
 * unused; written by KdTreeBuilder but never read on the hot path). Saves
 * 2 B/node × 3 M = −6 MB of mmap pressure under the 159 MiB cgroup.
 */
public final class KdLayout {

    /** Number of semantic feature dimensions. */
    public static final int DIMS = 14;

    /**
     * Variance-descending lane order. permuted[d] = semantic[DIM_PERMUTATION[d]].
     * Identical to jvmoonshot DIM_PERMUTATION (the .kdt is built with it).
     */
    public static final int[] DIM_PERMUTATION = {6, 10, 9, 5, 11, 2, 4, 7, 0, 1, 3, 8, 12, 13};

    /**
     * Inverse permutation: INV_PERMUTATION[s] = d such that DIM_PERMUTATION[d] == s.
     * Used by the exact double rerank to read a node's features back in SEMANTIC
     * order from the permuted i16 store.
     */
    public static final int[] INV_PERMUTATION = new int[DIMS];
    static {
        for (int d = 0; d < DIMS; d++) INV_PERMUTATION[DIM_PERMUTATION[d]] = d;
    }

    /** short[] stride per node: 14 dims + leftAndDim(2) + right(2) + fraud(1).
     *  RKD6 (Onda 21): drop lane 19 (was unused PAD). STRIDE 20→19; −6 MB mmap. */
    public static final int STRIDE = 19;
    /** 16 lo shorts + 16 hi shorts per top-bbox node. */
    public static final int STRIDE_BBOX = 32;

    /** Lane carrying the packed (leftIdx | splitDim) nav int (two LE i16 halves). */
    public static final int LANE_LEFT_DIM = 14;
    /** Lane carrying the right child index (two LE i16 halves). */
    public static final int LANE_RIGHT = 16;
    /** Lane carrying the fraud flag (0 or 1) in the previously-unused padding short. */
    public static final int LANE_FRAUD = 18;

    /** Packs a left-child index (28 bits, signed) and split dim (4 bits) into one int. */
    public static int packLeftAndDim(int left, int dim) {
        return (left & 0x0FFFFFFF) | ((dim & 0xF) << 28);
    }

    /** Sign-extends the 28-bit signed left index, preserving the -1 absent-child sentinel. */
    public static int unpackLeft(int leftAndDim) {
        return (leftAndDim << 4) >> 4;
    }

    public static int unpackDim(int leftAndDim) {
        return (leftAndDim >>> 28) & 0xF;
    }

    private KdLayout() {}
}
