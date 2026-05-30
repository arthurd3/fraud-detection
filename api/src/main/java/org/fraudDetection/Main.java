package org.fraudDetection;

import org.fraudDetection.knn.KdTree;
import org.fraudDetection.server.NioServer;

public class Main {
    static String dataPath() {
        String p = System.getProperty("DATA_PATH");
        if (p == null) p = System.getenv("DATA_PATH");
        return (p == null || p.isEmpty()) ? "src/main/resources" : p;
    }
    public static void main(String[] args) throws Exception {
        String d = dataPath();
        // FASE 0 (link-proof, TEMPORÁRIO): prova que o staticlib Rust linka e
        // roda no binário native-image de PRODUÇÃO. Removido na Fase 1.
        int ping = org.fraudDetection.rust.RustSearch.fdPing(2, 3);
        System.out.println("FASE0 fd_ping(2,3)=" + ping);
        if (ping != 5) { System.err.println("FASE0 FAIL"); System.exit(3); }
        System.out.println("FASE0 OK");
        long t0 = System.currentTimeMillis();
        // Onda 7 v2: EXACT KD-tree (RKD3) mmap. Replaces the legacy int8
        // MmapDataset + HNSW load — the fraud decision is now byte-identical
        // to the official ground truth.
        KdTree.load(d + "/references.kdt");
        System.out.println("kdtree loaded: " + KdTree.INSTANCE.size()
                + " nós (" + (System.currentTimeMillis() - t0) + " ms)");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        new NioServer(port).start();
    }
}
