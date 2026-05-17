package org.fraudDetection.tools;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.knn.HnswIndex;

public final class Prebuild {
    private Prebuild() {}
    public static void main(String[] args) throws Exception {
        String d = args.length > 0 ? args[0]
                 : System.getProperty("DATA_PATH",
                     System.getenv().getOrDefault("DATA_PATH", "src/main/resources"));
        long t0 = System.currentTimeMillis();
        MmapDataset.load(d + "/references.json.gz", d + "/references.bin");
        System.out.println("references.bin pronto: " + MmapDataset.count + " vetores");
        HnswIndex.load(d + "/hnsw.bin");                 // self-bootstrap → grava RBH2
        System.out.println("hnsw.bin (RBH2) pronto em "
                + ((System.currentTimeMillis() - t0) / 1000) + "s");
    }
}
