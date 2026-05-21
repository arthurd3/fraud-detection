package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.DistanceFunctions;
import org.fraudDetection.knn.KdLayout;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.knn.KdTreeIO;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.tools.Prebuild;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Onda 12+ Fase 1 — microbench codegen isolado de
 * {@link DistanceFunctions#sqDistDoubleLikeC}.
 *
 * <p>256 queries reais (parseadas do test-data.json, idem padrão AllocCheckKd) ×
 * 100 refs sampleados uniformemente do KD-tree (proxy do pool típico de ~100
 * candidatos). Warmup 1M iter + 3 trials × 10M iter de loop fechado: o que sai
 * é o ns/call do kernel rerank em HotSpot. Use para priorizar H1 (hoist do
 * {@code round4(query)}) vs H3 (cast-trick branchless) antes da Fase 2 —
 * NÃO mede algoritmo. HotSpot-only; native-image rebuild não está nesse escopo.
 */
public final class BenchRerank {

    private static final int DIMS = 14;
    private static final int Q = 256;
    private static final int R = 100;
    private static final int WARMUP = 1_000_000;
    private static final int MEASURE = 10_000_000;

    public static void main(String[] args) throws Exception {
        String res = "src/main/resources";
        String kdt = res + "/references.kdt";
        if (!KdTreeIO.isValid(Path.of(kdt), 3_000_000)) {
            System.out.println("references.kdt ausente — construindo via Prebuild...");
            Prebuild.main(new String[]{res});
        }
        KdTree tree = KdTreeIO.load(Path.of(kdt));
        KdTree.INSTANCE = tree;
        int n = tree.size();
        System.out.println("kdtree heap: " + n + " nós");

        float[][] qs = new float[Q][DIMS];
        int got = 0;
        TestDataReader rd = new TestDataReader("../../rinha-de-backend-2026/test/test-data.json");
        TestDataReader.Entry e;
        while (got < Q && (e = rd.next()) != null) {
            ConnectionState st = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            st.readBuffer.put(b);
            st.bodyOffset = 0;
            st.contentLength = b.length;
            if (FraudRequestParser.parse(st) != FraudRequestParser.PARSE_OK) continue;
            System.arraycopy(st.queryVector, 0, qs[got], 0, DIMS);
            got++;
        }
        if (got == 0) throw new IllegalStateException("no parseable queries");
        if (got < Q) System.out.println("WARN: só " + got + " queries parseáveis (esperado " + Q + ")");
        final int Qe = got;

        // 100 refs evenly spaced through the tree (proxy for typical pool).
        short[][] refs = new short[R][DIMS];
        int[] inv = KdLayout.INV_PERMUTATION;
        for (int i = 0; i < R; i++) {
            int ti = (int) ((long) i * n / R);
            for (int sd = 0; sd < DIMS; sd++) refs[i][sd] = tree.ptI16(ti, inv[sd]);
        }

        // H1 hoisted query: pre-compute round4(query) per query, ONCE.
        double[][] qsR4 = new double[Qe][DIMS];
        for (int q = 0; q < Qe; q++) {
            for (int d = 0; d < DIMS; d++) {
                qsR4[q][d] = Math.round((double) qs[q][d] * 10000.0) / 10000.0;
            }
        }

        // Bit-id sanity: every (query, ref) must give the same double from both overloads.
        long mismatches = 0;
        for (int i = 0; i < Math.min(Qe, 256); i++) {
            double v1 = DistanceFunctions.sqDistDoubleLikeC(qs[i],   refs[i % R]);
            double v2 = DistanceFunctions.sqDistDoubleLikeC(qsR4[i], refs[i % R]);
            if (Double.doubleToRawLongBits(v1) != Double.doubleToRawLongBits(v2)) mismatches++;
        }
        System.out.printf("bit-id sanity: %d mismatches over %d (query,ref) pairs  [target 0]%n",
                mismatches, Math.min(Qe, 256));

        // ── Baseline (legacy float[] overload — recomputes round4 per call) ──────────
        double sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += DistanceFunctions.sqDistDoubleLikeC(qs[i % Qe], refs[i % R]);
        }
        if (sink == Double.NaN) System.out.println("(blackhole warmup " + sink + ")");

        System.out.printf("BASELINE kernel: sqDistDoubleLikeC(float[], short[])  |  Q=%d  R=%d  warmup=%d  measure=%d%n",
                Qe, R, WARMUP, MEASURE);
        double[] baseNs = new double[3];
        for (int t = 0; t < 3; t++) {
            sink = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < MEASURE; i++) {
                sink += DistanceFunctions.sqDistDoubleLikeC(qs[i % Qe], refs[i % R]);
            }
            long ns = System.nanoTime() - t0;
            if (sink == Double.NaN) System.out.println("(blackhole " + sink + ")");
            baseNs[t] = (double) ns / MEASURE;
            System.out.printf("  trial %d: %d ns total  ->  %.2f ns/call  (sink=%.6e)%n",
                    t + 1, ns, baseNs[t], sink);
        }

        // ── H1 variant (double[] overload — round4 pre-hoisted) ──────────────────────
        sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += DistanceFunctions.sqDistDoubleLikeC(qsR4[i % Qe], refs[i % R]);
        }
        if (sink == Double.NaN) System.out.println("(blackhole warmup " + sink + ")");

        System.out.printf("H1 kernel: sqDistDoubleLikeC(double[], short[])  |  Q=%d  R=%d  warmup=%d  measure=%d%n",
                Qe, R, WARMUP, MEASURE);
        double[] h1Ns = new double[3];
        for (int t = 0; t < 3; t++) {
            sink = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < MEASURE; i++) {
                sink += DistanceFunctions.sqDistDoubleLikeC(qsR4[i % Qe], refs[i % R]);
            }
            long ns = System.nanoTime() - t0;
            if (sink == Double.NaN) System.out.println("(blackhole " + sink + ")");
            h1Ns[t] = (double) ns / MEASURE;
            System.out.printf("  trial %d: %d ns total  ->  %.2f ns/call  (sink=%.6e)%n",
                    t + 1, ns, h1Ns[t], sink);
        }

        double baseMed = median3(baseNs);
        double h1Med   = median3(h1Ns);
        double delta   = baseMed - h1Med;
        double pct     = (delta / baseMed) * 100.0;
        System.out.println("──────────────────────────────────────────────────────────");
        System.out.printf("DELTA: baseline median %.2f ns/call  ->  H1 median %.2f ns/call  =  -%.2f ns/call  (-%.1f%%)%n",
                baseMed, h1Med, delta, pct);
        System.out.println("(35 calls/query mean ⇒ estimated savings ≈ "
                + String.format("%.0f", delta * 35) + " ns/query mean, "
                + String.format("%.0f", delta * 55) + " ns/query p99)");
    }

    private static double median3(double[] a) {
        double[] c = a.clone();
        java.util.Arrays.sort(c);
        return c[1];
    }
}
