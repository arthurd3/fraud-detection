package org.fraudDetection.server;

import java.nio.charset.StandardCharsets;

public class HttpResponserWriter {

    private static final byte[] RESPONSE_READY =
            ("HTTP/1.1 200 OK\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"
            ).getBytes(StandardCharsets.US_ASCII);

    public static void writeReady(ConnectionState state){
        state.writeBuffer.clear();
        state.writeBuffer.put(RESPONSE_READY);
        state.writeBuffer.flip();
    }

    public static void writeFraudScore(ConnectionState state , boolean approved , int fraudLevel){
        throw new UnsupportedOperationException("NEXT");
    }
}
