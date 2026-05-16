package org.fraudDetection;

import org.fraudDetection.dataset.MmapDataset;
import org.fraudDetection.server.NioServer;

public class Main {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        MmapDataset.load("src/main/resources/references.json.gz",
                         "src/main/resources/references.bin");
        System.out.println("dataset loaded: " + MmapDataset.count
                + " vectors (" + (System.currentTimeMillis() - t0) + " ms)");

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        new NioServer(port).start();
    }
}
