package org.fraudDetection.knn;

import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.file.Path;

/**
 * EXACT k-NN via balanced KD-tree + branch-and-bound (BBF), ported near-verbatim
 * from jvmoonshot {@code search.KdTree} (Onda 7 v2). Java 21: NO Vector API, NO
 * FFM/Panama, NO Unsafe — heap mode uses {@code short[] pts}; mmap mode uses
 * {@link MappedByteBuffer} absolute little-endian reads (mirrors
 * {@link org.fraudDetection.dataset.MmapDataset}).
 *
 * <p><b>EXACT MODE ONLY.</b> All tuning/epsilon/MAX_VISITS/relax is dropped:
 * {@link #thresholdSum} returns {@code peekSum} (no relaxation), there is no
 * visit cap, pruning is sound (slab + bbox lower bounds only). The i16 KD-tree
 * thus yields the EXACT i16 top-5; {@link #search} then collects the proven
 * candidate superset and runs an EXACT DOUBLE RERANK that replicates
 * main.c {@code knn_classify} bit-for-bit (origId tie-break included).
 */
public final class KdTree {

    public static final int DIMS = KdLayout.DIMS;       // 14
    public static final int STRIDE = KdLayout.STRIDE;   // 20
    public static final int STRIDE_BBOX = KdLayout.STRIDE_BBOX; // 32

    static final int LANE_LEFT_DIM = KdLayout.LANE_LEFT_DIM; // 14
    static final int LANE_RIGHT = KdLayout.LANE_RIGHT;       // 16
    static final int LANE_FRAUD = KdLayout.LANE_FRAUD;       // 18

    public static final int TOP_BBOX_DEPTH = 18;
    public static final int BBF_MAX_DEPTH = 18;
    public static final int PRIME_FANOUT_DEPTH = 5;
    public static final int PRIME_FANOUT_COUNT = 1 << PRIME_FANOUT_DEPTH; // 32
    public static final int PRIME_PLUNGE_CAP = 4;
    public static final int BBF_HEAP_CAP = KdScratch.BBF_HEAP_CAP; // 256
    public static final int BBF_POOL_CAP = KdScratch.BBF_POOL_CAP; // 256

    final int n;

    // Heap mode (non-null in heap mode).
    final short[] pts;
    final int[] origId;
    // Mmap mode (non-null in mmap mode). LE-ordered.
    final MappedByteBuffer ptsBuf;
    final MappedByteBuffer origBuf;

    final int[] topSlot;
    final short[] topBbox;
    final int topNodeCount;

    /** Owned scratch for the single-threaded NIO hot path. */
    private final KdScratch scratch = new KdScratch();

    // ── Static singleton facade (mirrors the old HnswIndex static API) ───────────────

    /** One instance per JVM (single-threaded NIO reactor), like the legacy index. */
    public static KdTree INSTANCE;

    /**
     * Onda 8 instrumentation switch — {@code static final} from system property
     * {@code -Dfd.instr=true}. Absent (production / submission native image):
     * folds to {@code false} at GraalVM build-time static-init ⇒ the
     * {@code if (INSTR)} sites are dead-code-eliminated → ZERO cost, ZERO
     * behavior change (G2 stays 0-div). Set only by offline replays
     * (HotSpot, {@code java -Dfd.instr=true}) to read the HW-independent
     * memory-locality predictor via {@link #lastDistinctPages()}.
     */
    public static final boolean INSTR = Boolean.getBoolean("fd.instr");

    /**
     * Production load: mmap {@code references.kdt} (off-heap pts/origId) +
     * best-effort hints/prewarm. {@code Main} calls this instead of the legacy
     * MmapDataset/HnswIndex load.
     */
    public static void load(String kdtPath) throws IOException {
        INSTANCE = KdTreeIO.loadMmap(Path.of(kdtPath));
        INSTANCE.applyMmapHints();
        System.out.println("kdtree (RKD4, EXACT) mmap: " + INSTANCE.size() + " nós");
    }

    /** Heap load (tests/brute): on-heap arrays. */
    public static void loadHeap(String kdtPath) throws IOException {
        INSTANCE = KdTreeIO.load(Path.of(kdtPath));
        System.out.println("kdtree (RKD4, EXACT) heap: " + INSTANCE.size() + " nós");
    }

    /** Static facade used by FraudController (replaces HnswIndex.search). */
    public static void searchStatic(ConnectionState s) { INSTANCE.search(s); }

    KdTree(int n, short[] pts, int[] origId, int[] topSlot, short[] topBbox, int topNodeCount) {
        this.n = n;
        this.pts = pts;
        this.origId = origId;
        this.ptsBuf = null;
        this.origBuf = null;
        this.topSlot = topSlot;
        this.topBbox = topBbox;
        this.topNodeCount = topNodeCount;
    }

    KdTree(int n, MappedByteBuffer ptsBuf, MappedByteBuffer origBuf,
           int[] topSlot, short[] topBbox, int topNodeCount) {
        this.n = n;
        this.pts = null;
        this.origId = null;
        this.ptsBuf = ptsBuf;
        this.origBuf = origBuf;
        this.topSlot = topSlot;
        this.topBbox = topBbox;
        this.topNodeCount = topNodeCount;
    }

    public int size() { return n; }

    /** Best-effort mmap hints + page prewarm at boot (NO-OP madvise on Java 21). */
    public void applyMmapHints() {
        if (ptsBuf != null) {
            KdMmap.madviseBestEffort(ptsBuf, 0);
            KdMmap.prewarm(ptsBuf);
        }
        if (origBuf != null) KdMmap.prewarm(origBuf);
    }

    // Gate accessors (ExactAgree).
    public int lastMaxHeap() { return scratch.maxHeap; }
    public int lastMaxPool() { return scratch.maxPool; }
    public int bbfHeapCap()  { return KdScratch.BBF_HEAP_CAP; }
    public int bbfPoolCap()  { return KdScratch.BBF_POOL_CAP; }

    // Onda 8 instrumentation accessors (valid after a search() with INSTR=true).
    public int lastVisits()          { return scratch.visits; }
    public int lastDistinctPages()   { return distinctUnits(scratch, 4096); }
    public int lastDistinctLines()   { return distinctUnits(scratch, 64); }
    public boolean lastAccessTrunc() { return scratch.accessTrunc; }

    /** Distinct {@code unitBytes}-aligned units of {@code pts} touched this query
     *  (a 40 B node may straddle one boundary → start+end unit both counted). */
    private int distinctUnits(KdScratch s, int unitBytes) {
        if (s.accessLog == null) return 0;
        int[] gen = (unitBytes == 4096) ? s.pageGen : s.lineGen;
        int stamp = (unitBytes == 4096) ? (++s.pageStamp) : (++s.lineStamp);
        final int strideBytes = STRIDE * 2;
        int cnt = 0, m = s.accessCount;
        int[] log = s.accessLog;
        for (int i = 0; i < m; i++) {
            long b0 = (long) log[i] * strideBytes;
            int u0 = (int) (b0 / unitBytes);
            int u1 = (int) ((b0 + strideBytes - 1) / unitBytes);
            if (gen[u0] != stamp) { gen[u0] = stamp; cnt++; }
            if (u1 != u0 && gen[u1] != stamp) { gen[u1] = stamp; cnt++; }
        }
        return cnt;
    }

    // ── Nav / feature access (heap or mmap) ──────────────────────────────────────────

    /** i16 feature value at permuted lane {@code d} of the node at {@code treeIdx}. */
    public short ptI16(int treeIdx, int d) {
        if (pts != null) return pts[treeIdx * STRIDE + d];
        return ptsBuf.getShort((treeIdx * STRIDE + d) * 2);
    }

    private int leftAndDimAt(int treeIdx) {
        int base = treeIdx * STRIDE;
        if (pts != null) {
            int off = base + LANE_LEFT_DIM;
            return (pts[off] & 0xFFFF) | ((pts[off + 1] & 0xFFFF) << 16);
        }
        return ptsBuf.getInt((base + LANE_LEFT_DIM) * 2);
    }

    private int rightAt(int treeIdx) {
        int base = treeIdx * STRIDE;
        if (pts != null) {
            int off = base + LANE_RIGHT;
            return (pts[off] & 0xFFFF) | ((pts[off + 1] & 0xFFFF) << 16);
        }
        return ptsBuf.getInt((base + LANE_RIGHT) * 2);
    }

    public int fraudBit(int treeIdx) {
        if (pts != null) return pts[treeIdx * STRIDE + LANE_FRAUD] & 1;
        return ptsBuf.getShort((treeIdx * STRIDE + LANE_FRAUD) * 2) & 1;
    }

    public int origIdAt(int treeIdx) {
        if (origId != null) return origId[treeIdx];
        return origBuf.getInt(treeIdx * 4);
    }

    private static int unpackLeft(int leftAndDim) { return KdLayout.unpackLeft(leftAndDim); }
    private static int unpackDim(int leftAndDim) { return KdLayout.unpackDim(leftAndDim); }

    // ── Distance kernel: scalar i16, int32 (contest sum < 2^31; see DistanceFunctions) ─

    private int distSumI16(KdScratch s, int treeIdx) {
        if (INSTR) {
            if (s.accessCount < s.accessLog.length) s.accessLog[s.accessCount++] = treeIdx;
            else s.accessTrunc = true;
        }
        short[] q = s.permutedQueryI16;
        int base = treeIdx * STRIDE;
        int sum = 0;
        if (pts != null) {
            for (int d = 0; d < DIMS; d++) {
                int diff = q[d] - pts[base + d];
                sum += diff * diff;
            }
        } else {
            int byteBase = base * 2;
            for (int d = 0; d < DIMS; d++) {
                int diff = q[d] - ptsBuf.getShort(byteBase + d * 2);
                sum += diff * diff;
            }
        }
        return sum;
    }

    private boolean bboxPrunesI16Sum(KdScratch s, int slot, int thresholdSum) {
        int base = slot * STRIDE_BBOX;
        short[] q = s.permutedQueryI16;
        short[] bb = topBbox;
        int partLo = 0;
        for (int d = 0; d < 8; d++) {
            int clamped = Math.max(bb[base + d] - q[d], Math.max(q[d] - bb[base + 16 + d], 0));
            partLo += clamped * clamped;
        }
        if (partLo >= thresholdSum) return true;
        int partHi = 0;
        for (int d = 8; d < DIMS; d++) {
            int clamped = Math.max(bb[base + d] - q[d], Math.max(q[d] - bb[base + 16 + d], 0));
            partHi += clamped * clamped;
        }
        return partLo + partHi >= thresholdSum;
    }

    /** EXACT mode: no relaxation, threshold == current 5th i16 sum. */
    private static int thresholdSum(int peekSum) { return peekSum; }

    // ── Candidate-pool collection (proven superset of C's double top-5) ──────────────

    /**
     * Record every evaluated node whose i16 sum was ≤ the running TopK-5th sum
     * (or while TopK not yet full). The final 5th sum is the minimum over the
     * query, so any node with i16Sum ≤ final-5th had i16Sum ≤ every earlier 5th
     * → it is recorded here at its evaluation. Any node with i16Sum &gt; final-5th
     * (incl. all pruned subtrees) has double-dist strictly &gt; the i16-5th's
     * double-dist by ≥ ~1e-8 (the 1/1e8 integer-gap argument), so it can never
     * enter C's double top-5. Hence pool ⊇ C's exact top-5.
     */
    private void poolRecord(KdScratch s, int treeIdx, int dist) {
        if (s.results.size() < KdTopK.MAX_K || dist <= s.results.peekSum()) {
            if (s.poolSize < KdScratch.POOL_CAP) {
                s.poolTreeIdx[s.poolSize] = treeIdx;
                s.poolOrig[s.poolSize] = origIdAt(treeIdx);
                s.poolSize++;
                if (s.poolSize > s.maxPool) s.maxPool = s.poolSize;
            }
        }
    }

    private void considerNode(KdScratch s, int treeIdx, int dist, boolean full, int k) {
        if (!full) {
            if (!s.results.contains(treeIdx)) s.results.push(treeIdx, dist);
        } else if (dist < s.results.peekSum() && !s.results.contains(treeIdx)) {
            s.results.replaceFarthest(treeIdx, dist);
        }
        poolRecord(s, treeIdx, dist);
    }

    // ── Search preparation ───────────────────────────────────────────────────────────

    private void prepareSearch(float[] querySemantic, KdScratch s) {
        s.results.clear();
        int[] slab = s.slab;
        for (int d = 0; d < DIMS; d++) slab[d] = 0;
        s.visits = 0;
        s.bbfSize = 0;
        s.bbfSlabNext = 0;
        s.poolSize = 0;
        short[] pqi = s.permutedQueryI16;
        int[] perm = KdLayout.DIM_PERMUTATION;
        for (int d = 0; d < DIMS; d++) pqi[d] = Quantizer.q16(querySemantic[perm[d]]);
        for (int d = DIMS; d < STRIDE; d++) pqi[d] = 0;

        if (INSTR) {
            if (s.accessLog == null) {
                s.accessLog = new int[1 << 20];
                long bytes = (long) n * STRIDE * 2L;
                s.pageGen = new int[(int) (bytes / 4096) + 2];
                s.lineGen = new int[(int) (bytes / 64) + 2];
            }
            s.accessCount = 0;
            s.accessTrunc = false;
        }
    }

    // ── Prime (fan-out + plunge to pre-fill top-K) ───────────────────────────────────

    private void prime(KdScratch s, int k) {
        s.fanOutCount = 0;
        primeRecurse(0, s, k, 0);
        primeSelectAndPlunge(s, k);
    }

    private void primeRecurse(int treeIdx, KdScratch s, int k, int depth) {
        if (treeIdx < 0) return;
        s.visits++;
        int dist = distSumI16(s, treeIdx);
        considerNode(s, treeIdx, dist, s.results.size() >= k, k);
        int leftAndDim = leftAndDimAt(treeIdx);
        int leftIdx = unpackLeft(leftAndDim);
        int rightIdx = rightAt(treeIdx);
        if (depth < PRIME_FANOUT_DEPTH) {
            primeRecurse(leftIdx, s, k, depth + 1);
            primeRecurse(rightIdx, s, k, depth + 1);
        } else {
            int splitDim = unpackDim(leftAndDim);
            int delta = s.permutedQueryI16[splitDim] - ptI16(treeIdx, splitDim);
            int near = (delta < 0) ? leftIdx : rightIdx;
            if (near >= 0 && s.fanOutCount < PRIME_FANOUT_COUNT) {
                s.fanOutBuf[s.fanOutCount++] = near;
            }
        }
    }

    private void primeSelectAndPlunge(KdScratch s, int k) {
        int count = s.fanOutCount;
        if (count == 0) return;
        int[] buf = s.fanOutBuf;
        int m = Math.min(count, PRIME_PLUNGE_CAP);
        for (int i = 0; i < m; i++) plunge(buf[i], s, k);
    }

    private void plunge(int treeIdx, KdScratch s, int k) {
        while (treeIdx >= 0) {
            s.visits++;
            int dist = distSumI16(s, treeIdx);
            considerNode(s, treeIdx, dist, s.results.size() >= k, k);
            int leftAndDim = leftAndDimAt(treeIdx);
            int splitDim = unpackDim(leftAndDim);
            int leftIdx = unpackLeft(leftAndDim);
            int rightIdx = rightAt(treeIdx);
            int delta = s.permutedQueryI16[splitDim] - ptI16(treeIdx, splitDim);
            treeIdx = (delta < 0) ? leftIdx : rightIdx;
        }
    }

    // ── Descend — classical DFS fallback for deep FAR nodes ──────────────────────────

    private void descend(int treeIdx, KdScratch s, int k, int slabSum, int depth) {
        if (treeIdx < 0) return;
        if (s.results.size() >= k) {
            int threshSum = thresholdSum(s.results.peekSum());
            if (slabSum > threshSum) return;
            if (depth <= TOP_BBOX_DEPTH) {
                int slot = topSlot[treeIdx];
                if (slot >= 0 && bboxPrunesI16Sum(s, slot, threshSum)) return;
            }
        }
        s.visits++;
        int dist = distSumI16(s, treeIdx);
        considerNode(s, treeIdx, dist, s.results.size() >= k, k);
        int leftAndDim = leftAndDimAt(treeIdx);
        int splitDim = unpackDim(leftAndDim);
        int leftIdx = unpackLeft(leftAndDim);
        int rightIdx = rightAt(treeIdx);
        int delta = s.permutedQueryI16[splitDim] - ptI16(treeIdx, splitDim);
        int near, far;
        if (delta < 0) { near = leftIdx; far = rightIdx; }
        else { near = rightIdx; far = leftIdx; }

        descend(near, s, k, slabSum, depth + 1);

        int[] slab = s.slab;
        int oldSlabD = slab[splitDim];
        int newSlabD = delta * delta;
        int newSlabSum = slabSum - oldSlabD + newSlabD;
        if (s.results.size() < k || newSlabSum <= thresholdSum(s.results.peekSum())) {
            slab[splitDim] = newSlabD;
            descend(far, s, k, newSlabSum, depth + 1);
            slab[splitDim] = oldSlabD;
        }
    }

    // ── Best-first BBF ───────────────────────────────────────────────────────────────

    private void descendBBF(KdScratch s, int k) {
        continueDfsBBF(0, 0, 0, s, k);
        int[] hTreeIdx = s.bbfTreeIdx;
        int[] hSlabSum = s.bbfSlabSum;
        int[] hDepth = s.bbfDepth;
        int[] hSlabIdx = s.bbfSlabIdx;
        int[] pool = s.bbfSlabPool;
        while (s.bbfSize > 0) {
            int popTi = hTreeIdx[0];
            int popSs = hSlabSum[0];
            int popDe = hDepth[0];
            int popSi = hSlabIdx[0];
            int lastIdx = --s.bbfSize;
            if (lastIdx > 0) {
                int ti = hTreeIdx[lastIdx];
                int ss = hSlabSum[lastIdx];
                int de = hDepth[lastIdx];
                int si = hSlabIdx[lastIdx];
                int i = 0, half = lastIdx >>> 1;
                while (i < half) {
                    int child = (i << 1) + 1;
                    int right = child + 1;
                    if (right < lastIdx && hSlabSum[right] < hSlabSum[child]) child = right;
                    if (ss <= hSlabSum[child]) break;
                    hTreeIdx[i] = hTreeIdx[child];
                    hSlabSum[i] = hSlabSum[child];
                    hDepth[i] = hDepth[child];
                    hSlabIdx[i] = hSlabIdx[child];
                    i = child;
                }
                hTreeIdx[i] = ti;
                hSlabSum[i] = ss;
                hDepth[i] = de;
                hSlabIdx[i] = si;
            }
            if (s.results.size() >= k && popSs > thresholdSum(s.results.peekSum())) continue;
            System.arraycopy(pool, popSi * DIMS, s.slab, 0, DIMS);
            continueDfsBBF(popTi, popSs, popDe, s, k);
        }
    }

    private void continueDfsBBF(int treeIdx, int slabSum, int depth, KdScratch s, int k) {
        int[] slab = s.slab;
        int[] hTreeIdx = s.bbfTreeIdx;
        int[] hSlabSum = s.bbfSlabSum;
        int[] hDepth = s.bbfDepth;
        int[] hSlabIdx = s.bbfSlabIdx;
        int[] pool = s.bbfSlabPool;
        while (treeIdx >= 0) {
            boolean full = s.results.size() >= k;
            int thresholdSum = full ? thresholdSum(s.results.peekSum()) : Integer.MAX_VALUE;
            if (full) {
                if (slabSum > thresholdSum) return;
                if (depth <= TOP_BBOX_DEPTH) {
                    int slot = topSlot[treeIdx];
                    if (slot >= 0 && bboxPrunesI16Sum(s, slot, thresholdSum)) return;
                }
            }
            s.visits++;
            int dist = distSumI16(s, treeIdx);
            if (!full) {
                if (!s.results.contains(treeIdx)) {
                    s.results.push(treeIdx, dist);
                    full = s.results.size() >= k;
                    if (full) thresholdSum = thresholdSum(s.results.peekSum());
                }
                poolRecord(s, treeIdx, dist);
            } else if (dist < s.results.peekSum() && !s.results.contains(treeIdx)) {
                s.results.replaceFarthest(treeIdx, dist);
                thresholdSum = thresholdSum(s.results.peekSum());
                poolRecord(s, treeIdx, dist);
            } else {
                poolRecord(s, treeIdx, dist);
            }
            int leftAndDim = leftAndDimAt(treeIdx);
            int splitDim = unpackDim(leftAndDim);
            int leftIdx = unpackLeft(leftAndDim);
            int rightIdx = rightAt(treeIdx);
            int delta = s.permutedQueryI16[splitDim] - ptI16(treeIdx, splitDim);
            int near, far;
            if (delta < 0) { near = leftIdx; far = rightIdx; }
            else { near = rightIdx; far = leftIdx; }

            if (far >= 0) {
                int oldSlabD = slab[splitDim];
                int newSlabD = delta * delta;
                int newSlabSum = slabSum - oldSlabD + newSlabD;
                if (!full || newSlabSum <= thresholdSum) {
                    int nextDepth = depth + 1;
                    if (nextDepth <= BBF_MAX_DEPTH && s.bbfSize < KdScratch.BBF_HEAP_CAP
                            && s.bbfSlabNext < KdScratch.BBF_POOL_CAP) {
                        int newSlabIdx = s.bbfSlabNext++;
                        int poolOff = newSlabIdx * DIMS;
                        System.arraycopy(slab, 0, pool, poolOff, DIMS);
                        pool[poolOff + splitDim] = newSlabD;
                        int i = s.bbfSize++;
                        if (s.bbfSize > s.maxHeap) s.maxHeap = s.bbfSize;
                        while (i > 0) {
                            int parent = (i - 1) >>> 1;
                            if (hSlabSum[parent] <= newSlabSum) break;
                            hTreeIdx[i] = hTreeIdx[parent];
                            hSlabSum[i] = hSlabSum[parent];
                            hDepth[i] = hDepth[parent];
                            hSlabIdx[i] = hSlabIdx[parent];
                            i = parent;
                        }
                        hTreeIdx[i] = far;
                        hSlabSum[i] = newSlabSum;
                        hDepth[i] = nextDepth;
                        hSlabIdx[i] = newSlabIdx;
                    } else {
                        slab[splitDim] = newSlabD;
                        descend(far, s, k, newSlabSum, nextDepth);
                        slab[splitDim] = oldSlabD;
                    }
                }
            }
            treeIdx = near;
            depth++;
        }
    }

    // ── EXACT search: i16 BBF + double rerank == main.c knn_classify ─────────────────

    /**
     * SAME signature role as the legacy HnswIndex.search: quantizes the query
     * to permuted i16, runs the EXACT i16 BBF to gather the proven candidate
     * superset, then an EXACT DOUBLE RERANK replicating main.c
     * {@code knn_classify} (ascending origId, strict {@code <} + break ⇒ lowest
     * original ref index wins ties). Writes {@code s.fraudCount} (0..5) and
     * {@code s.knnFraud[]} exactly as the old search did so the response
     * writer / controller are unchanged downstream.
     */
    public void search(ConnectionState s) {
        KdScratch sc = scratch;
        float[] qSem = s.queryVector;
        prepareSearch(qSem, sc);
        // mirror s.queryQ16 (permuted i16, lanes 14-19 zero) for parity/inspection
        System.arraycopy(sc.permutedQueryI16, 0, s.queryQ16, 0, STRIDE);

        prime(sc, KdTopK.MAX_K);
        descendBBF(sc, KdTopK.MAX_K);

        // Ensure the i16 top-5 themselves are in the pool (they always satisfy
        // dist <= peekSum, but a tie at the boundary may have been dropped from
        // results' 5 slots while an equal-dist sibling stayed — both are needed
        // for the double tie-break; poolRecord already captured every node with
        // dist <= running 5th, so the i16 top-5 are present).
        int[] pTree = sc.poolTreeIdx;
        int[] pOrig = sc.poolOrig;
        int pn = sc.poolSize;

        // Sort the pool by ascending origId (in-place heapsort over the two
        // parallel arrays; zero-alloc). C scans refs i=0..N-1 in original index
        // order; processing candidates by ascending origId + strict-< insertion
        // reproduces "lowest original index wins ties" exactly.
        heapsortByOrig(pTree, pOrig, pn);

        double[] dists = sc.rrDist;
        int[] idxs = sc.rrIdx;
        for (int i = 0; i < KdTopK.MAX_K; i++) { dists[i] = 1e30; idxs[i] = -1; }

        short[] refSem = sc.refSemantic;
        int[] inv = KdLayout.INV_PERMUTATION;
        int prevOrig = -1;
        for (int p = 0; p < pn; p++) {
            int oid = pOrig[p];
            if (oid == prevOrig) continue; // dedup (same ref via different visits)
            prevOrig = oid;
            int ti = pTree[p];
            // dequantize this candidate to SEMANTIC i16 order
            for (int sdim = 0; sdim < DIMS; sdim++) refSem[sdim] = ptI16(ti, inv[sdim]);
            double d = DistanceFunctions.sqDistDoubleLikeC(qSem, refSem);
            // C knn_classify: insertion-sort 5, strict '<' + break (ties: keep
            // the earlier — i.e. lower origId, which is our ascending order).
            for (int j = 0; j < KdTopK.MAX_K; j++) {
                if (d < dists[j]) {
                    for (int kk = KdTopK.MAX_K - 1; kk > j; kk--) {
                        dists[kk] = dists[kk - 1];
                        idxs[kk] = idxs[kk - 1];
                    }
                    dists[j] = d;
                    idxs[j] = oid; // store ORIGINAL ref id (== C idxs[])
                    break;
                }
            }
        }

        // fraud_n among the final 5 (fraud bit packed in pts lane LANE_FRAUD,
        // keyed by the winning origId → its treeIdx). Build origId→treeIdx via
        // the pool (every final idx came from the pool).
        int fraud = 0;
        for (int i = 0; i < KdTopK.MAX_K; i++) {
            int oid = idxs[i];
            boolean f = false;
            if (oid >= 0) {
                int ti = treeIdxForOrig(pTree, pOrig, pn, oid);
                f = ti >= 0 && fraudBit(ti) != 0;
            }
            sc.rrFraud[i] = f;
            s.knnFraud[i] = f;
            if (f) fraud++;
        }
        s.fraudCount = fraud;
    }

    /** Linear lookup of the tree index for an origId within the (sorted) pool. */
    private static int treeIdxForOrig(int[] pTree, int[] pOrig, int pn, int oid) {
        // pOrig is sorted ascending → binary search.
        int lo = 0, hi = pn - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = pOrig[mid];
            if (v == oid) return pTree[mid];
            if (v < oid) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** In-place heapsort of parallel arrays keyed by {@code key} (ascending). */
    private static void heapsortByOrig(int[] aux, int[] key, int n) {
        // build max-heap
        for (int i = (n >> 1) - 1; i >= 0; i--) siftDown(aux, key, i, n);
        for (int end = n - 1; end > 0; end--) {
            swap(aux, key, 0, end);
            siftDown(aux, key, 0, end);
        }
    }

    private static void siftDown(int[] aux, int[] key, int i, int n) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, mx = i;
            if (l < n && key[l] > key[mx]) mx = l;
            if (r < n && key[r] > key[mx]) mx = r;
            if (mx == i) return;
            swap(aux, key, i, mx);
            i = mx;
        }
    }

    private static void swap(int[] aux, int[] key, int x, int y) {
        int t = key[x]; key[x] = key[y]; key[y] = t;
        t = aux[x]; aux[x] = aux[y]; aux[y] = t;
    }
}
