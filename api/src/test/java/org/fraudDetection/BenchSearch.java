package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.knn.KdTreeIO;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.tools.Prebuild;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Onda 22+ — end-to-end {@link KdTree#search} latency bench (HotSpot only,
 * single-thread, no NIO/HTTP overhead). Modelado em {@code AllocCheckKd}:
 * 256 query vectors reais pre-parseados, {@code ConnectionState} reusado.
 * Warmup 20k searches + 3 trials × 100k searches → reporta ns/search
 * (median 3 trials). Captura tanto a vitória de H1 (per-call kernel cost)
 * quanto a vitória de H2 (pruning de chamadas) compostas.
 *
 * <p>NÃO substitui o calib rig sob carga (k6 + cgroup): aqui medimos só
 * o trabalho de CPU "puro" (no contention), o que isola o impacto da
 * mudança algorítmica. Para o impacto em p99 sob throttle, ver o k6 oficial.
 */
public final class BenchSearch {

    private static final int WARMUP = 20_000;
    private static final int MEASURE = 100_000;

    public static void main(String[] args) throws Exception {
        String res = "src/main/resources";
        String kdt = res + "/references.kdt";
        if (!KdTreeIO.isValid(Path.of(kdt), 3_000_000)) {
            System.out.println("references.kdt ausente — construindo via Prebuild...");
            Prebuild.main(new String[]{res});
        }
        KdTree.INSTANCE = KdTreeIO.load(Path.of(kdt));
        System.out.println("kdtree heap: " + KdTree.INSTANCE.size() + " nós");

        int Qv = 256;
        float[][] qv = new float[Qv][14];
        TestDataReader rd = new TestDataReader("../../rinha-de-backend-2026/test/test-data.json");
        int got = 0;
        TestDataReader.Entry e;
        while (got < Qv && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b); s.bodyOffset = 0; s.contentLength = b.length;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) continue;
            System.arraycopy(s.queryVector, 0, qv[got], 0, 14);
            got++;
        }
        if (got == 0) throw new IllegalStateException("no parseable queries");
        final int Qe = got;

        ConnectionState st = new ConnectionState();
        for (int i = 0; i < WARMUP; i++) {
            System.arraycopy(qv[i % Qe], 0, st.queryVector, 0, 14);
            KdTree.INSTANCE.search(st);
        }

        System.out.printf("BenchSearch: Q=%d  warmup=%d  measure=%d  (HotSpot end-to-end search)%n",
                Qe, WARMUP, MEASURE);
        double[] trials = new double[3];
        long sink = 0;
        for (int t = 0; t < 3; t++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < MEASURE; i++) {
                System.arraycopy(qv[i % Qe], 0, st.queryVector, 0, 14);
                KdTree.INSTANCE.search(st);
                sink += st.fraudCount;
            }
            long ns = System.nanoTime() - t0;
            trials[t] = (double) ns / MEASURE;
            System.out.printf("  trial %d: %d ns total  ->  %.0f ns/search  (sink=%d)%n",
                    t + 1, ns, trials[t], sink);
        }

        double[] s = trials.clone();
        java.util.Arrays.sort(s);
        System.out.printf("median ns/search = %.0f  (min %.0f, max %.0f)%n",
                s[1], s[0], s[2]);
    }
}
