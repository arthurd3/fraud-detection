package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.knn.KdTreeIO;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.tools.Prebuild;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Onda 8 — deterministic offline replay of ALL 54,100 official entries through
 * the EXACT kernel, reporting the HW-INDEPENDENT memory-locality predictor:
 *
 *  - {@code visits}        — nodes touched per query (Fase-1 lever: reduce work).
 *  - {@code distinctPages} — distinct 4 KB pages of {@code pts} per query
 *                            (THE predictor for the vEB/BFS relayout: page-cache
 *                            / TLB / fault pressure under the 350 MB cgroup on
 *                            the Mac Mini — a relayout cuts THIS, not visits).
 *  - {@code distinctLines} — distinct 64 B cache lines per query.
 *
 * <p>Sets {@link KdTree#INSTR}=true (record-only; never changes results — run
 * {@code ExactAgree} to prove G2 0-div is unaffected). Deterministic (seed-4242
 * test data + fixed tree) ⇒ comparable across iterations to rank levers WITHOUT
 * burning official previews. Absolute p99 ms is NOT measured here (local CPU ≠
 * Mac Mini) — the preview remains the p99 verdict; this predicts the win.
 *
 * <p>Args: {@code [N]} (default = all). Run from {@code fraudDetection/api}.
 */
public final class VisitsReplay {

    public static void main(String[] args) throws Exception {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : Integer.MAX_VALUE;

        String res = "src/main/resources";
        String kdt = res + "/references.kdt";
        if (!KdTreeIO.isValid(Path.of(kdt), 3_000_000)) {
            System.out.println("references.kdt ausente/incompatível — construindo via Prebuild...");
            Prebuild.main(new String[]{res});
        }
        if (!KdTree.INSTR) {
            System.err.println("ERRO: rode com -Dfd.instr=true (instrumentação desligada).");
            System.exit(2);
        }
        KdTree tree = KdTreeIO.load(Path.of(kdt));
        KdTree.INSTANCE = tree;
        System.out.println("kdtree heap: " + tree.size() + " nós  | INSTR=on");

        TestDataReader rd = new TestDataReader("../../rinha-de-backend-2026/test/test-data.json");

        int cap = 54100;
        int[] vis = new int[cap], pg = new int[cap], ln = new int[cap];
        // Onda 22 rerank-stage instrumentation arrays.
        int[] rCand = new int[cap], rDouble = new int[cap], rIns = new int[cap];
        int[] rAbove = new int[cap], rPeek = new int[cap], rH2 = new int[cap];
        int total = 0, parseFail = 0, trunc = 0;
        long sVis = 0, sPg = 0, sLn = 0, sP = 0, sB = 0, sD = 0;
        long sRC = 0, sRD = 0, sRI = 0, sRA = 0, sRP = 0, sRH = 0;

        TestDataReader.Entry e;
        while ((e = rd.next()) != null && total < limit) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b);
            s.bodyOffset = 0;
            s.contentLength = b.length;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) { parseFail++; continue; }

            tree.search(s);

            int v = tree.lastVisits();
            int p = tree.lastDistinctPages();
            int l = tree.lastDistinctLines();
            int rc = tree.lastRerankCandidates();
            int rd2 = tree.lastRerankDoubleCalls();
            int ri = tree.lastRerankInsertions();
            int ra = tree.lastPoolAboveFinal();
            int rp = tree.lastPeekSumFinalI16();
            int rh = tree.lastRerankH2Skipped();
            if (tree.lastAccessTrunc()) trunc++;
            if (total < cap) {
                vis[total] = v; pg[total] = p; ln[total] = l;
                rCand[total] = rc; rDouble[total] = rd2; rIns[total] = ri;
                rAbove[total] = ra; rPeek[total] = rp; rH2[total] = rh;
            }
            sVis += v; sPg += p; sLn += l;
            sP += tree.lastVPrime(); sB += tree.lastVBBF(); sD += tree.lastVDescend();
            sRC += rc; sRD += rd2; sRI += ri; sRA += ra; sRP += rp; sRH += rh;
            total++;
        }

        int m = Math.min(total, cap);
        report("visits        ", vis, m, sVis);
        report("distinctPages ", pg, m, sPg);
        report("distinctLines ", ln, m, sLn);
        report("rerank.cand   ", rCand, m, sRC);
        report("rerank.double ", rDouble, m, sRD);
        report("rerank.h2skip ", rH2, m, sRH);
        report("rerank.ins    ", rIns, m, sRI);
        report("poolAboveFinal", rAbove, m, sRA);
        report("peekSumFinalI16", rPeek, m, sRP);
        long mm = m == 0 ? 1 : m;
        System.out.printf("visits-breakdown mean: prime=%d  bbf=%d  descend=%d  (total=%d)%n",
                sP / mm, sB / mm, sD / mm, sVis / mm);

        // Derivadas agregadas (sobre o total, não média de razões — evita viés de
        // queries com poucos candidatos dominarem a média). Pós-H2: dedup_skipped
        // = candidates - double - h2_skipped (H2 e dedup são caminhos diferentes
        // de "continue" no loop; rerankCandidates conta TODAS as iterações).
        long dedupSkipped   = sRC - sRD - sRH;
        double dedupRatio   = sRC > 0 ? (double) dedupSkipped / sRC * 100.0 : 0.0;
        double h2Ratio      = sRC > 0 ? (double) sRH / sRC * 100.0 : 0.0;
        double earlyExit    = sRC > 0 ? (double) sRA / sRC * 100.0 : 0.0;
        double doubleShare  = sRC > 0 ? (double) sRD / sRC * 100.0 : 0.0;
        System.out.printf("dedup.ratio (agg)         = %.2f%%   (%d dedup-skipped of %d candidates)%n",
                dedupRatio, dedupSkipped, sRC);
        System.out.printf("h2.skip.ratio (agg)       = %.2f%%   (%d H2-pruned of %d candidates)%n",
                h2Ratio, sRH, sRC);
        System.out.printf("earlyExit.potential (agg) = %.2f%%   (%d pool entries with i16Sum > final-5th of %d)%n",
                earlyExit, sRA, sRC);
        System.out.printf("double.share (agg)        = %.2f%%   (%d sqDistDoubleLikeC calls of %d candidates)%n",
                doubleShare, sRD, sRC);

        // p50/p99 das razões dedup, h2, e earlyExit — útil pra ver a cauda.
        // Pre-build dedup-only array p/ reportRatio.
        int[] rDedupOnly = new int[m];
        for (int i = 0; i < m; i++) rDedupOnly[i] = rCand[i] - rDouble[i] - rH2[i];
        reportRatio("dedup.ratio   ", rCand, rDedupOnly, m, false);
        reportRatio("h2.skip.ratio ", rCand, rH2,        m, false);
        reportRatio("earlyExit.pot ", rCand, rAbove,     m, false);
        reportRatio("double.share  ", rCand, rDouble,    m, false);

        System.out.println("──────────────────────────────────────────────────────────");
        System.out.println("entries: " + total + "  parseFail: " + parseFail
                + "  accessLog-trunc: " + trunc + (trunc > 0 ? "  ⚠ (raise cap)" : ""));
        System.out.println("(deterministic — comparar mean/p99/max entre iterações; "
                + "queda de distinctPages prediz o ganho de p99 da Fase 2)");
    }

    /**
     * Razão por query (escalonada × 10000 para usar int sort). Quando
     * {@code complementOfDouble=true}, calcula dedup = (cand - other)/cand;
     * caso contrário earlyExit = other/cand.
     */
    private static void reportRatio(String name, int[] cand, int[] other, int m, boolean complementOfDouble) {
        if (m == 0) { System.out.printf("%s  (empty)%n", name); return; }
        int[] r = new int[m];
        long sum = 0;
        int nz = 0;
        for (int i = 0; i < m; i++) {
            if (cand[i] <= 0) { r[i] = 0; continue; }
            long num = complementOfDouble ? (long) (cand[i] - other[i]) : (long) other[i];
            int scaled = (int) (num * 10000L / cand[i]); // permil + 1 casa
            r[i] = scaled; sum += scaled; nz++;
        }
        java.util.Arrays.sort(r);
        int p50 = r[(int) (m * 0.50)];
        int p99 = r[(int) Math.min(m - 1, (long) (m * 0.99))];
        int max = r[m - 1];
        double mean = nz > 0 ? (double) sum / nz : 0.0;
        System.out.printf("%s  mean=%.2f%%   p50=%.2f%%   p99=%.2f%%   max=%.2f%%%n",
                name, mean / 100.0, p50 / 100.0, p99 / 100.0, max / 100.0);
    }

    private static void report(String name, int[] a, int m, long sum) {
        int[] c = Arrays.copyOf(a, m);
        Arrays.sort(c);
        long mean = m == 0 ? 0 : sum / m;
        int p50 = m == 0 ? 0 : c[(int) (m * 0.50)];
        int p99 = m == 0 ? 0 : c[(int) Math.min(m - 1, (long) (m * 0.99))];
        int max = m == 0 ? 0 : c[m - 1];
        System.out.printf("%s  mean=%-8d p50=%-8d p99=%-8d max=%-8d%n", name, mean, p50, p99, max);
    }
}
