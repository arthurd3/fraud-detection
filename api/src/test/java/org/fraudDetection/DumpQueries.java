package org.fraudDetection;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.server.ConnectionState;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Gera o golden p/ o gate E=0 do motor Rust (G2a). Roda o MESMO
 * {@link FraudRequestParser} de produção sobre cada entrada do test-data.json e
 * emite o queryVector[14] (o que cruza a fronteira p/ o Rust) + o fraudCount
 * esperado (ground truth = {@code round(expected_fraud_score*5)}).
 *
 * <p>NÃO depende de {@code KdTree}/{@code RustSearch}/GraalVM — compila só com
 * parser + ConnectionState + TestDataReader (OpenJDK puro), então roda no dev box
 * sem o módulo org.graalvm.nativeimage. O Rust (example {@code agree}) lê este
 * arquivo, roda {@code fd_search} e prova rust==ground-truth nas 54.100.
 *
 * <p>Formato LE de {@code agree-queries.bin}:
 * {@code [u32 count]} então por entrada {@code [u8 parseOk][14 f32][u8 expectedFC]}.
 *
 * <p>Uso (de dentro de api/): {@code java -cp <out> org.fraudDetection.DumpQueries
 * [test-data.json] [out.bin]} (defaults ../../rinha-de-backend-2026/test/test-data.json
 * e ../agree-queries.bin).
 */
public final class DumpQueries {

    public static void main(String[] args) throws Exception {
        String testData = args.length > 0 ? args[0]
                : "../../rinha-de-backend-2026/test/test-data.json";
        String out = args.length > 1 ? args[1] : "../agree-queries.bin";

        TestDataReader rd = new TestDataReader(testData);

        ByteArrayOutputStream body = new ByteArrayOutputStream(64 << 20);
        byte[] rec = new byte[1 + 14 * 4 + 1];
        ByteBuffer rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);

        int count = 0, parseFail = 0;
        TestDataReader.Entry e;
        while ((e = rd.next()) != null) {
            ConnectionState s = new ConnectionState();
            byte[] b = e.body.getBytes(StandardCharsets.US_ASCII);
            s.readBuffer.put(b);
            s.bodyOffset = 0;
            s.contentLength = b.length;

            int pr = FraudRequestParser.parse(s);
            rb.clear();
            if (pr == FraudRequestParser.PARSE_OK) {
                rb.put((byte) 1);
                for (int i = 0; i < 14; i++) rb.putFloat(s.queryVector[i]);
            } else {
                parseFail++;
                rb.put((byte) 0);
                for (int i = 0; i < 14; i++) rb.putFloat(0f);
            }
            // ground truth fraudCount = round(expected_fraud_score * 5) ∈ 0..5
            int fc = (int) Math.round(e.expectedFraudScore * 5.0);
            rb.put((byte) fc);

            body.write(rec, 0, rec.length);
            count++;
        }

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            ByteBuffer h = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(count);
            os.write(h.array());
            body.writeTo(os);
        }
        System.out.println("DumpQueries: " + count + " entries ("
                + parseFail + " parse-fail) → " + out);
    }
}
