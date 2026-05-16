package org.fraudDetection.server;

import java.nio.charset.StandardCharsets;

public final class HttpResponseWriter {

    private HttpResponseWriter() {}

    private static final byte[] RESPONSE_READY =
            ("HTTP/1.1 200 OK\r\n" +
             "Connection: keep-alive\r\n" +
             "Content-Length: 0\r\n" +
             "\r\n").getBytes(StandardCharsets.US_ASCII);

    // 6 respostas canned, indexadas por fraudCount (0..5). approved = score < 0.6.
    private static final byte[][] RESPONSE_FRAUD = new byte[6][];
    static {
        String[] body = {
            "{\"approved\":true,\"fraud_score\":0.0}",   // 0/5
            "{\"approved\":true,\"fraud_score\":0.2}",   // 1/5
            "{\"approved\":true,\"fraud_score\":0.4}",   // 2/5
            "{\"approved\":false,\"fraud_score\":0.6}",  // 3/5
            "{\"approved\":false,\"fraud_score\":0.8}",  // 4/5
            "{\"approved\":false,\"fraud_score\":1.0}"   // 5/5
        };
        for (int i = 0; i < 6; i++) {
            byte[] bb = body[i].getBytes(StandardCharsets.US_ASCII);
            String head = "HTTP/1.1 200 OK\r\n" +
                          "Connection: keep-alive\r\n" +
                          "Content-Type: application/json\r\n" +
                          "Content-Length: " + bb.length + "\r\n\r\n";
            byte[] hb = head.getBytes(StandardCharsets.US_ASCII);
            byte[] full = new byte[hb.length + bb.length];
            System.arraycopy(hb, 0, full, 0, hb.length);
            System.arraycopy(bb, 0, full, hb.length, bb.length);
            RESPONSE_FRAUD[i] = full;
        }
    }

    public static void writeReady(ConnectionState state) {
        state.writeBuffer.clear();
        state.writeBuffer.put(RESPONSE_READY);
        state.writeBuffer.flip();
    }

    /** fraudCount 0..5 -> 1 das 6 respostas canned (Content-Length ja correto). */
    public static void writeFraudScore(ConnectionState state, int fraudCount) {
        state.writeBuffer.clear();
        state.writeBuffer.put(RESPONSE_FRAUD[fraudCount]);
        state.writeBuffer.flip();
    }
}
