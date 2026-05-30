package org.fraudDetection.controllers;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

public final class FraudController {

    private FraudController() {}

    public static void handle(ConnectionState state) {
        if (FraudRequestParser.parse(state) != FraudRequestParser.PARSE_OK) {
            state.fraudCount = 0;                       // fail-open (unchanged)
        } else {
            // Onda 7 v2: EXACT KD-tree + BBF + double rerank. KdTree builds the
            // permuted i16 query from state.queryVector internally.
            KdTree.searchStatic(state);
        }
        HttpResponseWriter.writeFraudScore(state, state.fraudCount);
        // Onda 31: I/O agora é do NioServer (write inline; OP_WRITE só em parcial).
    }
}
