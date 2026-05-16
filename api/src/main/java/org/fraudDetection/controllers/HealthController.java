package org.fraudDetection.controllers;

import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

import java.nio.channels.SelectionKey;

public class HealthController {

    public static void handle(ConnectionState state, SelectionKey key){
        HttpResponseWriter.writeReady(state);
        key.interestOps(SelectionKey.OP_WRITE);
    }
}
