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
        int total = 0, parseFail = 0, trunc = 0;
        long sVis = 0, sPg = 0, sLn = 0;

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
            if (tree.lastAccessTrunc()) trunc++;
            if (total < cap) { vis[total] = v; pg[total] = p; ln[total] = l; }
            sVis += v; sPg += p; sLn += l;
            total++;
        }

        int m = Math.min(total, cap);
        report("visits        ", vis, m, sVis);
        report("distinctPages ", pg, m, sPg);
        report("distinctLines ", ln, m, sLn);
        System.out.println("──────────────────────────────────────────────────────────");
        System.out.println("entries: " + total + "  parseFail: " + parseFail
                + "  accessLog-trunc: " + trunc + (trunc > 0 ? "  ⚠ (raise cap)" : ""));
        System.out.println("(deterministic — comparar mean/p99/max entre iterações; "
                + "queda de distinctPages prediz o ganho de p99 da Fase 2)");
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
