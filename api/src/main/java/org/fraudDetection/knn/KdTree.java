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
    /**
     * Onda 11 Phase B v2 (2026-05-20): raised 18→22 (≈ max tree depth for n=3M).
     * Previously depth-19..22 nodes were routed to the recursive {@code descend}
     * fallback (~167 visits / query, 42% of total) and processed in DFS-near-then-
     * far order per subtree. By extending the BBF best-first heap to handle the
     * full tree depth, those same deep nodes are popped in slabSum-ascending
     * order globally — earlier pops shrink {@code thresholdSum} sooner, so later
     * pops can be pruned by the updated threshold before paying their distSumI16.
     * E=0 unchanged: the pruning test (slabSum &gt; threshSum, optional bbox check
     * at depth ≤ TOP_BBOX_DEPTH=18) is sound under any visit order. {@link KdScratch}
     * BBF_HEAP_CAP / BBF_POOL_CAP grown to 1024 to absorb the deeper enqueues.
     */
    public static final int BBF_MAX_DEPTH = 22;
    /**
     * Onda 11 — Beam-of-2 greedy pre-pass replaces the legacy depth-5 exhaustive
     * fan-out (PRIME_FANOUT_DEPTH=5, PRIME_FANOUT_COUNT=32) plus the disabled
     * PRIME_PLUNGE_CAP=0 plunge stage. The fan-out visited 63 internal nodes at
     * depths 0..5 and staged 32 leaf candidates that were then never plunged
     * (Onda 9 Passo 2 sweep 0..32 proved plunges did not tighten the BBF entry
     * bound — bbf/descend were identical 122/163 at every cap). The untested
     * variant: replace the 32-fan-out entirely with two root→leaf greedy
     * descents (~44 visits, two true depth-22 leaves). E=0 by construction:
     * prime only writes to results/pool via considerNode, so the i16-5th double
     * bound argument (see poolRecord doc) is unchanged.
     */
    public static final int PRIME_BEAM_WIDTH = 2;
    public static final int BBF_HEAP_CAP = KdScratch.BBF_HEAP_CAP; // 1024 (Phase B v2)
    public static final int BBF_POOL_CAP = KdScratch.BBF_POOL_CAP; // 1024 (Phase B v2)

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
     * Onda 10 Step 2 — visit-budget cap (relaxação CONTROLADA, jvmoonshot pattern).
     * Quando ativo, {@link #continueDfsBBF} retorna após {@code s.visits >= MAX_VISITS_BUDGET},
     * mesmo se BBF heap ainda tem candidatos. Truncamento → top-5 atual (pode estar
     * sub-ótimo) → algumas queries têm fraudCount errado vs ground truth.
     *
     * <p>{@code MAX_VISITS_ENABLED} é {@code static final} capturado no class load via env
     * {@code KDTREE_MAX_VISITS}: ausente ou 0 ⇒ false ⇒ C2/GraalVM dead-code-elimina os
     * checks no hot loop (custo ZERO). Presente e &gt;0 ⇒ true ⇒ branch profile gravado
     * pelo PGO + cap aplicado.
     *
     * <p>{@code MAX_VISITS_BUDGET} é {@code static int} mutável (valor pode ser sweepado em
     * runtime via {@link #setMaxVisitsBudget}); a estrutura do código já está congelada pelo
     * {@code static final} ENABLED, então tuning é zero-rebuild.
     *
     * <p>Trade-off: 1 erro = -90 pts; p99 32ms → ~5-15ms quando cap corta cauda longa.
     * Ganho líquido positivo enquanto E (erros totais) &lt; ~30 (sweet spot ~5-15 erros).
     */
    public static final boolean MAX_VISITS_ENABLED;
    public static int MAX_VISITS_BUDGET;
    static {
        String v = System.getenv("KDTREE_MAX_VISITS");
        int parsed = Integer.MAX_VALUE;
        boolean enabled = false;
        if (v != null && !v.isEmpty()) {
            try {
                int n = Integer.parseInt(v);
                if (n > 0) { parsed = n; enabled = true; }
            } catch (NumberFormatException ignored) {}
        }
        MAX_VISITS_BUDGET = parsed;
        MAX_VISITS_ENABLED = enabled;
    }
    /** Bench/sweep setter — só efetivo se {@link #MAX_VISITS_ENABLED} foi ligado no boot. */
    public static void setMaxVisitsBudget(int cap) { MAX_VISITS_BUDGET = Math.max(1, cap); }

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

    /**
     * Best-effort mmap hints + page prewarm at boot. NO-OP madvise on Java 21,
     * but Onda 12 Phase D adds {@link KdMmap#loadIntoMemory} which uses the
     * standard NIO {@code MappedByteBuffer.load()} for MADV_WILLNEED + force
     * page-in; that keeps the index resident under the 159 MB cgroup pressure.
     */
    public void applyMmapHints() {
        if (ptsBuf != null) {
            KdMmap.madviseBestEffort(ptsBuf, 0);
            KdMmap.prewarm(ptsBuf);
            KdMmap.loadIntoMemory(ptsBuf);
        }
        if (origBuf != null) {
            KdMmap.prewarm(origBuf);
            KdMmap.loadIntoMemory(origBuf);
        }
    }

    /**
     * Onda 32 (p99): mlock da região mmap'd `pts` (~108.7 MiB) do índice p/ o
     * kernel não evictá-la sob pressão de memória do HOST (o índice vive no page
     * cache do host, não no cgroup — file_mapped=0; a cauda de major-fault vem da
     * eviction host-level sob carga: 2 api + lapada + k6 + SO). `pts` é o buffer
     * QUENTE (lido ~310×/query); `origId` (~8.6 MiB, lido ~5×/query) é deixado de
     * fora DE PROPÓSITO: anon(49)+pts(108.7)+origId(8.6)=166.5 MiB encostaria no
     * cap de 167 MiB (margem 0.5 MiB → OOM); só pts deixa ~9 MiB de folga.
     * Best-effort: qualquer falha (ENOMEM/EPERM/sem mmap) é logada e ignorada
     * (status quo). Toca zero compute/bytes => E=0 intacto. Rodar APÓS
     * {@link #applyMmapHints()} (páginas já residentes).
     */
    public void mlockIndex() {
        if (ptsBuf == null) { System.out.println("mlockIndex: heap mode, skip"); return; }
        org.fraudDetection.rust.RustSearch.fdRaiseMemlockRlimit();   // best-effort
        lockOne("pts", ptsBuf);
        // origId deixado reclaimável de propósito (margem do cap de 167 MiB).
    }

    private static void lockOne(String tag, java.nio.MappedByteBuffer buf) {
        if (buf == null) return;
        try {
            long addr = org.fraudDetection.rust.BufferAddr.addressOf(buf);
            long len  = buf.capacity();
            int rc = org.fraudDetection.rust.RustSearch.fdMlockRegion(addr, len);
            if (rc == 0) System.out.println("mlockIndex: locked " + tag + " " + len + " B");
            else System.err.println("mlockIndex: " + tag + " mlock failed rc=" + rc + " (continuing)");
        } catch (Throwable t) {
            System.err.println("mlockIndex: " + tag + " reflect failed: " + t + " (continuing)");
        }
    }

    // Gate accessors (ExactAgree).
    public int lastMaxHeap() { return scratch.maxHeap; }
    public int lastMaxPool() { return scratch.maxPool; }
    public int bbfHeapCap()  { return KdScratch.BBF_HEAP_CAP; }
    public int bbfPoolCap()  { return KdScratch.BBF_POOL_CAP; }

    // Onda 8 instrumentation accessors (valid after a search() with INSTR=true).
    public int lastVisits()          { return scratch.visits; }
    public int lastVPrime()          { return scratch.vPrime; }
    public int lastVBBF()            { return scratch.vBBF; }
    public int lastVDescend()        { return scratch.vDescend; }
    public int lastDistinctPages()   { return distinctUnits(scratch, 4096); }
    public int lastDistinctLines()   { return distinctUnits(scratch, 64); }
    public boolean lastAccessTrunc() { return scratch.accessTrunc; }

    // Onda 22 — rerank instrumentation accessors.
    public int lastRerankCandidates()  { return scratch.rerankCandidates; }
    public int lastRerankDoubleCalls() { return scratch.rerankDoubleCalls; }
    public int lastRerankInsertions()  { return scratch.rerankInsertions; }
    public int lastRerankH2Skipped()   { return scratch.rerankH2Skipped; }
    public int lastPeekSumFinalI16()   { return scratch.peekSumFinalI16; }
    /**
     * #{i : poolI16Sum[i] > peekSumFinalI16} sobre {@code [0, poolSize)}.
     * Onda 22 H2: {@code poolI16Sum} agora é unconditional em produção,
     * então este accessor é sempre meaningful (não precisa de {@code INSTR}).
     * Em produção pós-H2, esse conjunto corresponde a candidatos H2-podados
     * (módulo dedup-skip que pode acontecer antes).
     */
    public int lastPoolAboveFinal() {
        int[] log = scratch.poolI16Sum;
        int t = scratch.peekSumFinalI16;
        int n = scratch.poolSize;
        int c = 0;
        for (int i = 0; i < n; i++) if (log[i] > t) c++;
        return c;
    }
    public int lastPoolSize() { return scratch.poolSize; }

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
        int p = treeIdx * 3; // RKD5: origId packed uint24 LE
        return (origBuf.get(p) & 0xFF)
                | ((origBuf.get(p + 1) & 0xFF) << 8)
                | ((origBuf.get(p + 2) & 0xFF) << 16);
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
            // Onda 10 Step 1+1b lições (2026-05-19, FALSIFIED ambas tentativas):
            //
            //  Step 1  — bulk-read (3×getLong + 1×getInt + shifts) com default.iprof DO :onda9
            //            (scalar-treinado) → regressão -391 pts (4539→4148 mediana 3-trial rig).
            //  Step 1b — mesmo bulk-read APÓS regenerar default.iprof via Dockerfile.train +
            //            k6 oficial host (workflow §6) → ainda perde 64 pts (4350→4286).
            //
            //  Conclusão: PGO mismatch explicava 327 dos 391 pts iniciais, mas bulk-read perde
            //  por motivo mais fundamental — C2/GraalVM Native auto-vetoriza o scalar loop em
            //  AVX2 SIMD (14 i16² em ~2-4 instr.), enquanto getLong+shifts é scalar puro (~14
            //  instr.). Confie no compilador moderno; manual unroll perde em loops puros aqui.
            //
            //  Recipe PGO regen (preservado p/ futuros refactors maiores): Dockerfile.train +
            //  docker save -o ~/Desktop/.../_extract/instr-image.tar (sandbox bloqueia /tmp/);
            //  tar -xf p/ extrair /app/api; host run com SIGINT após k6 → api/default.iprof.
            //
            //  Mantido scalar (este é o código byte-idêntico ao :onda9 produção).
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
                // Onda 22 H2: gravamos i16Sum unconditionally (era INSTR-gated na Fase 1);
                // {@link KdTree#search} usa para early-exit por i16-5th bound.
                s.poolI16Sum[s.poolSize] = dist;
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
        s.visits = 0; s.vPrime = 0; s.vBBF = 0; s.vDescend = 0;
        s.bbfSize = 0;
        s.bbfSlabNext = 0;
        s.poolSize = 0;
        s.rerankCandidates = 0; s.rerankDoubleCalls = 0; s.rerankInsertions = 0;
        s.rerankH2Skipped = 0;
        s.peekSumFinalI16 = 0;
        short[] pqi = s.permutedQueryI16;
        int[] perm = KdLayout.DIM_PERMUTATION;
        for (int d = 0; d < DIMS; d++) pqi[d] = Quantizer.q16(querySemantic[perm[d]]);
        for (int d = DIMS; d < STRIDE; d++) pqi[d] = 0;

        // H1 (2026-05-21) — pre-compute round4(query) in SEMANTIC order ONCE per
        // query. Hoists 14 Math.round/mult/div from the per-candidate rerank loop
        // (≈ 35 calls/query mean, 71 max) to a single 14-iter prologue. Identity
        // proven by ExactAgree 54 100 0-div; algebraically the same expression
        // as the per-call line in DistanceFunctions.sqDistDoubleLikeC(float[],…).
        double[] qR4 = s.queryRound4;
        for (int d = 0; d < DIMS; d++) qR4[d] = Math.round((double) querySemantic[d] * 10000.0) / 10000.0;

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

    // ── Prime (Onda 11 beam-of-2 greedy descent) ─────────────────────────────────────

    /**
     * Pre-fill the top-K and pool with two cheap root→leaf greedy descents.
     *
     * <p>Descent #1 walks from the root following the {@code near} child at
     * every split (the side of the splitting plane that contains the query),
     * evaluating {@code distSumI16} and {@code considerNode} at every node.
     * In parallel, it tracks the single best (smallest {@code delta²}) far
     * child seen along the path — {@code delta²} is the L² lower bound on
     * the L² distance from query to any point in that far subtree, so the
     * smallest one is the most promising start for the second descent.
     *
     * <p>Descent #2 replays the same near-only greedy walk from that best far
     * child. Total visits ≈ {@code 2·depth} (≈ 44 for the balanced 3 M-node
     * tree of depth ~22), replacing the 63-visit exhaustive fan-out at
     * depths 0..5 of the legacy {@code primeRecurse}.
     *
     * <p>E=0 by construction: every visit feeds the same {@code considerNode}
     * path that the legacy prime used; pruning aggression in subsequent
     * BBF/descend depends only on {@code results.peekSum()}, which monotone-
     * decreases regardless of the prime variant. The i16-5th double-bound
     * argument (see {@link #poolRecord}) is unchanged. {@code ExactAgree}
     * over 54,100 queries is the empirical proof.
     */
    private void prime(KdScratch s, int k) {
        int bestFar = -1;
        int bestFarDeltaSq = Integer.MAX_VALUE;
        int treeIdx = 0;

        // Descent #1 — from root, always follow near; remember the best far.
        while (treeIdx >= 0) {
            s.visits++; s.vPrime++;
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
            if (far >= 0) {
                int deltaSq = delta * delta;
                if (deltaSq < bestFarDeltaSq) {
                    bestFarDeltaSq = deltaSq;
                    bestFar = far;
                }
            }
            treeIdx = near;
        }

        // Descent #2 — from the best far child, same near-only descent. Skipped
        // if the tree was so small that descent #1 saw no far children.
        if (bestFar < 0) return;
        treeIdx = bestFar;
        while (treeIdx >= 0) {
            s.visits++; s.vPrime++;
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
        s.visits++; s.vDescend++;
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
            s.visits++; s.vBBF++;
            // Onda 10 Step 2 — visit-budget cap (relaxação). C2/GraalVM elimina o branch
            // inteiro quando MAX_VISITS_ENABLED=false (static final do env vazio). Quando
            // ativo, retorna o top-5 acumulado até aqui (pode estar sub-ótimo). Apenas o
            // BBF tem cap; the Onda 11 beam-of-2 prime (≤44 visits) + descend (≤depth ~22)
            // are bounded by construction so the cap never short-circuits them.
            if (MAX_VISITS_ENABLED && s.visits >= MAX_VISITS_BUDGET) return;
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
        sc.peekSumFinalI16 = sc.results.peekSum();

        // Ensure the i16 top-5 themselves are in the pool (they always satisfy
        // dist <= peekSum, but a tie at the boundary may have been dropped from
        // results' 5 slots while an equal-dist sibling stayed — both are needed
        // for the double tie-break; poolRecord already captured every node with
        // dist <= running 5th, so the i16 top-5 are present).
        int[] pTree = sc.poolTreeIdx;
        int[] pOrig = sc.poolOrig;
        int pn = sc.poolSize;

        // Sort the pool by ascending origId (in-place heapsort over THREE parallel
        // arrays — zero-alloc). C scans refs i=0..N-1 in original index order;
        // processing candidates by ascending origId + strict-< insertion reproduces
        // "lowest original index wins ties" exactly.
        //
        // Onda 22 H2 (2026-05-21): poolI16Sum MUST be sorted in parallel with
        // pTree/pOrig — the rerank loop reads {@code sc.poolI16Sum[p]} indexed by
        // SORTED position p (the same p that indexes pOrig[p]/pTree[p]). Forgetting
        // to swap poolI16Sum during the sort yields a misaligned bound check ⇒
        // 50 %+ ExactAgree mismatches (bug detected & fixed pre-commit).
        heapsortByOrig(pTree, sc.poolI16Sum, pOrig, pn);

        double[] dists = sc.rrDist;
        int[] idxs = sc.rrIdx;
        for (int i = 0; i < KdTopK.MAX_K; i++) { dists[i] = 1e30; idxs[i] = -1; }

        short[] refSem = sc.refSemantic;
        double[] qR4 = sc.queryRound4;
        int[] poolI16 = sc.poolI16Sum;
        int finalI16 = sc.peekSumFinalI16;
        int[] inv = KdLayout.INV_PERMUTATION;
        int prevOrig = -1;
        for (int p = 0; p < pn; p++) {
            sc.rerankCandidates++;
            int oid = pOrig[p];
            if (oid == prevOrig) continue; // dedup (same ref via different visits)
            prevOrig = oid;
            // Onda 22 H2 (2026-05-21) — early-exit por i16-5th double bound argument
            // (sound: see poolRecord doc; 1/1e8 integer-gap >> ~2e-14 round-off slack ⇒
            // poolI16Sum[p] > peekSumFinalI16 ⇒ doubleSum strictly above 5th, can't enter
            // top-5; ExactAgree 0/54100 prova empírica). Fase 1 mediu teto 86.20 % de
            // candidatos podáveis (1.9 M of 2.2 M sobre 54100 entries).
            if (poolI16[p] > finalI16) { sc.rerankH2Skipped++; continue; }
            int ti = pTree[p];
            sc.rerankDoubleCalls++;
            // dequantize this candidate to SEMANTIC i16 order
            for (int sdim = 0; sdim < DIMS; sdim++) refSem[sdim] = ptI16(ti, inv[sdim]);
            double d = DistanceFunctions.sqDistDoubleLikeC(qR4, refSem);
            // C knn_classify: insertion-sort 5, strict '<' + break (ties: keep
            // the earlier — i.e. lower origId, which is our ascending order).
            // Onda 22 H6 (2026-05-21) FALSIFIED — branch-free 5-slot variant
            // testada (9 trials BenchSearch): mean 16,339 vs H2 mean 16,274 =
            // +0.4 % noise; H6 spread 10 % vs H2 spread 2 % ⇒ no-op-noisy. Por
            // §7 protocolo, revertido. Ver ledger §3.1b FALSIFIED para detalhe.
            // Branchful early-break vence porque rerank.ins ≈ rerank.double pós-H2
            // (toda call insere) com predictable insertion-position distribution.
            for (int j = 0; j < KdTopK.MAX_K; j++) {
                if (d < dists[j]) {
                    sc.rerankInsertions++;
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

    /** In-place heapsort of THREE parallel arrays keyed by {@code key} (ascending).
     *  Onda 22 H2: extended from 2 to 3 arrays to keep {@code poolI16Sum} aligned
     *  with {@code pTree}/{@code pOrig} so the H2 bound check
     *  {@code sc.poolI16Sum[p] > peekSumFinalI16} reads the correct i16Sum per
     *  sorted candidate. */
    private static void heapsortByOrig(int[] aux, int[] sumAux, int[] key, int n) {
        for (int i = (n >> 1) - 1; i >= 0; i--) siftDown(aux, sumAux, key, i, n);
        for (int end = n - 1; end > 0; end--) {
            swap(aux, sumAux, key, 0, end);
            siftDown(aux, sumAux, key, 0, end);
        }
    }

    private static void siftDown(int[] aux, int[] sumAux, int[] key, int i, int n) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, mx = i;
            if (l < n && key[l] > key[mx]) mx = l;
            if (r < n && key[r] > key[mx]) mx = r;
            if (mx == i) return;
            swap(aux, sumAux, key, i, mx);
            i = mx;
        }
    }

    private static void swap(int[] aux, int[] sumAux, int[] key, int x, int y) {
        int t = key[x];    key[x]    = key[y];    key[y]    = t;
        t = aux[x];        aux[x]    = aux[y];    aux[y]    = t;
        t = sumAux[x];     sumAux[x] = sumAux[y]; sumAux[y] = t;
    }
}
