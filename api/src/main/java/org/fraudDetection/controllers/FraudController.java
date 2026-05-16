package org.fraudDetection.controllers;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.HnswIndex;
import org.fraudDetection.knn.Quantizer;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

import java.nio.channels.SelectionKey;

public final class FraudController {

    private FraudController() {}

    public static void handle(ConnectionState state, SelectionKey key) {
        if (FraudRequestParser.parse(state) != FraudRequestParser.PARSE_OK) {
            state.fraudCount = 0;
        } else {
            Quantizer.quantize(state.queryVector, state.queryQ);
            HnswIndex.search(state);                 
        }
        HttpResponseWriter.writeFraudScore(state, state.fraudCount);
        key.interestOps(SelectionKey.OP_WRITE);
    }
}