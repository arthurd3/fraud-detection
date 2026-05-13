package org.fraudDetection.server;

import java.nio.channels.SelectionKey;

public class HealthController {

    public static void handle(ConnectionState state, SelectionKey key){
        HttpResponserWriter.writeReady(state);
        key.interestOps(SelectionKey.OP_WRITE);

    }
}
