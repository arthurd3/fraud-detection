package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.KdLayout;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.knn.KdTreeIO;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.tools.Prebuild;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Onda 7 v2 acceptance gates (offline, HotSpot):
 *
 *  G2  — tree + double rerank == official expected (approved AND fraud_score)
 *        over ALL 54,100 entries  →  target 0 mismatches.
 *  G3a — for the first {@code g3aN} entries, a literal {@code bruteDouble}
 *        (transcription of main.c knn_classify over all 3M refs, in original
 *        index order, using the SAME i16 store dequantized to double) equals
 *        the tree+rerank result  →  target 0 div.
 *  G3b — {@code bruteDouble} approved/fraud_score == expected over the first
 *        {@code g3bN} entries  →  target 0 div.
 *  Also asserts BBF heap/pool max usage ≤ caps over all queries.
 *
 * <p>Args: {@code [g3aN] [g3bN]}  (defaults 5000 / 5000). G2 is ALWAYS the full
 * 54,100. {@code bruteDouble} is O(N) per query (~0.05–0.1 s) so g3a/g3b are
 * sampled by default; scale via args. G2 (sub-ms KD-tree search) is the primary
 * byte-identity proof; G2-full ∧ G3a transitively implies bruteDouble==expected.
 */
public final class ExactAgree {

    private static final int DIMS = 14;

    public static void main(String[] args) throws Exception {
        int g3aN = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int g3bN = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        String res = "src/main/resources";
        String kdt = res + "/references.kdt";
        if (!KdTreeIO.isValid(Path.of(kdt), 3_000_000)) {
            System.out.println("references.kdt ausente/incompatível — construindo via Prebuild...");
            Prebuild.main(new String[]{res});
        }

        // Heap load (gives pts + origId arrays for the literal brute scan).
        KdTree tree = KdTreeIO.load(Path.of(kdt));
        KdTree.INSTANCE = tree;
        int n = tree.size();
        System.out.println("kdtree heap: " + n + " nós");

        // Build refs in ORIGINAL index order, dequantized to SEMANTIC double
        // (== main.c refs[].v[]: k/10000.0). origToTree maps origId → treeIdx.
        int[] origToTree = new int[n];
        for (int t = 0; t < n; t++) origToTree[tree.origIdAt(t)] = t;
        double[] refsSem = new double[(long) n * DIMS > Integer.MAX_VALUE ? 0 : n * DIMS];
        int[] inv = KdLayout.INV_PERMUTATION;
        byte[] refFraud = new byte[n];
        for (int orig = 0; orig < n; orig++) {
            int t = origToTree[orig];
            int rb = orig * DIMS;
            for (int sd = 0; sd < DIMS; sd++)
                refsSem[rb + sd] = tree.ptI16(t, inv[sd]) / 10000.0;
            refFraud[orig] = (byte) tree.fraudBit(t);
        }
        System.out.println("refs double (original order) prontos");

        TestDataReader rd = new TestDataReader("../../rinha-de-backend-2026/test/test-data.json");

        int total = 0;
        int g2Mismatch = 0, g2ScoreMismatch = 0, g2ApprovedMismatch = 0;
        int fp = 0, fn = 0;
        int g3aDiv = 0, g3bDiv = 0;
        int g3aDone = 0, g3bDone = 0;

        double[] bdDist = new double[5];
        int[] bdIdx = new int[5];

        TestDataReader.Entry e;
        while ((e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b);
            s.bodyOffset = 0;
            s.contentLength = b.length;
            total++;

            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) {
                // parse failure → fail-open (fraudCount 0 → approved). Compare
                // to expected like production would.
                boolean apF = true;
                double scF = 0.0;
                if (apF != e.expected) { g2Mismatch++; g2ApprovedMismatch++; if (apF) fp++; else fn++; }
                if (scF != e.expectedFraudScore) { g2ScoreMismatch++; }
                continue;
            }

            tree.search(s);
            int fc = s.fraudCount;
            boolean ap = fc < 3;
            double sc = fc / 5.0;

            boolean apMis = ap != e.expected;
            boolean scMis = sc != e.expectedFraudScore;
            if (apMis) { g2ApprovedMismatch++; if (ap) fp++; else fn++; }
            if (scMis) g2ScoreMismatch++;
            if (apMis || scMis) g2Mismatch++;

            // ── G3a / G3b: literal bruteDouble (knn_classify transcription) ──────────
            boolean doA = g3aDone < g3aN;
            boolean doB = g3bDone < g3bN;
            if (doA || doB) {
                bruteDouble(refsSem, refFraud, n, s.queryVector, bdDist, bdIdx);
                int bfFraud = 0;
                for (int i = 0; i < 5; i++)
                    if (bdIdx[i] >= 0 && refFraud[bdIdx[i]] != 0) bfFraud++;
                boolean bAp = bfFraud < 3;
                double bSc = bfFraud / 5.0;

                if (doA) {
                    g3aDone++;
                    if (bAp != ap || bSc != sc) g3aDiv++;
                }
                if (doB) {
                    g3bDone++;
                    if (bAp != e.expected || bSc != e.expectedFraudScore) g3bDiv++;
                }
            }
        }

        int e2 = g2ApprovedMismatch; // E = approved mismatches (FP+FN)
        int maxHeap = tree.lastMaxHeap(), maxPool = tree.lastMaxPool();
        boolean heapOk = maxHeap <= tree.bbfHeapCap();
        boolean poolOk = maxPool <= tree.bbfPoolCap();

        System.out.println("──────────────────────────────────────────────────────────");
        System.out.println("entries processed : " + total);
        System.out.println("G2 mismatches     : " + g2Mismatch
                + "  (approved=" + g2ApprovedMismatch + ", fraud_score=" + g2ScoreMismatch + ")"
                + "  [target 0 over " + total + "]");
        System.out.println("G3a div (tree==brute, " + g3aDone + " q)   : " + g3aDiv + "  [target 0]");
        System.out.println("G3b div (brute==expected, " + g3bDone + " q): " + g3bDiv + "  [target 0]");
        System.out.println("E (FP+FN)         : " + e2 + "  (FP=" + fp + " FN=" + fn + ")");
        System.out.println("maxHeap           : " + maxHeap + " / cap " + tree.bbfHeapCap()
                + (heapOk ? "  OK" : "  OVER"));
        System.out.println("maxPool           : " + maxPool + " / cap " + tree.bbfPoolCap()
                + (poolOk ? "  OK" : "  OVER"));
        boolean pass = g2Mismatch == 0 && g3aDiv == 0 && g3bDiv == 0 && heapOk && poolOk;
        System.out.println("RESULT            : " + (pass ? "PASS (G2/G3a/G3b green)" : "FAIL"));
        System.out.println("──────────────────────────────────────────────────────────");
        if (!pass) System.exit(1);
    }

    /**
     * Literal transcription of main.c {@code knn_classify}: scan refs i=0..N-1
     * in ORIGINAL order, {@code d = Σ(round4(q[j]) - refs[i][j])²} over double,
     * insertion-sort 5 with strict {@code <} + break (lowest original index wins
     * ties). {@code refsSem} are already {@code k/10000.0} (== C refs[].v[]).
     */
    private static void bruteDouble(double[] refsSem, byte[] refFraud, int n,
                                    float[] qSem, double[] dists, int[] idxs) {
        // round4(query) once (== main.c entries[i].vec[] after round4).
        double[] a = A.get();
        for (int j = 0; j < DIMS; j++) a[j] = Math.round((double) qSem[j] * 10000.0) / 10000.0;
        for (int i = 0; i < 5; i++) { dists[i] = 1e30; idxs[i] = -1; }
        for (int i = 0; i < n; i++) {
            int rb = i * DIMS;
            double sum = 0.0;
            for (int j = 0; j < DIMS; j++) {
                double diff = a[j] - refsSem[rb + j];
                sum += diff * diff;
            }
            for (int j = 0; j < 5; j++) {
                if (sum < dists[j]) {
                    for (int k = 4; k > j; k--) { dists[k] = dists[k - 1]; idxs[k] = idxs[k - 1]; }
                    dists[j] = sum;
                    idxs[j] = i;
                    break;
                }
            }
        }
    }

    /** Reused round4(query) buffer (one per JVM; ExactAgree is single-threaded). */
    private static final class A {
        private static final double[] BUF = new double[DIMS];
        static double[] get() { return BUF; }
    }
}
