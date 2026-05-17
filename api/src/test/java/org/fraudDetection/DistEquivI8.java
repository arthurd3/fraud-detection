package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.DistanceFunctions;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Gate A — sqDistI8 (SIMD) == sqDistI8Scalar em TODOS os 3M, p/ os 2 oráculos. */
public final class DistEquivI8 {

    static final String LEGIT = "{\"id\":\"tx-1329056812\",\"transaction\":{\"amount\":41.12,\"installments\":2,\"requested_at\":\"2026-03-11T18:45:53Z\"},\"customer\":{\"avg_amount\":82.24,\"tx_count_24h\":3,\"known_merchants\":[\"MERC-003\",\"MERC-016\"]},\"merchant\":{\"id\":\"MERC-016\",\"mcc\":\"5411\",\"avg_amount\":60.25},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":29.23},\"last_transaction\":null}";
    static final String FRAUD = "{\"id\":\"tx-3330991687\",\"transaction\":{\"amount\":9505.97,\"installments\":10,\"requested_at\":\"2026-03-14T05:15:12Z\"},\"customer\":{\"avg_amount\":81.28,\"tx_count_24h\":20,\"known_merchants\":[\"MERC-008\",\"MERC-007\",\"MERC-005\"]},\"merchant\":{\"id\":\"MERC-068\",\"mcc\":\"7802\",\"avg_amount\":54.86},\"terminal\":{\"is_online\":false,\"card_present\":true,\"km_from_home\":952.27},\"last_transaction\":null}";

    public static void main(String[] args) throws IOException {
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        boolean ok = check("legit", LEGIT) & check("fraud", FRAUD);
        System.out.println(ok ? "Gate A: SIMD == escalar -> PASS"
                              : "Gate A: DIVERGENCIA -> FAIL");
        if (!ok) System.exit(1);
    }

    static boolean check(String tag, String body) {
        ConnectionState s = new ConnectionState();
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        s.readBuffer.put(b);
        s.bodyOffset = 0;
        s.contentLength = b.length;
        if (FraudRequestParser.parse(s) != FraudRequestParser.PARSE_OK)
            throw new IllegalStateException("parse falhou: " + tag);
        Quantizer.quantize(s.queryVector, s.queryQ);

        int n = MmapDataset.count, diffs = 0;
        for (int i = 0; i < n; i++) {
            MmapDataset.data.get(MmapDataset.recBase(i), s.vScratch, 0, 16);
            int simd = DistanceFunctions.sqDistI8(s.queryQ, s.vScratch);
            int scal = DistanceFunctions.sqDistI8Scalar(s.queryQ, s.vScratch);
            if (simd != scal) {
                if (diffs < 5)
                    System.out.printf("  DIFF %s i=%d simd=%d scal=%d%n", tag, i, simd, scal);
                diffs++;
            }
        }
        System.out.printf("%s: %d divergencias em %d%n", tag, diffs, n);
        return diffs == 0;
    }
}
