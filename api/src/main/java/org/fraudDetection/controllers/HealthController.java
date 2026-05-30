package org.fraudDetection.controllers;

import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

public class HealthController {

    public static void handle(ConnectionState state){
        HttpResponseWriter.writeReady(state);
        // Onda 31: I/O agora é do NioServer (write inline; OP_WRITE só em parcial).
    }
}
