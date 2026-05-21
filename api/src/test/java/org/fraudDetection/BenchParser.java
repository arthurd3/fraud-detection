package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.server.ConnectionState;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Fase 0 do brief de redução do p99 do parser. Mede ns/parse mediano
 * para decidir se vale atacar (≥ 3% do request total p99 = 11 100 ns).
 *
 * <p>Modelo: BenchSearch/AllocCheckKd. Carrega 100 bodies reais via
 * TestDataReader, pré-popula 100 ConnectionStates (uma por body, com
 * readBuffer já preenchido e bodyOffset/contentLength setados), warmup
 * 1 M iter, 3 trials × 10 M iter, reporta mediana.
 *
 * <p>Anti-DCE: acumula PARSE_OK + queryVector[0] em sink. Anti-branch-
 * predictor-lock: rotação modulo 100 dos bodies. Para p99/p99.9 também
 * roda 1 trial extra × 1 M iter amostrado (overhead-of-nanoTime cônscio
 * mas suficiente para shape da cauda).
 */
public final class BenchParser {

    private static final int N_BODIES = 100;
    private static final int WARMUP   = 1_000_000;
    private static final int MEASURE  = 10_000_000;
    private static final int SAMPLE_N = 1_000_000;
    private static final long REQUEST_P99_NS = 370_000L; // 0.37 ms, baseline :onda12 standalone

    public static void main(String[] args) throws Exception {
        // 1) Pré-popula N_BODIES ConnectionStates a partir do dataset oficial.
        TestDataReader rd = new TestDataReader("../../rinha-de-backend-2026/test/test-data.json");
        ConnectionState[] states = new ConnectionState[N_BODIES];
        int[]             lens   = new int[N_BODIES];
        int got = 0;
        TestDataReader.Entry e;
        while (got < N_BODIES && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            if (b.length > 4096) continue; // readBuffer = direct 4KB
            s.readBuffer.put(b);
            s.bodyOffset    = 0;
            s.contentLength = b.length;
            // Sanity: o parser DEVE rodar uma vez sem erro nesse body.
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) continue;
            states[got] = s;
            lens[got]   = b.length;
            got++;
        }
        if (got == 0) throw new IllegalStateException("no parseable bodies in test-data.json");
        final int N = got;

        int sumLen = 0; int minLen = Integer.MAX_VALUE; int maxLen = 0;
        for (int i = 0; i < N; i++) { sumLen += lens[i]; if (lens[i] < minLen) minLen = lens[i]; if (lens[i] > maxLen) maxLen = lens[i]; }
        System.out.printf("BenchParser: N=%d bodies (len min=%d mean=%d max=%d)%n",
                N, minLen, sumLen / N, maxLen);
        // Sanity print: queryVector[0] do body 0 (deve ser estável e plausível 0..1 ou -1)
        ConnectionState ss = states[0];
        FraudRequestParser.parse(ss);
        System.out.printf("  sanity: states[0].queryVector = [%.4f, %.4f, %.4f, ..., %.4f]%n",
                ss.queryVector[0], ss.queryVector[1], ss.queryVector[2], ss.queryVector[13]);

        // 2) Warmup
        long sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            ConnectionState s = states[i % N];
            int rc = FraudRequestParser.parse(s);
            sink += rc + Float.floatToRawIntBits(s.queryVector[0]);
        }

        // 3) 3 trials × MEASURE — mean ns/parse por trial
        System.out.printf("warmup=%d  measure=%d%n", WARMUP, MEASURE);
        double[] trials = new double[3];
        for (int t = 0; t < 3; t++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < MEASURE; i++) {
                ConnectionState s = states[i % N];
                int rc = FraudRequestParser.parse(s);
                sink += rc + Float.floatToRawIntBits(s.queryVector[0]);
            }
            long ns = System.nanoTime() - t0;
            trials[t] = (double) ns / MEASURE;
            System.out.printf("  trial %d: %d ns total  ->  %.2f ns/parse  (sink=%d)%n",
                    t + 1, ns, trials[t], sink);
        }
        double[] sorted = trials.clone();
        Arrays.sort(sorted);
        double median = sorted[1];
        System.out.printf("median ns/parse = %.2f  (min %.2f, max %.2f)%n",
                median, sorted[0], sorted[2]);

        // 4) Amostragem per-iter para p99/p99.9 (1 M iter)
        long[] samples = new long[SAMPLE_N];
        for (int i = 0; i < SAMPLE_N; i++) {
            ConnectionState st = states[i % N];
            long t0 = System.nanoTime();
            int rc = FraudRequestParser.parse(st);
            long ns = System.nanoTime() - t0;
            samples[i] = ns;
            sink += rc + Float.floatToRawIntBits(st.queryVector[0]);
        }
        Arrays.sort(samples);
        long p50  = samples[SAMPLE_N / 2];
        long p99  = samples[(int) (SAMPLE_N * 0.99)];
        long p999 = samples[(int) (SAMPLE_N * 0.999)];
        long mx   = samples[SAMPLE_N - 1];
        System.out.printf("ns/parse (per-iter, ~25-50 ns nanoTime overhead): p50=%d  p99=%d  p99.9=%d  max=%d (sink=%d)%n",
                p50, p99, p999, mx, sink);

        // 5) Decisão da Fase 0
        double pctMedian = 100.0 * median / REQUEST_P99_NS;
        double pctP99    = 100.0 * p99    / REQUEST_P99_NS;
        System.out.printf("%n=== Fase 0 / decisão ===%n");
        System.out.printf("median ns/parse                 = %.2f ns%n", median);
        System.out.printf("request p99 (baseline :onda12)  = %d ns (0.37 ms standalone host)%n", REQUEST_P99_NS);
        System.out.printf("parser_ns_median / request_p99  = %.2f%%%n", pctMedian);
        System.out.printf("parser_p99       / request_p99  = %.2f%%%n", pctP99);
        if (pctMedian < 3.0) {
            System.out.println("VERDICT: ABANDON — parser < 3% threshold; documente em PERFORMANCE_LEDGER e devolva.");
        } else if (pctMedian < 8.0) {
            System.out.println("VERDICT: LIMITED ATTACK — parser 3-8%; só hipóteses zero-risco (H1/H2/H7).");
        } else {
            System.out.println("VERDICT: FULL ATTACK — parser >= 8%; prossiga Fase 1 (profile).");
        }
    }
}
