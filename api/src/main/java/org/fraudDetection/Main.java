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
