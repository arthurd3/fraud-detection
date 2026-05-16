package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Gate2Int8 {
    public static void main(String[] args) throws IOException {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");        

        List<String> base = Files.readAllLines(
                Path.of("../docs/baselines/onda1-approved-" + N + ".txt"));
        TestDataReader rd = new TestDataReader(
                "../../rinha-de-backend-2026/test/test-data.json");

        int n = 0, agree = 0, fp = 0, fn = 0;
        TestDataReader.Entry e;
        while (n < N && (e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] body = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(body);
            s.bodyOffset = 0;
            s.contentLength = body.length;

            boolean approved;
            if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK) {
                approved = true;
            } else {
                Quantizer.quantize(s.queryVector, s.queryQ);
                HnswIndex.search(s);
                approved = s.fraudCount < 3;
            }
            boolean baseApproved = base.get(n).endsWith(" 1");
            if (approved == baseApproved) agree++;
            else if (approved && !baseApproved) fp++;     
            else fn++;                                     
            n++;
        }
        double pct = 100.0 * agree / n;
        System.out.printf("Gate 2: %d/%d agreement = %.2f%% (FP=%d FN=%d) -> %s%n",
                agree, n, pct, fp, fn, pct >= 99.0 ? "PASS" : "FAIL");
        if (pct < 99.0) System.exit(1);
    }
}