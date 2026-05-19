package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.knn.KdTreeIO;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.tools.Prebuild;

import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Onda 7 v2 — proves {@link KdTree#search} is zero-alloc steady-state (HotSpot).
 * Mirrors the legacy {@code AllocCheck}: warm 20k, then measure
 * {@link ThreadMXBean#getThreadAllocatedBytes} over 100k → assert ~0 B/query.
 * All KD-tree scratch (BBF heap/pool, candidate pool, rerank buffers) is
 * preallocated in {@code KdScratch}; the query path must not {@code new}.
 */
public final class AllocCheckKd {
    public static void main(String[] args) throws Exception {
        String res = "src/main/resources";
        String kdt = res + "/references.kdt";
        if (!KdTreeIO.isValid(Path.of(kdt), 3_000_000)) {
            System.out.println("references.kdt ausente — construindo via Prebuild...");
            Prebuild.main(new String[]{res});
        }
        KdTree.INSTANCE = KdTreeIO.load(Path.of(kdt));
        System.out.println("kdtree heap: " + KdTree.INSTANCE.size() + " nós");

        int Q = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;

        // Pre-parse a pool of real query vectors (parsing is NOT measured).
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

        // One reused ConnectionState — search() must be zero-alloc.
        ConnectionState st = new ConnectionState();

        for (int i = 0; i < 20_000; i++) {
            System.arraycopy(qv[i % got], 0, st.queryVector, 0, 14);
            KdTree.INSTANCE.search(st);
        }

        ThreadMXBean tb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long a0 = tb.getThreadAllocatedBytes(tid);
        for (int i = 0; i < Q; i++) {
            System.arraycopy(qv[i % got], 0, st.queryVector, 0, 14);
            KdTree.INSTANCE.search(st);
        }
        long a1 = tb.getThreadAllocatedBytes(tid);

        double perQ = (a1 - a0) / (double) Q;
        System.out.printf("alloc: %d bytes em %d queries -> %.2f B/query%n", (a1 - a0), Q, perQ);
        boolean pass = perQ < 16.0;            // folga p/ ruído de medição
        System.out.println(pass ? "AllocCheckKd: zero-alloc -> PASS"
                                 : "AllocCheckKd: AINDA ALOCA -> FAIL");
        if (!pass) System.exit(1);
    }
}
