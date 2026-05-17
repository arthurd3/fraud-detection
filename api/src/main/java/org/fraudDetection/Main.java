package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswIndex;
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
        MmapDataset.load(d + "/references.json.gz", d + "/references.bin");
        System.out.println("dataset loaded: " + MmapDataset.count
                + " vectors (" + (System.currentTimeMillis() - t0) + " ms)");
        HnswIndex.load(d + "/hnsw.bin");
        System.out.println("hnsw pronto");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        new NioServer(port).start();
    }
}