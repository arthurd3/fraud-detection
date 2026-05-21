package org.fraudDetection.knn;

import java.util.Arrays;
import java.util.Random;

/**
 * One-shot EXACT {@link KdTree} construction. Ported near-verbatim from
 * jvmoonshot KdTreeBuilder (sliding-midpoint splits, 3-way quickselect,
 * variance-descending lane permutation, {@code new Random(42L)} — seed 42
 * KEPT for determinism). Input is the semantic float layout (n*14 row-major,
 * NOT permuted) + a fraud[] flag; features are quantized to i16 via
 * {@link Quantizer#q16} at write time, split decisions use the original
 * float (semantic) values.
 */
public final class KdTreeBuilder {

    private KdTreeBuilder() {}

    /**
     * @param vecsSemantic n*14 row-major semantic floats (already round4'd, as
     *                     stored in references.json.gz)
     * @param fraud        n fraud flags (true = fraud)
     */
    public static KdTree build(int n, float[] vecsSemantic, boolean[] fraud) {
        final int DIMS = KdLayout.DIMS;
        final int STRIDE = KdLayout.STRIDE;
        int[] dimPerm = KdLayout.DIM_PERMUTATION;

        // Float source in PERMUTED order (split decisions), stride-20 so split
        // code reads it with the same stride as pts[].
        float[] srcVecsFloat = new float[n * STRIDE];
        short[] srcVecsShort = new short[n * STRIDE];
        for (int i = 0; i < n; i++) {
            int srcBase = i * DIMS;       // semantic, contiguous 14
            int dstBase = i * STRIDE;     // permuted, stride = KdLayout.STRIDE (19 in RKD6)
            for (int d = 0; d < DIMS; d++) {
                float v = vecsSemantic[srcBase + dimPerm[d]];
                srcVecsFloat[dstBase + d] = v;
                srcVecsShort[dstBase + d] = Quantizer.q16(v);
            }
        }

        int[] indices = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        short[] pts = new short[n * STRIDE];
        int[] origId = new int[n];

        Random rng = new Random(42L);
        int[] nextIdx = {0};
        int rootIdx = buildRecursive(srcVecsFloat, srcVecsShort, fraud, indices,
                0, n, 0, pts, origId, nextIdx, rng);
        if (rootIdx != 0) throw new IllegalStateException("root not at 0: " + rootIdx);
        if (nextIdx[0] != n) throw new IllegalStateException("node count mismatch");

        // ── Onda 8 Fase 2/2c: relayout DFS-global → blocked, pré-ordem intra-bloco ──
        // PERMUTAÇÃO PURA dos índices de nó: mesma árvore/splits/fraud/origId,
        // só muda treeIdx + ordem física. determinismo intacto (não toca
        // buildRecursive/Random(42L)). E=0 por construção; ExactAgree 0-div prova.
        // Bloco = topo de BLOCK_HEIGHT níveis contíguos em PRÉ-ORDEM DFS
        // (≤2^BH-1=63 nós ≈ ≤2520 B ≤ 1 página 4 KB) ⇒ caminho raiz→folha cruza
        // ~prof/BH blocos em vez de tocar páginas espalhadas pelos 120 MB.
        {
            short[] pts2 = new short[n * STRIDE];
            int[] origId2 = new int[n];
            relayoutBlocked(pts, origId, n, pts2, origId2);
            pts = pts2;
            origId = origId2;
        }

        int[] topSlot = new int[n];
        Arrays.fill(topSlot, -1);
        int maxTopNodes = 1 << (KdTree.TOP_BBOX_DEPTH + 1);
        short[] tmpBbox = new short[maxTopNodes * KdLayout.STRIDE_BBOX];
        int[] topNodeCountArr = {0};
        short[] rootOut = new short[DIMS * 2];
        computeTopBboxesRec(pts, 0, 0, topSlot, tmpBbox, topNodeCountArr, rootOut);

        int topNodeCount = topNodeCountArr[0];
        short[] topBbox = new short[topNodeCount * KdLayout.STRIDE_BBOX];
        System.arraycopy(tmpBbox, 0, topBbox, 0, topNodeCount * KdLayout.STRIDE_BBOX);

        return new KdTree(n, pts, origId, topSlot, topBbox, topNodeCount);
    }

    private static void computeTopBboxesRec(
            short[] pts, int treeIdx, int depth,
            int[] topSlot, short[] tmpBbox, int[] nextSlot, short[] out) {
        final int DIMS = KdLayout.DIMS;
        final int STRIDE = KdLayout.STRIDE;
        int ptsOffset = treeIdx * STRIDE;
        int leftAndDim = (pts[ptsOffset + KdLayout.LANE_LEFT_DIM] & 0xFFFF)
                | ((pts[ptsOffset + KdLayout.LANE_LEFT_DIM + 1] & 0xFFFF) << 16);
        int leftIdx = KdLayout.unpackLeft(leftAndDim);
        int rightIdx = (pts[ptsOffset + KdLayout.LANE_RIGHT] & 0xFFFF)
                | ((pts[ptsOffset + KdLayout.LANE_RIGHT + 1] & 0xFFFF) << 16);

        for (int d = 0; d < DIMS; d++) {
            short v = pts[ptsOffset + d];
            out[d] = v;
            out[DIMS + d] = v;
        }
        if (leftIdx >= 0) {
            short[] leftOut = new short[DIMS * 2];
            computeTopBboxesRec(pts, leftIdx, depth + 1, topSlot, tmpBbox, nextSlot, leftOut);
            for (int d = 0; d < DIMS; d++) {
                if (leftOut[d] < out[d]) out[d] = leftOut[d];
                if (leftOut[DIMS + d] > out[DIMS + d]) out[DIMS + d] = leftOut[DIMS + d];
            }
        }
        if (rightIdx >= 0) {
            short[] rightOut = new short[DIMS * 2];
            computeTopBboxesRec(pts, rightIdx, depth + 1, topSlot, tmpBbox, nextSlot, rightOut);
            for (int d = 0; d < DIMS; d++) {
                if (rightOut[d] < out[d]) out[d] = rightOut[d];
                if (rightOut[DIMS + d] > out[DIMS + d]) out[DIMS + d] = rightOut[DIMS + d];
            }
        }

        if (depth <= KdTree.TOP_BBOX_DEPTH) {
            int slot = nextSlot[0]++;
            topSlot[treeIdx] = slot;
            int base = slot * KdLayout.STRIDE_BBOX;
            System.arraycopy(out, 0, tmpBbox, base, DIMS);
            System.arraycopy(out, DIMS, tmpBbox, base + 16, DIMS);
        }
    }

    private static int buildRecursive(
            float[] srcVecsFloat, short[] srcVecsShort, boolean[] srcFraud,
            int[] indices, int from, int to, int depth,
            short[] pts, int[] origId, int[] nextIdx, Random rng) {
        final int DIMS = KdLayout.DIMS;
        final int STRIDE = KdLayout.STRIDE;
        if (from >= to) return -1;
        int splitDim = chooseSplitDim(srcVecsFloat, indices, from, to);
        int splitPos = slidingMidpointTarget(srcVecsFloat, indices, from, to, splitDim);
        quickselect(indices, from, to, splitPos, srcVecsFloat, splitDim, rng);

        int treeIdx = nextIdx[0]++;
        int srcId = indices[splitPos];

        System.arraycopy(srcVecsShort, srcId * STRIDE, pts, treeIdx * STRIDE, DIMS);
        origId[treeIdx] = srcId;
        short fraudBit = (short) (srcFraud[srcId] ? 1 : 0);
        pts[treeIdx * STRIDE + KdLayout.LANE_FRAUD] = fraudBit;

        int leftIdx = buildRecursive(srcVecsFloat, srcVecsShort, srcFraud, indices,
                from, splitPos, depth + 1, pts, origId, nextIdx, rng);
        int rightIdx = buildRecursive(srcVecsFloat, srcVecsShort, srcFraud, indices,
                splitPos + 1, to, depth + 1, pts, origId, nextIdx, rng);

        int packed = KdLayout.packLeftAndDim(leftIdx, splitDim);
        pts[treeIdx * STRIDE + KdLayout.LANE_LEFT_DIM] = (short) (packed & 0xFFFF);
        pts[treeIdx * STRIDE + KdLayout.LANE_LEFT_DIM + 1] = (short) ((packed >>> 16) & 0xFFFF);
        pts[treeIdx * STRIDE + KdLayout.LANE_RIGHT] = (short) (rightIdx & 0xFFFF);
        pts[treeIdx * STRIDE + KdLayout.LANE_RIGHT + 1] = (short) ((rightIdx >>> 16) & 0xFFFF);

        return treeIdx;
    }

    private static final int IMBALANCE_CAP_DENOM = 9;

    private static int slidingMidpointTarget(float[] srcVecs, int[] indices, int from, int to,
                                             int splitDim) {
        final int STRIDE = KdLayout.STRIDE;
        int sz = to - from;
        if (sz <= 2) return from + (sz >>> 1);
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            float v = srcVecs[indices[i] * STRIDE + splitDim];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (max == min) return from + (sz >>> 1);
        float mid = (min + max) * 0.5f;
        int below = 0;
        for (int i = from; i < to; i++) {
            if (srcVecs[indices[i] * STRIDE + splitDim] < mid) below++;
        }
        int minSide = Math.max(1, sz / IMBALANCE_CAP_DENOM);
        if (below < minSide) below = minSide;
        else if (below > sz - minSide) below = sz - minSide;
        return from + below;
    }

    private static int chooseSplitDim(float[] srcVecs, int[] indices, int from, int to) {
        final int DIMS = KdLayout.DIMS;
        final int STRIDE = KdLayout.STRIDE;
        int sz = to - from, step = Math.max(1, sz / 256);
        float[] mins = new float[DIMS], maxs = new float[DIMS];
        Arrays.fill(mins, Float.POSITIVE_INFINITY);
        Arrays.fill(maxs, Float.NEGATIVE_INFINITY);
        for (int i = from; i < to; i += step) {
            int off = indices[i] * STRIDE;
            for (int d = 0; d < DIMS; d++) {
                float v = srcVecs[off + d];
                if (v < mins[d]) mins[d] = v;
                if (v > maxs[d]) maxs[d] = v;
            }
        }
        int best = 0;
        float bestRange = -1f;
        for (int d = 0; d < DIMS; d++) {
            float r = maxs[d] - mins[d];
            if (r > bestRange) {
                bestRange = r;
                best = d;
            }
        }
        return best;
    }

    private static void quickselect(int[] indices, int from, int to, int target,
                                    float[] srcVecs, int splitDim, Random rng) {
        final int STRIDE = KdLayout.STRIDE;
        while (to - from > 1) {
            float pivot = srcVecs[indices[from + rng.nextInt(to - from)] * STRIDE + splitDim];
            int less = from, cur = from, greater = to;
            while (cur < greater) {
                float v = srcVecs[indices[cur] * STRIDE + splitDim];
                if (v < pivot) {
                    int t = indices[less];
                    indices[less++] = indices[cur];
                    indices[cur++] = t;
                } else if (v > pivot) {
                    int t = indices[--greater];
                    indices[greater] = indices[cur];
                    indices[cur] = t;
                } else {
                    cur++;
                }
            }
            if (target < less) to = less;
            else if (target >= greater) from = greater;
            else return;
        }
    }

    // ── Onda 8 Fase 2/2c: blocked relayout, pre-order intra-block (pure perm) ────────

    /**
     * Blocked-relayout block height: 6 ⇒ ≤2^6-1=63 nodes/block ≈ ≤2520 B
     * ≤ 1 page (4 KB). Fase-1 grid-search (BH 4..12, deterministic replay,
     * 2026-05-19, BFS-intra-block variant) found 6 optimal (distinctPages mean
     * BH6=31, 7=34, 5=38, 4/8=35, 12=49); Fase-2c switched intra-block order
     * BFS→pre-order DFS at BH=6 (pages 31→30, lines 354→311, E=0). Plain
     * constant, no runtime knob — simplicity wins, zero gain measured elsewhere.
     */
    static final int BLOCK_HEIGHT = 6;

    private static int leftAndDimOf(short[] pts, int t) {
        int b = t * KdLayout.STRIDE;
        return (pts[b + KdLayout.LANE_LEFT_DIM] & 0xFFFF)
                | ((pts[b + KdLayout.LANE_LEFT_DIM + 1] & 0xFFFF) << 16);
    }

    private static int rightOf(short[] pts, int t) {
        int b = t * KdLayout.STRIDE;
        return (pts[b + KdLayout.LANE_RIGHT] & 0xFFFF)
                | ((pts[b + KdLayout.LANE_RIGHT + 1] & 0xFFFF) << 16);
    }

    /**
     * Renumber nodes from global pre-order DFS into a BLOCKED layout: each
     * block is the top {@code BLOCK_HEIGHT} levels of a subtree laid out
     * contiguously in block-local PRE-ORDER DFS (Fase-2c; was BFS — pre-order
     * keeps the page win and improves cache-line locality); ≤2^BH children
     * new block (recursively, via an explicit work queue). Whole-tree root
     * (old idx 0) → new idx 0. Pure permutation: features/fraud/origId copied
     * verbatim, child links remapped through {@code newIdx} — tree, splits and
     * results are byte-identical (E=0 by construction; ExactAgree proves it).
     */
    private static void relayoutBlocked(short[] pts, int[] origId, int n,
                                        short[] pts2, int[] origId2) {
        final int STRIDE = KdLayout.STRIDE;
        final int DIMS = KdLayout.DIMS;
        int[] newIdx = new int[n];
        Arrays.fill(newIdx, -1);

        int[] blockQ = new int[n];          // block roots to process (old idx)
        int bqHead = 0, bqTail = 0;
        blockQ[bqTail++] = 0;               // whole-tree root (pre-order idx 0)

        int cap = 1 << BLOCK_HEIGHT;        // ≥ max nodes enqueued per block
        int[] lq = new int[cap];
        int[] ld = new int[cap];
        int next = 0;

        while (bqHead < bqTail) {
            int r = blockQ[bqHead++];
            int sp = 0;
            lq[sp] = r; ld[sp] = 0; sp++;
            while (sp > 0) {                       // pre-order DFS within block
                sp--;
                int node = lq[sp]; int dep = ld[sp];
                if (node < 0) continue;
                newIdx[node] = next++;
                int lad = leftAndDimOf(pts, node);
                int left = KdLayout.unpackLeft(lad);
                int right = rightOf(pts, node);
                if (dep + 1 < BLOCK_HEIGHT) {
                    // push right then left ⇒ left pops first (pre-order: node,
                    // left-subtree, right-subtree) ⇒ a root→leaf descent is
                    // contiguous within the block (better cache-line locality
                    // than BFS, same page win — both ≤1 page/block).
                    if (right >= 0) { lq[sp] = right; ld[sp] = dep + 1; sp++; }
                    if (left  >= 0) { lq[sp] = left;  ld[sp] = dep + 1; sp++; }
                } else {
                    if (left  >= 0) blockQ[bqTail++] = left;
                    if (right >= 0) blockQ[bqTail++] = right;
                }
            }
        }
        if (next != n) throw new IllegalStateException("relayout covered " + next + " != " + n);
        if (newIdx[0] != 0) throw new IllegalStateException("relayout root not at new 0");

        for (int old = 0; old < n; old++) {
            int ni = newIdx[old];
            int sb = old * STRIDE, db = ni * STRIDE;
            for (int d = 0; d < DIMS; d++) pts2[db + d] = pts[sb + d];
            pts2[db + KdLayout.LANE_FRAUD] = pts[sb + KdLayout.LANE_FRAUD];

            int lad = leftAndDimOf(pts, old);
            int oldLeft = KdLayout.unpackLeft(lad);
            int dim = KdLayout.unpackDim(lad);
            int newLeft = (oldLeft < 0) ? -1 : newIdx[oldLeft];
            int packed = KdLayout.packLeftAndDim(newLeft, dim);
            pts2[db + KdLayout.LANE_LEFT_DIM]     = (short) (packed & 0xFFFF);
            pts2[db + KdLayout.LANE_LEFT_DIM + 1] = (short) ((packed >>> 16) & 0xFFFF);

            int oldRight = rightOf(pts, old);
            int newRight = (oldRight < 0) ? -1 : newIdx[oldRight];
            pts2[db + KdLayout.LANE_RIGHT]     = (short) (newRight & 0xFFFF);
            pts2[db + KdLayout.LANE_RIGHT + 1] = (short) ((newRight >>> 16) & 0xFFFF);

            origId2[ni] = origId[old];
        }
    }
}
