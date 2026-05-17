package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.DistanceFunctions;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BenchSearch {

    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");

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

        warmup(qs, m);
        long[] scal = measure(qs, m, false);
        long[] simd = measure(qs, m, true);
        report("ESCALAR", scal);
        report("SIMD   ", simd);
        System.out.printf("speedup p50 (escalar/SIMD) = %.2fx%n",
                pctl(scal, 50) / (double) pctl(simd, 50));
    }

    static int searchFraud(byte[] q, byte[] vs, boolean simd) {
        int n = MmapDataset.count;
        float[] bd = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                      Float.MAX_VALUE, Float.MAX_VALUE};
        boolean[] bf = new boolean[5];
        for (int i = 0; i < n; i++) {
            MmapDataset.data.get(MmapDataset.recBase(i), vs, 0, 16);
            int d = simd ? DistanceFunctions.sqDistI8(q, vs)
                         : DistanceFunctions.sqDistI8Scalar(q, vs);
            if (d < bd[4]) {
                int p = 4;
                while (p > 0 && bd[p - 1] > d) { bd[p] = bd[p-1]; bf[p] = bf[p-1]; p--; }
                bd[p] = d; bf[p] = MmapDataset.fraud(i);
            }
        }
        int f = 0; for (int k = 0; k < 5; k++) if (bf[k]) f++; return f;
    }

    static void warmup(byte[][] qs, int m) {
        byte[] vs = new byte[16];
        for (int r = 0; r < 3; r++)
            for (int i = 0; i < Math.min(m, 150); i++)
                if (qs[i] != null) { searchFraud(qs[i], vs, true); searchFraud(qs[i], vs, false); }
    }
    static long[] measure(byte[][] qs, int m, boolean simd) {
        byte[] vs = new byte[16];
        long[] t = new long[m]; int c = 0;
        for (int i = 0; i < m; i++) {
            if (qs[i] == null) continue;
            long a = System.nanoTime();
            searchFraud(qs[i], vs, simd);
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