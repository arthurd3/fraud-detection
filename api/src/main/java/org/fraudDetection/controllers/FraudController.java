package org.fraudDetection.controllers;

import org.fraudDetection.json.FraudRequestParser;
import org.fraudDetection.knn.KdTree;
import org.fraudDetection.rust.RustSearch;
import org.fraudDetection.server.ConnectionState;
import org.fraudDetection.server.HttpResponseWriter;

public final class FraudController {

    /**
     * Motor de busca em Rust (port byte-idêntico de {@link KdTree#search}) quando
     * {@code FD_RUST_SEARCH=1}. Ausente → caminho Java (oráculo/rollback). E=0
     * provado nas 54.100 (cargo agreement). Lido 1× no class-load.
     */
    static final boolean RUST_SEARCH = rustSearchEnabled();

    private static boolean rustSearchEnabled() {
        String v = System.getenv("FD_RUST_SEARCH");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    private FraudController() {}

    public static void handle(ConnectionState state) {
        if (FraudRequestParser.parse(state) != FraudRequestParser.PARSE_OK) {
            state.fraudCount = 0;                       // fail-open (unchanged)
        } else if (RUST_SEARCH) {
            // Motor Rust: passa o queryVector[14] semântico, recebe o fraudCount.
            state.fraudCount = RustSearch.searchVector(state.queryVector);
        } else {
            // Onda 7 v2: EXACT KD-tree + BBF + double rerank (caminho Java/oráculo).
            KdTree.searchStatic(state);
        }
        HttpResponseWriter.writeFraudScore(state, state.fraudCount);
        // Onda 31: I/O agora é do NioServer (write inline; OP_WRITE só em parcial).
    }
}
