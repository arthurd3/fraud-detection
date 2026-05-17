package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RecallHnsw {
    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        if (args.length > 1) HnswIndex.efSearch = Integer.parseInt(args[1]);
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        HnswIndex.load("src/main/resources/hnsw.bin");

        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");
        int[] bh = new int[5], hh = new int[5];
        int n = 0, agree = 0, fp = 0, fn = 0; double recSum = 0;

        TestDataReader.Entry e;
        while (n < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b); s.bodyOffset = 0; s.contentLength = b.length;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) { n++; continue; }
            Quantizer.quantize(s.queryVector, s.queryQ);

            HnswIndex.top5Brute(s.queryQ, bh);
            HnswIndex.top5Hnsw (s.queryQ, hh);

            int inter = 0;
            for (int x : hh) if (x >= 0) for (int y : bh) if (x == y) { inter++; break; }
            recSum += inter / 5.0;

            boolean apH = approved(hh), apB = approved(bh);
            if (apH == apB) agree++;
            else if (apH && !apB) fp++; else fn++;
            n++;
        }
        double recall = 100.0 * recSum / n, ag = 100.0 * agree / n;
        System.out.printf("ef_search=%d  recall@5=%.2f%%  approved-agree=%.2f%% (FP=%d FN=%d) -> %s%n",
                HnswIndex.efSearch, recall, ag, fp, fn,
                (recall >= 95.0 && ag >= 99.0) ? "PASS" : "FAIL");
        if (!(recall >= 95.0 && ag >= 99.0)) System.exit(1);
    }
    static boolean approved(int[] ids) {
        int fr = 0; for (int id : ids) if (id >= 0 && MmapDataset.fraud(id)) fr++;
        return fr < 3;                       // fraud_score < 0.6
    }
}
