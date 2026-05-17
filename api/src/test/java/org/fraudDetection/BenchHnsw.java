package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Gate 4 — p50/p99 HNSW vs brute-force int8 (molde do BenchSearch da 2b).
 * HNSW medido em todas as N queries; BRUTE (O(3M), ~dezenas de ms cada) num
 * subconjunto representativo p/ não levar minutos. Reporta speedup p50.
 */
public final class BenchHnsw {
    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        if (args.length > 1) HnswIndex.efSearch = Integer.parseInt(args[1]);
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        HnswIndex.load("src/main/resources/hnsw.bin");

        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");
        byte[][] qs = new byte[N][];
        int m = 0;
        TestDataReader.Entry e;
        while (m < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b); s.bodyOffset = 0; s.contentLength = b.length;
            if (FraudRequestParser.parse(s) == FraudRequestParser.PARSE_OK) {
                Quantizer.quantize(s.queryVector, s.queryQ);
                qs[m] = Arrays.copyOf(s.queryQ, 16);
            }
            m++;
        }

        int bruteN = Math.min(m, 400);   // brute é O(3M) → amostra p/ p50/p99
        warmup(qs, m, bruteN);
        long[] hnsw  = measure(qs, m, true,  m);
        long[] brute = measure(qs, m, false, bruteN);
        report("HNSW ", hnsw);
        report("BRUTE", brute);
        System.out.printf("speedup p50 (brute/HNSW) = %.1fx%n",
                pctl(brute, 50) / (double) pctl(hnsw, 50));
    }

    static final int[] OUT = new int[5];
    static void one(byte[] q, boolean hnsw) {
        if (hnsw) HnswIndex.top5Hnsw(q, OUT); else HnswIndex.top5Brute(q, OUT);
    }
    static void warmup(byte[][] qs, int m, int bruteN) {
        for (int r = 0; r < 3; r++)
            for (int i = 0; i < Math.min(m, 100); i++)
                if (qs[i] != null) { one(qs[i], true); if (i < Math.min(bruteN,30)) one(qs[i], false); }
    }
    static long[] measure(byte[][] qs, int m, boolean hnsw, int limit) {
        long[] t = new long[limit]; int c = 0;
        for (int i = 0; i < m && c < limit; i++) {
            if (qs[i] == null) continue;
            long a = System.nanoTime();
            one(qs[i], hnsw);
            t[c++] = System.nanoTime() - a;
        }
        return Arrays.copyOf(t, c);
    }
    static long pctl(long[] v, int p) {
        long[] s = v.clone(); Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.ceil(p / 100.0 * s.length) - 1)];
    }
    static void report(String tag, long[] t) {
        long sum = 0; for (long x : t) sum += x;
        System.out.printf("%s  n=%d  p50=%.3fms  p99=%.3fms  media=%.3fms%n",
                tag, t.length, pctl(t,50)/1e6, pctl(t,99)/1e6, sum/(double)t.length/1e6);
    }
}
